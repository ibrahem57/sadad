import { createClient } from 'supabase';
import { sha256,verifyProof } from './proof.ts';
import { createECDH,createHash } from 'node:crypto';
const url=Deno.env.get('SUPABASE_URL')!;
const serviceKey=Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!;
const publicKey=Deno.env.get('SUPABASE_ANON_KEY')!;
const db=createClient(url,serviceKey,{auth:{persistSession:false,autoRefreshToken:false}});
// مفتاح توقيع مشتق ومفصول بالغرض عن مفتاح الخدمة؛ لا يُرسل السر أو المفتاح الخاص.
const leaseCurve=createECDH('prime256v1');
leaseCurve.setPrivateKey(createHash('sha256').update('sadid-offline-lease-v1\0').update(serviceKey).digest());
const leasePoint=leaseCurve.getPublicKey(undefined,'uncompressed');
const b64=(bytes:Uint8Array)=>btoa(String.fromCharCode(...bytes)).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
const leaseJwk:JsonWebKey={kty:'EC',crv:'P-256',x:b64(leasePoint.subarray(1,33)),y:b64(leasePoint.subarray(33)),d:b64(leaseCurve.getPrivateKey())};
const leaseSigningKey=await crypto.subtle.importKey('jwk',leaseJwk,{name:'ECDSA',namedCurve:'P-256'},false,['sign']);
const headers={'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Access-Control-Allow-Origin':'*','Access-Control-Allow-Headers':'authorization,apikey,content-type,x-sadad-session,x-sadad-admin,x-sadad-installation,x-sadad-time,x-sadad-nonce,x-sadad-signature,x-sadad-recovery','Access-Control-Allow-Methods':'GET,POST,OPTIONS'};
const respond=(status:number,data:unknown)=>new Response(JSON.stringify(data),{status,headers});
const messages:Record<string,string>={authorization_required:'يلزم التحقق من الجلسة والجهاز.',installation_or_session_revoked:'الجلسة أو الجهاز غير معتمد.',installation_revoked:'تم إلغاء هذا الجهاز.',store_suspended:'المتجر موقوف؛ حُفظت الإدخالات المعلقة.',password_change_required:'غيّر كلمة المرور المؤقتة أولًا.',dependency_pending:'لم تصل العملية التي يعتمد عليها هذا الإدخال بعد.',operation_id_reused:'رقم العملية مستخدم بمحتوى مختلف.',overpayment:'الدفعة تتجاوز المتبقي.',admin_authorization_required:'يلزم حساب إدارة صالح.',ledger_not_initialized:'انتظر اعتماد الجهاز من الإدارة.'};
function requireData(r:any){if(r.error)throw r.error;return r.data;}
function sessionId(jwt:string){try{return JSON.parse(atob(jwt.split('.')[1].replace(/-/g,'+').replace(/_/g,'/'))).session_id||'';}catch{return '';}}
Deno.serve(async(request:Request)=>{
 if(request.method==='OPTIONS')return new Response(null,{status:204,headers});
 const route=new URL(request.url).pathname.replace(/^.*\/sadid-ledger/,'').replace(/^\/api/,'');
 try{
  if(request.method!=='GET'&&request.method!=='POST')return respond(405,{ok:false,message:'طريقة الطلب غير مدعومة.'});
  const raw=request.method==='GET'?'':await request.text();
  if(new TextEncoder().encode(raw).length>65536)return respond(413,{ok:false,message:'الطلب أكبر من الحد المسموح.'});
  let body:any={};try{body=raw?JSON.parse(raw):{};}catch{return respond(400,{ok:false,message:'صيغة البيانات غير صحيحة.'});}
  if(route.startsWith('/admin/')){
   const token=request.headers.get('x-sadad-admin')||'';if(!token)return respond(401,{ok:false,message:'سجّل دخول الإدارة.'});
   const hash=await sha256(token);
   if(route==='/admin/installations'&&request.method==='GET')return respond(200,{ok:true,installations:requireData(await db.rpc('sadid_pending_installations',{p_admin_hash:hash}))});
   if(route==='/admin/installations/approve'&&request.method==='POST')return respond(200,{ok:true,result:requireData(await db.rpc('sadid_approve_installation',{p_admin_hash:hash,p_installation:body.installationId,p_reason:body.reason}))});
   if(route==='/admin/recovery/approve'&&request.method==='POST')return respond(200,{ok:true,result:requireData(await db.rpc('sadid_approve_recovery',{p_admin_hash:hash,p_old:body.oldInstallationId,p_new:body.newInstallationId,p_manifest:body.commands,p_reason:body.reason}))});
   if(route==='/admin/ledger/read'&&request.method==='POST')return respond(200,{ok:true,snapshot:requireData(await db.rpc('sadid_support_snapshot',{p_admin_hash:hash,p_store:body.storeId,p_reason:body.reason}))});
   return respond(404,{ok:false,message:'المسار غير موجود.'});
  }
  const jwt=(request.headers.get('authorization')||'').match(/^Bearer\s+(.+)$/i)?.[1]||'';
  if(!jwt)return respond(401,{ok:false,message:'سجّل الدخول للمتابعة.'});
  const verified=await db.auth.getUser(jwt);
  if(verified.error||!verified.data.user)return respond(401,{ok:false,message:'انتهت الجلسة. سجّل الدخول من جديد.'});
  const user=verified.data.user.id,session=sessionId(jwt);
  if(!session)return respond(401,{ok:false,message:'الجلسة غير صالحة.'});
  const installation=request.headers.get('x-sadad-installation')||'';
  if(route==='/mobile/v3/enroll'&&request.method==='POST'){
   const proof=await verifyProof(request,route,raw,body.publicKey);
   const legacy=request.headers.get('x-sadad-session')||'';
   if(!proof||!legacy)return respond(403,{ok:false,message:'تعذر إثبات هوية الجهاز.'});
   return respond(200,{ok:true,result:requireData(await db.rpc('sadid_enroll',{p_user:user,p_session:session,p_legacy_hash:await sha256(legacy),p_installation:installation,p_key:body.publicKey}))});
  }
  const keyInfo=requireData(await db.rpc('sadid_installation_key',{p_user:user,p_session:session,p_installation:installation}));
  if(!keyInfo)return respond(403,{ok:false,code:'installation_revoked',message:'الجهاز غير معتمد. اطلب موافقة الإدارة.'});
  const proof=await verifyProof(request,route,raw,keyInfo.publicKey);
  if(!proof)return respond(403,{ok:false,code:'invalid_proof',message:'تعذر التحقق من توقيع الجهاز.'});
  let scope:string,rpc:string='',args:Record<string,unknown>={};
  if(route==='/mobile/v3/operations'&&request.method==='POST'){scope='write';rpc='sadid_apply';args={p_command:body};}
  else if(route==='/mobile/v3/snapshot'&&request.method==='GET'){scope='read';rpc='sadid_snapshot';}
  else if(route==='/mobile/v3/outcomes'&&request.method==='POST'){scope='outcomes';rpc='sadid_outcomes';args={p_ids:body.operationIds};}
  else if(route==='/mobile/v3/lease'&&request.method==='POST'){scope='lease';}
  else return respond(404,{ok:false,message:'المسار غير موجود.'});
  const recovery=request.headers.get('x-sadad-recovery');
  const ticket=requireData(recovery&&scope==='write'?await db.rpc('sadid_issue_recovery_capability',{p_user:user,p_session:session,p_installation:installation,p_nonce:proof.nonce,p_request:body,p_permit:recovery}):await db.rpc('sadid_issue_capability',{p_user:user,p_session:session,p_installation:installation,p_nonce:proof.nonce,p_scope:scope,p_request:scope==='write'?body:null}));
  if(scope==='lease'){
   const issuedAt=Date.now();
   const payload=JSON.stringify({schemaVersion:1,projectRef:'vhftjmiltqfwmrszdtgf',storeId:keyInfo.storeId,userId:user,installationId:installation,generation:keyInfo.generation,issuedAt,expiresAt:issuedAt+7*86400000,allowed:['contact.create','debt.create','payment.create']});
   const signature=new Uint8Array(await crypto.subtle.sign({name:'ECDSA',hash:'SHA-256'},leaseSigningKey,new TextEncoder().encode(payload)));
   const {d:unused,...publicLeaseKey}=leaseJwk;
   return respond(200,{ok:true,lease:{payload:b64(new TextEncoder().encode(payload)),signature:b64(signature),publicKey:publicLeaseKey}});
  }
  // الطلب المالي يحمل JWT المستخدم الحقيقي؛ مفتاح الخدمة لا يصل إلى هذا العميل.
  const userDb=createClient(url,publicKey,{global:{headers:{Authorization:`Bearer ${jwt}`,'X-Sadad-Capability':ticket.token}},auth:{persistSession:false,autoRefreshToken:false}});
  const result=requireData(await userDb.rpc(rpc,args));
  return respond(200,{ok:true,[scope==='read'?'snapshot':'result']:result});
 }catch(error:any){
  const code=error?.code==='23505'?'replayed_request':Object.hasOwn(messages,error?.message)?error.message:error?.message==='invalid_public_key'?'invalid_public_key':'temporary_service_failure';
  const status=code==='temporary_service_failure'?503:code==='dependency_pending'?409:403;
  return respond(status,{ok:false,code,message:messages[code]||'تعذر إتمام الطلب. احتفظ بالإدخال وحاول مجددًا.'});
 }
});
