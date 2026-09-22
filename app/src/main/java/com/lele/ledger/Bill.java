package com.lele.ledger;

/**
 * 一条记账记录。
 * amount > 0 表示收入，< 0 表示支出（这样直接求和就是净额）。
 */
public final class Bill {
    public final long id;
    public final double amount;
    public final String channel;   // 微信 / 支付宝 / 银行卡
    public final String title;
    public final long ts;          // 毫秒时间戳
    public final String category;  // 用途（用户选的）；null 表示还没选

    /**
     * true 表示这只是一个「待用户确认」的转账占位对象，**绝不能写进数据库**。
     *
     * 存在的理由：转账「已被接收」的通知里通常没有金额，方向也有歧义，
     * 硬猜就会把用户的真实账目记错——所以先把它当成一个待确认的提案，
     * 由界面向用户问清楚（见 TransferActivity）。
     */
    public final boolean pending;

    public Bill(long id, double amount, String channel, String title, long ts) {
        this(id, amount, channel, title, ts, null, false);
    }

    public Bill(long id, double amount, String channel, String title, long ts, String category) {
        this(id, amount, channel, title, ts, category, false);
    }

    private Bill(long id, double amount, String channel, String title, long ts,
                 String category, boolean pending) {
        this.id = id;
        this.amount = amount;
        this.channel = channel;
        this.title = title;
        this.ts = ts;
        this.category = category;
        this.pending = pending;
    }

    /**
     * 转账「已被接收」但金额/方向还不确定时的占位。
     *
     * @param amount 通知里已知的金额（负数=支出）；拿不到金额就传 0，由用户填。
     * @param title  通知标题。微信转账的状态更新用的是聊天标题，也就是对方的名字。
     */
    public static Bill pendingTransfer(double amount, String channel, String title, long ts) {
        return new Bill(0L, amount, channel, title, ts, null, true);
    }

    /** 同上，但带上待确认表的行 id。 */
    public static Bill pendingTransfer(long id, double amount, String channel, String title, long ts) {
        return new Bill(id, amount, channel, title, ts, null, true);
    }

    /** 是否还没选用途。空串也算没选——数据库里两种都可能出现。 */
    public boolean uncategorized() {
        return category == null || category.isEmpty();
    }
}
