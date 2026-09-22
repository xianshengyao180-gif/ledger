package com.lele.ledger;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.regex.Pattern;

/**
 * 核心：通知监听。
 *
 * 系统在用户授权「通知使用权」后，会把所有通知投递到 onNotificationPosted。
 * 我们做四件事：取文本 -> 存原文(排查用) -> 交给 BillParser -> 入库 -> 广播通知界面刷新。
 * 不联网、不上传、不做任何别的事。
 */
public class NotiListener extends NotificationListenerService {

    private static final String TAG = "Ledger";
    private static final long DEDUPE_WINDOW_MS = 60_000L;

    /**
     * 心跳间隔。作用：在原始通知表里定期留一行，从而能判断「服务是不是还活着」。
     * 排查「某笔没记上」时这是关键证据——
     *   有心跳到那个时间点 → 服务活着 → 说明那笔微信/支付宝根本没发通知；
     *   心跳在某个时刻断了 → 服务被杀 → 那才是保活问题。
     */
    private static final long HEARTBEAT_MS = 10 * 60_000L;

    /** 标记行的「包名」。不是真实通知，只用于在时间线上标出服务状态。 */
    public static final String MARK = "★";

    /** 这两个包的通知一律存原文。 */
    private static final String PKG_WECHAT = "com.tencent.mm";
    private static final String PKG_ALIPAY = "com.eg.android.AlipayGphone";

    /**
     * 其它 App（银行、云闪付等）只在文案里出现钱味时才存原文，
     * 免得被系统通知刷满、把有用的挤掉。
     */
    private static final Pattern MONEY_HINT = Pattern.compile(
            "转账|付款|支付|收款|扣款|消费|支出|收入|退款|到账|入账|元|￥|¥|RMB|CNY"
    );

    private Handler heartbeat;

    private final Runnable beat = new Runnable() {
        @Override
        public void run() {
            mark("心跳", "监听服务仍在运行");
            if (heartbeat != null) {
                heartbeat.postDelayed(this, HEARTBEAT_MS);
            }
        }
    };

    /** 系统把通知投递权交给本应用时触发。等于「服务已就绪」的起点。 */
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "onListenerConnected 已连接");
        mark("监听已连接", "系统开始把通知投递给本应用");
        startHeartbeat();
    }

    /** 服务被系统断开时触发。断开之后的通知收不到，且不会自动补发。 */
    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.i(TAG, "onListenerDisconnected 已断开");
        mark("监听已断开", "服务被系统断开，尝试自动重新绑定");
        stopHeartbeat();
        // 系统偶尔会断开监听。主动申请重绑能自愈，不必等用户去设置里手动开关。
        try {
            requestRebind(new ComponentName(this, NotiListener.class));
        } catch (Throwable t) {
            Log.e(TAG, "申请重绑失败", t);
        }
    }

    private void startHeartbeat() {
        if (heartbeat == null) {
            heartbeat = new Handler(Looper.getMainLooper());
        }
        heartbeat.removeCallbacks(beat);
        heartbeat.postDelayed(beat, HEARTBEAT_MS);
    }

    private void stopHeartbeat() {
        if (heartbeat != null) {
            heartbeat.removeCallbacks(beat);
        }
    }

    /** 写一行状态标记到原始通知表。失败也不影响主流程。 */
    private void mark(String title, String body) {
        try {
            Db db = new Db(getApplicationContext());
            try {
                db.insertRaw(MARK, title, body);
            } finally {
                db.close();
            }
        } catch (Throwable t) {
            Log.e(TAG, "写状态标记失败", t);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) {
            return;
        }
        // 绝不处理自己发的通知。
        //
        // 这是个真实踩过的坑：本应用发出的提醒文案里本身就带「转账已被接收」等字样，
        // 它又会被投回这里、被当成一笔新的转账 → 再存一条待确认 → 再发提醒……
        // 自反馈、无上限增长。同理，「记了一笔支出 -¥1.00」也会被解析成一条重复账单。
        // 记账应用绝不该给自己的通知记账。
        if (getPackageName().equals(sbn.getPackageName())) {
            return;
        }
        // 入口就记一笔：即使后面解析失败，也能证明「通知确实进来了」
        try {
            Log.i(TAG, "通知到达 pkg=" + sbn.getPackageName() + " key=" + sbn.getKey());
        } catch (Throwable ignored) {
            // 日志失败不影响主流程
        }
        // 出错时还要能说清「是哪条通知出的事」，否则事后无从查起
        String dbgTitle = "";
        String dbgText = "";
        try {
            Bundle ex = sbn.getNotification().extras;
            if (ex == null) {
                return;
            }
            String title = str(ex.getCharSequence(Notification.EXTRA_TITLE));
            String text = join(
                    str(ex.getCharSequence(Notification.EXTRA_TEXT)),
                    str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT)),
                    str(ex.getCharSequence(Notification.EXTRA_SUB_TEXT))
            );

            dbgTitle = title;
            dbgText = text;
            String pkg = sbn.getPackageName() == null ? "" : sbn.getPackageName();
            // 微信/支付宝的通知需要深度解析
            // 转账和红包的金额可能在 EXTRA_MESSAGES 等字段里
            if (PKG_WECHAT.equals(pkg) || PKG_ALIPAY.equals(pkg)) {
                String deepText = deepExtractText(ex);
                if (!text.contains(deepText)) {
                    text = text + " " + deepText;
                }
            }
            // 系统为每条通知分配的稳定标识。同一条通知被重复投递时它不变，
            // 不同的两笔支付则一定不同——这正是精确去重需要的。
            String nkey = sbn.getKey();

            boolean recorded = false;
            // 记完这笔之后的本月支出。预算预警要用它，而 db 在 finally 里就关了，
            // 所以必须在关之前算出来带出去。
            double spentAfter = -1;
            // 这笔的 id，用来让「待分类」提醒直接跳到它
            long newId = -1;
            // 有没有新塞进一条待确认转账
            boolean pendingAdded = false;
            Db db = new Db(getApplicationContext());
            try {
                if (shouldLogRaw(pkg, title, text)) {
                    db.insertRaw(pkg, title, text);
                }

                Bill bill = BillParser.parse(pkg, title, text);
                // 注意：这里不能用 return 提前退出。下面还接着写库、广播刷新、
                // 发提醒通知——早退会把它们全跳过（实测：转账入库了但界面不刷、
                // 提醒也不弹）。所以用嵌套判断，而不是早退。
                if (bill != null && bill.pending) {
                    // 转账「已被接收」：通知里没有金额、方向也有歧义，不能猜。
                    // 存进待确认表，由界面向用户问清楚——绝不直接写进账本。
                    if (!db.pendingSeen(nkey)) {
                        db.insertPending(nkey, bill.amount, bill.channel, bill.title);
                        Log.i(TAG, "待确认转账：" + bill.channel + " " + bill.title
                                + " amount=" + bill.amount);
                        pendingAdded = true;
                    }
                } else if (bill != null) {
                    // 优先用通知标识去重；拿不到才退回「金额+渠道+标题」。
                    // 因为微信所有付款通知标题都叫「Weixin Pay」，用后者会把
                    // 一分钟内两笔同额真实支付误判为重复而丢掉一笔。
                    boolean dup = nkey != null && !nkey.isEmpty()
                            ? db.seenKey(nkey, DEDUPE_WINDOW_MS)
                            : db.existsRecent(bill.amount, bill.channel, bill.title, DEDUPE_WINDOW_MS);
                    if (dup) {
                        Log.d(TAG, "重复通知，跳过");
                    } else {
                        long id = db.insert(bill.amount, bill.channel, bill.title, bill.ts, nkey);
                        // insert 失败时返回 -1。只有真写进去了才算记上，
                        // 否则界面会白刷一下、日志会假报成功，反而掩盖了故障。
                        recorded = id > 0;
                        if (recorded) {
                            newId = id;
                            Log.i(TAG, "已记账 #" + id + " " + bill.channel + " " + bill.amount + " " + bill.title);
                            // 只有支出才可能触发预算预警，收入不会
                            if (bill.amount < 0) {
                                spentAfter = db.monthTotals(Budget.monthStart())[1];
                            }
                        } else {
                            Log.e(TAG, "写库失败，未记账：" + bill.channel + " " + bill.amount);
                            mark("写库失败", bill.channel + " " + bill.amount + " ｜" + bill.title);
                        }
                    }
                }
            } finally {
                db.close();
            }

            // 库写完了再广播，确保界面收到时一定能查到这笔
            if (recorded || pendingAdded) {
                notifyUi();
            }
            if (recorded) {
                // 越过预算预警线就发一条通知。放在最后：
                // 发通知万一失败，也不该影响「已经记上了」这个结果。
                if (spentAfter >= 0) {
                    Budget.checkAndAlert(getApplicationContext(), spentAfter);
                }
                // 提醒用户给这笔选个用途。同样放在最后，失败不影响记账。
                if (newId > 0) {
                    Db d2 = new Db(getApplicationContext());
                    try {
                        BillNotify.refresh(getApplicationContext(), d2);
                    } finally {
                        d2.close();
                    }
                }
            } else if (pendingAdded) {
                // 转账待确认：同样出一条可点的提醒
                Db d2 = new Db(getApplicationContext());
                try {
                    BillNotify.refresh(getApplicationContext(), d2);
                } finally {
                    d2.close();
                }
            }
        } catch (Throwable t) {
            // 通知内容千奇百怪，绝不能因为一条通知解析失败就崩掉整个服务
            Log.e(TAG, "处理通知失败", t);
            // 关键：把异常写进「原始通知」。用户不连电脑、不抓日志，
            // 也能直接在 App 里看到并复制出来。
            try {
                String detail = t + " ｜标题:" + dbgTitle + " ｜正文:" + dbgText;
                if (detail.length() > 300) {
                    detail = detail.substring(0, 300);
                }
                mark("处理异常", detail);
            } catch (Throwable ignored) {
                // 连错误都写不进去也没办法
            }
        }
    }

    /**
     * 通知界面刷新。
     *
     * 界面在后台时没人接收，也没关系——切回前台时 onResume 会自己刷一遍。
     * 这样「App 开着时」就能实时看到新记录，不必切后台再回来。
     */
    private void notifyUi() {
        try {
            Intent i = new Intent(MainActivity.ACTION_BILLS_CHANGED);
            i.setPackage(getPackageName());
            sendBroadcast(i);
        } catch (Throwable t) {
            Log.e(TAG, "广播刷新失败", t);
        }
    }

    private static boolean shouldLogRaw(String pkg, String title, String text) {
        if (PKG_WECHAT.equals(pkg) || PKG_ALIPAY.equals(pkg) || pkg.startsWith("com.alipay.")) {
            return true;
        }
        return MONEY_HINT.matcher(title + " " + text).find();
    }

    /**
     * 从通知 extras 的深层字段中提取文本。
     *
     * 微信/支付宝的转账/红包通知，金额经常不在 EXTRA_TEXT 里，
     * 而是藏在 EXTRA_MESSAGES（聊天消息列表）、EXTRA_SUMMARY_TEXT
     * （折叠摘要）、EXTRA_INFO_TEXT 等字段中。
     * 光读 EXTRA_TEXT 常常只拿到「已被接收」四个字，没有金额。
     */
    private static String deepExtractText(Bundle ex) {
        StringBuilder sb = new StringBuilder();

        // EXTRA_MESSAGES: 微信聊天通知的消息列表（转账/红包的金额经常在这里）
        Object msgs = ex.get(Notification.EXTRA_MESSAGES);
        if (msgs instanceof CharSequence[]) {
            for (CharSequence m : (CharSequence[]) msgs) {
                String s = str(m);
                if (!s.isEmpty()) {
                    sb.append(' ').append(s);
                }
            }
        } else if (msgs instanceof CharSequence) {
            sb.append(' ').append((CharSequence) msgs);
        }

        // EXTRA_SUMMARY_TEXT: 折叠通知的摘要文本
        String summary = str(ex.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
        if (!summary.isEmpty()) {
            sb.append(' ').append(summary);
        }

        // EXTRA_INFO_TEXT: 备用文本
        String info = str(ex.getCharSequence(Notification.EXTRA_INFO_TEXT));
        if (!info.isEmpty()) {
            sb.append(' ').append(info);
        }

        // EXTRA_TEXT_LINES: 多行文本（部分通知用这个）
        Object lines = ex.get(Notification.EXTRA_TEXT_LINES);
        if (lines instanceof CharSequence[]) {
            for (CharSequence line : (CharSequence[]) lines) {
                String s = str(line);
                if (!s.isEmpty()) {
                    sb.append(' ').append(s);
                }
            }
        }

        return sb.toString().trim();
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(p);
            }
        }
        return sb.toString();
    }
}
