package com.sadad.app;
import org.json.JSONObject;

/** Complete history transfer with bounded memory. Partial downloads never replace the ledger. */
final class PagedSnapshotTransfer {
    static void download(SessionStore session, SadadDatabase database, long revision) throws Exception {
        synchronized (SyncScheduler.LOCK) {
            final String token=session.token(), account=session.syncAccountId();
            long watermark=database.outboxWatermark();database.beginSnapshotStage();JSONObject accountInfo=null;
            try {
                for(String table:new String[]{"contacts","debts","payments"}) {
                    long after=0;boolean done=false;
                    while(!done) {
                        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                        JSONObject page=LedgerSyncService.request(session,"GET","/mobile/snapshot-page?table="+table+"&after="+after+"&revision="+revision,null,token);
                        if(!account.equals(session.syncAccountId())||!token.equals(session.token()))throw new IllegalStateException("تغيّر الحساب أثناء التحميل.");
                        database.stageSnapshotPage(table,page.getJSONArray("rows"));
                        long next=page.getLong("nextAfter");done=page.getBoolean("done");if(!done&&next<=after)throw new IllegalStateException("مؤشر تحميل غير صالح.");after=next;accountInfo=page.optJSONObject("account");
                    }
                }
                JSONObject status=LedgerSyncService.request(session,"GET","/mobile/session",null,token);
                if(status.optLong("revision")!=revision)throw new IllegalStateException("تغيّر سجل الخادم أثناء التحميل؛ بقيت النسخة المحلية محفوظة.");
                if(!account.equals(session.syncAccountId())||!token.equals(session.token()))throw new IllegalStateException("تغيّر الحساب أثناء التحميل.");
                database.commitSnapshotStage(watermark);session.setRevision(revision);session.setUnsyncedChanges(false);session.setSyncBlocked(false);if(accountInfo!=null)session.setAccount(accountInfo);
            } finally { database.dropSnapshotStage(); }
        }
    }
}
