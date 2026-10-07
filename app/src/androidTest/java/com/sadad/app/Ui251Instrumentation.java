package com.sadad.app;
import android.app.Instrumentation;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Focused 2.5.1 UI regression checks on a separate, disposable applicationId. */
public final class Ui251Instrumentation extends Instrumentation {
    private MainActivity host; private NativeScreens screens; private File output;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!getTargetContext().getPackageName().contains(".qa")) throw new AssertionError("Use QA applicationId");
            new SessionStore(getTargetContext()).save(new JSONObject().put("token", "qa-local").put("expiresAt", Long.MAX_VALUE).put("forcePasswordChange", false).put("account", new JSONObject().put("id", "qa").put("username", "qa").put("name", "متجري").put("status", "active").put("subscriptionMode", "permanent")));
            host = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            output = new File(host.getFilesDir(), "qa251"); output.mkdirs(); Field layer = MainActivity.class.getDeclaredField("screens"); layer.setAccessible(true); screens = (NativeScreens) layer.get(host);
            host.database.getWritableDatabase().delete("payments", null, null); host.database.getWritableDatabase().delete("debts", null, null); host.database.getWritableDatabase().delete("contacts", null, null);
            long first = person("محمد مصطفى", "0591111111"); long second = person("محمد النجار", "0592222222"); long third = person("عماد قطيش", "0593333333"); person("حسن جنوان", "0594444444");
            debt(first, "150"); debt(first, "200"); host.database.savePayment(new JSONObject().put("contactId", first).put("amount", "75").put("method", "cash")); debt(second, "600"); debt(third, "750");
            runOnMainSync(() -> host.setTheme(false)); show("contacts"); screenshot("customers-light"); assertPresent("الزبائن"); assertPresent("الكل (4)"); assertPresent("عليهم دين (3)");
            click("تم تسديده (1)"); assertPresent("حسن جنوان"); assertAbsent("محمد النجار"); click("الكل (4)");
            runOnMainSync(() -> findHint(root(), "ابحث بالاسم أو رقم الهاتف").setText("النجار")); waitForIdleSync(); assertPresent("محمد النجار"); assertAbsent("عماد قطيش"); runOnMainSync(() -> findHint(root(), "ابحث بالاسم أو رقم الهاتف").setText("")); waitForIdleSync();
            click("الترتيب:"); accessibleClick("الأعلى دينًا"); waitForIdleSync(); Field rowsField = NativeScreens.class.getDeclaredField("contactRows"); rowsField.setAccessible(true); LinearLayout rows = (LinearLayout) rowsField.get(screens); if (findText(rows.getChildAt(0), "عماد قطيش") == null) throw new AssertionError("Wrong debt sorting");
            show("history_results"); assertAbsent("رقم الهاتف:"); assertAbsent("التاريخ والساعة:"); assertPresent("المتبقي: 275"); assertPresent("آخر دين"); assertPresent("200"); screenshot("history-light");
            TextView arrow = (TextView) findDescription(root(), "رجوع إلى الشاشة السابقة"); if (arrow.getCurrentTextColor() != Color.BLACK) throw new AssertionError("Light arrow must be black");
            click("محمد مصطفى"); if (!"contact_detail".equals(host.currentRoute()) || screens.contactId != first) throw new AssertionError("Name didn't open person's ledger");
            screens.contactId = first; show("debt_form"); runOnMainSync(() -> findHint(root(), "0.00").setText("50")); click("حفظ الدين"); screenshot("debt-review-light"); accessibleClick("تأكيد تسجيل الدين"); waitForIdleSync(); if (!"contact_detail".equals(host.currentRoute())) throw new AssertionError("Debt confirmation failed");
            show("notifications"); invoke("showAccountMenu"); screenshot("account-menu-light"); accessibleClick("المظهر"); screenshot("appearance-light"); accessibleDescription("اختيار الوضع الداكن"); waitForIdleSync();
            if (!host.isDarkTheme()) throw new AssertionError("Dark choice failed"); show("history_results"); arrow = (TextView) findDescription(root(), "رجوع إلى الشاشة السابقة"); if (arrow.getCurrentTextColor() != Color.WHITE) throw new AssertionError("Dark arrow must be white"); screenshot("history-dark");
            show("contacts"); screenshot("customers-dark");
            final AlertDialog[] dialog = {null}; final boolean[] confirmed = {false}; runOnMainSync(() -> dialog[0] = new SadadDialog.Builder(host).setTitle("مراجعة تسجيل الدفعة").setMessage("الشخص: محمد مصطفى\nالمبلغ: 100 ₪\nراجع البيانات قبل الحفظ.").setNegativeButton("مراجعة البيانات", null).setPositiveButton("تأكيد تسجيل الدفعة", (d, w) -> confirmed[0] = true).show()); waitForIdleSync(); screenshot("payment-review-dark");
            if (((TextView) dialog[0].findViewById(android.R.id.message)).getCurrentTextColor() != Color.parseColor("#F0F7EF")) throw new AssertionError("Unreadable dark dialog"); runOnMainSync(() -> dialog[0].getButton(AlertDialog.BUTTON_POSITIVE).performClick()); waitForIdleSync(); if (!confirmed[0]) throw new AssertionError("Confirmation listener changed");
            show("notifications"); invoke("showAppearancePicker"); screenshot("appearance-dark"); accessibleDescription("اختيار الوضع الفاتح"); waitForIdleSync(); if (host.isDarkTheme()) throw new AssertionError("Light choice failed");
            runOnMainSync(() -> dialog[0] = new SadadDatePickerDialog(host, (picker, y, m, d) -> {}, 2026, 9, 6)); runOnMainSync(() -> dialog[0].show()); waitForIdleSync(); screenshot("calendar-light"); runOnMainSync(() -> dialog[0].dismiss());
            show("settings"); screenshot("settings-light"); show("home"); screenshot("home-light");
            result.putString("stream", "PASS: customer filters/search/sort, last debt after latest payment, name navigation, black/white bare arrows, actual debt confirmation, theme artwork/selection, dark dialogs and callbacks, themed calendar\n"); finish(-1, result);
        } catch (Throwable error) { result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error)); finish(0, result); }
    }
    private long person(String name, String phone) throws Exception { return host.database.saveContact(new JSONObject().put("name", name).put("phone", phone)).getLong("id"); }
    private void debt(long id, String amount) throws Exception { host.database.saveDebt(new JSONObject().put("contactId", id).put("amount", amount).put("direction", "receivable")); }
    private View root() { return host.getWindow().getDecorView(); }
    private void show(String route) { runOnMainSync(() -> { host.show(route); host.refreshCurrentScreen(); }); waitForIdleSync(); }
    private void click(String text) { runOnMainSync(() -> { TextView view = findText(root(), text); if (view == null) throw new AssertionError("Missing button: " + text); view.performClick(); }); waitForIdleSync(); }
    private void invoke(String name) throws Exception { Method method = NativeScreens.class.getDeclaredMethod(name); method.setAccessible(true); runOnMainSync(() -> { try { method.invoke(screens); } catch (Exception error) { throw new RuntimeException(error); } }); waitForIdleSync(); }
    private void accessibleClick(String text) { AccessibilityNodeInfo node = getUiAutomation().getRootInActiveWindow(); if (node == null) throw new AssertionError("No active window"); for (AccessibilityNodeInfo found : node.findAccessibilityNodeInfosByText(text)) { if (text.contentEquals(found.getText() == null ? "" : found.getText())) { while (found != null && !found.isClickable()) found = found.getParent(); if (found != null && found.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return; } } throw new AssertionError("Cannot click dialog: " + text); }
    private void accessibleDescription(String description) { AccessibilityNodeInfo node = getUiAutomation().getRootInActiveWindow(); AccessibilityNodeInfo found = accessibilityDescription(node, description); if (found == null || !found.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("Missing choice: " + description); }
    private AccessibilityNodeInfo accessibilityDescription(AccessibilityNodeInfo node, String description) { if (node == null) return null; if (description.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())) return node; for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo found = accessibilityDescription(node.getChild(i), description); if (found != null) return found; } return null; }
    private void screenshot(String name) throws Exception { waitForIdleSync(); Thread.sleep(250); Bitmap bitmap = getUiAutomation().takeScreenshot(); try (FileOutputStream out = new FileOutputStream(new File(output, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } bitmap.recycle(); }
    private TextView findText(View view, String text) { if (view instanceof TextView && ((TextView)view).getText().toString().contains(text)) return (TextView)view; if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextView found=findText(group.getChildAt(i),text);if(found!=null)return found;}}return null; }
    private EditText findHint(View view, String hint) { if(view instanceof EditText && hint.contentEquals(((EditText)view).getHint()==null?"":((EditText)view).getHint()))return (EditText)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){EditText found=findHint(group.getChildAt(i),hint);if(found!=null)return found;}}return null; }
    private View findDescription(View view, String description) { if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=findDescription(group.getChildAt(i),description);if(found!=null)return found;}}return null; }
    private void assertPresent(String text) { if(findText(root(),text)==null)throw new AssertionError("Missing: "+text); }
    private void assertAbsent(String text) { if(findText(root(),text)!=null)throw new AssertionError("Unexpected: "+text); }
}
