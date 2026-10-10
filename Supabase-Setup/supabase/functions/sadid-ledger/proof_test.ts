import { proofText,sha256,verifyProof } from './proof.ts';
const assert=(condition:boolean)=>{if(!condition)throw new Error('فشل التحقق');};
const encoder=new TextEncoder();
async function fixture(){
 const pair=await crypto.subtle.generateKey({name:'ECDSA',namedCurve:'P-256'},true,['sign','verify']);
 const publicKey=await crypto.subtle.exportKey('jwk',pair.publicKey);
 const route='/mobile/v3/operations',raw='{"payload":{"amountCents":10000}}';
 const id=crypto.randomUUID(),nonce=crypto.randomUUID(),time=String(Date.now());
 const text=proofText('POST',route,await sha256(raw),time,nonce,id);
 const sig=new Uint8Array(await crypto.subtle.sign({name:'ECDSA',hash:'SHA-256'},pair.privateKey,encoder.encode(text)));
 const signature=btoa(String.fromCharCode(...sig));
 const headers={'x-sadad-installation':id,'x-sadad-nonce':nonce,'x-sadad-time':time,'x-sadad-signature':signature};
 return {route,raw,publicKey,headers};
}
Deno.test('قبول توقيع الجهاز الصحيح',async()=>{const f=await fixture();assert(!!await verifyProof(new Request('https://test.invalid',{method:'POST',headers:f.headers}),f.route,f.raw,f.publicKey));});
Deno.test('رفض تعديل المحتوى أو المسار',async()=>{const f=await fixture();const r=new Request('https://test.invalid',{method:'POST',headers:f.headers});assert(!await verifyProof(r,f.route,f.raw+' ',f.publicKey));assert(!await verifyProof(r,'/other',f.raw,f.publicKey));});
Deno.test('رفض توقيع منتهي أو مفتاح آخر',async()=>{const f=await fixture(),other=await fixture();const r=new Request('https://test.invalid',{method:'POST',headers:f.headers});assert(!await verifyProof(r,f.route,f.raw,f.publicKey,Date.now()+600000));assert(!await verifyProof(r,f.route,f.raw,other.publicKey));});
