package com.lele.ledger;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.Locale;

/**
 * 「记了一笔，选个用途」的提醒。
 *
 * 为什么用横幅通知而不是直接弹悬浮窗：Android 10 起后台应用不允许直接弹出 Activity，
 * 想盖在别的应用上得申请「悬浮窗」权限。而高重要级通知会以横幅落到屏幕顶端、
 * 点一下就是分类页——不需要额外权限，也不会因为没授权就静默什么都不发生。
 *
 * 只占一个通知位（NOTI_ID 固定）：连续记几笔时不会堆出一排通知，
 * 而是同一张卡片不断更新，文案里带上「还有 N 笔待分类」。
 */
public final class BillNotify {

    private static final String TAG = "Ledger";
    // 渠道一旦创建，除了名称和描述，振动/重要性都归用户所有，App 再也改不动。
    // 旧渠道 categorize 建的时候没带振动，怎么改代码都改不动它（实测踩过）。
    // 所以换个新 id，用新渠道把振动带上。
    private static final String CH_ID = "categorize_v2";
    private static final int NOTI_ID = 3000;

    /**
     * 待办目标 Activity 读取的 id。
     * 分类页和转账页的 extra 名字不同，这里统一成一个常量，
     * 免得两边各写一份、改一处忘一处。
     */
    public static final String EXTRA_KEY = "bill_id";

    private BillNotify() {
    }

    /** 按当前「待确认转账 / 待分类」的数量决定发通知还是撤通知。 */
    public static void refresh(Context ctx, Db db) {
        try {
            // 待确认的转账优先：它金额都还没定，不处理就永远记不上。
            Bill pendingTransfer = db.nextPending();
            int pendingCount = db.countPending();
            if (pendingTransfer != null) {
                notify(ctx, "检测到一笔转账",
                        (pendingTransfer.title == null || pendingTransfer.title.isEmpty()
                                ? "转账" : pendingTransfer.title)
                                + " · 转账已被接收"
                                + (pendingCount > 1 ? "（还有 " + pendingCount + " 笔）" : "")
                                + "　点此核对金额",
                        TransferActivity.class, pendingTransfer.id);
                return;
            }

            Bill b = db.nextUncategorized();
            if (b == null) {
                cancel(ctx);
                return;
            }
            int count = db.countUncategorized();
            String amount = String.format(Locale.CHINA, "%s¥%.2f",
                    b.amount >= 0 ? "+" : "-", Math.abs(b.amount));
            String title = b.amount >= 0 ? "记了一笔收入" : "记了一笔支出";
            String text = amount + " · " + b.channel
                    + (count > 1 ? "（还有 " + count + " 笔待分类）" : "")
                    + "　点此选择用途";
            notify(ctx, title, text, CategorizeActivity.class, b.id);
        } catch (Throwable t) {
            Log.e(TAG, "待分类提醒发送失败", t);
        }
    }

    /** 发一条可点的提醒。target 点开后能直接定位到这一笔。 */
    private static void notify(Context ctx, String title, String text,
                               Class<?> target, long id) {
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && nm.getNotificationChannel(CH_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "待分类提醒", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("检测到收付款后提醒你选择用途");
            // 横幅只飘三秒左右，配一段短振动让它能被注意到。
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 250, 150, 250});
            nm.createNotificationChannel(ch);
        }

        PendingIntent pi = null;
        try {
            Intent open = new Intent(ctx, target);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            open.putExtra(EXTRA_KEY, id);
            int flag = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flag |= PendingIntent.FLAG_IMMUTABLE;
            }
            pi = PendingIntent.getActivity(ctx, (int) (id % 100000), open, flag);
        } catch (Throwable ignored) {
            // 拿不到点击入口也要把提醒发出去
        }

        NotificationCompat.Builder nb = new NotificationCompat.Builder(ctx, CH_ID)
                .setSmallIcon(R.drawable.ic_stat_ledger)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVibrate(new long[]{0, 250, 150, 250})
                .setPriority(NotificationCompat.PRIORITY_HIGH);
        if (pi != null) {
            nb.setContentIntent(pi);
        }
        nm.notify(NOTI_ID, nb.build());
    }

    public static void cancel(Context ctx) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(NOTI_ID);
            }
        } catch (Throwable ignored) {
            // 撤不掉也无所谓
        }
    }
}
