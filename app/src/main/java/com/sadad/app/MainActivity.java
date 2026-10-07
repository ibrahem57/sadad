package com.sadad.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ClipData;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.fingerprint.FingerprintManager;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URI;
import java.util.Locale;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

/** Native Android activity for the offline test build. */
public final class MainActivity extends Activity {
    private static final String PREFS = "sadad.device.preferences";
    private static final String PREF_FORCED_PASSWORD_LOGIN = "forced_password_login_required";
    private static final String PREF_FORCED_PASSWORD_WELCOME = "forced_password_welcome_shown";
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    static final java.lang.ref.WeakReference<MainActivity> EMPTY_INSTANCE = new java.lang.ref.WeakReference<>(null);
    static volatile java.lang.ref.WeakReference<MainActivity> activeInstance = EMPTY_INSTANCE;
    void syncTick() { if (sessions == null || !sessions.isActive()) return; if (sessions.hasUnsyncedChanges()) syncLocalChanges(); else checkServerUpdates(); }
    private final AtomicBoolean syncing = new AtomicBoolean(false);
    private volatile boolean syncAgain;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Handler undoHandler = new Handler(Looper.getMainLooper());
    private int undoBannerCount;
    private final Runnable statusPoll = new Runnable() {
        @Override public void run() {
            if (BuildConfig.STANDALONE_MODE) return;
            if (sessions != null && !sessions.token().isEmpty() && !sessions.forcePasswordChange()) {
                if (!sessions.isActive()) {
                    sessions.clear();
                    show("login");
                    showBrandedMessage("انتهت الجلسة أو مدة الاشتراك. تواصل مع الأدمن للتجديد.");
                    return;
                }
                syncTick();
                statusHandler.postDelayed(this, 3_600_000L);
            }
        }
    };
    private JSONObject pendingLegacyServerSnapshot;
    private boolean pendingLegacyServerHasRows;
    SadadDatabase database;
    SessionStore sessions;
    LocalSecurity localSecurity;
    private NativeScreens screens;
    private volatile String currentRoute = "welcome";
    private String contactFormOrigin = "home";
    String contactFormBackTarget() { return contactFormOrigin; }
    private volatile boolean biometricPromptInProgress;
    private volatile boolean demoCustomerSession;
    private long leftAt;
    private long pendingNotificationContactId;
    private static final int REQUEST_SAVE_PDF = 7315;
    private static final int REQUEST_VOICE_DRAFT = 7316;
    private static final int REQUEST_PROFILE_PHOTO = 7317;
    private boolean pendingStorePhoto;
    private String pendingPhotoAccount;
    private boolean pendingPdfShare = true;
    private EditText pendingVoiceDraftTarget;
    private boolean ignoreNextResumeLock;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback connectivityCallback;
    private byte[] pendingPdf;
    private String pendingPdfName;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        sessions = new SessionStore(this);
        sessions.ensureCurrentAccountSaved();
        if (sessions.forcePasswordChange()) {
            android.content.SharedPreferences.Editor onboarding = getPreferences(0).edit().putBoolean(PREF_FORCED_PASSWORD_LOGIN, true);
            if (!getPreferences(0).getBoolean(PREF_FORCED_PASSWORD_WELCOME, false)) {
                onboarding.putBoolean("welcome_seen", false).putBoolean(PREF_FORCED_PASSWORD_WELCOME, true);
            }
            onboarding.apply();
        }
        database = new SadadDatabase(this, sessions.databaseNameForAccount(sessions.syncAccountId()));
        localSecurity = new LocalSecurity(this);
        DueReminderManager.ensureChannel(this);
        DueReminderManager.scheduleDaily(this);
        network.execute(() -> DueReminderManager.checkDueDates(getApplicationContext()));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (getPreferences(0).getBoolean("demo_data_seeded", false)) {
            database.removeLegacySampleRecords();
            getPreferences(0).edit().remove("demo_data_seeded").apply();
        }
        screens = new NativeScreens(this);
        captureNotificationTarget(getIntent());
        DueReminderManager.setStoreName(this, storeName());
        applyTheme(isDarkTheme());
        if (!getPreferences(0).getBoolean("welcome_seen", false)) screens.show("welcome");
        else openInitialRoute();
        activeInstance = new java.lang.ref.WeakReference<>(this);
        SyncScheduler.schedule(this, sessions.hasUnsyncedChanges());
        registerConnectivitySync();
    }

    boolean isDarkTheme() { return "dark".equals(getPreferences(0).getString("theme", "light")); }
    int brandLogoResource() { return isDarkTheme() ? R.drawable.sadad_logo_dark : R.drawable.sadad_logo; }
    String currentRoute() { return currentRoute; }
    void refreshCurrentScreen() { if (screens != null) screens.show(currentRoute); }
    void show(String route) {
        if (route == null || route.isEmpty() || route.equals(currentRoute)) return;
        if ("customer_login".equals(route) || "customer_portal".equals(route)) { show(sessions.isActive() ? "home" : "login"); return; }
        if (route.equals("login") || route.equals("welcome") || route.equals("unlock") || route.equals("force_password") || route.equals("customer_login")) {
            currentRoute = route; screens.show(route); return;
        }
        if (route.equals("customer_portal") && BuildConfig.STANDALONE_MODE && demoCustomerSession) {
            currentRoute = route; screens.show(route); return;
        }
        if (!sessions.isActive()) { currentRoute = "login"; screens.show("login"); return; }
        if ("contact_form".equals(route)) contactFormOrigin = currentRoute;
        currentRoute = route;
        screens.show(route);
        if ("home".equals(route)) maybeRequestNotificationPermission();
    }

    void finishWelcome() {
        getPreferences(0).edit().putBoolean("welcome_seen", true).putBoolean(PREF_FORCED_PASSWORD_WELCOME, true).apply();
        openInitialRoute();
    }

    void loginDemoCustomer(String phone, String password) {
        if (!BuildConfig.STANDALONE_MODE) {
            showBrandedMessage("دخول الزبائن الرسمي سيفعّل بعد ربط التحقق الحقيقي من رقم الهاتف بالخادم.");
            return;
        }
        String normalized = normalizeCustomerPhone(phone);
        if (!"97059999999".equals(normalized) || !"123123".equals(password)) {
            showBrandedMessage("رقم الهاتف أو كلمة المرور غير صحيحة.");
            return;
        }
        demoCustomerSession = true;
        show("customer_portal");
    }

    void exitDemoCustomer() {
        demoCustomerSession = false;
        show("login");
    }

    private String normalizeCustomerPhone(String value) {
        if (value == null) return "";
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '\u0660' && c <= '\u0669') c = (char) ('0' + c - '\u0660');
            else if (c >= '\u06F0' && c <= '\u06F9') c = (char) ('0' + c - '\u06F0');
            if (c >= '0' && c <= '9') digits.append(c);
        }
        String normalized = digits.toString();
        if (normalized.startsWith("00970")) return normalized.substring(2);
        if (normalized.startsWith("970")) return normalized;
        if (normalized.startsWith("0")) return "970" + normalized.substring(1);
        return normalized;
    }

    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                || getPreferences(0).getBoolean("notification_permission_prompted", false)) return;
        getPreferences(0).edit().putBoolean("notification_permission_prompted", true).apply();
        new SadadDialog.Builder(this)
                .setTitle("تذكير بمواعيد الاستحقاق")
                .setMessage("اسمح لسدد بإظهار تنبيه قبل موعد الدين بيوم وفي يوم الاستحقاق. يمكنك تغيير الإذن لاحقاً من إعدادات التطبيق.")
                .setNegativeButton("لاحقاً", null)
                .setPositiveButton("متابعة", (dialog, which) -> requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7316))
                .show();
    }

    void openNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
            catch (Exception ignored) { showBrandedMessage("افتح إعدادات التطبيق وفعّل الإشعارات."); }
        }
    }

    String storeName() {
        String id=sessions==null?"":sessions.syncAccountId();
        if(id.isEmpty()) return getPreferences(0).getString("store_name", "").trim();
        String saved=getPreferences(0).getString(profileKey("store_name",id),"").trim();
        return saved.isEmpty()?sessions.account().optString("name","").trim():saved;
    }
    String ownerName() {
        String id=sessions==null?"":sessions.syncAccountId();
        return id.isEmpty()?getPreferences(0).getString("owner_name", "").trim():getPreferences(0).getString(profileKey("owner_name",id),"").trim();
    }
    private String profileKey(String prefix,String accountId) {
        try { byte[] digest=MessageDigest.getInstance("SHA-256").digest(accountId.getBytes(StandardCharsets.UTF_8)); StringBuilder suffix=new StringBuilder(); for(int i=0;i<8;i++)suffix.append(String.format(Locale.US,"%02x",digest[i])); return prefix+"_"+suffix; }
        catch(Exception e){return prefix+"_"+Integer.toHexString(accountId.hashCode());}
    }
    String username() { return sessions == null ? "" : sessions.account().optString("username", "").trim(); }
    boolean configureApiBaseUrl(String value) {
        try {
            String normalized = value == null ? "" : value.trim();
            while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
            URI uri = new URI(normalized);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !"/api".equals(uri.getPath()) || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)))
                throw new IllegalArgumentException("أدخل رابط الخادم كاملًا وينتهي بـ /api.");
            if (!BuildConfig.DEBUG && !"https".equalsIgnoreCase(scheme)) throw new IllegalArgumentException("يتطلب إصدار التطبيق اتصال HTTPS آمنًا.");
            sessions.setApiBaseUrl(normalized);
            return true;
        } catch (Exception e) {
            showBrandedMessage(e.getMessage() == null ? "رابط الخادم غير صالح." : e.getMessage());
            return false;
        }
    }
    void saveStoreProfile(String store, String owner) {
        String id=sessions==null?"":sessions.syncAccountId();
        android.content.SharedPreferences.Editor edit=getPreferences(0).edit().putString("store_name", store == null ? "" : store.trim())
                .putString("owner_name", owner == null ? "" : owner.trim());
        if(!id.isEmpty()) edit.putString(profileKey("store_name",id),store==null?"":store.trim()).putString(profileKey("owner_name",id),owner==null?"":owner.trim());
        edit.apply();
        DueReminderManager.setStoreName(this, store == null ? "" : store.trim());
        screens.show(currentRoute);
        showBrandedMessage("تم حفظ بيانات المتجر.");
    }

    interface ActionCallback { void complete(boolean success, String message); }

    void changeServerPassword(String previous, String next, ActionCallback callback) {
        if (next == null || next.length() < 12) { callback.complete(false, "كلمة المرور الجديدة يجب ألا تقل عن 12 محرفًا."); return; }
        if (BuildConfig.STANDALONE_MODE) {
            network.execute(() -> {
                if (!localSecurity.verifyLoginPassword(username(), previous, "123")) {
                    runOnUiThread(() -> callback.complete(false, "كلمة المرور السابقة غير صحيحة."));
                    return;
                }
                try {
                    localSecurity.setLoginPassword(username(), next);
                    runOnUiThread(() -> { showBrandedMessage("تم تغيير كلمة مرور هذا الجهاز."); callback.complete(true, ""); });
                } catch (Exception e) {
                    runOnUiThread(() -> callback.complete(false, e.getMessage() == null ? "تعذر تغيير كلمة المرور." : e.getMessage()));
                }
            });
            return;
        }
        JSONObject body = new JSONObject();
        try { body.put("currentPassword", previous == null ? "" : previous).put("password", next); }
        catch (JSONException e) { callback.complete(false, "تعذر تجهيز الطلب."); return; }
        network.execute(() -> {
            try {
                apiRequest("POST", "/mobile/change-password", body, sessions.token());
                sessions.setForcePasswordChange(false);
                getPreferences(0).edit().putBoolean(PREF_FORCED_PASSWORD_LOGIN, false).apply();
                runOnUiThread(() -> {
                    showBrandedMessage("تم تغيير كلمة مرور الحساب."); callback.complete(true, "");
                    if ("force_password".equals(currentRoute)) {
                        showAfterAuthentication();
                        if (pendingLegacyServerSnapshot != null) offerLegacyDataChoice(pendingLegacyServerSnapshot, pendingLegacyServerHasRows);
                        else syncLocalChanges();
                    }
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? "تعذر تغيير كلمة المرور." : e.getMessage();
                runOnUiThread(() -> callback.complete(false, message));
            }
        });
    }

    void openSupportWhatsApp() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/970599562401"));
            startActivity(intent);
        } catch (Exception e) { showBrandedMessage("تعذر فتح WhatsApp. تحقق من تثبيته أو اتصال الإنترنت."); }
    }

    private void openInitialRoute() {
        if (getPreferences(0).getBoolean(PREF_FORCED_PASSWORD_LOGIN, false)) {
            sessions.clear();
            show("login");
            return;
        }
        if (!sessions.isActive()) {
            sessions.clear();
            if (!sessions.activateFirstSavedAccount() || !sessions.isActive()) { show("login"); return; }
            switchDatabaseForAccount(sessions.syncAccountId());
        }
        if (sessions.forcePasswordChange()) {
            getPreferences(0).edit().putBoolean(PREF_FORCED_PASSWORD_LOGIN, true).apply();
            sessions.clear();
            show("login");
            return;
        }
        if (localSecurity.hasPin() && getPreferences(0).getBoolean("lock_enabled", true)) {
            show("unlock");
            if (getPreferences(0).getBoolean("biometric_enabled", false)) requestBiometric(false);
        } else showAfterAuthentication();
    }

    private void captureNotificationTarget(Intent intent) {
        if (intent == null || !"contact_detail".equals(intent.getStringExtra("sadad_target_route"))) return;
        long id = intent.getLongExtra("sadad_contact_id", 0L);
        if (id > 0L) pendingNotificationContactId = id;
    }

    private void showAfterAuthentication() {
        long target = pendingNotificationContactId;
        if (target > 0L && screens != null) {
            pendingNotificationContactId = 0L;
            screens.contactId = target;
            if ("contact_detail".equals(currentRoute)) screens.show("contact_detail");
            else show("contact_detail");
        } else show("home");
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        captureNotificationTarget(intent);
        if (pendingNotificationContactId <= 0L || screens == null) return;
        if (!sessions.isActive()) { show("login"); return; }
        if (sessions.forcePasswordChange()) { show("force_password"); return; }
        if (localSecurity.hasPin() && getPreferences(0).getBoolean("lock_enabled", true)) {
            show("unlock");
            if (getPreferences(0).getBoolean("biometric_enabled", false)) requestBiometric(false);
        } else showAfterAuthentication();
    }

    void loginLocal(String username, String password) { loginLocal(username, password, ""); }

    void loginLocal(String username, String password, String staffName) {
        String normalizedUsername = username == null ? "" : username.trim();
        if (normalizedUsername.isEmpty() || password == null || password.isEmpty()) { showBrandedMessage("أدخل اسم المستخدم وكلمة المرور."); return; }
        if (!BuildConfig.STANDALONE_MODE && !sessions.hasSavedUsername(normalizedUsername) && sessions.savedAccounts().length() >= 3) {
            showBrandedMessage("يمكن حفظ ثلاثة حسابات متاجر كحد أقصى. أزل حسابًا محفوظًا من الإعدادات أولًا."); return;
        }
        if (BuildConfig.STANDALONE_MODE) {
            loginStandalone(normalizedUsername, password, staffName);
            return;
        }
        if (sessions.hasUnsyncedChanges() && sessions.syncAccountId().isEmpty() && !sessions.syncOwner().isEmpty() && !sessions.syncOwner().equalsIgnoreCase(normalizedUsername)) {
            showBrandedMessage("توجد بيانات غير متزامنة لحساب آخر. سجّل الدخول بهذا الحساب أولًا مع اتصال بالخادم."); return;
        }
        showBrandedMessage("جارٍ الاتصال بالخادم والتحقق من الاشتراك والجهاز…");
        JSONObject localSnapshotBefore;
        try { localSnapshotBefore = database.getSnapshot(); }
        catch (Exception e) { localSnapshotBefore = new JSONObject(); }
        final JSONObject legacySnapshot = localSnapshotBefore;
        network.execute(() -> {
            long oldRevision = sessions.revision();
            boolean existingPending = sessions.hasUnsyncedChanges();
            String oldSyncOwner = sessions.syncOwner();
            String oldSyncAccountId = sessions.syncAccountId();
            boolean legacyLocal = !existingPending && oldSyncOwner.isEmpty() && hasLedgerRows(legacySnapshot);
            try {
                JSONObject request = new JSONObject().put("username", normalizedUsername).put("password", password)
                        .put("deviceId", sessions.deviceId()).put("deviceName", deviceName()).put("staffName", staffName == null ? "" : staffName.trim());
                JSONObject response = apiRequest("POST", "/mobile/login", request, "");
                JSONObject account = response.optJSONObject("account");
                JSONObject snapshot = response.optJSONObject("snapshot");
                String accountId = account == null ? "" : account.optString("id", "");
                boolean pending = existingPending && (oldSyncAccountId.isEmpty()
                        ? oldSyncOwner.equalsIgnoreCase(normalizedUsername)
                        : account != null && oldSyncAccountId.equals(accountId));
                if (existingPending && !pending) throw new IllegalStateException("توجد بيانات غير متزامنة تخص حساب متجر مختلف. سجّل الدخول بحسابه أو صدّرها قبل المتابعة.");
                sessions.save(response);
                if (!accountId.isEmpty()) switchDatabaseForAccount(accountId);
                if (account != null) sessions.setAccount(account);
                DueReminderManager.setStoreName(this, storeName());
                if (account != null) {
                    String accountStoreKey=profileKey("store_name",accountId), accountOwnerKey=profileKey("owner_name",accountId);
                    android.content.SharedPreferences prefs=getPreferences(0);
                    android.content.SharedPreferences.Editor profile=prefs.edit();
                    if (!prefs.contains(accountStoreKey)) profile.putString(accountStoreKey,account.optString("name",""));
                    if (!prefs.contains(accountOwnerKey) && "sadad.db".equals(sessions.databaseNameForAccount(accountId)) && !prefs.getString("owner_name","").trim().isEmpty()) profile.putString(accountOwnerKey,prefs.getString("owner_name","").trim());
                    profile.apply();
                }
                if (pending) { sessions.setRevision(oldRevision); sessions.setUnsyncedChanges(true); }
                else if (legacyLocal) { sessions.setUnsyncedChanges(true); sessions.setSyncBlocked(true); }
                else {
                    if (snapshot != null) database.importSnapshot(snapshot);
                    DueReminderManager.checkDueDates(getApplicationContext());
                    sessions.setUnsyncedChanges(false);
                }
                boolean mustChange = response.optBoolean("forcePasswordChange", false);
                runOnUiThread(() -> {
                    getPreferences(0).edit().putBoolean(PREF_FORCED_PASSWORD_LOGIN, false).apply();
                    if (mustChange) show("force_password");
                    else if (localSecurity.hasPin() && getPreferences(0).getBoolean("lock_enabled", true)) {
                        show("unlock"); if (getPreferences(0).getBoolean("biometric_enabled", false)) requestBiometric(false);
                    } else showAfterAuthentication();
                    if (legacyLocal) {
                        pendingLegacyServerSnapshot = snapshot;
                        pendingLegacyServerHasRows = hasLedgerRows(snapshot);
                        if (!mustChange) offerLegacyDataChoice(snapshot, pendingLegacyServerHasRows);
                    } else if (pending && !mustChange) syncLocalChanges();
                });
            } catch (Exception e) {
                boolean networkFailure = e instanceof java.net.SocketTimeoutException
                        || e instanceof java.net.ConnectException
                        || e instanceof java.net.UnknownHostException;
                String message = networkFailure
                        ? "تعذر الوصول إلى الخادم. تحقق من رابط الخادم واتصال الهاتف بالشبكة نفسها."
                        : e.getMessage() == null ? "تعذر الاتصال بالخادم." : e.getMessage();
                runOnUiThread(() -> showBrandedMessage(message));
            }
        });
    }

    private void loginStandalone(String username, String password, String staffName) {
        showBrandedMessage("جارٍ التحقق من الحساب المحلي…");
        network.execute(() -> {
            boolean valid;
            try { valid = "123".equals(username) && localSecurity.verifyLoginPassword(username, password, "123"); }
            catch (Exception e) {
                runOnUiThread(() -> showBrandedMessage("تعذر التحقق من الحساب المحلي. حاول مرة أخرى."));
                return;
            }
            if (!valid) {
                runOnUiThread(() -> showBrandedMessage("اسم المستخدم أو كلمة المرور غير صحيحة."));
                return;
            }
            try {
                JSONObject account = new JSONObject()
                        .put("id", "offline-123")
                        .put("username", username)
                        .put("name", storeName().isEmpty() ? "متجري" : storeName())
                        .put("status", "active")
                        .put("subscriptionMode", "permanent")
                        .put("permissions", new JSONObject());
                JSONObject response = new JSONObject()
                        .put("token", "offline-" + UUID.randomUUID())
                .put("expiresAt", Long.MAX_VALUE)
                        .put("deviceStaffName", savedOfflineOperator(staffName))
                        .put("account", account)
                        .put("forcePasswordChange", false);
                sessions.save(response);
                switchDatabaseForAccount("offline-123");
                sessions.setUnsyncedChanges(false);
                sessions.setSyncBlocked(false);
                getPreferences(0).edit().putString("store_name", account.optString("name", "متجري")).apply();
                runOnUiThread(this::showAfterAuthentication);
            } catch (Exception e) {
                runOnUiThread(() -> showBrandedMessage("تعذر فتح الحساب المحلي."));
            }
        });
    }

    private boolean hasLedgerRows(JSONObject snapshot) {
        if (snapshot == null) return false;
        JSONArray contacts = snapshot.optJSONArray("contacts"), debts = snapshot.optJSONArray("debts"), payments = snapshot.optJSONArray("payments");
        return (contacts != null && contacts.length() > 0) || (debts != null && debts.length() > 0) || (payments != null && payments.length() > 0);
    }

    private void offerLegacyDataChoice(JSONObject serverSnapshot, boolean serverHasRows) {
        if (serverSnapshot == null) return;
        String message = serverHasRows
                ? "وجدنا سجلات من النسخة المحلية القديمة وسجلات لهذا المتجر على الخادم. اختر أي نسخة تعتمد. رفع بيانات الجهاز سيستبدل سجل الخادم بعد حفظ نسخة احتياطية."
                : "وجدنا سجلات من النسخة المحلية القديمة. هل تريد رفعها إلى هذا المتجر؟ سيحفظ الخادم نسخة احتياطية قبل الرفع.";
        AlertDialog.Builder dialog = new SadadDialog.Builder(this).setTitle("سجلات موجودة على الجهاز").setMessage(message)
                .setNeutralButton("اعتماد سجلات الخادم", (d, w) -> applyServerSnapshot(serverSnapshot, true))
                .setNegativeButton("لاحقًا", (d, w) -> { sessions.setSyncBlocked(true); showBrandedMessage("احتفظنا بالسجلات المحلية دون مزامنتها."); });
        dialog.setPositiveButton(serverHasRows ? "استبدال سجل الخادم" : "رفع السجلات القديمة", (d, w) -> {
            sessions.setSyncBlocked(false); sessions.setUnsyncedChanges(true); syncLocalChanges();
        }).show();
        pendingLegacyServerSnapshot = null;
    }

    private String deviceName() {
        String manufacturer = Build.MANUFACTURER == null ? "Android" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "جهاز" : Build.MODEL.trim();
        return (manufacturer + " " + model).replaceAll("\\s+", " ").trim();
    }

    private static final class ApiFailure extends Exception {
        final int status;
        final JSONObject body;
        ApiFailure(int status, JSONObject body, String message) { super(message); this.status = status; this.body = body; }
    }

    private JSONObject apiRequest(String method, String endpoint, JSONObject body, String token) throws Exception {
        String base = sessions.apiBaseUrl().trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        HttpURLConnection connection = (HttpURLConnection) new URL(base + endpoint).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/json");
        if (token != null && !token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (stream != null) {
            try (InputStream input = stream) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            }
        }
        String raw = bytes.toString(StandardCharsets.UTF_8.name());
        JSONObject response;
        try { response = raw.isEmpty() ? new JSONObject() : new JSONObject(raw); }
        catch (JSONException e) { response = new JSONObject().put("message", "استجابة غير صالحة من الخادم."); }
        finally { connection.disconnect(); }
        if (code < 200 || code >= 300) throw new ApiFailure(code, response, response.optString("message", "تعذر إكمال الطلب."));
        return response;
    }

    void markLedgerChanged() {
        network.execute(() -> DueReminderManager.checkDueDates(getApplicationContext()));
        if (BuildConfig.STANDALONE_MODE) return;
        sessions.setUnsyncedChanges(true);
        SyncScheduler.schedule(this, true);
        syncLocalChanges();
    }

    private void registerConnectivitySync() {
        if (BuildConfig.STANDALONE_MODE || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        try {
            connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (connectivityManager == null) return;
            connectivityCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    if (!sessions.isActive() || sessions.forcePasswordChange()) return;
                    if (sessions.hasUnsyncedChanges() && !sessions.syncBlocked()) syncLocalChanges();
                    else checkServerUpdates();
                }
            };
            connectivityManager.registerDefaultNetworkCallback(connectivityCallback);
        } catch (Exception ignored) { connectivityCallback = null; }
    }

    void syncLocalChanges() {
        if (BuildConfig.STANDALONE_MODE || !sessions.isActive() || sessions.forcePasswordChange() || !sessions.hasUnsyncedChanges() || sessions.syncBlocked()) return;
        if (!syncing.compareAndSet(false, true)) { syncAgain = true; return; }
        network.execute(() -> {
            synchronized (SyncScheduler.LOCK) {
            final SadadDatabase targetDatabase = database;
            final String accountId = sessions.syncAccountId(), token = sessions.token();
            try {
                long change;
                JSONObject local;
                synchronized (targetDatabase) { change = targetDatabase.changeToken(); local = targetDatabase.getSnapshot(); }
                JSONObject body = new JSONObject().put("baseRevision", sessions.revision()).put("snapshot", local);
                JSONObject result = apiRequest("POST", "/mobile/sync", body, sessions.token());
                JSONObject snapshot = result.optJSONObject("snapshot");
                if (targetDatabase != database || !accountId.equals(sessions.syncAccountId()) || !token.equals(sessions.token())) return;
                synchronized (targetDatabase) {
                if (targetDatabase.changeToken() != change) {
                    sessions.setRevision(result.optLong("revision", snapshot == null ? sessions.revision() : snapshot.optLong("revision", sessions.revision())));
                    sessions.setUnsyncedChanges(true); syncAgain = true; return;
                }
                if (snapshot != null) {
                    targetDatabase.importSnapshot(snapshot);
                    DueReminderManager.checkDueDates(getApplicationContext());
                    JSONObject account = snapshot.optJSONObject("account"); if (account != null) sessions.setAccount(account);
                }
                sessions.setRevision(result.optLong("revision", snapshot == null ? sessions.revision() + 1 : snapshot.optLong("revision", sessions.revision() + 1)));
                sessions.setUnsyncedChanges(false); sessions.setSyncBlocked(false);
                }
            } catch (ApiFailure failure) {
                JSONObject latest = failure.body.optJSONObject("snapshot");
                if (latest != null) handleServerSnapshot(latest, "تغيّرت بيانات الحساب من جهاز آخر أو من الإدارة.");
                else if (failure.status == 401 || failure.status == 423) {
                    sessions.clear(); runOnUiThread(() -> { show("login"); showBrandedMessage(failure.getMessage()); });
                } else if (failure.status == 403) {
                    try { JSONObject response = apiRequest("GET", "/mobile/snapshot", null, sessions.token()); JSONObject snapshot = response.optJSONObject("snapshot"); if (snapshot != null) handleServerSnapshot(snapshot, failure.getMessage()); }
                    catch (Exception ignored) { runOnUiThread(() -> showBrandedMessage(failure.getMessage())); }
                } else runOnUiThread(() -> showBrandedMessage(failure.getMessage()));
            } catch (Exception error) {
                SyncScheduler.schedule(this, true);
            } finally {
                syncing.set(false);
                if (syncAgain) { syncAgain = false; syncLocalChanges(); }
            }
            }
        });
    }

    private void handleServerSnapshot(JSONObject snapshot, String message) {
        if (sessions.syncBlocked()) return;
        if (!sessions.hasUnsyncedChanges()) { runOnUiThread(() -> applyServerSnapshot(snapshot, true)); return; }
        runOnUiThread(() -> new SadadDialog.Builder(this)
                .setTitle("تعارض في السجلات")
                .setMessage(message + " سجلات هذا الجهاز لم تُرفع بعد. هل تريد تحميل نسخة الخادم الآن؟")
                .setPositiveButton("تحميل نسخة الخادم", (dialog, which) -> applyServerSnapshot(snapshot, true))
                .setNegativeButton("الاحتفاظ بنسخة الجهاز", (dialog, which) -> { sessions.setSyncBlocked(true); showBrandedMessage("احتفظنا بسجلات هذا الجهاز. صدّرها قبل تحميل نسخة الخادم لاحقًا."); })
                .setCancelable(false).show());
    }

    private void applyServerSnapshot(JSONObject snapshot, boolean announce) {
        try {
            database.importSnapshot(snapshot);
            network.execute(() -> DueReminderManager.checkDueDates(getApplicationContext()));
            sessions.setRevision(snapshot.optLong("revision", sessions.revision()));
            JSONObject account = snapshot.optJSONObject("account"); if (account != null) sessions.setAccount(account);
            sessions.setUnsyncedChanges(false); sessions.setSyncBlocked(false);
            if (announce) showBrandedMessage("تم تحميل أحدث السجلات من الخادم.");
            if (screens != null && !"contact_form".equals(currentRoute) && !"debt_form".equals(currentRoute) && !"payment_form".equals(currentRoute)) screens.show(currentRoute);
        } catch (Exception e) { showBrandedMessage("تعذر تحميل السجلات من الخادم."); }
    }

    void refreshServerData() {
        if (!sessions.isActive()) { showBrandedMessage("سجّل الدخول إلى الخادم أولًا."); return; }
        if (sessions.hasUnsyncedChanges()) {
            new SadadDialog.Builder(this).setTitle("تحميل سجلات الخادم")
                    .setMessage("تحميل السجلات من الخادم سيستبدل التعديلات المحلية غير المزامنة. هل تريد المتابعة؟")
                    .setNegativeButton("إلغاء", null).setPositiveButton("تحميل", (dialog, which) -> requestServerSnapshot()).show();
            return;
        }
        requestServerSnapshot();
    }

    void retryLocalSync() {
        if (!sessions.hasUnsyncedChanges()) { showBrandedMessage("لا توجد تغييرات محلية بانتظار المزامنة."); return; }
        sessions.setSyncBlocked(false);
        syncLocalChanges();
    }

    private void requestServerSnapshot() {
        if (BuildConfig.STANDALONE_MODE) { showBrandedMessage("هذه نسخة مستقلة ولا تتصل بخادم."); return; }
        network.execute(() -> {
            try {
                JSONObject response = apiRequest("GET", "/mobile/snapshot", null, sessions.token());
                JSONObject snapshot = response.optJSONObject("snapshot");
                if (snapshot != null) runOnUiThread(() -> applyServerSnapshot(snapshot, true));
            } catch (Exception e) { runOnUiThread(() -> showBrandedMessage(e.getMessage() == null ? "تعذر الاتصال بالخادم." : e.getMessage())); }
        });
    }

    private void checkServerUpdates() {
        if (BuildConfig.STANDALONE_MODE || sessions.token().isEmpty() || sessions.forcePasswordChange()) return;
        network.execute(() -> {
            try {
                JSONObject status = apiRequest("GET", "/mobile/session", null, sessions.token());
                JSONObject account = status.optJSONObject("account"); if (account != null) sessions.setAccount(account);
                sessions.setForcePasswordChange(status.optBoolean("forcePasswordChange", false));
                long serverRevision = status.optLong("revision", sessions.revision());
                if (serverRevision != sessions.revision() && !sessions.syncBlocked()) {
                    JSONObject response = apiRequest("GET", "/mobile/snapshot", null, sessions.token());
                    JSONObject snapshot = response.optJSONObject("snapshot");
                    if (snapshot != null) handleServerSnapshot(snapshot, "وجد الخادم نسخة أحدث من سجلات المتجر.");
                }
                if (sessions.forcePasswordChange()) runOnUiThread(() -> show("force_password"));
            } catch (ApiFailure failure) {
                if (failure.status == 401 || failure.status == 423) runOnUiThread(() -> { sessions.clear(); show("login"); showBrandedMessage(failure.getMessage()); });
            } catch (Exception ignored) { }
        });
    }

    boolean verifyAppPin(String pin) {
        if (!localSecurity.verifyPin(pin)) return false;
        showAfterAuthentication();
        return true;
    }

    boolean setAppPin(String pin) {
        try {
            localSecurity.setPin(pin);
            getPreferences(0).edit().putBoolean("lock_enabled", true).apply();
            showBrandedMessage("تم إعداد رمز قفل التطبيق.");
            screens.show(currentRoute);
            return true;
        } catch (Exception e) {
            showBrandedMessage(e.getMessage() == null ? "تعذر حفظ رمز القفل." : e.getMessage());
            return false;
        }
    }

    void setLockEnabled(boolean enabled) {
        getPreferences(0).edit().putBoolean("lock_enabled", enabled).apply();
        if (!enabled) getPreferences(0).edit().putBoolean("biometric_enabled", false).apply();
        screens.show(currentRoute);
    }

    void setTheme(boolean dark) {
        getPreferences(0).edit().putString("theme", dark ? "dark" : "light").apply();
        applyTheme(dark);
        screens.show(currentRoute);
    }

    void saveAndSharePdf(byte[] pdf, String suggestedName) { savePdf(pdf, suggestedName, true); }

    interface ReportGenerator { byte[] create() throws Exception; }
    private final java.util.concurrent.ExecutorService exports = java.util.concurrent.Executors.newSingleThreadExecutor();
    void generateReport(ReportGenerator generator, String name, String mime, boolean share) {
        exports.execute(() -> { try { byte[] bytes = generator.create(); runOnUiThread(() -> saveReport(bytes, name, mime, share)); }
            catch (Exception e) { runOnUiThread(() -> showBrandedMessage("تعذر إنشاء كشف السجلات.")); } });
    }
    private String pendingReportMime = "application/pdf";
    void savePdf(byte[] pdf, String suggestedName, boolean share) { saveReport(pdf, suggestedName, "application/pdf", share); }
    void saveReport(byte[] pdf, String suggestedName, String mime, boolean share) {
        if (share) { shareReport(pdf, suggestedName, mime); return; }
        pendingReportMime = mime;
        pendingPdfShare = share;
        ignoreNextResumeLock = true;
        if (pdf == null || pdf.length == 0) { showBrandedMessage("ملف الكشف فارغ."); return; }
        pendingPdf = pdf;
        pendingPdfName = suggestedName == null || suggestedName.trim().isEmpty() ? "Sadad-Statement.pdf" : suggestedName.trim();
        String extension = LedgerTableExcel.MIME.equals(mime) ? ".xlsx" : ".pdf";
        if (!pendingPdfName.toLowerCase(Locale.ROOT).endsWith(extension)) pendingPdfName += extension;
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType(mime);
        create.putExtra(Intent.EXTRA_TITLE, pendingPdfName);
        try { startActivityForResult(create, REQUEST_SAVE_PDF); }
        catch (Exception e) { pendingPdf = null; pendingPdfName = null; showBrandedMessage("تعذر فتح شاشة حفظ ملف PDF."); }
    }

    private void sharePdf(byte[] pdf, String name) { shareReport(pdf, name, "application/pdf"); }
    private void shareReport(byte[] pdf, String name, String mime) {
        if (pdf == null || pdf.length == 0) { showBrandedMessage("ملف الكشف فارغ."); return; }
        try {
            java.io.File directory = new java.io.File(getCacheDir(), "exports"); if (!directory.exists() && !directory.mkdirs()) throw new java.io.IOException();
            String filename = name == null ? "كشف-سدد.pdf" : name.replaceAll("[\\\\/\\x00-\\x1F]", "-");
            String extension = LedgerTableExcel.MIME.equals(mime) ? ".xlsx" : ".pdf";
            if (!filename.toLowerCase(Locale.ROOT).endsWith(extension)) filename += extension;
            java.io.File file = new java.io.File(directory, filename);
            try (OutputStream out = new FileOutputStream(file)) { out.write(pdf); }
            Uri uri = new Uri.Builder().scheme("content").authority(getPackageName() + ".pdf").appendPath(filename).build();
            Intent send = new Intent(Intent.ACTION_SEND); send.setType(mime); send.putExtra(Intent.EXTRA_STREAM, uri); send.putExtra(Intent.EXTRA_SUBJECT, filename);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); send.setClipData(ClipData.newUri(getContentResolver(), "كشف حساب PDF", uri)); ignoreNextResumeLock = true;
            startActivity(Intent.createChooser(send, "مشاركة كشف السجلات"));
        } catch (Exception error) { showBrandedMessage("تعذر مشاركة الكشف."); }
    }

    void printPdf(byte[] pdf, String jobName) {
        if (pdf == null || pdf.length == 0) { showBrandedMessage("ملف الطباعة فارغ."); return; }
        try {
            PrintManager printer = (PrintManager) getSystemService(PRINT_SERVICE);
            if (printer == null) { showBrandedMessage("خدمة الطباعة غير متوفرة على هذا الجهاز."); return; }
            final String safeName = jobName == null || jobName.trim().isEmpty() ? "سند سدد" : jobName.trim();
            final byte[] content = pdf.clone();
            PrintDocumentAdapter adapter = new PrintDocumentAdapter() {
                @Override public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                                               CancellationSignal cancellationSignal, LayoutResultCallback callback,
                                               Bundle extras) {
                    if (cancellationSignal.isCanceled()) { callback.onLayoutCancelled(); return; }
                    PrintDocumentInfo info = new PrintDocumentInfo.Builder(safeName + ".pdf")
                            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build();
                    callback.onLayoutFinished(info, !newAttributes.equals(oldAttributes));
                }
                @Override public void onWrite(PageRange[] pages, android.os.ParcelFileDescriptor destination,
                                              CancellationSignal cancellationSignal, WriteResultCallback callback) {
                    try (FileOutputStream output = new FileOutputStream(destination.getFileDescriptor())) {
                        if (cancellationSignal.isCanceled()) { callback.onWriteCancelled(); return; }
                        output.write(content); output.flush();
                        callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
                    } catch (Exception error) { callback.onWriteFailed(error.getMessage()); }
                }
            };
            ignoreNextResumeLock = true;
            printer.print(safeName, adapter, new PrintAttributes.Builder().build());
        } catch (Exception e) { showBrandedMessage("تعذر فتح شاشة الطباعة. تأكد من إعداد خدمة طباعة على الجهاز."); }
    }

    android.graphics.drawable.Drawable profilePhoto(boolean store) {
        java.io.File file = new java.io.File(getFilesDir(), profileKey("account_photo", sessions.syncAccountId()) + ".jpg");
        if (!file.isFile()) file = new java.io.File(getFilesDir(), profileKey("store_photo", sessions.syncAccountId()) + ".jpg");
        return file.isFile() ? android.graphics.drawable.Drawable.createFromPath(file.getAbsolutePath()) : null;
    }

    void pickProfilePhoto(boolean store) {
        pendingStorePhoto = store; pendingPhotoAccount = sessions.syncAccountId();
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT); pick.addCategory(Intent.CATEGORY_OPENABLE); pick.setType("image/*");
        ignoreNextResumeLock = true;
        try { startActivityForResult(pick, REQUEST_PROFILE_PHOTO); } catch (Exception e) { showBrandedMessage("تعذر فتح اختيار الصور."); }
    }

    void startVoiceDraft(EditText target) {
        if (target == null) return;
        Intent listen = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        listen.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        listen.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar");
        listen.putExtra(RecognizerIntent.EXTRA_PROMPT, "أمْلِ تفاصيل الحركة لمراجعتها");
        listen.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        listen.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        pendingVoiceDraftTarget = target;
        try {
            if (listen.resolveActivity(getPackageManager()) == null) throw new android.content.ActivityNotFoundException("speech recognition unavailable");
            ignoreNextResumeLock = true;
            leftAt = 0L;
            startActivityForResult(listen, REQUEST_VOICE_DRAFT);
        }
        catch (Exception unavailable) {
            pendingVoiceDraftTarget = null; ignoreNextResumeLock = false;
            showBrandedMessage("الإملاء الصوتي غير متاح على هذا الجهاز. تحقق من وجود خدمة التعرف على الصوت.");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PROFILE_PHOTO) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            try {
                android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options(); opts.inJustDecodeBounds = true;
                try (InputStream in = getContentResolver().openInputStream(data.getData())) { android.graphics.BitmapFactory.decodeStream(in, null, opts); }
                opts.inSampleSize = 1; while (Math.max(opts.outWidth, opts.outHeight) / opts.inSampleSize > 1024) opts.inSampleSize *= 2; opts.inJustDecodeBounds = false;
                android.graphics.Bitmap photo; try (InputStream in = getContentResolver().openInputStream(data.getData())) { photo = android.graphics.BitmapFactory.decodeStream(in, null, opts); }
                if (photo == null) throw new java.io.IOException("الصورة غير صالحة");
                int orientation = android.media.ExifInterface.ORIENTATION_NORMAL;
                try (InputStream in = getContentResolver().openInputStream(data.getData())) { orientation = new android.media.ExifInterface(in).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL); } catch (Exception ignored) { }
                android.graphics.Matrix matrix = new android.graphics.Matrix();
                switch (orientation) { case 2: matrix.setScale(-1, 1); break; case 3: matrix.setRotate(180); break; case 4: matrix.setScale(1, -1); break; case 5: matrix.setRotate(90); matrix.postScale(-1, 1); break; case 6: matrix.setRotate(90); break; case 7: matrix.setRotate(-90); matrix.postScale(-1, 1); break; case 8: matrix.setRotate(-90); break; }
                if (!matrix.isIdentity()) { android.graphics.Bitmap oriented = android.graphics.Bitmap.createBitmap(photo, 0, 0, photo.getWidth(), photo.getHeight(), matrix, true); if (oriented != photo) photo.recycle(); photo = oriented; }
                ProfilePhotoEditor.show(this, photo, pendingPhotoAccount); photo.recycle();
            } catch (Exception e) { showBrandedMessage("تعذر حفظ الصورة. اختر صورة أخرى."); }
        } else if (requestCode == REQUEST_VOICE_DRAFT) {
            EditText target = pendingVoiceDraftTarget; pendingVoiceDraftTarget = null;
            java.util.ArrayList<String> results = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (resultCode == RESULT_OK && target != null && target.isAttachedToWindow() && results != null && !results.isEmpty()) {
                String recognized = results.get(0);
                if (recognized != null && !recognized.trim().isEmpty()) {
                    try {
                        String previous = target.getText().toString().trim();
                        target.setText(previous.isEmpty() ? recognized.trim() : previous + " " + recognized.trim());
                        target.setSelection(target.getText().length());
                        showBrandedMessage("أضيف الإملاء كمسودة. راجعه قبل الحفظ.");
                    } catch (Exception detachedView) { showBrandedMessage("عاد الإملاء لكن الشاشة تغيّرت؛ افتح الحقل وحاول مرة أخرى."); }
                }
            }
        } else if (requestCode == REQUEST_SAVE_PDF) {
            byte[] contents = pendingPdf;
            pendingPdf = null;
            String name = pendingPdfName;
            pendingPdfName = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || contents == null) return;
            Uri uri = data.getData();
            try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                if (output == null) throw new java.io.IOException("لم يتم فتح ملف الحفظ.");
                output.write(contents);
                output.flush();
                if (!pendingPdfShare) { showBrandedMessage("تم حفظ كشف السجلات."); return; }
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(pendingReportMime);
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.putExtra(Intent.EXTRA_SUBJECT, name == null ? "كشف حساب سدد" : name);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                send.setClipData(ClipData.newUri(getContentResolver(), "كشف حساب PDF", uri));
                startActivity(Intent.createChooser(send, "مشاركة كشف PDF"));
                showBrandedMessage("تم حفظ ملف PDF. اختر التطبيق الذي تريد المشاركة من خلاله.");
            } catch (Exception e) { showBrandedMessage("تعذر حفظ أو مشاركة PDF. اختر مجلداً آخر وحاول مجدداً."); }
        }
    }

    void logout() {
        String token = sessions.token();
        String accountId = sessions.syncAccountId();
        if (!BuildConfig.STANDALONE_MODE && !token.isEmpty()) network.execute(() -> { try { apiRequest("POST", "/mobile/logout", new JSONObject(), token); } catch (Exception ignored) { } });
        if (!accountId.isEmpty()) sessions.removeSavedAccount(accountId);
        sessions.clear();
        localSecurity.clearPin();
        getPreferences(0).edit().remove("biometric_enabled").remove("lock_enabled").putBoolean(PREF_FORCED_PASSWORD_LOGIN, false).apply();
        show("login");
    }

    private String savedOfflineOperator(String requested) {
        String key = profileKey("first_operator", "offline-123"); String saved = getPreferences(0).getString(key, "").trim();
        if (saved.isEmpty() && requested != null && !requested.trim().isEmpty()) { saved = requested.trim(); getPreferences(0).edit().putString(key, saved).apply(); }
        return saved.isEmpty() ? "صاحب المتجر" : saved;
    }

    void editProfilePhoto() {
        android.graphics.drawable.Drawable image = profilePhoto(false);
        if (!(image instanceof android.graphics.drawable.BitmapDrawable)) { pickProfilePhoto(false); return; }
        ProfilePhotoEditor.show(this, ((android.graphics.drawable.BitmapDrawable) image).getBitmap(), sessions.syncAccountId());
    }
    void saveProfilePhoto(android.graphics.Bitmap image, String account) {
        if (!account.equals(sessions.syncAccountId())) { showBrandedMessage("تغيّر الحساب؛ افتح الصورة مجددًا."); return; }
        try (OutputStream out = new FileOutputStream(new java.io.File(getFilesDir(), profileKey("account_photo", account) + ".jpg"))) {
            if (!image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)) throw new java.io.IOException(); screens.show(currentRoute);
        } catch (Exception error) { showBrandedMessage("تعذر حفظ الصورة."); }
    }

    String currentOperatorName() {
        String staff = sessions == null ? "" : sessions.deviceStaffName().trim();
        if (!staff.isEmpty()) return staff;
        String owner = ownerName(); return owner.isEmpty() ? "صاحب المتجر" : owner;
    }

    boolean devicePermissionAllowed(String permission) { return sessions == null || sessions.devicePermissionAllowed(permission); }

    void showStoreActivityLog() {
        if (BuildConfig.STANDALONE_MODE) { showBrandedMessage("سجل المستخدمين متاح في النسخة المتصلة فقط."); return; }
        network.execute(() -> {
            try {
                JSONArray events = apiRequest("GET", "/mobile/activity", null, sessions.token()).optJSONArray("events");
                JSONArray rows = events == null ? new JSONArray() : events;
                String[] labels = new String[rows.length()];
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject event = rows.optJSONObject(i);
                    labels[i] = (event == null ? "" : event.optString("actor", "صاحب المتجر") + " · " + event.optString("action", "حركة") + "\n"
                            + event.optString("description", "") + "\n" + DateFormat.getDateFormat(this).format(new Date(event.optLong("createdAt")))
                            + " · " + DateFormat.getTimeFormat(this).format(new Date(event.optLong("createdAt"))));
                }
                runOnUiThread(() -> {
                    AlertDialog.Builder dialog = new SadadDialog.Builder(this).setTitle("سجل نشاط المستخدمين");
                    if (labels.length == 0) dialog.setMessage("لا توجد عمليات مسجلة بعد.");
                    else dialog.setItems(labels, (d, which) -> { });
                    dialog.setPositiveButton("إغلاق", null).show();
                });
            } catch (Exception e) {
                String message=e.getMessage()==null?"تعذر تحميل سجل النشاط.":e.getMessage();
                runOnUiThread(() -> showBrandedMessage(message));
            }
        });
    }

    void addStoreAccount() {
        if (sessions.savedAccounts().length() >= 3) { showBrandedMessage("وصلت إلى الحد الأقصى: ثلاثة حسابات متاجر محفوظة."); return; }
        if (sessions.hasUnsyncedChanges() || sessions.syncBlocked()) { showBrandedMessage("زامن سجلات الحساب الحالي قبل إضافة حساب آخر."); return; }
        show("login");
    }

    void removeSavedStoreAccount(String accountId) {
        if (accountId == null || accountId.isEmpty() || accountId.equals(sessions.syncAccountId())) {
            showBrandedMessage("لا يمكن إزالة الحساب المفتوح من هنا. استخدم تسجيل الخروج لإزالته."); return;
        }
        sessions.removeSavedAccount(accountId);
        screens.show("settings");
        showBrandedMessage("تمت إزالة الحساب من هذا الجهاز.");
    }

    void switchStoreAccount(String accountId) {
        if (accountId == null || accountId.isEmpty() || accountId.equals(sessions.syncAccountId())) return;
        if (sessions.hasUnsyncedChanges() || sessions.syncBlocked()) { showBrandedMessage("زامن سجلات الحساب الحالي قبل التبديل."); return; }
        String previousId = sessions.syncAccountId();
        if (!sessions.activateSavedAccount(accountId)) { showBrandedMessage("تعذر فتح الحساب المحفوظ. سجّل الدخول إليه من جديد."); return; }
        switchDatabaseForAccount(accountId);
        showBrandedMessage("جارٍ تحميل سجلات المتجر…");
        network.execute(() -> {
            try {
                JSONObject result = apiRequest("GET", "/mobile/snapshot", null, sessions.token());
                JSONObject snapshot = result.optJSONObject("snapshot");
                if (snapshot == null) throw new IllegalStateException("لم تصل سجلات الحساب من الخادم.");
                database.importSnapshot(snapshot); sessions.setRevision(snapshot.optLong("revision", sessions.revision()));
                JSONObject nextAccount=snapshot.optJSONObject("account"); sessions.setAccount(nextAccount);
                if(nextAccount!=null){String key=profileKey("store_name",accountId);if(getPreferences(0).getString(key,"").trim().isEmpty())getPreferences(0).edit().putString(key,nextAccount.optString("name","")).apply();}
                runOnUiThread(() -> {
                    if (sessions.forcePasswordChange()) show("force_password");
                    else if (localSecurity.hasPin() && getPreferences(0).getBoolean("lock_enabled", true)) show("unlock");
                    else show("home");
                    showBrandedMessage("تم التبديل إلى " + sessions.account().optString("name", "المتجر") + ".");
                });
            } catch (Exception e) {
                sessions.activateSavedAccount(previousId); switchDatabaseForAccount(previousId);
                String message = e.getMessage() == null ? "تعذر التبديل إلى الحساب." : e.getMessage();
                runOnUiThread(() -> showBrandedMessage(message));
            }
        });
    }

    private void switchDatabaseForAccount(String accountId) {
        String name = sessions.databaseNameForAccount(accountId);
        if (database != null && name.equals(database.getDatabaseName())) return;
        if (database != null) database.close();
        database = new SadadDatabase(this, name);
    }

    private void applyTheme(boolean dark) {
        int color = dark ? Color.rgb(16, 39, 33) : Color.rgb(245, 248, 243);
        getWindow().setStatusBarColor(color);
        getWindow().setNavigationBarColor(color);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarDividerColor(color);
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().getDecorView().setForceDarkAllowed(false);
        }
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    void showBrandedMessage(String message) {
        runOnUiThread(() -> {
            try {
                LinearLayout box = new LinearLayout(this);
                box.setGravity(Gravity.CENTER_VERTICAL);
                box.setPadding(dp(12), dp(9), dp(16), dp(9));
                box.setBackground(round(Color.rgb(0, 75, 65), dp(18)));
                ImageView icon = new ImageView(this);
                icon.setImageResource(brandLogoResource());
                icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                box.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));
                TextView label = new TextView(this);
                label.setText(message == null ? "" : message);
                label.setTextColor(Color.WHITE);
                label.setTextSize(14);
                label.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
                label.setPadding(dp(10), 0, 0, 0);
                box.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
                Toast toast = Toast.makeText(this, "", Toast.LENGTH_LONG);
                toast.setView(box);
                toast.show();
            } catch (Exception ignored) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
        });
    }

    void showUndoBanner(String message, Runnable undoAction, Runnable commitAction) {
        runOnUiThread(() -> {
            View decorView = getWindow().getDecorView();
            if (!(decorView instanceof FrameLayout)) {
                if (commitAction != null) undoHandler.postDelayed(commitAction, 5000L);
                return;
            }
            FrameLayout decor = (FrameLayout) decorView;
            LinearLayout banner = new LinearLayout(this);
            banner.setGravity(Gravity.CENTER_VERTICAL);
            banner.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            banner.setPadding(dp(16), dp(10), dp(12), dp(10));
            banner.setBackground(round(Color.rgb(6, 75, 66), dp(16)));
            banner.setElevation(dp(10));

            TextView action = new TextView(this);
            action.setText("تراجع"); action.setTextColor(Color.rgb(172, 241, 222));
            action.setTextSize(14); action.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            action.setGravity(Gravity.CENTER); action.setPadding(dp(12), dp(6), dp(12), dp(6));
            banner.addView(action, new LinearLayout.LayoutParams(-2, -2));

            TextView text = new TextView(this);
            text.setText(message == null ? "" : message); text.setTextColor(Color.WHITE);
            text.setTextSize(14); text.setMaxLines(2); text.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
            text.setPadding(dp(10), 0, dp(4), 0);
            banner.addView(text, new LinearLayout.LayoutParams(0, -2, 1));

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            int stack = undoBannerCount++;
            params.setMargins(dp(16), 0, dp(16), dp(82 + stack * 64));
            decor.addView(banner, params);
            boolean[] finished = {false};
            Runnable[] expire = new Runnable[1];
            Runnable[] ticker = new Runnable[1];
            Runnable remove = () -> {
                if (ticker[0] != null) undoHandler.removeCallbacks(ticker[0]);
                try { if (banner.getParent() == decor) decor.removeView(banner); } catch (Exception ignored) { }
                undoBannerCount = Math.max(0, undoBannerCount - 1);
            };
            action.setOnClickListener(v -> {
                if (finished[0]) return;
                finished[0] = true; undoHandler.removeCallbacks(expire[0]); remove.run();
                if (undoAction != null) undoAction.run();
            });
            long deadline = SystemClock.uptimeMillis() + 5000L;
            if (commitAction != null) ticker[0] = new Runnable() {
                @Override public void run() {
                    if (finished[0]) return;
                    long remaining = Math.max(0L, deadline - SystemClock.uptimeMillis());
                    int seconds = Math.max(1, (int) ((remaining + 999L) / 1000L));
                    text.setText((message == null || message.trim().isEmpty() ? "العنصر" : message) +
                            " · سيتم الحذف نهائياً بعد " + seconds + " ثوانٍ");
                    if (remaining > 0L) undoHandler.postDelayed(this, 1000L);
                }
            };
            expire[0] = () -> {
                if (finished[0]) return;
                finished[0] = true; remove.run();
                if (commitAction != null) commitAction.run();
            };
            undoHandler.postDelayed(expire[0], 5000L);
            if (ticker[0] != null) undoHandler.post(ticker[0]);
        });
    }

    private static GradientDrawable round(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(radius); return shape;
    }
    int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    void requestBiometric(boolean enabling) {
        if (enabling && !localSecurity.hasPin()) {
            showBrandedMessage("أنشئ رمز قفل من الإعدادات أولاً لاستخدام البصمة أو الوجه.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                biometricPromptInProgress = true;
                android.hardware.biometrics.BiometricPrompt prompt = new android.hardware.biometrics.BiometricPrompt.Builder(this)
                        .setTitle("فتح تطبيق سدد").setSubtitle("تحقق بالبصمة أو الوجه حسب المتاح على جهازك")
                        .setNegativeButton("استخدام رمز التطبيق", getMainExecutor(), (dialog, which) -> onBiometricResult(false, enabling)).build();
                prompt.authenticate(new CancellationSignal(), getMainExecutor(), new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult result) { onBiometricResult(true, enabling); }
                    @Override public void onAuthenticationError(int code, CharSequence message) { onBiometricResult(false, enabling); }
                });
            } catch (Exception e) { onBiometricResult(false, enabling); }
            return;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                FingerprintManager manager = (FingerprintManager) getSystemService(FINGERPRINT_SERVICE);
                if (manager == null || !manager.isHardwareDetected() || !manager.hasEnrolledFingerprints()) {
                    onBiometricResult(false, enabling); return;
                }
                biometricPromptInProgress = true;
                manager.authenticate(null, new CancellationSignal(), 0, new FingerprintManager.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(FingerprintManager.AuthenticationResult result) { onBiometricResult(true, enabling); }
                    @Override public void onAuthenticationFailed() { showBrandedMessage("لم يتم التحقق من البصمة. حاول مرة أخرى."); }
                    @Override public void onAuthenticationError(int errorCode, CharSequence errString) { onBiometricResult(false, enabling); }
                }, new Handler(Looper.getMainLooper()));
            } catch (Exception e) { onBiometricResult(false, enabling); }
        } else onBiometricResult(false, enabling);
    }

    private void onBiometricResult(boolean success, boolean enabling) {
        biometricPromptInProgress = false;
        runOnUiThread(() -> {
            if (enabling) {
                if (success) {
                    getPreferences(0).edit().putBoolean("biometric_enabled", true).putBoolean("lock_enabled", true).apply();
                    showBrandedMessage("تم تفعيل التحقق الحيوي.");
                } else showBrandedMessage("لم يكتمل التحقق. تأكد من إعداد بصمة أو وجه في الجهاز.");
                screens.show(currentRoute);
            } else if (success) showAfterAuthentication();
        });
    }

    @Override protected void onPause() {
        statusHandler.removeCallbacks(statusPoll);
        if (!biometricPromptInProgress && !ignoreNextResumeLock) leftAt = System.currentTimeMillis();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        SyncScheduler.schedule(this, sessions != null && sessions.hasUnsyncedChanges());
        boolean skipLockForSpeech = ignoreNextResumeLock; ignoreNextResumeLock = false;
        if (!skipLockForSpeech && !biometricPromptInProgress && leftAt > 0 && sessions.isActive() && System.currentTimeMillis() - leftAt > 1200
                && localSecurity.hasPin() && getPreferences(0).getBoolean("lock_enabled", true)) {
            show("unlock");
            if (getPreferences(0).getBoolean("biometric_enabled", false)) requestBiometric(false);
        } else if (!sessions.isActive() && !"login".equals(currentRoute) && !"welcome".equals(currentRoute)
                && !"customer_login".equals(currentRoute) && !("customer_portal".equals(currentRoute) && demoCustomerSession)) show("login");
        leftAt = 0;
        if (!BuildConfig.STANDALONE_MODE && sessions.isActive() && !"force_password".equals(currentRoute)) {
            if (sessions.hasUnsyncedChanges() && !sessions.syncBlocked()) syncLocalChanges();
            else checkServerUpdates();
            statusHandler.removeCallbacks(statusPoll);
            statusHandler.postDelayed(statusPoll, 3_600_000L);
        }
    }

    @SuppressWarnings("deprecation") @Override public void onBackPressed() {
        String route = currentRoute;
        if ("customer_portal".equals(route)) { exitDemoCustomer(); return; }
        if ("customer_login".equals(route)) { show("login"); return; }
        if ("force_password".equals(route)) { showBrandedMessage("غيّر كلمة المرور المؤقتة أولًا للمتابعة."); return; }
        if ("welcome".equals(route)) { finishWelcome(); return; }
        if ("contact_detail".equals(route)) { show("contacts"); return; }
        if ("contact_form".equals(route)) { show(contactFormBackTarget()); return; }
        if ("debt_form".equals(route) || "payment_form".equals(route)) { show("contact_detail"); return; }
        if ("trash".equals(route)) { show("settings"); return; }
        if ("notifications".equals(route)) { show("home"); return; }
        if ("history_results".equals(route) || "reports".equals(route)) { show("history"); return; }
        if ("contacts".equals(route) || "history".equals(route) || "reports".equals(route) || "settings".equals(route)) { show("home"); return; }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (activeInstance.get() == this) activeInstance = EMPTY_INSTANCE;
        exports.shutdown();

        if (connectivityManager != null && connectivityCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(connectivityCallback); } catch (Exception ignored) { }
        }
        network.shutdown(); if (database != null) database.close(); super.onDestroy();
    }
}
