const fs=require('fs'),crypto=require('crypto'),{DatabaseSync}=require('node:sqlite');
const api=require('../server/server.js');const db=api.initializeDatabase(new DatabaseSync(':memory:'));
const env={INITIAL_ADMIN_KEY:crypto.randomBytes(32).toString('hex'),TOKEN_ENCRYPTION_KEY:crypto.randomBytes(32).toString('hex')};api.configureDatabase(db,env);
const server=api.createHttpServer();const handler=server.listeners('request')[0];server.removeAllListeners('request');let fail=true;
server.on('request',(req,res)=>{if(req.url==='/api/mobile/sync'&&fail){fail=false;res.writeHead(503,{'Content-Type':'application/json'});res.end(JSON.stringify({ok:false,message:'QA retry'}));return;}handler(req,res);});
server.listen(18082,'0.0.0.0',async()=>{try{
async function request(path,body,token){const r=await fetch('http://127.0.0.1:18082/api'+path,{method:'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:JSON.stringify(body)});const data=await r.json();if(!r.ok)throw Error(data.message);return data;}
const admin=await request('/setup/admin',{setupKey:env.INITIAL_ADMIN_KEY,username:'qa-admin',password:crypto.randomBytes(24).toString('hex')});const password=crypto.randomBytes(24).toString('hex');
await request('/admin/stores',{name:'اختبار المزامنة',username:'qa-sync',password,subscriptionMode:'permanent',maxDevices:1},admin.token);
const login=await request('/mobile/login',{username:'qa-sync',password,deviceId:'qa-sync-device-20261007-254',deviceName:'QA',staffName:'رامي'});await request('/mobile/change-password',{currentPassword:password,password:crypto.randomBytes(24).toString('hex')},login.token);login.snapshot=(await fetch('http://127.0.0.1:18082/api/mobile/snapshot',{headers:{Authorization:'Bearer '+login.token}}).then(r=>r.json())).snapshot;login.forcePasswordChange=false;fs.writeFileSync(require('path').join(__dirname,'qa-sync-session.json'),JSON.stringify(login));console.log('QA fixture ready on 18082 (in-memory database).');
}catch(e){console.error(e.message);server.close();process.exitCode=1;}});



