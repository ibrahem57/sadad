package com.sadad.app;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.*;
import java.util.List;

/** The old sadad.db is deliberately never opened or migrated automatically. */
@Database(entities = {SyncDatabase.Cache.class, SyncDatabase.Command.class}, version = 1, exportSchema = true)
public abstract class SyncDatabase extends RoomDatabase {
    private static volatile SyncDatabase instance;
    static SyncDatabase get(Context context) {
        if (instance == null) synchronized (SyncDatabase.class) {
            if (instance == null) instance = Room.databaseBuilder(context.getApplicationContext(), SyncDatabase.class,
                    "sadad-sync-v3.db").setJournalMode(JournalMode.WRITE_AHEAD_LOGGING).build();
        }
        return instance;
    }
    public abstract Journal journal();

    @Entity(tableName = "confirmed_cache")
    public static class Cache {
        @PrimaryKey @NonNull public String scope = "";
        @NonNull public String json = "{}";
        @NonNull public String epoch = "";
        public long cursor;
        public long refreshedAt;
        @NonNull public String lastError = "";
        public boolean recoveryRequired;
    }
    @Entity(tableName = "command_journal", indices = {@Index(value = {"operationId"}, unique = true), @Index("scope")})
    public static class Command {
        @PrimaryKey(autoGenerate = true) public long localOrder;
        @NonNull public String operationId = "";
        @NonNull public String scope = "";
        @NonNull public String entityId = "";
        @NonNull public String type = "";
        /** Immutable wire bytes. A retry always sends exactly this value. */
        @NonNull public String json = "{}";
        @NonNull public String state = "queued";
        @NonNull public String error = "";
        @NonNull public String errorCode = "";
        @NonNull public String ackEpoch = "";
        public long ackSequence;
        public long createdAt;
        public long lastAttemptAt;
        public int attempts;
    }
    @Dao public interface Journal {
        @Query("SELECT * FROM confirmed_cache WHERE scope = :scope") Cache cache(String scope);
        @Insert(onConflict = OnConflictStrategy.REPLACE) void saveCache(Cache cache);
        @Query("SELECT * FROM command_journal WHERE scope = :scope ORDER BY localOrder") List<Command> all(String scope);
        @Query("SELECT * FROM command_journal WHERE scope = :scope AND state != 'confirmed' AND state != 'reviewed' ORDER BY localOrder") List<Command> outstanding(String scope);
        @Query("SELECT * FROM command_journal WHERE operationId = :operationId AND scope = :scope") Command command(String scope, String operationId);
        @Insert void insert(Command command);
        @Update void update(Command command);
        @Query("UPDATE command_journal SET state = 'confirmed', error = '', errorCode = '' WHERE scope = :scope AND state = 'acknowledged' AND ackEpoch = :epoch AND ackSequence <= :cursor") void confirmVisible(String scope, String epoch, long cursor);
    }
}
