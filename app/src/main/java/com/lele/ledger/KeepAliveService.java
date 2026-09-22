package com.lele.ledger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * 常驻前台服务。
 *
 * 为什么必须有它（不是「保活优化」，是这个机型上能工作的前提）：
 *
 * 2026-09-18 在一加 Ace 3（ColorOS）上实测，应用进程没在运行时，
 * 系统的「应用启动管理」会直接拒绝绑定通知监听服务，系统日志原文：
 *
 *   I OplusAppStartupManager: prevent start com.lele.ledger,
 *       cmp {...NotiListener} by android callingUid 1000, Type bsfn
 *   W NotificationListeners: Unable to bind notification listener service
 *
 * 后果：监听服务从未真正连上，通知一条都收不到——表现为「一笔都记不上」。
 * 对照实验：把应用拉到前台（进程活着）后立刻绑定成功，监听器进入
 * 「Live notification listeners」列表，通知随即正常到达。
 *
 * 所以只要让进程一直活着，系统就能连上。前台服务正是为此。
 */
public final class KeepAliveService extends Service {

    private SmsObserver smsObserver;

    private static final String TAG = "Ledger";
    private static final String CH_ID = "keepalive";
    private static final int NOTI_ID = 1001;

    /** 供界面判断常驻服务在不在跑（与界面同进程，静态标志足够）。 */
    public static volatile boolean RUNNING = false;

    public static void start(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            Intent i = new Intent(ctx, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable t) {
            Log.e(TAG, "启动常驻服务失败", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTI_ID, n);
            }
        } catch (Throwable t) {
            // 前台服务没起来也要继续，尽力而为
            Log.e(TAG, "startForeground 失败", t);
        }
        RUNNING = true;
        notifyUi();
        // 进程刚起来时系统可能还没把监听绑上（尤其开机后），主动申请一次
        requestListenerRebind();
        // 如果无障碍权限已开启，尝试发送一个无害的辅助功能意图
        // 主要目的是引导用户去系统设置中开启无障碍服务
        promptAccessibilityIfNeeded();
        // 启动短信监听，读取银行到账/扣款短信
        startSmsObserver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        RUNNING = true;
        notifyUi();
        requestListenerRebind();
        // 每次重启也确保短信监听开着
        startSmsObserver();
        return START_STICKY;   // 被系统回收后尽量自动重建
    }

    /**
     * 告诉界面「我起来了」。
     *
     * 服务由 startForegroundService 异步启动，主界面 onResume 时它往往还没跑完，
     * 静态标志 RUNNING 仍是 false —— 首屏就会误报「常驻未开」。
     * 启动完成后主动广播一次，界面收到后立刻重查，显示才与实际一致。
     */
    private void notifyUi() {
        try {
            Intent i = new Intent(MainActivity.ACTION_BILLS_CHANGED);
            i.setPackage(getPackageName());
            sendBroadcast(i);
        } catch (Throwable ignored) {
            // 界面不在前台时没人接收，属正常情况
        }
    }

    @Override
    public void onDestroy() {
        RUNNING = false;
        if (smsObserver != null) {
            smsObserver.stop();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 检查无障碍服务状态并在日志中记录，方便调试时确认。
     */
    private void promptAccessibilityIfNeeded() {
        try {
            String svc = getPackageName() + "/" + WeChatAccessibilityService.class.getCanonicalName();
            boolean enabled = android.provider.Settings.Secure.getInt(
                    getContentResolver(),
                    android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
            if (!enabled) {
                Log.i(TAG, "无障碍未开启，提醒用户配置");
            } else {
                String enabledSvcs = android.provider.Settings.Secure.getString(
                        getContentResolver(),
                        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                if (enabledSvcs == null || !enabledSvcs.contains(svc)) {
                    Log.i(TAG, "微信无障碍记账服务未开启");
                } else {
                    Log.d(TAG, "微信无障碍记账服务已开启");
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "检查无障碍状态失败", t);
        }
    }

    /** 启动短信监听器 */
    private void startSmsObserver() {
        if (smsObserver == null) {
            smsObserver = new SmsObserver(this);
        }
        smsObserver.start();
    }

    /**
     * 主动申请重绑监听服务。
     * 开机后系统绑定可能早于本进程启动而失败，这里补一次，不必等用户去设置里手动开关。
     */
    private void requestListenerRebind() {
        try {
            NotificationListenerService.requestRebind(
                    new ComponentName(this, NotiListener.class));
        } catch (Throwable t) {
            Log.e(TAG, "申请重绑监听失败", t);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null || nm.getNotificationChannel(CH_ID) != null) {
                return;
            }
            // 低重要级：常驻但不能吵人，不发声、不震动、不出角标
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "后台记账", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("保持监听服务存活，付款后自动记账");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        } catch (Throwable t) {
            Log.e(TAG, "创建通知渠道失败", t);
        }
    }

    private Notification buildNotification() {
        PendingIntent pi = null;
        try {
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flag = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flag |= PendingIntent.FLAG_IMMUTABLE;
            }
            pi = PendingIntent.getActivity(this, 0, open, flag);
        } catch (Throwable ignored) {
            // 拿不到 PendingIntent 也要把常驻服务跑起来
        }
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_ID)
                .setSmallIcon(R.drawable.ic_stat_ledger)
                .setContentTitle("记账监听运行中")
                .setContentText("付款后会自动记账")
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        if (pi != null) {
            b.setContentIntent(pi);
        }
        return b.build();
    }
}
