package com.sadad.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class DueReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        DueReminderManager.scheduleDaily(context);
        DueReminderManager.checkDueDates(context);
    }
}
