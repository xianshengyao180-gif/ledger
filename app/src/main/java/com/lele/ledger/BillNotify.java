package com.lele.ledger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/** 仅在收到新的待确认转账时发送系统提醒，普通账单分类留在应用内处理。 */
public final class BillNotify {

    private static final String TAG = "Ledger";
    // 沿用渠道 ID，保留用户已设置的重要性、声音和振动偏好。
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

    /** 只有新的待确认转账事件可以触发此入口。 */
    public static void notifyPendingTransfer(Context ctx, Db db) {
        try {
            clearCategoryReminder(ctx);
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

            cancel(ctx);
        } catch (Throwable t) {
            Log.e(TAG, "转账待确认提醒发送失败", t);
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = nm.getNotificationChannel(CH_ID);
            if (ch == null) {
                ch = new NotificationChannel(CH_ID, "转账待确认", NotificationManager.IMPORTANCE_HIGH);
                ch.enableVibration(true);
                ch.setVibrationPattern(new long[]{0, 250, 150, 250});
            }
            ch.setName("转账待确认");
            ch.setDescription("收到需要核对金额的转账时提醒");
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
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVibrate(new long[]{0, 250, 150, 250})
                .setPriority(NotificationCompat.PRIORITY_HIGH);
        if (pi != null) {
            nb.setContentIntent(pi);
        }
        nm.notify(NOTI_ID, nb.build());
    }

    /** 更新或打开应用时，仅撤回旧版遗留的待分类通知。 */
    public static void clearCategoryReminder(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            for (StatusBarNotification notice : nm.getActiveNotifications()) {
                if (notice.getId() != NOTI_ID) continue;
                CharSequence title = notice.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
                if ("记了一笔收入".contentEquals(title == null ? "" : title)
                        || "记了一笔支出".contentEquals(title == null ? "" : title)) {
                    nm.cancel(NOTI_ID);
                }
            }
        } catch (Throwable ignored) {
            // 撤回旧提醒失败不会影响记账。
        }
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
