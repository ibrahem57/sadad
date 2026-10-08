const { DatabaseSync } = require('node:sqlite');
const crypto = require('node:crypto');
const api = require('../server/server.js');
const db = api.initializeDatabase(new DatabaseSync(require('node:path').join(require('node:os').tmpdir(),'sadad-qa255-'+Date.now()+'.sqlite')));
api.configureDatabase(db, {INITIAL_ADMIN_KEY: crypto.randomBytes(32).toString('hex'), TOKEN_ENCRYPTION_KEY: crypto.randomBytes(32).toString('hex')});
const env = {INITIAL_ADMIN_KEY: crypto.randomBytes(32).toString('hex'), TOKEN_ENCRYPTION_KEY: crypto.randomBytes(32).toString('hex')};
api.configureDatabase(db, env);
const server = api.createHttpServer();
server.listen(0, '127.0.0.1', async () => {
  const base = `http://127.0.0.1:${server.address().port}`;
  async function request(route, body, token) {
    const response = await fetch(base + route, {method: body ? 'POST' : 'GET', headers: {'Content-Type': 'application/json', Connection:'close', ...(token ? {Authorization: `Bearer ${token}`} : {})}, ...(body ? {body: JSON.stringify(body)} : {})});
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

    const assert=require('node:assert/strict');
    const empty={contacts:[],debts:[],payments:[],deleted:{contacts:[],debts:[],payments:[]}};
    let revision=after.snapshot.revision;
    const patch={...empty,payments:[{id:3,debtId:1,amount:5,method:'cash',createdAt:now+2}]};
    const sent={baseRevision:revision,batchId:'qa255:first',delta:patch};
    const first=await request('/api/mobile/sync-delta',sent,mobile.token);revision=first.revision;
    const replay=await request('/api/mobile/sync-delta',sent,mobile.token);assert.equal(replay.revision,revision);assert.equal(replay.replayed,true);
    assert.equal(db.prepare('SELECT COUNT(*) n FROM payments').get().n,3);
    assert.equal(db.prepare('SELECT COUNT(*) n FROM store_backups').get().n,0,'append makes no full backup');
    const stale=await fetch(base+'/api/mobile/sync-delta',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+mobile.token},body:JSON.stringify({...sent,batchId:'qa255:stale'})});assert.equal(stale.status,409);
    // Synthetic volume measures indexed lookup on one node; it is not a concurrent production capacity certification.
    const seedStart=performance.now();db.exec('BEGIN IMMEDIATE');
    db.exec(`WITH RECURSIVE n(x) AS (VALUES(2) UNION ALL SELECT x+1 FROM n WHERE x<1000)
      INSERT INTO stores(id,name,username,salt,password_hash,force_password_change,debtor_limit,created_at)
      SELECT x,'QA volume','load-'||x,'unused','unused',0,5000,1 FROM n`);
    db.exec(`WITH RECURSIVE n(x) AS (VALUES(2) UNION ALL SELECT x+1 FROM n WHERE x<=1500)
      INSERT INTO contacts(store_id,id,name,created_at) SELECT s.id,n.x,'Person '||n.x,1 FROM stores s CROSS JOIN n`);
    db.exec(`WITH RECURSIVE n(x) AS (VALUES(2) UNION ALL SELECT x+1 FROM n WHERE x<=600)
      INSERT INTO debts(store_id,id,contact_id,direction,amount_cents,created_at)
      SELECT s.id,n.x,n.x,'receivable',10000,1 FROM stores s CROSS JOIN n`);
    db.exec('COMMIT');
    console.log("Volume seeded");const seedMs=Math.round(performance.now()-seedStart);let pages=0,rows=0,afterId=0;const pageStart=performance.now();
    do{const page=await request('/api/mobile/snapshot-page?table=contacts&revision='+revision+'&after='+afterId,null,mobile.token);pages++;rows+=page.rows.length;afterId=page.nextAfter;if(page.done)break;}while(true);
    assert.equal(rows,1501);assert.equal(pages,4);
    const pageDownloadMs=Math.round(performance.now()-pageStart);const patchStart=performance.now();const next=await request('/api/mobile/sync-delta',{baseRevision:revision,batchId:'qa255:large',delta:{...empty,payments:[{id:4,debtId:1,amount:5,method:'bank',createdAt:now+3}]}},mobile.token);revision=next.revision;
    const patchMs=Math.round(performance.now()-patchStart);
    assert.equal(db.prepare('SELECT COUNT(*) n FROM stores').get().n,1000);
    assert.equal(db.prepare('SELECT COUNT(*) n FROM contacts').get().n,1500001);
    assert.equal(db.prepare('SELECT COUNT(*) n FROM debts').get().n,600001);
    assert.equal(db.prepare('SELECT COUNT(*) n FROM payments WHERE store_id=1 AND id=4').get().n,1);
    const conflict=await fetch(base+'/api/mobile/snapshot-page?table=debts&revision='+(revision-1),{headers:{Authorization:'Bearer '+mobile.token}});assert.equal(conflict.status,409);

    const tokens=[];db.exec('BEGIN IMMEDIATE');
    const addSession=db.prepare("INSERT INTO sessions VALUES(?,'store',?,'qa-load',?,?)");
    const addDevice=db.prepare("INSERT INTO store_devices(store_id,device_id,first_seen_at,last_seen_at,staff_name) VALUES(?,'qa-load',?,?,'QA operator')");
    for(let id=2;id<=601;id++){const token=crypto.randomBytes(32).toString('base64url');tokens.push(token);addSession.run(crypto.createHash('sha256').update(token).digest('hex'),id,Date.now()+3600000,Date.now());addDevice.run(id,Date.now(),Date.now());}
    db.exec('COMMIT');let cursor=0;const latencies=[];const trafficStart=performance.now();
    await Promise.all(Array.from({length:10},async()=>{while(cursor<tokens.length){const index=cursor++,start=performance.now();await request('/api/mobile/sync-delta',{baseRevision:0,batchId:'load:1',delta:{...empty,payments:[{id:100,debtId:2,amount:1,method:'cash',createdAt:Date.now()}]}},tokens[index]);latencies.push(performance.now()-start);}}));
    latencies.sort((a,b)=>a-b);const trafficMs=Math.round(performance.now()-trafficStart);
    assert.equal(db.prepare('SELECT COUNT(*) n FROM payments WHERE store_id BETWEEN 2 AND 601').get().n,600);
    console.log('PASS: 600 authenticated updates across 600 stores, concurrency 10, '+trafficMs+'ms');

    // Restore is verified from a consistent SQLite online backup, including all stores.
    const backupPath=require('node:path').join(require('node:os').tmpdir(),'sadad-qa255-backup-'+Date.now()+'.sqlite');await require('node:sqlite').backup(db,backupPath,{rate:100});
    const restored=new DatabaseSync(backupPath);assert.equal(restored.prepare('SELECT COUNT(*) n FROM contacts').get().n,1500001);restored.close();
    const metrics={syntheticStores:1000,contacts:1500001,debts:600001,seedMs,pagedContacts:rows,pageRequests:pages,pageDownloadMs,deltaMs:patchMs,concurrentRequests:600,concurrency:10,trafficMs,requestsPerSecond:Math.round(600000/trafficMs),p95Ms:Math.round(latencies[Math.floor(latencies.length*.95)]),rssMiB:Math.round(process.memoryUsage().rss/1048576),tests:'delta, idempotency, stale revision, paged full download, cross-store isolation, complete backup restore',limitation:'Synthetic data on local node; no certification of concurrent production traffic.'};
    require('fs').writeFileSync(require('node:path').join(__dirname,'qa-scale-result.json'),JSON.stringify(metrics,null,2));console.log('PASS scale '+JSON.stringify(metrics));
    // Old migration fixture checks only the original first two payments.
    db.prepare('DELETE FROM payments WHERE id>2').run();
    db.exec('DELETE FROM stores WHERE id>1');db.exec('DELETE FROM debts WHERE id>1');db.exec('DELETE FROM contacts WHERE id>1');

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
  } catch (error) { console.error(error.stack,error.cause); process.exitCode = 1; }
  finally { server.close(() => db.close()); }
});
