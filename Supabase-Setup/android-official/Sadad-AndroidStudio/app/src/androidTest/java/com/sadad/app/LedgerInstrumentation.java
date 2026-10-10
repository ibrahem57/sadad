package com.sadad.app;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** اختبارات محلية على Android فعلي باستخدام Keystore وSQLite؛ ليست اختبارات خادم. */
public final class LedgerInstrumentation extends Instrumentation {
 private final JSONArray tests=new JSONArray();
 private void check(String name,boolean condition)throws Exception{if(!condition)throw new AssertionError(name);tests.put(new JSONObject().put("name",name).put("status","passed"));}
 public void onCreate(Bundle args){super.onCreate(args);start();}
 public void onStart(){Bundle result=new Bundle();LedgerRepository repo=null;try{
  Context context=getTargetContext().createDeviceProtectedStorageContext();SessionStore session=new SessionStore(context);String user=UUID.randomUUID().toString();String jwt="e30."+InstallationKey.b64(new JSONObject().put("sub",user).toString().getBytes(StandardCharsets.UTF_8))+".local";
  session.save(new JSONObject().put("token","local-device-token").put("expiresAt",System.currentTimeMillis()+3600000).put("account",new JSONObject().put("id","-99")).put("auth",new JSONObject().put("accessToken",jwt).put("refreshToken","local-refresh-token").put("expiresAt",System.currentTimeMillis()+3600000)));
  repo=new LedgerRepository(context,session);String scope=repo.journal.scope,contact=UUID.randomUUID().toString(),debt=UUID.randomUUID().toString(),epoch=UUID.randomUUID().toString();
  repo.journal.metadata("installation",new JSONObject().put("generation",1).put("status","active"));
  JSONObject lease=new JSONObject().put("issuedAt",System.currentTimeMillis()).put("expiresAt",System.currentTimeMillis()+7*86400000L);
  repo.journal.metadata("lease",new JSONObject().put("payload",lease).put("elapsed",SystemClock.elapsedRealtime()).put("wall",System.currentTimeMillis()).put("boot",Settings.Global.getInt(context.getContentResolver(),"boot_count",-1)));
  JSONObject snapshot=new JSONObject().put("epoch",epoch).put("cursor",0).put("contacts",new JSONArray().put(new JSONObject().put("id",contact).put("name","اسم سري لا يظهر في SQLite").put("archived",false).put("version",1))).put("debts",new JSONArray().put(new JSONObject().put("id",debt).put("contact_id",contact).put("direction","receivable").put("amount_cents",50000))).put("payments",new JSONArray()).put("outcomes",new JSONArray()).put("reversals",new JSONArray()).put("adjustments",new JSONArray());
  repo.journal.merge(snapshot);JSONObject first=repo.add("payment.create",new JSONObject().put("debtId",debt).put("amountCents",10000).put("method","cash"));
  check("الرصيد يشمل دفعة معلقة ٥٠٠ ← ٤٠٠",repo.view().getJSONArray("debts").getJSONObject(0).getLong("remainingCents")==40000);
  repo.journal.close();repo=new LedgerRepository(context,session);check("الطابور يبقى بعد إغلاق قاعدة البيانات وإعادة فتحها",repo.journal.entries().length()==1);
  JSONObject sending=repo.journal.startSend(first.getJSONObject("command").getString("operationId"));boolean cancelled=false;try{repo.journal.cancel(first.getJSONObject("command").getString("operationId"));cancelled=true;}catch(IllegalArgumentException expected){}check("لا إلغاء بعد بدء الإرسال",!cancelled);
  repo.journal.state(sending,"uncertain","انقطاع الشبكة");repo.journal.merge(snapshot);check("نسخة خادم قديمة لا تمحو الأثر غير المؤكد",repo.view().getJSONArray("debts").getJSONObject(0).getLong("remainingCents")==40000);
  JSONObject receipt=new JSONObject().put("operationId",first.getJSONObject("command").getString("operationId")).put("status","accepted").put("sequence",1).put("epoch",epoch);
  sending.put("receipt",receipt);repo.journal.state(sending,"acknowledged","");check("الإيصال لا يزيل الإسقاط قبل وصول النسخة",repo.view().getJSONArray("debts").getJSONObject(0).getLong("remainingCents")==40000);
  snapshot.put("cursor",1).put("payments",new JSONArray().put(new JSONObject().put("id",first.getJSONObject("command").getString("entityId")).put("debt_id",debt).put("amount_cents",10000))).put("outcomes",new JSONArray().put(receipt));repo.journal.merge(snapshot);
  check("تأكيد الدفعة لا يخصم المبلغ مرتين",repo.view().getJSONArray("debts").getJSONObject(0).getLong("remainingCents")==40000);
  JSONObject rejected=repo.add("payment.create",new JSONObject().put("debtId",debt).put("amountCents",2000).put("method","cash"));repo.journal.state(rejected,"rejected","رفض نهائي");check("الرفض يزيل الإسقاط ويحفظ التفاصيل",repo.view().getJSONArray("debts").getJSONObject(0).getLong("remainingCents")==40000&&repo.journal.entries().length()==2);
  JSONObject queued=repo.add("payment.create",new JSONObject().put("debtId",debt).put("amountCents",10000).put("method","cash"));boolean exceeded=false;try{repo.add("payment.create",new JSONObject().put("debtId",debt).put("amountCents",30001).put("method","cash"));exceeded=true;}catch(IllegalArgumentException expected){}check("الدفعات المتكررة لا تتجاوز المتبقي المتوقع",!exceeded);repo.journal.cancel(queued.getJSONObject("command").getString("operationId"));check("الإلغاء غير المرسل محفوظ في التاريخ",repo.journal.entries().getJSONObject(2).getString("state").equals("cancelled"));
  JSONObject bundle=repo.journal.exportPlain();byte[] sealed=RecoveryCrypto.seal(bundle,"عبارة مرور قوية للتجربة ١٢٣".toCharArray());boolean wrong=false;try{RecoveryCrypto.open(sealed,"كلمة خاطئة لا تفتح الملف".toCharArray());wrong=true;}catch(Exception expected){}check("رفض عبارة مرور خاطئة",!wrong);JSONObject decoded=RecoveryCrypto.open(sealed,"عبارة مرور قوية للتجربة ١٢٣".toCharArray());repo.journal.importPlain(decoded);check("استيراد النسخة ذاتها لا يكرر العمليات",repo.journal.entries().length()==3);
  check("التصدير لا يحتوي رموز الدخول",!bundle.toString().contains("local-refresh-token")&&!bundle.toString().contains("local-device-token"));
  repo.journal.metadata("lease",new JSONObject().put("payload",new JSONObject().put("issuedAt",0).put("expiresAt",1)).put("elapsed",SystemClock.elapsedRealtime()).put("wall",System.currentTimeMillis()).put("boot",Settings.Global.getInt(context.getContentResolver(),"boot_count",-1)));check("انتهاء الأيام السبعة يوقف الإضافة ويبقي القراءة",!repo.canAddOffline()&&repo.view().getJSONArray("contacts").length()==1);
  result.putString("evidence",new JSONObject().put("engine","Android Keystore + SQLite/WAL").put("leaseFixture","محلي للاختبار؛ فحص الترخيص الموقع منفصل").put("tests",tests).toString());finish(0,result);
 }catch(Throwable e){result.putString("failure",e.toString());result.putString("passedBeforeFailure",tests.toString());finish(1,result);}finally{if(repo!=null)repo.journal.close();}}
}
