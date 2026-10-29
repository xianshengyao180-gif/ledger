package com.lele.ledger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机 / 应用更新后自动拉起常驻服务。
 *
 * 为什么需要：监听服务和常驻服务都依赖进程活着。手机重启后进程必然没了，
 * 若不自动拉起，就要等用户手动打开一次 App——那期间所有付款都不会被记账。
 *
 * 注意：ColorOS 上还需要用户在「自启动管理」里允许本应用，否则本接收器
 * 根本不会被调用。界面里有对应引导（点首页的常驻服务状态行）。
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        Log.i("Ledger", "BootReceiver 收到 " + action);
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            BillNotify.clearCategoryReminder(context);
            KeepAliveService.start(context);
        }
    }
}
