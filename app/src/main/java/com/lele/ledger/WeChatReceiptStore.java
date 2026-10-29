package com.lele.ledger;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import java.util.List;

/** Review proposals live outside bills. Repeated page events do not create repeated bills. */
final class WeChatReceiptStore {
    static final class Pending {
        final long id, cents, occurredAt;
        final String kind, fingerprint;
        final int direction;

        Pending(Cursor c) {
            id = c.getLong(0);
            fingerprint = c.getString(1);
            kind = c.getString(2);
            cents = c.getLong(3);
            direction = c.getInt(4);
            occurredAt = c.getLong(5);
        }
    }

    static void createTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS wechat_receipt ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, fingerprint TEXT NOT NULL UNIQUE,"
                + "kind TEXT NOT NULL, cents INTEGER NOT NULL, direction INTEGER NOT NULL,"
                + "occurred_at INTEGER NOT NULL, captured_at INTEGER NOT NULL, status INTEGER NOT NULL DEFAULT 0)");
    }

    static boolean offer(Db db, WeChatReceiptParser.Receipt receipt) {
        ContentValues value = new ContentValues();
        value.put("fingerprint", receipt.fingerprint);
        value.put("kind", receipt.kind);
        value.put("cents", receipt.cents);
        value.put("direction", receipt.direction);
        value.put("occurred_at", receipt.occurredAt);
        value.put("captured_at", System.currentTimeMillis());
        if (receipt.occurredAt > 0) {
            // Enrich an earlier proposal only when the exact same page was parsed without its date.
            int updated = db.getWritableDatabase().updateWithOnConflict("wechat_receipt", value,
                    "fingerprint=? AND status=0 AND occurred_at=0",
                    new String[]{receipt.withoutTimeFingerprint}, SQLiteDatabase.CONFLICT_IGNORE);
            if (updated > 0) return true;
        }
        return db.getWritableDatabase().insertWithOnConflict("wechat_receipt", null, value,
                SQLiteDatabase.CONFLICT_IGNORE) > 0;
    }

    static int count(Db db) {
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM wechat_receipt WHERE status=0", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    static Pending next(Db db) {
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT id,fingerprint,kind,cents,direction,occurred_at FROM wechat_receipt"
                        + " WHERE status=0 ORDER BY id LIMIT 1", null)) {
            return c.moveToFirst() ? new Pending(c) : null;
        }
    }

    static List<Pending> pending(Db db) {
        List<Pending> result = new ArrayList<>();
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT id,fingerprint,kind,cents,direction,occurred_at FROM wechat_receipt"
                        + " WHERE status=0 ORDER BY id DESC", null)) {
            while (c.moveToNext()) result.add(new Pending(c));
        }
        return result;
    }

    static boolean similarBill(Db db, double amount, long time) {
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT 1 FROM bills WHERE channel LIKE '%微信%' AND abs(amount-?)<0.005"
                        + " AND ts BETWEEN ? AND ? LIMIT 1",
                new String[]{String.valueOf(amount), String.valueOf(time - 86_400_000L),
                        String.valueOf(time + 86_400_000L)})) {
            return c.moveToFirst();
        }
    }

    /** Both changes share a transaction. A crash after saving cannot add the bill a second time. */
    static long accept(Db helper, Pending pending, long cents, int direction, long time) {
        if (cents <= 0 || (direction != 1 && direction != -1) || time <= 0) return -1;
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor c = db.rawQuery("SELECT status FROM wechat_receipt WHERE id=?",
                    new String[]{String.valueOf(pending.id)})) {
                if (!c.moveToFirst() || c.getInt(0) != 0) return -1;
            }
            String key = "wechat-receipt:" + pending.fingerprint;
            try (Cursor c = db.rawQuery("SELECT id FROM bills WHERE nkey=? LIMIT 1", new String[]{key})) {
                if (c.moveToFirst()) return -1;
            }
            long id = helper.insert(direction * cents / 100.0, "微信",
                    WeChatReceiptParser.RED_PACKET.equals(pending.kind) ? "微信红包" : "微信转账", time, key);
            if (id <= 0) return -1;
            mark(db, pending.id, 1);
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    static void ignore(Db db, long id) {
        mark(db.getWritableDatabase(), id, 2);
    }

    private static void mark(SQLiteDatabase db, long id, int status) {
        ContentValues value = new ContentValues();
        value.put("status", status);
        db.update("wechat_receipt", value, "id=? AND status=0", new String[]{String.valueOf(id)});
    }

    private WeChatReceiptStore() {}
}
