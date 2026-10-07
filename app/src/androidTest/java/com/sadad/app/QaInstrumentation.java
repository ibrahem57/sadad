package com.sadad.app;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;

/** Runs against the isolated com.sadad.qa.offline variant; never the user's ledger. */
public final class QaInstrumentation extends Instrumentation {
    private MainActivity host;
    private File output;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!getTargetContext().getPackageName().contains(".qa")) throw new AssertionError("Use isolated QA applicationId");
            SessionStore session = new SessionStore(getTargetContext());
            session.save(new JSONObject().put("token", "qa-local").put("expiresAt", Long.MAX_VALUE).put("forcePasswordChange", false).put("account", new JSONObject().put("id", "qa").put("username", "qa").put("name", "متجري").put("status", "active").put("subscriptionMode", "permanent")));
            host = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            output = new File(host.getFilesDir(), "qa"); output.mkdirs();
            host.database.getWritableDatabase().delete("payments", null, null); host.database.getWritableDatabase().delete("debts", null, null); host.database.getWritableDatabase().delete("contacts", null, null);
            long id = host.database.saveContact(new JSONObject().put("name", "محمد مصطفى").put("phone", "0591111111")).getLong("id");
            host.database.saveDebt(new JSONObject().put("contactId", id).put("amount", "150").put("direction", "receivable").put("note", "شراء بضاعة"));
            host.database.saveDebt(new JSONObject().put("contactId", id).put("amount", "200").put("direction", "receivable").put("note", "كشف جديد"));
            host.database.savePayment(new JSONObject().put("contactId", id).put("amount", "75").put("method", "cash").put("createdBy", "صاحب المتجر"));
            show("login"); screenshot("login");
            assertAbsent("نسخة مستقلة: تحفظ السجلات"); assertAbsent("(للحساب متعدد الأجهزة)");
            show("home"); screenshot("home"); assertAbsent("إجراءات سريعة"); assertAbsent("مرحباً بك"); assertPresent("حركة الشهر الحالي");
            show("contact_form"); screenshot("contact-add"); assertAbsent("التصنيف");
            if (findDescription(host.getWindow().getDecorView(), "إملاء صوتي كمسودة") == null) throw new AssertionError("Missing microphone");
            show("notifications"); screenshot("notifications"); assertAbsent("إعدادات إشعارات الهاتف");
            show("history"); screenshot("history"); assertAbsent("السجل فارغ"); assertAbsent("المتبقي الإجمالي");
            runOnMainSync(() -> findText(host.getWindow().getDecorView(), "يومي").performClick()); waitForIdleSync();
            if (!"history_results".equals(host.currentRoute())) throw new AssertionError("History didn't open"); screenshot("history-results");
            runOnMainSync(() -> findText(host.getWindow().getDecorView(), "شهري").performClick()); waitForIdleSync(); assertPresent("هذا الشهر");
            show("settings"); screenshot("settings-top"); assertPresent("حفظ كشف السجلات"); assertPresent("مشاركة كشف السجلات");
            runOnMainSync(() -> findScroll(host.getWindow().getDecorView()).fullScroll(View.FOCUS_DOWN)); waitForIdleSync(); Thread.sleep(350); screenshot("settings-bottom");
            runOnMainSync(() -> host.setTheme(true)); waitForIdleSync(); screenshot("settings-dark"); runOnMainSync(() -> host.setTheme(false));
            JSONObject snap = statementSample();
            byte[] pdf = LedgerTablePdf.create("متجر الاختبار", "صاحب المتجر", "كل المدة", snap, 0, Long.MIN_VALUE, Long.MAX_VALUE);
            File statement = new File(output, "statements.pdf"); try (FileOutputStream out = new FileOutputStream(statement)) { out.write(pdf); }
            File cache = new File(host.getCacheDir(), "exports"); cache.mkdirs();
            File shareFile = new File(cache, "test.pdf"); try (FileOutputStream out = new FileOutputStream(shareFile)) { out.write(pdf); }
            android.net.Uri shareUri = new android.net.Uri.Builder().scheme("content").authority(host.getPackageName() + ".pdf").appendPath("test.pdf").build();
            try (java.io.InputStream in = host.getContentResolver().openInputStream(shareUri)) { if (in.read() != '%') throw new AssertionError("Invalid shared PDF"); }
            try (android.database.Cursor cursor = host.getContentResolver().query(shareUri, null, null, null, null)) { if (cursor == null || !cursor.moveToFirst() || cursor.getLong(1) != pdf.length) throw new AssertionError("PDF metadata failed"); }
            boolean readOnly = false; try { host.getContentResolver().openFileDescriptor(shareUri, "w").close(); } catch (java.io.FileNotFoundException expected) { readOnly = true; } if (!readOnly) throw new AssertionError("Provider allowed write access");
            try (PdfRenderer renderer = new PdfRenderer(ParcelFileDescriptor.open(statement, ParcelFileDescriptor.MODE_READ_ONLY))) {
                if (renderer.getPageCount() < 4) throw new AssertionError("Expected multiple pages");
                for (int i = 0; i < renderer.getPageCount(); i++) try (PdfRenderer.Page page = renderer.openPage(i)) {
                    Bitmap bitmap = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888); bitmap.eraseColor(android.graphics.Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); writeBitmap(bitmap, "pdf-page-" + (i + 1)); bitmap.recycle();
                }
                result.putInt("pdfPages", renderer.getPageCount());
            }
            android.content.IntentFilter saveFilter = new android.content.IntentFilter(Intent.ACTION_CREATE_DOCUMENT); saveFilter.addCategory(Intent.CATEGORY_OPENABLE); saveFilter.addDataType("application/pdf");
            android.app.Instrumentation.ActivityMonitor saveMonitor = addMonitor(saveFilter, new ActivityResult(android.app.Activity.RESULT_CANCELED, null), true);
            runOnMainSync(() -> host.savePdf(pdf, "test.pdf", false)); waitForIdleSync(); if (saveMonitor.getHits() != 1) throw new AssertionError("Save didn't open document picker"); removeMonitor(saveMonitor);
            android.app.Instrumentation.ActivityMonitor shareMonitor = addMonitor(new android.content.IntentFilter(Intent.ACTION_CHOOSER), new ActivityResult(android.app.Activity.RESULT_CANCELED, null), true);
            runOnMainSync(() -> host.savePdf(pdf, "test.pdf", true)); waitForIdleSync(); if (shareMonitor.getHits() != 1) throw new AssertionError("Share didn't open chooser"); removeMonitor(shareMonitor);
            Bitmap photo = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888); photo.eraseColor(android.graphics.Color.GREEN); File photoFile = new File(output, "profile.jpg"); try (FileOutputStream out = new FileOutputStream(photoFile)) { photo.compress(Bitmap.CompressFormat.JPEG, 90, out); } photo.recycle();
            java.lang.reflect.Field storeFlag = MainActivity.class.getDeclaredField("pendingStorePhoto"); storeFlag.setAccessible(true); storeFlag.setBoolean(host, true);
            java.lang.reflect.Field photoAccount = MainActivity.class.getDeclaredField("pendingPhotoAccount"); photoAccount.setAccessible(true); photoAccount.set(host, host.sessions.syncAccountId());
            runOnMainSync(() -> host.onActivityResult(7317, android.app.Activity.RESULT_OK, new Intent().setData(android.net.Uri.fromFile(photoFile)))); waitForIdleSync(); if (host.profilePhoto(true) == null) throw new AssertionError("Photo didn't persist");
            show("home"); result.putString("stream", "PASS: dashboard, removals, history navigation, settings scroll/theme, photo persistence, multi-person paginated PDF, save picker, share chooser, read-only PDF provider\n"); finish(-1, result);
        } catch (Throwable error) { result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error)); finish(0, result); }
    }
    private void show(String route) { runOnMainSync(() -> { host.show(route); host.refreshCurrentScreen(); }); waitForIdleSync(); }
    private void screenshot(String name) throws Exception { Thread.sleep(250); Bitmap bitmap = getUiAutomation().takeScreenshot(); writeBitmap(bitmap, name); bitmap.recycle(); }
    private void writeBitmap(Bitmap bitmap, String name) throws Exception { try (FileOutputStream out = new FileOutputStream(new File(output, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } }
    private TextView findText(View view, String text) { if (view instanceof TextView && ((TextView) view).getText().toString().contains(text)) return (TextView) view; if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) { TextView result = findText(group.getChildAt(i), text); if (result != null) return result; } } return null; }
    private View findDescription(View view, String description) { if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view; if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) { View result = findDescription(group.getChildAt(i), description); if (result != null) return result; } } return null; }
    private ScrollView findScroll(View view) { if (view instanceof ScrollView) return (ScrollView) view; if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) { ScrollView result = findScroll(group.getChildAt(i)); if (result != null) return result; } } return null; }
    private void assertPresent(String text) { if (findText(host.getWindow().getDecorView(), text) == null) throw new AssertionError("Missing: " + text); }
    private void assertAbsent(String text) { if (findText(host.getWindow().getDecorView(), text) != null) throw new AssertionError("Unexpected: " + text); }
    private JSONObject statementSample() throws Exception {
        JSONArray people = new JSONArray().put(new JSONObject().put("id", 1).put("name", "محمد مصطفى").put("phone", "0591111111").put("receivable", 0)).put(new JSONObject().put("id", 2).put("name", "أحمد خليل").put("phone", "0592222222").put("receivable", 50));
        JSONArray transactions = new JSONArray(); long now = System.currentTimeMillis();
        for (int i = 0; i < 74; i++) transactions.put(new JSONObject().put("id", i + 1).put("contactId", 1).put("createdAt", now - (74 - i) * 86400000L).put("kind", i % 2 == 0 ? "debt" : "payment").put("direction", "receivable").put("amount", 100.25).put("note", i == 4 ? new String(new char[30]).replace("\0", "بيان حركة طويل لاختبار التفاف النص ومتابعته داخل الجدول. ") : "شراء بضاعة وتسديد حساب").put("createdBy", "موظف الاختبار").put("dueDate", "2026-12-01"));
        transactions.put(new JSONObject().put("id", 80).put("contactId", 2).put("createdAt", now).put("kind", "debt").put("direction", "receivable").put("amount", 50).put("note", "حساب الشخص الثاني"));
        return new JSONObject().put("contacts", people).put("transactions", transactions).put("debts", new JSONArray());
    }
}
