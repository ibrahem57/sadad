package com.sadad.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.fingerprint.FingerprintManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.JsPromptResult;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private static final String START_URL = "file:///android_asset/screens/";
    private static final String PREFS = "sadad.device.preferences";
    private WebView webView;
    private SadadDatabase database;
    private SessionStore sessions;
    private LocalSecurity localSecurity;
    private final ExecutorService syncQueue = Executors.newSingleThreadExecutor();
    private volatile String currentRoute = "login";
    private volatile boolean biometricPromptInProgress = false;
    private volatile boolean appLocked = false;
    private long leftAt = 0L;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        database = new SadadDatabase(this);
        sessions = new SessionStore(this);
        localSecurity = new LocalSecurity(this);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(239, 252, 247));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setHapticFeedbackEnabled(false);
        webView.setLongClickable(false);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setDefaultTextEncodingName("UTF-8");
        settings.setTextZoom(100);
        webView.addJavascriptInterface(new AppBridge(), "Sadad");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsPrompt(WebView view,String url,String message,String defaultValue,JsPromptResult result) {
                EditText input=new EditText(MainActivity.this); input.setText(defaultValue==null?"":defaultValue); input.setSingleLine(false);
                new AlertDialog.Builder(MainActivity.this).setTitle("ملاحظة لسدد").setMessage(message).setView(input)
                        .setPositiveButton("متابعة المشاركة",(dialog,which)->result.confirm(input.getText().toString()))
                        .setNegativeButton("إلغاء",(dialog,which)->result.cancel()).setOnCancelListener(dialog->result.cancel()).show();
                return true;
            }
        });
        webView.setWebViewClient(new LocalPageClient());
        setContentView(webView);
        applyTheme(isDarkTheme());
        if (state != null) webView.restoreState(state);
        openInitialRoute();
    }

    private void openInitialRoute() {
        if (!sessions.isActive()) { sessions.clear(); navigateInternal("login"); return; }
        if (sessions.forcePasswordChange()) { navigateInternal("change_password"); return; }
        if (localSecurity.hasPin() && getPreferences(0).getBoolean("lock_enabled", true)) {
            appLocked = true;
            if (getPreferences(0).getBoolean("biometric_enabled", false)) requestBiometric(false);
            else navigateInternal("unlock");
            return;
        }
        navigateInternal("home");
    }

    @Override protected void onResume() {
        super.onResume();
        if (!biometricPromptInProgress && leftAt > 0L && sessions.isActive() && !sessions.forcePasswordChange()) {
            boolean pinLock = localSecurity.hasPin() && getPreferences(0).getBoolean("lock_enabled", true);
            boolean biometric = getPreferences(0).getBoolean("biometric_enabled", false);
            if (pinLock) appLocked = true;
            if (pinLock && biometric) requestBiometric(false);
            else if (pinLock && System.currentTimeMillis() - leftAt > 1200) navigateInternal("unlock");
        } else if (!biometricPromptInProgress && !sessions.isActive() && !"login".equals(currentRoute)) {
            sessions.clear(); navigateInternal("login");
        }
        leftAt = 0L;
    }

    @Override protected void onPause() {
        if (!biometricPromptInProgress) leftAt = System.currentTimeMillis();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle out) { if (webView != null) webView.saveState(out); super.onSaveInstanceState(out); }

    @SuppressWarnings("deprecation") @Override public void onBackPressed() {
        if ("login".equals(currentRoute) || "unlock".equals(currentRoute) || "change_password".equals(currentRoute)) { super.onBackPressed(); return; }
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        syncQueue.shutdown();
        if (webView != null) { webView.removeJavascriptInterface("Sadad"); webView.stopLoading(); webView.destroy(); webView = null; }
        if (database != null) database.close();
        super.onDestroy();
    }

    private boolean isDarkTheme() { return "dark".equals(getPreferences(0).getString("theme", "light")); }
    private void applyTheme(boolean dark) {
        int surface = dark ? Color.rgb(14, 23, 21) : Color.rgb(239, 252, 247);
        getWindow().setStatusBarColor(surface); getWindow().setNavigationBarColor(surface);
        int flags = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        if (webView != null) webView.setBackgroundColor(surface);
    }

    private void navigateInternal(String route) {
        route = guardedRoute(route);
        Map<String,String> pages = routes(); String page = pages.get(route);
        if (page == null || webView == null) return;
        currentRoute = route;
        runOnUiThread(() -> { if (webView != null) webView.loadUrl(START_URL + page); });
    }

    private String guardedRoute(String route) {
        if ("login".equals(route)) return route;
        if (!sessions.isActive()) return "login";
        if (sessions.forcePasswordChange()) return "change_password";
        if (appLocked) return "unlock";
        return route;
    }

    private void requireLocalAccess() throws IOException {
        if (!sessions.isActive() || sessions.forcePasswordChange() || appLocked) {
            navigateInternal("home");
            throw new IOException("سجّل الدخول وافتح قفل التطبيق للمتابعة.");
        }
    }

    private synchronized void refreshAuth() throws Exception {
        if (!sessions.isActive()) { sessions.clear(); navigateInternal("login"); throw new IOException("سجّل الدخول من جديد."); }
        JSONObject auth = sessions.auth();
        if (auth.optLong("expiresAt") > System.currentTimeMillis() + 60000L) return;
        JSONObject refreshed = requestApi("POST", "mobile/auth/refresh", new JSONObject().put("refreshToken", auth.getString("refreshToken")), false);
        sessions.updateAuth(refreshed.getJSONObject("auth"));
    }

    private Map<String,String> routes() {
        Map<String,String> map=new HashMap<>();
        map.put("home","home.html"); map.put("contacts","contacts.html"); map.put("contacts_empty","contacts_empty.html");
        map.put("contact","contact.html"); map.put("contact_empty","contact_empty.html"); map.put("contact_add","contact_add.html");
        map.put("debt_add","debt_add.html"); map.put("payment","payment.html"); map.put("history","history.html");
        map.put("debt","debt.html"); map.put("debt_edit","debt_edit.html"); map.put("settings","settings.html");
        map.put("login","login.html"); map.put("change_password","change_password.html"); map.put("unlock","unlock.html");
        map.put("whatsapp","whatsapp.html"); map.put("reports","reports.html");
        return Collections.unmodifiableMap(map);
    }

    private JSONObject requestApi(String method, String path, JSONObject body, boolean authenticated) throws Exception {
        if (authenticated) refreshAuth();
        String base = sessions.apiBaseUrl().trim().replaceAll("/+$", "");
        URL url = new URL(base + "/" + path.replaceAll("^/+", ""));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method); connection.setConnectTimeout(7000); connection.setReadTimeout(10000);
        connection.setUseCaches(false); connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        String publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY.trim();
        if (!publishableKey.isEmpty()) connection.setRequestProperty("apikey", publishableKey);
        if (authenticated) {
            String bearer = sessions.auth().optString("accessToken"); if (bearer.isEmpty()) throw new IOException("سجّل الدخول للمتابعة.");
            connection.setRequestProperty("Authorization", "Bearer " + bearer);
            connection.setRequestProperty("X-Sadad-Session", sessions.token());
        }
        if ("mobile/auth/refresh".equals(path)) connection.setRequestProperty("X-Sadad-Session", sessions.token());
        if (body != null) {
            connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        }
        int code = connection.getResponseCode(); InputStream stream = code >= 200 && code < 400 ? connection.getInputStream() : connection.getErrorStream();
        String response;
        try (InputStream in = stream; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (in != null) { byte[] buffer = new byte[4096]; int count; while ((count = in.read(buffer)) != -1) bytes.write(buffer,0,count); }
            response = bytes.toString("UTF-8");
        } finally { connection.disconnect(); }
        JSONObject json = response.isEmpty() ? new JSONObject() : new JSONObject(response);
        if (code == 401 && (authenticated || "mobile/auth/refresh".equals(path))) { sessions.clear(); appLocked = false; navigateInternal("login"); }
        if (code == 403 && json.optBoolean("forcePasswordChange")) { sessions.setForcePasswordChange(true); navigateInternal("change_password"); }
        if (code < 200 || code >= 300) throw new IOException(json.optString("message", "تعذر الاتصال بالخادم (" + code + ")."));
        return json;
    }

    private JSONObject apiError(String message) {
        try { return new JSONObject().put("ok", false).put("message", message == null ? "تعذر تنفيذ الطلب." : message); }
        catch (JSONException e) { return new JSONObject(); }
    }

    private void finishLogin(JSONObject response) throws Exception {
        String owner = response.getJSONObject("account").getString("id");
        if (database.hasContacts() && !owner.equals(sessions.ledgerOwner())) throw new IOException("توجد بيانات محلية لحساب آخر أو غير مرتبط. يلزم ترحيلها قبل تسجيل الدخول لهذا المتجر.");
        sessions.save(response);
        if (sessions.forcePasswordChange()) { navigateInternal("change_password"); return; }
        JSONObject remote = response.optJSONObject("snapshot");
        if (remote == null) remote = requestApi("GET", "mobile/snapshot", null, true).optJSONObject("snapshot");
        if (remote != null && remote.optJSONArray("contacts") != null && remote.optJSONArray("contacts").length() > 0) {
            database.importSnapshot(remote);
            if (remote.optJSONObject("account") != null) sessions.setAccount(remote.optJSONObject("account"));
            sessions.setRevision(remote.optLong("revision", 0L));
        } else if (database.hasContacts()) {
            JSONObject envelope=new JSONObject().put("baseRevision",sessions.revision()).put("snapshot",database.getSnapshot());
            JSONObject result=requestApi("POST","mobile/sync",envelope,true);
            sessions.setRevision(result.optLong("revision",0L));
        }
        if (localSecurity.hasPin() && getPreferences(0).getBoolean("lock_enabled", true)) {
            appLocked = true;
            if (getPreferences(0).getBoolean("biometric_enabled", false)) requestBiometric(false); else navigateInternal("unlock");
        } else navigateInternal("home");
    }

    private void scheduleSync() {
        if (!sessions.isActive() || sessions.forcePasswordChange()) return;
        syncQueue.execute(() -> {
            try {
                JSONObject payload=new JSONObject().put("baseRevision",sessions.revision()).put("snapshot",database.getSnapshot());
                JSONObject response=requestApi("POST","mobile/sync",payload,true);
                sessions.setRevision(response.optLong("revision",sessions.revision()));
                JSONObject snapshot=response.optJSONObject("snapshot"); if(snapshot!=null&&snapshot.optJSONObject("account")!=null)sessions.setAccount(snapshot.optJSONObject("account"));
            } catch (Exception error) { showBrandedMessage("حُفظ التغيير على الجهاز، وتعذرت مزامنته: " + error.getMessage()); }
        });
    }

    private void showBrandedMessage(String message) {
        runOnUiThread(() -> {
            if (webView == null) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); return; }
            String quoted=JSONObject.quote(message==null?"":message);
            webView.evaluateJavascript("if(window.showSadadToast){window.showSadadToast("+quoted+");}else{console.warn("+quoted+");}",null);
        });
    }

    private void requestBiometric(boolean enabling) {
        if (enabling && (appLocked || !sessions.isActive())) return;
        if (enabling && !localSecurity.hasPin()) { callBiometricSetting(false); showBrandedMessage("أنشئ رمز قفل التطبيق أولاً لاستخدام البصمة أو الوجه."); return; }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                biometricPromptInProgress = true;
                android.hardware.biometrics.BiometricPrompt prompt=new android.hardware.biometrics.BiometricPrompt.Builder(this)
                        .setTitle("فتح تطبيق سدد").setSubtitle("تحقق ببصمة الإصبع أو الوجه").setNegativeButton("استخدام رمز التطبيق",getMainExecutor(),(dialog,which)->onBiometricResult(false,enabling)).build();
                prompt.authenticate(new CancellationSignal(),getMainExecutor(),new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback(){
                    @Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult result){onBiometricResult(true,enabling);}
                    @Override public void onAuthenticationError(int code,CharSequence message){onBiometricResult(false,enabling);}
                });
            } catch(Exception e){onBiometricResult(false,enabling);}
            return;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                FingerprintManager manager=(FingerprintManager)getSystemService(FINGERPRINT_SERVICE);
                if(manager==null||!manager.isHardwareDetected()||!manager.hasEnrolledFingerprints()){onBiometricResult(false,enabling);return;}
                biometricPromptInProgress = true;
                manager.authenticate(null,new CancellationSignal(),0,new FingerprintManager.AuthenticationCallback(){
                    @Override public void onAuthenticationSucceeded(FingerprintManager.AuthenticationResult result){onBiometricResult(true,enabling);}
                    @Override public void onAuthenticationFailed(){showBrandedMessage("لم يتم التحقق من البصمة.");}
                    @Override public void onAuthenticationError(int errorCode,CharSequence errString){onBiometricResult(false,enabling);}
                },new Handler(Looper.getMainLooper()));
            } catch(Exception e){onBiometricResult(false,enabling);}
            return;
        }
        onBiometricResult(false,enabling);
    }

    private void onBiometricResult(boolean success, boolean enabling) {
        biometricPromptInProgress = false;
        runOnUiThread(() -> {
            if (enabling) {
                if(success)getPreferences(0).edit().putBoolean("biometric_enabled",true).putBoolean("lock_enabled",true).apply();
                callBiometricSetting(success);
                if(!success)showBrandedMessage("البصمة أو الوجه غير متاح. تحقق من قفل الجهاز ثم حاول مجدداً.");
            } else if(success) { appLocked = false; navigateInternal("home"); }
            else navigateInternal("unlock");
        });
    }
    private void callBiometricSetting(boolean success){if(webView!=null)webView.evaluateJavascript("if(window.onBiometricSetting)window.onBiometricSetting("+success+")",null);}

    private final class LocalPageClient extends WebViewClient {
        @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            String page = Uri.parse(url).getLastPathSegment();
            for (Map.Entry<String,String> entry : routes().entrySet()) {
                if (entry.getValue().equals(page) && !entry.getKey().equals(guardedRoute(entry.getKey()))) {
                    view.stopLoading(); navigateInternal(entry.getKey()); return;
                }
            }
            super.onPageStarted(view, url, favicon);
        }
        @Override public void onPageFinished(WebView view,String url){
            String page=Uri.parse(url).getLastPathSegment();
            if(page!=null) for(Map.Entry<String,String> entry:routes().entrySet()) if(entry.getValue().equals(page)){currentRoute=entry.getKey();break;}
            super.onPageFinished(view,url);
        }
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return handleUrl(request.getUrl()); }
        @Override @SuppressWarnings("deprecation") public boolean shouldOverrideUrlLoading(WebView view,String url){return handleUrl(Uri.parse(url));}
        private boolean handleUrl(Uri uri){
            String scheme=uri.getScheme()==null?"":uri.getScheme();
            if("file".equalsIgnoreCase(scheme) && uri.toString().startsWith(START_URL)) {
                for (Map.Entry<String,String> entry : routes().entrySet()) if (entry.getValue().equals(uri.getLastPathSegment())) { navigateInternal(entry.getKey()); return true; }
                return true;
            }
            if("tel".equalsIgnoreCase(scheme)||"mailto".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme)||"whatsapp".equalsIgnoreCase(scheme)){
                try{startActivity(new Intent(Intent.ACTION_VIEW,uri));}catch(Exception e){showBrandedMessage("لا يوجد تطبيق مناسب لهذا الإجراء.");}return true;
            }
            return true;
        }
    }

    private final class AppBridge {
        @JavascriptInterface public String getSnapshot(){
            try{requireLocalAccess();JSONObject data=database.getSnapshot();data.put("revision",sessions.revision());data.put("account",sessions.account());return data.toString();}
            catch(Exception e){return apiError("تعذر قراءة البيانات المحفوظة.").toString();}
        }
        @JavascriptInterface public String getSessionInfo(){
            try{return new JSONObject().put("authenticated",sessions.isActive()).put("account",sessions.account()).put("forcePasswordChange",sessions.forcePasswordChange()).put("revision",sessions.revision()).put("apiBaseUrl",sessions.apiBaseUrl()).put("darkTheme",isDarkTheme()).put("hasAppPassword",localSecurity.hasPin()).put("biometricEnabled",getPreferences(0).getBoolean("biometric_enabled",false)).toString();}
            catch(Exception e){return "{}";}
        }
        @JavascriptInterface public String getApiBaseUrl(){return sessions.apiBaseUrl();}
        @JavascriptInterface public String setApiBaseUrl(String value){
            try{String normalized=value==null?"":value.trim().replaceAll("/+$","");URL u=new URL(normalized);String host=u.getHost();boolean local="localhost".equalsIgnoreCase(host)||"127.0.0.1".equals(host)||"10.0.2.2".equals(host);if(!"https".equalsIgnoreCase(u.getProtocol())&&!local)throw new IllegalArgumentException("استخدم رابط HTTPS للخادم، ويسمح HTTP للمحاكي المحلي فقط.");if(!normalized.endsWith("/api"))normalized+="/api";sessions.setApiBaseUrl(normalized);return new JSONObject().put("ok",true).put("url",normalized).toString();}
            catch(Exception e){return apiError(e.getMessage()).toString();}
        }
        @JavascriptInterface public String login(String username,String password){
            try{String deviceId = getPreferences(0).getString("auth_device_id", ""); if (deviceId.isEmpty()) { deviceId = java.util.UUID.randomUUID().toString(); getPreferences(0).edit().putString("auth_device_id", deviceId).commit(); } JSONObject response=requestApi("POST","mobile/login",new JSONObject().put("username",username).put("password",password).put("deviceId",deviceId).put("deviceName", "Android • " + Build.MODEL).put("staffName", getPreferences(0).getString("staff_name", "")),false);finishLogin(response);return new JSONObject().put("ok",true).put("forcePasswordChange",sessions.forcePasswordChange()).toString();}
            catch(Exception e){return apiError(e.getMessage()).toString();}
        }
        @JavascriptInterface public String loginWithStaff(String username, String password, String staffName) {
            getPreferences(0).edit().putString("staff_name", staffName == null ? "" : staffName.trim()).commit();
            return login(username, password);
        }
        @JavascriptInterface public String changePassword(String password){
            try {
                requestApi("POST","mobile/change-password",new JSONObject().put("password",password),true);
                sessions.setForcePasswordChange(false);
                JSONObject response=requestApi("GET","mobile/snapshot",null,true);
                JSONObject remote=response.optJSONObject("snapshot");
                boolean remoteHasContacts=remote!=null&&remote.optJSONArray("contacts")!=null&&remote.optJSONArray("contacts").length()>0;
                if(remoteHasContacts){database.importSnapshot(remote);sessions.setRevision(remote.optLong("revision",sessions.revision()));}
                else if(database.hasContacts()){
                    JSONObject envelope=new JSONObject().put("baseRevision",sessions.revision()).put("snapshot",database.getSnapshot());
                    JSONObject synced=requestApi("POST","mobile/sync",envelope,true);sessions.setRevision(synced.optLong("revision",0L));
                }
                navigateInternal(localSecurity.hasPin()?"unlock":"home");
                return new JSONObject().put("ok",true).toString();
            }
            catch(Exception e){return apiError(e.getMessage()).toString();}
        }
        @JavascriptInterface public void logout(){
            try{requestApi("POST","mobile/logout",null,true);}catch(Exception ignored){}
            sessions.clear();appLocked=false;runOnUiThread(()->{if(webView!=null)webView.clearHistory();});getPreferences(0).edit().remove("biometric_enabled").remove("lock_enabled").apply();localSecurity.clearPin();navigateInternal("login");
        }
        @JavascriptInterface public String saveContact(String json){try{requireLocalAccess();JSONObject saved=database.saveContact(new JSONObject(json));scheduleSync();return new JSONObject().put("ok",true).put("id",saved.getLong("id")).toString();}catch(IllegalArgumentException e){return apiError(e.getMessage()).toString();}catch(Exception e){return apiError("تعذر حفظ جهة الاتصال.").toString();}}
        @JavascriptInterface public String saveDebt(String json){try{requireLocalAccess();JSONObject saved=database.saveDebt(new JSONObject(json));scheduleSync();return new JSONObject().put("ok",true).put("id",saved.getLong("id")).toString();}catch(IllegalArgumentException e){return apiError(e.getMessage()).toString();}catch(Exception e){return apiError("تعذر حفظ الدين.").toString();}}
        @JavascriptInterface public String savePayment(String json){try{requireLocalAccess();JSONObject saved=database.savePayment(new JSONObject(json));scheduleSync();return new JSONObject().put("ok",true).put("id",saved.getLong("id")).toString();}catch(IllegalArgumentException e){return apiError(e.getMessage()).toString();}catch(Exception e){return apiError("تعذر تسجيل الدفعة.").toString();}}
        @JavascriptInterface public String updateDebt(String json){try{requireLocalAccess();database.updateDebt(new JSONObject(json));scheduleSync();return new JSONObject().put("ok",true).toString();}catch(IllegalArgumentException e){return apiError(e.getMessage()).toString();}catch(Exception e){return apiError("تعذر تعديل بيانات الدين.").toString();}}
        @JavascriptInterface public void deleteAllData(){try{requireLocalAccess();database.deleteAllData();}catch(Exception e){showBrandedMessage(e.getMessage());}}
        @JavascriptInterface public void navigate(String route){
            if(!"login".equals(route)&&!"unlock".equals(route)&&!"change_password".equals(route)&&!sessions.isActive()){sessions.clear();navigateInternal("login");return;}
            if(sessions.forcePasswordChange()&&!"change_password".equals(route)&&!"login".equals(route)){navigateInternal("change_password");return;}
            if(routes().containsKey(route))navigateInternal(route);
        }
        @JavascriptInterface public void setTheme(String theme){boolean dark="dark".equalsIgnoreCase(theme);getPreferences(0).edit().putString("theme",dark?"dark":"light").apply();applyTheme(dark);}
        @JavascriptInterface public String setAppPassword(String pin){try{requireLocalAccess();localSecurity.setPin(pin);getPreferences(0).edit().putBoolean("lock_enabled",true).apply();return new JSONObject().put("ok",true).toString();}catch(Exception e){return apiError(e.getMessage()).toString();}}
        @JavascriptInterface public String verifyAppPassword(String pin){if(sessions.isActive() && localSecurity.verifyPin(pin)){appLocked=false;navigateInternal("home");return "{\"ok\":true}";}long waitMs=localSecurity.pinLockoutRemainingMs();String message=waitMs>0?"محاولات كثيرة؛ انتظر "+Math.max(1L,(waitMs+999L)/1000L)+" ثانية.":"رمز القفل غير صحيح.";try{return new JSONObject().put("ok",false).put("message",message).toString();}catch(JSONException e){return "{\"ok\":false,\"message\":\"تعذر التحقق من الرمز.\"}";}}
        @JavascriptInterface public void enableBiometric(){requestBiometric(true);}
        @JavascriptInterface public void unlockBiometric(){requestBiometric(false);}
        @JavascriptInterface public void disableBiometric(){if(appLocked || !sessions.isActive())return;getPreferences(0).edit().putBoolean("biometric_enabled",false).apply();callBiometricSetting(false);}
        @JavascriptInterface public void setAppLockEnabled(boolean enabled){if(appLocked || !sessions.isActive())return;getPreferences(0).edit().putBoolean("lock_enabled",enabled).apply();}
        @JavascriptInterface public void shareText(String subject,String content){runOnUiThread(()->{Intent send=new Intent(Intent.ACTION_SEND);send.setType("text/plain");send.putExtra(Intent.EXTRA_SUBJECT,subject==null?"سدد":subject);send.putExtra(Intent.EXTRA_TEXT,content==null?"":content);startActivity(Intent.createChooser(send,"مشاركة عبر"));});}
        @JavascriptInterface public String whatsappStatus(){try{requireLocalAccess();return requestApi("GET","mobile/whatsapp",null,true).toString();}catch(Exception e){return apiError(e.getMessage()).toString();}}
        @JavascriptInterface public String connectWhatsApp(String phoneNumberId,String accessToken,String templateName,String templateLanguage){try{requireLocalAccess();return requestApi("PUT","mobile/whatsapp",new JSONObject().put("phoneNumberId",phoneNumberId).put("accessToken",accessToken).put("templateName",templateName).put("templateLanguage",templateLanguage),true).toString();}catch(Exception e){return apiError(e.getMessage()).toString();}}
        @JavascriptInterface public String sendWhatsAppReport(long contactId){
            try {
                requireLocalAccess();FutureTask<JSONObject> task=new FutureTask<>(()->{
                    JSONObject payload=new JSONObject().put("baseRevision",sessions.revision()).put("snapshot",database.getSnapshot());
                    JSONObject synced=requestApi("POST","mobile/sync",payload,true);
                    sessions.setRevision(synced.optLong("revision",sessions.revision()));
                    return requestApi("POST","mobile/whatsapp/send-report",new JSONObject().put("contactId",contactId),true);
                });
                syncQueue.execute(task);
                return task.get(25,TimeUnit.SECONDS).toString();
            }catch(Exception e){return apiError(e.getMessage()).toString();}
        }
        @JavascriptInterface public void syncNow(){
            if(!sessions.isActive() || appLocked || sessions.forcePasswordChange()){showBrandedMessage("سجّل الدخول وافتح قفل التطبيق للمتابعة.");return;}
            syncQueue.execute(()->{
                try {
                    JSONObject payload=new JSONObject().put("baseRevision",sessions.revision()).put("snapshot",database.getSnapshot());
                    JSONObject response=requestApi("POST","mobile/sync",payload,true);
                    sessions.setRevision(response.optLong("revision",sessions.revision()));
                    showBrandedMessage("تمت مزامنة بيانات المتجر مع الخادم.");
                }catch(Exception e){showBrandedMessage("تعذرت المزامنة: "+e.getMessage());}
            });
        }
        @JavascriptInterface public void showMessage(String message){showBrandedMessage(message);}
    }
}
