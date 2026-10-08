const { DatabaseSync } = require('node:sqlite');
const crypto = require('node:crypto');
const api = require('../server/server.js');
const db = api.initializeDatabase(new DatabaseSync(':memory:'));
api.configureDatabase(db, {INITIAL_ADMIN_KEY: crypto.randomBytes(32).toString('hex'), TOKEN_ENCRYPTION_KEY: crypto.randomBytes(32).toString('hex')});
const env = {INITIAL_ADMIN_KEY: crypto.randomBytes(32).toString('hex'), TOKEN_ENCRYPTION_KEY: crypto.randomBytes(32).toString('hex')};
api.configureDatabase(db, env);
const server = api.createHttpServer();
server.listen(0, '127.0.0.1', async () => {
  const base = `http://127.0.0.1:${server.address().port}`;
  async function request(route, body, token) {
    const response = await fetch(base + route, {method: body ? 'POST' : 'GET', headers: {'Content-Type': 'application/json', ...(token ? {Authorization: `Bearer ${token}`} : {})}, ...(body ? {body: JSON.stringify(body)} : {})});
    const data = await response.json(); if (!response.ok || !data.ok) throw Error(`${route}: ${data.message}`); return data;
  }
  try {
    const status = await request('/api/setup/status'); if (!status.bootstrapRequired) throw Error('Expected fresh database');
    const adminPassword = crypto.randomBytes(24).toString('hex');
    const admin = await request('/api/setup/admin', {setupKey: env.INITIAL_ADMIN_KEY, username:'qa-admin', password:adminPassword});
    const storePassword = crypto.randomBytes(24).toString('hex');
    await request('/api/admin/stores', {name:'متجر اختبار الربط', username:'qa-store', password:storePassword, subscriptionMode:'permanent', maxDevices:1}, admin.token);
    const mobile = await request('/api/mobile/login', {username:'qa-store', password:storePassword, deviceId:'qa-emulator-20261006', deviceName:'QA Emulator', staffName:'رامي'});
    if (!mobile.forcePasswordChange) throw Error('Expected first-login password change');
    await request('/api/mobile/change-password', {currentPassword:storePassword, password:crypto.randomBytes(24).toString('hex')}, mobile.token);
    const before = await request('/api/mobile/snapshot', null, mobile.token);
    const now = Date.now();
    const snapshot = {contacts:[{id:1,name:'محمد اختبار',phone:'0591111111',category:'عميل',createdAt:now}], debts:[{id:1,contactId:1,direction:'receivable',amount:150,note:'اختبار المزامنة',createdAt:now}], payments:[{id:1,debtId:1,amount:10,method:'wallet_jawwal',createdAt:now},{id:2,debtId:1,amount:20,method:'wallet_palpay',createdAt:now+1}]};
    const synced = await request('/api/mobile/sync', {baseRevision:before.snapshot.revision,snapshot}, mobile.token);
    const after = await request('/api/mobile/snapshot', null, mobile.token);
    if (after.snapshot.contacts.length !== 1 || after.snapshot.totals.receivable !== 120) throw Error('Incorrect synced ledger');
    if (after.snapshot.payments.map(p=>p.method).sort().join(',') !== 'wallet_jawwal,wallet_palpay') throw Error('Wallet channels changed');
    if(after.snapshot.payments.some(p=>p.createdBy!=='رامي')) throw Error('First login staff identity missing');

    const assert=require('node:assert/strict');const storeId=mobile.account.id;
    db.prepare("INSERT INTO contacts(store_id,id,name,created_at) VALUES(?,999,'QA large deletion',1)").run(storeId);
    db.exec('BEGIN IMMEDIATE');for(let n=1000;n<1600;n++){db.prepare("INSERT INTO debts(store_id,id,contact_id,direction,amount_cents,created_at) VALUES(?,?,999,'receivable',100,1)").run(storeId,n);db.prepare("INSERT INTO payments(store_id,id,debt_id,amount_cents,created_at) VALUES(?,?,?,100,1)").run(storeId,n,n);}db.exec('COMMIT');
    const batch={baseRevision:after.snapshot.revision,batchId:'qa256-delete',delta:{contacts:[],debts:[],payments:[],deleted:{contacts:[999],debts:[],payments:Array.from({length:400},(_,n)=>1000+n)}}};
    const removed=await request('/api/mobile/sync-delta',batch,mobile.token);
    assert.equal(db.prepare('SELECT COUNT(*) n FROM contacts WHERE id=999').get().n,0);
    assert.equal(db.prepare('SELECT COUNT(*) n FROM debts WHERE contact_id=999').get().n,0);
    assert.equal(db.prepare('SELECT COUNT(*) n FROM payments WHERE id>=1000').get().n,0);
    const archive=JSON.parse(db.prepare('SELECT snapshot_json FROM contact_archives WHERE contact_id=999').get().snapshot_json);
    assert.equal(archive.debts.length,600);assert.equal(archive.payments.length,600);
    const replay=await request('/api/mobile/sync-delta',batch,mobile.token);assert.equal(replay.replayed,true);
    db.prepare("INSERT INTO contacts(store_id,id,name,created_at) VALUES(?,998,'QA protected balance',1)").run(storeId);db.prepare("INSERT INTO debts(store_id,id,contact_id,direction,amount_cents,created_at) VALUES(?,998,998,'receivable',100,1)").run(storeId);
    const reject=await fetch(base+'/api/mobile/sync-delta',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+mobile.token},body:JSON.stringify({baseRevision:removed.revision,delta:{contacts:[],debts:[],payments:[],deleted:{contacts:[998],debts:[],payments:[]}}})});assert.equal(reject.status,409);assert.equal(db.prepare('SELECT COUNT(*) n FROM debts WHERE id=998').get().n,1);
    db.exec('DELETE FROM debts WHERE id=998');db.exec('DELETE FROM contacts WHERE id=998');
    console.log('PASS: atomic cascade of 600 settled debts and payments across a 400-event batch, complete archive, receipt replay, outstanding balance protected');

    const originalSchema = db.prepare("SELECT sql FROM sqlite_master WHERE name='payments'").get().sql;
    db.exec(originalSchema.replace('CREATE TABLE payments','CREATE TABLE payments_legacy').replace(", 'wallet_jawwal'",'').replace("'cash','bank','wallet','wallet_jawwal','wallet_palpay'","'cash','bank','wallet'"));
    db.exec("INSERT INTO payments_legacy SELECT store_id,id,debt_id,amount_cents,'wallet',note,created_by,created_at,server_received_at FROM payments");
    db.exec('DROP TABLE payments'); db.exec('ALTER TABLE payments_legacy RENAME TO payments');
    api.initializeDatabase(db);
    const retained = db.prepare('SELECT COUNT(*) n, SUM(amount_cents) total FROM payments').get();
    if(retained.n!==2 || retained.total!==3000) throw Error('Migration lost old payments');
    db.prepare("UPDATE payments SET method='wallet_jawwal' WHERE id=1").run();
    db.prepare("UPDATE payments SET method='wallet_palpay' WHERE id=2").run();
    const stores = await request('/api/admin/stores', null, admin.token);
    const page = await fetch(base+'/'); if (!page.ok || !(await page.text()).includes('سدد')) throw Error('Admin page failed');
    console.log('PASS: admin setup, store creation, mobile login, password change, ledger sync, two wallet channels and balance 120, admin site');
  } catch (error) { console.error(error.message); process.exitCode = 1; }
  finally { server.close(() => db.close()); }
});
