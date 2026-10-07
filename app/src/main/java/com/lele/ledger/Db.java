package com.lele.ledger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地账本。存在手机里，不联网、不上传。
 *
 * 两张表：
 *   bills    —— 解析成功的记账记录（对用户可见）
 *   raw_noti —— 收到的原始通知原文（排查用，滚动保留最近 300 条）
 *
 * raw_noti 的存在意义：通知文案会随微信/支付宝版本变化。一旦某类通知没被
 * 正确记账，用户可以直接在 App 里看到原文，而不是靠猜。
 */
public final class Db extends SQLiteOpenHelper {

    private static final String NAME = "ledger.db";
    private static final int VERSION = 7;
    private static final int RAW_KEEP = 400;

    /**
     * 状态标记（心跳/连接事件）单独保留条数。
     * 必须比 RAW_KEEP 大得多：心跳是「服务何时活着」的时间线，
     * 若与普通通知共用上限，活跃聊天几小时就能把它挤掉，
     * 结果看起来像「心跳断档=服务被杀」——那是假证据，会把人带偏。
     */
    private static final int MARK_KEEP = 1000;

    public Db(Context ctx) {
        super(ctx, NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE bills ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "amount REAL NOT NULL,"
                + "channel TEXT,"
                + "title TEXT,"
                + "nkey TEXT,"
                + "category TEXT,"
                + "ts INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_bills_ts ON bills(ts DESC)");
        createRawTable(db);
        createPendingTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // v1 -> v2：新增原始通知表。不触碰已有的 bills 数据。
        if (oldV < 2) {
            createRawTable(db);
        }
        // v2 -> v3：新增通知标识列，用于精确去重。旧行为 NULL，不受影响。
        if (oldV < 3) {
            db.execSQL("ALTER TABLE bills ADD COLUMN nkey TEXT");
        }
        // v3 -> v4：新增用途（分类）。旧行 NULL 视为「未分类」，等待用户补。
        if (oldV < 4) {
            db.execSQL("ALTER TABLE bills ADD COLUMN category TEXT");
        }
        // v4 -> v5：新增「待确认转账」表。独立于 bills，绝不污染真实账本。
        if (oldV < 5) {
            createPendingTable(db);
        }
        // v5 -> v6：新增 budget_tags 表，按分类设预算。
        if (oldV < 6) {
            db.execSQL("CREATE TABLE IF NOT EXISTS budget_tags ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "name TEXT NOT NULL UNIQUE,"
                    + "budget REAL NOT NULL DEFAULT 0,"
                    + "threshold INTEGER NOT NULL DEFAULT 80)");
        }
        // v6 -> v7：给 budget_tags 补上 threshold 列
        if (oldV < 7) {
            try {
                db.execSQL("ALTER TABLE budget_tags ADD COLUMN threshold INTEGER NOT NULL DEFAULT 80");
            } catch (Exception ignored) {}
        }
    }

    private static void createPendingTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS pending_transfer ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "nkey TEXT, amount REAL, channel TEXT, title TEXT, ts INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pending_ts ON pending_transfer(ts DESC)");
    }

    private static void createRawTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS raw_noti ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "pkg TEXT, title TEXT, body TEXT, ts INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_raw_ts ON raw_noti(ts DESC)");
    }

    /**
     * 删掉一条记录。返回被删的行数：0 表示这行本来就不在（比如已经被删过），
     * 调用方据此提示用户，而不是假装删成功。
     */
    public int delete(long id) {
        return getWritableDatabase().delete("bills", "id=?", new String[]{String.valueOf(id)});
    }

    public long insert(double amount, String channel, String title, long ts, String nkey) {
        ContentValues v = new ContentValues();
        v.put("amount", amount);
        v.put("channel", channel);
        v.put("title", title);
        v.put("nkey", nkey);
        v.put("ts", ts);
        return getWritableDatabase().insert("bills", null, v);
    }

    /**
     * 精确去重：同一条通知（同一通知标识）在 N 毫秒内只记一次。
     *
     * 为何不用「金额+渠道+标题」去重（v1.2 及之前）：实测微信所有付款通知的标题
     * 都叫「Weixin Pay」，于是一分钟内两笔同为 ¥1.00 的真实支付会被误判为重复、
     * 丢掉一笔。**记账应用静默丢数据是最坏的故障**，所以改用通知标识。
     */
    public boolean seenKey(String nkey, long windowMs) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM bills WHERE nkey=? AND ts>=? LIMIT 1",
                new String[]{nkey, String.valueOf(System.currentTimeMillis() - windowMs)});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    /** 兜底去重：拿不到通知标识时才用（金额+渠道+标题）。 */
    public boolean existsRecent(double amount, String channel, String title, long windowMs) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM bills WHERE amount=? AND channel=? AND title=? AND ts>=? LIMIT 1",
                new String[]{String.valueOf(amount), channel, title, String.valueOf(System.currentTimeMillis() - windowMs)});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    /** 存一条原始通知，并分别裁剪普通通知与状态标记，避免无限增长。 */
    public void insertRaw(String pkg, String title, String body) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("pkg", pkg);
        v.put("title", title);
        v.put("body", body);
        v.put("ts", System.currentTimeMillis());
        db.insert("raw_noti", null, v);

        Object[] mark = new Object[]{NotiListener.MARK, NotiListener.MARK};
        db.execSQL("DELETE FROM raw_noti WHERE pkg<>? AND id NOT IN "
                + "(SELECT id FROM raw_noti WHERE pkg<>? ORDER BY ts DESC LIMIT " + RAW_KEEP + ")", mark);
        db.execSQL("DELETE FROM raw_noti WHERE pkg=? AND id NOT IN "
                + "(SELECT id FROM raw_noti WHERE pkg=? ORDER BY ts DESC LIMIT " + MARK_KEEP + ")", mark);
    }

    /** 最近若干条原始通知，返回 {时间戳, 包名, 标题, 正文}。 */
    public List<String[]> recentRaw(int limit) {
        List<String[]> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts, pkg, title, body FROM raw_noti ORDER BY ts DESC LIMIT ?",
                new String[]{String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                out.add(new String[]{c.getString(0), c.getString(1), c.getString(2), c.getString(3)});
            }
        } finally {
            c.close();
        }
        return out;
    }

    public void clearRaw() {
        getWritableDatabase().execSQL("DELETE FROM raw_noti");
    }

    /**
     * 最近一次心跳/连接标记的时间戳；从没写过则返回 0。
     * 用来在首页回答一个关键问题：监听服务现在到底还活着吗？
     */
    public long lastMarkTs() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT MAX(ts) FROM raw_noti WHERE pkg=?", new String[]{NotiListener.MARK});
        try {
            if (c.moveToFirst() && !c.isNull(0)) {
                return c.getLong(0);
            }
            return 0L;
        } finally {
            c.close();
        }
    }

    public List<Bill> recent(int limit) {
        List<Bill> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, amount, channel, title, ts, category FROM bills ORDER BY ts DESC LIMIT ?",
                new String[]{String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                out.add(new Bill(c.getLong(0), c.getDouble(1), c.getString(2), c.getString(3),
                        c.getLong(4), c.getString(5)));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** 给定任意时间戳，返回其所在月份下个月1号0点的毫秒值 */
    private static long nextMonthStart(long ts) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts);
        c.set(java.util.Calendar.DAY_OF_MONTH, 1);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        c.add(java.util.Calendar.MONTH, 1);
        return c.getTimeInMillis();
    }

    /** 查当月数据（monthStart 到 monthStart+1月），按时间倒序 */
    public List<Bill> recentInMonth(long monthStart) {
        List<Bill> out = new ArrayList<>();
        long monthEnd = nextMonthStart(monthStart);
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, amount, channel, title, ts, category FROM bills WHERE ts>=? AND ts<? ORDER BY ts DESC",
                new String[]{String.valueOf(monthStart), String.valueOf(monthEnd)});
        try {
            while (c.moveToNext()) {
                out.add(new Bill(c.getLong(0), c.getDouble(1), c.getString(2), c.getString(3),
                        c.getLong(4), c.getString(5)));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** 按 id 取一条；不存在则返回 null。 */
    public Bill byId(long id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, amount, channel, title, ts, category FROM bills WHERE id=? LIMIT 1",
                new String[]{String.valueOf(id)});
        try {
            if (c.moveToNext()) {
                return new Bill(c.getLong(0), c.getDouble(1), c.getString(2), c.getString(3),
                        c.getLong(4), c.getString(5));
            }
            return null;
        } finally {
            c.close();
        }
    }

    // ---------------- 待确认的转账 ----------------

    /**
     * 记一条「转账已被接收、但金额/方向待用户确认」的提案。
     *
     * 单独一张表、**绝不进 bills**：微信这条通知里根本没有金额，方向也有歧义。
     * 硬猜一个数写进账本，等于替用户编了一笔不存在的账。
     */
    public long insertPending(String nkey, double amount, String channel, String title) {
        ContentValues v = new ContentValues();
        v.put("nkey", nkey);
        v.put("amount", amount);
        v.put("channel", channel);
        v.put("title", title);
        v.put("ts", System.currentTimeMillis());
        return getWritableDatabase().insert("pending_transfer", null, v);
    }

    /** 同一条转账通知被重复投递时，不要去重成两条提案。 */
    public boolean pendingSeen(String nkey) {
        if (nkey == null || nkey.isEmpty()) {
            return false;
        }
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM pending_transfer WHERE nkey=? LIMIT 1",
                new String[]{nkey});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    public int countPending() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM pending_transfer", null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /** 最近一条待确认转账；没有则返回 null。返回对象的 pending 为 true。 */
    public Bill nextPending() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, amount, channel, title, ts FROM pending_transfer "
                        + "ORDER BY ts DESC LIMIT 1", null);
        try {
            if (c.moveToNext()) {
                return Bill.pendingTransfer(c.getLong(0), c.getDouble(1),
                        c.getString(2), c.getString(3), c.getLong(4));
            }
            return null;
        } finally {
            c.close();
        }
    }

    public Bill pendingById(long id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, amount, channel, title, ts FROM pending_transfer WHERE id=? LIMIT 1",
                new String[]{String.valueOf(id)});
        try {
            if (c.moveToNext()) {
                return Bill.pendingTransfer(c.getLong(0), c.getDouble(1),
                        c.getString(2), c.getString(3), c.getLong(4));
            }
            return null;
        } finally {
            c.close();
        }
    }

    public int deletePending(long id) {
        return getWritableDatabase().delete("pending_transfer", "id=?",
                new String[]{String.valueOf(id)});
    }

    // ---------------- 用途（分类） ----------------

    /** 给一条记录定用途。改成空串 = 退回「未分类」。 */
    public int updateCategory(long id, String name) {
        ContentValues v = new ContentValues();
        v.put("category", name == null ? "" : name);
        return getWritableDatabase().update("bills", v, "id=?",
                new String[]{String.valueOf(id)});
    }

    /** 更新金额。 */
    public int updateAmount(long id, double amount) {
        ContentValues v = new ContentValues();
        v.put("amount", amount);
        return getWritableDatabase().update("bills", v, "id=?",
                new String[]{String.valueOf(id)});
    }

    /** 还有多少笔没定用途。 */
    public int countUncategorized() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM bills WHERE category IS NULL OR category=''", null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /** 最近一笔未分类的记录；全部分好了则返回 null。 */
    public Bill nextUncategorized() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, amount, channel, title, ts, category FROM bills "
                        + "WHERE category IS NULL OR category='' ORDER BY ts DESC LIMIT 1", null);
        try {
            if (c.moveToNext()) {
                return new Bill(c.getLong(0), c.getDouble(1), c.getString(2), c.getString(3),
                        c.getLong(4), c.getString(5));
            }
            return null;
        } finally {
            c.close();
        }
    }

    /** 所有未分类的记录，按时间倒序。 */
    public List<Bill> listUncategorized() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, amount, channel, title, ts, category FROM bills "
                        + "WHERE category IS NULL OR category='' ORDER BY ts DESC", null);
        List<Bill> out = new ArrayList<>();
        try {
            while (c.moveToNext()) {
                out.add(new Bill(c.getLong(0), c.getDouble(1), c.getString(2), c.getString(3),
                        c.getLong(4), c.getString(5)));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** 本月内还没定用途的笔数，用来在饼图页提醒用户去补。 */
    public int uncategorizedCount(long sinceMs, boolean income) {
        String cmp = income ? "amount>=0" : "amount<0";
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM bills WHERE ts>=? AND " + cmp
                        + " AND (category IS NULL OR category='')",
                new String[]{String.valueOf(sinceMs)});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /**
     * 按用途汇总，金额从大到小。返回 {用途名, 金额}。
     *
     * 未分类的单独归一档，叫「未分类」——把它藏起来会让饼图总额对不上账本，
     * 用户反而更困惑。
     */
    public List<String[]> categoryTotals(long sinceMs, boolean income) {
        List<String[]> out = new ArrayList<>();
        String amountExpr = income ? "SUM(amount)" : "SUM(-amount)";
        String cmp = income ? "amount>=0" : "amount<0";
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT CASE WHEN category IS NULL OR category='' THEN ? ELSE category END AS c, "
                        + amountExpr + " FROM bills WHERE ts>=? AND " + cmp
                        + " GROUP BY c HAVING " + amountExpr + " > 0 ORDER BY " + amountExpr + " DESC",
                new String[]{Category.NONE, String.valueOf(sinceMs)});
        try {
            while (c.moveToNext()) {
                out.add(new String[]{c.getString(0), String.valueOf(c.getDouble(1))});
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** 本月收支合计：{收入, 支出(正数)} */
    public double[] monthTotals(long monthStart) {
        double income = 0, expense = 0;
        long monthEnd = nextMonthStart(monthStart);
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT amount FROM bills WHERE ts>=? AND ts<?",
                new String[]{String.valueOf(monthStart), String.valueOf(monthEnd)});
        try {
            while (c.moveToNext()) {
                double a = c.getDouble(0);
                if (a >= 0) {
                    income += a;
                } else {
                    expense -= a;
                }
            }
        } finally {
            c.close();
        }
        return new double[]{income, expense};
    }

    // ---------------- 分类预算（budget_tags） ----------------

    /** 取所有分类标签，按 name 排序。若表不存在则静默返回空列表。 */
    public List<String[]> listBudgetTags() {
        List<String[]> out = new ArrayList<>();
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT id, name, budget, threshold FROM budget_tags ORDER BY id", null);
            try {
                while (c.moveToNext()) {
                    out.add(new String[]{String.valueOf(c.getLong(0)), c.getString(1), String.valueOf(c.getDouble(2)), String.valueOf(c.getInt(3))});
                }
            } finally {
                c.close();
            }
        } catch (Exception ignored) {
            // 旧数据库还没有这张表时返回空
        }
        return out;
    }

    /** 添加或更新一个预算标签。name 必须唯一，重复时做 UPSERT。 */
    /** 添加或更新一个预算标签（含阈值）。name 必须唯一，重复时做 UPSERT。 */
    public long upsertBudgetTag(String name, double budget, int threshold) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("budget", budget);
        v.put("threshold", threshold);
        // INSERT OR REPLACE：名字冲突时更新预算和阈值
        return getWritableDatabase().insertWithOnConflict(
                "budget_tags", null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 向后兼容：仅存预算，阈值用默认值 */
    public long upsertBudgetTag(String name, double budget) {
        return upsertBudgetTag(name, budget, Budget.DEFAULT_THRESHOLD);
    }

    /** 删除一个预算标签。 */
    public int deleteBudgetTag(long id) {
        return getWritableDatabase().delete("budget_tags", "id=?",
                new String[]{String.valueOf(id)});
    }

    /** 按分类名查预算。查不到返回 0。 */
    public double budgetForTag(String name) {
        if (name == null || name.isEmpty()) return 0;
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT budget FROM budget_tags WHERE name=? LIMIT 1",
                    new String[]{name});
            try {
                return c.moveToFirst() ? c.getDouble(0) : 0;
            } finally {
                c.close();
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** 本月某分类累计支出（正数）。查不到分类或没有支出时返回 0。 */
    public double monthSpentForTag(long sinceMs, String name) {
        if (name == null || name.isEmpty()) return 0;
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT SUM(-amount) FROM bills WHERE ts>=? AND amount<0 AND category=?",
                    new String[]{String.valueOf(sinceMs), name});
            try {
                return c.moveToFirst() ? c.getDouble(0) : 0;
            } finally {
                c.close();
            }
        } catch (Exception e) {
            return 0;
        }
    }
}
