package com.sadad.app;

import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class LedgerTransport {
 static final String BASE="https://vhftjmiltqfwmrszdtgf.supabase.co/functions/v1/sadid-ledger/api";
 final SessionStore sessions;final InstallationKey installation;
 static final class Failure extends IOException{final String code;final int status;Failure(int status,String code,String message){super(message);this.status=status;this.code=code;}}
 LedgerTransport(SessionStore sessions,InstallationKey installation){this.sessions=sessions;this.installation=installation;}
 JSONObject legacy(String route,JSONObject body,boolean authenticated)throws Exception{return request(sessions.apiBaseUrl()+"/"+route,"POST",body,authenticated,false,"");}
 synchronized void refresh()throws Exception{JSONObject auth=sessions.auth();if(auth.optLong("expiresAt")>System.currentTimeMillis()+60000)return;JSONObject r=legacy("mobile/auth/refresh",new JSONObject().put("refreshToken",auth.getString("refreshToken")),false);sessions.updateAuth(r.getJSONObject("auth"));}
 JSONObject signed(String route,String method,JSONObject body,String recovery)throws Exception{refresh();return request(BASE+route,method,body,true,true,recovery);}
 private JSONObject request(String address,String method,JSONObject body,boolean authenticated,boolean signed,String recovery)throws Exception{
  String raw=body==null?"":body.toString();URL url=new URL(address);HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setRequestMethod(method);c.setConnectTimeout(10000);c.setReadTimeout(20000);c.setUseCaches(false);c.setInstanceFollowRedirects(false);c.setRequestProperty("apikey",BuildConfig.SUPABASE_PUBLISHABLE_KEY);c.setRequestProperty("Content-Type","application/json; charset=utf-8");
  if(authenticated)c.setRequestProperty("Authorization","Bearer "+sessions.auth().optString("accessToken"));
  if(authenticated||address.endsWith("mobile/auth/refresh"))c.setRequestProperty("X-Sadad-Session",sessions.token());
  if(signed){String time=String.valueOf(System.currentTimeMillis()),nonce=UUID.randomUUID().toString(),route=address.substring(BASE.length());c.setRequestProperty("X-Sadad-Installation",installation.id());c.setRequestProperty("X-Sadad-Time",time);c.setRequestProperty("X-Sadad-Nonce",nonce);c.setRequestProperty("X-Sadad-Signature",installation.sign(method,route,raw,time,nonce));if(recovery!=null&&!recovery.isEmpty())c.setRequestProperty("X-Sadad-Recovery",recovery);}
  try{if(body!=null){c.setDoOutput(true);try(OutputStream out=c.getOutputStream()){out.write(raw.getBytes(StandardCharsets.UTF_8));}}int status=c.getResponseCode();InputStream stream=status>=200&&status<300?c.getInputStream():c.getErrorStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream();if(stream!=null)try(InputStream in=stream){byte[] b=new byte[8192];int count;while((count=in.read(b))!=-1){if(bytes.size()+count>8*1024*1024)throw new IOException("الرد أكبر من الحد المسموح.");bytes.write(b,0,count);}}JSONObject result=new JSONObject(bytes.toString("UTF-8"));if(status<200||status>=300)throw new Failure(status,result.optString("code",status==401?"auth_required":"temporary_service_failure"),result.optString("message","تعذر الاتصال."));return result;}finally{c.disconnect();}
 }
}
