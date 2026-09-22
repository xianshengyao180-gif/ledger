package com.lele.ledger;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.Calendar;
import java.util.Locale;

/**
 * 月度预算与预警。
 *
 * 总预算存 SharedPreferences（兼容旧版），分类预算存在 db.budget_tags 表。
 * 每档提醒只发一次，按「年-月 + 档位」记标记。跨月标记自动失效。
 */
public final class Budget {

    private static final String TAG = "Ledger";
    private static final String FILE = "budget";
    private static final String K_AMOUNT = "amount";
    private static final String K_THRESHOLD = "threshold";

    /** 默认预警线：花到预算的 80% 提醒一次。 */
    public static final int DEFAULT_THRESHOLD = 80;

    private static final String CH_ID = "budget";
    private static final String CH_TAG_ID = "budget_tag";
    private static final int NOTI_ID = 2001;
    private static final int TAG_NOTI_BASE = 2100;

    private Budget() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 月度总预算；0 表示还没设。 */
    public static double amount(Context c) {
        try {
            return Double.parseDouble(sp(c).getString(K_AMOUNT, "0"));
        } catch (Exception e) {
            return 0;
        }
    }

    public static void setAmount(Context c, double v) {
        sp(c).edit().putString(K_AMOUNT, String.valueOf(v)).apply();
    }

    /** 预警阈值（百分比）。0 表示不做中途预警，只在超支时提醒。 */
    public static int threshold(Context c) {
        return sp(c).getInt(K_THRESHOLD, DEFAULT_THRESHOLD);
    }

    public static void setThreshold(Context c, int v) {
        sp(c).edit().putInt(K_THRESHOLD, v).apply();
    }

    private static boolean fired(Context c, int pct, String monthKey) {
        return sp(c).getBoolean("fired_" + monthKey + "_" + pct, false);
    }

    private static void markFired(Context c, int pct, String monthKey) {
        sp(c).edit().putBoolean("fired_" + monthKey + "_" + pct, true).apply();
    }

    /** 分类预算：某分类的预警标记。 */
    private static boolean tagFired(Context c, String monthKey, String tagName) {
        return sp(c).getBoolean("tag_fired_" + monthKey + "_" + tagName, false);
    }

    private static void markTagFired(Context c, String monthKey, String tagName) {
        sp(c).edit().putBoolean("tag_fired_" + monthKey + "_" + tagName, true).apply();
    }

    /** 取某分类的预警阈值。没单独设则继承总预算阈值。 */
    public static int tagThreshold(Context c, String tagName) {
        return sp(c).getInt("tag_th_" + tagName, DEFAULT_THRESHOLD);
    }

    public static void setTagThreshold(Context c, String tagName, int v) {
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        sp(c).edit().putInt("tag_th_" + tagName, v).apply();
    }

    // ---------------- 月份 ----------------

    public static long monthStart() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** 形如 202609。用作「本月是否已提醒」的标记前缀。 */
    public static String monthKey() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.CHINA, "%04d%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1);
    }

    /** 本月剩余天数（含今天）。 */
    public static int daysLeft() {
        Calendar c = Calendar.getInstance();
        int today = c.get(Calendar.DAY_OF_MONTH);
        int last = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        return Math.max(1, last - today + 1);
    }

    // ---------------- 总预算预警 ----------------

    /**
     * 记完一笔账后调用：本月支出跨过总预算预警线就发一条通知。
     *
     * @param spent 本月累计支出（正数）
     */
    public static void checkAndAlert(Context ctx, double spent) {
        double budget = amount(ctx);
        if (budget <= 0 || spent <= 0) {
            return;
        }
        int pct = (int) Math.floor(spent * 100 / budget);
        String mk = monthKey();

        if (pct >= 100) {
            if (fired(ctx, 100, mk)) {
                return;
            }
            markFired(ctx, 100, mk);
            post(ctx, "预算已用完",
                    String.format(Locale.CHINA, "本月已花 ¥%.2f，超出预算 ¥%.2f",
                            spent, spent - budget));
            return;
        }

        int th = threshold(ctx);
        if (th <= 0 || pct < th || fired(ctx, th, mk)) {
            return;
        }
        markFired(ctx, th, mk);
        post(ctx, "预算预警",
                String.format(Locale.CHINA, "本月已花 ¥%.2f，已达预算 %d%%，剩余 ¥%.2f",
                        spent, pct, budget - spent));
    }

    // ---------------- 分类预算预警 ----------------

    /**
     * 检查某分类当月支出是否超过该分类预算。
     * 每条新记录归类后调用一次。
     */
    public static void checkTagAndAlert(Context ctx, Db db, String tagName) {
        if (tagName == null || tagName.isEmpty()) return;
        double tagBudget = db.budgetForTag(tagName);
        if (tagBudget <= 0) return;

        double spent = db.monthSpentForTag(monthStart(), tagName);
        if (spent <= 0) return;

        String mk = monthKey();
        if (tagFired(ctx, mk, tagName)) return;

        int th = tagThreshold(ctx, tagName);

        if (spent >= tagBudget) {
            markTagFired(ctx, mk, tagName);
            postTag(ctx, tagName,
                    String.format(Locale.CHINA, "「%s」本月已花 ¥%.2f，超出预算 ¥%.2f",
                            tagName, spent, spent - tagBudget));
        } else if (th > 0) {
            int pct = (int) Math.floor(spent * 100 / tagBudget);
            if (pct >= th) {
                markTagFired(ctx, mk, tagName);
                postTag(ctx, tagName,
                        String.format(Locale.CHINA, "「%s」已花 ¥%.2f，达预算 %d%%，剩余 ¥%.2f",
                                tagName, spent, pct, tagBudget - spent));
            }
        }
    }

    // ---------------- 通知发送 ----------------

    private static void post(Context ctx, String title, String text) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            ensureChannel(nm, CH_ID, "预算提醒", NotificationManager.IMPORTANCE_DEFAULT);

            PendingIntent pi = buildOpenIntent(ctx);
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CH_ID)
                    .setSmallIcon(R.drawable.ic_stat_ledger)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            if (pi != null) b.setContentIntent(pi);
            nm.notify(NOTI_ID, b.build());
        } catch (Throwable t) {
            Log.e(TAG, "预算提醒发送失败", t);
        }
    }

    private static void postTag(Context ctx, String tagName, String text) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            ensureChannel(nm, CH_TAG_ID, "分类预算提醒", NotificationManager.IMPORTANCE_DEFAULT);

            PendingIntent pi = buildOpenIntent(ctx);
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CH_TAG_ID)
                    .setSmallIcon(R.drawable.ic_stat_ledger)
                    .setContentTitle("分类预算提醒 · " + tagName)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            if (pi != null) b.setContentIntent(pi);

            int notiId = TAG_NOTI_BASE + Math.abs(tagName.hashCode() % 100);
            nm.notify(notiId, b.build());
        } catch (Throwable t) {
            Log.e(TAG, "分类预算提醒发送失败", t);
        }
    }

    private static void ensureChannel(NotificationManager nm, String id, String name, int importance) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && nm.getNotificationChannel(id) == null) {
            NotificationChannel ch = new NotificationChannel(id, name, importance);
            nm.createNotificationChannel(ch);
        }
    }

    private static PendingIntent buildOpenIntent(Context ctx) {
        try {
            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flag = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flag |= PendingIntent.FLAG_IMMUTABLE;
            }
            return PendingIntent.getActivity(ctx, 0, open, flag);
        } catch (Throwable ignored) {
            return null;
        }
    }
}