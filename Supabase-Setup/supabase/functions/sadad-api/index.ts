import { createClient } from "supabase";
import { randomBytes, scrypt, timingSafeEqual, createHash, createCipheriv, createDecipheriv } from "node:crypto";
import { Buffer } from "node:buffer";
import { adminLedgerSnapshot, ledgerAuditEvents } from "./admin-ledger.ts";

type Json = Record<string, any>;
type Principal = { kind: "admin" | "store"; token: string; session: Json; user: Json; device: Json | null };

const supabaseUrl = Deno.env.get("SUPABASE_URL");
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
if (!supabaseUrl || !serviceRoleKey) throw new Error("Supabase Edge Function environment is missing its project credentials.");

// The service-role client is created only inside this server-side function. Never send its key to Android or the admin page.
const db: any = createClient(supabaseUrl, serviceRoleKey, { auth: { persistSession: false, autoRefreshToken: false } });

const SESSION_MS = 12 * 60 * 60 * 1000;
const LOGIN_WINDOW_MS = 15 * 60_000;
const LOGIN_BLOCK_MS = 15 * 60_000;
const ADMIN_PASSWORD_MIN = 12;
const PASSWORD_MAX = 128;
const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
  "Access-Control-Max-Age": "86400",
};

class HttpError extends Error {
  status: number;
  extra: Json;
  constructor(status: number, message: string, extra: Json = {}) { super(message); this.status = status; this.extra = extra; }
}

function text(value: unknown, max = 250): string { return String(value ?? "").trim().slice(0, max); }
function cents(value: unknown): number {
  const n = Number(value);
  if (!Number.isFinite(n) || n < 0 || n > 1_000_000_000) throw new HttpError(400, "المبلغ المدخل غير صالح.");
  return Math.round(n * 100);
}
function money(value: unknown): number { return Math.round(Number(value || 0)) / 100; }
function nowOr(value: unknown): number { const n = Number(value); return Number.isFinite(n) && n > 0 ? n : Date.now(); }
function parseJsonObject(value: unknown): Json {
  if (value && typeof value === "object" && !Array.isArray(value)) return value as Json;
  if (typeof value === "string") { try { return JSON.parse(value); } catch { return {}; } }
  return {};
}
function send(status: number, body: Json): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...CORS_HEADERS, "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" } });
}
function ok(data: Json = {}): Response { return send(200, { ok: true, ...data }); }
async function bodyJson(request: Request): Promise<Json> {
  const raw = await request.text();
  if (raw.length > 3_000_000) throw new HttpError(413, "الطلب أكبر من الحد المسموح.");
  if (!raw) return {};
  try { const value = JSON.parse(raw); if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(); return value; }
  catch { throw new HttpError(400, "صيغة البيانات غير صحيحة."); }
}
function fail(error: unknown): Response {
  if (error instanceof HttpError) return send(error.status, { ok: false, message: error.message, ...error.extra });
  console.error("Sadad Edge Function error", error);
  return send(500, { ok: false, message: "حدث خطأ داخلي." });
}
function dataOrThrow<T = any>(result: { data: T; error: any }): T {
  if (result.error) throw result.error;
  return result.data;
}
function hashToken(value: string): string { return createHash("sha256").update(value).digest("hex"); }
function bearer(request: Request): string {
  const match = (request.headers.get("authorization") || "").match(/^Bearer\s+(.+)$/i);
  return match ? match[1] : "";
}
function passwordSalt(stored: string | null = null): { salt: string; rawSalt: string; N: number } {
  const encoded = String(stored || "").match(/^scrypt\$(\d{3,8})\$([0-9a-f]{32})$/i);
  if (encoded) return { salt: stored!, rawSalt: encoded[2], N: Number(encoded[1]) };
  const rawSalt = stored || randomBytes(16).toString("hex");
  const configured = Number(Deno.env.get("PASSWORD_SCRYPT_N")) || 32768;
  const N = stored ? 32768 : Math.max(1024, Math.min(32768, 2 ** Math.floor(Math.log2(configured))));
  return { salt: N === 32768 ? rawSalt : `scrypt$${N}$${rawSalt}`, rawSalt, N };
}
async function passwordHash(password: string, storedSalt: string | null = null): Promise<{ salt: string; hash: string }> {
  const params = passwordSalt(storedSalt);
  const key = await new Promise<Buffer>((resolve, reject) => {
    scrypt(String(password), params.rawSalt, 64, { N: params.N, r: 8, p: 1, maxmem: 64 * 1024 * 1024 }, (error, derived) => error ? reject(error) : resolve(derived));
  });
  return { salt: params.salt, hash: key.toString("hex") };
}
function safeEqual(a: string, b: string): boolean {
  const left = Buffer.from(String(a || ""), "hex");
  const right = Buffer.from(String(b || ""), "hex");
  return left.length === right.length && left.length > 0 && timingSafeEqual(left, right);
}
function loginBucketKeys(request: Request, identity: string): Array<{ key: string; limit: number }> {
  // Supabase's gateway supplies forwarded client IP metadata. The account bucket remains
  // the effective limit if a caller can alter an IP header.
  const forwarded = text(request.headers.get("x-forwarded-for")?.split(",")[0], 64) || "unknown";
  const normalized = text(identity, 100).normalize("NFKC").toLocaleLowerCase("en-US");
  return [
    { key: hashToken(`login-ip:${forwarded}`), limit: 60 },
    { key: hashToken(`login-account:${normalized}`), limit: 10 },
  ];
}
async function checkLoginLimit(request: Request, identity: string): Promise<void> {
  const keys = loginBucketKeys(request, identity).map(item => item.key);
  const rows = dataOrThrow(await db.from("login_rate_limits").select("bucket_key,blocked_until").in("bucket_key", keys));
  if (rows.some((row: Json) => Number(row.blocked_until) > Date.now())) throw new HttpError(429, "محاولات دخول كثيرة. انتظر قليلًا ثم حاول مجددًا.");
}
async function recordLoginFailure(request: Request, identity: string): Promise<void> {
  const now = Date.now();
  for (const bucket of loginBucketKeys(request, identity)) {
    dataOrThrow(await db.rpc("sadad_record_login_failure", {
      p_bucket_key: bucket.key,
      p_failure_limit: bucket.limit,
      p_now: now,
      p_window_ms: LOGIN_WINDOW_MS,
      p_block_ms: LOGIN_BLOCK_MS,
    }));
  }
  await db.from("login_rate_limits").delete().lt("window_started_at", now - 24 * 60 * 60_000).lt("blocked_until", now);
}
async function clearLoginFailures(request: Request, identity: string): Promise<void> {
  const key = loginBucketKeys(request, identity)[1].key;
  dataOrThrow(await db.from("login_rate_limits").delete().eq("bucket_key", key));
}
async function makeSession(kind: "admin" | "store", principalId: number, deviceId: string | null = null): Promise<Json> {
  const token = randomBytes(32).toString("base64url");
  const expiresAt = Date.now() + SESSION_MS;
  dataOrThrow(await db.from("sessions").insert({ token_hash: hashToken(token), kind, principal_id: principalId, device_id: deviceId, expires_at: expiresAt, created_at: Date.now() }));
  return { token, expiresAt };
}
function subscriptionState(store: Json, now = Date.now()): Json {
  if (store.subscription_mode === "permanent") return { mode: "permanent", active: true, expiresAt: null, remainingMs: null };
  if (store.subscription_mode === "paused") return { mode: "paused", active: false, expiresAt: store.subscription_expires_at || null, remainingMs: Math.max(0, Number(store.subscription_paused_remaining_ms) || 0) };
  const expiresAt = Number(store.subscription_expires_at) || 0;
  return { mode: "timed", active: expiresAt > now, expiresAt: expiresAt || null, remainingMs: Math.max(0, expiresAt - now) };
}
async function refreshStoreStatus(store: Json): Promise<Json> {
  const subscription = subscriptionState(store);
  if ((!subscription.active || subscription.mode === "paused") && store.status === "active") {
    dataOrThrow(await db.from("stores").update({ status: "suspended", suspend_until: null }).eq("id", store.id));
    dataOrThrow(await db.from("sessions").delete().eq("kind", "store").eq("principal_id", store.id));
    return dataOrThrow(await db.from("stores").select("*").eq("id", store.id).single());
  }
  if (store.status === "suspended" && store.suspend_until && Number(store.suspend_until) <= Date.now() && subscription.active) {
    dataOrThrow(await db.from("stores").update({ status: "active", suspend_until: null }).eq("id", store.id));
    return dataOrThrow(await db.from("stores").select("*").eq("id", store.id).single());
  }
  return store;
}
async function requireSession(request: Request, kind: "admin" | "store"): Promise<Principal> {
  const token = bearer(request);
  if (!token) throw new HttpError(401, "سجّل الدخول للمتابعة.");
  const session = dataOrThrow(await db.from("sessions").select("*").eq("token_hash", hashToken(token)).eq("kind", kind).gt("expires_at", Date.now()).maybeSingle());
  if (!session) throw new HttpError(401, "انتهت الجلسة. سجّل الدخول من جديد.");
  let user = kind === "admin"
    ? dataOrThrow(await db.from("admins").select("id,username,force_password_change").eq("id", session.principal_id).maybeSingle())
    : dataOrThrow(await db.from("stores").select("*").eq("id", session.principal_id).maybeSingle());
  if (!user) throw new HttpError(401, "الحساب غير موجود.");
  let device: Json | null = null;
  if (kind === "store") {
    user = await refreshStoreStatus(user);
    device = session.device_id
      ? dataOrThrow(await db.from("store_devices").select("*").eq("store_id", user.id).eq("device_id", session.device_id).is("revoked_at", null).maybeSingle())
      : null;
    if (!device) {
      await db.from("sessions").delete().eq("token_hash", hashToken(token));
      throw new HttpError(401, "تم إلغاء تسجيل هذا الجهاز من الإدارة. سجّل الدخول مجددًا.");
    }
    if (user.status !== "active") throw new HttpError(423, "الحساب موقوف من الإدارة. تواصل مع الدعم.");
  }
  return { kind, token, session, user, device };
}
function requirePermission(store: Json, permission: string): void {
  if (store.force_password_change) throw new HttpError(403, "غيّر كلمة المرور المؤقتة أولاً.", { forcePasswordChange: true });
  if (parseJsonObject(store.permissions)[permission] === false) throw new HttpError(403, "هذه الميزة غير مفعّلة لحسابك من الإدارة.");
}

async function accountForStore(store: Json): Promise<Json> {
  const devicesResult = await db.from("store_devices").select("device_id", { count: "exact", head: true }).eq("store_id", store.id).is("revoked_at", null);
  if (devicesResult.error) throw devicesResult.error;
  const state = subscriptionState(store);
  return {
    id: store.id, name: store.name, username: store.username, verified: !!store.verified,
    permissions: parseJsonObject(store.permissions), debtorLimit: Number(store.debtor_limit), whatsappEnabled: !!store.whatsapp_enabled,
    subscriptionMode: state.mode, subscriptionExpiresAt: state.expiresAt, subscriptionRemainingMs: state.remainingMs,
    maxDevices: Number(store.allowed_devices || 1), boundDevices: devicesResult.count || 0,
  };
}
async function makeSnapshot(store: Json): Promise<Json> {
  const [account, contactRows, debtRows, paymentRows] = await Promise.all([
    accountForStore(store),
    db.from("contacts").select("*").eq("store_id", store.id).order("name", { ascending: true }).order("id", { ascending: true }),
    db.from("debts").select("*").eq("store_id", store.id).order("created_at", { ascending: false }).order("id", { ascending: false }),
    db.from("payments").select("*").eq("store_id", store.id).order("created_at", { ascending: false }).order("id", { ascending: false }),
  ]);
  const contacts = dataOrThrow(contactRows) as Json[];
  const debts = dataOrThrow(debtRows) as Json[];
  const payments = dataOrThrow(paymentRows) as Json[];
  const byContact = new Map<number, Json>();
  const result: Json = { contacts: [], debts: [], payments: [], transactions: [], totals: { receivable: 0, payable: 0, net: 0 }, revision: Number(store.revision), account };
  for (const c of contacts) {
    const contact = { id: Number(c.id), name: c.name, phone: c.phone, category: c.category, note: c.note, whatsappOptIn: !!c.whatsapp_opt_in,
      creditLimit: money(c.credit_limit_cents), createdAt: Number(c.created_at), createdBy: c.created_by || "", receivable: 0, payable: 0,
      net: 0, transactionCount: 0, lastActivity: 0, latestDebtId: 0 };
    result.contacts.push(contact); byContact.set(Number(c.id), contact);
  }
  const paidByDebt = new Map<number, number>();
  for (const p of payments) paidByDebt.set(Number(p.debt_id), (paidByDebt.get(Number(p.debt_id)) || 0) + Number(p.amount_cents));
  const debtById = new Map<number, Json>();
  for (const d of debts) {
    const paid = paidByDebt.get(Number(d.id)) || 0;
    const remaining = Math.max(0, Number(d.amount_cents) - paid);
    const contact = byContact.get(Number(d.contact_id));
    const debt = { id: Number(d.id), contactId: Number(d.contact_id), direction: d.direction, amount: money(d.amount_cents), paid: money(paid), remaining: money(remaining), note: d.note, dueDate: d.due_date, createdAt: Number(d.created_at), createdBy: d.created_by || "" };
    result.debts.push(debt); debtById.set(Number(d.id), d);
    if (contact) {
      contact[d.direction === "receivable" ? "receivable" : "payable"] += money(remaining);
      contact.transactionCount++;
      if (Number(d.created_at) > contact.lastActivity) { contact.lastActivity = Number(d.created_at); contact.latestDebtId = Number(d.id); }
    }
    result.transactions.push({ id: `d${d.id}`, debtId: Number(d.id), contactId: Number(d.contact_id), contactName: contact?.name || "", kind: "debt", direction: d.direction, amount: money(d.amount_cents), note: d.note, method: "", createdAt: Number(d.created_at), createdBy: d.created_by || "" });
  }
  const contactNames = new Map(contacts.map(c => [Number(c.id), String(c.name)]));
  for (const p of payments) {
    const debt = debtById.get(Number(p.debt_id));
    if (!debt) continue;
    const contactId = Number(debt.contact_id), name = contactNames.get(contactId) || "";
    const payment = { id: Number(p.id), debtId: Number(p.debt_id), contactId, contactName: name, direction: debt.direction, amount: money(p.amount_cents), method: p.method, note: p.note, createdAt: Number(p.created_at), createdBy: p.created_by || "" };
    result.payments.push(payment);
    const contact = byContact.get(contactId);
    if (contact && Number(p.created_at) > contact.lastActivity) contact.lastActivity = Number(p.created_at);
    result.transactions.push({ id: `p${p.id}`, debtId: Number(p.debt_id), contactId, contactName: name, kind: "payment", direction: debt.direction, amount: money(p.amount_cents), note: p.note, method: p.method, createdAt: Number(p.created_at), createdBy: p.created_by || "" });
  }
  for (const contact of result.contacts) {
    contact.net = contact.receivable - contact.payable;
    result.totals.receivable += contact.receivable;
    result.totals.payable += contact.payable;
  }
  result.totals.net = result.totals.receivable - result.totals.payable;
  result.transactions.sort((a: Json, b: Json) => b.createdAt - a.createdAt);
  return result;
}

function normalizedContactPhone(value: unknown): string {
  let digits = String(value ?? "").replace(/[٠-٩]/g, char => String(char.charCodeAt(0) - 1632)).replace(/[۰-۹]/g, char => String(char.charCodeAt(0) - 1776)).replace(/\D/g, "");
  if (digits.startsWith("00970")) digits = digits.slice(2);
  else if (digits.startsWith("0")) digits = `970${digits.slice(1)}`;
  return digits;
}
function timestamp(value: unknown): number {
  if (value === undefined || value === null || value === "") return Date.now();
  const n = Number(value);
  if (!Number.isSafeInteger(n) || n < 1 || n > Date.now() + 5 * 60_000) throw new HttpError(400, "يوجد تاريخ سجل غير صالح.");
  return n;
}
function assertIds(rows: Json[], label: string): Set<number> {
  const seen = new Set<number>();
  for (const row of rows) {
    const id = Number(row?.id);
    if (!Number.isSafeInteger(id) || id < 1 || seen.has(id)) throw new HttpError(400, `معرّف ${label} غير صالح أو مكرر.`);
    seen.add(id);
  }
  return seen;
}
function paymentKey(contactId: number, createdAt: number, method: string): string { return `${contactId}|${createdAt}|${method}`; }

async function syncSnapshot(store: Json, input: Json, baseRevision: unknown, device: Json): Promise<Json> {
  if (!input || !Array.isArray(input.contacts) || !Array.isArray(input.debts) || !Array.isArray(input.payments)) throw new HttpError(400, "بيانات المزامنة غير مكتملة. لم يتم تغيير السجل.");
  const contacts: Json[] = input.contacts, debts: Json[] = input.debts, payments: Json[] = input.payments;
  if (contacts.length > 50_000 || debts.length > 100_000 || payments.length > 500_000) throw new HttpError(413, "عدد السجلات أكبر من الحد.");
  if (Number(baseRevision) !== Number(store.revision)) throw new HttpError(409, "تغيّرت البيانات على جهاز آخر. حمّل النسخة الأحدث ثم أعد المحاولة.", { snapshot: await makeSnapshot(store) });

  const contactIds = assertIds(contacts, "الشخص");
  const debtIds = assertIds(debts, "الدين");
  const paymentIds = assertIds(payments, "الدفعة");
  const uniqueContacts = new Set<string>();
  for (const contact of contacts) {
    if (!text(contact.name, 120)) throw new HttpError(400, "يوجد شخص بلا اسم.");
    timestamp(contact.createdAt); cents(contact.creditLimit || 0);
    const key = `${text(contact.name, 120).normalize("NFKC").toLocaleLowerCase("ar")} | ${normalizedContactPhone(contact.phone)}`;
    if (uniqueContacts.has(key)) throw new HttpError(400, "يوجد اسم ورقم هاتف مكرران في سجل المتجر. راجع قائمة الأشخاص قبل المزامنة.");
    uniqueContacts.add(key);
  }

  const previous = await makeSnapshot(store);
  const oldContacts = new Map<number, Json>(previous.contacts.map((row: Json) => [Number(row.id), row]));
  const oldDebts = new Map<number, Json>(previous.debts.map((row: Json) => [Number(row.id), row]));
  const oldPayments = new Map<number, Json>(previous.payments.map((row: Json) => [Number(row.id), row]));
  const removedContacts = previous.contacts.filter((row: Json) => !contactIds.has(Number(row.id)));
  const removedDebts = previous.debts.filter((row: Json) => !debtIds.has(Number(row.id)));
  const removedPayments = previous.payments.filter((row: Json) => !paymentIds.has(Number(row.id)));
  const addedContacts = contacts.filter(row => !oldContacts.has(Number(row.id)));
  const addedDebts = debts.filter(row => !oldDebts.has(Number(row.id)));
  const addedPayments = payments.filter(row => !oldPayments.has(Number(row.id)));
  const devicePermissions = { registerPayments: true, deleteRecords: true, deleteContacts: true, ...parseJsonObject(device.permissions) };
  if (removedContacts.length && !devicePermissions.deleteContacts) throw new HttpError(403, `الأدمن منع حذف الأشخاص لهذا الجهاز (${device.staff_name || "مستخدم الجهاز"}).`);
  if ((removedDebts.length || removedPayments.length || (removedContacts.length && previous.debts.some((row: Json) => removedContacts.some((c: Json) => Number(c.id) === Number(row.contactId))))) && !devicePermissions.deleteRecords) throw new HttpError(403, `الأدمن منع حذف السجلات لهذا الجهاز (${device.staff_name || "مستخدم الجهاز"}).`);
  if (addedPayments.length && !devicePermissions.registerPayments) throw new HttpError(403, `الأدمن منع تسجيل الدفعات لهذا الجهاز (${device.staff_name || "مستخدم الجهاز"}).`);
  if (addedPayments.length || removedPayments.length) requirePermission(store, "payments");

  for (const debt of debts) {
    if (!contactIds.has(Number(debt.contactId))) throw new HttpError(400, "دين مرتبط بشخص غير موجود.");
    if (!["receivable", "payable"].includes(debt.direction)) throw new HttpError(400, "اتجاه الدين غير صالح.");
    const amount = cents(debt.amount);
    if (amount < 1) throw new HttpError(400, "يجب أن يكون مبلغ الدين أكبر من صفر.");
    const created = timestamp(debt.createdAt);
    if (debt.dueDate && !/^\d{4}-\d{2}-\d{2}$/.test(text(debt.dueDate, 20))) throw new HttpError(400, "موعد الاستحقاق غير صالح.");
    const old = oldDebts.get(Number(debt.id));
    if (old && (Number(old.contactId) !== Number(debt.contactId) || old.direction !== debt.direction || cents(old.amount) !== amount || text(old.note, 500) !== text(debt.note, 500) || text(old.dueDate, 20) !== text(debt.dueDate, 20) || Number(old.createdAt) !== created)) throw new HttpError(403, "لا يمكن تعديل دين مسجل مباشرة. احذف السجل بصلاحية الإدارة ثم أضف سجلًا صحيحًا.");
  }
  for (const payment of payments) {
    if (!debtIds.has(Number(payment.debtId))) throw new HttpError(400, "دفعة مرتبطة بدين غير موجود.");
    const amount = cents(payment.amount), method = payment.method || "cash", created = timestamp(payment.createdAt);
    if (amount < 1) throw new HttpError(400, "يجب أن يكون مبلغ الدفعة أكبر من صفر.");
    if (!["cash", "bank", "wallet"].includes(method)) throw new HttpError(400, "طريقة الدفع غير صالحة.");
    const old = oldPayments.get(Number(payment.id));
    if (old && (Number(old.debtId) !== Number(payment.debtId) || cents(old.amount) !== amount || old.method !== method || text(old.note, 500) !== text(payment.note, 500) || Number(old.createdAt) !== created)) throw new HttpError(403, "لا يمكن تعديل دفعة مسجلة مباشرة. احذف السجل بصلاحية الإدارة ثم أضف سجلًا صحيحًا.");
  }
  for (const contact of removedContacts) {
    const previousContact = oldContacts.get(Number(contact.id));
    if (Number(previousContact?.receivable || 0) > 0 || Number(previousContact?.payable || 0) > 0) throw new HttpError(409, `لا يمكن حذف ${text(contact.name, 120)} لوجود دين متبقٍ. سجّل التسديد أولًا.`);
    if (debts.some(row => Number(row.contactId) === Number(contact.id))) throw new HttpError(400, "يجب حذف سجل الشخص كاملًا قبل حذف الشخص.");
  }
  const removedDebtIds = new Set(removedDebts.map((row: Json) => Number(row.id)));
  for (const debt of removedDebts) {
    if (Number(debt.remaining) > 0) throw new HttpError(409, "لا يمكن حذف دين ما زال عليه مبلغ متبقٍ.");
    if (!removedDebtIds.has(Number(debt.id))) throw new HttpError(400, "تعذر التحقق من حذف الدين.");
  }

  const paidByDebt = new Map<number, number>();
  for (const payment of payments) paidByDebt.set(Number(payment.debtId), (paidByDebt.get(Number(payment.debtId)) || 0) + cents(payment.amount));
  const activeDebtors = new Set<number>();
  for (const debt of debts) {
    const remaining = cents(debt.amount) - (paidByDebt.get(Number(debt.id)) || 0);
    if (remaining < 0) throw new HttpError(400, "مجموع دفعات دين تجاوز أصل الدين.");
    if (debt.direction === "receivable" && remaining > 0) activeDebtors.add(Number(debt.contactId));
  }
  if (activeDebtors.size > Number(store.debtor_limit)) throw new HttpError(403, `تجاوزت حد ${store.debtor_limit} مدينًا الذي حدده الأدمن.`);

  const receivedAt = Date.now();
  const contactByDebt = new Map(debts.map(row => [Number(row.id), Number(row.contactId)]));
  const newPaymentGroups = new Map<string, { contactId: number; createdAt: number; method: string; amountCents: number }>();
  for (const payment of addedPayments) {
    const createdAt = nowOr(payment.createdAt), method = payment.method || "cash", contactId = contactByDebt.get(Number(payment.debtId))!;
    const key = paymentKey(contactId, createdAt, method), group = newPaymentGroups.get(key) || { contactId, createdAt, method, amountCents: 0 };
    group.amountCents += cents(payment.amount); newPaymentGroups.set(key, group);
  }
  const recentCutoff = receivedAt - 60_000;
  const recentRows = dataOrThrow(await db.from("payments").select("id,debt_id,amount_cents,method,created_at,server_received_at")
    .eq("store_id", store.id).or(`server_received_at.gte.${recentCutoff},and(server_received_at.eq.0,created_at.gte.${recentCutoff})`));
  const recentGroups = new Map<string, { contactId: number; createdAt: number; receivedAt: number; method: string; amountCents: number }>();
  for (const row of recentRows as Json[]) {
    const debt = oldDebts.get(Number(row.debt_id));
    if (!debt) continue;
    const contactId = Number(debt.contactId), method = row.method || "cash", createdAt = Number(row.created_at), key = paymentKey(contactId, createdAt, method);
    const group = recentGroups.get(key) || { contactId, createdAt, receivedAt: Number(row.server_received_at || row.created_at), method, amountCents: 0 };
    group.amountCents += Number(row.amount_cents); recentGroups.set(key, group);
  }
  const candidates = [...newPaymentGroups.values()];
  for (let i = 0; i < candidates.length; i++) {
    const candidate = candidates[i];
    const prior = [...recentGroups.values()].some(row => row.contactId === candidate.contactId && row.method === candidate.method && row.amountCents === candidate.amountCents && row.receivedAt <= receivedAt && receivedAt - row.receivedAt <= 60_000);
    const sameBatch = candidates.slice(i + 1).some(row => row.contactId === candidate.contactId && row.method === candidate.method && row.amountCents === candidate.amountCents && Math.abs(row.createdAt - candidate.createdAt) <= 60_000);
    if (prior || sameBatch) throw new HttpError(400, "توجد دفعة مطابقة مسجلة لهذا الشخص خلال آخر دقيقة. انتظر دقيقة قبل إعادة المحاولة لتجنب التكرار.");
  }

  const actor = text(device.staff_name, 80) || "صاحب المتجر";
  const contactName = new Map(contacts.map(row => [Number(row.id), text(row.name, 120)]));
  const editedContacts = contacts.filter(row => {
    const old = oldContacts.get(Number(row.id));
    return old && (text(old.name, 120) !== text(row.name, 120) || text(old.phone, 40) !== text(row.phone, 40) || text(old.category, 40) !== text(row.category, 40) || text(old.note, 500) !== text(row.note, 500) || !!old.whatsappOptIn !== (row.whatsappOptIn === true) || cents(old.creditLimit || 0) !== cents(row.creditLimit || 0));
  });
  const removedArchiveRows = removedContacts.map((contact: Json) => {
    const contactDebts = previous.debts.filter((row: Json) => Number(row.contactId) === Number(contact.id));
    const ids = new Set(contactDebts.map((row: Json) => Number(row.id)));
    const contactPayments = previous.payments.filter((row: Json) => ids.has(Number(row.debtId)));
    return { contact, debts: contactDebts, payments: contactPayments };
  });
  const audit: Array<{ action: string; description: string }> = [];
  for (const row of addedContacts) audit.push({ action: "إضافة شخص", description: `أضاف ${text(row.name, 120)} إلى الأشخاص.` });
  for (const row of editedContacts) audit.push({ action: "تعديل شخص", description: `عدّل بيانات ${text(row.name, 120)}.` });
  for (const row of addedDebts) audit.push({ action: "تسجيل دين", description: `سجّل دينًا بقيمة ${money(cents(row.amount))} ₪ على ${contactName.get(Number(row.contactId)) || "شخص"}.` });
  for (const row of addedPayments) audit.push({ action: "تسجيل دفعة", description: `سجّل دفعة بقيمة ${money(cents(row.amount))} ₪ لـ ${contactName.get(contactByDebt.get(Number(row.debtId))!) || "شخص"}.` });
  for (const row of removedContacts) audit.push({ action: "حذف زبون", description: `حذف ${text(row.name, 120)} وسجله.` });
  for (const row of removedDebts) audit.push({ action: "حذف سجل", description: `حذف سجل دين لـ ${contactName.get(Number(row.contactId)) || "شخص"}.` });
  for (const row of removedPayments) audit.push({ action: "حذف سجل", description: `حذف دفعة لـ ${text(row.contactName, 120) || "شخص"}.` });
  if (audit.length > 300) audit.push({ action: "ملخص حركات", description: `تم تسجيل ${audit.length} حركة في مزامنة واحدة.` });

  const commit = await db.rpc("sadad_commit_store_snapshot", {
    p_store_id: store.id,
    p_base_revision: Number(baseRevision),
    p_snapshot: { contacts, debts, payments },
    p_previous_snapshot: previous,
    p_removed_contacts: removedArchiveRows,
    p_audit_events: audit.slice(0, 300),
    p_actor: actor,
    p_device_id: device.device_id || "",
    p_received_at: receivedAt,
  });
  if (commit.error) {
    if (commit.error.code === "P0001" && commit.error.message === "revision_conflict") {
      const latest = dataOrThrow(await db.from("stores").select("*").eq("id", store.id).single());
      throw new HttpError(409, "تغيّرت البيانات على جهاز آخر. حمّل النسخة الأحدث ثم أعد المحاولة.", { snapshot: await makeSnapshot(latest) });
    }
    if (commit.error.code === "P0001" && commit.error.message === "store_not_found") throw new HttpError(404, "الحساب غير موجود.");
    throw commit.error;
  }
  const latest = dataOrThrow(await db.from("stores").select("*").eq("id", store.id).single());
  return { revision: Number(commit.data), snapshot: await makeSnapshot(latest) };
}

async function adminStoreList(auth: Principal, includeArchived = false): Promise<Json[]> {
  const rows: Json[] = dataOrThrow(await db.from("stores").select("*").order("created_at", { ascending: false }));
  const visible = rows.filter(row => includeArchived || row.status !== "deleted");
  for (let index=0;index<visible.length;index++) if (visible[index].status !== "deleted") visible[index]=await refreshStoreStatus(visible[index]);
  const legacy: Json[] = dataOrThrow(await db.rpc("sadad_admin_store_list"));
  const summaries: Json[] = dataOrThrow(await db.rpc("sadid_admin_summaries", { p_admin_hash: hashToken(auth.token), p_stores: visible.map(row => Number(row.id)) }));
  const installations: Json[] = dataOrThrow(await db.rpc("sadid_pending_installations", { p_admin_hash: hashToken(auth.token) }));
  return visible.map(row => {
    const old = legacy.find(item => Number(item.id) === Number(row.id)) || {};
    const summary = summaries.find(item => Number(item.storeId) === Number(row.id)) || {};
    const phones = installations.filter(item => Number(item.storeId) === Number(row.id));
    return { ...publicStore(row), contacts: Number(old.contacts || 0), debts: Number(old.debts || 0), debtTotal: Number(old.debt_total || 0),
      ...summary, ledgerMode: summary.initialized ? "current" : "legacy", archived: row.status === "deleted",
      loginDevices: Number(old.device_count || 0), boundDevices: phones.filter(item => item.status === "active").length,
      pendingDevices: phones.filter(item => item.status === "pending").length };
  });
}

async function adminAccountAction(auth: Principal, storeId: number, action: string, reason: string): Promise<Json> {
  return dataOrThrow(await db.rpc("sadid_admin_account_action", { p_admin_hash: hashToken(auth.token), p_store: storeId, p_action: action, p_reason: reason }));
}

function publicStore(store: Json): Json {
  const state = subscriptionState(store);
  return { id: Number(store.id), name: store.name, username: store.username, status: store.status, suspendUntil: store.suspend_until,
    verified: !!store.verified, debtorLimit: Number(store.debtor_limit), permissions: parseJsonObject(store.permissions),
    whatsappEnabled: !!store.whatsapp_enabled, revision: Number(store.revision), createdAt: Number(store.created_at),
    subscriptionMode: state.mode, subscriptionStartedAt: store.subscription_started_at || null,
    subscriptionExpiresAt: state.expiresAt, subscriptionRemainingMs: state.remainingMs, maxDevices: Number(store.allowed_devices || 1) };
}
async function listDevices(storeId: number): Promise<Json[]> {
  const rows = dataOrThrow(await db.from("store_devices").select("device_id,label,staff_name,permissions,first_seen_at,last_seen_at,revoked_at").eq("store_id", storeId).order("last_seen_at", { ascending: false }));
  return rows.map((row: Json) => ({ id: row.device_id, label: row.label, staffName: row.staff_name || "", permissions: { registerPayments: true, deleteRecords: true, deleteContacts: true, ...parseJsonObject(row.permissions) }, firstSeenAt: row.first_seen_at, lastSeenAt: row.last_seen_at, revokedAt: row.revoked_at }));
}
async function listBackups(storeId: number): Promise<Json[]> {
  const rows = dataOrThrow(await db.from("store_backups").select("id,created_at,reason,contact_count,debt_count,payment_count").eq("store_id", storeId).order("created_at", { ascending: false }).order("id", { ascending: false }).limit(100));
  return rows.map((row: Json) => ({ id: Number(row.id), createdAt: Number(row.created_at), reason: row.reason, contactCount: row.contact_count, debtCount: row.debt_count, paymentCount: row.payment_count }));
}
async function listContactArchives(storeId: number): Promise<Json[]> {
  const rows = dataOrThrow(await db.from("contact_archives").select("id,contact_id,contact_name,archived_at,source_revision,debt_count,payment_count,restored_at").eq("store_id", storeId).order("archived_at", { ascending: false }).order("id", { ascending: false }));
  return rows.map((row: Json) => ({ id: Number(row.id), contactId: Number(row.contact_id), contactName: row.contact_name, archivedAt: Number(row.archived_at), sourceRevision: Number(row.source_revision), debtCount: row.debt_count, paymentCount: row.payment_count, restoredAt: row.restored_at }));
}
async function enforceDeviceCap(storeId: number, limit: number): Promise<void> {
  const devices = dataOrThrow(await db.from("store_devices").select("device_id").eq("store_id", storeId).is("revoked_at", null).order("last_seen_at", { ascending: false }).order("first_seen_at", { ascending: false }).order("device_id", { ascending: true }));
  for (const device of devices.slice(Math.max(1, limit))) {
    const now = Date.now();
    dataOrThrow(await db.from("store_devices").update({ revoked_at: now }).eq("store_id", storeId).eq("device_id", device.device_id).is("revoked_at", null));
    dataOrThrow(await db.from("sessions").delete().eq("kind", "store").eq("principal_id", storeId).eq("device_id", device.device_id));
  }
}
function parseDeviceLimit(value: unknown, fallback = 1): number {
  if (value === undefined || value === null) return fallback;
  const digits = String(value).trim().replace(/[٠-٩]/g, digit => String("٠١٢٣٤٥٦٧٨٩".indexOf(digit))).replace(/[۰-۹]/g, digit => String("۰۱۲۳۴۵۶۷۸۹".indexOf(digit)));
  const limit = Number(digits);
  if (!digits || !Number.isSafeInteger(limit) || limit < 1 || limit > 50) throw new HttpError(400, "عدد الأجهزة المسموح بها يجب أن يكون من 1 إلى 50.");
  return limit;
}
function encryptionKey(): Buffer {
  const raw = Deno.env.get("TOKEN_ENCRYPTION_KEY") || "";
  if (/^[0-9a-f]{64}$/i.test(raw)) return Buffer.from(raw, "hex");
  if (raw) { const key = Buffer.from(raw, "base64"); if (key.length === 32) return key; }
  throw new HttpError(503, "لم يضبط الخادم مفتاح تشفير ربط واتساب.");
}
function encryptSecret(value: string): string {
  const iv = randomBytes(12), cipher = createCipheriv("aes-256-gcm", encryptionKey(), iv);
  const data = Buffer.concat([cipher.update(value, "utf8"), cipher.final()]);
  return Buffer.concat([iv, cipher.getAuthTag(), data]).toString("base64");
}
function decryptSecret(value: string): string {
  const data = Buffer.from(value, "base64"), iv = data.subarray(0, 12), tag = data.subarray(12, 28), encrypted = data.subarray(28);
  const decipher = createDecipheriv("aes-256-gcm", encryptionKey(), iv); decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(encrypted), decipher.final()]).toString("utf8");
}

let dummyCredentialSalt = "";
function dummySalt(): string { if (!dummyCredentialSalt) dummyCredentialSalt = passwordSalt().salt; return dummyCredentialSalt; }
function safeUsername(value: string): boolean { return /^[\p{L}\p{N}._-]{3,80}$/u.test(value); }
async function findAccount(table: "admins" | "stores", username: string): Promise<Json | null> {
  if (!safeUsername(username)) return null;
  return dataOrThrow(await db.from(table).select("*").ilike("username", username).maybeSingle());
}
async function countAdmins(): Promise<number> {
  const result = await db.from("admins").select("id", { count: "exact", head: true });
  if (result.error) throw result.error;
  return result.count || 0;
}
async function route(request: Request): Promise<Response> {
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS_HEADERS });
  const url = new URL(request.url);
  let pathname = url.pathname;
  const functionMarker = "/sadad-api";
  const markerAt = pathname.indexOf(functionMarker);
  if (markerAt >= 0) pathname = pathname.slice(markerAt + functionMarker.length) || "/";
  if (pathname.startsWith("/api/")) pathname = pathname.slice(4);
  if (pathname === "/api") pathname = "/";

  if (request.method === "GET" && pathname === "/health") return ok({ service: "sadad", version: "2.0.0", storage: "supabase-postgres" });
  if (request.method === "GET" && pathname === "/setup/status") return ok({ bootstrapRequired: (await countAdmins()) === 0 });
  if (request.method === "POST" && pathname === "/setup/admin") {
    await checkLoginLimit(request, "admin-setup");
    const body = await bodyJson(request);
    if (await countAdmins() > 0) throw new HttpError(409, "تم إنشاء مدير النظام مسبقًا.");
    const configuredKey = Deno.env.get("INITIAL_ADMIN_KEY") || "";
    const providedHash = hashToken(text(body.setupKey, 200));
    const configuredHash = hashToken(configuredKey);
    if (!configuredKey || !safeEqual(providedHash, configuredHash)) {
      await recordLoginFailure(request, "admin-setup");
      throw new HttpError(403, "مفتاح التأسيس غير صحيح.");
    }
    const username = text(body.username, 80), password = String(body.password || "");
    if (!safeUsername(username) || password.length < ADMIN_PASSWORD_MIN || password.length > PASSWORD_MAX) throw new HttpError(400, `اسم المستخدم يجب أن يكون صالحًا وكلمة مرور الأدمن بين ${ADMIN_PASSWORD_MIN} و${PASSWORD_MAX} خانة.`);
    const hashed = await passwordHash(password);
    const admin = dataOrThrow(await db.from("admins").insert({ username, salt: hashed.salt, password_hash: hashed.hash, force_password_change: true, created_at: Date.now() }).select("id,username,force_password_change").single());
    await clearLoginFailures(request, "admin-setup");
    return ok({ ...(await makeSession("admin", Number(admin.id))), admin: { username: admin.username }, forcePasswordChange: true });
  }

  if (request.method === "POST" && pathname === "/admin/login") {
    const body = await bodyJson(request), username = text(body.username, 80), identity = `admin:${username}`;
    await checkLoginLimit(request, identity);
    const admin = await findAccount("admins", username), password = String(body.password || ""), withinLimit = password.length <= PASSWORD_MAX;
    const hashed = await passwordHash(withinLimit ? password : password.slice(0, PASSWORD_MAX), admin?.salt || dummySalt());
    if (!admin || !withinLimit || !safeEqual(hashed.hash, admin.password_hash)) {
      await recordLoginFailure(request, identity);
      throw new HttpError(401, "اسم المستخدم أو كلمة المرور غير صحيحة.");
    }
    await clearLoginFailures(request, identity);
    return ok({ ...(await makeSession("admin", Number(admin.id))), admin: { username: admin.username }, forcePasswordChange: !!admin.force_password_change });
  }
  if (request.method === "POST" && pathname === "/mobile/login") {
    const body = await bodyJson(request), username = text(body.username, 80), identity = `store:${username}`;
    await checkLoginLimit(request, identity);
    let store = await findAccount("stores", username);
    const password = String(body.password || ""), withinLimit = password.length <= PASSWORD_MAX;
    const hashed = await passwordHash(withinLimit ? password : password.slice(0, PASSWORD_MAX), store?.salt || dummySalt());
    if (!store || !withinLimit || !safeEqual(hashed.hash, store.password_hash)) {
      await recordLoginFailure(request, identity);
      throw new HttpError(401, "اسم المستخدم أو كلمة المرور غير صحيحة.");
    }
    await clearLoginFailures(request, identity);
    store = await refreshStoreStatus(store);
    if (store.status !== "active") {
      const state = subscriptionState(store);
      if (state.mode === "paused") throw new HttpError(423, "الاشتراك موقوف. يفعّل الأدمن الاشتراك أو يجدده.");
      if (state.mode === "timed" && !state.active) throw new HttpError(423, "انتهت مدة الاشتراك. تواصل مع الأدمن لتجديده.");
      throw new HttpError(423, "الحساب مجمّد من الأدمن. تواصل معه لإعادة التفعيل.");
    }
    const deviceId = text(body.deviceId, 120), deviceLabel = text(body.deviceName, 120), requestedStaff = text(body.staffName, 80);
    if (!/^[A-Za-z0-9._:-]{16,120}$/.test(deviceId)) throw new HttpError(400, "تعذر التعرف على هذا الجهاز. حدّث التطبيق وحاول مجددًا.");
    const existing = dataOrThrow(await db.from("store_devices").select("*").eq("store_id", store.id).eq("device_id", deviceId).maybeSingle());
    if (existing?.revoked_at) throw new HttpError(403, "تم إلغاء هذا الجهاز من الأدمن. اطلب السماح به مجددًا.");
    const requiresStaffName = Number(store.allowed_devices || 1) > 1;
    if (requiresStaffName && (!existing || !existing.staff_name) && requestedStaff.length < 2) throw new HttpError(409, "أدخل اسم الشخص الذي سيستخدم هذا الجهاز ليظهر اسمه في السجلات. لا يمكن تغييره بعد التسجيل إلا من الأدمن.");
    if (requiresStaffName && requestedStaff.length > 0 && (requestedStaff.length < 2 || /[\u0000-\u001f]/.test(requestedStaff))) throw new HttpError(400, "اكتب اسمًا من خانتين على الأقل بدون رموز تحكم.");
    const staffName = existing?.staff_name || (requiresStaffName ? requestedStaff : "");
    const now = Date.now(), token = randomBytes(32).toString("base64url"), expiresAt = now + SESSION_MS;
    const createdSession = await db.rpc("sadad_create_store_session", {
      p_store_id: Number(store.id), p_token_hash: hashToken(token), p_expires_at: expiresAt, p_now: now,
      p_device_id: deviceId, p_device_label: deviceLabel, p_staff_name: staffName,
    });
    if (createdSession.error) {
      if (createdSession.error.code === "P0001" && createdSession.error.message === "device_limit") throw new HttpError(403, `وصل الحساب إلى حد الأجهزة المسموح (${store.allowed_devices}). اطلب من الأدمن زيادة العدد أو السماح بهذا الجهاز.`);
      if (createdSession.error.code === "P0001" && createdSession.error.message === "device_revoked") throw new HttpError(403, "تم إلغاء هذا الجهاز من الأدمن. اطلب السماح به مجددًا.");
      if (createdSession.error.code === "P0001" && createdSession.error.message === "device_staff_required") throw new HttpError(409, "أدخل اسم الشخص الذي سيستخدم هذا الجهاز ليظهر اسمه في السجلات.");
      if (createdSession.error.code === "P0001" && createdSession.error.message === "store_inactive") throw new HttpError(423, "الحساب موقوف من الإدارة. تواصل مع الدعم.");
      throw createdSession.error;
    }
    const session = { token, expiresAt }, deviceRecord = createdSession.data;
    store = dataOrThrow(await db.from("stores").select("*").eq("id", store.id).single());
    if (!store) throw new HttpError(401, "جلسة المتجر غير صالحة.");
    const snapshot = await makeSnapshot(store);
    const deviceInfo = { staffName: deviceRecord.staff_name || "صاحب المتجر", permissions: { registerPayments: true, deleteRecords: true, deleteContacts: true, ...parseJsonObject(deviceRecord.permissions) } };
    return ok({ ...session, forcePasswordChange: !!store.force_password_change, snapshot, account: snapshot.account, device: deviceInfo, deviceStaffName: deviceInfo.staffName });
  }

  if (request.method === "POST" && pathname === "/admin/logout") {
    const auth = await requireSession(request, "admin");
    dataOrThrow(await db.from("sessions").delete().eq("token_hash", hashToken(auth.token)));
    return ok();
  }
  if (request.method === "POST" && pathname === "/mobile/logout") {
    const auth = await requireSession(request, "store");
    dataOrThrow(await db.from("sessions").delete().eq("token_hash", hashToken(auth.token)));
    return ok();
  }
  if (pathname.startsWith("/admin/")) {
    const auth = await requireSession(request, "admin");
    if (request.method === "GET" && pathname === "/admin/session") return ok({ admin: { username: auth.user.username }, forcePasswordChange: !!auth.user.force_password_change });
    if (request.method === "POST" && pathname === "/admin/change-password") {
      const body = await bodyJson(request), currentPassword = String(body.currentPassword || ""), password = String(body.password || "");
      if (password.length < ADMIN_PASSWORD_MIN || password.length > PASSWORD_MAX) throw new HttpError(400, `كلمة المرور الجديدة يجب أن تكون بين ${ADMIN_PASSWORD_MIN} و${PASSWORD_MAX} محرفًا.`);
      if (currentPassword.length > PASSWORD_MAX) throw new HttpError(403, "كلمة المرور الحالية غير صحيحة.");
      const admin = dataOrThrow(await db.from("admins").select("*").eq("id", auth.user.id).single());
      const current = await passwordHash(currentPassword, admin.salt);
      if (!safeEqual(current.hash, admin.password_hash)) throw new HttpError(403, "كلمة المرور الحالية غير صحيحة.");
      const next = await passwordHash(password);
      dataOrThrow(await db.from("admins").update({ salt: next.salt, password_hash: next.hash, force_password_change: false }).eq("id", admin.id));
      dataOrThrow(await db.from("sessions").delete().eq("kind", "admin").eq("principal_id", admin.id).neq("token_hash", hashToken(auth.token)));
      return ok({ forcePasswordChange: false });
    }
    if (auth.user.force_password_change) throw new HttpError(403, "غيّر كلمة مرور الأدمن للمتابعة.", { forcePasswordChange: true });
    if (request.method === "GET" && pathname === "/admin/dashboard") {
      const stores = await adminStoreList(auth, url.searchParams.get("includeArchived") === "true");
      const available = stores.filter(store => !store.archived);
      const overview = { stores: available.length, active: available.filter(store => store.status === "active").length,
        trusted: available.filter(store => store.verified).length, debts: available.reduce((sum,store) => sum + Number(store.debtTotal), 0),
        pendingDevices: available.reduce((sum,store) => sum + Number(store.pendingDevices), 0) };
      return ok({ overview, stores, meta: { username: auth.user.username, backend: "supabase" } });
    }
    if (request.method === "GET" && pathname === "/admin/overview") {
      const stores = await adminStoreList(auth);
      return ok({ overview: { stores: stores.length, active: stores.filter(store => store.status === "active").length,
        trusted: stores.filter(store => store.verified).length, debts: stores.reduce((sum,store) => sum + Number(store.debtTotal),0) }, meta: { username: auth.user.username } });
    }
    if (request.method === "GET" && pathname === "/admin/stores") return ok({ stores: await adminStoreList(auth, url.searchParams.get("includeArchived") === "true") });
    if (request.method === "POST" && pathname === "/admin/stores") {
      const body = await bodyJson(request), name = text(body.name, 120), username = text(body.username, 80), password = String(body.password || "");
      if (!name || !safeUsername(username) || password.length < ADMIN_PASSWORD_MIN || password.length > PASSWORD_MAX) throw new HttpError(400, "أدخل اسم المتجر واسم مستخدم وكلمة مؤقتة بين 12 و128 محرفًا.");
      const mode = ["permanent", "timed", "paused"].includes(body.subscriptionMode) ? body.subscriptionMode : "timed";
      const days = Math.floor(Number(body.subscriptionDays) || 0), maxDevices = parseDeviceLimit(body.maxDevices, 1), now = Date.now();
      if (mode === "timed" && (days < 1 || days > 36500)) throw new HttpError(400, "حدد مدة اشتراك بين يوم واحد و100 سنة، أو اختر فعال دائمًا.");
      const hashed = await passwordHash(password);
      const defaults = { contacts: true, debts: true, payments: true, reports: true, analytics: true, export: true, whatsapp: true, appLock: true };
      const permissions = { ...defaults, ...parseJsonObject(body.permissions) };
      const debtorLimit = body.debtorLimit === undefined ? 100 : Math.max(0, Math.min(1_000_000, Math.floor(Number(body.debtorLimit) || 0)));
      const inserted = await db.from("stores").insert({
        name, username, salt: hashed.salt, password_hash: hashed.hash, force_password_change: true,
        status: mode === "paused" ? "suspended" : "active", debtor_limit: debtorLimit, subscription_mode: mode,
        subscription_started_at: mode === "permanent" ? null : now,
        subscription_expires_at: mode === "timed" ? now + days * 86_400_000 : null,
        allowed_devices: maxDevices, permissions, created_at: now,
      }).select("id,username").single();
      if (inserted.error?.code === "23505") throw new HttpError(409, "اسم المستخدم مستخدم بالفعل.");
      const created = dataOrThrow(inserted);
      return send(201, { ok: true, id: Number(created.id), username: created.username, password, forcePasswordChange: true });
    }

    const storeMatch = pathname.match(/^\/admin\/stores\/(\d+)(?:\/(.*))?$/);
    if (storeMatch) {
      const storeId = Number(storeMatch[1]), action = storeMatch[2] || "";
      let store = dataOrThrow(await db.from("stores").select("*").eq("id", storeId).maybeSingle());
      if (!store) throw new HttpError(404, "المتجر غير موجود.");
      if (store.status === "deleted" && !(request.method === "GET" && action === "") && action !== "unarchive") throw new HttpError(409, "الحساب مؤرشف. أعده من الأرشيف أولًا.");
      store = await refreshStoreStatus(store);
      const ledgerCurrent = !!dataOrThrow(await db.rpc("sadid_is_v3_store", { p_store: storeId }));
      if (ledgerCurrent && request.method === "POST" && (/^(backups|archives)\/\d+\/restore$/.test(action) || /^devices\/[^/]+\/permissions$/.test(action))) throw new HttpError(409, "استخدم استرداد الأوامر المعتمد وصلاحيات الحساب لهذا المتجر.");
      if (request.method === "POST" && ["archive", "unarchive", "end-sessions", "revoke-all"].includes(action)) {
        const body = await bodyJson(request), reason = text(body.reason, 2000);
        if (!reason) throw new HttpError(400, "اكتب سبب الإجراء.");
        return ok({ result: await adminAccountAction(auth, storeId, action, reason) });
      }

      if (request.method === "GET" && action === "") {
        const reason = text(url.searchParams.get("reason") || "Store management in admin dashboard", 2000);
        const raw = ledgerCurrent ? dataOrThrow(await db.rpc("sadid_support_snapshot", { p_admin_hash: hashToken(auth.token), p_store: storeId, p_reason: reason })) : null;
        const [devices, backups, contactArchives, auditEvents, controls] = await Promise.all([
          listDevices(storeId), ledgerCurrent ? [] : listBackups(storeId), ledgerCurrent ? [] : listContactArchives(storeId),
          db.from("audit_events").select("actor,action,description,created_at,device_id").eq("store_id", storeId).order("created_at", { ascending: false }).order("id", { ascending: false }).limit(200),
          db.rpc("sadid_admin_controls", { p_admin_hash: hashToken(auth.token), p_store: storeId }),
        ]);
        const control = dataOrThrow(controls);
        const account = { ...publicStore(store), archived: store.status === "deleted", ledgerMode: ledgerCurrent ? "current" : "legacy", canDelete: !ledgerCurrent && !control.hasDeviceHistory };
        const snapshot = raw ? adminLedgerSnapshot(raw, account) : await makeSnapshot(store);
        const events = raw ? ledgerAuditEvents(raw) : dataOrThrow(auditEvents).map((row: Json) => ({ actor: row.actor, action: row.action, description: row.description, createdAt: row.created_at, deviceId: row.device_id }));
        return ok({ store: account, snapshot, devices, backups, contactArchives, auditEvents: events, ...control });
      }
      if (request.method === "GET" && action === "archives") return ok({ archives: await listContactArchives(storeId) });
      const restoreArchive = action.match(/^archives\/(\d+)\/restore$/);
      if (request.method === "POST" && restoreArchive) {
        const archiveId = Number(restoreArchive[1]);
        const archive = dataOrThrow(await db.from("contact_archives").select("id,restored_at").eq("id", archiveId).eq("store_id", storeId).maybeSingle());
        if (!archive) throw new HttpError(404, "نسخة الزبون المحذوف غير موجودة.");
        if (archive.restored_at) throw new HttpError(409, "تمت استعادة هذا السجل مسبقًا.");
        const currentSnapshot = await makeSnapshot(store), now = Date.now();
        const restored = await db.rpc("sadad_restore_contact_archive_checked", {
          p_store_id: storeId, p_archive_id: archiveId, p_expected_revision: Number(store.revision), p_current_snapshot: currentSnapshot, p_now: now,
        });
        if (restored.error) {
          if (restored.error.code === "P0001" && restored.error.message === "revision_conflict") throw new HttpError(409, "تغيّرت البيانات على جهاز آخر. حدّث سجل المتجر ثم أعد الاستعادة.");
          if (restored.error.code === "P0001" && restored.error.message === "archive_not_found") throw new HttpError(404, "نسخة الزبون المحذوف غير موجودة.");
          if (restored.error.code === "P0001" && restored.error.message === "archive_already_restored") throw new HttpError(409, "تمت استعادة هذا السجل مسبقًا.");
          if (restored.error.code === "P0001" && restored.error.message === "archive_contact_exists") throw new HttpError(409, "يوجد سجل حالي بهذا المعرّف. حدّث سجلات المتجر قبل الاستعادة.");
          throw restored.error;
        }
        const updated = dataOrThrow(await db.from("stores").select("*").eq("id", storeId).single());
        return ok({ restored: true, revision: Number(restored.data), snapshot: await makeSnapshot(updated), archives: await listContactArchives(storeId) });
      }
      if (request.method === "GET" && /^backups\/\d+$/.test(action)) {
        const backupId = Number(action.split("/")[1]);
        const backup = dataOrThrow(await db.from("store_backups").select("id,created_at,reason,snapshot_json,contact_count,debt_count,payment_count").eq("id", backupId).eq("store_id", storeId).maybeSingle());
        if (!backup) throw new HttpError(404, "النسخة الاحتياطية غير موجودة.");
        return ok({ backup: { id: backup.id, createdAt: backup.created_at, reason: backup.reason, contactCount: backup.contact_count, debtCount: backup.debt_count, paymentCount: backup.payment_count }, snapshot: backup.snapshot_json });
      }
      const restoreBackup = action.match(/^backups\/(\d+)\/restore$/);
      if (request.method === "POST" && restoreBackup) {
        const backupId = Number(restoreBackup[1]);
        const backup = dataOrThrow(await db.from("store_backups").select("id").eq("id", backupId).eq("store_id", storeId).maybeSingle());
        if (!backup) throw new HttpError(404, "النسخة الاحتياطية غير موجودة.");
        const body = await bodyJson(request);
        let contactId: number | null = null;
        if (body.contactId !== undefined && body.contactId !== null) {
          contactId = Number(body.contactId);
          if (!Number.isSafeInteger(contactId) || contactId < 1) throw new HttpError(400, "معرّف الشخص غير صالح.");
        }
        const currentSnapshot = await makeSnapshot(store), now = Date.now();
        const restored = await db.rpc("sadad_restore_store_backup_checked", {
          p_store_id: storeId, p_backup_id: backupId, p_contact_id: contactId,
          p_expected_revision: Number(store.revision), p_current_snapshot: currentSnapshot, p_now: now,
        });
        if (restored.error) {
          if (restored.error.code === "P0001" && restored.error.message === "revision_conflict") throw new HttpError(409, "تغيّرت البيانات على جهاز آخر. حدّث سجل المتجر ثم أعد الاستعادة.");
          if (restored.error.code === "P0001" && restored.error.message === "backup_not_found") throw new HttpError(404, "النسخة الاحتياطية غير موجودة.");
          if (restored.error.code === "P0001" && restored.error.message === "backup_contact_not_found") throw new HttpError(404, "هذا الشخص غير موجود في النسخة الاحتياطية المختارة.");
          throw restored.error;
        }
        const updated = dataOrThrow(await db.from("stores").select("*").eq("id", storeId).single());
        return ok({ restored: true, revision: Number(restored.data), snapshot: await makeSnapshot(updated) });
      }
      if (request.method === "GET" && action === "devices") return ok({ devices: await listDevices(storeId) });

      const devicePermissionsMatch = action.match(/^devices\/([A-Za-z0-9._:%-]+)\/permissions$/);
      if (request.method === "POST" && devicePermissionsMatch) {
        const deviceId = decodeURIComponent(devicePermissionsMatch[1]);
        const device = dataOrThrow(await db.from("store_devices").select("*").eq("store_id", storeId).eq("device_id", deviceId).maybeSingle());
        if (!device) throw new HttpError(404, "الجهاز غير معروف لهذا الحساب.");
        const body = await bodyJson(request); const permissions: Json = { registerPayments: true, deleteRecords: true, deleteContacts: true, ...parseJsonObject(device.permissions) };
        for (const key of ["registerPayments", "deleteRecords", "deleteContacts"]) if (typeof body[key] === "boolean") permissions[key] = body[key];
        dataOrThrow(await db.from("store_devices").update({ permissions }).eq("store_id", storeId).eq("device_id", deviceId));
        return ok({ permissions });
      }
      const deleteDevice = action.match(/^devices\/([A-Za-z0-9._:%-]+)\/delete$/);
      if (request.method === "DELETE" && deleteDevice) {
        const deviceId = decodeURIComponent(deleteDevice[1]);
        dataOrThrow(await db.from("sessions").delete().eq("kind", "store").eq("principal_id", storeId).eq("device_id", deviceId));
        const removed = dataOrThrow(await db.from("store_devices").delete().eq("store_id", storeId).eq("device_id", deviceId).select("device_id"));
        if (!removed.length) throw new HttpError(404, "الجهاز غير معروف لهذا الحساب.");
        return ok({ deleted: true });
      }
      if (request.method === "POST" && action === "devices/revoke-all") {
        const now = Date.now();
        dataOrThrow(await db.from("store_devices").update({ revoked_at: now }).eq("store_id", storeId).is("revoked_at", null));
        dataOrThrow(await db.from("sessions").delete().eq("kind", "store").eq("principal_id", storeId));
        return ok({ revoked: true });
      }
      const revokeDevice = action.match(/^devices\/([A-Za-z0-9._:%-]+)\/revoke$/);
      if (request.method === "POST" && revokeDevice) {
        const deviceId = decodeURIComponent(revokeDevice[1]);
        dataOrThrow(await db.from("store_devices").update({ revoked_at: Date.now() }).eq("store_id", storeId).eq("device_id", deviceId).is("revoked_at", null));
        dataOrThrow(await db.from("sessions").delete().eq("kind", "store").eq("principal_id", storeId).eq("device_id", deviceId));
        return ok({ revoked: true });
      }
      const allowDevice = action.match(/^devices\/([A-Za-z0-9._:%-]+)\/allow$/);
      if (request.method === "POST" && allowDevice) {
        const deviceId = decodeURIComponent(allowDevice[1]);
        const device = dataOrThrow(await db.from("store_devices").select("*").eq("store_id", storeId).eq("device_id", deviceId).maybeSingle());
        if (!device) throw new HttpError(404, "الجهاز غير معروف لهذا الحساب.");
        const active = await db.from("store_devices").select("device_id", { count: "exact", head: true }).eq("store_id", storeId).is("revoked_at", null);
        if (active.error) throw active.error;
        if (device.revoked_at && (active.count || 0) >= Number(store.allowed_devices || 1)) throw new HttpError(409, "وصلت إلى حد الأجهزة المسموح. ارفع الحد أو ألغِ جهازًا آخر.");
        dataOrThrow(await db.from("store_devices").update({ revoked_at: null }).eq("store_id", storeId).eq("device_id", deviceId));
        return ok({ allowed: true });
      }
      if (request.method === "POST" && action === "update") {
        const body = await bodyJson(request), nextStatus = ["active", "suspended"].includes(body.status) ? body.status : store.status;
        const suspendUntil = nextStatus === "suspended" ? (Number(body.suspendUntil) || null) : null;
        if (nextStatus === "active" && !subscriptionState(store).active) throw new HttpError(409, "الاشتراك موقوف أو منتهٍ. فعّل الاشتراك أو جدده أولاً.");
        const permissionNames = ["contacts", "debts", "payments", "reports", "analytics", "export", "whatsapp", "appLock"];
        const permissions = parseJsonObject(store.permissions), requested = parseJsonObject(body.permissions);
        for (const key of permissionNames) if (typeof requested[key] === "boolean") permissions[key] = requested[key];
        const name = body.name === undefined ? store.name : text(body.name, 120), username = body.username === undefined ? store.username : text(body.username, 80);
        if (!name || !safeUsername(username)) throw new HttpError(400, "أدخل اسم متجر واسم مستخدم صالحين.");
        const maxDevices = parseDeviceLimit(body.maxDevices ?? body.allowedDevices ?? body.allowed_devices ?? body.maxUsers ?? body.max_users, Number(store.allowed_devices || 1));
        const debtorLimit = Math.max(0, Math.min(1_000_000, body.debtorLimit === undefined ? Number(store.debtor_limit) : Number(body.debtorLimit)));
        const update = await db.from("stores").update({
          name, username, status: nextStatus, suspend_until: suspendUntil,
          verified: body.verified === undefined ? store.verified : !!body.verified,
          debtor_limit: debtorLimit, permissions,
          whatsapp_enabled: body.whatsappEnabled === undefined ? store.whatsapp_enabled : !!body.whatsappEnabled,
          allowed_devices: maxDevices, revision: Number(store.revision) + 1,
        }).eq("id", storeId);
        if (update.error?.code === "23505") throw new HttpError(409, "اسم المستخدم مستخدم بالفعل.");
        if (update.error) throw update.error;
        await enforceDeviceCap(storeId, maxDevices);
        if (nextStatus !== "active") dataOrThrow(await db.from("sessions").delete().eq("kind", "store").eq("principal_id", storeId));
        const updated = dataOrThrow(await db.from("stores").select("id,name,username,status,suspend_until,verified,debtor_limit,permissions,whatsapp_enabled,allowed_devices").eq("id", storeId).single());
        return ok({ store: { ...updated, maxDevices, allowedDevices: maxDevices, permissions: parseJsonObject(updated.permissions) } });
      }
      if (request.method === "POST" && action === "subscription") {
        const body = await bodyJson(request), mode = body.mode;
        if (!["permanent", "timed", "paused"].includes(mode)) throw new HttpError(400, "اختر مدة اشتراك صحيحة.");
        const now = Date.now(); let started = store.subscription_started_at, expires = store.subscription_expires_at, remaining = store.subscription_paused_remaining_ms;
        let status = store.status, suspendUntil = store.suspend_until;
        if (mode === "permanent") { started = null; expires = null; remaining = null; status = "active"; suspendUntil = null; }
        else if (mode === "paused") { if (store.subscription_mode === "timed") remaining = subscriptionState(store, now).remainingMs; status = "suspended"; suspendUntil = null; }
        else {
          const days = Math.floor(Number(body.days) || 0);
          if (days < 1 || days > 36500) throw new HttpError(400, "حدد عدد أيام بين 1 و36500.");
          const base = body.extend ? (store.subscription_mode === "paused" ? now + Math.max(0, Number(remaining) || 0) : Math.max(now, Number(expires) || 0)) : now;
          expires = base + days * 86_400_000; started = started || now; remaining = null; status = "active"; suspendUntil = null;
        }
        dataOrThrow(await db.from("stores").update({ subscription_mode: mode, subscription_started_at: started, subscription_expires_at: expires, subscription_paused_remaining_ms: remaining, status, suspend_until: suspendUntil, revision: Number(store.revision) + 1 }).eq("id", storeId));
        if (status !== "active") dataOrThrow(await db.from("sessions").delete().eq("kind", "store").eq("principal_id", storeId));
        const updated = dataOrThrow(await db.from("stores").select("*").eq("id", storeId).single());
        const state = subscriptionState(updated);
        return ok({ store: { subscriptionMode: state.mode, subscriptionStartedAt: updated.subscription_started_at, subscriptionExpiresAt: state.expiresAt, subscriptionRemainingMs: state.remainingMs, status: updated.status } });
      }
      if (request.method === "POST" && action === "reset-password") {
        const body = await bodyJson(request), password = String(body.password || "");
        if (password.length < ADMIN_PASSWORD_MIN || password.length > PASSWORD_MAX) throw new HttpError(400, "كلمة المرور المؤقتة يجب أن تكون بين 12 و128 محرفًا.");
        const hashed = await passwordHash(password);
        dataOrThrow(await db.from("stores").update({ salt: hashed.salt, password_hash: hashed.hash, force_password_change: true }).eq("id", storeId));
        dataOrThrow(await db.from("sessions").delete().eq("kind", "store").eq("principal_id", storeId));
        await adminAccountAction(auth, storeId, "end-sessions", "Store password reset by admin");
        return ok({ username: store.username, password, forcePasswordChange: true });
      }
      if (request.method === "DELETE" && action === "permanent") {
        const control = dataOrThrow(await db.rpc("sadid_admin_controls", { p_admin_hash: hashToken(auth.token), p_store: storeId }));
        if (ledgerCurrent || control.hasDeviceHistory) throw new HttpError(409, "هذا الحساب له سجل محفوظ. استخدم أرشفة الحساب بدل الحذف النهائي.");
        if (url.searchParams.get("confirm") !== store.username) throw new HttpError(400, "أرسل اسم المستخدم لتأكيد الحذف النهائي.");
        dataOrThrow(await db.from("sessions").delete().eq("kind", "store").eq("principal_id", storeId));
        dataOrThrow(await db.from("stores").delete().eq("id", storeId));
        return ok({ deleted: true });
      }
      throw new HttpError(404, "المسار الإداري غير موجود.");
    }
    throw new HttpError(404, "المسار الإداري غير موجود.");
  }
  if (pathname.startsWith("/mobile/")) {
    const auth = await requireSession(request, "store");
    let store = auth.user;
    if (request.method === "GET" && pathname === "/mobile/session") return ok({ account: await accountForStore(store), forcePasswordChange: !!store.force_password_change, revision: Number(store.revision) });
    if (request.method === "POST" && pathname === "/mobile/change-password") {
      const body = await bodyJson(request), currentPassword = String(body.currentPassword || ""), password = String(body.password || "");
      if (password.length < ADMIN_PASSWORD_MIN || password.length > PASSWORD_MAX) throw new HttpError(400, `كلمة المرور الجديدة يجب أن تكون بين ${ADMIN_PASSWORD_MIN} و${PASSWORD_MAX} محرفًا.`);
      if (currentPassword.length > PASSWORD_MAX) throw new HttpError(403, "كلمة المرور الحالية غير صحيحة.");
      const current = await passwordHash(currentPassword, store.salt);
      if (!safeEqual(current.hash, store.password_hash)) throw new HttpError(403, "كلمة المرور الحالية غير صحيحة.");
      const next = await passwordHash(password);
      dataOrThrow(await db.from("stores").update({ salt: next.salt, password_hash: next.hash, force_password_change: false }).eq("id", store.id));
      dataOrThrow(await db.from("sessions").delete().eq("kind", "store").eq("principal_id", store.id).neq("token_hash", hashToken(auth.token)));
      return ok({ forcePasswordChange: false });
    }
    if (dataOrThrow(await db.rpc("sadid_is_v3_store", { p_store: store.id })) && pathname !== "/mobile/logout") throw new HttpError(409, "هذا المتجر يستخدم السجل المعتمد. حدّث التطبيق إلى الإصدار الثالث؛ احتفظ بأي إدخالات قديمة دون حذفها.", { code: "legacy_ledger_read_only" });
    if (request.method === "GET" && pathname === "/mobile/activity") {
      const events = dataOrThrow(await db.from("audit_events").select("actor,action,description,created_at").eq("store_id", store.id).order("created_at", { ascending: false }).order("id", { ascending: false }).limit(300));
      return ok({ events: events.map((item: Json) => ({ actor: item.actor, action: item.action, description: item.description, createdAt: item.created_at })) });
    }
    if (request.method === "GET" && pathname === "/mobile/snapshot") return ok({ snapshot: await makeSnapshot(store) });
    if (request.method === "POST" && pathname === "/mobile/sync") {
      requirePermission(store, "contacts"); requirePermission(store, "debts");
      const body = await bodyJson(request);
      if (!auth.device) throw new HttpError(401, "الجهاز غير معتمد.");
      const result = await syncSnapshot(store, body.snapshot || {}, body.baseRevision, auth.device);
      return ok(result);
    }
    if (request.method === "GET" && pathname === "/mobile/whatsapp") {
      const canUse = parseJsonObject(store.permissions).whatsapp !== false;
      return ok({ enabled: !!store.whatsapp_enabled && canUse, connected: !!store.whatsapp_number_id,
        phoneNumberId: store.whatsapp_number_id, number: store.whatsapp_number_id ? `••••${String(store.whatsapp_number_id).slice(-4)}` : "",
        templateName: store.whatsapp_template_name, templateLanguage: store.whatsapp_template_language, featureAllowed: canUse });
    }
    if (request.method === "PUT" && pathname === "/mobile/whatsapp") {
      requirePermission(store, "whatsapp");
      if (!store.whatsapp_enabled) throw new HttpError(403, "ربط واتساب غير مفعّل من الأدمن لهذا المتجر.");
      const body = await bodyJson(request), phoneId = text(body.phoneNumberId, 80), accessToken = text(body.accessToken, 4096);
      const templateName = text(body.templateName, 512), templateLanguage = text(body.templateLanguage || "ar", 20);
      if (!/^\d{5,30}$/.test(phoneId) || accessToken.length < 30) throw new HttpError(400, "أدخل Phone Number ID ورمز وصول صالحين من Meta.");
      if (templateName && !/^[a-z0-9_]{1,512}$/.test(templateName)) throw new HttpError(400, "اسم قالب Meta يجب أن يحتوي أحرفاً إنجليزية صغيرة وأرقاماً وشرطة سفلية.");
      dataOrThrow(await db.from("stores").update({ whatsapp_number_id: phoneId, whatsapp_token_enc: encryptSecret(accessToken), whatsapp_template_name: templateName, whatsapp_template_language: templateLanguage }).eq("id", store.id));
      return ok({ connected: true, phoneNumberId: phoneId, templateName, templateLanguage });
    }
    if (request.method === "POST" && pathname === "/mobile/whatsapp/send-report") {
      requirePermission(store, "reports"); requirePermission(store, "whatsapp");
      if (!store.whatsapp_enabled || !store.whatsapp_number_id || !store.whatsapp_token_enc) throw new HttpError(403, "اربط حساب واتساب للأعمال واطلب تفعيله من الأدمن.");
      const metaVersion = Deno.env.get("META_GRAPH_VERSION") || "";
      if (!/^v\d+\.\d+$/.test(metaVersion)) throw new HttpError(503, "لم يحدد الأدمن إصدار Meta Graph API المدعوم في إعدادات الخادم.");
      const body = await bodyJson(request), contactId = Number(body.contactId);
      const contact = dataOrThrow(await db.from("contacts").select("*").eq("store_id", store.id).eq("id", contactId).maybeSingle());
      if (!contact || !contact.phone) throw new HttpError(404, "أضف رقم هاتف لهذا الشخص أولاً.");
      if (!contact.whatsapp_opt_in) throw new HttpError(403, "لا يمكن إرسال كشف قبل موافقة الشخص على استلام رسائل واتساب.");
      const snapshot = await makeSnapshot(store), person = snapshot.contacts.find((row: Json) => Number(row.id) === contactId);
      const transactions = snapshot.transactions.filter((row: Json) => Number(row.contactId) === contactId);
      const summary = `كشف ${contact.name}: لي ${Number(person?.receivable || 0).toFixed(2)} ₪، عليّ ${Number(person?.payable || 0).toFixed(2)} ₪. ${transactions.length} حركة مسجلة. — سدد`;
      let destination = String(contact.phone).replace(/\D/g, "");
      if (destination.startsWith("00")) destination = destination.slice(2);
      if (destination.length < 8 || destination.length > 15 || destination.startsWith("0")) throw new HttpError(400, "أدخل رقم الشخص بالصيغة الدولية مع رمز الدولة، مثل 97059xxxxxxx.");
      const token = decryptSecret(store.whatsapp_token_enc);
      let message: Json;
      if (store.whatsapp_template_name) {
        const parameters = [contact.name, `${Number(person?.receivable || 0).toFixed(2)} ₪`, `${Number(person?.payable || 0).toFixed(2)} ₪`, `${transactions.length} حركة`].map(value => ({ type: "text", text: String(value).slice(0, 900) }));
        message = { messaging_product: "whatsapp", to: destination, type: "template", template: { name: store.whatsapp_template_name, language: { code: store.whatsapp_template_language || "ar" }, components: [{ type: "body", parameters }] } };
      } else message = { messaging_product: "whatsapp", to: destination, type: "text", text: { preview_url: false, body: summary } };
      const response = await fetch(`https://graph.facebook.com/${metaVersion}/${store.whatsapp_number_id}/messages`, {
        method: "POST", headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" }, body: JSON.stringify(message),
      });
      const result = await response.json().catch(() => ({}));
      if (!response.ok) throw new HttpError(502, text(result.error?.message || "فشل إرسال كشف واتساب إلى Meta.", 300));
      return ok({ sent: true, messageId: result.messages?.[0]?.id || "" });
    }
    throw new HttpError(404, "مسار التطبيق غير موجود.");
  }
  throw new HttpError(404, "المسار غير موجود.");
}

Deno.serve(async (request: Request) => {
  try { return await route(request); }
  catch (error) { return fail(error); }
});

