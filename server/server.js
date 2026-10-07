const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
let db = null;
let serverEnv = process.env;
const SESSION_MS = 12 * 60 * 60 * 1000;
const ADMIN_PASSWORD_MIN = 12;
const ADMIN_PASSWORD_MAX = 128;
const LOGIN_WINDOW_MS = 15 * 60_000;
const LOGIN_BLOCK_MS = 15 * 60_000;
let lastLoginCleanupAt = 0;
let dummyLoginSalt = '';

function configureDatabase(database, env = process.env) { db = database; serverEnv = env || process.env; }

function loadEnv(file) {
  if (!fs.existsSync(file)) return;
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$/);
    if (m && process.env[m[1]] === undefined) process.env[m[1]] = m[2].replace(/^(["'])(.*)\1$/, '$2');
  }
}

const SCHEMA_SQL = `
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
      subscription_mode TEXT NOT NULL DEFAULT 'permanent' CHECK(subscription_mode IN ('permanent','timed','paused')),
      subscription_started_at INTEGER, subscription_expires_at INTEGER, subscription_paused_remaining_ms INTEGER,
      allowed_devices INTEGER NOT NULL DEFAULT 1 CHECK(allowed_devices BETWEEN 1 AND 50),
      permissions TEXT NOT NULL DEFAULT '{}', whatsapp_enabled INTEGER NOT NULL DEFAULT 0,
      whatsapp_number_id TEXT NOT NULL DEFAULT '', whatsapp_token_enc TEXT NOT NULL DEFAULT '',
      whatsapp_template_name TEXT NOT NULL DEFAULT '', whatsapp_template_language TEXT NOT NULL DEFAULT 'ar',
      revision INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS sessions (
      token_hash TEXT PRIMARY KEY, kind TEXT NOT NULL CHECK(kind IN ('admin','store')),
      principal_id INTEGER NOT NULL, device_id TEXT, expires_at INTEGER NOT NULL, created_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS login_rate_limits (
      bucket_key TEXT PRIMARY KEY, failures INTEGER NOT NULL,
      window_started_at INTEGER NOT NULL, blocked_until INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE IF NOT EXISTS store_devices (
      store_id INTEGER NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
      device_id TEXT NOT NULL, label TEXT NOT NULL DEFAULT '', first_seen_at INTEGER NOT NULL,
      last_seen_at INTEGER NOT NULL, revoked_at INTEGER,
      staff_name TEXT NOT NULL DEFAULT '', permissions TEXT NOT NULL DEFAULT '{}',
      PRIMARY KEY(store_id,device_id)
    );
    CREATE TABLE IF NOT EXISTS audit_events (
      id INTEGER PRIMARY KEY, store_id INTEGER NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
      device_id TEXT NOT NULL DEFAULT '', actor TEXT NOT NULL DEFAULT '', action TEXT NOT NULL,
      description TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS store_backups (
      id INTEGER PRIMARY KEY, store_id INTEGER NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
      created_at INTEGER NOT NULL, reason TEXT NOT NULL, snapshot_json TEXT NOT NULL,
      contact_count INTEGER NOT NULL DEFAULT 0, debt_count INTEGER NOT NULL DEFAULT 0, payment_count INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE IF NOT EXISTS contact_archives (
      id INTEGER PRIMARY KEY, store_id INTEGER NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
      contact_id INTEGER NOT NULL, contact_name TEXT NOT NULL, archived_at INTEGER NOT NULL,
      source_revision INTEGER NOT NULL DEFAULT 0, snapshot_json TEXT NOT NULL,
      debt_count INTEGER NOT NULL DEFAULT 0, payment_count INTEGER NOT NULL DEFAULT 0, restored_at INTEGER
    );
    CREATE TABLE IF NOT EXISTS contacts (
      store_id INTEGER NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
      id INTEGER NOT NULL, name TEXT NOT NULL, phone TEXT NOT NULL DEFAULT '', category TEXT NOT NULL DEFAULT '',
      note TEXT NOT NULL DEFAULT '', whatsapp_opt_in INTEGER NOT NULL DEFAULT 0, credit_limit_cents INTEGER NOT NULL DEFAULT 0,
      created_by TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL,
      PRIMARY KEY(store_id,id)
    );
    CREATE TABLE IF NOT EXISTS debts (
      store_id INTEGER NOT NULL, id INTEGER NOT NULL, contact_id INTEGER NOT NULL,
      direction TEXT NOT NULL CHECK(direction IN ('receivable','payable')), amount_cents INTEGER NOT NULL CHECK(amount_cents>0),
      note TEXT NOT NULL DEFAULT '', due_date TEXT NOT NULL DEFAULT '', created_by TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL,
      PRIMARY KEY(store_id,id), FOREIGN KEY(store_id,contact_id) REFERENCES contacts(store_id,id) ON DELETE CASCADE
    );
    CREATE TABLE IF NOT EXISTS payments (
      store_id INTEGER NOT NULL, id INTEGER NOT NULL, debt_id INTEGER NOT NULL, amount_cents INTEGER NOT NULL CHECK(amount_cents>0),
      method TEXT NOT NULL DEFAULT 'cash' CHECK(method IN ('cash','bank','wallet','wallet_jawwal','wallet_palpay')), note TEXT NOT NULL DEFAULT '',
      created_by TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, server_received_at INTEGER NOT NULL DEFAULT 0,
      PRIMARY KEY(store_id,id), FOREIGN KEY(store_id,debt_id) REFERENCES debts(store_id,id) ON DELETE CASCADE
    );
    CREATE INDEX IF NOT EXISTS idx_debts_store_contact ON debts(store_id,contact_id);
    CREATE INDEX IF NOT EXISTS idx_payments_store_debt ON payments(store_id,debt_id);
    CREATE INDEX IF NOT EXISTS idx_sessions_expiry ON sessions(expires_at);
    CREATE INDEX IF NOT EXISTS idx_login_rate_limits_window ON login_rate_limits(window_started_at);
    CREATE INDEX IF NOT EXISTS idx_store_devices_active ON store_devices(store_id,revoked_at);
    CREATE INDEX IF NOT EXISTS idx_store_backups_recent ON store_backups(store_id,created_at DESC);
    CREATE INDEX IF NOT EXISTS idx_contact_archives_recent ON contact_archives(store_id,archived_at DESC);
  `;

function initializeDatabase(database, { durable = false } = {}) {
  if (!durable) database.exec('PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;');
  else database.exec('PRAGMA foreign_keys=ON;');
  database.exec(SCHEMA_SQL);
  if (!durable) {
    ensureColumn(database, 'admins', 'force_password_change', 'INTEGER NOT NULL DEFAULT 0');
    ensureColumn(database, 'stores', 'subscription_mode', "TEXT NOT NULL DEFAULT 'permanent'");
    ensureColumn(database, 'stores', 'subscription_started_at', 'INTEGER');
    ensureColumn(database, 'stores', 'subscription_expires_at', 'INTEGER');
    ensureColumn(database, 'stores', 'subscription_paused_remaining_ms', 'INTEGER');
    ensureColumn(database, 'stores', 'allowed_devices', 'INTEGER NOT NULL DEFAULT 1');
    ensureColumn(database, 'sessions', 'device_id', 'TEXT');
    ensureColumn(database, 'store_devices', 'staff_name', "TEXT NOT NULL DEFAULT ''");
    ensureColumn(database, 'store_devices', 'permissions', "TEXT NOT NULL DEFAULT '{}'");
    ensureColumn(database, 'contacts', 'created_by', "TEXT NOT NULL DEFAULT ''");
    ensureColumn(database, 'contacts', 'credit_limit_cents', 'INTEGER NOT NULL DEFAULT 0');
    ensureColumn(database, 'debts', 'created_by', "TEXT NOT NULL DEFAULT ''");
    ensureColumn(database, 'payments', 'created_by', "TEXT NOT NULL DEFAULT ''");
    ensureColumn(database, 'payments', 'server_received_at', 'INTEGER NOT NULL DEFAULT 0');
    ensureColumn(database, 'stores', 'whatsapp_template_name', "TEXT NOT NULL DEFAULT ''");
    ensureColumn(database, 'stores', 'whatsapp_template_language', "TEXT NOT NULL DEFAULT 'ar'");
    database.exec("UPDATE stores SET subscription_mode='permanent' WHERE subscription_expires_at IS NULL AND subscription_mode='timed'");
  } else {
    for (const migration of [
      "ALTER TABLE store_devices ADD COLUMN staff_name TEXT NOT NULL DEFAULT ''",
      "ALTER TABLE store_devices ADD COLUMN permissions TEXT NOT NULL DEFAULT '{}'",
      "ALTER TABLE contacts ADD COLUMN created_by TEXT NOT NULL DEFAULT ''",
      "ALTER TABLE contacts ADD COLUMN credit_limit_cents INTEGER NOT NULL DEFAULT 0",
      "ALTER TABLE debts ADD COLUMN created_by TEXT NOT NULL DEFAULT ''",
      "ALTER TABLE payments ADD COLUMN created_by TEXT NOT NULL DEFAULT ''",
      "ALTER TABLE payments ADD COLUMN server_received_at INTEGER NOT NULL DEFAULT 0",
    ]) { try { database.exec(migration); } catch (_) { /* Durable SQLite schema already contains the column */ } }
  }
  const paymentsSchema = database.prepare("SELECT sql FROM sqlite_master WHERE type='table' AND name='payments'").get();
  if (paymentsSchema && !paymentsSchema.sql.includes('wallet_jawwal')) {
    const migrateMethods = () => {
      database.exec(SCHEMA_SQL.match(/CREATE TABLE IF NOT EXISTS payments \([\s\S]*?\n    \);/)[0].replace('IF NOT EXISTS payments', 'payments_method_upgrade'));
      database.exec('INSERT INTO payments_method_upgrade SELECT store_id,id,debt_id,amount_cents,method,note,created_by,created_at,server_received_at FROM payments');
      database.exec('DROP TABLE payments'); database.exec('ALTER TABLE payments_method_upgrade RENAME TO payments');
      database.exec('CREATE INDEX idx_payments_store_debt ON payments(store_id,debt_id)');
    };
    if (typeof database.transactionSync === 'function') database.transactionSync(migrateMethods);
    else { database.exec('BEGIN IMMEDIATE'); try { migrateMethods(); database.exec('COMMIT'); } catch (error) { database.exec('ROLLBACK'); throw error; } }
  }
  database.exec('CREATE INDEX IF NOT EXISTS idx_payments_store_received ON payments(store_id,server_received_at)');
  return database;
}

function openDatabase() {
  const dataDir = path.resolve(serverEnv.DATA_DIR || path.join(__dirname, 'data'));
  fs.mkdirSync(dataDir, { recursive: true, mode: 0o700 });
  if (process.platform !== 'win32') { try { fs.chmodSync(dataDir, 0o700); } catch (_) { /* Keep startup portable on managed volumes. */ } }
  const { DatabaseSync } = require('node:sqlite');
  const databasePath = path.join(dataDir, 'sadad.sqlite');
  const database = initializeDatabase(new DatabaseSync(databasePath));
  if (process.platform !== 'win32') { try { fs.chmodSync(databasePath, 0o600); } catch (_) { /* Keep startup portable on managed volumes. */ } }
  return database;
}

function seedInitialAdmin(database) {
  const username = text(serverEnv.INITIAL_ADMIN_USERNAME, 80);
  const password = String(serverEnv.INITIAL_ADMIN_PASSWORD || '');
  if (!username && !password) return;
  if (!username || !password) throw new Error('Set both INITIAL_ADMIN_USERNAME and INITIAL_ADMIN_PASSWORD.');
  if (!/^[\p{L}\p{N}._-]{3,80}$/u.test(username) || password.length < ADMIN_PASSWORD_MIN || password.length > ADMIN_PASSWORD_MAX) {
    throw new Error(`Initial admin username must be valid and initial password must be ${ADMIN_PASSWORD_MIN}-${ADMIN_PASSWORD_MAX} characters.`);
  }
  if (Number(database.prepare('SELECT COUNT(*) n FROM admins').get().n) > 0) return;
  const ph = passwordHashSync(password);
  database.prepare('INSERT INTO admins(username,salt,password_hash,force_password_change,created_at) VALUES(?,?,?,?,?)')
    .run(username, ph.salt, ph.hash, 1, Date.now());
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
  if (serverEnv.NODE_ENV === 'production') headers['Strict-Transport-Security'] = 'max-age=31536000';
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
function normalizedContactPhone(value) {
  let digits = String(value ?? '').replace(/[٠-٩]/g, char => String(char.charCodeAt(0) - 1632)).replace(/[۰-۹]/g, char => String(char.charCodeAt(0) - 1776)).replace(/\D/g, '');
  if (digits.startsWith('00970')) digits = digits.slice(2);
  else if (digits.startsWith('0')) digits = `970${digits.slice(1)}`;
  return digits;
}
function parseDeviceLimit(value, fallback = 1) {
  if (value === undefined || value === null) return fallback;
  const digits = String(value).trim()
    .replace(/[٠-٩]/g, digit => String('٠١٢٣٤٥٦٧٨٩'.indexOf(digit)))
    .replace(/[۰-۹]/g, digit => String('۰۱۲۳۴۵۶۷۸۹'.indexOf(digit)));
  if (!digits) throw new HttpError(400, 'أدخل عددًا صحيحًا للأجهزة المسموح بها.');
  const limit = Number(digits);
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 50) throw new HttpError(400, 'عدد الأجهزة المسموح بها يجب أن يكون من 1 إلى 50.');
  return limit;
}
function passwordSalt(salt = null) {
  const stored = String(salt || '');
  const encoded = stored.match(/^scrypt\$(\d{3,8})\$([0-9a-f]{32})$/i);
  if (encoded) return { salt: stored, rawSalt: encoded[2], N: Number(encoded[1]) };
  const rawSalt = stored || crypto.randomBytes(16).toString('hex');
  const configured = Number(serverEnv.PASSWORD_SCRYPT_N) || 32768;
  const N = stored ? 32768 : Math.max(1024, Math.min(32768, 2 ** Math.floor(Math.log2(configured))));
  return { salt: N === 32768 ? rawSalt : `scrypt$${N}$${rawSalt}`, rawSalt, N };
}
function passwordHashSync(password, salt = null) {
  const params = passwordSalt(salt);
  const hash = crypto.scryptSync(String(password), params.rawSalt, 64, { N: params.N, r: 8, p: 1, maxmem: 64 * 1024 * 1024 }).toString('hex');
  return { salt: params.salt, hash };
}
function passwordHash(password, salt = null) {
  const params = passwordSalt(salt);
  return new Promise((resolve, reject) => crypto.scrypt(String(password), params.rawSalt, 64, { N: params.N, r: 8, p: 1, maxmem: 64 * 1024 * 1024 }, (e, key) => e ? reject(e) : resolve({ salt: params.salt, hash: key.toString('hex') })));
}
function dummyCredentialSalt() {
  if (!dummyLoginSalt) dummyLoginSalt = passwordSalt(null).salt;
  return dummyLoginSalt;
}
function safeEqual(a, b) {
  const x = Buffer.from(String(a || ''), 'hex'); const y = Buffer.from(String(b || ''), 'hex');
  return x.length === y.length && x.length > 0 && crypto.timingSafeEqual(x, y);
}
function hashToken(token) { return crypto.createHash('sha256').update(token).digest('hex'); }
function bearer(req) { const match = String(req.headers.authorization || '').match(/^Bearer\s+(.+)$/i); return match ? match[1] : ''; }
function loginBucketKeys(req, identity) {
  const configuredProxyIp = serverEnv.TRUST_PROXY_HEADERS === 'true' ? text(req.headers['x-sadad-client-ip'], 64) : '';
  const ip = configuredProxyIp || text(req.socket?.remoteAddress, 64) || 'unknown';
  const normalizedIdentity = text(identity, 100).normalize('NFKC').toLocaleLowerCase('en-US');
  return [
    { key: hashToken(`login-ip:${ip}`), limit: 60 },
    { key: hashToken(`login-account:${ip}:${normalizedIdentity}`), limit: 10 },
  ];
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
function makeSession(kind, principalId, deviceId = null) {
  const token = crypto.randomBytes(32).toString('base64url'); const expiresAt = Date.now() + SESSION_MS;
  db.prepare('INSERT INTO sessions(token_hash,kind,principal_id,device_id,expires_at,created_at) VALUES(?,?,?,?,?,?)').run(hashToken(token), kind, principalId, deviceId, expiresAt, Date.now());
  return { token, expiresAt };
}

function subscriptionState(store, now = Date.now()) {
  if (store.subscription_mode === 'permanent') return { mode: 'permanent', active: true, expiresAt: null, remainingMs: null };
  if (store.subscription_mode === 'paused') return { mode: 'paused', active: false, expiresAt: store.subscription_expires_at || null, remainingMs: Math.max(0, Number(store.subscription_paused_remaining_ms) || 0) };
  const expiresAt = Number(store.subscription_expires_at) || 0;
  return { mode: 'timed', active: expiresAt > now, expiresAt: expiresAt || null, remainingMs: Math.max(0, expiresAt - now) };
}

function refreshStoreStatus(store, now = Date.now()) {
  const subscription = subscriptionState(store, now);
  if ((!subscription.active || subscription.mode === 'paused') && store.status === 'active') {
    db.prepare("UPDATE stores SET status='suspended',suspend_until=NULL WHERE id=?").run(store.id);
    db.prepare("DELETE FROM sessions WHERE kind='store' AND principal_id=?").run(store.id);
    return db.prepare('SELECT * FROM stores WHERE id=?').get(store.id);
  }
  if (store.status === 'suspended' && store.suspend_until && store.suspend_until <= now && subscription.active) {
    db.prepare("UPDATE stores SET status='active',suspend_until=NULL WHERE id=?").run(store.id);
    return db.prepare('SELECT * FROM stores WHERE id=?').get(store.id);
  }
  return store;
}

function requireSession(req, kind) {
  const token = bearer(req); if (!token) throw new HttpError(401, 'سجّل الدخول للمتابعة.');
  const session = db.prepare('SELECT * FROM sessions WHERE token_hash=? AND expires_at>? AND kind=?').get(hashToken(token), Date.now(), kind);
  if (!session) throw new HttpError(401, 'انتهت الجلسة. سجّل الدخول من جديد.');
  let principal = kind === 'admin'
    ? db.prepare('SELECT id,username,force_password_change FROM admins WHERE id=?').get(session.principal_id)
    : db.prepare('SELECT * FROM stores WHERE id=?').get(session.principal_id);
  if (!principal) throw new HttpError(401, 'الحساب غير موجود.');
  let device = null;
  if (kind === 'store') {
    principal = refreshStoreStatus(principal);
    device = session.device_id ? db.prepare('SELECT * FROM store_devices WHERE store_id=? AND device_id=? AND revoked_at IS NULL').get(principal.id, session.device_id) : null;
    if (!device) {
      db.prepare('DELETE FROM sessions WHERE token_hash=?').run(hashToken(token));
      throw new HttpError(401, 'تم إلغاء تسجيل هذا الجهاز من الإدارة. سجّل الدخول مجددًا.');
    }
    if (principal.status !== 'active') throw new HttpError(423, 'الحساب موقوف من الإدارة. تواصل مع الدعم.');
  }
  return { token, session, principal, device };
}
function requirePermission(store, permission) {
  if (store.force_password_change) throw new HttpError(403, 'غيّر كلمة المرور المؤقتة أولاً.', { forcePasswordChange: true });
  const permissions = JSON.parse(store.permissions || '{}');
  if (permissions[permission] === false) throw new HttpError(403, 'هذه الميزة غير مفعّلة لحسابك من الإدارة.');
  return permissions;
}
function tx(fn) {
  if (typeof db.transactionSync === 'function') return db.transactionSync(fn);
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
function accountForStore(store) {
  return { id: store.id, name: store.name, username: store.username, verified: !!store.verified,
    permissions: JSON.parse(store.permissions || '{}'), debtorLimit: store.debtor_limit, whatsappEnabled: !!store.whatsapp_enabled,
    subscriptionMode: store.subscription_mode, subscriptionExpiresAt: store.subscription_expires_at || null,
    subscriptionRemainingMs: subscriptionState(store).remainingMs, maxDevices: Number(store.allowed_devices || 1),
    boundDevices: Number(db.prepare('SELECT COUNT(*) n FROM store_devices WHERE store_id=? AND revoked_at IS NULL').get(store.id).n) };
}
function emptySnapshot(store) {
  return { contacts: [], debts: [], payments: [], transactions: [], totals: { receivable: 0, payable: 0, net: 0 }, revision: store.revision,
    account: accountForStore(store) };
}
function makeSnapshot(store) {
  const result = emptySnapshot(store); const byId = new Map();
  const contacts = db.prepare('SELECT * FROM contacts WHERE store_id=? ORDER BY name COLLATE NOCASE,id').all(store.id);
  for (const c of contacts) {
    const contact = { id: c.id, name: c.name, phone: c.phone, category: c.category, note: c.note, whatsappOptIn: !!c.whatsapp_opt_in, creditLimit: money(c.credit_limit_cents || 0),
      createdAt: c.created_at, createdBy: c.created_by || '', receivable: 0, payable: 0, net: 0, transactionCount: 0, lastActivity: 0, latestDebtId: 0 };
    result.contacts.push(contact); byId.set(c.id, contact);
  }
  const debts = db.prepare('SELECT d.*,COALESCE(SUM(p.amount_cents),0) paid_cents FROM debts d LEFT JOIN payments p ON p.store_id=d.store_id AND p.debt_id=d.id WHERE d.store_id=? GROUP BY d.id ORDER BY d.created_at DESC,d.id DESC').all(store.id);
  for (const d of debts) {
    const paid = Number(d.paid_cents); const remaining = Math.max(0, Number(d.amount_cents) - paid);
    result.debts.push({ id: d.id, contactId: d.contact_id, direction: d.direction, amount: money(d.amount_cents), paid: money(paid), remaining: money(remaining), note: d.note, dueDate: d.due_date, createdAt: d.created_at, createdBy: d.created_by || '' });
    const c = byId.get(d.contact_id);
    if (c) { const key = d.direction === 'receivable' ? 'receivable' : 'payable'; c[key] += money(remaining); c.transactionCount++; if (d.created_at > c.lastActivity) { c.lastActivity = d.created_at; c.latestDebtId = d.id; } }
    result.transactions.push({ id: `d${d.id}`, debtId: d.id, contactId: d.contact_id, contactName: byId.get(d.contact_id)?.name || '', kind: 'debt', direction: d.direction, amount: money(d.amount_cents), note: d.note, method: '', createdAt: d.created_at, createdBy: d.created_by || '' });
  }
  const payments = db.prepare('SELECT p.*,d.contact_id,d.direction,c.name contact_name FROM payments p JOIN debts d ON d.store_id=p.store_id AND d.id=p.debt_id JOIN contacts c ON c.store_id=d.store_id AND c.id=d.contact_id WHERE p.store_id=? ORDER BY p.created_at DESC,p.id DESC').all(store.id);
  for (const p of payments) {
    result.payments.push({ id: p.id, debtId: p.debt_id, contactId: p.contact_id, contactName: p.contact_name, direction: p.direction, amount: money(p.amount_cents), method: p.method, note: p.note, createdAt: p.created_at, createdBy: p.created_by || '' });
    const c = byId.get(p.contact_id); if (c && p.created_at > c.lastActivity) c.lastActivity = p.created_at;
    result.transactions.push({ id: `p${p.id}`, debtId: p.debt_id, contactId: p.contact_id, contactName: p.contact_name, kind: 'payment', direction: p.direction, amount: money(p.amount_cents), note: p.note, method: p.method, createdAt: p.created_at, createdBy: p.created_by || '' });
  }
  for (const c of result.contacts) { c.net = c.receivable - c.payable; result.totals.receivable += c.receivable; result.totals.payable += c.payable; }
  result.totals.net = result.totals.receivable - result.totals.payable;
  result.transactions.sort((a, b) => b.createdAt - a.createdAt);
  return result;
}

function recordBackup(store, reason) {
  const snapshot = makeSnapshot(store);
  const contactCount = snapshot.contacts.length, debtCount = snapshot.debts.length, paymentCount = snapshot.payments.length;
  if (!contactCount && !debtCount && !paymentCount) return null;
  const result = db.prepare('INSERT INTO store_backups(store_id,created_at,reason,snapshot_json,contact_count,debt_count,payment_count) VALUES(?,?,?,?,?,?,?)')
    .run(store.id, Date.now(), text(reason, 80) || 'قبل التعديل', JSON.stringify(snapshot), contactCount, debtCount, paymentCount);
  db.prepare('DELETE FROM store_backups WHERE store_id=? AND id NOT IN (SELECT id FROM store_backups WHERE store_id=? ORDER BY created_at DESC,id DESC LIMIT 100)').run(store.id, store.id);
  return Number(result.lastInsertRowid);
}

function listBackups(storeId) {
  return db.prepare('SELECT id,created_at,reason,contact_count,debt_count,payment_count FROM store_backups WHERE store_id=? ORDER BY created_at DESC,id DESC LIMIT 100')
    .all(storeId).map(row => ({ id: row.id, createdAt: row.created_at, reason: row.reason, contactCount: row.contact_count, debtCount: row.debt_count, paymentCount: row.payment_count }));
}

function listContactArchives(storeId) {
  return db.prepare('SELECT id,contact_id,contact_name,archived_at,source_revision,debt_count,payment_count,restored_at FROM contact_archives WHERE store_id=? ORDER BY archived_at DESC,id DESC')
    .all(storeId).map(row => ({ id: row.id, contactId: row.contact_id, contactName: row.contact_name,
      archivedAt: row.archived_at, sourceRevision: row.source_revision, debtCount: row.debt_count,
      paymentCount: row.payment_count, restoredAt: row.restored_at }));
}

function archiveDeletedContacts(store, removedContacts, snapshot) {
  const debts = Array.isArray(snapshot.debts) ? snapshot.debts : [];
  const payments = Array.isArray(snapshot.payments) ? snapshot.payments : [];
  const insert = db.prepare('INSERT INTO contact_archives(store_id,contact_id,contact_name,archived_at,source_revision,snapshot_json,debt_count,payment_count) VALUES(?,?,?,?,?,?,?,?)');
  for (const contact of removedContacts) {
    const contactDebts = debts.filter(row => Number(row.contactId) === Number(contact.id));
    const debtIds = new Set(contactDebts.map(row => Number(row.id)));
    const contactPayments = payments.filter(row => debtIds.has(Number(row.debtId)));
    const archivedAt = Date.now();
    insert.run(store.id, Number(contact.id), text(contact.name, 120), archivedAt, Number(store.revision) || 0,
      JSON.stringify({ contact, debts: contactDebts, payments: contactPayments, archivedAt }), contactDebts.length, contactPayments.length);
  }
}

function enforceDeviceCap(storeId, limit) {
  const devices = db.prepare('SELECT device_id FROM store_devices WHERE store_id=? AND revoked_at IS NULL ORDER BY last_seen_at DESC,first_seen_at DESC,device_id').all(storeId);
  const revoked = devices.slice(Math.max(1, Number(limit) || 1));
  const mark = db.prepare('UPDATE store_devices SET revoked_at=? WHERE store_id=? AND device_id=? AND revoked_at IS NULL');
  const removeSessions = db.prepare("DELETE FROM sessions WHERE kind='store' AND principal_id=? AND device_id=?");
  for (const device of revoked) { mark.run(Date.now(), storeId, device.device_id); removeSessions.run(storeId, device.device_id); }
}

function syncSnapshot(store, input, baseRevision, device = null) {
  if (!input || !Array.isArray(input.contacts) || !Array.isArray(input.debts) || !Array.isArray(input.payments)) {
    throw new HttpError(400, 'بيانات المزامنة غير مكتملة. لم يتم تغيير السجل.');
  }
  const contacts = input.contacts;
  const debts = input.debts;
  const payments = input.payments;
  if (contacts.length > 50_000 || debts.length > 100_000 || payments.length > 500_000) throw new HttpError(413, 'عدد السجلات أكبر من الحد.');
  if (Number(baseRevision) !== Number(store.revision)) throw new HttpError(409, 'تغيّرت البيانات على جهاز آخر. حمّل النسخة الأحدث ثم أعد المحاولة.', { snapshot: makeSnapshot(store) });
  const timestamp = (value) => {
    if (value === undefined || value === null || value === '') return Date.now();
    const n = Number(value);
    if (!Number.isSafeInteger(n) || n < 1 || n > Date.now() + 5 * 60_000) throw new HttpError(400, 'يوجد تاريخ سجل غير صالح.');
    return n;
  };
  const assertIds = (rows, label) => {
    const seen = new Set();
    for (const row of rows) {
      const id = Number(row?.id);
      if (!Number.isSafeInteger(id) || id < 1 || seen.has(id)) throw new HttpError(400, `معرّف ${label} غير صالح أو مكرر.`);
      seen.add(id);
    }
    return seen;
  };
  const contactIds = assertIds(contacts, 'الشخص');
  const debtIds = assertIds(debts, 'الدين');
  assertIds(payments, 'الدفعة');
  for (const c of contacts) {
    if (!text(c.name, 120)) throw new HttpError(400, 'يوجد شخص بلا اسم.');
    timestamp(c.createdAt);
    cents(c.creditLimit || 0);
  }
  const uniqueContacts = new Set();
  for (const contact of contacts) {
    const key = `${text(contact.name,120).normalize('NFKC').toLocaleLowerCase('ar')}|${normalizedContactPhone(contact.phone)}`;
    if (uniqueContacts.has(key)) throw new HttpError(400, 'يوجد اسم ورقم هاتف مكرران في سجل المتجر. راجع قائمة الأشخاص قبل المزامنة.');
    uniqueContacts.add(key);
  }
  const previousSnapshot = makeSnapshot(store), previousContacts = new Map(previousSnapshot.contacts.map(item => [Number(item.id), item]));
  const previousDebts = new Map(previousSnapshot.debts.map(item => [Number(item.id), item]));
  const previousPayments = new Map(previousSnapshot.payments.map(item => [Number(item.id), item]));
  const nextContactIds = contactIds, nextDebtIds = debtIds, nextPaymentIds = new Set(payments.map(item => Number(item.id)));
  const removedContacts = previousSnapshot.contacts.filter(contact => !contactIds.has(Number(contact.id)));
  const removedDebts = previousSnapshot.debts.filter(item => !nextDebtIds.has(Number(item.id)));
  const removedPayments = previousSnapshot.payments.filter(item => !nextPaymentIds.has(Number(item.id)));
  const addedContacts = contacts.filter(item => !previousContacts.has(Number(item.id)));
  const addedDebts = debts.filter(item => !previousDebts.has(Number(item.id)));
  const addedPayments = payments.filter(item => !previousPayments.has(Number(item.id)));
  const devicePermissions = {registerPayments:true,deleteRecords:true,deleteContacts:true,...JSON.parse(device?.permissions||'{}')};
  if (removedContacts.length && !devicePermissions.deleteContacts) throw new HttpError(403, `الأدمن منع حذف الأشخاص لهذا الجهاز (${device.staff_name||'مستخدم الجهاز'}).`);
  if ((removedDebts.length || removedPayments.length || (removedContacts.length && previousSnapshot.debts.some(item => removedContacts.some(contact => Number(contact.id) === Number(item.contactId))))) && !devicePermissions.deleteRecords) throw new HttpError(403, `الأدمن منع حذف السجلات لهذا الجهاز (${device.staff_name||'مستخدم الجهاز'}).`);
  if (addedPayments.length && !devicePermissions.registerPayments) throw new HttpError(403, `الأدمن منع تسجيل الدفعات لهذا الجهاز (${device.staff_name||'مستخدم الجهاز'}).`);
  if (addedPayments.length || removedPayments.length) requirePermission(store,'payments');

  // Existing debt and payment rows are immutable: edits must be represented as an
  // authorized deletion plus a new record, so permissions and audit history apply.
  for (const d of debts) {
    if (!contactIds.has(Number(d.contactId))) throw new HttpError(400, 'دين مرتبط بشخص غير موجود.');
    if (!['receivable','payable'].includes(d.direction)) throw new HttpError(400, 'اتجاه الدين غير صالح.');
    const amount = cents(d.amount);
    if (amount < 1) throw new HttpError(400, 'يجب أن يكون مبلغ الدين أكبر من صفر.');
    timestamp(d.createdAt);
    if (d.dueDate && !/^\d{4}-\d{2}-\d{2}$/.test(text(d.dueDate,20))) throw new HttpError(400, 'موعد الاستحقاق غير صالح.');
    const old = previousDebts.get(Number(d.id));
    if (old && (Number(old.contactId) !== Number(d.contactId) || old.direction !== d.direction || cents(old.amount) !== amount
      || text(old.note,500) !== text(d.note,500) || text(old.dueDate,20) !== text(d.dueDate,20) || Number(old.createdAt) !== timestamp(d.createdAt))) {
      throw new HttpError(403, 'لا يمكن تعديل دين مسجل مباشرة. احذف السجل بصلاحية الإدارة ثم أضف سجلًا صحيحًا.');
    }
  }
  for (const p of payments) {
    if (!debtIds.has(Number(p.debtId))) throw new HttpError(400, 'دفعة مرتبطة بدين غير موجود.');
    const amount = cents(p.amount);
    if (amount < 1) throw new HttpError(400, 'يجب أن يكون مبلغ الدفعة أكبر من صفر.');
    if (!['cash','bank','wallet','wallet_jawwal','wallet_palpay'].includes(p.method || 'cash')) throw new HttpError(400, 'طريقة الدفع غير صالحة.');
    timestamp(p.createdAt);
    const old = previousPayments.get(Number(p.id));
    if (old && (Number(old.debtId) !== Number(p.debtId) || cents(old.amount) !== amount || old.method !== (p.method || 'cash')
      || text(old.note,500) !== text(p.note,500) || Number(old.createdAt) !== timestamp(p.createdAt))) {
      throw new HttpError(403, 'لا يمكن تعديل دفعة مسجلة مباشرة. احذف السجل بصلاحية الإدارة ثم أضف سجلًا صحيحًا.');
    }
  }
  const removedDebtIds = new Set(removedDebts.map(row => Number(row.id)));
  for (const contact of removedContacts) {
    const previous = previousContacts.get(Number(contact.id));
    if (Number(previous?.receivable || 0) > 0 || Number(previous?.payable || 0) > 0) {
      throw new HttpError(409, `لا يمكن حذف ${text(contact.name,120)} لوجود دين متبقٍ. سجّل التسديد أولًا.`);
    }
    if (debts.some(row => Number(row.contactId) === Number(contact.id))) throw new HttpError(400, 'يجب حذف سجل الشخص كاملًا قبل حذف الشخص.');
  }
  for (const debt of removedDebts) {
    if (Number(debt.remaining) > 0) throw new HttpError(409, 'لا يمكن حذف دين ما زال عليه مبلغ متبقٍ.');
    if (!removedDebtIds.has(Number(debt.id))) throw new HttpError(400, 'تعذر التحقق من حذف الدين.');
  }

  const paidByDebt = new Map();
  for (const p of payments) paidByDebt.set(Number(p.debtId), (paidByDebt.get(Number(p.debtId)) || 0) + cents(p.amount));
  const activeDebtors = new Set();
  for (const d of debts) {
    const remaining = cents(d.amount) - (paidByDebt.get(Number(d.id)) || 0);
    if (remaining < 0) throw new HttpError(400, 'مجموع دفعات دين تجاوز أصل الدين.');
    if (d.direction === 'receivable' && remaining > 0) activeDebtors.add(Number(d.contactId));
  }
  if (activeDebtors.size > Number(store.debtor_limit)) throw new HttpError(403, `تجاوزت حد ${store.debtor_limit} مدينًا الذي حدده الأدمن.`);
  const contactByDebt = new Map(debts.map(debt => [Number(debt.id), Number(debt.contactId)]));
  const syncReceivedAt = Date.now();
  const newPaymentGroups = new Map();
  for (const payment of addedPayments) {
    const key = [contactByDebt.get(Number(payment.debtId)), nowOr(payment.createdAt), payment.method || 'cash'].join('|');
    const group = newPaymentGroups.get(key) || { contactId: contactByDebt.get(Number(payment.debtId)), createdAt: nowOr(payment.createdAt), method: payment.method || 'cash', amountCents: 0 };
    group.amountCents += cents(payment.amount);
    newPaymentGroups.set(key, group);
  }
  const recentPaymentGroups = new Map();
  const recentRows = db.prepare(`SELECT p.amount_cents,p.method,p.created_at,
      CASE WHEN p.server_received_at>0 THEN p.server_received_at ELSE p.created_at END received_at,d.contact_id
    FROM payments p JOIN debts d ON d.store_id=p.store_id AND d.id=p.debt_id
    WHERE p.store_id=? AND (CASE WHEN p.server_received_at>0 THEN p.server_received_at ELSE p.created_at END)>=?`)
    .all(store.id, syncReceivedAt - 60_000);
  for (const payment of recentRows) {
    const key = [Number(payment.contact_id), Number(payment.created_at), payment.method || 'cash'].join('|');
    const group = recentPaymentGroups.get(key) || { contactId: Number(payment.contact_id), createdAt: Number(payment.created_at), receivedAt: Number(payment.received_at), method: payment.method || 'cash', amountCents: 0 };
    group.amountCents += Number(payment.amount_cents);
    recentPaymentGroups.set(key, group);
  }
  const candidates = Array.from(newPaymentGroups.values());
  for (let i = 0; i < candidates.length; i++) {
    const candidate = candidates[i];
    const duplicatePrior = Array.from(recentPaymentGroups.values()).some(prior => prior.contactId === candidate.contactId
      && prior.method === candidate.method && prior.amountCents === candidate.amountCents
      && prior.receivedAt <= syncReceivedAt && syncReceivedAt - prior.receivedAt <= 60_000);
    const duplicateBatch = candidates.slice(i + 1).some(other => other.contactId === candidate.contactId
      && other.method === candidate.method && other.amountCents === candidate.amountCents
      && Math.abs(other.createdAt - candidate.createdAt) <= 60_000);
    if (duplicatePrior || duplicateBatch) throw new HttpError(400, 'توجد دفعة مطابقة مسجلة لهذا الشخص خلال آخر دقيقة. انتظر دقيقة قبل إعادة المحاولة لتجنب التكرار.');
  }
  const actor = text(device?.staff_name,80) || 'صاحب المتجر';
  const contactNameById = new Map(contacts.map(item => [Number(item.id), text(item.name,120)]));
  const editedContacts = contacts.filter(item => {
    const old = previousContacts.get(Number(item.id));
    return old && (text(old.name,120) !== text(item.name,120) || text(old.phone,40) !== text(item.phone,40)
      || text(old.category,40) !== text(item.category,40) || text(old.note,500) !== text(item.note,500)
      || !!old.whatsappOptIn !== (item.whatsappOptIn === true) || cents(old.creditLimit || 0) !== cents(item.creditLimit || 0));
  });
  tx(() => {
    recordBackup(store, removedContacts.length ? 'قبل حذف زبون من التطبيق' : 'قبل مزامنة التطبيق');
    if (removedContacts.length) archiveDeletedContacts(store, removedContacts, previousSnapshot);
    const deletePayment = db.prepare('DELETE FROM payments WHERE store_id=? AND id=?');
    for (const item of removedPayments) deletePayment.run(store.id, Number(item.id));
    const deleteDebt = db.prepare('DELETE FROM debts WHERE store_id=? AND id=?');
    for (const item of removedDebts) deleteDebt.run(store.id, Number(item.id));
    const deleteContact = db.prepare('DELETE FROM contacts WHERE store_id=? AND id=?');
    for (const item of removedContacts) deleteContact.run(store.id, Number(item.id));
    const putContact = db.prepare(`INSERT INTO contacts(store_id,id,name,phone,category,note,whatsapp_opt_in,credit_limit_cents,created_by,created_at)
      VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(store_id,id) DO UPDATE SET name=excluded.name,phone=excluded.phone,category=excluded.category,
      note=excluded.note,whatsapp_opt_in=excluded.whatsapp_opt_in,credit_limit_cents=excluded.credit_limit_cents`);
    for (const c of contacts) {
      const old = previousContacts.get(Number(c.id)); const createdBy = old?.createdBy || actor;
      putContact.run(store.id, Number(c.id), text(c.name,120), text(c.phone,40), text(c.category,40), text(c.note,500), c.whatsappOptIn === true ? 1 : 0, cents(c.creditLimit || 0), createdBy, old ? Number(old.createdAt) : timestamp(c.createdAt));
    }
    const putDebt = db.prepare('INSERT INTO debts(store_id,id,contact_id,direction,amount_cents,note,due_date,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?)');
    for (const d of addedDebts) {
      putDebt.run(store.id, Number(d.id), Number(d.contactId), d.direction, cents(d.amount), text(d.note,500), text(d.dueDate,20), actor, timestamp(d.createdAt));
    }
    const putPayment = db.prepare('INSERT INTO payments(store_id,id,debt_id,amount_cents,method,note,created_by,created_at,server_received_at) VALUES(?,?,?,?,?,?,?,?,?)');
    for (const p of addedPayments) {
      putPayment.run(store.id, Number(p.id), Number(p.debtId), cents(p.amount), p.method || 'cash', text(p.note,500), actor, timestamp(p.createdAt), syncReceivedAt);
    }
    const audit = db.prepare('INSERT INTO audit_events(store_id,device_id,actor,action,description,created_at) VALUES(?,?,?,?,?,?)');
    const recordAudit = (action, description) => audit.run(store.id, device?.device_id || '', actor, action, text(description,500), Date.now());
    const auditEvents = [];
    for (const item of addedContacts) auditEvents.push(['إضافة شخص', `أضاف ${text(item.name,120)} إلى الأشخاص.`]);
    for (const item of editedContacts) auditEvents.push(['تعديل شخص', `عدّل بيانات ${text(item.name,120)}.`]);
    for (const item of addedDebts) auditEvents.push(['تسجيل دين', `سجّل دينًا بقيمة ${money(cents(item.amount))} ₪ على ${contactNameById.get(Number(item.contactId))||'شخص'}.`]);
    for (const item of addedPayments) {
      const contactId = contactByDebt.get(Number(item.debtId));
      auditEvents.push(['تسجيل دفعة', `سجّل دفعة بقيمة ${money(cents(item.amount))} ₪ لـ ${contactNameById.get(contactId)||'شخص'}.`]);
    }
    for (const item of removedContacts) auditEvents.push(['حذف زبون', `حذف ${text(item.name,120)} وسجله.`]);
    for (const item of removedDebts) auditEvents.push(['حذف سجل', `حذف سجل دين لـ ${contactNameById.get(Number(item.contactId))||'شخص'}.`]);
    for (const item of removedPayments) auditEvents.push(['حذف سجل', `حذف دفعة لـ ${text(item.contactName,120)||'شخص'}.`]);
    for (const [action,description] of auditEvents.slice(0,300)) recordAudit(action,description);
    if (auditEvents.length>300) recordAudit('ملخص حركات', `تم تسجيل ${auditEvents.length} حركة في مزامنة واحدة.`);
    db.prepare('DELETE FROM audit_events WHERE store_id=? AND id NOT IN (SELECT id FROM audit_events WHERE store_id=? ORDER BY created_at DESC,id DESC LIMIT 5000)').run(store.id,store.id);
    db.prepare('UPDATE stores SET revision=revision+1 WHERE id=?').run(store.id);
  });
  return db.prepare('SELECT * FROM stores WHERE id=?').get(store.id);
}

function restoreArchivedContact(store, archive) {
  if (archive.restored_at) throw new HttpError(409, 'تمت استعادة هذا السجل مسبقًا.');
  const snapshot = JSON.parse(archive.snapshot_json);
  const contact = snapshot.contact;
  if (!contact || !text(contact.name, 120)) throw new HttpError(500, 'بيانات النسخة المؤرشفة غير صالحة.');
  const contactId = Number(contact.id);
  if (!Number.isSafeInteger(contactId) || contactId < 1) throw new HttpError(500, 'معرّف الزبون المؤرشف غير صالح.');
  if (db.prepare('SELECT 1 FROM contacts WHERE store_id=? AND id=?').get(store.id, contactId)) throw new HttpError(409, 'يوجد سجل حالي بهذا المعرّف. حدّث سجلات المتجر قبل الاستعادة.');
  const debts = Array.isArray(snapshot.debts) ? snapshot.debts : [];
  const payments = Array.isArray(snapshot.payments) ? snapshot.payments : [];
  tx(() => {
    recordBackup(store, 'قبل استعادة زبون محذوف');
    db.prepare('INSERT INTO contacts(store_id,id,name,phone,category,note,whatsapp_opt_in,credit_limit_cents,created_at) VALUES(?,?,?,?,?,?,?,?,?)')
      .run(store.id, contactId, text(contact.name,120), text(contact.phone,40), text(contact.category,40), text(contact.note,500), contact.whatsappOptIn ? 1 : 0, cents(contact.creditLimit || 0), nowOr(contact.createdAt));
    const debtMap = new Map();
    let nextDebtId = Number(db.prepare('SELECT COALESCE(MAX(id),0)+1 n FROM debts WHERE store_id=?').get(store.id).n);
    const addDebt = db.prepare('INSERT INTO debts(store_id,id,contact_id,direction,amount_cents,note,due_date,created_at) VALUES(?,?,?,?,?,?,?,?)');
    for (const debt of debts) {
      let targetId = Number(debt.id);
      if (db.prepare('SELECT 1 FROM debts WHERE store_id=? AND id=?').get(store.id,targetId)) targetId = nextDebtId++;
      debtMap.set(Number(debt.id), targetId);
      addDebt.run(store.id,targetId,contactId,debt.direction==='payable'?'payable':'receivable',cents(debt.amount),text(debt.note,500),text(debt.dueDate,20),nowOr(debt.createdAt));
    }
    let nextPaymentId = Number(db.prepare('SELECT COALESCE(MAX(id),0)+1 n FROM payments WHERE store_id=?').get(store.id).n);
    const addPayment = db.prepare('INSERT INTO payments(store_id,id,debt_id,amount_cents,method,note,created_at) VALUES(?,?,?,?,?,?,?)');
    for (const payment of payments) {
      const targetDebtId = debtMap.get(Number(payment.debtId));
      if (!targetDebtId) continue;
      let targetId = Number(payment.id);
      if (db.prepare('SELECT 1 FROM payments WHERE store_id=? AND id=?').get(store.id,targetId)) targetId = nextPaymentId++;
      addPayment.run(store.id,targetId,targetDebtId,cents(payment.amount),['cash','bank','wallet','wallet_jawwal','wallet_palpay'].includes(payment.method)?payment.method:'cash',text(payment.note,500),nowOr(payment.createdAt));
    }
    db.prepare('UPDATE stores SET revision=revision+1 WHERE id=?').run(store.id);
    db.prepare('UPDATE contact_archives SET restored_at=? WHERE id=? AND store_id=?').run(Date.now(),archive.id,store.id);
  });
  return db.prepare('SELECT * FROM stores WHERE id=?').get(store.id);
}

function restoreStoreBackup(store, backup, contactId = null) {
  const snapshot = JSON.parse(backup.snapshot_json);
  const contacts = Array.isArray(snapshot.contacts) ? snapshot.contacts : [];
  const debts = Array.isArray(snapshot.debts) ? snapshot.debts : [];
  const payments = Array.isArray(snapshot.payments) ? snapshot.payments : [];
  const selectedContact = contactId == null ? null : contacts.find(item => Number(item.id) === Number(contactId));
  if (contactId != null && !selectedContact) throw new HttpError(404, 'هذا الشخص غير موجود في النسخة الاحتياطية المختارة.');
  tx(() => {
    recordBackup(store, contactId == null ? 'قبل استعادة نسخة المتجر' : 'قبل استعادة سجل شخص');
    if (contactId == null) {
      db.prepare('DELETE FROM payments WHERE store_id=?').run(store.id);
      db.prepare('DELETE FROM debts WHERE store_id=?').run(store.id);
      db.prepare('DELETE FROM contacts WHERE store_id=?').run(store.id);
      const addContact = db.prepare('INSERT INTO contacts(store_id,id,name,phone,category,note,whatsapp_opt_in,credit_limit_cents,created_at) VALUES(?,?,?,?,?,?,?,?,?)');
      for (const c of contacts) addContact.run(store.id, Number(c.id), text(c.name,120), text(c.phone,40), text(c.category,40), text(c.note,500), c.whatsappOptIn ? 1 : 0, cents(c.creditLimit || 0), nowOr(c.createdAt));
      const addDebt = db.prepare('INSERT INTO debts(store_id,id,contact_id,direction,amount_cents,note,due_date,created_at) VALUES(?,?,?,?,?,?,?,?)');
      for (const d of debts) addDebt.run(store.id, Number(d.id), Number(d.contactId), d.direction === 'payable' ? 'payable' : 'receivable', cents(d.amount), text(d.note,500), text(d.dueDate,20), nowOr(d.createdAt));
      const addPayment = db.prepare('INSERT INTO payments(store_id,id,debt_id,amount_cents,method,note,created_at) VALUES(?,?,?,?,?,?,?)');
      for (const p of payments) addPayment.run(store.id, Number(p.id), Number(p.debtId), cents(p.amount), ['cash','bank','wallet','wallet_jawwal','wallet_palpay'].includes(p.method) ? p.method : 'cash', text(p.note,500), nowOr(p.createdAt));
    } else {
      const id = Number(contactId);
      db.prepare('DELETE FROM contacts WHERE store_id=? AND id=?').run(store.id, id);
      db.prepare('INSERT INTO contacts(store_id,id,name,phone,category,note,whatsapp_opt_in,credit_limit_cents,created_at) VALUES(?,?,?,?,?,?,?,?,?)')
        .run(store.id, id, text(selectedContact.name,120), text(selectedContact.phone,40), text(selectedContact.category,40), text(selectedContact.note,500), selectedContact.whatsappOptIn ? 1 : 0, cents(selectedContact.creditLimit || 0), nowOr(selectedContact.createdAt));
      const oldDebts = debts.filter(item => Number(item.contactId) === id);
      const debtIds = new Map();
      const nextDebtId = Number(db.prepare('SELECT COALESCE(MAX(id),0)+1 n FROM debts WHERE store_id=?').get(store.id).n);
      let candidateDebtId = nextDebtId;
      const addDebt = db.prepare('INSERT INTO debts(store_id,id,contact_id,direction,amount_cents,note,due_date,created_at) VALUES(?,?,?,?,?,?,?,?)');
      for (const d of oldDebts) {
        let targetId = Number(d.id);
        if (db.prepare('SELECT 1 FROM debts WHERE store_id=? AND id=?').get(store.id,targetId)) targetId = candidateDebtId++;
        debtIds.set(Number(d.id), targetId);
        addDebt.run(store.id, targetId, id, d.direction === 'payable' ? 'payable' : 'receivable', cents(d.amount), text(d.note,500), text(d.dueDate,20), nowOr(d.createdAt));
      }
      const oldDebtIds = new Set(oldDebts.map(item => Number(item.id)));
      const oldPayments = payments.filter(item => oldDebtIds.has(Number(item.debtId)));
      const nextPaymentId = Number(db.prepare('SELECT COALESCE(MAX(id),0)+1 n FROM payments WHERE store_id=?').get(store.id).n);
      let candidatePaymentId = nextPaymentId;
      const addPayment = db.prepare('INSERT INTO payments(store_id,id,debt_id,amount_cents,method,note,created_at) VALUES(?,?,?,?,?,?,?)');
      for (const p of oldPayments) {
        let targetId = Number(p.id);
        if (db.prepare('SELECT 1 FROM payments WHERE store_id=? AND id=?').get(store.id,targetId)) targetId = candidatePaymentId++;
        addPayment.run(store.id, targetId, debtIds.get(Number(p.debtId)), cents(p.amount), ['cash','bank','wallet','wallet_jawwal','wallet_palpay'].includes(p.method) ? p.method : 'cash', text(p.note,500), nowOr(p.createdAt));
      }
    }
    db.prepare('UPDATE stores SET revision=revision+1 WHERE id=?').run(store.id);
  });
  return db.prepare('SELECT * FROM stores WHERE id=?').get(store.id);
}

function deriveEncryptionKey() {
  const raw = serverEnv.TOKEN_ENCRYPTION_KEY || '';
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
    if (!serverEnv.INITIAL_ADMIN_KEY || !safeEqual(crypto.createHash('sha256').update(text(b.setupKey,200)).digest('hex'), crypto.createHash('sha256').update(serverEnv.INITIAL_ADMIN_KEY).digest('hex'))) {
      recordLoginFailure(req, 'admin-setup'); throw new HttpError(403, 'مفتاح التأسيس غير صحيح.');
    }
    const username = text(b.username,80); const password = String(b.password || '');
    if (!/^[\p{L}\p{N}._-]{3,80}$/u.test(username) || password.length < ADMIN_PASSWORD_MIN || password.length > ADMIN_PASSWORD_MAX) throw new HttpError(400, `اسم المستخدم يجب أن يكون صالحًا وكلمة مرور الأدمن بين ${ADMIN_PASSWORD_MIN} و${ADMIN_PASSWORD_MAX} خانة.`);
    const ph = await passwordHash(password); const created = db.prepare('INSERT INTO admins(username,salt,password_hash,created_at) VALUES(?,?,?,?)').run(username,ph.salt,ph.hash,Date.now());
    clearLoginFailures(req, 'admin-setup'); const session = makeSession('admin',Number(created.lastInsertRowid)); return ok(res,{...session,admin:{username}});
  }
  if (req.method === 'POST' && pathname === '/api/admin/login') {
    const b=await bodyJson(req); const user=text(b.username,80); const identity=`admin:${user}`; checkLoginLimit(req, identity);
    const admin=db.prepare('SELECT * FROM admins WHERE username=? COLLATE NOCASE').get(user);
    const password=String(b.password||'');
    const withinLimit = password.length <= ADMIN_PASSWORD_MAX;
    const ph=await passwordHash(withinLimit ? password : password.slice(0,ADMIN_PASSWORD_MAX),admin?.salt || dummyCredentialSalt());
    if (!admin || !withinLimit || !safeEqual(ph.hash,admin.password_hash)) { recordLoginFailure(req, identity); throw new HttpError(401,'اسم المستخدم أو كلمة المرور غير صحيحة.'); }
    clearLoginFailures(req, identity);
    return ok(res,{...makeSession('admin',admin.id),admin:{username:admin.username},forcePasswordChange:!!admin.force_password_change});
  }
  if (req.method === 'POST' && pathname === '/api/mobile/login') {
    const b=await bodyJson(req); const user=text(b.username,80); const identity=`store:${user}`; checkLoginLimit(req, identity);
    let store=db.prepare('SELECT * FROM stores WHERE username=? COLLATE NOCASE').get(user);
    const password=String(b.password||'');
    const withinLimit = password.length <= ADMIN_PASSWORD_MAX;
    const ph=await passwordHash(withinLimit ? password : password.slice(0,ADMIN_PASSWORD_MAX),store?.salt || dummyCredentialSalt());
    if (!store || !withinLimit || !safeEqual(ph.hash,store.password_hash)) { recordLoginFailure(req, identity); throw new HttpError(401,'اسم المستخدم أو كلمة المرور غير صحيحة.'); }
    clearLoginFailures(req, identity);
    store=refreshStoreStatus(store);
    if(store.status!=='active') {
      const state=subscriptionState(store);
      if(state.mode==='paused') throw new HttpError(423,'الاشتراك موقوف. يفعّل الأدمن الاشتراك أو يجدده.');
      if(state.mode==='timed'&&!state.active) throw new HttpError(423,'انتهت مدة الاشتراك. تواصل مع الأدمن لتجديده.');
      throw new HttpError(423,'الحساب مجمّد من الأدمن. تواصل معه لإعادة التفعيل.');
    }
    const deviceId=text(b.deviceId,120), deviceLabel=text(b.deviceName,120), requestedStaff=text(b.staffName,80);
    if(!/^[A-Za-z0-9._:-]{16,120}$/.test(deviceId)) throw new HttpError(400,'تعذر التعرف على هذا الجهاز. حدّث التطبيق وحاول مجددًا.');
    const existing=db.prepare('SELECT * FROM store_devices WHERE store_id=? AND device_id=?').get(store.id,deviceId);
    if(existing&&existing.revoked_at) throw new HttpError(403,'تم إلغاء هذا الجهاز من الأدمن. اطلب السماح به مجددًا.');
    const bound=Number(db.prepare('SELECT COUNT(*) n FROM store_devices WHERE store_id=? AND revoked_at IS NULL').get(store.id).n);
    if(!existing&&bound>=Number(store.allowed_devices||1)) throw new HttpError(403,`وصل الحساب إلى حد الأجهزة المسموح (${store.allowed_devices}). اطلب من الأدمن زيادة العدد أو السماح بهذا الجهاز.`);
    const requiresStaffName=Number(store.allowed_devices||1)>1;
    if(requiresStaffName&&(!existing||!existing.staff_name)&&requestedStaff.length<2) throw new HttpError(409,'أدخل اسم الشخص الذي سيستخدم هذا الجهاز ليظهر اسمه في السجلات. لا يمكن تغييره بعد التسجيل إلا من الأدمن.');
    const staffName=existing?.staff_name|| requestedStaff;
    if(requestedStaff.length>0&&(requestedStaff.length<2||/[\u0000-\u001f]/.test(requestedStaff))) throw new HttpError(400,'اكتب اسمًا من خانتين على الأقل بدون رموز تحكم.');
    tx(()=>{
      if(existing) {
        db.prepare('UPDATE store_devices SET last_seen_at=?,staff_name=CASE WHEN staff_name=\'\' THEN ? ELSE staff_name END WHERE store_id=? AND device_id=?').run(Date.now(),staffName,store.id,deviceId);
      } else db.prepare('INSERT INTO store_devices(store_id,device_id,label,staff_name,permissions,first_seen_at,last_seen_at) VALUES(?,?,?,?,?,?,?)')
        .run(store.id,deviceId,deviceLabel,staffName,JSON.stringify({registerPayments:true,deleteRecords:true,deleteContacts:true}),Date.now(),Date.now());
    });
    const session=makeSession('store',store.id,deviceId); const latest=db.prepare('SELECT * FROM stores WHERE id=?').get(store.id);
    const snapshot=makeSnapshot(latest);
    const device=db.prepare('SELECT staff_name,permissions FROM store_devices WHERE store_id=? AND device_id=?').get(store.id,deviceId);
    const deviceInfo={staffName:device.staff_name||'صاحب المتجر',permissions:{registerPayments:true,deleteRecords:true,deleteContacts:true,...JSON.parse(device.permissions||'{}')}};
    return ok(res,{...session,forcePasswordChange:!!latest.force_password_change,snapshot,account:snapshot.account,device:deviceInfo,deviceStaffName:deviceInfo.staffName});
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
      if(password.length<ADMIN_PASSWORD_MIN || password.length>ADMIN_PASSWORD_MAX) throw new HttpError(400,`كلمة مرور الأدمن يجب أن تكون بين ${ADMIN_PASSWORD_MIN} و${ADMIN_PASSWORD_MAX} خانة.`);
      if(currentPassword.length>ADMIN_PASSWORD_MAX) throw new HttpError(403,'كلمة المرور الحالية غير صحيحة.');
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
      for (const item of db.prepare("SELECT * FROM stores WHERE status!='deleted'").all()) refreshStoreStatus(item);
      const stores=db.prepare("SELECT COUNT(*) n FROM stores WHERE status!='deleted'").get().n;
      const active=db.prepare("SELECT COUNT(*) n FROM stores WHERE status='active'").get().n;
      const trusted=db.prepare('SELECT COUNT(*) n FROM stores WHERE verified=1 AND status=\'active\'').get().n;
      const led=db.prepare('SELECT COUNT(*) n FROM contacts').get().n;
      const debt=db.prepare('SELECT COALESCE(SUM(amount_cents),0) n FROM debts').get().n;
      return ok(res,{overview:{stores,active,trusted,contacts:led,debts:money(debt)},meta:{username:auth.principal.username}});
    }
    if (req.method==='GET' && pathname==='/api/admin/stores') {
      for (const item of db.prepare("SELECT * FROM stores WHERE status!='deleted'").all()) refreshStoreStatus(item);
      const rows=db.prepare(`SELECT s.id,s.name,s.username,s.status,s.suspend_until,s.subscription_mode,s.subscription_started_at,s.subscription_expires_at,s.allowed_devices,s.verified,s.debtor_limit,s.permissions,s.whatsapp_enabled,s.revision,s.created_at,
        (SELECT COUNT(*) FROM contacts c WHERE c.store_id=s.id) contacts,
        (SELECT COUNT(*) FROM debts d WHERE d.store_id=s.id) debt_count,
        (SELECT COALESCE(SUM(d.amount_cents),0) FROM debts d WHERE d.store_id=s.id) debt_cents,
        (SELECT COUNT(*) FROM store_devices dv WHERE dv.store_id=s.id AND dv.revoked_at IS NULL) device_count
        FROM stores s ORDER BY s.created_at DESC`).all();
      return ok(res,{stores:rows.map(s=>({...s,verified:!!s.verified,whatsappEnabled:!!s.whatsapp_enabled,debtorsLimit:s.debtor_limit,permissions:JSON.parse(s.permissions),contacts:s.contacts,debts:s.debt_count,debtTotal:money(s.debt_cents),subscriptionMode:s.subscription_mode,subscriptionStartedAt:s.subscription_started_at,subscriptionExpiresAt:s.subscription_expires_at,subscriptionRemainingMs:subscriptionState(s).remainingMs,maxDevices:s.allowed_devices,boundDevices:s.device_count}))});
    }
    if (req.method==='POST' && pathname==='/api/admin/stores') {
      const b=await bodyJson(req); const name=text(b.name,120); const username=text(b.username,80); const password=String(b.password||'');
      if(!name || !/^[\p{L}\p{N}._-]{3,80}$/u.test(username) || password.length<12 || password.length>ADMIN_PASSWORD_MAX) throw new HttpError(400,'أدخل اسم المتجر واسم مستخدم وكلمة مؤقتة بين 12 و128 محرفًا.');
      const mode=['permanent','timed','paused'].includes(b.subscriptionMode)?b.subscriptionMode:'timed';
      const days=Math.floor(Number(b.subscriptionDays)||0); const maxDevices=parseDeviceLimit(b.maxDevices, 1); const now=Date.now();
      if(mode==='timed'&&(days<1||days>36500)) throw new HttpError(400,'حدد مدة اشتراك بين يوم واحد و100 سنة، أو اختر فعال دائمًا.');
      const ph=await passwordHash(password);
      const defaults={contacts:true,debts:true,payments:true,reports:true,analytics:true,export:true,whatsapp:true,appLock:true};
      const permissions={...defaults,...(b.permissions||{})};
      const debtorLimit=b.debtorLimit===undefined?100:Math.max(0,Math.min(1_000_000,Math.floor(Number(b.debtorLimit)||0)));
      const insert=db.prepare('INSERT INTO stores(name,username,salt,password_hash,force_password_change,status,debtor_limit,subscription_mode,subscription_started_at,subscription_expires_at,allowed_devices,permissions,created_at) VALUES(?,?,?,?,1,?,?,?,?,?,?,?,?)');
      const row=insert.run(name,username,ph.salt,ph.hash,mode==='paused'?'suspended':'active',debtorLimit,mode,mode==='permanent'?null:now,mode==='timed'?now+days*86400000:null,maxDevices,JSON.stringify(permissions),now);
      return send(res,201,{ok:true,id:Number(row.lastInsertRowid),username,password,forcePasswordChange:true});
    }
    const match=pathname.match(/^\/api\/admin\/stores\/(\d+)(?:\/(.*))?$/);
    if(match){
      const id=Number(match[1]); const action=match[2]||''; let store=db.prepare('SELECT * FROM stores WHERE id=?').get(id); if(!store) throw new HttpError(404,'المتجر غير موجود.');
      store=refreshStoreStatus(store);
      if(req.method==='GET' && action==='') return ok(res,{store:{id:store.id,name:store.name,username:store.username,status:store.status,suspendUntil:store.suspend_until,verified:!!store.verified,debtorLimit:store.debtor_limit,permissions:JSON.parse(store.permissions),whatsappEnabled:!!store.whatsapp_enabled,revision:store.revision,createdAt:store.created_at,subscriptionMode:store.subscription_mode,subscriptionStartedAt:store.subscription_started_at,subscriptionExpiresAt:store.subscription_expires_at,subscriptionRemainingMs:subscriptionState(store).remainingMs,maxDevices:Number(store.allowed_devices||1)},snapshot:makeSnapshot(store),devices:db.prepare('SELECT device_id,label,staff_name,permissions,first_seen_at,last_seen_at,revoked_at FROM store_devices WHERE store_id=? ORDER BY revoked_at IS NULL DESC,last_seen_at DESC').all(id).map(d=>({id:d.device_id,label:d.label,staffName:d.staff_name||'',permissions:{registerPayments:true,deleteRecords:true,deleteContacts:true,...JSON.parse(d.permissions||'{}')},firstSeenAt:d.first_seen_at,lastSeenAt:d.last_seen_at,revokedAt:d.revoked_at})),auditEvents:db.prepare('SELECT actor,action,description,created_at,device_id FROM audit_events WHERE store_id=? ORDER BY created_at DESC,id DESC LIMIT 200').all(id).map(e=>({actor:e.actor,action:e.action,description:e.description,createdAt:e.created_at,deviceId:e.device_id})),backups:listBackups(id),contactArchives:listContactArchives(id)});
      if(req.method==='GET' && action==='archives') return ok(res,{archives:listContactArchives(id)});
      if(req.method==='POST' && /^archives\/\d+\/restore$/.test(action)){
        const archiveId=Number(action.split('/')[1]);
        const archive=db.prepare('SELECT * FROM contact_archives WHERE id=? AND store_id=?').get(archiveId,id);
        if(!archive) throw new HttpError(404,'نسخة الزبون المحذوف غير موجودة.');
        const changed=restoreArchivedContact(store,archive);
        return ok(res,{restored:true,revision:changed.revision,snapshot:makeSnapshot(changed),archives:listContactArchives(id)});
      }
      if(req.method==='GET' && /^backups\/\d+$/.test(action)){
        const backupId=Number(action.split('/')[1]); const backup=db.prepare('SELECT id,created_at,reason,snapshot_json,contact_count,debt_count,payment_count FROM store_backups WHERE id=? AND store_id=?').get(backupId,id);
        if(!backup) throw new HttpError(404,'النسخة الاحتياطية غير موجودة.');
        return ok(res,{backup:{id:backup.id,createdAt:backup.created_at,reason:backup.reason,contactCount:backup.contact_count,debtCount:backup.debt_count,paymentCount:backup.payment_count},snapshot:JSON.parse(backup.snapshot_json)});
      }
      if(req.method==='POST' && /^backups\/\d+\/restore$/.test(action)){
        const backupId=Number(action.split('/')[1]); const backup=db.prepare('SELECT * FROM store_backups WHERE id=? AND store_id=?').get(backupId,id);
        if(!backup) throw new HttpError(404,'النسخة الاحتياطية غير موجودة.');
        const b=await bodyJson(req); const contactId=b.contactId===undefined||b.contactId===null?null:Number(b.contactId);
        if(contactId!==null&&(!Number.isSafeInteger(contactId)||contactId<1)) throw new HttpError(400,'معرّف الشخص غير صالح.');
        const changed=restoreStoreBackup(store,backup,contactId);
        return ok(res,{restored:true,revision:changed.revision,snapshot:makeSnapshot(changed)});
      }
      if(req.method==='POST' && action==='subscription'){
        const b=await bodyJson(req); const mode=b.mode;
        if(!['permanent','timed','paused'].includes(mode)) throw new HttpError(400,'اختر مدة اشتراك صحيحة.');
        const now=Date.now(); let started=store.subscription_started_at; let expires=store.subscription_expires_at; let pausedRemaining=store.subscription_paused_remaining_ms; let status=store.status; let suspendUntil=store.suspend_until;
        if(mode==='permanent'){started=null;expires=null;pausedRemaining=null;status='active';suspendUntil=null;}
        else if(mode==='paused'){
          if(store.subscription_mode==='timed') pausedRemaining=subscriptionState(store,now).remainingMs;
          status='suspended';suspendUntil=null;
        }
        else {
          const days=Math.floor(Number(b.days)||0);
          if(days<1||days>36500) throw new HttpError(400,'حدد عدد أيام بين 1 و36500.');
          const base=b.extend?(store.subscription_mode==='paused'?now+Math.max(0,Number(pausedRemaining)||0):Math.max(now,Number(expires)||0)):now;
          expires=base+days*86400000; started=started||now; pausedRemaining=null; status='active'; suspendUntil=null;
        }
        db.prepare('UPDATE stores SET subscription_mode=?,subscription_started_at=?,subscription_expires_at=?,subscription_paused_remaining_ms=?,status=?,suspend_until=?,revision=revision+1 WHERE id=?').run(mode,started,expires,pausedRemaining,status,suspendUntil,id);
        if(status!=='active') db.prepare("DELETE FROM sessions WHERE kind='store' AND principal_id=?").run(id);
        const updated=db.prepare('SELECT * FROM stores WHERE id=?').get(id);
        return ok(res,{store:{subscriptionMode:updated.subscription_mode,subscriptionStartedAt:updated.subscription_started_at,subscriptionExpiresAt:updated.subscription_expires_at,subscriptionRemainingMs:subscriptionState(updated).remainingMs,status:updated.status}});
      }
      if(req.method==='GET' && action==='devices') return ok(res,{devices:db.prepare('SELECT device_id,label,staff_name,permissions,first_seen_at,last_seen_at,revoked_at FROM store_devices WHERE store_id=? ORDER BY revoked_at IS NULL DESC,last_seen_at DESC').all(id).map(d=>({id:d.device_id,label:d.label,staffName:d.staff_name||'',permissions:{registerPayments:true,deleteRecords:true,deleteContacts:true,...JSON.parse(d.permissions||'{}')},firstSeenAt:d.first_seen_at,lastSeenAt:d.last_seen_at,revokedAt:d.revoked_at}))});
      const devicePermissionsMatch=action.match(/^devices\/([A-Za-z0-9._:%-]+)\/permissions$/);
      if(req.method==='POST'&&devicePermissionsMatch){const deviceId=decodeURIComponent(devicePermissionsMatch[1]);const device=db.prepare('SELECT * FROM store_devices WHERE store_id=? AND device_id=?').get(id,deviceId);if(!device)throw new HttpError(404,'الجهاز غير معروف لهذا الحساب.');const b=await bodyJson(req);const current={registerPayments:true,deleteRecords:true,deleteContacts:true,...JSON.parse(device.permissions||'{}')};for(const key of ['registerPayments','deleteRecords','deleteContacts'])if(typeof b[key]==='boolean')current[key]=b[key];db.prepare('UPDATE store_devices SET permissions=? WHERE store_id=? AND device_id=?').run(JSON.stringify(current),id,deviceId);return ok(res,{permissions:current});}
      const deleteDevice=action.match(/^devices\/([A-Za-z0-9._:%-]+)\/delete$/);
      if(req.method==='DELETE'&&deleteDevice){const deviceId=decodeURIComponent(deleteDevice[1]);db.prepare("DELETE FROM sessions WHERE kind='store' AND principal_id=? AND device_id=?").run(id,deviceId);const removed=db.prepare('DELETE FROM store_devices WHERE store_id=? AND device_id=?').run(id,deviceId);if(!removed.changes)throw new HttpError(404,'الجهاز غير معروف لهذا الحساب.');return ok(res,{deleted:true});}
      if(req.method==='POST' && action==='devices/revoke-all'){
        const now=Date.now(); db.prepare('UPDATE store_devices SET revoked_at=? WHERE store_id=? AND revoked_at IS NULL').run(now,id);
        db.prepare("DELETE FROM sessions WHERE kind='store' AND principal_id=?").run(id);
        return ok(res,{revoked:true});
      }
      const revokeDevice=action.match(/^devices\/([A-Za-z0-9._:%-]+)\/revoke$/);
      if(req.method==='POST'&&revokeDevice){const deviceId=decodeURIComponent(revokeDevice[1]);db.prepare('UPDATE store_devices SET revoked_at=? WHERE store_id=? AND device_id=? AND revoked_at IS NULL').run(Date.now(),id,deviceId);db.prepare("DELETE FROM sessions WHERE kind='store' AND principal_id=? AND device_id=?").run(id,deviceId);return ok(res,{revoked:true});}
      const allowDevice=action.match(/^devices\/([A-Za-z0-9._:%-]+)\/allow$/);
      if(req.method==='POST'&&allowDevice){const deviceId=decodeURIComponent(allowDevice[1]);const device=db.prepare('SELECT * FROM store_devices WHERE store_id=? AND device_id=?').get(id,deviceId);if(!device)throw new HttpError(404,'الجهاز غير معروف لهذا الحساب.');const active=Number(db.prepare('SELECT COUNT(*) n FROM store_devices WHERE store_id=? AND revoked_at IS NULL').get(id).n);if(device.revoked_at&&active>=Number(store.allowed_devices||1))throw new HttpError(409,'وصلت إلى حد الأجهزة المسموح. ارفع الحد أو ألغِ جهازًا آخر.');db.prepare('UPDATE store_devices SET revoked_at=NULL WHERE store_id=? AND device_id=?').run(id,deviceId);return ok(res,{allowed:true});}
      if(req.method==='POST' && action==='update'){
        const b=await bodyJson(req); const nextStatus=['active','suspended'].includes(b.status)?b.status:store.status;
        const until=nextStatus==='suspended'?(Number(b.suspendUntil)||null):null;
        if(nextStatus==='active'&&!subscriptionState(store).active) throw new HttpError(409,'الاشتراك موقوف أو منتهٍ. فعّل الاشتراك أو جدده أولاً.');
        const permissionNames=['contacts','debts','payments','reports','analytics','export','whatsapp','appLock']; let perms=JSON.parse(store.permissions||'{}');
        if(b.permissions&&typeof b.permissions==='object') for(const p of permissionNames) if(typeof b.permissions[p]==='boolean') perms[p]=b.permissions[p];
        const newName=b.name===undefined?store.name:text(b.name,120); const newUsername=b.username===undefined?store.username:text(b.username,80);
        if(!newName||!/^[\p{L}\p{N}._-]{3,80}$/u.test(newUsername)) throw new HttpError(400,'أدخل اسم متجر واسم مستخدم صالحين.');
        const requestedDeviceLimit=b.maxDevices??b.allowedDevices??b.allowed_devices??b.maxUsers??b.max_users;
        const maxDevices=parseDeviceLimit(requestedDeviceLimit, Number(store.allowed_devices)||1);
        db.prepare('UPDATE stores SET name=?,username=?,status=?,suspend_until=?,verified=?,debtor_limit=?,permissions=?,whatsapp_enabled=?,allowed_devices=?,revision=revision+1 WHERE id=?').run(newName,newUsername,nextStatus,until,b.verified===undefined?store.verified:(b.verified?1:0),Math.max(0,Math.min(1_000_000,b.debtorLimit===undefined?store.debtor_limit:Number(b.debtorLimit))),JSON.stringify(perms),b.whatsappEnabled===undefined?store.whatsapp_enabled:(b.whatsappEnabled?1:0),maxDevices,id);
        enforceDeviceCap(id,maxDevices);
        if(nextStatus!=='active') db.prepare("DELETE FROM sessions WHERE kind='store' AND principal_id=?").run(id);
        return ok(res,{store:{...db.prepare('SELECT id,name,username,status,suspend_until,verified,debtor_limit,permissions,whatsapp_enabled FROM stores WHERE id=?').get(id),maxDevices,allowedDevices:maxDevices}});
      }
      if(req.method==='POST' && action==='reset-password'){
        const b=await bodyJson(req); const password=String(b.password||''); if(password.length<12||password.length>ADMIN_PASSWORD_MAX) throw new HttpError(400,'كلمة المرور المؤقتة يجب أن تكون بين 12 و128 محرفًا.');
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
    if(req.method==='GET' && pathname==='/api/mobile/session') return ok(res,{account:accountForStore(store),forcePasswordChange:!!store.force_password_change,revision:store.revision});
    if(req.method==='POST' && pathname==='/api/mobile/change-password'){
      const b=await bodyJson(req); const currentPassword=String(b.currentPassword||''); const password=String(b.password||''); if(password.length<12||password.length>ADMIN_PASSWORD_MAX) throw new HttpError(400,'كلمة المرور الجديدة يجب أن تكون بين 12 و128 محرفًا.');
      if(currentPassword.length>ADMIN_PASSWORD_MAX) throw new HttpError(403,'كلمة المرور الحالية غير صحيحة.');
      const current=await passwordHash(currentPassword,store.salt); if(!safeEqual(current.hash,store.password_hash)) throw new HttpError(403,'كلمة المرور الحالية غير صحيحة.');
      const ph=await passwordHash(password); db.prepare('UPDATE stores SET salt=?,password_hash=?,force_password_change=0 WHERE id=?').run(ph.salt,ph.hash,store.id);
      db.prepare("DELETE FROM sessions WHERE kind='store' AND principal_id=? AND token_hash<>?").run(store.id,hashToken(auth.token));
      return ok(res,{forcePasswordChange:false});
    }
    if(req.method==='GET' && pathname==='/api/mobile/activity'){
      const events=db.prepare('SELECT actor,action,description,created_at FROM audit_events WHERE store_id=? ORDER BY created_at DESC,id DESC LIMIT 300').all(store.id)
        .map(item=>({actor:item.actor,action:item.action,description:item.description,createdAt:item.created_at}));
      return ok(res,{events});
    }
    if(req.method==='GET' && pathname==='/api/mobile/snapshot') return ok(res,{snapshot:makeSnapshot(store)});
    if(req.method==='POST' && pathname==='/api/mobile/sync'){
      requirePermission(store,'contacts'); requirePermission(store,'debts'); const b=await bodyJson(req); const changed=syncSnapshot(store,b.snapshot||{},b.baseRevision,auth.device); return ok(res,{revision:changed.revision,snapshot:makeSnapshot(changed)});
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
      const metaVersion = serverEnv.META_GRAPH_VERSION || '';
      if(!/^v\d+\.\d+$/.test(metaVersion)) throw new HttpError(503,'لم يحدد الأدمن إصدار Meta Graph API المدعوم في إعدادات الخادم.');
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
      const response=await fetch(`https://graph.facebook.com/${metaVersion}/${store.whatsapp_number_id}/messages`,{method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:JSON.stringify(message)});
      const result=await response.json().catch(()=>({})); if(!response.ok) throw new HttpError(502,text(result.error?.message||'فشل إرسال كشف واتساب إلى Meta.',300));
      return ok(res,{sent:true,messageId:result.messages?.[0]?.id||''});
    }
    throw new HttpError(404,'مسار التطبيق غير موجود.');
  }
  if (req.method==='GET') {
    const adminWeb=path.join(__dirname,'public');
    const safe=pathname==='/'?'/admin.html':pathname; const file=path.resolve(adminWeb,'.'+safe);
    if(!file.startsWith(path.resolve(adminWeb)+path.sep)) throw new HttpError(404,'الصفحة غير موجودة.');
    if(!fs.existsSync(file)||!fs.statSync(file).isFile()) throw new HttpError(404,'الصفحة غير موجودة.');
    const ext=path.extname(file); const type=({'.html':'text/html; charset=utf-8','.css':'text/css; charset=utf-8','.js':'text/javascript; charset=utf-8','.svg':'image/svg+xml'})[ext]||'application/octet-stream';
    return send(res,200,fs.readFileSync(file),type);
  }
  throw new HttpError(404,'المسار غير موجود.');
}

function createHttpServer() {
  return http.createServer(async(req,res)=>{
    try { await route(req,res); }
    catch(e) { const status=e instanceof HttpError?e.status:500; if(status===500) console.error(e); send(res,status,{ok:false,message:status===500?'حدث خطأ داخلي.':(e.message||'تعذر إكمال الطلب.'),...(e.extra||{})}); }
  });
}

function sweepStoreStatuses() {
  if (!db) return;
  for (const store of db.prepare("SELECT * FROM stores WHERE status!='deleted'").all()) refreshStoreStatus(store);
}

function startNodeServer() {
  loadEnv(path.join(__dirname, '.env'));
  configureDatabase(openDatabase(), process.env);
  seedInitialAdmin(db);
  sweepStoreStatuses();
  const subscriptionTimer = setInterval(sweepStoreStatuses, 60_000);
  subscriptionTimer.unref();
  const port = Number(process.env.PORT || 8081);
  const host = process.env.HOST || '0.0.0.0';
  const server = createHttpServer();
  server.listen(port,host,()=>console.log(`Sadad server listening on http://${host}:${port}`));
  process.on('SIGTERM',()=>{clearInterval(subscriptionTimer);server.close(()=>{db.close();process.exit(0);});});
  return server;
}

if (require.main === module) startNodeServer();

module.exports = { configureDatabase, initializeDatabase, openDatabase, seedInitialAdmin, sweepStoreStatuses, createHttpServer, route, HttpError };
