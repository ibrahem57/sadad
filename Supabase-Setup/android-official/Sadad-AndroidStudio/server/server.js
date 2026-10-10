const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { DatabaseSync } = require('node:sqlite');

loadEnv(path.join(__dirname, '.env'));
const PORT = Number(process.env.PORT || 8080);
const HOST = process.env.HOST || '0.0.0.0';
const DATA_DIR = path.resolve(process.env.DATA_DIR || path.join(__dirname, 'data'));
const ADMIN_WEB = path.join(__dirname, 'public');
const SESSION_MS = 12 * 60 * 60 * 1000;
const ADMIN_PASSWORD_MIN = 12;
const PASSWORD_MAX = 128;
const LOGIN_WINDOW_MS = 15 * 60_000;
const LOGIN_BLOCK_MS = 15 * 60_000;
const META_VERSION = process.env.META_GRAPH_VERSION || '';
const db = openDatabase();
seedInitialAdmin(db);
let lastLoginCleanupAt = 0;
let dummyLoginSalt = '';

function loadEnv(file) {
  if (!fs.existsSync(file)) return;
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$/);
    if (m && process.env[m[1]] === undefined) process.env[m[1]] = m[2].replace(/^(["'])(.*)\1$/, '$2');
  }
}

function openDatabase() {
  fs.mkdirSync(DATA_DIR, { recursive: true, mode: 0o700 });
  if (process.platform !== 'win32') { try { fs.chmodSync(DATA_DIR, 0o700); } catch (_) { /* Keep startup portable on managed volumes. */ } }
  const databasePath = path.join(DATA_DIR, 'sadad.sqlite');
  const database = new DatabaseSync(databasePath);
  if (process.platform !== 'win32') { try { fs.chmodSync(databasePath, 0o600); } catch (_) { /* Keep startup portable on managed volumes. */ } }
  database.exec('PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;');
  database.exec(`
    CREATE TABLE IF NOT EXISTS admins (
      id INTEGER PRIMARY KEY, username TEXT NOT NULL UNIQUE COLLATE NOCASE,
      salt TEXT NOT NULL, password_hash TEXT NOT NULL, force_password_change INTEGER NOT NULL DEFAULT 0,
      created_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS stores (
      id INTEGER PRIMARY KEY, name TEXT NOT NULL, username TEXT NOT NULL UNIQUE COLLATE NOCASE,
      salt TEXT NOT NULL, password_hash TEXT NOT NULL, force_password_change INTEGER NOT NULL DEFAULT 1,
      status TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('active','suspended','deleted')),
      suspend_until INTEGER, verified INTEGER NOT NULL DEFAULT 0, debtor_limit INTEGER NOT NULL DEFAULT 100,
      permissions TEXT NOT NULL DEFAULT '{}', whatsapp_enabled INTEGER NOT NULL DEFAULT 0,
      whatsapp_number_id TEXT NOT NULL DEFAULT '', whatsapp_token_enc TEXT NOT NULL DEFAULT '',
      whatsapp_template_name TEXT NOT NULL DEFAULT '', whatsapp_template_language TEXT NOT NULL DEFAULT 'ar',
      revision INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS sessions (
      token_hash TEXT PRIMARY KEY, kind TEXT NOT NULL CHECK(kind IN ('admin','store')),
      principal_id INTEGER NOT NULL, expires_at INTEGER NOT NULL, created_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS login_rate_limits (
      bucket_key TEXT PRIMARY KEY, failures INTEGER NOT NULL,
      window_started_at INTEGER NOT NULL, blocked_until INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE IF NOT EXISTS contacts (
      store_id INTEGER NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
      id INTEGER NOT NULL, name TEXT NOT NULL, phone TEXT NOT NULL DEFAULT '', category TEXT NOT NULL DEFAULT '',
      note TEXT NOT NULL DEFAULT '', whatsapp_opt_in INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL,
      PRIMARY KEY(store_id,id)
    );
    CREATE TABLE IF NOT EXISTS debts (
      store_id INTEGER NOT NULL, id INTEGER NOT NULL, contact_id INTEGER NOT NULL,
      direction TEXT NOT NULL CHECK(direction IN ('receivable','payable')), amount_cents INTEGER NOT NULL CHECK(amount_cents>0),
      note TEXT NOT NULL DEFAULT '', due_date TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL,
      PRIMARY KEY(store_id,id), FOREIGN KEY(store_id,contact_id) REFERENCES contacts(store_id,id) ON DELETE CASCADE
    );
    CREATE TABLE IF NOT EXISTS payments (
      store_id INTEGER NOT NULL, id INTEGER NOT NULL, debt_id INTEGER NOT NULL, amount_cents INTEGER NOT NULL CHECK(amount_cents>0),
      method TEXT NOT NULL DEFAULT 'cash' CHECK(method IN ('cash','bank','wallet')), note TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL,
      server_received_at INTEGER NOT NULL DEFAULT 0,
      PRIMARY KEY(store_id,id), FOREIGN KEY(store_id,debt_id) REFERENCES debts(store_id,id) ON DELETE CASCADE
    );
    CREATE INDEX IF NOT EXISTS idx_debts_store_contact ON debts(store_id,contact_id);
    CREATE INDEX IF NOT EXISTS idx_payments_store_debt ON payments(store_id,debt_id);
    CREATE INDEX IF NOT EXISTS idx_sessions_expiry ON sessions(expires_at);
    CREATE INDEX IF NOT EXISTS idx_login_rate_limits_window ON login_rate_limits(window_started_at);
  `);
  ensureColumn(database, 'admins', 'force_password_change', 'INTEGER NOT NULL DEFAULT 0');
  ensureColumn(database, 'stores', 'whatsapp_template_name', "TEXT NOT NULL DEFAULT ''");
  ensureColumn(database, 'stores', 'whatsapp_template_language', "TEXT NOT NULL DEFAULT 'ar'");
  ensureColumn(database, 'payments', 'server_received_at', 'INTEGER NOT NULL DEFAULT 0');
  database.exec('CREATE INDEX IF NOT EXISTS idx_payments_store_received ON payments(store_id,server_received_at)');
  return database;
}

function seedInitialAdmin(database) {
  const username = text(process.env.INITIAL_ADMIN_USERNAME, 80);
  const password = String(process.env.INITIAL_ADMIN_PASSWORD || '');
  if (!username && !password) return;
  if (!username || !password) throw new Error('Set both INITIAL_ADMIN_USERNAME and INITIAL_ADMIN_PASSWORD.');
  if (!/^[\p{L}\p{N}._-]{3,80}$/u.test(username) || password.length < ADMIN_PASSWORD_MIN || password.length > PASSWORD_MAX) {
    throw new Error(`Initial admin username must be valid and initial password must be ${ADMIN_PASSWORD_MIN}-${PASSWORD_MAX} characters.`);
  }
  if (Number(database.prepare('SELECT COUNT(*) n FROM admins').get().n) > 0) return;
  const salt = crypto.randomBytes(16).toString('hex');
  const hash = crypto.scryptSync(password, salt, 64, { N: 32768, r: 8, p: 1, maxmem: 64 * 1024 * 1024 }).toString('hex');
  database.prepare('INSERT INTO admins(username,salt,password_hash,force_password_change,created_at) VALUES(?,?,?,?,?)')
    .run(username, salt, hash, 1, Date.now());
  console.log(`Initial admin ${username} created; password change is required at first login.`);
}

function ensureColumn(database, table, name, definition) {
  const columns = database.prepare(`PRAGMA table_info(${table})`).all();
  if (!columns.some(column => column.name === name)) database.exec(`ALTER TABLE ${table} ADD COLUMN ${name} ${definition}`);
}

class HttpError extends Error {
  constructor(status, message, extra = {}) { super(message); this.status = status; this.extra = extra; }
}
function send(res, status, body, type = 'application/json; charset=utf-8') {
  const headers = {
    'Content-Type': type, 'X-Content-Type-Options': 'nosniff', 'X-Frame-Options': 'DENY',
    'Referrer-Policy': 'same-origin', 'Cache-Control': 'no-store',
    'Content-Security-Policy': "default-src 'self'; img-src 'self' data:; script-src 'self'; style-src 'self'; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'",
    'Permissions-Policy': 'camera=(), microphone=(), geolocation=()',
    'Cross-Origin-Resource-Policy': 'same-origin'
  };
  if (process.env.NODE_ENV === 'production') headers['Strict-Transport-Security'] = 'max-age=31536000';
  res.writeHead(status, headers);
  res.end(type.startsWith('application/json') ? JSON.stringify(body) : body);
}
function ok(res, data = {}) { send(res, 200, { ok: true, ...data }); }
function bodyJson(req) {
  return new Promise((resolve, reject) => {
    let data = ''; let bytes = 0;
    req.on('data', chunk => { bytes += chunk.length; if (bytes > 3_000_000) { reject(new HttpError(413, 'الطلب أكبر من الحد المسموح.')); req.destroy(); return; } data += chunk; });
    req.on('end', () => {
      if (!data) return resolve({});
      try { resolve(JSON.parse(data)); } catch (_) { reject(new HttpError(400, 'صيغة البيانات غير صحيحة.')); }
    });
    req.on('error', reject);
  });
}
function text(value, max = 250) { return String(value ?? '').trim().slice(0, max); }
function passwordHash(password, salt = crypto.randomBytes(16).toString('hex')) {
  return new Promise((resolve, reject) => crypto.scrypt(String(password), salt, 64, { N: 32768, r: 8, p: 1, maxmem: 64 * 1024 * 1024 }, (e, key) => e ? reject(e) : resolve({ salt, hash: key.toString('hex') })));
}
function dummyCredentialSalt() {
  if (!dummyLoginSalt) dummyLoginSalt = crypto.randomBytes(16).toString('hex');
  return dummyLoginSalt;
}
function safeEqual(a, b) {
  const x = Buffer.from(String(a || ''), 'hex'); const y = Buffer.from(String(b || ''), 'hex');
  return x.length === y.length && x.length > 0 && crypto.timingSafeEqual(x, y);
}
function hashToken(token) { return crypto.createHash('sha256').update(token).digest('hex'); }
function bearer(req) { const match = String(req.headers.authorization || '').match(/^Bearer\s+(.+)$/i); return match ? match[1] : ''; }
function loginBucketKeys(req, identity) {
  const trustedProxyIp = process.env.TRUST_PROXY_HEADERS === 'true' ? text(req.headers['x-sadad-client-ip'], 64) : '';
  const ip = trustedProxyIp || text(req.socket?.remoteAddress, 64) || 'unknown';
  const normalizedIdentity = text(identity, 100).normalize('NFKC').toLocaleLowerCase('en-US');
  return [{ key: hashToken(`login-ip:${ip}`), limit: 60 }, { key: hashToken(`login-account:${ip}:${normalizedIdentity}`), limit: 10 }];
}
function checkLoginLimit(req, identity) {
  const now = Date.now();
  for (const bucket of loginBucketKeys(req, identity)) {
    const row = db.prepare('SELECT blocked_until FROM login_rate_limits WHERE bucket_key=?').get(bucket.key);
    if (row && Number(row.blocked_until) > now) throw new HttpError(429, 'محاولات دخول كثيرة. انتظر قليلًا ثم حاول مجددًا.');
  }
}
function recordLoginFailure(req, identity) {
  const now = Date.now();
  tx(() => {
    for (const bucket of loginBucketKeys(req, identity)) {
      const row = db.prepare('SELECT failures,window_started_at FROM login_rate_limits WHERE bucket_key=?').get(bucket.key);
      const inWindow = row && now - Number(row.window_started_at) < LOGIN_WINDOW_MS;
      const failures = inWindow ? Number(row.failures) + 1 : 1;
      const windowStartedAt = inWindow ? Number(row.window_started_at) : now;
      const blockedUntil = failures >= bucket.limit ? now + LOGIN_BLOCK_MS : 0;
      db.prepare(`INSERT INTO login_rate_limits(bucket_key,failures,window_started_at,blocked_until) VALUES(?,?,?,?)
        ON CONFLICT(bucket_key) DO UPDATE SET failures=excluded.failures,window_started_at=excluded.window_started_at,blocked_until=excluded.blocked_until`)
        .run(bucket.key, failures, windowStartedAt, blockedUntil);
    }
    if (now - lastLoginCleanupAt > 10 * 60_000) {
      db.prepare('DELETE FROM login_rate_limits WHERE window_started_at<? AND blocked_until<?').run(now - 24 * 60 * 60_000, now);
      lastLoginCleanupAt = now;
    }
  });
}
function clearLoginFailures(req, identity) {
  const keys = loginBucketKeys(req, identity);
  db.prepare('DELETE FROM login_rate_limits WHERE bucket_key=?').run(keys[1].key);
}
function makeSession(kind, principalId) {
  const token = crypto.randomBytes(32).toString('base64url'); const expiresAt = Date.now() + SESSION_MS;
  db.prepare('INSERT INTO sessions(token_hash,kind,principal_id,expires_at,created_at) VALUES(?,?,?,?,?)').run(hashToken(token), kind, principalId, expiresAt, Date.now());
  return { token, expiresAt };
}
function requireSession(req, kind) {
  const token = bearer(req); if (!token) throw new HttpError(401, 'سجّل الدخول للمتابعة.');
  const session = db.prepare('SELECT * FROM sessions WHERE token_hash=? AND expires_at>? AND kind=?').get(hashToken(token), Date.now(), kind);
  if (!session) throw new HttpError(401, 'انتهت الجلسة. سجّل الدخول من جديد.');
  const principal = kind === 'admin'
    ? db.prepare('SELECT id,username,force_password_change FROM admins WHERE id=?').get(session.principal_id)
    : db.prepare('SELECT * FROM stores WHERE id=?').get(session.principal_id);
  if (!principal) throw new HttpError(401, 'الحساب غير موجود.');
  if (kind === 'store') {
    if (principal.status === 'suspended' && principal.suspend_until && principal.suspend_until <= Date.now()) {
      db.prepare("UPDATE stores SET status='active',suspend_until=NULL WHERE id=?").run(principal.id); principal.status = 'active';
    }
    if (principal.status !== 'active') throw new HttpError(423, 'الحساب موقوف من الإدارة. تواصل مع الدعم.');
  }
  return { token, session, principal };
}
function requirePermission(store, permission) {
  if (store.force_password_change) throw new HttpError(403, 'غيّر كلمة المرور المؤقتة أولاً.', { forcePasswordChange: true });
  const permissions = JSON.parse(store.permissions || '{}');
  if (permissions[permission] === false) throw new HttpError(403, 'هذه الميزة غير مفعّلة لحسابك من الإدارة.');
  return permissions;
}
function tx(fn) {
  db.exec('BEGIN IMMEDIATE');
  try { const out = fn(); db.exec('COMMIT'); return out; }
  catch (e) { db.exec('ROLLBACK'); throw e; }
}
const cents = value => {
  const n = Number(value); if (!Number.isFinite(n) || n < 0 || n > 1_000_000_000) throw new HttpError(400, 'المبلغ المدخل غير صالح.');
  return Math.round(n * 100);
};
const money = value => Math.round(Number(value || 0)) / 100;
const nowOr = value => { const n = Number(value); return Number.isFinite(n) && n > 0 ? n : Date.now(); };
function emptySnapshot(store) {
  return { contacts: [], debts: [], payments: [], transactions: [], totals: { receivable: 0, payable: 0, net: 0 }, revision: store.revision,
    account: { id: store.id, name: store.name, username: store.username, verified: !!store.verified, permissions: JSON.parse(store.permissions || '{}'), debtorLimit: store.debtor_limit, whatsappEnabled: !!store.whatsapp_enabled } };
}
function makeSnapshot(store) {
  const result = emptySnapshot(store); const byId = new Map();
  const contacts = db.prepare('SELECT * FROM contacts WHERE store_id=? ORDER BY name COLLATE NOCASE,id').all(store.id);
  for (const c of contacts) {
    const contact = { id: c.id, name: c.name, phone: c.phone, category: c.category, note: c.note, whatsappOptIn: !!c.whatsapp_opt_in,
      createdAt: c.created_at, receivable: 0, payable: 0, net: 0, transactionCount: 0, lastActivity: 0, latestDebtId: 0 };
    result.contacts.push(contact); byId.set(c.id, contact);
  }
  const debts = db.prepare('SELECT d.*,COALESCE(SUM(p.amount_cents),0) paid_cents FROM debts d LEFT JOIN payments p ON p.store_id=d.store_id AND p.debt_id=d.id WHERE d.store_id=? GROUP BY d.id ORDER BY d.created_at DESC,d.id DESC').all(store.id);
  for (const d of debts) {
    const paid = Number(d.paid_cents); const remaining = Math.max(0, Number(d.amount_cents) - paid);
    result.debts.push({ id: d.id, contactId: d.contact_id, direction: d.direction, amount: money(d.amount_cents), paid: money(paid), remaining: money(remaining), note: d.note, dueDate: d.due_date, createdAt: d.created_at });
    const c = byId.get(d.contact_id);
    if (c) { const key = d.direction === 'receivable' ? 'receivable' : 'payable'; c[key] += money(remaining); c.transactionCount++; if (d.created_at > c.lastActivity) { c.lastActivity = d.created_at; c.latestDebtId = d.id; } }
    result.transactions.push({ id: `d${d.id}`, debtId: d.id, contactId: d.contact_id, contactName: byId.get(d.contact_id)?.name || '', kind: 'debt', direction: d.direction, amount: money(d.amount_cents), note: d.note, method: '', createdAt: d.created_at });
  }
  const payments = db.prepare('SELECT p.*,d.contact_id,d.direction,c.name contact_name FROM payments p JOIN debts d ON d.store_id=p.store_id AND d.id=p.debt_id JOIN contacts c ON c.store_id=d.store_id AND c.id=d.contact_id WHERE p.store_id=? ORDER BY p.created_at DESC,p.id DESC').all(store.id);
  for (const p of payments) {
    result.payments.push({ id: p.id, debtId: p.debt_id, contactId: p.contact_id, contactName: p.contact_name, direction: p.direction, amount: money(p.amount_cents), method: p.method, note: p.note, createdAt: p.created_at });
    const c = byId.get(p.contact_id); if (c && p.created_at > c.lastActivity) c.lastActivity = p.created_at;
    result.transactions.push({ id: `p${p.id}`, debtId: p.debt_id, contactId: p.contact_id, contactName: p.contact_name, kind: 'payment', direction: p.direction, amount: money(p.amount_cents), note: p.note, method: p.method, createdAt: p.created_at });
  }
  for (const c of result.contacts) { c.net = c.receivable - c.payable; result.totals.receivable += c.receivable; result.totals.payable += c.payable; }
  result.totals.net = result.totals.receivable - result.totals.payable;
  result.transactions.sort((a, b) => b.createdAt - a.createdAt);
  return result;
}
function syncSnapshot(store, input, baseRevision) {
  if (!input || !Array.isArray(input.contacts) || !Array.isArray(input.debts) || !Array.isArray(input.payments)) throw new HttpError(400, 'بيانات المزامنة غير مكتملة. لم يتم تغيير السجل.');
  const contacts = input.contacts, debts = input.debts, payments = input.payments;
  if (contacts.length > 50_000 || debts.length > 100_000 || payments.length > 500_000) throw new HttpError(413, 'عدد السجلات أكبر من الحد.');
  if (Number(baseRevision) !== Number(store.revision)) throw new HttpError(409, 'تغيّرت البيانات على جهاز آخر. حمّل النسخة الأحدث ثم أعد المحاولة.', { snapshot: makeSnapshot(store) });
  const timestamp = value => {
    if (value === undefined || value === null || value === '') return Date.now();
    const n = Number(value);
    if (!Number.isSafeInteger(n) || n < 1 || n > Date.now() + 5 * 60_000) throw new HttpError(400, 'يوجد تاريخ سجل غير صالح.');
    return n;
  };
  const assertIds = (rows, label) => {
    const ids = new Set();
    for (const row of rows) { const id = Number(row?.id); if (!Number.isSafeInteger(id) || id < 1 || ids.has(id)) throw new HttpError(400, `معرّف ${label} غير صالح أو مكرر.`); ids.add(id); }
    return ids;
  };
  const contactIds = assertIds(contacts, 'الشخص'), debtIds = assertIds(debts, 'الدين');
  assertIds(payments, 'الدفعة');
  for (const c of contacts) {
    if (!text(c.name, 120)) throw new HttpError(400, 'يوجد شخص بلا اسم.');
    timestamp(c.createdAt); cents(c.creditLimit || 0);
  }
  const uniqueContacts = new Set();
  for (const c of contacts) {
    const phone = String(c.phone || '').replace(/[٠-٩]/g, d => String('٠١٢٣٤٥٦٧٨٩'.indexOf(d))).replace(/[۰-۹]/g, d => String('۰۱۲۳۴۵۶۷۸۹'.indexOf(d))).replace(/\D/g, '');
    const key = `${text(c.name,120).normalize('NFKC').toLocaleLowerCase('ar')}|${phone}`;
    if (uniqueContacts.has(key)) throw new HttpError(400, 'يوجد اسم ورقم هاتف مكرران.');
    uniqueContacts.add(key);
  }
  const previousSnapshot = makeSnapshot(store);
  const previousContacts = new Map(previousSnapshot.contacts.map(row => [Number(row.id), row]));
  const previousDebts = new Map(previousSnapshot.debts.map(row => [Number(row.id), row]));
  const previousPayments = new Map(previousSnapshot.payments.map(row => [Number(row.id), row]));
  const nextPaymentIds = new Set(payments.map(row => Number(row.id)));
  const removedContacts = previousSnapshot.contacts.filter(row => !contactIds.has(Number(row.id)));
  const removedDebts = previousSnapshot.debts.filter(row => !debtIds.has(Number(row.id)));
  const removedPayments = previousSnapshot.payments.filter(row => !nextPaymentIds.has(Number(row.id)));
  const addedContacts = contacts.filter(row => !previousContacts.has(Number(row.id)));
  const addedDebts = debts.filter(row => !previousDebts.has(Number(row.id)));
  const addedPayments = payments.filter(row => !previousPayments.has(Number(row.id)));
  if (addedPayments.length || removedPayments.length) requirePermission(store, 'payments');
  for (const d of debts) {
    if (!contactIds.has(Number(d.contactId))) throw new HttpError(400, 'دين مرتبط بشخص غير موجود.');
    if (!['receivable','payable'].includes(d.direction) || cents(d.amount) < 1) throw new HttpError(400, 'بيانات الدين غير صالحة.');
    timestamp(d.createdAt);
    if (d.dueDate && !/^\d{4}-\d{2}-\d{2}$/.test(text(d.dueDate,20))) throw new HttpError(400, 'موعد الاستحقاق غير صالح.');
    const old = previousDebts.get(Number(d.id));
    if (old && (Number(old.contactId) !== Number(d.contactId) || old.direction !== d.direction || cents(old.amount) !== cents(d.amount)
      || text(old.note,500) !== text(d.note,500) || text(old.dueDate,20) !== text(d.dueDate,20) || Number(old.createdAt) !== timestamp(d.createdAt)))
      throw new HttpError(403, 'لا يمكن تعديل دين مسجل مباشرة.');
  }
  for (const p of payments) {
    if (!debtIds.has(Number(p.debtId)) || cents(p.amount) < 1 || !['cash','bank','wallet'].includes(p.method || 'cash')) throw new HttpError(400, 'بيانات الدفعة غير صالحة.');
    timestamp(p.createdAt);
    const old = previousPayments.get(Number(p.id));
    if (old && (Number(old.debtId) !== Number(p.debtId) || cents(old.amount) !== cents(p.amount) || old.method !== (p.method || 'cash')
      || text(old.note,500) !== text(p.note,500) || Number(old.createdAt) !== timestamp(p.createdAt))) throw new HttpError(403, 'لا يمكن تعديل دفعة مسجلة مباشرة.');
  }
  for (const contact of removedContacts) {
    const old = previousContacts.get(Number(contact.id));
    if (Number(old?.receivable || 0) > 0 || Number(old?.payable || 0) > 0) throw new HttpError(409, `لا يمكن حذف ${text(contact.name,120)} لوجود دين متبقٍ.`);
    if (debts.some(row => Number(row.contactId) === Number(contact.id))) throw new HttpError(400, 'احذف سجل الشخص قبل حذف الشخص.');
  }
  for (const debt of removedDebts) if (Number(debt.remaining) > 0) throw new HttpError(409, 'لا يمكن حذف دين ما زال عليه مبلغ متبقٍ.');
  const paidByDebt = new Map();
  for (const p of payments) paidByDebt.set(Number(p.debtId), (paidByDebt.get(Number(p.debtId)) || 0) + cents(p.amount));
  const activeDebtors = new Set();
  for (const d of debts) {
    const remaining = cents(d.amount) - (paidByDebt.get(Number(d.id)) || 0);
    if (remaining < 0) throw new HttpError(400, 'مجموع دفعات دين تجاوز أصل الدين.');
    if (d.direction === 'receivable' && remaining > 0) activeDebtors.add(Number(d.contactId));
  }
  if (activeDebtors.size > Number(store.debtor_limit)) throw new HttpError(403, `تجاوزت حد ${store.debtor_limit} مدينًا الذي حدده الأدمن.`);
  const contactByDebt = new Map(debts.map(row => [Number(row.id), Number(row.contactId)]));
  const receivedAt = Date.now(), newGroups = new Map();
  for (const p of addedPayments) {
    const contactId = contactByDebt.get(Number(p.debtId)), createdAt = timestamp(p.createdAt), method = p.method || 'cash';
    const key = [contactId, createdAt, method].join('|');
    const group = newGroups.get(key) || { contactId, createdAt, method, amountCents: 0 };
    group.amountCents += cents(p.amount); newGroups.set(key, group);
  }
  const priorGroups = new Map();
  const recent = db.prepare(`SELECT p.amount_cents,p.method,p.created_at,
      CASE WHEN p.server_received_at>0 THEN p.server_received_at ELSE p.created_at END received_at,d.contact_id
    FROM payments p JOIN debts d ON d.store_id=p.store_id AND d.id=p.debt_id
    WHERE p.store_id=? AND (CASE WHEN p.server_received_at>0 THEN p.server_received_at ELSE p.created_at END)>=?`).all(store.id, receivedAt - 60_000);
  for (const p of recent) {
    const key = [Number(p.contact_id), Number(p.created_at), p.method].join('|');
    const group = priorGroups.get(key) || { contactId: Number(p.contact_id), method: p.method, receivedAt: Number(p.received_at), amountCents: 0 };
    group.amountCents += Number(p.amount_cents); priorGroups.set(key, group);
  }
  const candidates = Array.from(newGroups.values());
  for (let i=0;i<candidates.length;i++) {
    const candidate = candidates[i];
    const duplicatePrior = Array.from(priorGroups.values()).some(prior => prior.contactId===candidate.contactId && prior.method===candidate.method && prior.amountCents===candidate.amountCents && prior.receivedAt<=receivedAt && receivedAt-prior.receivedAt<=60_000);
    const duplicateBatch = candidates.slice(i+1).some(other => other.contactId===candidate.contactId && other.method===candidate.method && other.amountCents===candidate.amountCents && Math.abs(other.createdAt-candidate.createdAt)<=60_000);
    if (duplicatePrior || duplicateBatch) throw new HttpError(400, 'توجد دفعة مطابقة خلال آخر دقيقة. انتظر دقيقة قبل إعادة المحاولة.');
  }
  tx(() => {
    const removePayment = db.prepare('DELETE FROM payments WHERE store_id=? AND id=?');
    for (const p of removedPayments) removePayment.run(store.id, Number(p.id));
    const removeDebt = db.prepare('DELETE FROM debts WHERE store_id=? AND id=?');
    for (const d of removedDebts) removeDebt.run(store.id, Number(d.id));
    const removeContact = db.prepare('DELETE FROM contacts WHERE store_id=? AND id=?');
    for (const c of removedContacts) removeContact.run(store.id, Number(c.id));
    const putContact = db.prepare(`INSERT INTO contacts(store_id,id,name,phone,category,note,whatsapp_opt_in,created_at) VALUES(?,?,?,?,?,?,?,?)
      ON CONFLICT(store_id,id) DO UPDATE SET name=excluded.name,phone=excluded.phone,category=excluded.category,note=excluded.note,whatsapp_opt_in=excluded.whatsapp_opt_in`);
    for (const c of contacts) { const old=previousContacts.get(Number(c.id)); putContact.run(store.id, Number(c.id), text(c.name,120), text(c.phone,40), text(c.category,40), text(c.note,500), c.whatsappOptIn===true?1:0, old?Number(old.createdAt):timestamp(c.createdAt)); }
    const putDebt = db.prepare('INSERT INTO debts(store_id,id,contact_id,direction,amount_cents,note,due_date,created_at) VALUES(?,?,?,?,?,?,?,?)');
    for (const d of addedDebts) putDebt.run(store.id, Number(d.id), Number(d.contactId), d.direction, cents(d.amount), text(d.note,500), text(d.dueDate,20), timestamp(d.createdAt));
    const putPayment = db.prepare('INSERT INTO payments(store_id,id,debt_id,amount_cents,method,note,created_at,server_received_at) VALUES(?,?,?,?,?,?,?,?)');
    for (const p of addedPayments) putPayment.run(store.id, Number(p.id), Number(p.debtId), cents(p.amount), p.method||'cash', text(p.note,500), timestamp(p.createdAt), receivedAt);
    db.prepare('UPDATE stores SET revision=revision+1 WHERE id=?').run(store.id);
  });
  return db.prepare('SELECT * FROM stores WHERE id=?').get(store.id);
}
function deriveEncryptionKey() {
  const raw = process.env.TOKEN_ENCRYPTION_KEY || '';
  if (/^[0-9a-f]{64}$/i.test(raw)) return Buffer.from(raw, 'hex');
  if (raw) { const key = Buffer.from(raw, 'base64'); if (key.length === 32) return key; }
  throw new HttpError(503, 'لم يضبط الخادم مفتاح تشفير ربط واتساب.');
}
function encryptSecret(value) {
  const iv = crypto.randomBytes(12); const cipher = crypto.createCipheriv('aes-256-gcm', deriveEncryptionKey(), iv);
  const data = Buffer.concat([cipher.update(value, 'utf8'), cipher.final()]);
  return Buffer.concat([iv, cipher.getAuthTag(), data]).toString('base64');
}
function decryptSecret(value) {
  const b = Buffer.from(value, 'base64'); const iv = b.subarray(0,12); const tag = b.subarray(12,28); const data = b.subarray(28);
  const decipher = crypto.createDecipheriv('aes-256-gcm', deriveEncryptionKey(), iv); decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(data), decipher.final()]).toString('utf8');
}
async function route(req, res) {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const pathname = url.pathname;
  if (req.method === 'GET' && pathname === '/api/health') return ok(res, { service: 'sadad', version: '1.0.0' });
  if (req.method === 'GET' && pathname === '/api/setup/status') return ok(res, { bootstrapRequired: Number(db.prepare('SELECT COUNT(*) n FROM admins').get().n) === 0 });
  if (req.method === 'POST' && pathname === '/api/setup/admin') {
    checkLoginLimit(req, 'admin-setup'); const b = await bodyJson(req);
    if (Number(db.prepare('SELECT COUNT(*) n FROM admins').get().n) > 0) throw new HttpError(409, 'تم إنشاء مدير النظام مسبقًا.');
    if (!process.env.INITIAL_ADMIN_KEY || !safeEqual(crypto.createHash('sha256').update(text(b.setupKey,200)).digest('hex'), crypto.createHash('sha256').update(process.env.INITIAL_ADMIN_KEY).digest('hex'))) { recordLoginFailure(req, 'admin-setup'); throw new HttpError(403, 'مفتاح التأسيس غير صحيح.'); }
    const username = text(b.username,80); const password = String(b.password || '');
    if (!/^[\p{L}\p{N}._-]{3,80}$/u.test(username) || password.length < ADMIN_PASSWORD_MIN || password.length > PASSWORD_MAX) throw new HttpError(400, `اسم المستخدم لا يقل عن 3 أحرف وكلمة المرور بين ${ADMIN_PASSWORD_MIN} و${PASSWORD_MAX}.`);
    const ph = await passwordHash(password); const created = db.prepare('INSERT INTO admins(username,salt,password_hash,created_at) VALUES(?,?,?,?)').run(username,ph.salt,ph.hash,Date.now());
    clearLoginFailures(req, 'admin-setup'); const session = makeSession('admin',Number(created.lastInsertRowid)); return ok(res,{...session,admin:{username}});
  }
  if (req.method === 'POST' && pathname === '/api/admin/login') {
    const b=await bodyJson(req); const user=text(b.username,80); const identity=`admin:${user}`; checkLoginLimit(req, identity);
    const admin=db.prepare('SELECT * FROM admins WHERE username=? COLLATE NOCASE').get(user);
    const password=String(b.password||''); const withinLimit=password.length<=PASSWORD_MAX;
    const ph=await passwordHash(withinLimit?password:password.slice(0,PASSWORD_MAX),admin?.salt||dummyCredentialSalt());
    if (!admin || !withinLimit || !safeEqual(ph.hash,admin.password_hash)) { recordLoginFailure(req, identity); throw new HttpError(401,'اسم المستخدم أو كلمة المرور غير صحيحة.'); }
    clearLoginFailures(req, identity);
    return ok(res,{...makeSession('admin',admin.id),admin:{username:admin.username},forcePasswordChange:!!admin.force_password_change});
  }
  if (req.method === 'POST' && pathname === '/api/mobile/login') {
    const b=await bodyJson(req); const user=text(b.username,80); const identity=`store:${user}`; checkLoginLimit(req, identity);
    const store=db.prepare('SELECT * FROM stores WHERE username=? COLLATE NOCASE').get(user);
    const password=String(b.password||''); const withinLimit=password.length<=PASSWORD_MAX;
    const ph=await passwordHash(withinLimit?password:password.slice(0,PASSWORD_MAX),store?.salt||dummyCredentialSalt());
    if (!store || !withinLimit || !safeEqual(ph.hash,store.password_hash)) { recordLoginFailure(req, identity); throw new HttpError(401,'اسم المستخدم أو كلمة المرور غير صحيحة.'); }
    clearLoginFailures(req, identity);
    const session=makeSession('store',store.id); const latest=db.prepare('SELECT * FROM stores WHERE id=?').get(store.id);
    return ok(res,{...session,forcePasswordChange:!!latest.force_password_change,snapshot:makeSnapshot(latest),account:makeSnapshot(latest).account});
  }
  if (req.method === 'POST' && pathname === '/api/admin/logout') {
    const auth=requireSession(req,'admin'); db.prepare('DELETE FROM sessions WHERE token_hash=?').run(hashToken(auth.token)); return ok(res);
  }
  if (req.method === 'POST' && pathname === '/api/mobile/logout') {
    const auth=requireSession(req,'store'); db.prepare('DELETE FROM sessions WHERE token_hash=?').run(hashToken(auth.token)); return ok(res);
  }
  if (pathname.startsWith('/api/admin/')) {
    const auth=requireSession(req,'admin');
    if (req.method==='GET' && pathname==='/api/admin/session') return ok(res,{admin:{username:auth.principal.username},forcePasswordChange:!!auth.principal.force_password_change});
    if (req.method==='POST' && pathname==='/api/admin/change-password') {
      const b=await bodyJson(req); const currentPassword=String(b.currentPassword||''); const password=String(b.password||'');
      if(password.length<ADMIN_PASSWORD_MIN||password.length>PASSWORD_MAX) throw new HttpError(400,`كلمة مرور الأدمن يجب أن تكون بين ${ADMIN_PASSWORD_MIN} و${PASSWORD_MAX} محرفًا.`);
      if(currentPassword.length>PASSWORD_MAX) throw new HttpError(403,'كلمة المرور الحالية غير صحيحة.');
      const admin=db.prepare('SELECT * FROM admins WHERE id=?').get(auth.principal.id);
      const currentHash=await passwordHash(currentPassword,admin.salt);
      if(!safeEqual(currentHash.hash,admin.password_hash)) throw new HttpError(403,'كلمة المرور الحالية غير صحيحة.');
      const next=await passwordHash(password);
      db.prepare('UPDATE admins SET salt=?,password_hash=?,force_password_change=0 WHERE id=?').run(next.salt,next.hash,admin.id);
      db.prepare("DELETE FROM sessions WHERE kind='admin' AND principal_id=? AND token_hash<>?").run(admin.id,hashToken(auth.token));
      return ok(res,{changed:true,admin:{username:admin.username}});
    }
    if (auth.principal.force_password_change) throw new HttpError(403,'غيّر كلمة مرور الأدمن للمتابعة.',{forcePasswordChange:true});
    if (req.method==='GET' && pathname==='/api/admin/overview') {
      const stores=db.prepare("SELECT COUNT(*) n FROM stores WHERE status!='deleted'").get().n;
      const active=db.prepare("SELECT COUNT(*) n FROM stores WHERE status='active'").get().n;
      const trusted=db.prepare('SELECT COUNT(*) n FROM stores WHERE verified=1 AND status=\'active\'').get().n;
      const led=db.prepare('SELECT COUNT(*) n FROM contacts').get().n;
      const debt=db.prepare('SELECT COALESCE(SUM(amount_cents),0) n FROM debts').get().n;
      return ok(res,{overview:{stores,active,trusted,contacts:led,debts:money(debt)},meta:{username:auth.principal.username}});
    }
    if (req.method==='GET' && pathname==='/api/admin/stores') {
      const rows=db.prepare(`SELECT s.id,s.name,s.username,s.status,s.suspend_until,s.verified,s.debtor_limit,s.permissions,s.whatsapp_enabled,s.revision,s.created_at,
        (SELECT COUNT(*) FROM contacts c WHERE c.store_id=s.id) contacts,
        (SELECT COUNT(*) FROM debts d WHERE d.store_id=s.id) debt_count,
        (SELECT COALESCE(SUM(d.amount_cents),0) FROM debts d WHERE d.store_id=s.id) debt_cents
        FROM stores s ORDER BY s.created_at DESC`).all();
      return ok(res,{stores:rows.map(s=>({...s,verified:!!s.verified,whatsappEnabled:!!s.whatsapp_enabled,debtorsLimit:s.debtor_limit,permissions:JSON.parse(s.permissions),contacts:s.contacts,debts:s.debt_count,debtTotal:money(s.debt_cents)}))});
    }
    if (req.method==='POST' && pathname==='/api/admin/stores') {
      const b=await bodyJson(req); const name=text(b.name,120); const username=text(b.username,80); const password=String(b.password||'');
      if(!name || !/^[\p{L}\p{N}._-]{3,80}$/u.test(username) || password.length<12 || password.length>PASSWORD_MAX) throw new HttpError(400,'أدخل اسم المتجر واسم مستخدم وكلمة مؤقتة بين 12 و128 محرفًا.');
      const ph=await passwordHash(password);
      const defaults={contacts:true,debts:true,payments:true,reports:true,analytics:true,export:true,whatsapp:true,appLock:true};
      const permissions={...defaults,...(b.permissions||{})};
      const insert=db.prepare('INSERT INTO stores(name,username,salt,password_hash,force_password_change,status,debtor_limit,permissions,created_at) VALUES(?,?,?,?,1,\'active\',?,?,?)');
      const row=insert.run(name,username,ph.salt,ph.hash,Math.max(0,Math.min(1_000_000,Number(b.debtorLimit)||100)),JSON.stringify(permissions),Date.now());
      return send(res,201,{ok:true,id:Number(row.lastInsertRowid),username,password,forcePasswordChange:true});
    }
    const match=pathname.match(/^\/api\/admin\/stores\/(\d+)(?:\/(.*))?$/);
    if(match){
      const id=Number(match[1]); const action=match[2]||''; const store=db.prepare('SELECT * FROM stores WHERE id=?').get(id); if(!store) throw new HttpError(404,'المتجر غير موجود.');
      if(req.method==='GET' && action==='') return ok(res,{store:{id:store.id,name:store.name,username:store.username,status:store.status,suspendUntil:store.suspend_until,verified:!!store.verified,debtorLimit:store.debtor_limit,permissions:JSON.parse(store.permissions),whatsappEnabled:!!store.whatsapp_enabled,revision:store.revision,createdAt:store.created_at},snapshot:makeSnapshot(store)});
      if(req.method==='POST' && action==='update'){
        const b=await bodyJson(req); const nextStatus=['active','suspended'].includes(b.status)?b.status:store.status;
        const until=nextStatus==='suspended'?(Number(b.suspendUntil)||null):null;
        const permissionNames=['contacts','debts','payments','reports','analytics','export','whatsapp','appLock']; let perms=JSON.parse(store.permissions||'{}');
        if(b.permissions&&typeof b.permissions==='object') for(const p of permissionNames) if(typeof b.permissions[p]==='boolean') perms[p]=b.permissions[p];
        const newName=b.name===undefined?store.name:text(b.name,120); const newUsername=b.username===undefined?store.username:text(b.username,80);
        db.prepare('UPDATE stores SET name=?,username=?,status=?,suspend_until=?,verified=?,debtor_limit=?,permissions=?,whatsapp_enabled=? WHERE id=?').run(newName,newUsername,nextStatus,until,b.verified===undefined?store.verified:(b.verified?1:0),Math.max(0,Math.min(1_000_000,b.debtorLimit===undefined?store.debtor_limit:Number(b.debtorLimit))),JSON.stringify(perms),b.whatsappEnabled===undefined?store.whatsapp_enabled:(b.whatsappEnabled?1:0),id);
        return ok(res,{store:db.prepare('SELECT id,name,username,status,suspend_until,verified,debtor_limit,permissions,whatsapp_enabled FROM stores WHERE id=?').get(id)});
      }
      if(req.method==='POST' && action==='reset-password'){
        const b=await bodyJson(req); const password=String(b.password||''); if(password.length<12||password.length>PASSWORD_MAX) throw new HttpError(400,'كلمة المرور المؤقتة يجب أن تكون بين 12 و128 محرفًا.');
        const ph=await passwordHash(password); db.prepare('UPDATE stores SET salt=?,password_hash=?,force_password_change=1 WHERE id=?').run(ph.salt,ph.hash,id); db.prepare("DELETE FROM sessions WHERE kind='store' AND principal_id=?").run(id); return ok(res,{username:store.username,password,forcePasswordChange:true});
      }
      if(req.method==='DELETE' && action==='permanent'){
        if(url.searchParams.get('confirm')!==store.username) throw new HttpError(400,'أرسل اسم المستخدم لتأكيد الحذف النهائي.');
        db.prepare('DELETE FROM sessions WHERE kind=\'store\' AND principal_id=?').run(id); db.prepare('DELETE FROM stores WHERE id=?').run(id); return ok(res,{deleted:true});
      }
    }
    throw new HttpError(404,'المسار الإداري غير موجود.');
  }
  if (pathname.startsWith('/api/mobile/')) {
    const auth=requireSession(req,'store'); const store=db.prepare('SELECT * FROM stores WHERE id=?').get(auth.principal.id);
    if(req.method==='GET' && pathname==='/api/mobile/session') return ok(res,{account:makeSnapshot(store).account,forcePasswordChange:!!store.force_password_change,revision:store.revision});
    if(req.method==='POST' && pathname==='/api/mobile/change-password'){
      const b=await bodyJson(req); const password=String(b.password||''); if(password.length<12||password.length>PASSWORD_MAX) throw new HttpError(400,'كلمة المرور الجديدة يجب أن تكون بين 12 و128 محرفًا.');
      const ph=await passwordHash(password); db.prepare('UPDATE stores SET salt=?,password_hash=?,force_password_change=0 WHERE id=?').run(ph.salt,ph.hash,store.id); db.prepare("DELETE FROM sessions WHERE kind='store' AND principal_id=? AND token_hash<>?").run(store.id,hashToken(auth.token)); return ok(res,{forcePasswordChange:false});
    }
    if(req.method==='GET' && pathname==='/api/mobile/snapshot') return ok(res,{snapshot:makeSnapshot(store)});
    if(req.method==='POST' && pathname==='/api/mobile/sync'){
      requirePermission(store,'contacts'); requirePermission(store,'debts'); const b=await bodyJson(req); const changed=syncSnapshot(store,b.snapshot||{},b.baseRevision); return ok(res,{revision:changed.revision,snapshot:makeSnapshot(changed)});
    }
    if(req.method==='GET' && pathname==='/api/mobile/whatsapp'){
      const canUse=JSON.parse(store.permissions||'{}').whatsapp!==false;
      return ok(res,{enabled:!!store.whatsapp_enabled&&canUse,connected:!!store.whatsapp_number_id,phoneNumberId:store.whatsapp_number_id,number:store.whatsapp_number_id?`••••${store.whatsapp_number_id.slice(-4)}`:'',templateName:store.whatsapp_template_name,templateLanguage:store.whatsapp_template_language,featureAllowed:canUse});
    }
    if(req.method==='PUT' && pathname==='/api/mobile/whatsapp'){
      requirePermission(store,'whatsapp'); if(!store.whatsapp_enabled) throw new HttpError(403,'ربط واتساب غير مفعّل من الأدمن لهذا المتجر.');
      const b=await bodyJson(req); const phoneId=text(b.phoneNumberId,80); const accessToken=text(b.accessToken,4096);
      const templateName=text(b.templateName,512); const templateLanguage=text(b.templateLanguage||'ar',20);
      if(!/^\d{5,30}$/.test(phoneId)||accessToken.length<30) throw new HttpError(400,'أدخل Phone Number ID ورمز وصول صالحين من Meta.');
      if(templateName&&!/^[a-z0-9_]{1,512}$/.test(templateName)) throw new HttpError(400,'اسم قالب Meta يجب أن يحتوي أحرفاً إنجليزية صغيرة وأرقاماً وشرطة سفلية.');
      db.prepare('UPDATE stores SET whatsapp_number_id=?,whatsapp_token_enc=?,whatsapp_template_name=?,whatsapp_template_language=? WHERE id=?').run(phoneId,encryptSecret(accessToken),templateName,templateLanguage,store.id); return ok(res,{connected:true,phoneNumberId:phoneId,templateName,templateLanguage});
    }
    if(req.method==='POST' && pathname==='/api/mobile/whatsapp/send-report'){
      requirePermission(store,'reports'); requirePermission(store,'whatsapp'); if(!store.whatsapp_enabled||!store.whatsapp_number_id||!store.whatsapp_token_enc) throw new HttpError(403,'اربط حساب واتساب للأعمال واطلب تفعيله من الأدمن.');
      if(!/^v\d+\.\d+$/.test(META_VERSION)) throw new HttpError(503,'لم يحدد الأدمن إصدار Meta Graph API المدعوم في إعدادات الخادم.');
      const b=await bodyJson(req); const contactId=Number(b.contactId); const contact=db.prepare('SELECT * FROM contacts WHERE store_id=? AND id=?').get(store.id,contactId);
      if(!contact||!contact.phone) throw new HttpError(404,'أضف رقم هاتف لهذا الشخص أولاً.'); if(!contact.whatsapp_opt_in) throw new HttpError(403,'لا يمكن إرسال كشف قبل موافقة الشخص على استلام رسائل واتساب.');
      const snapshot=makeSnapshot(store); const person=snapshot.contacts.find(c=>c.id===contactId); const txs=snapshot.transactions.filter(t=>Number(t.contactId)===contactId); const summary=`كشف ${contact.name}: لي ${Number(person?.receivable||0).toFixed(2)} ₪، عليّ ${Number(person?.payable||0).toFixed(2)} ₪. ${txs.length} حركة مسجلة. — سدد`;
      let destination=contact.phone.replace(/\D/g,''); if(destination.startsWith('00'))destination=destination.slice(2); if(destination.length<8||destination.length>15||destination.startsWith('0')) throw new HttpError(400,'أدخل رقم الشخص بالصيغة الدولية مع رمز الدولة، مثل 97059xxxxxxx.');
      const token=decryptSecret(store.whatsapp_token_enc);
      let message;
      if(store.whatsapp_template_name){
        const parameters=[contact.name,`${Number(person?.receivable||0).toFixed(2)} ₪`,`${Number(person?.payable||0).toFixed(2)} ₪`,`${txs.length} حركة`].map(value=>({type:'text',text:String(value).slice(0,900)}));
        message={messaging_product:'whatsapp',to:destination,type:'template',template:{name:store.whatsapp_template_name,language:{code:store.whatsapp_template_language||'ar'},components:[{type:'body',parameters}]}};
      } else message={messaging_product:'whatsapp',to:destination,type:'text',text:{preview_url:false,body:summary}};
      const response=await fetch(`https://graph.facebook.com/${META_VERSION}/${store.whatsapp_number_id}/messages`,{method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:JSON.stringify(message)});
      const result=await response.json().catch(()=>({})); if(!response.ok) throw new HttpError(502,text(result.error?.message||'فشل إرسال كشف واتساب إلى Meta.',300));
      return ok(res,{sent:true,messageId:result.messages?.[0]?.id||''});
    }
    throw new HttpError(404,'مسار التطبيق غير موجود.');
  }
  if (req.method==='GET') {
    const safe=pathname==='/'?'/admin.html':pathname; const file=path.resolve(ADMIN_WEB,'.'+safe);
    if(!file.startsWith(path.resolve(ADMIN_WEB)+path.sep)) throw new HttpError(404,'الصفحة غير موجودة.');
    if(!fs.existsSync(file)||!fs.statSync(file).isFile()) throw new HttpError(404,'الصفحة غير موجودة.');
    const ext=path.extname(file); const type=({'.html':'text/html; charset=utf-8','.css':'text/css; charset=utf-8','.js':'text/javascript; charset=utf-8','.svg':'image/svg+xml'})[ext]||'application/octet-stream';
    return send(res,200,fs.readFileSync(file),type);
  }
  throw new HttpError(404,'المسار غير موجود.');
}

const server=http.createServer(async(req,res)=>{
  try { await route(req,res); }
  catch(e) { const status=e instanceof HttpError?e.status:500; if(status===500) console.error(e); send(res,status,{ok:false,message:status===500?'حدث خطأ داخلي.':(e.message||'تعذر إكمال الطلب.'),...(e.extra||{})}); }
});
server.listen(PORT,HOST,()=>console.log(`Sadad server listening on http://${HOST}:${PORT}`));
process.on('SIGTERM',()=>{server.close(()=>{db.close();process.exit(0);});});
