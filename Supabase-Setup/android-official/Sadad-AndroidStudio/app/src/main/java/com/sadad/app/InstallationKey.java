package com.sadad.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.util.Arrays;
import java.util.UUID;

/** مفتاح تركيب غير قابل للتصدير؛ لا يُدرج في ملفات الاسترداد. */
final class InstallationKey {
 private static final String ALIAS="sadid.installation.ec.v1";
 private final Context context;
 InstallationKey(Context context){this.context=context.getApplicationContext();}
 String id(){android.content.SharedPreferences p=context.getSharedPreferences("sadid.installation",0);String id=p.getString("id","");if(id.isEmpty()){id=UUID.randomUUID().toString();if(!p.edit().putString("id",id).commit())throw new IllegalStateException("تعذر حفظ هوية التركيب.");}return id;}
 private KeyStore store()throws Exception{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);if(!ks.containsAlias(ALIAS)){KeyPairGenerator g=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore");g.initialize(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY).setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build());g.generateKeyPair();}return ks;}
 JSONObject publicKey()throws Exception{ECPublicKey key=(ECPublicKey)store().getCertificate(ALIAS).getPublicKey();ECPoint p=key.getW();return new JSONObject().put("kty","EC").put("crv","P-256").put("x",b64(fixed(p.getAffineX().toByteArray()))).put("y",b64(fixed(p.getAffineY().toByteArray())));}
 private static byte[] fixed(byte[] value){byte[] out=new byte[32];int size=Math.min(value.length,32);System.arraycopy(value,value.length-size,out,32-size,size);return out;}
 static String b64(byte[] value){return Base64.encodeToString(value,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
 static byte[] unb64(String value){return Base64.decode(value,Base64.URL_SAFE|Base64.NO_WRAP);}
 static String hash(String value)throws Exception{byte[] d=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:d)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
 String sign(String method,String route,String raw,String time,String nonce)throws Exception{String text=String.join("\n","sadid-v3",method,route,hash(raw),time,nonce,id());Signature signer=Signature.getInstance("SHA256withECDSA");signer.initSign((PrivateKey)store().getKey(ALIAS,null));signer.update(text.getBytes(StandardCharsets.UTF_8));return b64(derToRaw(signer.sign()));}
 private static byte[] derToRaw(byte[] der)throws Exception{int p=2;if((der[1]&255)>127)p=2+(der[1]&127);if(der[p++]!=2)throw new GeneralSecurityException();int rn=der[p++]&255;byte[] r=Arrays.copyOfRange(der,p,p+rn);p+=rn;if(der[p++]!=2)throw new GeneralSecurityException();int sn=der[p++]&255;byte[] s=Arrays.copyOfRange(der,p,p+sn);byte[] raw=new byte[64];System.arraycopy(fixed(r),0,raw,0,32);System.arraycopy(fixed(s),0,raw,32,32);return raw;}
 static byte[] rawToDer(byte[] raw){if(raw.length!=64)throw new IllegalArgumentException();byte[] r=unsigned(Arrays.copyOfRange(raw,0,32)),s=unsigned(Arrays.copyOfRange(raw,32,64));byte[] out=new byte[6+r.length+s.length];int p=0;out[p++]=48;out[p++]=(byte)(out.length-2);out[p++]=2;out[p++]=(byte)r.length;System.arraycopy(r,0,out,p,r.length);p+=r.length;out[p++]=2;out[p++]=(byte)s.length;System.arraycopy(s,0,out,p,s.length);return out;}
 private static byte[] unsigned(byte[] bytes){int p=0;while(p<bytes.length-1&&bytes[p]==0)p++;byte[] trimmed=Arrays.copyOfRange(bytes,p,bytes.length);if((trimmed[0]&128)!=0){byte[] out=new byte[trimmed.length+1];System.arraycopy(trimmed,0,out,1,trimmed.length);return out;}return trimmed;}
}
