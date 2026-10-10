import { makeHandler } from "./handler.ts";

function assert(value: unknown, message = "Assertion failed"): asserts value {
  if (!value) throw new Error(message);
}
const jwt = (sid = "auth-session") =>
  `header.${btoa(JSON.stringify({ session_id: sid }))}.signature`;
function fixture(
  options: {
    valid?: boolean;
    bound?: boolean;
    revoked?: boolean;
    expired?: boolean;
    identity?: string;
  } = {},
) {
  const calls: { url: string; init: any }[] = [],
    filters: Record<string, any> = {};
  let generatedEmail = "";
  let inserted: any, deleted = false, signedOut = false;
  const session = {
    access_token: jwt(),
    refresh_token: "refresh",
    expires_at: Math.floor(Date.now() / 1000) + 3600,
    user: { id: "user" },
  };
  const query: any = {
    select: () => query,
    eq: (key: string, val: any) => {
      filters[key] = val;
      return query;
    },
    gt: (key: string, val: any) => {
      filters[key] = val;
      return query;
    },
    maybeSingle: () =>
      Promise.resolve({
        data: options.bound === false || options.expired ||
            filters.auth_session_id !== "auth-session"
          ? null
          : { store_id: 7 },
        error: null,
      }),
    insert: (data: any) => {
      inserted = data;
      return Promise.resolve({ data: null, error: null });
    },
    delete: () => {
      deleted = true;
      return query;
    },
    then: (resolve: any) => resolve({ data: null, error: null }),
  };
  const db: any = {
    rpc: () => Promise.resolve({ data: options.identity ?? null, error: null }),
    from: () => query,
    auth: {
      getUser: () =>
        Promise.resolve({
          data: { user: options.valid === false ? null : { id: "user" } },
          error: options.valid === false ? { status: 401 } : null,
        }),
      admin: {
        generateLink: (args: any) => { generatedEmail=args.email; return Promise.resolve({
            data: { properties: { hashed_token: "otp" } },
            error: null,
          });},
        signOut: () => {
          signedOut = true;
          return Promise.resolve({ error: null });
        },
      },
    },
  };
  const handler = makeHandler({
    db,
    projectUrl: "https://project.test",
    serverKey: "server-secret",
    newAuthClient: () => ({
      auth: {
        verifyOtp: () => Promise.resolve({ data: { session }, error: null }),
        refreshSession: () =>
          Promise.resolve({ data: { session }, error: null }),
      },
    }),
    fetcher: ((url: string, init: any) => {
      calls.push({ url, init });
      if (url.endsWith("/mobile/login")) {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              ok: true,
              token: "device",
              expiresAt: Date.now() + 43200000,
              account: { id: 7 },
            }),
          ),
        );
      }
      return Promise.resolve(
        new Response(
          JSON.stringify({ ok: !options.revoked, account: { id: 7 } }),
          {
            status: options.revoked ? 401 : 200,
          },
        ),
      );
    }) as typeof fetch,
  });
  const request = (
    route = "snapshot",
    extra: Record<string, string> = {},
    body?: any,
  ) =>
    new Request(
      `https://project.test/functions/v1/sadad-auth/api/mobile/${route}`,
      {
        method: body ? "POST" : "GET",
        headers: {
          Authorization: `Bearer ${jwt()}`,
          "X-Sadad-Session": "device",
          ...extra,
        },
        body: body ? JSON.stringify(body) : undefined,
      },
    );
  return {
    handler,
    request,
    calls,
    filters,
    get generatedEmail(){return generatedEmail;},
    get inserted() {
      return inserted;
    },
    get deleted() {
      return deleted;
    },
    get signedOut() {
      return signedOut;
    },
  };
}
Deno.test("رفض غياب بيانات التحقق قبل الاتصال بالخادم", async () => {
  const f = fixture();
  const result = await f.handler(
    new Request("https://project.test/api/mobile/snapshot"),
  );
  assert(result.status === 401 && f.calls.length === 0);
});
Deno.test("رفض رمز دخول غير صالح قبل الاتصال بالخادم", async () => {
  const f = fixture({ valid: false });
  assert((await f.handler(f.request())).status === 401 && f.calls.length === 0);
});
Deno.test("رفض جلسة غير مرتبطة أو منتهية", async () => {
  for (const options of [{ bound: false }, { expired: true }]) {
    const f = fixture(options);
    assert(
      (await f.handler(f.request())).status === 401 && f.calls.length === 0,
    );
  }
});
Deno.test("رمز جلسة أخرى لا يستخدم رمز الجهاز", async () => {
  const f = fixture();
  assert(
    (await f.handler(
      f.request("snapshot", {
        Authorization: `Bearer ${jwt("another-session")}`,
      }),
    )).status === 401,
  );
});
Deno.test("الطلب الخاص الصحيح يتحقق من المستخدم ويحول اعتماد الجهاز فقط", async () => {
  const f = fixture();
  assert((await f.handler(f.request())).status === 200);
  assert(f.filters.user_id === "user" && f.filters.expires_at > 0);
  assert(f.calls[0].init.headers.Authorization === "Bearer device");
});
Deno.test("رفض جلسة متجر ملغاة", async () => {
  const f = fixture({ revoked: true });
  assert((await f.handler(f.request())).status === 401);
});
Deno.test("تجديد الرمز يتحقق من الجهاز وربط جلسة Auth", async () => {
  const f = fixture({ revoked: true });
  assert(
    (await f.handler(
      f.request("auth/refresh", {}, { refreshToken: "refresh" }),
    )).status === 401,
  );
});
Deno.test("الدخول يربط المتجر الموثوق ويعيد الرموز دون كشف OTP", async () => {
  const f = fixture();
  const result = await f.handler(
    f.request("login", {}, { username: "store", password: "password" }),
  );
  const body = await result.json();
  assert(result.status === 200 && body.auth.accessToken === jwt());
  assert(
    f.inserted.store_id === 7 && f.inserted.user_id === "user" &&
      f.inserted.session_hash.length === 64,
  );
  assert(
    !JSON.stringify(body).includes("otp") &&
      !JSON.stringify(body).includes("server-secret"),
  );
});
Deno.test("الخروج يلغي الربط والجلسة", async () => {
  const f = fixture();
  assert(
    (await f.handler(f.request("logout", {}, {}))).status === 200 &&
      f.deleted && f.signedOut,
  );
});
Deno.test("رفض مسارات الإدارة والمسارات العشوائية", async () => {
  const f = fixture();
  assert(
    (await f.handler(new Request("https://project.test/api/admin/session")))
          .status === 404 && f.calls.length === 0,
  );
});

Deno.test("هوية المتجر الموثوقة لا تتغير عند تدوير مفتاح الخدمة", async()=>{const f=fixture({identity:"durable-store@auth.sadad.invalid"});const response=await f.handler(f.request("login",{}, {username:"store",password:"password"}));assert(response.status===200&&f.generatedEmail==="durable-store@auth.sadad.invalid");});
