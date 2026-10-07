package com.sadad.app;
import android.app.*;
import android.content.Intent;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
public final class Ui252FocusedInstrumentation extends Instrumentation {
 MainActivity host; NativeScreens screens; File output;
 @Override public void onCreate(Bundle args){super.onCreate(args);start();}
 @Override public void onStart(){ Bundle result=new Bundle();try{
  if(!getTargetContext().getPackageName().contains(".qa"))throw new AssertionError("QA only");
  new SessionStore(getTargetContext()).save(new JSONObject().put("token","qa-local").put("expiresAt",Long.MAX_VALUE).put("account",new JSONObject().put("id","qa").put("name","متجري").put("status","active").put("subscriptionMode","permanent")));
  host=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
  Field field=MainActivity.class.getDeclaredField("screens");field.setAccessible(true);screens=(NativeScreens)field.get(host); output=new File(host.getFilesDir(),"qa252");output.mkdirs();
  runOnMainSync(()->host.setTheme(false));
  for(String origin:new String[]{"home","history","history_results","notifications","settings","contacts"}){
   show(origin);screens.editingContactId=0;show("contact_form");runOnMainSync(()->find(host.getWindow().getDecorView(),"رجوع إلى الشاشة السابقة").performClick());waitForIdleSync();check(origin.equals(host.currentRoute()),"Arrow origin "+origin);
   show("contact_form");runOnMainSync(()->host.onBackPressed());waitForIdleSync();check(origin.equals(host.currentRoute()),"System back origin "+origin);
  }
  Method key=MainActivity.class.getDeclaredMethod("profileKey",String.class,String.class);key.setAccessible(true);Bitmap photo=Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888);photo.eraseColor(Color.rgb(30,170,140));
  try(FileOutputStream stream=new FileOutputStream(new File(host.getFilesDir(),key.invoke(host,"account_photo",host.sessions.syncAccountId())+".jpg"))){photo.compress(Bitmap.CompressFormat.JPEG,90,stream);}photo.recycle();
  check(host.profilePhoto(true)!=null&&host.profilePhoto(false)!=null,"Shared photo");
  show("settings");capture(host.getWindow().getDecorView(),"settings-light");show("home");capture(host.getWindow().getDecorView(),"home-light");show("contacts");capture(host.getWindow().getDecorView(),"customers-light");
  final AlertDialog[] dialog={null};final boolean[] saved={false};
  for(boolean dark:new boolean[]{false,true}){
   runOnMainSync(()->{host.setTheme(dark);dialog[0]=new SadadDialog.Builder(host).setTitle("مراجعة تسجيل الدين").setMessage("الشخص: محمد\nالمبلغ: 10 ₪\nراجع المعلومات قبل التسجيل.").setPositiveButton("تأكيد تسجيل الدين",(d,w)->saved[0]=true).setNegativeButton("مراجعة البيانات",null).show();});waitForIdleSync();Thread.sleep(300);
   Button yes=dialog[0].getButton(-1),no=dialog[0].getButton(-2);check(yes.getHeight()>0&&no.getHeight()>0,"Button measured");check(yes.getLayout().getHeight()+yes.getPaddingTop()+yes.getPaddingBottom()<=yes.getHeight(),"Text height clipped");check(yes.getTop()>=0 && no.getTop()>=0 && yes.getBottom()<=((View)yes.getParent()).getHeight() && no.getBottom()<=((View)no.getParent()).getHeight(),"Button bounds clipped");
   capture(dialog[0].getWindow().getDecorView(),dark?"debt-review-dark":"debt-review-light");runOnMainSync(()->yes.performClick());waitForIdleSync();check(saved[0],"Confirm callback");
  }
  runOnMainSync(()->host.setTheme(false));show("home");result.putString("stream","PASS: six origins with arrow/system back, shared portrait, full dialog text and button bounds, light/dark dialogs and confirmation callback\n");finish(-1,result);
 }catch(Throwable error){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(error));finish(0,result);}}
 void show(String route){runOnMainSync(()->{host.show(route);host.refreshCurrentScreen();});waitForIdleSync();}
 View find(View view,String description){if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),description);if(found!=null)return found;}return null;}
 void capture(View view,String name)throws Exception{waitForIdleSync();Thread.sleep(200);runOnMainSync(()->{Bitmap image=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(image));try(FileOutputStream stream=new FileOutputStream(new File(output,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,stream);}catch(Exception error){throw new RuntimeException(error);}image.recycle();});}
 void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
