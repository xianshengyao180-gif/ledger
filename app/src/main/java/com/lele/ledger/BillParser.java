package com.lele.ledger;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 离线、确定性的付款通知解析器：把通知文案翻译成「金额 + 收支方向 + 支付渠道」。
 *
 * 改编自 FuShengDuoBuYu/AutoBookKeepingBeta 的 Util/NotificationBillParser.java
 * 原项目采用 MIT 协议（Copyright (c) FuShengDuoBuYu）。
 * 本文件只保留「通知文案 -> 记账」的解析规则，未包含原项目的云同步、
 * 家庭账本、AI、地图等任何部分。
 *
 * 相对于原版的修正：
 *  1. 原版把渠道名里的「支付」误当消费关键词，导致「微信支付 收款」被记成支出。
 *     这里在关键词匹配前先剥离渠道名。
 *  2. 原版词表里没有「转账」，导致微信/支付宝转账的通知被整条丢弃。这里补上，
 *     并把「转账」的方向歧义单独处理（见 TRANSFER_DONE_PATTERN）。
 *  3. 标题白名单原先只认「微信支付」。实测新版微信的付款通知标题是英文
 *     「Weixin Pay」，导致「正文不含支付类关键词、只有标题能判别」的通知被丢弃。
 *     这里改成标题白名单（见 WECHAT_PAY_TITLES）。
 *  4. 只认简体「转账」。实测用户的微信通知是繁体「[轉賬] 已被接收」，
 *     整类转账因此静默漏掉。现在简繁都认。
 *
 * 设计原则：纯规则、不联网、可离线跑。规则失效时改这里即可，不用动别的地方。
 */
public final class BillParser {

    /** 这些文案代表「还没真的花钱」，必须排除，否则会误记一堆。简繁都收。 */
    private static final Pattern IGNORE_PATTERN = Pattern.compile(
            "待支付|未支付|还未支付|支付失败|付款失败|扣款失败|交易关闭|自动取消|验证码|登录提醒|配置更新|请勿泄露|请勿告诉"
            + "|待付款|未付款|交易失敗|付款失敗|已取消|驗證碼|登錄提醒|請勿洩露|請勿告訴|請勿外傳"
    );

    /** 支出方向的关键词。注意「转账」本身不分方向，靠转账专项规则消歧。 */
    private static final Pattern EXPENSE_PATTERN = Pattern.compile(
            "支出|付款|支付|消费|消費|扣款|转出|轉出|提现|提現|买单|買單|代扣|转账|轉賬|轉帳|汇款|匯款"
    );

    private static final Pattern INCOME_PATTERN = Pattern.compile(
            "退款|退回|返还|返還|收入|收款|到账|到賬|到帳|入账|入賬|入帳|转入|轉入"
    );

    /** 「别人转给我」的明确表述——这类一定是收入，优先级高于普通关键词。简繁都认。 */
    private static final Pattern INCOMING_TRANSFER_PATTERN = Pattern.compile(
            "收到转账|收到轉賬|收到轉帳|向你转账|向你轉賬|向你轉帳|给你转账|给你轉賬|转账给你|轉賬給你|轉帳給你"
            + "|已收钱|已收錢|已收款"
    );

    /** 「转账」二字在文案里出现（含繁体）。用来判断上下文是不是转账。 */
    private static final Pattern TRANSFER_WORD_PATTERN = Pattern.compile("转账|轉賬|轉帳");

    /**
     * 转账「已被对方接收」。
     *
     * 实测（2026-09，微信 + 繁体环境）通知长这样：
     *   标题=对方的名字（如「老登」），正文=「[轉賬] 已被接收」。
     *
     * 这类通知有两个坑：
     *   1. 文案是繁体，只认简体「转账」会整条漏掉；
     *   2. **正文里没有金额**。微信只在聊天里更新状态，金额不进通知。
     * 而且方向本身也有歧义：可能是我发出去的被对方收下，也可能是我收下的。
     *
     * 所以这类通知一律**不自动记账**，交给用户确认（见 TransferActivity）。
     * 记账应用把金额或方向猜错，比多问一句坏得多。
     */
    private static final Pattern TRANSFER_DONE_PATTERN = Pattern.compile(
            "已被接收|已被領取|已被领取|已接收|已領取|已领取"
    );

    /**
     * 微信聊天里的转账气泡，形如「[转账]」或「[轉賬]100.00」。
     *
     * 这是我**发出去**的转账：钱虽然立刻从零钱扣了，但对方 24 小时内不点收款
     * 就会原路退回。所以按用户的要求，此刻**先不记账**，等「已被接收」那一刻再说——
     * 否则会记下一笔对方根本没要的钱。
     *
     * 用方括号限定，是为了不误伤银行的「转账支出」类通知：那种是即时到账的真实支出，
     * 必须照常记（银行通知里不会带方括号）。
     */
    private static final Pattern WECHAT_TRANSFER_BUBBLE_PATTERN = Pattern.compile(
            "\\[(?:转账|轉賬|轉帳)\\]"
    );

    private static final Pattern REFUND_PATTERN = Pattern.compile("退款|退回|返还|返還");

    /**
     * 微信付款类通知的标题白名单。
     *
     * 实测（2026-09，安卓微信）付款通知的标题是英文「Weixin Pay」，
     * 因此只认「微信支付」会让「正文缺少关键词、只能靠标题判别」的通知漏掉。
     * 用白名单而不是模糊匹配「微信」，是为了避免把聊天/公众号消息误判成账单。
     */
    private static final String[] WECHAT_PAY_TITLES = {
            "微信支付", "Weixin Pay", "微信收款助手", "收款小账本", "微信轉賬", "微信轉帳"
    };

    /** 带货币符号或「元/块/圆」的金额，如 ￥23.50 / 23.50元 / 1,234.00元 */
    private static final Pattern CURRENCY_AMOUNT_PATTERN = Pattern.compile(
            "(?:(?:￥|¥|人民币|RMB|CNY)\\s*([0-9]+(?:,[0-9]{3})*(?:\\.\\d{1,2})?|[0-9]+(?:\\.\\d{1,2})?)"
                    + "|([0-9]+(?:,[0-9]{3})*(?:\\.\\d{1,2})?|[0-9]+(?:\\.\\d{1,2})?)\\s*(?:元|块|圆|塊|圓))"
    );

    /** 语义相邻的金额，如「支付 23.50」「转账100.00」「消费金额：23.50」 */
    private static final Pattern SEMANTIC_AMOUNT_PATTERN = Pattern.compile(
            "(?:金额|消費|消费|支付|支出|收入|转入|轉入|转出|轉出|扣款|付款|收款|入账|入賬|入帳|提现|提現"
                    + "|退款|转账|轉賬|轉帳|汇款|匯款|金额|金額)"
                    + "[^0-9￥¥]{0,8}(?:￥|¥)?\\s*([0-9]+(?:,[0-9]{3})*(?:\\.\\d{1,2})?|[0-9]+(?:\\.\\d{1,2})?)"
    );

    private BillParser() {
    }

    /**
     * @param packageName 发通知的 App 包名
     * @param title       通知标题
     * @param text        通知正文（多条会拼在一起）
     * @return 解析出的记录；判定为「不是一笔真付款」时返回 null。
     *         返回 {@link Bill#pending} 为 true 的对象时，表示「需要用户确认」，
     *         调用方**不得**直接写库。
     */
    public static Bill parse(String packageName, String title, String text) {
        String pkg = packageName == null ? "" : packageName;
        String safeTitle = title == null ? "" : title;
        String safeText = text == null ? "" : text;
        String compact = safeText.replaceAll("\\s+", "");

        if (compact.isEmpty() || IGNORE_PATTERN.matcher(compact).find()) {
            return null;
        }

        boolean isAlipay = "com.eg.android.AlipayGphone".equals(pkg) || pkg.startsWith("com.alipay.");
        boolean isWechat = "com.tencent.mm".equals(pkg);

        // 坑：渠道名「微信支付」「支付宝」本身含「支付」，会让收款通知被误判成支出。
        // 所以做关键词匹配前先把渠道名抹掉，只看真正描述这笔交易的那部分文案。
        String kw = compact.replace("微信支付", "").replace("支付宝", "");
        boolean incomingTransfer = INCOMING_TRANSFER_PATTERN.matcher(kw).find();
        boolean transferWord = TRANSFER_WORD_PATTERN.matcher(kw).find();
        // 必须是「转账 + 状态更新」同时出现才算，否则一句「文件已被接收」也会弹出记账框
        boolean transferDone = transferWord && TRANSFER_DONE_PATTERN.matcher(kw).find();
        boolean wechatBubble = WECHAT_TRANSFER_BUBBLE_PATTERN.matcher(kw).find();
        boolean hasExpense = EXPENSE_PATTERN.matcher(kw).find();
        boolean hasIncome = INCOME_PATTERN.matcher(kw).find();
        boolean isRefund = REFUND_PATTERN.matcher(kw).find();

        // 标题也可能承载关键信息（如「微信支付」「Weixin Pay」），不能只看正文
        boolean matchesTitle = isWechat
                ? containsAny(safeTitle, WECHAT_PAY_TITLES)
                : (isAlipay && safeTitle.contains("交易提醒"));

        // ① 转账「已被接收」：如果通知里带金额，说明是别人转给我/发给我的红包，记作收入。
        //    如果通知里没有金额，说明是我转出去被对方接收了，放待确认让用户填。
        //    （微信转账/红包的金额常藏在 EXTRA_MESSAGES 里，NotiListener 已做深度提取。）
        if (transferDone) {
            ArrayList<Double> known = extractAmounts(safeText);
            if (known.isEmpty()) {
                // 没金额：是我发出去被对方收了，放待确认。
                // 金额传 0.0（不是 -0.0）：这里根本没有「已知金额」，符号无意义。
                // -0.0 虽然与 0 数值相等、显示也一样，但会让「amount<0 才预填」这类
                // 判断和测试期望（+0.00）对不上，读代码时像个 bug。用 +0 更诚实。
                String who = safeTitle.trim();
                return Bill.pendingTransfer(0.0, channelOf(pkg),
                        who.isEmpty() ? "转账" : who, System.currentTimeMillis());
            }
            // 有金额：别人转给我/发给我的红包，记收入
            double amount = Math.abs(known.get(0));
            return new Bill(0L, amount, channelOf(pkg),
                    safeTitle.trim().isEmpty() ? "转账" : safeTitle.trim(),
                    System.currentTimeMillis());
        }

        // ② 我发出的转账气泡：此刻不记账，等对方收款（见上面的注释）。
        //    对方真收了会来一条「已被接收」，那时才由用户确认入账。
        if (wechatBubble) {
            return null;
        }

        if (!hasExpense && !hasIncome && !incomingTransfer && !matchesTitle) {
            return null;
        }

        ArrayList<Double> amounts = extractAmounts(safeText);
        if (amounts.isEmpty()) {
            return null;
        }

        double amount = Math.abs(amounts.get(0));
        // 「转账」方向不明：默认按支出，但凡出现「收到转账/向你转账」等表述就判为收入。
        boolean income = incomingTransfer || isRefund || (hasIncome && !hasExpense);
        return new Bill(0L, income ? amount : -amount, channelOf(pkg), safeTitle, System.currentTimeMillis());
    }

    private static boolean containsAny(String haystack, String[] needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static ArrayList<Double> extractAmounts(String text) {
        ArrayList<Double> amounts = new ArrayList<>();
        collect(amounts, text, CURRENCY_AMOUNT_PATTERN);
        collect(amounts, text, SEMANTIC_AMOUNT_PATTERN);
        return amounts;
    }

    private static void collect(ArrayList<Double> out, String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String v = firstNonEmptyGroup(m);
            if (v.isEmpty()) {
                continue;
            }
            try {
                double d = Double.parseDouble(v.replace(",", ""));
                if (d > 0 && !contains(out, d)) {
                    out.add(d);
                }
            } catch (NumberFormatException ignored) {
                // 解析不出的金额直接跳过，不影响其它匹配
            }
        }
    }

    private static String firstNonEmptyGroup(Matcher m) {
        for (int i = 1; i <= m.groupCount(); i++) {
            String v = m.group(i);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private static boolean contains(ArrayList<Double> list, double target) {
        for (double d : list) {
            if (Math.abs(d - target) < 0.01) {
                return true;
            }
        }
        return false;
    }

    private static String channelOf(String pkg) {
        if ("com.tencent.mm".equals(pkg)) {
            return "微信";
        }
        if ("com.eg.android.AlipayGphone".equals(pkg) || pkg.startsWith("com.alipay.")) {
            return "支付宝";
        }
        return "银行卡";
    }
}
