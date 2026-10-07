package com.sadad.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ReminderBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        DueReminderManager.scheduleDaily(context);
        SyncScheduler.schedule(context, new SessionStore(context).hasUnsyncedChanges());
    }
}
