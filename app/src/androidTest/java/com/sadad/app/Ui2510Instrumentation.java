package com.sadad.app;
import android.app.*;import android.content.Intent;import android.graphics.*;import android.graphics.drawable.BitmapDrawable;import android.os.Bundle;import android.view.*;import android.widget.*;import org.json.*;import java.io.*;import java.lang.reflect.*;
/** Focused regression for the unified list lifecycle and shared photo deletion. QA package only. */
public final class Ui2510Instrumentation extends Instrumentation {
 MainActivity host;NativeScreens screens;File output;
 public void onCreate(Bundle args){super.onCreate(args);start();}
 public void onStart(){Bundle result=new Bundle();try{
  check(getTargetContext().getPackageName().contains(".qa"),"QA only");
  new SessionStore(getTargetContext()).save(new JSONObject().put("token","qa").put("expiresAt",Long.MAX_VALUE).put("account",new JSONObject().put("id","qa").put("name","متجر الاختبار").put("status","active").put("subscriptionMode","permanent")));
  host=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));Field field=MainActivity.class.getDeclaredField("screens");field.setAccessible(true);screens=(NativeScreens)field.get(host);output=new File(host.getFilesDir(),"qa254");output.mkdirs();
  android.database.sqlite.SQLiteDatabase db=host.database.getWritableDatabase();db.delete("payments",null,null);db.delete("debts",null,null);db.delete("contacts",null,null);
  long person=host.database.saveContact(new JSONObject().put("name","محمد").put("phone","+970599123456")).getLong("id");db.beginTransaction();try{for(int i=0;i<1005;i++)db.execSQL("INSERT INTO debts(contact_id,direction,amount_cents,created_at) VALUES(?,'receivable',100,?)",new Object[]{person,System.currentTimeMillis()+i});db.setTransactionSuccessful();}finally{db.endTransaction();}
  show("home");TextView open=text(root(),"عرض سجل الحركات");check(open!=null,"Open history");runOnMainSync(open::performClick);settle();
  ListView list=findList(root());check(list!=null&&list.getHeaderViewsCount()==1&&list.getCount()==1006,"Complete unified history");check(list.getChildCount()<30,"Eager full-list rendering");check(list.getDividerHeight()>0,"Missing row gaps");check(count(root(),"إغلاق سجل الحركات")==1&&list.getFooterViewsCount()==0,"Bottom close remains");
  check(text(root(),"إغلاق سجل الحركات").getCompoundDrawables()[2].getBounds().width()>=Math.round(28*host.getResources().getDisplayMetrics().density),"Narrow arrow");capture("home-2510");
  runOnMainSync(()->list.setSelection(list.getCount()-1));settle();check(list.getLastVisiblePosition()==1005,"Last movement inaccessible");capture("history-end-2510");runOnMainSync(()->list.setSelection(0));settle();
  runOnMainSync(()->text(root(),"إغلاق سجل الحركات").performClick());settle();check(findList(root()).getCount()==1&&text(root(),"عرض سجل الحركات")!=null,"Close history");
  Bitmap image=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);image.eraseColor(Color.rgb(35,160,130));runOnMainSync(()->host.saveProfilePhoto(image,host.sessions.syncAccountId()));image.recycle();show("settings");check(host.profilePhoto(false)!=null&&host.profilePhoto(true)!=null,"Shared photo setup");
  final AlertDialog[] dialog={null};runOnMainSync(()->dialog[0]=ProfilePhotoEditor.show(host,((BitmapDrawable)host.profilePhoto(false)).getBitmap(),host.sessions.syncAccountId()));settle();check(dialog[0].getButton(-3).getText().toString().equals("حذف الصورة"),"Delete option");runOnMainSync(()->dialog[0].getButton(-3).performClick());settle();check(host.profilePhoto(false)==null&&host.profilePhoto(true)==null,"Shared photo retained");capture("photo-deleted-2510");show("home");capture("empty-photo-home-2510");
  result.putString("stream","PASS: 2.5.10 complete 1005-movement unified home scrolling, last row accessible, fewer than 30 active views, row gaps, one top close, wide arrow, shared photo deletion and fallback avatar.\n");finish(-1,result);
 }catch(Throwable error){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(error));finish(0,result);}}
 void show(String route)throws Exception{runOnMainSync(()->{host.show(route);host.refreshCurrentScreen();});settle();}
 void settle()throws Exception{waitForIdleSync();Thread.sleep(400);waitForIdleSync();}
 View root(){return host.getWindow().getDecorView();}
 TextView text(View v,String value){if(v instanceof TextView&&((TextView)v).getText().toString().contains(value))return(TextView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView found=text(((ViewGroup)v).getChildAt(i),value);if(found!=null)return found;}return null;}
 int count(View v,String value){int n=v instanceof TextView&&((TextView)v).getText().toString().contains(value)?1:0;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)n+=count(((ViewGroup)v).getChildAt(i),value);return n;}
 ListView findList(View v){if(v instanceof ListView)return(ListView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){ListView found=findList(((ViewGroup)v).getChildAt(i));if(found!=null)return found;}return null;}
 void capture(String name)throws Exception{settle();runOnMainSync(()->{View v=root();Bitmap b=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));try(FileOutputStream out=new FileOutputStream(new File(output,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}catch(Exception e){throw new RuntimeException(e);}b.recycle();});}
 void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
