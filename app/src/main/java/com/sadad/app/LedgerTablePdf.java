package com.sadad.app;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

/** Arabic A4 statements. Each person starts on a new page with repeated table headers. */
final class LedgerTablePdf {
    private static final int WIDTH = 595, HEIGHT = 842, MARGIN = 36, BODY = 523;
    // Logical RTL order: date, movement, debt, payment, running balance, due date, description.
    private static final int[] COL = {74, 45, 58, 58, 66, 74, 148};
    private static final String[] HEAD = {"التاريخ", "الحركة", "دين ₪", "دفعة ₪", "الرصيد ₪", "الاستحقاق", "البيان"};
    private final PdfDocument doc = new PdfDocument();
    private PdfDocument.Page page;
    private Canvas canvas;
    private float y;
    private int pageNumber;
    private String store, owner, period;
    private JSONObject person;
    private long opening, closing;
    private boolean alternate;

    static byte[] create(String store, String owner, String period, JSONObject snapshot, long personId, long from, long to) throws Exception {
        LedgerTablePdf writer = new LedgerTablePdf(); writer.store = store; writer.owner = owner; writer.period = period;
        JSONArray people = snapshot.optJSONArray("contacts"), transactions = snapshot.optJSONArray("transactions"), debts = snapshot.optJSONArray("debts");
        try {
            if (people != null) for (int p = 0; p < people.length(); p++) {
                JSONObject person = people.optJSONObject(p); if (person == null || (personId != 0L && person.optLong("id") != personId)) continue;
                ArrayList<JSONObject> rows = new ArrayList<>();
                if (transactions != null) for (int i = 0; i < transactions.length(); i++) {
                    JSONObject tx = transactions.optJSONObject(i);
                    if (tx != null && tx.optLong("contactId") == person.optLong("id") && "receivable".equals(tx.optString("direction"))) rows.add(tx);
                }
                Collections.sort(rows, (a, b) -> { int time = Long.compare(a.optLong("createdAt"), b.optLong("createdAt")); return time == 0 ? Long.compare(a.optLong("id"), b.optLong("id")) : time; });
                writer.person = person; writer.opening = 0; writer.closing = 0;
                long totalDebt = 0, totalPaid = 0;
                for (JSONObject tx : rows) {
                    long cents = Math.round(tx.optDouble("amount") * 100d); boolean payment = "payment".equals(tx.optString("kind"));
                    if (tx.optLong("createdAt") < from) writer.opening += payment ? -cents : cents;
                    if (tx.optLong("createdAt") >= from && tx.optLong("createdAt") <= to) { if (payment) totalPaid += cents; else totalDebt += cents; }
                }
                writer.closing = writer.opening + totalDebt - totalPaid;
                writer.startPage();
                writer.tableRow(new String[]{"", "افتتاحي", "", "", number(writer.opening), "", "رصيد ما قبل الفترة المحددة"}, false);
                long running = writer.opening; int count = 0;
                for (JSONObject tx : rows) {
                    long time = tx.optLong("createdAt"); if (time < from || time > to) continue;
                    boolean payment = "payment".equals(tx.optString("kind")); long cents = Math.round(tx.optDouble("amount") * 100d); running += payment ? -cents : cents; count++;
                    String due = tx.optString("dueDate", "");
                    if (!payment && debts != null) for (int d = 0; d < debts.length(); d++) { JSONObject debt = debts.optJSONObject(d); if (debt != null && debt.optLong("id") == tx.optLong("debtId")) { due = debt.optString("dueDate", due); break; } }
                    String note = tx.optString("note", ""); if (!tx.optString("createdBy").trim().isEmpty()) note += (note.isEmpty() ? "" : "\n") + "سجّلها: " + tx.optString("createdBy");
                    ArrayList<String> parts = split(note, 220);
                    writer.tableRow(new String[]{date(time), payment ? "دفعة" : "دين", payment ? "" : number(cents), payment ? number(cents) : "", number(running), payment || due.isEmpty() ? "-" : dueDate(due), parts.get(0)}, false);
                    for (int n = 1; n < parts.size(); n++) writer.tableRow(new String[]{"", "تابع", "", "", "", "", parts.get(n)}, false);
                }
                if (count == 0) writer.tableRow(new String[]{"", "", "", "", "", "", "لا توجد حركات ضمن الفترة المحددة"}, false);
                writer.tableRow(new String[]{"", "الإجمالي", number(totalDebt), number(totalPaid), number(writer.closing), "", "رصيد نهاية الفترة"}, true);
                writer.finishPage();
            }
            if (writer.pageNumber == 0) throw new IllegalArgumentException("لا توجد بيانات للشخص");
            ByteArrayOutputStream out = new ByteArrayOutputStream(); writer.doc.writeTo(out); return out.toByteArray();
        } finally { writer.finishPage(); writer.doc.close(); }
    }

    private void startPage() {
        if (page != null) finishPage();
        page = doc.startPage(new PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, ++pageNumber).create()); canvas = page.getCanvas(); canvas.drawColor(Color.WHITE);
        Paint band = new Paint(); band.setColor(Color.rgb(6, 75, 66)); canvas.drawRect(0, 0, WIDTH, 70, band);
        draw("سدد | كشف حساب شخصي", MARGIN, 18, BODY, 22, Color.WHITE, true);
        y = 88;
        block("المتجر: " + (store.isEmpty() ? "متجري" : store) + (owner.isEmpty() ? "" : "   |   صاحب المتجر: " + owner), 12, false);
        block("اسم الشخص: " + person.optString("name"), 16, true);
        block("الهاتف: " + (person.optString("phone").isEmpty() ? "غير مسجل" : person.optString("phone")), 12, false);
        block("التصنيف: "+person.optString("category")+"   |   سقف الائتمان: "+person.optString("creditLimit","0")+" ₪",11,false);
        block("تاريخ الإضافة: "+date(person.optLong("createdAt"))+"   |   أضافه: "+person.optString("createdBy"),11,false);
        block("موافقة إشعارات واتساب: "+(person.optBoolean("whatsappOptIn")?"نعم":"لا"),11,false);
        if (!person.optString("note").trim().isEmpty()) block("ملاحظات الشخص: " + person.optString("note").trim(), 11, false);
        block("الفترة: " + period + "   |   إعداد الكشف: " + date(System.currentTimeMillis()), 11, false);
        block("الرصيد الافتتاحي: " + number(opening) + " ₪   |   رصيد نهاية الفترة: " + number(closing) + " ₪", 12, true);
        block("الدين المفتوح الحالي (كل الفترات): " + number(Math.round(person.optDouble("receivable") * 100d)) + " ₪", 11, false);
        y += 10; drawTableRow(HEAD, true, true);
        Paint footer = new Paint(); footer.setColor(Color.rgb(215, 233, 226)); canvas.drawLine(MARGIN, HEIGHT - 40, WIDTH - MARGIN, HEIGHT - 40, footer);
        draw("سدد | " + person.optString("name") + " | صفحة " + pageNumber, MARGIN, HEIGHT - 32, BODY, 10, Color.rgb(95, 113, 106), false);
    }

    private void block(String value, int size, boolean bold) { StaticLayout layout = layout(value, BODY, size, Color.rgb(25, 50, 43), bold); canvas.save(); canvas.translate(MARGIN, y); layout.draw(canvas); canvas.restore(); y += layout.getHeight() + 4; }

    private void tableRow(String[] cells, boolean total) {
        float height = rowHeight(cells, false); if (y + height > HEIGHT - 52) startPage(); drawTableRow(cells, false, total);
    }

    private float rowHeight(String[] cells, boolean header) { int height = 28; for (int i = 0; i < COL.length; i++) height = Math.max(height, cellLayout(cells[i], i, header, Color.BLACK, header).getHeight() + 16); return height; }

    private StaticLayout cellLayout(String value, int column, boolean header, int color, boolean bold) {
        int size = header ? 10 : 11;
        if (!header && (column == 0 || column >= 2 && column <= 5)) {
            TextPaint measure = new TextPaint(); measure.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
            measure.setTextSize(size); while (size > 7 && measure.measureText(value) > COL[column] - 10) { measure.setTextSize(--size); }
        }
        return layout(value, COL[column] - 10, size, color, bold);
    }

    private void drawTableRow(String[] cells, boolean header, boolean total) {
        float height = rowHeight(cells, header); float right = WIDTH - MARGIN;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); int ink = header ? Color.WHITE : Color.rgb(20, 51, 42);
        for (int i = 0; i < COL.length; i++) {
            float left = right - COL[i]; paint.setStyle(Paint.Style.FILL); paint.setColor(header ? Color.rgb(6, 75, 66) : total ? Color.rgb(213, 239, 228) : alternate ? Color.rgb(243, 250, 247) : Color.WHITE); canvas.drawRect(left, y, right, y + height, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(.7f); paint.setColor(Color.rgb(199, 221, 211)); canvas.drawRect(left, y, right, y + height, paint);
            StaticLayout text = cellLayout(cells[i], i, header, ink, header || total); canvas.save(); canvas.translate(left + 5, y + 8); text.draw(canvas); canvas.restore(); right = left;
        }
        y += height; if (!header) alternate = !alternate;
    }

    private static StaticLayout layout(String value, int width, int size, int color, boolean bold) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG); paint.setTextSize(size); paint.setColor(color); paint.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return StaticLayout.Builder.obtain(value, 0, value.length(), paint, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setTextDirection(TextDirectionHeuristics.RTL).setIncludePad(true).build();
    }

    private void draw(String value, int x, int top, int width, int size, int color, boolean bold) { canvas.save(); canvas.translate(x, top); layout(value, width, size, color, bold).draw(canvas); canvas.restore(); }
    private void finishPage() { if (page != null) { doc.finishPage(page); page = null; } }
    private static String number(long cents) { return String.format(Locale.US, "%,.2f", cents / 100d); }
    private static String date(long timestamp) { return new SimpleDateFormat("dd/MM/yyyy", Locale.US).format(new Date(timestamp)); }
    private static String dueDate(String value) { try { return date(new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value).getTime()); } catch (Exception e) { return value; } }
    private static ArrayList<String> split(String value, int max) {
        ArrayList<String> result = new ArrayList<>(); String remaining = value;
        while (remaining.length() > max) { int cut = remaining.lastIndexOf(' ', max); if (cut < max / 2) cut = max; if (Character.isHighSurrogate(remaining.charAt(cut - 1))) cut--; result.add(remaining.substring(0, cut)); remaining = remaining.substring(cut).trim(); }
        result.add(remaining); return result;
    }
}
