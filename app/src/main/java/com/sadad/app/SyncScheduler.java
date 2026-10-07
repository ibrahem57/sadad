package com.sadad.app;
import android.app.job.*;
import android.content.*;

/** Android persists hourly work and holds pending uploads until connectivity returns. */
final class SyncScheduler {
    static final int HOURLY = 25401, PENDING = 25402;
    static final Object LOCK = new Object();
    static void schedule(Context context, boolean pending) {
        if(BuildConfig.STANDALONE_MODE)return;
        JobScheduler jobs=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);if(jobs==null)return;
        ComponentName service=new ComponentName(context,LedgerSyncService.class);
        if(jobs.getPendingJob(HOURLY)==null)jobs.schedule(new JobInfo.Builder(HOURLY,service).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(3_600_000L, 300_000L).setPersisted(true).build());
        if(pending && jobs.getPendingJob(PENDING)==null) {
            JobInfo.Builder upload=new JobInfo.Builder(PENDING,service).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setBackoffCriteria(30_000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL).setPersisted(true);
            if(android.os.Build.VERSION.SDK_INT>=31)upload.setExpedited(true);
            if(jobs.schedule(upload.build())==JobScheduler.RESULT_FAILURE) {
                // Expedited quota can be exhausted; preserve a normal queued upload in that case.
                if(android.os.Build.VERSION.SDK_INT>=31)upload.setExpedited(false);
                jobs.schedule(upload.build());
            }
        }
    }
}
