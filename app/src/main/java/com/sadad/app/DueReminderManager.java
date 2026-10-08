package com.sadad.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;

final class DueReminderManager {
    static final String CHANNEL_ID = "sadad_due_dates";
    private static final String PREFS = "sadad.due.notifications";
    private static final String ALERT_ACTION = "com.sadad.app.DUE_DATE_REMINDER";
    private static final int ALARM_ID = 9301;

    private DueReminderManager() { }

    static void setStoreName(Context context, String value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("store_name", value == null ? "" : value.trim()).apply();
    }

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        Uri sound = Settings.System.DEFAULT_NOTIFICATION_URI;
        AudioAttributes audio = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build();
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "تذكيرات مواعيد الديون", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("تنبيه قبل موعد استحقاق الدين وفي يوم الاستحقاق");
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0, 250, 150, 250});
        channel.setSound(sound, audio);
        manager.createNotificationChannel(channel);
    }

    static void scheduleDaily(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        Intent intent = new Intent(context, DueReminderReceiver.class).setAction(ALERT_ACTION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getBroadcast(context, ALARM_ID, intent, flags);
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, 9);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= System.currentTimeMillis()) next.add(Calendar.DAY_OF_YEAR, 1);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pending);
        else manager.set(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pending);
    }

    static void checkDueDates(Context context) {
        ensureChannel(context);
        String today = day(Calendar.getInstance());
        Calendar tomorrowCalendar = Calendar.getInstance();
        tomorrowCalendar.add(Calendar.DAY_OF_YEAR, 1);
        String tomorrow = day(tomorrowCalendar);
        String storeName = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("store_name", "").trim();
        HashMap<Long, String> names = new HashMap<>();
        try {
            SessionStore session = new SessionStore(context);
            SadadDatabase database = new SadadDatabase(context, session.databaseNameForAccount(session.syncAccountId()));
            JSONObject snapshot = database.dueReminderSnapshot(today, tomorrow);
            JSONArray contacts = snapshot.optJSONArray("contacts"), debts = snapshot.optJSONArray("debts");
            if (contacts != null) for (int i = 0; i < contacts.length(); i++) {
                JSONObject person = contacts.optJSONObject(i);
                if (person != null) names.put(person.optLong("id"), person.optString("name", "شخص"));
            }
            if (debts != null) for (int i = 0; i < debts.length(); i++) {
                JSONObject debt = debts.optJSONObject(i);
                if (debt == null || !"receivable".equals(debt.optString("direction")) || debt.optDouble("remaining") <= 0) continue;
                String due = debt.optString("dueDate", "");
                String stage = due.equals(today) ? "today" : due.equals(tomorrow) ? "tomorrow" : "";
                if (stage.isEmpty()) continue;
                String key = debt.optLong("id") + ":" + due + ":" + stage;
                if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("sent_" + key, false)) continue;
                long contactId = debt.optLong("contactId");
                String contactName = names.get(contactId);
                if (contactName == null || contactName.trim().isEmpty()) contactName = "شخص";
                JSONObject event = new JSONObject()
                        .put("id", key)
                        .put("debtId", debt.optLong("id"))
                        .put("contactId", contactId)
                        .put("contactName", contactName)
                        .put("storeName", storeName)
                        .put("amount", debt.optDouble("remaining"))
                        .put("dueDate", due)
                        .put("stage", stage)
                        .put("createdAt", System.currentTimeMillis())
                        .put("read", false);
                saveEvent(context, key, event);
                postNotification(context, key, contactName, storeName, debt.optDouble("remaining"), stage, contactId);
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("sent_" + key, true).apply();
            }
            database.close();
        } catch (Exception ignored) { }
    }

    private static String day(Calendar calendar) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.getTime());
    }

    private static void saveEvent(Context context, String key, JSONObject event) throws Exception {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray old;
        try { old = new JSONArray(prefs.getString("inbox", "[]")); }
        catch (Exception e) { old = new JSONArray(); }
        JSONArray next = new JSONArray();
        next.put(event);
        for (int i = 0; i < old.length() && next.length() < 60; i++) {
            JSONObject row = old.optJSONObject(i);
            if (row != null && !key.equals(row.optString("id"))) next.put(row);
        }
        prefs.edit().putString("inbox", next.toString()).apply();
    }

    private static void postNotification(Context context, String key, String contactName, String storeName, double amount, String stage, long contactId) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        Intent open = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("sadad_target_route", "contact_detail").putExtra("sadad_contact_id", contactId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent tap = PendingIntent.getActivity(context, key.hashCode(), open, flags);
        String storeLabel = storeName == null || storeName.trim().isEmpty() ? "سدد" : storeName.trim();
        String title = ("today".equals(stage) ? "موعد الاستحقاق اليوم" : "موعد الاستحقاق غداً") + " · " + storeLabel;
        String message = contactName + " · " + String.format(Locale.US, "%.2f", amount) + " ₪ · " + storeLabel;
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID) : new Notification.Builder(context);
        Notification notification = builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title).setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(tap).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER)
                .setPriority(Notification.PRIORITY_HIGH).build();
        try { manager.notify(key.hashCode(), notification); } catch (SecurityException ignored) { }
    }

    static JSONArray inbox(Context context) {
        try { return new JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("inbox", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    static int unreadCount(Context context) {
        JSONArray rows = inbox(context);
        int count = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && !row.optBoolean("read", false)) count++;
        }
        return count;
    }

    static JSONObject dismiss(Context context, String id) {
        if (id == null || id.isEmpty()) return null;
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray old = inbox(context), updated = new JSONArray();
        JSONObject removed = null;
        for (int i = 0; i < old.length(); i++) {
            JSONObject row = old.optJSONObject(i);
            if (row == null) continue;
            if (id.equals(row.optString("id"))) removed = copy(row);
            else updated.put(row);
        }
        if (removed == null) return null;
        prefs.edit().putString("inbox", updated.toString()).apply();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(id.hashCode());
        return removed;
    }

    static void restore(Context context, JSONObject event) {
        if (event == null) return;
        String id = event.optString("id", "");
        if (id.isEmpty()) return;
        JSONArray old = inbox(context), updated = new JSONArray();
        boolean exists = false;
        updated.put(copy(event));
        for (int i = 0; i < old.length(); i++) {
            JSONObject row = old.optJSONObject(i);
            if (row == null) continue;
            if (id.equals(row.optString("id"))) { exists = true; break; }
            if (updated.length() < 60) updated.put(row);
        }
        if (exists) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("inbox", updated.toString()).apply();
        postNotification(context, id, event.optString("contactName", "شخص"), event.optString("storeName", ""), event.optDouble("amount"), event.optString("stage"), event.optLong("contactId"));
    }

    static void markAllRead(Context context) {
        JSONArray rows = inbox(context), updated = new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null) { row = copy(row); row.remove("read"); try { row.put("read", true); } catch (Exception ignored) { } updated.put(row); }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("inbox", updated.toString()).apply();
    }

    private static JSONObject copy(JSONObject source) {
        try { return new JSONObject(source.toString()); } catch (Exception e) { return new JSONObject(); }
    }
}
