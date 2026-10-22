package dev.linjian.peek;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** Durable local outbox. Accepted rows are never silently evicted; V1 does not upload them. */
public final class WakeQueue extends SQLiteOpenHelper {
    public static final int CAPACITY = 10000;

    public WakeQueue(Context context) {
        super(context, "autonomous_wake_v1.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE events (_id INTEGER PRIMARY KEY AUTOINCREMENT, dedupe TEXT NOT NULL"
                    + " UNIQUE, kind TEXT NOT NULL, payload TEXT NOT NULL, priority INTEGER NOT"
                    + " NULL, created INTEGER NOT NULL, due INTEGER NOT NULL, attempts INTEGER NOT"
                    + " NULL DEFAULT 0, state TEXT NOT NULL DEFAULT 'pending', sync_state TEXT NOT"
                    + " NULL DEFAULT 'pending', error TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX wake_due ON events(state,due,priority)");
        db.execSQL("CREATE TABLE cooldown (kind TEXT PRIMARY KEY, until_ms INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported wake queue migration");
    }

    /** Returns row id, -1 for a duplicate, -2 when full. Caller must surface capacity errors. */
    public synchronized long enqueue(
            String key, String kind, String payload, int priority, long now) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor c =
                    db.rawQuery("SELECT _id FROM events WHERE dedupe=?", new String[] {key})) {
                if (c.moveToFirst()) return -1;
            }
            if (count(db, "1=1") >= CAPACITY) return -2;
            ContentValues v = new ContentValues();
            v.put("dedupe", key);
            v.put("kind", kind);
            v.put("payload", payload);
            v.put("priority", priority);
            v.put("created", now);
            v.put("due", now);
            long id = db.insertOrThrow("events", null, v);
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    public static final class Event {
        public final long id;
        public final String key;
        public final String kind;
        public final String payload;
        public final int priority;
        public final long created;
        public final int attempts;

        Event(
                long id,
                String key,
                String kind,
                String payload,
                int priority,
                long created,
                int attempts) {
            this.id = id;
            this.key = key;
            this.kind = kind;
            this.payload = payload;
            this.priority = priority;
            this.created = created;
            this.attempts = attempts;
        }
    }

    public synchronized Event next(long now) {
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT"
                                    + " e._id,e.dedupe,e.kind,e.payload,e.priority,e.created,e.attempts"
                                    + " FROM events e LEFT JOIN cooldown c ON e.kind=c.kind WHERE"
                                    + " e.state='pending' AND e.due<=? AND (c.until_ms IS NULL OR"
                                    + " c.until_ms<=?) ORDER BY e.priority DESC,e.created ASC,e._id"
                                    + " ASC LIMIT 1",
                                new String[] {"" + now, "" + now})) {
            return c.moveToFirst()
                    ? new Event(
                            c.getLong(0),
                            c.getString(1),
                            c.getString(2),
                            c.getString(3),
                            c.getInt(4),
                            c.getLong(5),
                            c.getInt(6))
                    : null;
        }
    }

    /**
     * Stable notification id + onlyAlertOnce make delivery replay replace the same notification.
     */
    public synchronized void delivered(Event event, long now, long cooldownMs) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues done = new ContentValues();
            done.put("state", "delivered");
            done.put("error", "");
            db.update("events", done, "_id=? AND state='pending'", new String[] {"" + event.id});
            ContentValues cooldown = new ContentValues();
            cooldown.put("kind", event.kind);
            cooldown.put("until_ms", now + cooldownMs);
            db.insertWithOnConflict("cooldown", null, cooldown, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void retry(Event event, long now, String reason) {
        ContentValues v = new ContentValues();
        v.put("attempts", event.attempts + 1);
        v.put("due", now + WakePolicy.retryDelay(event.attempts + 1));
        v.put("error", reason);
        getWritableDatabase()
                .update("events", v, "_id=? AND state='pending'", new String[] {"" + event.id});
    }

    public synchronized long nextDue(long fallback) {
        try (Cursor c =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT MIN(MAX(e.due,COALESCE(c.until_ms,0))) FROM events e LEFT"
                                    + " JOIN cooldown c ON e.kind=c.kind WHERE e.state='pending'",
                                null)) {
            return c.moveToFirst() && !c.isNull(0) ? Math.min(fallback, c.getLong(0)) : fallback;
        }
    }

    public synchronized int pending() {
        return count(getReadableDatabase(), "state='pending'");
    }

    public synchronized int unsynced() {
        return count(getReadableDatabase(), "sync_state='pending'");
    }

    private int count(SQLiteDatabase db, String where) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM events WHERE " + where, null)) {
            c.moveToFirst();
            return c.getInt(0);
        }
    }
}
