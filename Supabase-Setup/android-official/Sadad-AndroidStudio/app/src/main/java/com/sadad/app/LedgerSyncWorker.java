package com.sadad.app;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.util.concurrent.TimeUnit;
public final class LedgerSyncWorker extends Worker {
 public LedgerSyncWorker(@NonNull Context context,@NonNull WorkerParameters parameters){super(context,parameters);}
 static void schedule(Context context){OneTimeWorkRequest request=new OneTimeWorkRequest.Builder(LedgerSyncWorker.class).setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build();WorkManager.getInstance(context).enqueueUniqueWork("sadid-durable-sync",ExistingWorkPolicy.APPEND_OR_REPLACE,request);}
 @NonNull public Result doWork(){LedgerRepository repo=null;try{SessionStore sessions=new SessionStore(getApplicationContext());if(!sessions.hasCredentials()||sessions.forcePasswordChange())return Result.success();repo=new LedgerRepository(getApplicationContext(),sessions);repo.sync();return Result.success();}catch(LedgerTransport.Failure f){return f.status==401||f.status==403?Result.failure():Result.retry();}catch(Exception e){return Result.retry();}finally{if(repo!=null)repo.journal.close();}}
}
