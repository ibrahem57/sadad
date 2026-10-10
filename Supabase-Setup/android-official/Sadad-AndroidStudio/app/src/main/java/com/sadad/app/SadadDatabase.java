package com.sadad.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Offline ledger. Contact balances are derived from debts minus recorded payments. */
public final class SadadDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "sadad.db";
    private static final int DB_VERSION = 2;

    public SadadDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE contacts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL CHECK(length(trim(name)) > 0)," +
                "phone TEXT NOT NULL DEFAULT ''," +
                "category TEXT NOT NULL DEFAULT 'صديق'," +
                "note TEXT NOT NULL DEFAULT ''," +
                "whatsapp_opt_in INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE debts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "contact_id INTEGER NOT NULL REFERENCES contacts(id) ON DELETE CASCADE," +
                "direction TEXT NOT NULL CHECK(direction IN ('receivable','payable'))," +
                "amount_cents INTEGER NOT NULL CHECK(amount_cents > 0)," +
                "note TEXT NOT NULL DEFAULT ''," +
                "due_date TEXT NOT NULL DEFAULT ''," +
                "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE payments (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "debt_id INTEGER NOT NULL REFERENCES debts(id) ON DELETE CASCADE," +
                "amount_cents INTEGER NOT NULL CHECK(amount_cents > 0)," +
                "method TEXT NOT NULL DEFAULT 'cash' CHECK(method IN ('cash','bank','wallet'))," +
                "note TEXT NOT NULL DEFAULT ''," +
                "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX index_debts_contact ON debts(contact_id, created_at DESC)");
        db.execSQL("CREATE INDEX index_payments_debt ON payments(debt_id, created_at DESC)");
        db.execSQL("CREATE INDEX index_payments_created ON payments(created_at DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE contacts ADD COLUMN whatsapp_opt_in INTEGER NOT NULL DEFAULT 0");
    }

    public synchronized JSONObject saveContact(JSONObject input) {
        String name = clean(input.optString("name"), 120);
        if (name.isEmpty()) throw new IllegalArgumentException("أدخل اسم الشخص.");
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("phone", clean(input.optString("phone"), 40));
        values.put("category", clean(input.optString("category", "صديق"), 40));
        values.put("note", clean(input.optString("note"), 500));
        values.put("whatsapp_opt_in", input.optBoolean("whatsappOptIn", false) ? 1 : 0);
        values.put("created_at", System.currentTimeMillis());
        long id = getWritableDatabase().insertOrThrow("contacts", null, values);
        try {
            return new JSONObject().put("id", id);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized JSONObject saveDebt(JSONObject input) {
        long contactId = input.optLong("contactId", -1L);
        String direction = input.optString("direction", "receivable");
        long amount = toCents(input.optString("amount", "0"));
        if (!"receivable".equals(direction) && !"payable".equals(direction)) {
            throw new IllegalArgumentException("اختر اتجاه الدين.");
        }
        if (amount <= 0L) throw new IllegalArgumentException("أدخل مبلغًا أكبر من صفر.");
        SQLiteDatabase db = getWritableDatabase();
        if (!exists(db, "contacts", contactId)) throw new IllegalArgumentException("اختر الشخص المرتبط بالدين.");
        ContentValues values = new ContentValues();
        values.put("contact_id", contactId);
        values.put("direction", direction);
        values.put("amount_cents", amount);
        values.put("note", clean(input.optString("note"), 500));
        values.put("due_date", clean(input.optString("dueDate"), 20));
        values.put("created_at", System.currentTimeMillis());
        long id = db.insertOrThrow("debts", null, values);
        try {
            return new JSONObject().put("id", id);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized JSONObject savePayment(JSONObject input) {
        long debtId = input.optLong("debtId", -1L);
        long amount = toCents(input.optString("amount", "0"));
        String method = input.optString("method", "cash");
        if (amount <= 0L) throw new IllegalArgumentException("أدخل مبلغ دفعة أكبر من صفر.");
        if (!"cash".equals(method) && !"bank".equals(method) && !"wallet".equals(method)) method = "cash";
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long original = scalarLong(db, "SELECT amount_cents FROM debts WHERE id=?", new String[]{String.valueOf(debtId)});
            if (original <= 0L) throw new IllegalArgumentException("لم يتم العثور على الدين.");
            long paid = scalarLong(db, "SELECT COALESCE(SUM(amount_cents),0) FROM payments WHERE debt_id=?", new String[]{String.valueOf(debtId)});
            long remaining = Math.max(0L, original - paid);
            if (amount > remaining) throw new IllegalArgumentException("مبلغ الدفعة أكبر من الرصيد المتبقي.");
            ContentValues values = new ContentValues();
            values.put("debt_id", debtId);
            values.put("amount_cents", amount);
            values.put("method", method);
            values.put("note", clean(input.optString("note"), 500));
            long createdAt=input.optLong("createdAt",System.currentTimeMillis());
            if(createdAt<=0L||createdAt>System.currentTimeMillis())throw new IllegalArgumentException("تاريخ الدفعة غير صالح.");
            values.put("created_at", createdAt);
            long id = db.insertOrThrow("payments", null, values);
            db.setTransactionSuccessful();
            return new JSONObject().put("id", id);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void updateDebt(JSONObject input) {
        long debtId = input.optLong("debtId", -1L);
        long amount = toCents(input.optString("amount", "0"));
        String direction = input.optString("direction", "receivable");
        if (amount <= 0L) throw new IllegalArgumentException("أدخل مبلغًا أكبر من صفر.");
        if (!"receivable".equals(direction) && !"payable".equals(direction)) {
            throw new IllegalArgumentException("اختر اتجاه الدين.");
        }
        SQLiteDatabase db = getWritableDatabase();
        long paid = scalarLong(db, "SELECT COALESCE(SUM(amount_cents),0) FROM payments WHERE debt_id=?", new String[]{String.valueOf(debtId)});
        if (amount < paid) throw new IllegalArgumentException("لا يمكن أن يقل أصل الدين عن الدفعات المسجلة.");
        ContentValues values = new ContentValues();
        values.put("amount_cents", amount);
        values.put("direction", direction);
        values.put("note", clean(input.optString("note"), 500));
        values.put("due_date", clean(input.optString("dueDate"), 20));
        int changed = db.update("debts", values, "id=?", new String[]{String.valueOf(debtId)});
        if (changed == 0) throw new IllegalArgumentException("لم يتم العثور على الدين.");
    }

    public synchronized void deleteAllData() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("payments", null, null);
            db.delete("debts", null, null);
            db.delete("contacts", null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized JSONObject getSnapshot() throws JSONException {
        SQLiteDatabase db = getReadableDatabase();
        Map<Long, JSONObject> contactById = new HashMap<>();
        JSONArray contacts = new JSONArray();
        try (Cursor cursor = db.rawQuery("SELECT id,name,phone,category,note,whatsapp_opt_in,created_at FROM contacts ORDER BY name COLLATE NOCASE,id", null)) {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                JSONObject contact = new JSONObject();
                contact.put("id", id);
                contact.put("name", cursor.getString(1));
                contact.put("phone", cursor.getString(2));
                contact.put("category", cursor.getString(3));
                contact.put("note", cursor.getString(4));
                contact.put("whatsappOptIn", cursor.getInt(5) == 1);
                contact.put("createdAt", cursor.getLong(6));
                contact.put("receivable", 0d);
                contact.put("payable", 0d);
                contact.put("net", 0d);
                contact.put("transactionCount", 0);
                contact.put("lastActivity", 0L);
                contact.put("latestDebtId", 0L);
                contactById.put(id, contact);
                contacts.put(contact);
            }
        }

        JSONArray debts = new JSONArray();
        List<JSONObject> transactions = new ArrayList<>();
        String debtQuery = "SELECT d.id,d.contact_id,d.direction,d.amount_cents,d.note,d.due_date,d.created_at," +
                "COALESCE(SUM(p.amount_cents),0) FROM debts d LEFT JOIN payments p ON p.debt_id=d.id " +
                "GROUP BY d.id ORDER BY d.created_at DESC,d.id DESC";
        try (Cursor cursor = db.rawQuery(debtQuery, null)) {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                long contactId = cursor.getLong(1);
                String direction = cursor.getString(2);
                long original = cursor.getLong(3);
                long paid = cursor.getLong(7);
                long remaining = Math.max(0L, original - paid);
                JSONObject debt = new JSONObject();
                debt.put("id", id);
                debt.put("contactId", contactId);
                debt.put("direction", direction);
                debt.put("amount", fromCents(original));
                debt.put("paid", fromCents(paid));
                debt.put("remaining", fromCents(remaining));
                debt.put("note", cursor.getString(4));
                debt.put("dueDate", cursor.getString(5));
                debt.put("createdAt", cursor.getLong(6));
                debts.put(debt);
                JSONObject contact = contactById.get(contactId);
                if (contact != null) {
                    String key = "receivable".equals(direction) ? "receivable" : "payable";
                    contact.put(key, contact.optDouble(key) + fromCents(remaining));
                    contact.put("transactionCount", contact.optInt("transactionCount") + 1);
                    if (cursor.getLong(6) > contact.optLong("lastActivity")) {
                        contact.put("lastActivity", cursor.getLong(6));
                        contact.put("latestDebtId", id);
                    }
                    JSONObject tx = new JSONObject();
                    tx.put("id", "d" + id);
                    tx.put("debtId", id);
                    tx.put("contactId", contactId);
                    tx.put("contactName", contact.optString("name"));
                    tx.put("kind", "debt");
                    tx.put("direction", direction);
                    tx.put("amount", fromCents(original));
                    tx.put("note", cursor.getString(4));
                    tx.put("method", "");
                    tx.put("createdAt", cursor.getLong(6));
                    transactions.add(tx);
                }
            }
        }

        JSONArray payments = new JSONArray();
        String paymentQuery = "SELECT p.id,p.debt_id,p.amount_cents,p.method,p.note,p.created_at," +
                "d.contact_id,d.direction,c.name FROM payments p JOIN debts d ON d.id=p.debt_id " +
                "JOIN contacts c ON c.id=d.contact_id ORDER BY p.created_at DESC,p.id DESC";
        try (Cursor cursor = db.rawQuery(paymentQuery, null)) {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                long debtId = cursor.getLong(1);
                long contactId = cursor.getLong(6);
                String direction = cursor.getString(7);
                double amount = fromCents(cursor.getLong(2));
                JSONObject payment = new JSONObject();
                payment.put("id", id);
                payment.put("debtId", debtId);
                payment.put("contactId", contactId);
                payment.put("amount", amount);
                payment.put("method", cursor.getString(3));
                payment.put("note", cursor.getString(4));
                payment.put("createdAt", cursor.getLong(5));
                payment.put("direction", direction);
                payments.put(payment);
                JSONObject contact = contactById.get(contactId);
                if (contact != null) {
                    contact.put("transactionCount", contact.optInt("transactionCount") + 1);
                    if (cursor.getLong(5) > contact.optLong("lastActivity")) contact.put("lastActivity", cursor.getLong(5));
                    JSONObject tx = new JSONObject();
                    tx.put("id", "p" + id);
                    tx.put("paymentId", id);
                    tx.put("debtId", debtId);
                    tx.put("contactId", contactId);
                    tx.put("contactName", cursor.getString(8));
                    tx.put("kind", "payment");
                    tx.put("direction", direction);
                    tx.put("amount", amount);
                    tx.put("note", cursor.getString(4));
                    tx.put("method", cursor.getString(3));
                    tx.put("createdAt", cursor.getLong(5));
                    transactions.add(tx);
                }
            }
        }

        for (int i = 0; i < contacts.length(); i++) {
            JSONObject contact = contacts.getJSONObject(i);
            contact.put("net", contact.optDouble("receivable") - contact.optDouble("payable"));
        }
        Collections.sort(transactions, Comparator.comparingLong((JSONObject row) -> row.optLong("createdAt")).reversed());
        JSONArray transactionArray = new JSONArray();
        for (JSONObject transaction : transactions) transactionArray.put(transaction);

        long receivable = scalarLong(db,
                "SELECT COALESCE(SUM(d.amount_cents-COALESCE(x.paid,0)),0) FROM debts d " +
                        "LEFT JOIN (SELECT debt_id,SUM(amount_cents) paid FROM payments GROUP BY debt_id) x ON x.debt_id=d.id " +
                        "WHERE d.direction='receivable'", null);
        long payable = scalarLong(db,
                "SELECT COALESCE(SUM(d.amount_cents-COALESCE(x.paid,0)),0) FROM debts d " +
                        "LEFT JOIN (SELECT debt_id,SUM(amount_cents) paid FROM payments GROUP BY debt_id) x ON x.debt_id=d.id " +
                        "WHERE d.direction='payable'", null);
        return new JSONObject()
                .put("contacts", contacts)
                .put("debts", debts)
                .put("payments", payments)
                .put("transactions", transactionArray)
                .put("totals", new JSONObject()
                        .put("receivable", fromCents(receivable))
                        .put("payable", fromCents(payable))
                        .put("net", fromCents(receivable - payable)));
    }

    /** Replaces the local cache with the authenticated server snapshot in one transaction. */
    public synchronized void importSnapshot(JSONObject snapshot) throws JSONException {
        SQLiteDatabase db = getWritableDatabase();
        JSONArray contacts = snapshot.optJSONArray("contacts");
        JSONArray debts = snapshot.optJSONArray("debts");
        JSONArray payments = snapshot.optJSONArray("payments");
        db.beginTransaction();
        try {
            db.delete("payments", null, null);
            db.delete("debts", null, null);
            db.delete("contacts", null, null);
            if (contacts != null) for (int i = 0; i < contacts.length(); i++) {
                JSONObject row = contacts.getJSONObject(i);
                ContentValues v = new ContentValues();
                v.put("id", row.getLong("id")); v.put("name", clean(row.optString("name"), 120));
                v.put("phone", clean(row.optString("phone"), 40)); v.put("category", clean(row.optString("category"), 40));
                v.put("note", clean(row.optString("note"), 500)); v.put("whatsapp_opt_in", row.optBoolean("whatsappOptIn") ? 1 : 0);
                v.put("created_at", row.optLong("createdAt", System.currentTimeMillis()));
                db.insertOrThrow("contacts", null, v);
            }
            if (debts != null) for (int i = 0; i < debts.length(); i++) {
                JSONObject row = debts.getJSONObject(i); ContentValues v = new ContentValues();
                v.put("id", row.getLong("id")); v.put("contact_id", row.getLong("contactId"));
                v.put("direction", row.optString("direction", "receivable")); v.put("amount_cents", toCents(row.optString("amount", "0")));
                v.put("note", clean(row.optString("note"), 500)); v.put("due_date", clean(row.optString("dueDate"), 20));
                v.put("created_at", row.optLong("createdAt", System.currentTimeMillis())); db.insertOrThrow("debts", null, v);
            }
            if (payments != null) for (int i = 0; i < payments.length(); i++) {
                JSONObject row = payments.getJSONObject(i); ContentValues v = new ContentValues();
                v.put("id", row.getLong("id")); v.put("debt_id", row.getLong("debtId")); v.put("amount_cents", toCents(row.optString("amount", "0")));
                v.put("method", row.optString("method", "cash")); v.put("note", clean(row.optString("note"), 500));
                v.put("created_at", row.optLong("createdAt", System.currentTimeMillis())); db.insertOrThrow("payments", null, v);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized boolean hasContacts() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT 1 FROM contacts LIMIT 1", null)) { return cursor.moveToFirst(); }
    }

    private static boolean exists(SQLiteDatabase db, String table, long id) {
        try (Cursor cursor = db.rawQuery("SELECT 1 FROM " + table + " WHERE id=? LIMIT 1", new String[]{String.valueOf(id)})) {
            return cursor.moveToFirst();
        }
    }

    private static long scalarLong(SQLiteDatabase db, String sql, String[] args) {
        try (Cursor cursor = db.rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    private static long toCents(String value) {
        try {
            BigDecimal amount = new BigDecimal(value.trim().replace(',', '.'));
            long cents = amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
            if (cents < 0L || cents > 9_000_000_000_000L) throw new NumberFormatException("out of range");
            return cents;
        } catch (Exception e) {
            throw new IllegalArgumentException("أدخل مبلغًا صحيحًا.");
        }
    }

    private static double fromCents(long cents) {
        return cents / 100.0d;
    }

    private static String clean(String value, int limit) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.length() > limit) trimmed = trimmed.substring(0, limit);
        return trimmed;
    }
}
