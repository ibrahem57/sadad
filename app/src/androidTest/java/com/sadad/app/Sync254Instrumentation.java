package com.sadad.app;
import android.app.*;import android.app.job.*;import android.content.*;import android.os.Bundle;import org.json.*;import java.nio.file.*;

/** Runs real scheduled jobs against a throwaway local admin server, with no Activity open. */
public final class Sync254Instrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();SadadDatabase db=null;try{
        check(getTargetContext().getPackageName().contains(".qa")&&!BuildConfig.STANDALONE_MODE,"Connected QA only");
        JSONObject fixture=new JSONObject(new String(Files.readAllBytes(Paths.get("/data/local/tmp/sadad-qa-sync.json")),java.nio.charset.StandardCharsets.UTF_8));
        SessionStore session=new SessionStore(getTargetContext());session.save(fixture);session.setApiBaseUrl("http://10.0.2.2:18082/api");session.setForcePasswordChange(false);session.setSyncBlocked(false);
        String filename=session.databaseNameForAccount(session.syncAccountId());getTargetContext().deleteDatabase(filename);db=new SadadDatabase(getTargetContext(),filename);
        long person=db.saveContact(new JSONObject().put("name","اختبار الخلفية")).getLong("id");db.saveDebt(new JSONObject().put("contactId",person).put("amount","80").put("direction","receivable"));db.savePayment(new JSONObject().put("contactId",person).put("amount","15").put("method","bank").put("createdBy","رامي"));session.setUnsyncedChanges(true);
        SyncScheduler.schedule(getTargetContext(),true);JobScheduler scheduler=(JobScheduler)getTargetContext().getSystemService(Context.JOB_SCHEDULER_SERVICE);
        JobInfo hourly=scheduler.getPendingJob(SyncScheduler.HOURLY);check(hourly!=null&&hourly.getIntervalMillis()==3_600_000L&&hourly.isPersisted()&&hourly.getNetworkType()==JobInfo.NETWORK_TYPE_ANY,"Hourly constraints");
        immediate(scheduler);Thread.sleep(2500);check(session.hasUnsyncedChanges()&&db.getSnapshot().getJSONArray("contacts").length()==1,"Failed upload lost offline data");
        immediate(scheduler);waitClean(session);JSONObject server=LedgerSyncService.request(session,"GET","/mobile/snapshot",null,session.token()).getJSONObject("snapshot");check(server.getJSONObject("totals").getDouble("receivable")==65,"Scheduled upload mismatch");
        JSONObject incoming=new JSONObject(server.toString());incoming.getJSONArray("contacts").put(new JSONObject().put("id",99).put("name","تحديث من الإدارة").put("createdAt",System.currentTimeMillis()));LedgerSyncService.request(session,"POST","/mobile/sync",new JSONObject().put("baseRevision",session.revision()).put("snapshot",incoming),session.token());
        immediate(scheduler);long end=System.currentTimeMillis()+30000;while(db.getSnapshot().getJSONArray("contacts").length()!=2&&System.currentTimeMillis()<end)Thread.sleep(200);check(db.getSnapshot().getJSONArray("contacts").length()==2,"Background download missing");
        JSONObject remote=LedgerSyncService.request(session,"GET","/mobile/snapshot",null,session.token()).getJSONObject("snapshot");remote.getJSONArray("contacts").put(new JSONObject().put("id",100).put("name","جهاز آخر").put("createdAt",System.currentTimeMillis()));LedgerSyncService.request(session,"POST","/mobile/sync",new JSONObject().put("baseRevision",session.revision()).put("snapshot",remote),session.token());
        db.saveContact(new JSONObject().put("name","تعديل محلي متعارض"));session.setUnsyncedChanges(true);immediate(scheduler);end=System.currentTimeMillis()+30000;while(!session.syncBlocked()&&System.currentTimeMillis()<end)Thread.sleep(200);check(session.syncBlocked()&&session.hasUnsyncedChanges()&&db.getSnapshot().getJSONArray("contacts").length()==3,"Conflict lost local edits");
        result.putString("stream","PASS: persisted hourly network job, failed upload preserves offline records, retry uploads balance 65, background download, conflicts retain local changes\n");finish(-1,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(e));finish(0,result);}finally{if(db!=null)db.close();}}
    void immediate(JobScheduler scheduler){scheduler.cancel(SyncScheduler.PENDING);SyncScheduler.schedule(getTargetContext(),true);check(scheduler.getPendingJob(SyncScheduler.PENDING)!=null,"Pending retry missing");}
    void waitClean(SessionStore session)throws Exception{long end=System.currentTimeMillis()+30000;while(session.hasUnsyncedChanges()&&System.currentTimeMillis()<end)Thread.sleep(200);check(!session.hasUnsyncedChanges(),"Pending upload did not finish");Thread.sleep(300);}
    void check(boolean condition,String text){if(!condition)throw new AssertionError(text);}
}
