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
    private static final int DB_VERSION = 7;

    /** Includes changes from this connection and other connections, without rebuilding the ledger. */
    synchronized long changeToken() {
        long version = 0, changes = 0;
        try (Cursor c = getReadableDatabase().rawQuery("PRAGMA data_version", null)) { if (c.moveToFirst()) version = c.getLong(0); }
        try (Cursor c = getReadableDatabase().rawQuery("SELECT total_changes()", null)) { if (c.moveToFirst()) changes = c.getLong(0); }
        return (version << 32) ^ changes;
    }

    @Override public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_debts_recent ON debts(created_at DESC,id DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_payments_recent ON payments(created_at DESC,id DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_contacts_name ON contacts(name COLLATE NOCASE,id)");
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_control (id INTEGER PRIMARY KEY, suppress INTEGER NOT NULL DEFAULT 0, ready INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("INSERT OR IGNORE INTO sync_control(id) VALUES(1)");
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_outbox (seq INTEGER PRIMARY KEY AUTOINCREMENT, entity TEXT NOT NULL, row_id INTEGER NOT NULL)");
        for (String table : new String[]{"contacts", "debts", "payments"}) for (String action : new String[]{"INSERT", "UPDATE", "DELETE"}) {
            String record = action.equals("DELETE") ? "OLD" : "NEW";
            db.execSQL("CREATE TRIGGER IF NOT EXISTS queue_" + table + "_" + action + " AFTER " + action + " ON " + table
                    + " WHEN (SELECT suppress FROM sync_control WHERE id=1)=0 BEGIN INSERT INTO sync_outbox(entity,row_id) VALUES('" + table + "'," + record + ".id); END");
        }
        if (scalarLong(db,"SELECT ready FROM sync_control WHERE id=1",null)==0) {
            db.beginTransaction();try {
                db.execSQL("DELETE FROM sync_outbox");
                for(String table:new String[]{"contacts","debts","payments"})db.execSQL("INSERT INTO sync_outbox(entity,row_id) SELECT '"+table+"',id FROM "+table+" ORDER BY id");
                db.execSQL("UPDATE sync_control SET ready=1 WHERE id=1");db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }
    }

    synchronized boolean deltaReady() { return scalarLong(getReadableDatabase(), "SELECT ready FROM sync_control WHERE id=1", null) == 1; }
    synchronized boolean hasPendingSync() { return scalarLong(getReadableDatabase(), "SELECT EXISTS(SELECT 1 FROM sync_outbox)", null) == 1; }
    synchronized void acknowledgeDelta(long watermark) { getWritableDatabase().delete("sync_outbox", "seq<=?", new String[]{String.valueOf(watermark)}); }
    synchronized void acknowledgeDelta(JSONObject packet) throws JSONException {
        JSONArray sequences=packet.getJSONArray("sequences");SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try {
            for(int n=0;n<sequences.length();n++)db.delete("sync_outbox","seq=?",new String[]{String.valueOf(sequences.getLong(n))});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    /** Only queued rows are read, capped at 500 changes, including deletions and cascades. */
    synchronized JSONObject pendingDelta() throws JSONException {
        SQLiteDatabase db = getReadableDatabase();
        java.util.LinkedHashMap<String, Long> changes = new java.util.LinkedHashMap<>(); long watermark = 0; JSONArray sequences=new JSONArray();
        String parents="SELECT q.seq,q.entity,q.row_id FROM sync_outbox q WHERE (q.entity='contacts' AND NOT EXISTS(SELECT 1 FROM contacts r WHERE r.id=q.row_id)) OR (q.entity='debts' AND NOT EXISTS(SELECT 1 FROM debts r WHERE r.id=q.row_id)) ORDER BY CASE q.entity WHEN 'contacts' THEN 0 ELSE 1 END,q.seq LIMIT 500";
        String query=scalarLong(db,"SELECT EXISTS("+parents+")",null)==1?parents:"SELECT seq,entity,row_id FROM sync_outbox ORDER BY seq LIMIT 500";
        try (Cursor cursor = db.rawQuery(query, null)) {
            while (cursor.moveToNext()) { watermark = Math.max(watermark,cursor.getLong(0)); sequences.put(cursor.getLong(0)); changes.put(cursor.getString(1) + ":" + cursor.getLong(2), cursor.getLong(2)); }
        }
        JSONObject delta = new JSONObject(), deleted = new JSONObject();
        for (String table : new String[]{"contacts", "debts", "payments"}) { delta.put(table, new JSONArray()); deleted.put(table, new JSONArray()); }
        for (Map.Entry<String, Long> entry : changes.entrySet()) {
            String table = entry.getKey().split(":")[0];
            try (Cursor cursor = db.rawQuery("SELECT * FROM " + table + " WHERE id=?", new String[]{String.valueOf(entry.getValue())})) {
                if (!cursor.moveToFirst()) { deleted.getJSONArray(table).put(entry.getValue()); continue; }
                JSONObject row = new JSONObject();
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    String column = cursor.getColumnName(i), key = column;
                    if (column.equals("amount_cents")) { row.put("amount", fromCents(cursor.getLong(i))); continue; }
                    if (column.equals("credit_limit_cents")) { row.put("creditLimit", fromCents(cursor.getLong(i))); continue; }
                    if (column.equals("whatsapp_opt_in")) { row.put("whatsappOptIn", cursor.getInt(i) != 0); continue; }
                    String[] parts = column.split("_"); for (int n=1;n<parts.length;n++) parts[n] = Character.toUpperCase(parts[n].charAt(0)) + parts[n].substring(1);
                    key = String.join("", parts);
                    row.put(key, cursor.getType(i)==Cursor.FIELD_TYPE_INTEGER ? cursor.getLong(i) : cursor.getString(i));
                }
                delta.getJSONArray(table).put(row);
            }
        }
        delta.put("deleted", deleted); return new JSONObject().put("delta", delta).put("watermark", watermark).put("sequences",sequences);
    }

    synchronized long outboxWatermark() { return scalarLong(getReadableDatabase(), "SELECT COALESCE(MAX(seq),0) FROM sync_outbox", null); }
    synchronized void beginSnapshotStage() {
        for(String table : new String[]{"contacts","debts","payments"}) { getWritableDatabase().execSQL("DROP TABLE IF EXISTS stage_"+table); getWritableDatabase().execSQL("CREATE TABLE stage_"+table+" AS SELECT * FROM "+table+" WHERE 0"); }
    }
    synchronized void stageSnapshotPage(String table, JSONArray rows) throws JSONException {
        if(!table.equals("contacts")&&!table.equals("debts")&&!table.equals("payments"))throw new IllegalArgumentException();
        SQLiteDatabase db=getWritableDatabase(); java.util.LinkedHashMap<String,String> columns=new java.util.LinkedHashMap<>();
        try(Cursor schema=db.rawQuery("PRAGMA table_info("+table+")",null)){while(schema.moveToNext())columns.put(schema.getString(1),schema.getString(2));}
        db.beginTransaction();try {
            for(int n=0;n<rows.length();n++) {
                JSONObject row=rows.getJSONObject(n);ContentValues values=new ContentValues();
                for(Map.Entry<String,String> schema:columns.entrySet()) {
                    String column=schema.getKey();String key=column;String[] parts=column.split("_");for(int k=1;k<parts.length;k++)parts[k]=Character.toUpperCase(parts[k].charAt(0))+parts[k].substring(1);key=String.join("",parts);
                    if(column.equals("amount_cents"))values.put(column,toCents(row.optString("amount","0")));
                    else if(column.equals("credit_limit_cents"))values.put(column,toCents(row.optString("creditLimit","0")));
                    else if(column.equals("whatsapp_opt_in"))values.put(column,row.optBoolean("whatsappOptIn")?1:0);
                    else if(schema.getValue().equals("INTEGER"))values.put(column,row.optLong(key));
                    else values.put(column,row.optString(key,""));
                }
                db.insertOrThrow("stage_"+table,null,values);
            }db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }
    synchronized void commitSnapshotStage(long expectedWatermark) {
        if(outboxWatermark()!=expectedWatermark)throw new IllegalStateException("حُفظت تعديلات جديدة أثناء التحميل. بقيت سجلاتك المحلية محفوظة؛ أعد المزامنة.");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try {
            db.execSQL("UPDATE sync_control SET suppress=1 WHERE id=1");
            for(String table:new String[]{"payments","debts","contacts"})db.execSQL("DELETE FROM "+table);
            for(String table:new String[]{"contacts","debts","payments"})db.execSQL("INSERT INTO "+table+" SELECT * FROM stage_"+table);
            db.execSQL("DELETE FROM sync_outbox");db.execSQL("UPDATE sync_control SET suppress=0,ready=1 WHERE id=1");db.setTransactionSuccessful();
        }finally{db.endTransaction();dropSnapshotStage();}
    }
    synchronized void dropSnapshotStage() {for(String table:new String[]{"contacts","debts","payments"})getWritableDatabase().execSQL("DROP TABLE IF EXISTS stage_"+table);}

    synchronized JSONObject dueReminderSnapshot(String today,String tomorrow) throws JSONException {
        JSONArray people=new JSONArray(),debts=new JSONArray();SQLiteDatabase db=getReadableDatabase();
        try(Cursor c=db.rawQuery("SELECT d.id,d.contact_id,c.name,d.due_date,d.amount_cents-COALESCE((SELECT SUM(amount_cents) FROM payments WHERE debt_id=d.id),0) remaining FROM debts d JOIN contacts c ON c.id=d.contact_id WHERE d.direction='receivable' AND d.due_date IN (?,?)",new String[]{today,tomorrow})) {while(c.moveToNext()){if(c.getLong(4)<=0)continue;people.put(new JSONObject().put("id",c.getLong(1)).put("name",c.getString(2)));debts.put(new JSONObject().put("id",c.getLong(0)).put("contactId",c.getLong(1)).put("dueDate",c.getString(3)).put("direction","receivable").put("remaining",fromCents(c.getLong(4))));}}
        return new JSONObject().put("contacts",people).put("debts",debts);
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

    synchronized JSONArray historyActivities(long from,long to) throws JSONException {
        JSONArray rows=new JSONArray();
        String sql="SELECT contact_id,MAX(created_at) FROM (SELECT contact_id,created_at FROM debts WHERE direction='receivable' UNION ALL SELECT d.contact_id,p.created_at FROM payments p JOIN debts d ON d.id=p.debt_id WHERE d.direction='receivable') WHERE created_at>=? AND created_at<=? GROUP BY contact_id ORDER BY MAX(created_at) DESC";
        try(Cursor c=getReadableDatabase().rawQuery(sql,new String[]{String.valueOf(from),String.valueOf(to)})){while(c.moveToNext())rows.put(new JSONObject().put("contactId",c.getLong(0)).put("createdAt",c.getLong(1)).put("kind","debt").put("direction","receivable"));}return rows;
    }
    synchronized JSONObject latestPersonDebt(long id) throws JSONException {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT amount_cents FROM debts WHERE contact_id=? AND direction='receivable' ORDER BY created_at DESC,id DESC LIMIT 1",new String[]{String.valueOf(id)})){return c.moveToFirst()?new JSONObject().put("amount",fromCents(c.getLong(0))):null;}
    }
    public synchronized JSONObject getSnapshot() throws JSONException { return buildSnapshot(false,0,0); }
    synchronized Cursor openHomeHistory() {
        return getReadableDatabase().rawQuery("SELECT d.id*2 AS _id,d.contact_id,c.name,'debt' AS kind,d.amount_cents,d.created_at,d.created_by FROM debts d JOIN contacts c ON c.id=d.contact_id WHERE d.direction='receivable' UNION ALL SELECT MIN(p.id)*2+1,d.contact_id,c.name,'payment',SUM(p.amount_cents),p.created_at,p.created_by FROM payments p JOIN debts d ON d.id=p.debt_id JOIN contacts c ON c.id=d.contact_id WHERE d.direction='receivable' GROUP BY p.created_at,d.contact_id,p.note,p.method,p.created_by ORDER BY 6 DESC,1 DESC",null);
    }
    synchronized JSONObject getRecentHistory(int limit) throws JSONException { return buildSnapshot(true,0,Math.max(1,limit)); }
    synchronized JSONObject getOverview() throws JSONException { return buildSnapshot(true,0,1000); }
    synchronized JSONObject getPersonSnapshot(long id) throws JSONException { return buildSnapshot(false,id,0); }
    private JSONObject buildSnapshot(boolean overview, long onlyPerson, int limit) throws JSONException {
        SQLiteDatabase db = getReadableDatabase();
        Map<Long, JSONObject> contactById = new HashMap<>();
        JSONArray contacts = new JSONArray();
        try (Cursor cursor = db.rawQuery("SELECT id,name,phone,category,note,whatsapp_opt_in,credit_limit_cents,created_at,created_by FROM contacts"+(onlyPerson>0?" WHERE id="+onlyPerson:"")+" ORDER BY name COLLATE NOCASE,id", null)) {
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
                (onlyPerson>0?"WHERE d.contact_id="+onlyPerson+" ":"")+"GROUP BY d.id ORDER BY d.created_at DESC,d.id DESC";
        if (limit>0) debtQuery += " LIMIT "+limit;
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
                "JOIN contacts c ON c.id=d.contact_id "+(onlyPerson>0?"WHERE d.contact_id="+onlyPerson+" ":"")+"ORDER BY p.created_at DESC,p.id DESC";
        if (limit>0) paymentQuery += " LIMIT "+limit;
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
                (onlyPerson>0?"WHERE d.contact_id="+onlyPerson+" ":"")+"GROUP BY p.created_at,d.contact_id,d.direction,p.note,p.method " +
                "ORDER BY p.created_at DESC,MIN(p.id) DESC";
        if (limit>0) groupedPaymentQuery += " LIMIT "+limit;
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

        if (overview) {
            for(JSONObject c : contactById.values()) { c.put("receivable",0d);c.put("payable",0d);c.put("transactionCount",0);c.put("lastActivity",0L); }
            String sums="SELECT d.contact_id,d.direction,SUM(d.amount_cents-COALESCE(p.paid,0)),COUNT(*),MAX(d.created_at) FROM debts d LEFT JOIN (SELECT debt_id,SUM(amount_cents) paid FROM payments GROUP BY debt_id) p ON p.debt_id=d.id GROUP BY d.contact_id,d.direction";
            try(Cursor c=db.rawQuery(sums,null)){while(c.moveToNext()){JSONObject person=contactById.get(c.getLong(0));if(person==null)continue;person.put(c.getString(1),fromCents(c.getLong(2)));person.put("transactionCount",person.optInt("transactionCount")+c.getInt(3));person.put("lastActivity",Math.max(person.optLong("lastActivity"),c.getLong(4)));}}
            try(Cursor c=db.rawQuery("SELECT d.contact_id,COUNT(*),MAX(p.created_at) FROM payments p JOIN debts d ON d.id=p.debt_id GROUP BY d.contact_id",null)){while(c.moveToNext()){JSONObject person=contactById.get(c.getLong(0));if(person!=null){person.put("transactionCount",person.optInt("transactionCount")+c.getInt(1));person.put("lastActivity",Math.max(person.optLong("lastActivity"),c.getLong(2)));}}}
            for(JSONObject person:contactById.values())person.put("net",person.optDouble("receivable")-person.optDouble("payable"));
        }
        java.util.Calendar month=java.util.Calendar.getInstance();month.set(java.util.Calendar.DAY_OF_MONTH,1);month.set(java.util.Calendar.HOUR_OF_DAY,0);month.set(java.util.Calendar.MINUTE,0);month.set(java.util.Calendar.SECOND,0);month.set(java.util.Calendar.MILLISECOND,0);
        String[] monthArgs={String.valueOf(month.getTimeInMillis())};
        long monthlyDebt=scalarLong(db,"SELECT COALESCE(SUM(amount_cents),0) FROM debts WHERE direction='receivable' AND created_at>=?",monthArgs);
        long monthlyPaid=scalarLong(db,"SELECT COALESCE(SUM(p.amount_cents),0) FROM payments p JOIN debts d ON d.id=p.debt_id WHERE d.direction='receivable' AND p.created_at>=?",monthArgs);
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
                .put("monthly",new JSONObject().put("debt",fromCents(monthlyDebt)).put("paid",fromCents(monthlyPaid)))
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
            db.execSQL("UPDATE sync_control SET suppress=1 WHERE id=1");
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
            db.execSQL("DELETE FROM sync_outbox"); db.execSQL("UPDATE sync_control SET suppress=0,ready=1 WHERE id=1");
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

