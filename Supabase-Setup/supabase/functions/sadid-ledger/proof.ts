const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const encoder=new TextEncoder();
export async function sha256(raw:string){return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',encoder.encode(raw)))).map(b=>b.toString(16).padStart(2,'0')).join('');}
export function proofText(method:string,route:string,digest:string,time:string,nonce:string,installation:string){return ['sadid-v3',method.toUpperCase(),route,digest,time,nonce,installation].join('\n');}
export async function verifyProof(request:Request,route:string,raw:string,jwk:JsonWebKey,now=Date.now()){
 const id=request.headers.get('x-sadad-installation')||'';
 const nonce=request.headers.get('x-sadad-nonce')||'';
 const time=request.headers.get('x-sadad-time')||'';
 const signature=request.headers.get('x-sadad-signature')||'';
 if(!uuid.test(id)||!uuid.test(nonce)||!/^\d{13}$/.test(time)||Math.abs(now-Number(time))>300000||signature.length>100)return null;
 if(jwk.kty!=='EC'||jwk.crv!=='P-256'||jwk.d)return null;
 try{
  const key=await crypto.subtle.importKey('jwk',jwk,{name:'ECDSA',namedCurve:'P-256'},false,['verify']);
  const bytes=Uint8Array.from(atob(signature.replace(/-/g,'+').replace(/_/g,'/')),c=>c.charCodeAt(0));
  if(bytes.length!==64)return null;
  const valid=await crypto.subtle.verify({name:'ECDSA',hash:'SHA-256'},key,bytes,encoder.encode(proofText(request.method,route,await sha256(raw),time,nonce,id)));
  return valid?{installationId:id,nonce}:null;
 }catch{return null;}
}
