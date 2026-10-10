package com.sadad.app;

import android.app.Activity;
import android.content.*;
import android.net.*;
import android.os.*;
import android.webkit.*;
import android.widget.Toast;
import android.view.WindowManager;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** واجهة الإصدار الثالث؛ لا تفتح sadad.db القديم ولا تحمل دفترًا كاملًا إلى الخادم. */
public final class LedgerActivity extends Activity {
 private WebView web;private SessionStore sessions;private LedgerRepository repo;private InstallationKey installation;
 private final ExecutorService background=Executors.newSingleThreadExecutor();
 private ConnectivityManager.NetworkCallback network;private byte[] exportBytes;private char[] importPassword;
 protected void onCreate(Bundle saved){super.onCreate(saved);getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE);sessions=new SessionStore(this);installation=new InstallationKey(this);web=new WebView(this);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setAllowFileAccess(true);web.getSettings().setAllowContentAccess(false);web.getSettings().setAllowFileAccessFromFileURLs(false);web.getSettings().setAllowUniversalAccessFromFileURLs(false);web.getSettings().setDomStorageEnabled(false);web.addJavascriptInterface(new Bridge(),"Ledger");web.setWebViewClient(new WebViewClient(){public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest request){return !request.getUrl().toString().equals("file:///android_asset/v3.html");}});web.setWebChromeClient(new WebChromeClient());setContentView(web);web.loadUrl("file:///android_asset/v3.html");if(BuildConfig.DEBUG)WebView.setWebContentsDebuggingEnabled(true);
  network=new ConnectivityManager.NetworkCallback(){public void onAvailable(Network n){queueSync();}};((ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE)).registerDefaultNetworkCallback(network);queueSync();
 }
 protected void onResume(){super.onResume();queueSync();}
 private synchronized LedgerRepository repository()throws Exception{if(!sessions.hasCredentials()||sessions.forcePasswordChange())throw new IOException("سجّل الدخول وغيّر كلمة المرور المؤقتة أولًا.");if(repo==null)repo=new LedgerRepository(this,sessions);return repo;}
 private void queueSync(){LedgerSyncWorker.schedule(this);if(background.isShutdown())return;background.execute(()->{try{if(sessions.hasCredentials()&&!sessions.forcePasswordChange())repository().sync();}catch(Exception ignored){}update();});}
 private void update(){runOnUiThread(()->{if(web!=null)web.evaluateJavascript("if(window.refreshView)refreshView()",null);});}
 private String result(JSONObject json){return json.toString();}
 private String error(Exception e){try{return new JSONObject().put("ok",false).put("message",e.getMessage()==null?"تعذر إتمام الإجراء.":e.getMessage()).toString();}catch(Exception ignored){return "{\"ok\":false}";}}
 final class Bridge {
  @JavascriptInterface public String snapshot(){try{JSONObject out=new JSONObject().put("ok",true).put("signedIn",sessions.hasCredentials()).put("forcePasswordChange",sessions.forcePasswordChange()).put("account",sessions.account());if(sessions.hasCredentials()&&!sessions.forcePasswordChange())out.put("data",repository().view());return result(out);}catch(Exception e){return error(e);}}
  @JavascriptInterface public String login(String username,String password){try{LedgerTransport transport=new LedgerTransport(sessions,installation);JSONObject response=transport.legacy("mobile/login",new JSONObject().put("username",username).put("password",password).put("deviceId",installation.id()).put("deviceName","سدد Android v3").put("staffName","صاحب المتجر"),false);if(repo!=null){repo.journal.close();repo=null;}sessions.save(response);queueSync();return result(new JSONObject().put("ok",true));}catch(Exception e){return error(e);}}
  @JavascriptInterface public String changePassword(String current,String password){try{LedgerTransport transport=new LedgerTransport(sessions,installation);transport.refresh();transport.legacy("mobile/change-password",new JSONObject().put("currentPassword",current).put("password",password),true);sessions.setForcePasswordChange(false);queueSync();return result(new JSONObject().put("ok",true));}catch(Exception e){return error(e);}}
  @JavascriptInterface public String add(String type,String payload){try{JSONObject entry=repository().add(type,new JSONObject(payload));queueSync();return result(new JSONObject().put("ok",true).put("operationId",entry.getJSONObject("command").getString("operationId")));}catch(Exception e){return error(e);}}
  @JavascriptInterface public String online(String type,String payload,long version){try{return result(new JSONObject().put("ok",true).put("result",repository().online(type,new JSONObject(payload),version)));}catch(Exception e){return error(e);}}
  @JavascriptInterface public String cancel(String id){try{repository().journal.cancel(id);return result(new JSONObject().put("ok",true));}catch(Exception e){return error(e);}}
  @JavascriptInterface public void syncNow(){queueSync();}
  @JavascriptInterface public String logout(){try{try{new LedgerTransport(sessions,installation).legacy("mobile/logout",new JSONObject(),true);}catch(Exception ignored){}synchronized(LedgerActivity.this){sessions.clear();if(repo!=null){repo.journal.close();repo=null;}}return result(new JSONObject().put("ok",true));}catch(Exception e){return error(e);}}
  @JavascriptInterface public String exportRecovery(String passphrase){try{exportBytes=RecoveryCrypto.seal(repository().journal.exportPlain(),passphrase.toCharArray());runOnUiThread(()->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,"sadid-recovery.sadid"),701));return result(new JSONObject().put("ok",true));}catch(Exception e){return error(e);}}
  @JavascriptInterface public String importRecovery(String passphrase){try{repository();importPassword=passphrase.toCharArray();runOnUiThread(()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),702));return result(new JSONObject().put("ok",true));}catch(Exception e){return error(e);}}
  @JavascriptInterface public String recoveryCommands(){try{JSONArray all=repository().journal.entries(),commands=new JSONArray();for(int i=0;i<all.length();i++){JSONObject e=all.getJSONObject(i);String state=e.getString("state");if(!state.equals("confirmed")&&!state.equals("rejected")&&!state.equals("cancelled"))commands.put(e.getJSONObject("command"));}return result(new JSONObject().put("ok",true).put("commands",commands).put("newInstallationId",installation.id()));}catch(Exception e){return error(e);}}
  @JavascriptInterface public String setRecoveryPermit(String permit){try{if(!permit.matches("[0-9a-fA-F-]{36}"))throw new IllegalArgumentException("تصريح غير صالح.");repository().journal.metadata("recovery",new JSONObject().put("permitId",permit));queueSync();return result(new JSONObject().put("ok",true));}catch(Exception e){return error(e);}}
 }
 protected void onActivityResult(int request,int code,Intent data){super.onActivityResult(request,code,data);if(code!=RESULT_OK||data==null){exportBytes=null;if(importPassword!=null)java.util.Arrays.fill(importPassword,'\0');importPassword=null;return;}Uri uri=data.getData();background.execute(()->{try{if(request==701){try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("تعذر فتح الوجهة.");out.write(exportBytes);out.flush();}repository().journal.metadata("export",new JSONObject().put("at",System.currentTimeMillis()).put("destination",uri.toString()));exportBytes=null;}else if(request==702){ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=getContentResolver().openInputStream(uri)){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>8*1024*1024)throw new IOException("ملف كبير جدًا.");bytes.write(buffer,0,n);}}repository().journal.importPlain(RecoveryCrypto.open(bytes.toByteArray(),importPassword));importPassword=null;queueSync();}runOnUiThread(()->Toast.makeText(this,request==701?"حُفظ التصدير. انسخ الملف خارج الهاتف لحماية الإدخالات.":"حُفظ الاستيراد؛ يلزم فحص الإيصالات وتصريح الاسترداد.",Toast.LENGTH_LONG).show());}catch(Exception e){runOnUiThread(()->Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show());}update();});}
 protected void onDestroy(){if(network!=null)((ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(network);background.shutdown();if(web!=null){web.removeJavascriptInterface("Ledger");web.destroy();web=null;}super.onDestroy();}
}
