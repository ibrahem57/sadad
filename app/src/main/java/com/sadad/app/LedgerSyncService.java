package com.sadad.app;
import android.app.job.*;
import org.json.JSONObject;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** No UI or foreground-service requirement: constrained work runs on a single worker. */
public final class LedgerSyncService extends JobService {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ConcurrentHashMap<Integer,Future<?>> tasks=new java.util.concurrent.ConcurrentHashMap<>();
    @Override public boolean onStartJob(JobParameters params) {
        if(BuildConfig.STANDALONE_MODE)return false;
        MainActivity activity=MainActivity.activeInstance.get();
        if(activity!=null){activity.syncTick();return false;}
        Future<?> task=worker.submit(()->{boolean retry=false;
            synchronized(SyncScheduler.LOCK){
                SessionStore session=new SessionStore(this);SadadDatabase db=null;
                try{
                    if(!session.isActive()||session.forcePasswordChange()||session.syncBlocked())return;
                    String account=session.syncAccountId(),token=session.token();if(token.isEmpty())return;
                    db=new SadadDatabase(this,session.databaseNameForAccount(account));
                    if(db.hasPendingSync())session.setUnsyncedChanges(true);
                    long change=db.changeToken();JSONObject result;
                    if(session.hasUnsyncedChanges()){
                        if (db.deltaReady()) {
                            JSONObject packet=db.pendingDelta(); result=request(session,"POST","/mobile/sync-delta",new JSONObject().put("baseRevision",session.revision()).put("delta",packet.getJSONObject("delta")).put("batchId",session.deviceId()+":"+packet.optLong("watermark")),token);
                            if(!account.equals(session.syncAccountId())||!token.equals(session.token()))return;
                            db.acknowledgeDelta(packet.optLong("watermark")); session.setRevision(result.optLong("revision",session.revision()));
                            session.setUnsyncedChanges(db.hasPendingSync());session.setSyncBlocked(false);
                            if(session.hasUnsyncedChanges())retry=true;
                            return;
                        }
                        result=request(session,"POST","/mobile/sync",new JSONObject().put("baseRevision",session.revision()).put("snapshot",db.getSnapshot()),token);
                    }else{
                        JSONObject status=request(session,"GET","/mobile/session",null,token);
                        if(!account.equals(session.syncAccountId()))return;
                        JSONObject info=status.optJSONObject("account");if(info!=null)session.setAccount(info);
                        session.setForcePasswordChange(status.optBoolean("forcePasswordChange",false));if(session.forcePasswordChange())return;
                        if(status.optLong("revision",session.revision())==session.revision())return;
                        PagedSnapshotTransfer.download(session,db,status.optLong("revision",session.revision()));
                        return;
                    }
                    if(!account.equals(session.syncAccountId())||!token.equals(session.token()))return;
                    // An activity may have opened while the request was in flight. Its edits stay local.
                    if(MainActivity.activeInstance.get()!=null||db.changeToken()!=change){retry=true;return;}
                    JSONObject snapshot=result.optJSONObject("snapshot");if(snapshot!=null){db.importSnapshot(snapshot);JSONObject info=snapshot.optJSONObject("account");if(info!=null)session.setAccount(info);}
                    session.setRevision(result.optLong("revision",snapshot==null?session.revision():snapshot.optLong("revision",session.revision())));
                    session.setUnsyncedChanges(false);session.setSyncBlocked(false);DueReminderManager.checkDueDates(this);
                }catch(HttpError e){if(e.code==409)session.setSyncBlocked(true);else retry=e.code>=500;}
                catch(Exception e){retry=true;}
                finally{if(db!=null)db.close();tasks.remove(params.getJobId());jobFinished(params,retry);}
            }
        });tasks.put(params.getJobId(),task);return true;
    }
    @Override public boolean onStopJob(JobParameters params){Future<?> task=tasks.remove(params.getJobId());if(task!=null)task.cancel(true);return true;}
    @Override public void onDestroy(){worker.shutdownNow();super.onDestroy();}
    static final class HttpError extends Exception{final int code;HttpError(int code){this.code=code;}}
    static JSONObject request(SessionStore session,String method,String path,JSONObject body,String token)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL(session.apiBaseUrl().replaceAll("/+$","")+path).openConnection();
        try{connection.setRequestMethod(method);connection.setConnectTimeout(6000);connection.setReadTimeout(10000);connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Authorization","Bearer "+token);
            if(body!=null){connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");try(OutputStream out=connection.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int code=connection.getResponseCode();if(code<200||code>=300)throw new HttpError(code);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=connection.getInputStream()){byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException();bytes.write(buffer,0,count);}}
            return new JSONObject(bytes.toString("UTF-8"));
        }finally{connection.disconnect();}
    }
}
