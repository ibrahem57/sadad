package com.sadad.app;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;

/** كل محتوى الأعمال والحالات والأخطاء مشفر بـ AES-GCM في معاملات SQLite/WAL.
 * لا تحتوي الأعمدة الواضحة إلا ترتيبًا محليًا وبصمة رقم عملية وقفل عامل. */
final class EncryptedJournal {
 private final SQLiteDatabase db;
 final String scope;
 private final String keyAlias;
 EncryptedJournal(Context context,String scope)throws Exception{
  this.scope=scope;String digest=InstallationKey.hash(scope);keyAlias="sadid.journal."+digest;
  db=context.openOrCreateDatabase("sadid-v3-"+digest+".db",0,null);db.enableWriteAheadLogging();
  db.execSQL("CREATE TABLE IF NOT EXISTS entries(order_id INTEGER PRIMARY KEY AUTOINCREMENT,identity_hash TEXT UNIQUE NOT NULL,data BLOB NOT NULL)");
  db.execSQL("CREATE TABLE IF NOT EXISTS metadata(id TEXT PRIMARY KEY,data BLOB NOT NULL)");
  db.execSQL("CREATE TABLE IF NOT EXISTS worker_lock(id INTEGER PRIMARY KEY,owner TEXT,until_ms INTEGER)");
 }
 private SecretKey key()throws Exception{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);if(ks.containsAlias(keyAlias))return (SecretKey)ks.getKey(keyAlias,null);KeyGenerator g=KeyGenerator.getInstance("AES","AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(keyAlias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());return g.generateKey();}
 private byte[] encrypt(String purpose,JSONObject object)throws Exception{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());c.updateAAD((scope+"|"+purpose).getBytes(StandardCharsets.UTF_8));byte[] body=c.doFinal(object.toString().getBytes(StandardCharsets.UTF_8)),iv=c.getIV(),out=new byte[iv.length+body.length];System.arraycopy(iv,0,out,0,iv.length);System.arraycopy(body,0,out,iv.length,body.length);return out;}
 private JSONObject decrypt(String purpose,byte[] bytes)throws Exception{if(bytes.length<29)throw new java.io.IOException("ملف محلي غير مكتمل؛ حُفظ دون حذف.");Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Arrays.copyOfRange(bytes,0,12)));c.updateAAD((scope+"|"+purpose).getBytes(StandardCharsets.UTF_8));return new JSONObject(new String(c.doFinal(Arrays.copyOfRange(bytes,12,bytes.length)),StandardCharsets.UTF_8));}
 synchronized JSONObject metadata(String name)throws Exception{try(Cursor c=db.rawQuery("SELECT data FROM metadata WHERE id=?",new String[]{name})){return c.moveToFirst()?decrypt(name,c.getBlob(0)):new JSONObject();}}
 synchronized void metadata(String name,JSONObject object)throws Exception{ContentValues v=new ContentValues();v.put("id",name);v.put("data",encrypt(name,object));if(db.insertWithOnConflict("metadata",null,v,SQLiteDatabase.CONFLICT_REPLACE)==-1)throw new java.io.IOException("تعذر حفظ البيانات.");}
 synchronized JSONArray entries()throws Exception{JSONArray array=new JSONArray();try(Cursor c=db.rawQuery("SELECT identity_hash,data FROM entries ORDER BY order_id",null)){while(c.moveToNext())array.put(decrypt("entry:"+c.getString(0),c.getBlob(1)));}return array;}
 private void write(JSONObject entry,boolean insert)throws Exception{String digest=InstallationKey.hash(entry.getJSONObject("command").getString("operationId"));ContentValues v=new ContentValues();v.put("identity_hash",digest);v.put("data",encrypt("entry:"+digest,entry));if(insert){if(db.insertOrThrow("entries",null,v)==-1)throw new java.io.IOException("تعذر حفظ الإدخال.");}else if(db.update("entries",v,"identity_hash=?",new String[]{digest})!=1)throw new java.io.IOException("الإدخال غير موجود.");}
 synchronized JSONObject add(JSONObject command)throws Exception{JSONObject entry=new JSONObject().put("command",new JSONObject(command.toString())).put("wire",command.toString()).put("state","queued").put("sentEver",false).put("attempts",0).put("history",new JSONArray().put(new JSONObject().put("state","queued").put("at",System.currentTimeMillis())));db.beginTransaction();try{write(entry,true);db.setTransactionSuccessful();}finally{db.endTransaction();}return entry;}
 private JSONObject transition(JSONObject entry,String state,String error)throws Exception{entry.put("state",state).put("error",error==null?"":error);entry.getJSONArray("history").put(new JSONObject().put("state",state).put("at",System.currentTimeMillis()).put("error",error==null?"":error));return entry;}
 synchronized void state(JSONObject entry,String state,String error)throws Exception{db.beginTransaction();try{transition(entry,state,error);write(entry,false);db.setTransactionSuccessful();}finally{db.endTransaction();}}
 synchronized JSONObject startSend(String operationId)throws Exception{db.beginTransaction();try{JSONArray all=entries();for(int i=0;i<all.length();i++){JSONObject e=all.getJSONObject(i);if(e.getJSONObject("command").getString("operationId").equals(operationId)){String state=e.getString("state");if(state.equals("cancelled")||state.equals("confirmed")||state.equals("rejected"))return null;e.put("sentEver",true).put("attempts",e.optInt("attempts")+1).put("lastAttemptAt",System.currentTimeMillis());transition(e,"sending","");write(e,false);db.setTransactionSuccessful();return e;}}return null;}finally{db.endTransaction();}}
 synchronized void cancel(String operationId)throws Exception{db.beginTransaction();try{JSONArray all=entries();JSONObject target=null;for(int i=0;i<all.length();i++){JSONObject e=all.getJSONObject(i);if(e.getJSONObject("command").getString("operationId").equals(operationId))target=e;JSONArray deps=e.getJSONObject("command").optJSONArray("dependsOn");if(!e.getString("state").equals("cancelled")&&deps!=null)for(int j=0;j<deps.length();j++)if(deps.getString(j).equals(operationId))throw new IllegalArgumentException("ألغِ الإدخالات التابعة أولًا.");}if(target==null||target.optBoolean("sentEver")||!target.getString("state").equals("queued"))throw new IllegalArgumentException("ربما أُرسل الإدخال؛ يجب التحقق من نتيجته أولًا.");target.put("cancelledAt",System.currentTimeMillis());transition(target,"cancelled","");write(target,false);db.setTransactionSuccessful();}finally{db.endTransaction();}}
 synchronized void merge(JSONObject snapshot)throws Exception{
  db.beginTransaction();try{JSONObject previous=metadata("cache");JSONArray all=entries();boolean epochChanged=previous.has("epoch")&&!previous.getString("epoch").equals(snapshot.getString("epoch"));
   if(epochChanged){metadata("sync",new JSONObject().put("recoveryRequired",true).put("error","تغير إصدار استرداد الخادم؛ يلزم فحص الإيصالات قبل الإرسال."));db.setTransactionSuccessful();return;}
   JSONArray outcomes=snapshot.getJSONArray("outcomes");for(int i=0;i<all.length();i++){JSONObject e=all.getJSONObject(i);if(e.getString("state").equals("cancelled"))continue;for(int j=0;j<outcomes.length();j++){JSONObject receipt=outcomes.getJSONObject(j);if(receipt.getString("operationId").equals(e.getJSONObject("command").getString("operationId"))){e.put("receipt",receipt);transition(e,receipt.getString("status").equals("accepted")?"confirmed":"rejected",receipt.optString("code",""));write(e,false);break;}}}
   metadata("cache",snapshot);metadata("sync",new JSONObject().put("at",System.currentTimeMillis()).put("error",""));db.setTransactionSuccessful();
  }finally{db.endTransaction();}
 }
 synchronized String acquire()throws Exception{String owner=UUID.randomUUID().toString();long now=System.currentTimeMillis();db.beginTransaction();try{try(Cursor c=db.rawQuery("SELECT until_ms FROM worker_lock WHERE id=1",null)){if(c.moveToFirst()&&c.getLong(0)>now)return null;}db.execSQL("INSERT OR REPLACE INTO worker_lock(id,owner,until_ms) VALUES(1,?,?)",new Object[]{owner,now+180000});db.setTransactionSuccessful();return owner;}finally{db.endTransaction();}}
 synchronized void renew(String owner){db.execSQL("UPDATE worker_lock SET until_ms=? WHERE id=1 AND owner=?",new Object[]{System.currentTimeMillis()+180000,owner});}
 synchronized void release(String owner){db.execSQL("DELETE FROM worker_lock WHERE id=1 AND owner=?",new Object[]{owner});}
 synchronized JSONObject exportPlain()throws Exception{db.beginTransaction();try{JSONObject out=new JSONObject().put("schemaVersion",1).put("scope",scope).put("cache",metadata("cache")).put("entries",entries()).put("createdAt",System.currentTimeMillis());db.setTransactionSuccessful();return out;}finally{db.endTransaction();}}
 synchronized void importPlain(JSONObject bundle)throws Exception{if(bundle.getInt("schemaVersion")!=1||!bundle.getString("scope").equals(scope))throw new IllegalArgumentException("ملف الاسترداد لمتجر أو مشروع مختلف.");db.beginTransaction();try{JSONArray incoming=bundle.getJSONArray("entries"),existing=entries();for(int i=0;i<incoming.length();i++){JSONObject entry=incoming.getJSONObject(i),match=null;String op=entry.getJSONObject("command").getString("operationId");for(int j=0;j<existing.length();j++){JSONObject e=existing.getJSONObject(j);if(e.getJSONObject("command").getString("operationId").equals(op)){match=e;break;}}if(match!=null){if(!match.getString("wire").equals(entry.getString("wire")))throw new IllegalArgumentException("رقم عملية بمحتوى مختلف.");continue;}if(!new JSONObject(entry.getString("wire")).toString().equals(entry.getJSONObject("command").toString()))throw new IllegalArgumentException("محتوى عملية غير متطابق.");if(!entry.getString("state").equals("cancelled")&&!entry.getString("state").equals("rejected")){transition(entry,"blocked","يلزم مصالحة الأرقام الأصلية وتصريح استرداد عند الحاجة.");}write(entry,true);}metadata("recovery",new JSONObject().put("importedAt",System.currentTimeMillis()).put("requiresApproval",true));db.setTransactionSuccessful();}finally{db.endTransaction();}}
 synchronized void close(){db.close();}
}
