package com.lele.ledger;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通过无障碍服务监听微信聊天界面，自动记录转账/红包入账。
 *
 * ## 为什么需要这个
 *
 * NotificationListenerService 在以下两种情况收不到微信转账通知：
 *   1. ColorOS（一加 Ace 3 等机型）拒绝绑定非活跃进程的通知监听服务。
 *   2. 新版微信（2025+）部分转账/红包状态更新不走系统通知，只更新聊天界面 UI。
 *
 * 无障碍服务直接读取当前界面文字，不受上述限制。
 *
 * ## 工作原理
 *
 * 当微信聊天界面出现以下文案时，从界面文字中提取金额和对方名称，记作收入。
 *
 *   - "[转账] 已被接收"
 *   - "[转账]100.00 已被接收"
 *   - "转账金额100.00元 已被接收"
 *   - "红包已被领取"
 *   - "¥100.00 已被领取"
 *
 * 提取到金额后调用 {@link Db#insert} 写入账本（direction = 收入），
 * 并通过 {@link MainActivity#ACTION_BILLS_CHANGED} 广播通知界面刷新。
 *
 * ## 去重
 *
 * 同一聊天界面可能多次触发无障碍事件。用"事件摘要哈希 + 2 秒窗口"防重复记入。
 *
 * ## 安全限制
 *
 * - 只处理包名 com.tencent.mm 的事件
 * - 所有解析在本地完成，不联网
 * - 不需要额外权限
 */
public final class WeChatAccessibilityService extends AccessibilityService {

    private static final String TAG = "Ledger";
    private static final String PKG_WECHAT = "com.tencent.mm";
    private static final String CHANNEL = "微信";

    /** 去重时间窗口（毫秒）：同一金额的摘要 2 秒内不重复记。 */
    private static final long DEDUPE_WINDOW_MS = 2000L;

    /** 事件节流：相同的事件 root 类名在 throttleMs 内忽略。 */
    private static final long THROTTLE_MS = 500L;

    /** 收款相关关键词。只匹配转账详情页的出现。
     *
     * 注意：聊天列表里的「X.XX 已收款」「转账 已接收」不算，
     * 只有详情页里「金额+已被接收」的组合才算。
     */
    private static final Pattern RECEIVED_PATTERN = Pattern.compile(
            "已被接收|已被領取|已被领取|已存入零钱|已存入零錢"
    );

    /** 金额提取模式：支持 ￥100.00、100.00元、¥100、100元 等形式 */
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(?:￥|¥|RMB|CNY)?\\s*([0-9]+(?:[.,][0-9]{1,2})?)"
    );

    /** 最后一次去重的时间戳 + 金额（用于防同一事件反复触发） */
    private volatile double lastAmount = 0;
    private volatile long lastEventTs = 0;
    /** 最后一次处理的事件 root 类名，用于节流重复事件 */
    private volatile String lastRootClass = "";

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        // 只处理微信包名
        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!PKG_WECHAT.equals(pkg)) return;

        // 事件类型过滤：只处理窗口内容变化和文本变化
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            return;
        }

        // 节流：相同 root 类名的快速重复事件直接跳过
        String rootClass = event.getClassName() != null ? event.getClassName().toString() : "";
        long now = System.currentTimeMillis();
        if (rootClass.equals(lastRootClass) && (now - lastEventTs) < THROTTLE_MS) {
            return;
        }
        lastRootClass = rootClass;
        lastEventTs = now;

        // 从事件源开始遍历 View 树
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) return;

        try {
            String allText = collectText(source);
            if (allText.isEmpty()) return;

            Log.d(TAG, "微信界面文本: " + allText);

            // 先确认是详情页：必须有"已被接收"或"已被领取"等确认文字
            if (!RECEIVED_PATTERN.matcher(allText).find()) return;

            // 提取金额
            double amount = extractAmount(allText);
            if (amount <= 0) {
                Log.d(TAG, "未提取到金额，跳过");
                return;
            }

            // 从根节点再确认一下这是详情页而非聊天列表
            // 详情页特征：存在一个明确的大金额节点（>5个字符的可解析金额），
            // 而聊天列表里的金额总是很短（"0.01"）且周围没有"已被接收"等完整文案
            if (!isTransferDetailPage(allText)) {
                Log.d(TAG, "非详情页，跳过");
                return;
            }

            // 提取对方名称：从 event 的 source 或 rootInActiveWindow 中找
            String who = extractWho(source, allText);

            // 去重检查：同一金额 2 秒内不再记
            if (Math.abs(amount - lastAmount) < 0.01 && (now - lastEventTs) < DEDUPE_WINDOW_MS) {
                Log.d(TAG, "去重跳过: " + amount + " " + who);
                return;
            }
            lastAmount = amount;
            lastEventTs = now;

            // 生成去重用的 nkey（无障碍服务没有系统通知的 key，自己算一个）
            String nkey = "as_" + Math.abs((allText + amount).hashCode());

            // 入库（收入 = 正数）
            Db db = new Db(getApplicationContext());
            try {
                // 先查数据库去重：5 分钟内同一 nkey 不再记
                if (db.seenKey(nkey, 300_000L)) {
                    Log.d(TAG, "数据库去重跳过: " + amount);
                    return;
                }

                String title = who.isEmpty() ? "转账/红包" : who;
                long id = db.insert(amount, CHANNEL, title, System.currentTimeMillis(), nkey);
                if (id > 0) {
                    Log.i(TAG, "无障碍记账成功 #" + id + " ￥" + amount + " " + who);
                    // 广播通知界面刷新
                    sendRefreshBroadcast();
                    // 发待分类通知（和通知监听器的逻辑保持一致）
                    BillNotify.refresh(getApplicationContext(), db);
                } else {
                    Log.e(TAG, "无障碍写库失败，未记账: ￥" + amount);
                }
            } finally {
                db.close();
            }

        } catch (Throwable t) {
            Log.e(TAG, "处理无障碍事件失败", t);
        } finally {
            if (source != null) {
                source.recycle();
            }
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "无障碍服务被中断");
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "微信无障碍记账服务已连接");
    }

    /**
     * 递归遍历 AccessibilityNodeInfo 树，收集所有可见文本。
     */
    private static String collectText(AccessibilityNodeInfo node) {
        if (node == null) return "";

        StringBuilder sb = new StringBuilder();
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
        stack.push(node);

        while (!stack.isEmpty()) {
            AccessibilityNodeInfo n = stack.pop();
            if (n == null) continue;

            // 收集节点的文本
            CharSequence text = n.getText();
            if (text != null && text.length() > 0) {
                sb.append(' ').append(text);
            }

            // 收集 contentDescription（有些 UI 把金额放在 description 里）
            CharSequence desc = n.getContentDescription();
            if (desc != null && desc.length() > 0) {
                sb.append(' ').append(desc);
            }

            // 子节点入栈
            for (int i = n.getChildCount() - 1; i >= 0; i--) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) {
                    stack.push(child);
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * 从文本中提取第一个正数金额。
     *
     * 支持格式：
     *   - ¥100.00、￥100、¥100.5
     *   - 100.00元、100元
     *   - 前后跟"转账"、"金额"等关键词的数字
     *   - 纯数字如 "100.00"
     */
    private static double extractAmount(String text) {
        if (text == null || text.isEmpty()) return 0;

        // 先尝试带货币符号或"元"的模式
        Pattern currencyPattern = Pattern.compile(
                "(?:￥|¥|RMB|CNY)\\s*([0-9]+(?:[,.][0-9]{1,2})?)"
        );
        Matcher m = currencyPattern.matcher(text);
        if (m.find()) {
            return parseAmount(m.group(1));
        }

        // 尝试 "X元" / "X块" / "X圆" 模式
        Pattern yuanPattern = Pattern.compile(
                "([0-9]+(?:[,.][0-9]{1,2})?)\\s*(?:元|块|圆|塊|圓)"
        );
        m = yuanPattern.matcher(text);
        if (m.find()) {
            return parseAmount(m.group(1));
        }

        // 尝试语义相邻模式："金额100.00"、"转账100.00"、"已收100.00"
        Pattern semanticPattern = Pattern.compile(
                "(?:金额|金額|转账|轉賬|轉帳|红包|紅包|收款|已收|¥|￥)"
                        + "\\s*([0-9]+(?:[,.][0-9]{1,2})?)"
        );
        m = semanticPattern.matcher(text);
        if (m.find()) {
            return parseAmount(m.group(1));
        }

        // 兜底：如果文本很短（<=20 字符），尝试直接找第一个数字
        if (text.length() <= 30) {
            Pattern fallback = Pattern.compile("([0-9]+(?:[,.][0-9]{1,2})?)");
            m = fallback.matcher(text);
            if (m.find()) {
                double d = parseAmount(m.group(1));
                // 只接受合理金额
                if (d > 0 && d < 999999) return d;
            }
        }

        return 0;
    }

    /**
     * 解析金额字符串，兼容中英文逗号分隔。
     */
    private static double parseAmount(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 从界面文字中提取转账/红包的对方名称。
     *
     * 策略：
     *   1. 看事件源所在窗口的标题（如果事件 source 在某个 Activity 内）
     *   2. 从文本中尝试提取："向XXX转账"、"来自XXX的红包"
     *   3. 兜底：取根节点 rootInActiveWindow 的参考信息
     */
    private static String extractWho(AccessibilityNodeInfo source, String allText) {
        // 尝试从文本中提取对方名字
        // 微信聊天界面里，转账气泡上方或下方通常会显示对方昵称
        // 常见模式：
        //   "向 XXX 转账"  → 我转给 XXX
        //   "来自 XXX 的红包" → XXX 发给我
        //   "XX 的红包" → XXX 发的
        //   聊天界面标题通常是对方名字

        String text = allText;

        // 模式 1: "来自XXX的红包" / "来自 XXX 的转账"
        Pattern fromPattern = Pattern.compile("来自(.{1,20})(?:的红包|的转账|的轉賬|的轉帳)");
        Matcher m = fromPattern.matcher(text);
        if (m.find()) return m.group(1).trim();

        // 模式 2: "向XXX转账" → 我转给XXX，不是收入，不提取
        // 但注意我们的场景是"已被接收"，此时对方名字可能在别处

        // 模式 3: 找纯中文名前后的转账/红包标记
        // 如 "张三 [转账] 已被接收"
        Pattern whoBeforePattern = Pattern.compile(
                "([\\u4e00-\\u9fff\\w]{1,20})\\s*[\\[\\[]?(?:转账|轉賬|轉帳|红包|紅包)"
        );
        m = whoBeforePattern.matcher(text);
        if (m.find()) return m.group(1).trim();

        // 无法从文本中提取，返回空
        return "";
    }

    /**
     * 判断当前界面是不是转账/红包详情页，而不是聊天列表。
     *
     * 详情页的特征：
     *   1. 界面文本里同时出现金额数字和"已被接收"等完成文案
     *   2. 金额数字附近有明确的货币符号（¥/￥）或"元"字
     *   3. 页面中金额节点的类名通常是包含 TextView 的容器（非聊天列表气泡）
     *
     * 我们通过扫描全量文本来判断：如果文本中包含货币符号+有效金额，
     * 且金额周围有"已被接收"等字样，且总文本量适中（详情页不会像聊天列表一样长），
     * 就判定为详情页。
     */
    private static boolean isTransferDetailPage(String allText) {
        if (allText == null || allText.isEmpty()) return false;

        String text = allText;

        // 详情页必须有"已被接收"或类似确认文案
        if (!RECEIVED_PATTERN.matcher(text).find()) return false;

        // 详情页的金额前面一定带有货币符号
        boolean hasCurrencyAmount = Pattern.compile("(?:￥|¥)\\s*[0-9]+(?:[.,][0-9]{1,2})?").matcher(text).find();
        if (!hasCurrencyAmount) {
            // 也可能是 "X.XX元 已被接收" 这种格式
            hasCurrencyAmount = Pattern.compile("[0-9]+(?:[.,][0-9]{1,2})?\\s*元").matcher(text).find();
        }
        if (!hasCurrencyAmount) return false;

        // 详情页文本长度应该在 50-500 字符之间。
        // 聊天列表如果因为遍历了太多气泡可能会很长，但详情页不会有成百上千字符。
        // 注意：collectText 可能因为递归遍历 rootInActiveWindow 而拿到额外内容，
        // 我们放宽上限。
        if (text.length() > 1000) return false;

        return true;
    }

    /**
     * 发送 ACTION_BILLS_CHANGED 广播，通知界面刷新。
     */
    private void sendRefreshBroadcast() {
        try {
            Intent i = new Intent(MainActivity.ACTION_BILLS_CHANGED);
            i.setPackage(getPackageName());
            sendBroadcast(i);
        } catch (Throwable t) {
            Log.e(TAG, "广播刷新失败", t);
        }
    }
}