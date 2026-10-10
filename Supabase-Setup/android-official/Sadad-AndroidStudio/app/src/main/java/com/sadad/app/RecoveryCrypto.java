package com.sadad.app;

import org.json.JSONObject;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/** صيغة مستقلة: PBKDF2-HMAC-SHA256 ثم AES-256-GCM. لا رموز دخول أو مفاتيح جهاز. */
final class RecoveryCrypto {
 private static final int ITERATIONS=600000;
 private static byte[] derive(char[] password,byte[] salt,int iterations)throws Exception{PBEKeySpec spec=new PBEKeySpec(password,salt,iterations,256);try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}catch(java.security.NoSuchAlgorithmException unavailable){
 // توافق Android 24/25: PBKDF2-HMAC-SHA256 القياسي، كتلة واحدة لمفتاح ٢٥٦ بت.
 byte[] secret=new String(password).getBytes(StandardCharsets.UTF_8);try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));byte[] block=Arrays.copyOf(salt,salt.length+4);block[block.length-1]=1;byte[] u=mac.doFinal(block),derived=u.clone();for(int i=1;i<iterations;i++){u=mac.doFinal(u);for(int j=0;j<derived.length;j++)derived[j]^=u[j];}Arrays.fill(u,(byte)0);return derived;}finally{Arrays.fill(secret,(byte)0);}
 }finally{spec.clearPassword();}}
 static byte[] seal(JSONObject bundle,char[] password)throws Exception{if(password.length<12)throw new IllegalArgumentException("استخدم عبارة مرور من ١٢ حرفًا على الأقل واحتفظ بها خارج الهاتف.");byte[] salt=new byte[16],iv=new byte[12];SecureRandom random=new SecureRandom();random.nextBytes(salt);random.nextBytes(iv);byte[] key=derive(password,salt,ITERATIONS);try{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));c.updateAAD("sadid-recovery-v1".getBytes(StandardCharsets.UTF_8));byte[] encrypted=c.doFinal(bundle.toString().getBytes(StandardCharsets.UTF_8));return new JSONObject().put("format","sadid-recovery-v1").put("kdf","PBKDF2-HMAC-SHA256").put("iterations",ITERATIONS).put("salt",InstallationKey.b64(salt)).put("iv",InstallationKey.b64(iv)).put("data",InstallationKey.b64(encrypted)).toString().getBytes(StandardCharsets.UTF_8);}finally{Arrays.fill(key,(byte)0);Arrays.fill(password,'\0');}}
 static JSONObject open(byte[] file,char[] password)throws Exception{if(file.length>8*1024*1024)throw new IllegalArgumentException("ملف الاسترداد أكبر من الحد المسموح.");JSONObject envelope=new JSONObject(new String(file,StandardCharsets.UTF_8));if(!envelope.getString("format").equals("sadid-recovery-v1")||!envelope.getString("kdf").equals("PBKDF2-HMAC-SHA256")||envelope.getInt("iterations")!=ITERATIONS)throw new IllegalArgumentException("صيغة استرداد غير مدعومة.");byte[] salt=InstallationKey.unb64(envelope.getString("salt")),iv=InstallationKey.unb64(envelope.getString("iv"));if(salt.length!=16||iv.length!=12)throw new IllegalArgumentException("ملف غير صالح.");byte[] key=derive(password,salt,ITERATIONS);try{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));c.updateAAD("sadid-recovery-v1".getBytes(StandardCharsets.UTF_8));return new JSONObject(new String(c.doFinal(InstallationKey.unb64(envelope.getString("data"))),StandardCharsets.UTF_8));}finally{Arrays.fill(key,(byte)0);Arrays.fill(password,'\0');}}
}
