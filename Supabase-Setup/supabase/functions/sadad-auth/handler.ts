import { createHash, createHmac } from "node:crypto";

type Json = Record<string, any>;
type Dependencies = {
  db: any;
  newAuthClient: () => any;
  projectUrl: string;
  serverKey: string;
  fetcher?: typeof fetch;
};
const headers = {
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store",
  "X-Content-Type-Options": "nosniff",
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, apikey, content-type, x-sadad-session",
  "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
};
const hash = (token: string) =>
  createHash("sha256").update(token).digest("hex");
function response(status: number, data: Json) {
  return new Response(JSON.stringify(data), { status, headers });
}
function requireData(result: any) {
  if (result.error) throw new Error("Supabase operation failed");
  return result.data;
}
// Decode only after Auth has verified the access token (getUser), or issued it itself.
function sessionId(token: string): string {
  try {
    return JSON.parse(
      atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")),
    ).session_id || "";
  } catch {
    return "";
  }
}
function authEnvelope(session: Json) {
  return {
    accessToken: session.access_token,
    refreshToken: session.refresh_token,
    expiresAt: session.expires_at * 1000,
  };
}

export function makeHandler(
  { db, newAuthClient, projectUrl, serverKey, fetcher = fetch }: Dependencies,
) {
  async function upstream(
    request: Request,
    route: string,
    token = "",
    body?: string,
  ) {
    const upstreamHeaders: Record<string, string> = {
      apikey: serverKey,
      "Content-Type": "application/json",
    };
    if (token) upstreamHeaders.Authorization = `Bearer ${token}`;
    // Preserve rate limiting information for credential verification.
    const ip = request.headers.get("x-forwarded-for");
    if (ip) upstreamHeaders["x-forwarded-for"] = ip;
    return await fetcher(`${projectUrl}/functions/v1/sadad-api/api${route}`, {
      method: request.method,
      headers: upstreamHeaders,
      body,
      redirect: "error",
      signal: AbortSignal.timeout(20000),
    });
  }
  async function binding(token: string, userId: string, authSessionId: string) {
    if (!token || !userId || !authSessionId) return null;
    return requireData(
      await db.from("sadad_auth_bindings").select("*")
        .eq("session_hash", hash(token)).eq("user_id", userId).eq(
          "auth_session_id",
          authSessionId,
        )
        .gt("expires_at", Date.now()).maybeSingle(),
    );
  }
  return async (request: Request): Promise<Response> => {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers });
    }
    const url = new URL(request.url);
    const route = url.pathname.replace(/^\/functions\/v1\/sadad-auth/, "")
      .replace(/^\/sadad-auth/, "").replace(/^\/api/, "");
    // No admin, bootstrap, arbitrary hosts, or non-mobile routes through this gateway.
    if (!/^\/mobile\/[a-z0-9/-]+$/.test(route)) {
      return response(404, { ok: false, message: "المسار غير موجود." });
    }
    try {
      const raw = ["GET", "HEAD"].includes(request.method)
        ? undefined
        : await request.text();
      if (raw && raw.length > 3_000_000) {
        return response(413, {
          ok: false,
          message: "الطلب أكبر من الحد المسموح.",
        });
      }
      if (route === "/mobile/login" && request.method === "POST") {
        const legacyResponse = await upstream(request, route, "", raw);
        const login = await legacyResponse.json();
        if (!legacyResponse.ok || !login.ok) {
          return response(legacyResponse.status, login);
        }
        if (!login.token || !login.account?.id || !login.expiresAt) {
          throw new Error("Incomplete login response");
        }
        // Opaque, server-derived identity preserves store-number login. No email is sent.
        // Only verified store credentials can reach generateLink; user metadata is never authorization.
        const identity = createHmac("sha256", serverKey).update(
          `sadad-store:${login.account.id}`,
        ).digest("hex");
        const stableEmail = requireData(await db.rpc("sadid_store_auth_identity", { p_store: login.account.id }));
        const link = requireData(
          await db.auth.admin.generateLink({
            type: "magiclink",
            email: stableEmail || `${identity}@stores.sadad.invalid`,
          }),
        );
        const authClient = newAuthClient(); // Never mutate the shared service-role client's auth session.
        const verified = requireData(
          await authClient.auth.verifyOtp({
          token_hash: link.properties.hashed_token,
            type: "email",
          }),
        );
        const session = verified.session;
        if (!session || !sessionId(session.access_token)) {
          throw new Error("Incomplete Auth session");
        }
        requireData(
          await db.from("sadad_auth_bindings").insert({
            session_hash: hash(login.token),
            user_id: session.user.id,
            auth_session_id: sessionId(session.access_token),
            store_id: login.account.id,
            expires_at: login.expiresAt,
          }),
        );
        // Do not expose Auth identities, OTPs, links, or privileged keys to the WebView.
        return response(200, { ...login, auth: authEnvelope(session) });
      }
      const deviceToken = request.headers.get("x-sadad-session") || "";
      if (route === "/mobile/auth/refresh" && request.method === "POST") {
        let body: Json;
        try {
          body = JSON.parse(raw || "{}");
        } catch {
          return response(400, {
            ok: false,
            message: "صيغة البيانات غير صحيحة.",
          });
        }
        if (
          !deviceToken || typeof body.refreshToken !== "string" ||
          !body.refreshToken
        ) return response(401, { ok: false, message: "سجّل الدخول من جديد." });
        const refreshed = await newAuthClient().auth.refreshSession({
          refresh_token: body.refreshToken,
        });
        if (refreshed.error || !refreshed.data.session) {
          return response(401, {
            ok: false,
            message: "انتهت الجلسة. سجّل الدخول من جديد.",
          });
        }
        const session = refreshed.data.session;
        if (
          !await binding(
            deviceToken,
            session.user.id,
            sessionId(session.access_token),
          )
        ) return response(401, { ok: false, message: "انتهت جلسة الجهاز." });
        // Enforce suspension, password resets, expiry and device revocation on refresh as well.
        const check = await upstream(
          new Request(request.url, { method: "GET", headers: request.headers }),
          "/mobile/session",
          deviceToken,
        );
        if (!check.ok) return response(check.status, await check.json());
        return response(200, { ok: true, auth: authEnvelope(session) });
      }
      const jwt =
        (request.headers.get("authorization") || "").match(/^Bearer\s+(.+)$/i)
          ?.[1] || "";
      if (!jwt || !deviceToken) {
        return response(401, { ok: false, message: "سجّل الدخول للمتابعة." });
      }
      const verified = await db.auth.getUser(jwt);
      if (
        verified.error &&
        (!verified.error.status || verified.error.status >= 500)
      ) throw new Error("Auth temporarily unavailable");
      const bound = verified.data.user
        ? await binding(deviceToken, verified.data.user.id, sessionId(jwt))
        : null;
      if (
        verified.error || !verified.data.user ||
        !bound
      ) {
        return response(401, {
          ok: false,
          message: "انتهت الجلسة. سجّل الدخول من جديد.",
        });
      }
      if (route !== "/mobile/logout") {
        const check = await upstream(
          new Request(request.url, { method: "GET", headers: request.headers }),
          "/mobile/session",
          deviceToken,
        );
        const status = await check.json();
        if (!check.ok) return response(check.status, status);
        if (String(status.account?.id) !== String(bound.store_id)) {
          return response(401, {
            ok: false,
            message: "جلسة المتجر غير مطابقة.",
          });
        }
        if (
          status.forcePasswordChange && route !== "/mobile/change-password" &&
          route !== "/mobile/session"
        ) {
          return response(403, {
            ok: false,
            forcePasswordChange: true,
            message: "غيّر كلمة المرور المؤقتة أولاً.",
          });
        }
      }
      if (route === "/mobile/logout" && request.method === "POST") {
        requireData(
          await db.from("sadad_auth_bindings").delete().eq(
            "session_hash",
            hash(deviceToken),
          ),
        );
        const revoked = await db.auth.admin.signOut(jwt, "local");
        if (revoked.error) throw new Error("Auth logout failed");
      }
      // Every private route also passes through the deployed store/device/permission checks.
      const result = await upstream(
        request,
        route + url.search,
        deviceToken,
        raw,
      );
      return response(result.status, await result.json());
    } catch {
      return response(502, {
        ok: false,
        message: "تعذّر الاتصال بخدمة الدخول. حاول مجددًا.",
      });
    }
  };
}
