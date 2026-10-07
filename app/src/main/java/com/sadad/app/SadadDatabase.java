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
    private static final int DB_VERSION = 6;

    /** Includes changes from this connection and other connections, without rebuilding the ledger. */
    synchronized long changeToken() {
        long version = 0, changes = 0;
        try (Cursor c = getReadableDatabase().rawQuery("PRAGMA data_version", null)) { if (c.moveToFirst()) version = c.getLong(0); }
        try (Cursor c = getReadableDatabase().rawQuery("SELECT total_changes()", null)) { if (c.moveToFirst()) changes = c.getLong(0); }
        return (version << 32) ^ changes;
    }

    public SadadDatabase(Context context) {
        this(context, DB_NAME);
    }

    public SadadDatabase(Context context, String databaseName) {
        super(context, databaseName == null || databaseName.trim().isEmpty() ? DB_NAME : databaseName, null, DB_VERSION);
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
                "credit_limit_cents INTEGER NOT NULL DEFAULT 0," +
                "created_by TEXT NOT NULL DEFAULT ''," +
                "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE debts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "contact_id INTEGER NOT NULL REFERENCES contacts(id) ON DELETE CASCADE," +
                "direction TEXT NOT NULL CHECK(direction IN ('receivable','payable'))," +
                "amount_cents INTEGER NOT NULL CHECK(amount_cents > 0)," +
                "note TEXT NOT NULL DEFAULT ''," +
                "due_date TEXT NOT NULL DEFAULT ''," +
                "created_by TEXT NOT NULL DEFAULT ''," +
                "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE payments (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "debt_id INTEGER NOT NULL REFERENCES debts(id) ON DELETE CASCADE," +
                "amount_cents INTEGER NOT NULL CHECK(amount_cents > 0)," +
                "method TEXT NOT NULL DEFAULT 'cash' CHECK(method IN ('cash','bank','wallet','wallet_jawwal','wallet_palpay'))," +
                "note TEXT NOT NULL DEFAULT ''," +
                "created_by TEXT NOT NULL DEFAULT ''," +
                "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX index_debts_contact ON debts(contact_id, created_at DESC)");
        db.execSQL("CREATE INDEX index_payments_debt ON payments(debt_id, created_at DESC)");
        db.execSQL("CREATE INDEX index_payments_created ON payments(created_at DESC)");
        db.execSQL("CREATE TABLE trash_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "kind TEXT NOT NULL," +
                "title TEXT NOT NULL," +
                "payload TEXT NOT NULL," +
                "deleted_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX index_trash_deleted ON trash_items(deleted_at DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE contacts ADD COLUMN whatsapp_opt_in INTEGER NOT NULL DEFAULT 0");
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE trash_items (id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,title TEXT NOT NULL,payload TEXT NOT NULL,deleted_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX index_trash_deleted ON trash_items(deleted_at DESC)");
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE contacts ADD COLUMN created_by TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE debts ADD COLUMN created_by TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE payments ADD COLUMN created_by TEXT NOT NULL DEFAULT ''");
        }
        if (oldVersion < 5) db.execSQL("ALTER TABLE contacts ADD COLUMN credit_limit_cents INTEGER NOT NULL DEFAULT 0");
        if (oldVersion < 6) {
            db.execSQL("CREATE TABLE payments_v6 (id INTEGER PRIMARY KEY AUTOINCREMENT,debt_id INTEGER NOT NULL REFERENCES debts(id) ON DELETE CASCADE,amount_cents INTEGER NOT NULL CHECK(amount_cents > 0),method TEXT NOT NULL DEFAULT 'cash' CHECK(method IN ('cash','bank','wallet','wallet_jawwal','wallet_palpay')),note TEXT NOT NULL DEFAULT '',created_by TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL)");
            db.execSQL("INSERT INTO payments_v6 SELECT id,debt_id,amount_cents,method,note,created_by,created_at FROM payments");
            db.execSQL("DROP TABLE payments"); db.execSQL("ALTER TABLE payments_v6 RENAME TO payments");
            db.execSQL("CREATE INDEX index_payments_debt ON payments(debt_id, created_at DESC)"); db.execSQL("CREATE INDEX index_payments_created ON payments(created_at DESC)");
        }
    }

    public synchronized JSONObject saveContact(JSONObject input) {
        String name = clean(input.optString("name"), 120);
        if (name.isEmpty()) throw new IllegalArgumentException("أدخل اسم الشخص.");
        String phone = clean(input.optString("phone"), 40);
        if (hasDuplicateContact(name, phone, -1L)) throw new IllegalArgumentException("المستخدم موجود مسبقاً بنفس الاسم ورقم الهاتف.");
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("phone", phone);
        values.put("category", clean(input.optString("category", "صديق"), 40));
        values.put("note", clean(input.optString("note"), 500));
        values.put("whatsapp_opt_in", input.optBoolean("whatsappOptIn", false) ? 1 : 0);
        values.put("credit_limit_cents", toCents(input.optString("creditLimit", "0")));
        values.put("created_by", clean(input.optString("createdBy"), 120));
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
        if (!"receivable".equals(direction)) throw new IllegalArgumentException("يسجل هذا الإصدار الديون المستحقة لك فقط.");
        if (amount <= 0L) throw new IllegalArgumentException("أدخل مبلغًا أكبر من صفر.");
        SQLiteDatabase db = getWritableDatabase();
        if (!exists(db, "contacts", contactId)) throw new IllegalArgumentException("اختر الشخص المرتبط بالدين.");
        ContentValues values = new ContentValues();
        values.put("contact_id", contactId);
        values.put("direction", direction);
        values.put("amount_cents", amount);
        values.put("note", clean(input.optString("note"), 500));
        values.put("due_date", clean(input.optString("dueDate"), 20));
        values.put("created_by", clean(input.optString("createdBy"), 120));
        values.put("created_at", System.currentTimeMillis());
        long id = db.insertOrThrow("debts", null, values);
        try {
            return new JSONObject().put("id", id);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized JSONObject savePayment(JSONObject input) {
        long contactId = input.optLong("contactId", -1L);
        long amount = toCents(input.optString("amount", "0"));
        String method = input.optString("method", "cash");
        if (amount <= 0L) throw new IllegalArgumentException("أدخل مبلغ دفعة أكبر من صفر.");
        if (!"cash".equals(method) && !"bank".equals(method) && !"wallet".equals(method) && !"wallet_jawwal".equals(method) && !"wallet_palpay".equals(method)) method = "cash";
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (!exists(db, "contacts", contactId)) throw new IllegalArgumentException("لم يتم العثور على الشخص.");
            String note = clean(input.optString("note"), 500);
            long createdAt = input.optLong("createdAt", System.currentTimeMillis());
            if (createdAt <= 0L || createdAt > System.currentTimeMillis()) throw new IllegalArgumentException("تاريخ الدفعة غير صالح.");
            if (hasRecentDuplicatePayment(db, contactId, amount, method, createdAt))
                throw new IllegalArgumentException("سُجلت دفعة مطابقة لهذا الشخص خلال آخر دقيقة. انتظر دقيقة قبل إعادة المحاولة لتجنب التكرار.");
            long remainingToAllocate = amount;
            long firstId = -1L;
            String openDebts = "SELECT d.id,d.amount_cents,COALESCE(SUM(p.amount_cents),0) " +
                    "FROM debts d LEFT JOIN payments p ON p.debt_id=d.id " +
                    "WHERE d.contact_id=? AND d.direction='receivable' " +
                    "GROUP BY d.id ORDER BY d.created_at ASC,d.id ASC";
            try (Cursor cursor = db.rawQuery(openDebts, new String[]{String.valueOf(contactId)})) {
                while (cursor.moveToNext() && remainingToAllocate > 0L) {
                    long debtId = cursor.getLong(0);
                    long outstanding = Math.max(0L, cursor.getLong(1) - cursor.getLong(2));
                    long allocation = Math.min(outstanding, remainingToAllocate);
                    if (allocation <= 0L) continue;
                    ContentValues values = new ContentValues();
                    values.put("debt_id", debtId);
                    values.put("amount_cents", allocation);
                    values.put("method", method);
                    values.put("note", note);
                    values.put("created_by", clean(input.optString("createdBy"), 120));
                    values.put("created_at", createdAt);
                    long id = db.insertOrThrow("payments", null, values);
                    if (firstId < 0L) firstId = id;
                    remainingToAllocate -= allocation;
                }
            }
            if (remainingToAllocate > 0L) throw new IllegalArgumentException("مبلغ الدفعة أكبر من إجمالي الدين الحالي للشخص.");
            db.setTransactionSuccessful();
            return new JSONObject().put("id", firstId).put("contactId", contactId)
                    .put("amount", fromCents(amount)).put("createdAt", createdAt);
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
        if (!"receivable".equals(direction)) throw new IllegalArgumentException("يسجل هذا الإصدار الديون المستحقة لك فقط.");
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

    /** Atomically creates a contact and, when entered, the contact's first receivable debt. */
    public synchronized JSONObject saveContactWithInitialDebt(JSONObject input, String amountText, String dueDate, String debtNote) throws JSONException {
        String name = clean(input.optString("name"), 120);
        if (name.isEmpty()) throw new IllegalArgumentException("أدخل اسم الشخص.");
        String phone = clean(input.optString("phone"), 40);
        if (hasDuplicateContact(name, phone, -1L)) throw new IllegalArgumentException("المستخدم موجود مسبقاً بنفس الاسم ورقم الهاتف.");
        String amountValue = amountText == null ? "" : amountText.trim();
        long amount = amountValue.isEmpty() ? 0L : toCents(amountValue);
        if (!amountValue.isEmpty() && amount <= 0L) throw new IllegalArgumentException("يجب أن يكون مبلغ الدين أكبر من صفر.");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues contact = new ContentValues();
            contact.put("name", name); contact.put("phone", phone);
            contact.put("category", clean(input.optString("category", "عميل"), 40));
            contact.put("note", clean(input.optString("note"), 500));
            contact.put("whatsapp_opt_in", input.optBoolean("whatsappOptIn", false) ? 1 : 0);
            contact.put("credit_limit_cents", toCents(input.optString("creditLimit", "0")));
            contact.put("created_by", clean(input.optString("createdBy"), 120));
            contact.put("created_at", System.currentTimeMillis());
            long contactId = db.insertOrThrow("contacts", null, contact);
            long debtId = 0L;
            if (amount > 0L) {
                ContentValues debt = new ContentValues();
                debt.put("contact_id", contactId); debt.put("direction", "receivable"); debt.put("amount_cents", amount);
                debt.put("note", clean(debtNote, 500)); debt.put("due_date", clean(dueDate, 20));
                debt.put("created_by", clean(input.optString("createdBy"), 120));
                debt.put("created_at", System.currentTimeMillis());
                debtId = db.insertOrThrow("debts", null, debt);
            }
            db.setTransactionSuccessful();
            return new JSONObject().put("id", contactId).put("debtId", debtId);
        } finally { db.endTransaction(); }
    }

    public synchronized boolean deleteContact(long contactId) {
        try { return deleteContactToTrash(contactId); }
        catch (JSONException e) { throw new IllegalStateException("تعذر حفظ السجل في سلة المحذوفات.", e); }
    }

    /** Archives and removes a contact's fully settled ledger while keeping the contact itself. */
    public synchronized boolean deleteContactLedgerToTrash(long contactId) throws JSONException {
        if (contactId <= 0L) return false;
        SQLiteDatabase db = getWritableDatabase();
        JSONObject snapshot = getSnapshot();
        JSONObject person = null;
        JSONArray contacts = snapshot.optJSONArray("contacts");
        if (contacts != null) for (int i = 0; i < contacts.length(); i++) {
            JSONObject row = contacts.optJSONObject(i);
            if (row != null && row.optLong("id") == contactId) { person = row; break; }
        }
        if (person == null) return false;
        long remaining = scalarLong(db,
                "SELECT COALESCE(SUM(MAX(d.amount_cents-COALESCE(p.paid,0),0)),0) FROM debts d " +
                        "LEFT JOIN (SELECT debt_id,SUM(amount_cents) paid FROM payments GROUP BY debt_id) p ON p.debt_id=d.id " +
                        "WHERE d.contact_id=? AND d.direction IN ('receivable','payable')",
                new String[]{String.valueOf(contactId)});
        if (remaining > 0L) throw new IllegalStateException("لا يمكن حذف السجل لوجود دين متبقٍ.");
        JSONArray debts = new JSONArray(), payments = new JSONArray();
        JSONArray allDebts = snapshot.optJSONArray("debts"), allPayments = snapshot.optJSONArray("payments");
        if (allDebts != null) for (int i = 0; i < allDebts.length(); i++) {
            JSONObject row = allDebts.optJSONObject(i);
            if (row != null && row.optLong("contactId") == contactId) debts.put(row);
        }
        if (debts.length() == 0) return false;
        if (allPayments != null) for (int i = 0; i < allPayments.length(); i++) {
            JSONObject row = allPayments.optJSONObject(i);
            if (row != null && row.optLong("contactId") == contactId) payments.put(row);
        }
        JSONObject payload = new JSONObject().put("contact", person).put("debts", debts).put("payments", payments);
        db.beginTransaction();
        try {
            ContentValues archived = new ContentValues(); archived.put("kind", "ledger");
            archived.put("title", "سجل " + person.optString("name", "شخص"));
            archived.put("payload", payload.toString()); archived.put("deleted_at", System.currentTimeMillis());
            db.insertOrThrow("trash_items", null, archived);
            int deleted = db.delete("debts", "contact_id=?", new String[]{String.valueOf(contactId)});
            if (deleted == 0) return false;
            db.setTransactionSuccessful();
            return true;
        } finally { db.endTransaction(); }
    }

    /** Archives a contact and its full ledger in local trash before cascading the deletion. */
    public synchronized boolean deleteContactToTrash(long contactId) throws JSONException {
        if (contactId <= 0L) return false;
        SQLiteDatabase db = getWritableDatabase();
        JSONObject snapshot = getSnapshot();
        JSONObject person = null;
        JSONArray contacts = snapshot.optJSONArray("contacts");
        if (contacts != null) for (int i = 0; i < contacts.length(); i++) {
            JSONObject row = contacts.optJSONObject(i);
            if (row != null && row.optLong("id") == contactId) { person = row; break; }
        }
        if (person == null) return false;
        long remaining = scalarLong(db,
                "SELECT COALESCE(SUM(MAX(d.amount_cents-COALESCE(p.paid,0),0)),0) FROM debts d " +
                        "LEFT JOIN (SELECT debt_id,SUM(amount_cents) paid FROM payments GROUP BY debt_id) p ON p.debt_id=d.id " +
                        "WHERE d.contact_id=? AND d.direction IN ('receivable','payable')",
                new String[]{String.valueOf(contactId)});
        if (remaining > 0L) throw new IllegalStateException("لا يمكن حذف الشخص لوجود دين متبقٍ.");

        JSONArray debts = new JSONArray(), payments = new JSONArray();
        JSONArray allDebts = snapshot.optJSONArray("debts"), allPayments = snapshot.optJSONArray("payments");
        if (allDebts != null) for (int i = 0; i < allDebts.length(); i++) {
            JSONObject row = allDebts.optJSONObject(i);
            if (row != null && row.optLong("contactId") == contactId) debts.put(row);
        }
        if (allPayments != null) for (int i = 0; i < allPayments.length(); i++) {
            JSONObject row = allPayments.optJSONObject(i);
            if (row != null && row.optLong("contactId") == contactId) payments.put(row);
        }
        JSONObject payload = new JSONObject().put("contact", person).put("debts", debts).put("payments", payments);
        db.beginTransaction();
        try {
            ContentValues archived = new ContentValues();
            archived.put("kind", "contact"); archived.put("title", person.optString("name", "شخص"));
            archived.put("payload", payload.toString()); archived.put("deleted_at", System.currentTimeMillis());
            db.insertOrThrow("trash_items", null, archived);
            int deleted = db.delete("contacts", "id=?", new String[]{String.valueOf(contactId)});
            if (deleted == 0) return false;
            db.setTransactionSuccessful();
            return true;
        } finally { db.endTransaction(); }
    }

    public synchronized void addTrashItem(String kind, String title, JSONObject payload) {
        if (payload == null) return;
        ContentValues values = new ContentValues();
        values.put("kind", clean(kind, 24)); values.put("title", clean(title, 160));
        values.put("payload", payload.toString()); values.put("deleted_at", System.currentTimeMillis());
        getWritableDatabase().insertOrThrow("trash_items", null, values);
    }

    public synchronized JSONArray getTrashItems() throws JSONException {
        JSONArray result = new JSONArray();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id,kind,title,payload,deleted_at FROM trash_items ORDER BY deleted_at DESC,id DESC", null)) {
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("id", cursor.getLong(0)); item.put("kind", cursor.getString(1));
                item.put("title", cursor.getString(2)); item.put("payload", new JSONObject(cursor.getString(3)));
                item.put("deletedAt", cursor.getLong(4)); result.put(item);
            }
        }
        return result;
    }

    /** Restores the contact and every debt/payment row from its local trash snapshot. */
    public synchronized boolean restoreTrashContact(long trashId) throws JSONException {
        SQLiteDatabase db = getWritableDatabase();
        String encoded = null;
        try (Cursor cursor = db.rawQuery("SELECT payload FROM trash_items WHERE id=? AND kind='contact'", new String[]{String.valueOf(trashId)})) {
            if (cursor.moveToFirst()) encoded = cursor.getString(0);
        }
        if (encoded == null) return false;
        JSONObject payload = new JSONObject(encoded), person = payload.optJSONObject("contact");
        if (person == null) return false;
        if (hasDuplicateContact(clean(person.optString("name"), 120), clean(person.optString("phone"), 40), -1L))
            throw new IllegalStateException("يوجد شخص بالاسم ورقم الهاتف نفسيهما بالفعل.");
        db.beginTransaction();
        try {
            ContentValues contact = new ContentValues();
            contact.put("name", clean(person.optString("name"), 120)); contact.put("phone", clean(person.optString("phone"), 40));
            contact.put("category", clean(person.optString("category", "عميل"), 40)); contact.put("note", clean(person.optString("note"), 500));
            contact.put("whatsapp_opt_in", person.optBoolean("whatsappOptIn") ? 1 : 0);
            contact.put("credit_limit_cents", toCents(person.optString("creditLimit", "0")));
            contact.put("created_at", person.optLong("createdAt", System.currentTimeMillis()));
            long restoredContactId = db.insertOrThrow("contacts", null, contact);
            Map<Long, Long> debtIdMap = new HashMap<>();
            JSONArray debts = payload.optJSONArray("debts");
            if (debts != null) for (int i = 0; i < debts.length(); i++) {
                JSONObject row = debts.optJSONObject(i); if (row == null) continue;
                ContentValues debt = new ContentValues(); debt.put("contact_id", restoredContactId);
                debt.put("direction", row.optString("direction", "receivable"));
                debt.put("amount_cents", toCents(row.optString("amount", "0")));
                debt.put("note", clean(row.optString("note"), 500)); debt.put("due_date", clean(row.optString("dueDate"), 20));
                debt.put("created_at", row.optLong("createdAt", System.currentTimeMillis()));
                debtIdMap.put(row.optLong("id"), db.insertOrThrow("debts", null, debt));
            }
            JSONArray payments = payload.optJSONArray("payments");
            if (payments != null) for (int i = 0; i < payments.length(); i++) {
                JSONObject row = payments.optJSONObject(i); if (row == null) continue;
                Long debtId = debtIdMap.get(row.optLong("debtId")); if (debtId == null) continue;
                ContentValues payment = new ContentValues(); payment.put("debt_id", debtId);
                payment.put("amount_cents", toCents(row.optString("amount", "0")));
                payment.put("method", row.optString("method", "cash")); payment.put("note", clean(row.optString("note"), 500));
                payment.put("created_at", row.optLong("createdAt", System.currentTimeMillis()));
                db.insertOrThrow("payments", null, payment);
            }
            db.delete("trash_items", "id=?", new String[]{String.valueOf(trashId)});
            db.setTransactionSuccessful();
            return true;
        } finally { db.endTransaction(); }
    }

    /** Restores a separately deleted ledger only when its original contact still exists. */
    public synchronized boolean restoreTrashLedger(long trashId) throws JSONException {
        SQLiteDatabase db = getWritableDatabase();
        String encoded = null;
        try (Cursor cursor = db.rawQuery("SELECT payload FROM trash_items WHERE id=? AND kind='ledger'", new String[]{String.valueOf(trashId)})) {
            if (cursor.moveToFirst()) encoded = cursor.getString(0);
        }
        if (encoded == null) return false;
        JSONObject payload = new JSONObject(encoded), person = payload.optJSONObject("contact");
        if (person == null) return false;
        long targetContactId = person.optLong("id");
        if (!exists(db, "contacts", targetContactId)) {
            targetContactId = findContactId(db, clean(person.optString("name"), 120), clean(person.optString("phone"), 40));
        }
        if (targetContactId <= 0L) return false;
        db.beginTransaction();
        try {
            Map<Long, Long> debtIdMap = new HashMap<>();
            JSONArray debts = payload.optJSONArray("debts");
            if (debts != null) for (int i = 0; i < debts.length(); i++) {
                JSONObject row = debts.optJSONObject(i); if (row == null) continue;
                ContentValues debt = new ContentValues(); debt.put("contact_id", targetContactId);
                debt.put("direction", row.optString("direction", "receivable"));
                debt.put("amount_cents", toCents(row.optString("amount", "0")));
                debt.put("note", clean(row.optString("note"), 500)); debt.put("due_date", clean(row.optString("dueDate"), 20));
                debt.put("created_at", row.optLong("createdAt", System.currentTimeMillis()));
                debtIdMap.put(row.optLong("id"), db.insertOrThrow("debts", null, debt));
            }
            JSONArray payments = payload.optJSONArray("payments");
            if (payments != null) for (int i = 0; i < payments.length(); i++) {
                JSONObject row = payments.optJSONObject(i); if (row == null) continue;
                Long debtId = debtIdMap.get(row.optLong("debtId")); if (debtId == null) continue;
                ContentValues payment = new ContentValues(); payment.put("debt_id", debtId);
                payment.put("amount_cents", toCents(row.optString("amount", "0")));
                payment.put("method", row.optString("method", "cash")); payment.put("note", clean(row.optString("note"), 500));
                payment.put("created_at", row.optLong("createdAt", System.currentTimeMillis()));
                db.insertOrThrow("payments", null, payment);
            }
            db.delete("trash_items", "id=?", new String[]{String.valueOf(trashId)});
            db.setTransactionSuccessful(); return true;
        } finally { db.endTransaction(); }
    }

    public synchronized boolean deleteTrashItem(long trashId) {
        return getWritableDatabase().delete("trash_items", "id=?", new String[]{String.valueOf(trashId)}) > 0;
    }

    public synchronized void updateContact(JSONObject input) {
        long id = input.optLong("id", -1L);
        String name = clean(input.optString("name"), 120);
        if (id <= 0L || name.isEmpty()) throw new IllegalArgumentException("أدخل اسم الشخص.");
        String phone = clean(input.optString("phone"), 40);
        if (hasDuplicateContact(name, phone, id)) throw new IllegalArgumentException("المستخدم موجود مسبقاً بنفس الاسم ورقم الهاتف.");
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("phone", phone);
        values.put("category", clean(input.optString("category", "عميل"), 40));
        values.put("note", clean(input.optString("note"), 500));
        values.put("credit_limit_cents", toCents(input.optString("creditLimit", "0")));
        int changed = getWritableDatabase().update("contacts", values, "id=?", new String[]{String.valueOf(id)});
        if (changed == 0) throw new IllegalArgumentException("لم يتم العثور على الشخص.");
    }

    /** Removes only the four seeded contacts from older local test builds. */
    public synchronized void removeLegacySampleRecords() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("contacts", "(name=? AND note=?) OR (name=? AND note=?) OR (name=? AND note=?) OR (name=? AND note=?)",
                    new String[]{"أحمد خالد", "بيانات تجريبية", "سماح يوسف", "حساب تجريبي للموردين",
                            "مروان حمدان", "سجل تجريبي آخر", "رنا إبراهيم", "حساب مسدد للتجربة"});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized JSONObject getSnapshot() throws JSONException {
        SQLiteDatabase db = getReadableDatabase();
        Map<Long, JSONObject> contactById = new HashMap<>();
        JSONArray contacts = new JSONArray();
        try (Cursor cursor = db.rawQuery("SELECT id,name,phone,category,note,whatsapp_opt_in,credit_limit_cents,created_at,created_by FROM contacts ORDER BY name COLLATE NOCASE,id", null)) {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                JSONObject contact = new JSONObject();
                contact.put("id", id);
                contact.put("name", cursor.getString(1));
                contact.put("phone", cursor.getString(2));
                contact.put("category", cursor.getString(3));
                contact.put("note", cursor.getString(4));
                contact.put("whatsappOptIn", cursor.getInt(5) == 1);
                contact.put("creditLimit", fromCents(cursor.getLong(6)));
                contact.put("createdAt", cursor.getLong(7));
                contact.put("createdBy", cursor.getString(8));
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
                "COALESCE(SUM(p.amount_cents),0),d.created_by FROM debts d LEFT JOIN payments p ON p.debt_id=d.id " +
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
                debt.put("createdBy", cursor.getString(8));
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
                    tx.put("dueDate", cursor.getString(5));
                    tx.put("method", "");
                    tx.put("createdAt", cursor.getLong(6));
                    tx.put("createdBy", cursor.getString(8));
                    transactions.add(tx);
                }
            }
        }

        JSONArray payments = new JSONArray();
        String paymentQuery = "SELECT p.id,p.debt_id,p.amount_cents,p.method,p.note,p.created_at," +
                "d.contact_id,d.direction,c.name,p.created_by FROM payments p JOIN debts d ON d.id=p.debt_id " +
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
                payment.put("createdBy", cursor.getString(9));
                payment.put("direction", direction);
                payments.put(payment);
            }
        }

        String groupedPaymentQuery = "SELECT MIN(p.id),MIN(p.debt_id),d.contact_id,d.direction,SUM(p.amount_cents)," +
                "p.note,p.method,p.created_at,c.name,p.created_by FROM payments p JOIN debts d ON d.id=p.debt_id " +
                "JOIN contacts c ON c.id=d.contact_id " +
                "GROUP BY p.created_at,d.contact_id,d.direction,p.note,p.method " +
                "ORDER BY p.created_at DESC,MIN(p.id) DESC";
        try (Cursor cursor = db.rawQuery(groupedPaymentQuery, null)) {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0), debtId = cursor.getLong(1), contactId = cursor.getLong(2), createdAt = cursor.getLong(7);
                String direction = cursor.getString(3);
                JSONObject contact = contactById.get(contactId);
                if (contact == null) continue;
                contact.put("transactionCount", contact.optInt("transactionCount") + 1);
                if (createdAt > contact.optLong("lastActivity")) contact.put("lastActivity", createdAt);
                JSONObject tx = new JSONObject();
                tx.put("id", "p" + id);
                tx.put("paymentId", id);
                tx.put("debtId", debtId);
                tx.put("contactId", contactId);
                tx.put("contactName", cursor.getString(8));
                tx.put("kind", "payment");
                tx.put("direction", direction);
                tx.put("amount", fromCents(cursor.getLong(4)));
                tx.put("note", cursor.getString(5));
                tx.put("method", cursor.getString(6));
                tx.put("createdAt", createdAt);
                tx.put("createdBy", cursor.getString(9));
                transactions.add(tx);
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
                v.put("credit_limit_cents", toCents(row.optString("creditLimit", "0")));
                v.put("created_by", clean(row.optString("createdBy"), 120));
                v.put("created_at", row.optLong("createdAt", System.currentTimeMillis()));
                db.insertOrThrow("contacts", null, v);
            }
            if (debts != null) for (int i = 0; i < debts.length(); i++) {
                JSONObject row = debts.getJSONObject(i); ContentValues v = new ContentValues();
                v.put("id", row.getLong("id")); v.put("contact_id", row.getLong("contactId"));
                v.put("direction", row.optString("direction", "receivable")); v.put("amount_cents", toCents(row.optString("amount", "0")));
                v.put("note", clean(row.optString("note"), 500)); v.put("due_date", clean(row.optString("dueDate"), 20));
                v.put("created_by", clean(row.optString("createdBy"), 120));
                v.put("created_at", row.optLong("createdAt", System.currentTimeMillis())); db.insertOrThrow("debts", null, v);
            }
            if (payments != null) for (int i = 0; i < payments.length(); i++) {
                JSONObject row = payments.getJSONObject(i); ContentValues v = new ContentValues();
                v.put("id", row.getLong("id")); v.put("debt_id", row.getLong("debtId")); v.put("amount_cents", toCents(row.optString("amount", "0")));
                v.put("method", row.optString("method", "cash")); v.put("note", clean(row.optString("note"), 500));
                v.put("created_by", clean(row.optString("createdBy"), 120));
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

    private static long findContactId(SQLiteDatabase db, String name, String phone) {
        try (Cursor cursor = db.rawQuery("SELECT id FROM contacts WHERE name=? AND phone=? ORDER BY id LIMIT 1",
                new String[]{name, phone})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    private static long scalarLong(SQLiteDatabase db, String sql, String[] args) {
        try (Cursor cursor = db.rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    private static long toCents(String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        try {
            BigDecimal amount = new BigDecimal(westernDigits(value.trim()).replace(',', '.'));
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
        String trimmed = westernDigits(value.trim());
        if (trimmed.length() > limit) trimmed = trimmed.substring(0, limit);
        return trimmed;
    }

    private static boolean hasRecentDuplicatePayment(SQLiteDatabase db, long contactId, long amount,
                                                     String method, long createdAt) {
        String sql = "SELECT 1 FROM payments p JOIN debts d ON d.id=p.debt_id " +
                "WHERE d.contact_id=? AND p.method=? AND p.created_at BETWEEN ? AND ? " +
                "GROUP BY p.created_at HAVING SUM(p.amount_cents)=? LIMIT 1";
        try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(contactId), method,
                String.valueOf(Math.max(0L, createdAt - 60_000L)), String.valueOf(createdAt), String.valueOf(amount)})) {
            return cursor.moveToFirst();
        }
    }

    private synchronized boolean hasDuplicateContact(String name, String phone, long exceptId) {
        String digits = phoneDigits(phone);
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id,phone FROM contacts WHERE name=? COLLATE NOCASE", new String[]{name.trim()})) {
            while (cursor.moveToNext()) {
                if (cursor.getLong(0) == exceptId) continue;
                if (phoneDigits(cursor.getString(1)).equals(digits)) return true;
            }
        }
        return false;
    }

    public synchronized boolean hasExactDuplicateContact(String name, String phone, long exceptId) {
        return hasDuplicateContact(clean(name, 120), clean(phone, 40), exceptId);
    }

    public synchronized boolean hasPotentialPhoneDuplicate(String phone, long exceptId) {
        String digits = normalizedPhoneDigits(phone);
        if (digits.isEmpty()) return false;
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id,phone FROM contacts WHERE id<>?", new String[]{String.valueOf(exceptId)})) {
            while (cursor.moveToNext()) if (digits.equals(normalizedPhoneDigits(cursor.getString(1)))) return true;
        }
        return false;
    }

    public synchronized long findContactIdByPhone(String phone, long exceptId) {
        String digits = normalizedPhoneDigits(phone);
        if (digits.isEmpty()) return 0L;
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id,phone FROM contacts WHERE id<>? ORDER BY id ASC",
                new String[]{String.valueOf(exceptId)})) {
            while (cursor.moveToNext()) {
                if (digits.equals(normalizedPhoneDigits(cursor.getString(1)))) return cursor.getLong(0);
            }
        }
        return 0L;
    }

    private static String phoneDigits(String value) {
        String normalized = westernDigits(value == null ? "" : value);
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) if (Character.isDigit(normalized.charAt(i))) digits.append(normalized.charAt(i));
        return digits.toString();
    }

    private static String normalizedPhoneDigits(String value) {
        String digits = phoneDigits(value);
        if (digits.startsWith("00970")) return digits.substring(2);
        if (digits.startsWith("970")) return digits;
        if (digits.startsWith("0")) return "970" + digits.substring(1);
        return digits;
    }

    private static String westernDigits(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '\u0660' && c <= '\u0669') c = (char) ('0' + c - '\u0660');
            else if (c >= '\u06F0' && c <= '\u06F9') c = (char) ('0' + c - '\u06F0');
            result.append(c);
        }
        return result.toString();
    }
}
