package com.lele.ledger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Telephony;
import android.util.Log;

import java.util.regex.Pattern;

/**
 * 监听短信数据库，读取银行到账/扣款短信。
 *
 * 通知监听只能收微信/支付宝的推送通知，收不到 SMS。
 * 本类通过 ContentObserver 监听 content://sms 的变化，
 * 在每一条新短信进来时，用 BillParser 解析并入库。
 *
 * 需要 READ_SMS 权限（Android 6+ 动态申请）。
 *
 * 注意：Android 14+ 对 READ_SMS 做了限制，只允许「默认短信 App」
 * 或有特殊权限的应用使用。如果你的版本在 14 及以上且不是默认短信 App，
 * 本观察者不会收到回调（不报错，静默不工作）。
 * 这种情况可以回退到监听「短信 App 的通知」——但目前绝大多数
 * Android 13 以下（含 ColorOS 的 Android 14）仍能用 ContentObserver。
 */
public class SmsObserver {

    private static final String TAG = "LedgerSms";

    // 只处理发件人是银行/支付平台的短信
    // 实测常见银行号段 —— 不够全，后期可以加
    private static final Pattern BANK_SENDER = Pattern.compile(
            "(?i)"
            + "(955\\d{2}|"             // 95588 工行, 95533 建行, 95566 中行, 95559 交行
            + "106\\d{8,}|"             // 大多数银行的短信通道号
            + "\\d{5,11}" +             // 纯数字号码，5~11 位（覆盖大部分银行短号）
            ")"
    );

    // 不处理纯微信/支付宝通知（它俩的通知监听已经能记了）
    // 但也要提防号码是假的，所以 Heuristic 判断即可
    private static final Pattern MONEY_SMS = Pattern.compile(
            "(?s)"  // DOTALL
            + "(?:"
            + "收入|支出|扣款|消费|转入|转出|转账|到账|入账|入帳|退款|还款|還款|"
            + "汇款|匯款|交易|支付|进账|進帳|工资|工資|报销|報銷|"
            + "代付|付款|收款|提现|提現|结息|結息|"
            + "元|￥|¥|CNY|HKD|USD"
            + ")"
    );

    private final Context context;
    private final Handler handler;
    private final ContentObserver observer;
    private boolean registered = false;

    /** 最近处理的短信 uri，用于简单去重 */
    private String lastUri = "";

    public SmsObserver(Context context) {
        this.context = context.getApplicationContext();
        this.handler = new Handler(Looper.getMainLooper());
        this.observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                readLatestSms();
            }
        };
    }

    /** 开始监听短信收件箱 */
    public void start() {
        if (registered) return;
        try {
            context.getContentResolver().registerContentObserver(
                    Telephony.Sms.Inbox.CONTENT_URI,  // content://sms/inbox
                    true,       // 通知子孙 uri 的变化
                    observer
            );
            registered = true;
            Log.i(TAG, "短信监听已注册");
        } catch (SecurityException e) {
            Log.w(TAG, "没有 READ_SMS 权限，短信监听失败", e);
        }
    }

    /** 停止监听 */
    public void stop() {
        if (!registered) return;
        try {
            context.getContentResolver().unregisterContentObserver(observer);
        } catch (Exception ignored) {
        }
        registered = false;
        Log.i(TAG, "短信监听已注销");
    }

    /**
     * 短信数据库有变化时，读取最新的一条未读短信。
     * 因为 onChange 是"有内容变了"，不确定是新增还是删除，
     * 所以稳妥地只读最新的、过去 30 秒内的短信。
     */
    private void readLatestSms() {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{
                            Telephony.Sms.Inbox._ID,
                            Telephony.Sms.Inbox.ADDRESS,
                            Telephony.Sms.Inbox.BODY,
                            Telephony.Sms.Inbox.DATE
                    },
                    null, null,
                    Telephony.Sms.Inbox.DATE + " DESC LIMIT 1"
            );
            if (cursor == null || !cursor.moveToFirst()) {
                return;
            }

            long id = cursor.getLong(0);
            String address = cursor.getString(1);
            String body = cursor.getString(2);
            long date = cursor.getLong(3);

            // 去重：同一条短信的 uri 不会变
            String uriCheck = "sms_" + id;
            if (uriCheck.equals(lastUri)) {
                return;
            }
            lastUri = uriCheck;

            // 只处理 60 秒内的短信，避免重复读旧短信
            long now = System.currentTimeMillis();
            if (now - date > 60_000) {
                return;
            }

            // 校验发件人：必须像银行/支付通道
            if (address == null || !BANK_SENDER.matcher(address).matches()) {
                return;
            }

            // 内容里必须有金钱相关的关键字
            if (body == null || !MONEY_SMS.matcher(body).find()) {
                return;
            }

            Log.i(TAG, "发现银行短信 from=" + address + " body=" + body);

            // 用已有的 BillParser 解析
            Bill bill = BillParser.parse("", "短信", body);
            if (bill == null) {
                Log.d(TAG, "BillParser 未能解析此短信");
                return;
            }

            // 写库
            Db db = new Db(context);
            try {
                String nkey = "sms_" + address + "_" + body.hashCode();
                boolean dup = db.seenKey(nkey, 60_000);
                if (dup) {
                    Log.d(TAG, "重复短信，跳过");
                    return;
                }

                long newId = db.insert(bill.amount, bill.channel, bill.title, date, nkey);
                if (newId > 0) {
                    Log.i(TAG, "短信已记账 #" + newId + " " + bill.amount + " " + bill.title);
                    notifyUi();
                }
            } finally {
                db.close();
            }

        } catch (SecurityException e) {
            Log.w(TAG, "READ_SMS 权限不足", e);
        } catch (Throwable t) {
            Log.e(TAG, "读取短信失败", t);
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void notifyUi() {
        try {
            Intent i = new Intent(MainActivity.ACTION_BILLS_CHANGED);
            i.setPackage(context.getPackageName());
            context.sendBroadcast(i);
        } catch (Throwable t) {
            Log.e(TAG, "广播刷新失败", t);
        }
    }
}