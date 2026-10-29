package com.lele.ledger;

import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;

/** Synthetic fixtures only. No screenshots, contacts or real transaction values. */
public class WeChatReceiptParserTest {
    private static final long NOW = 1_900_000_000_000L;

    private WeChatReceiptParser.Line line(String text, int top, int height) {
        return new WeChatReceiptParser.Line(text, 200, top, 350, height);
    }

    private List<WeChatReceiptParser.Line> transfer(String state) {
        return new ArrayList<>(Arrays.asList(line("轉賬詳情", 200, 30),
                line("￥23.45", 420, 90), line(state, 620, 30),
                line("轉賬時間", 800, 25), line("2026年10月01日 12:30:00", 840, 25),
                line("收款時間", 900, 25), line("2026-10-02 13:31:02", 940, 25)));
    }

    private List<WeChatReceiptParser.Line> packet(String state) {
        return new ArrayList<>(Arrays.asList(line("示例的利是", 200, 30),
                line("8.88元", 440, 90), line(state, 620, 30)));
    }

    @Test public void traditionalTransferIncomeUsesCollectionTime() throws Exception {
        WeChatReceiptParser.Receipt r = WeChatReceiptParser.parse(
                transfer("你已收款，資金已存入餘額"), WeChatReceiptParser.TRANSFER, NOW);
        assertNotNull(r);
        assertEquals(2345, r.cents);
        assertEquals(1, r.direction);
        assertEquals(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                .parse("2026-10-02 13:31:02").getTime(), r.occurredAt);
    }

    @Test public void outgoingTransferIsExpense() {
        assertEquals(-1, WeChatReceiptParser.parse(transfer("對方已收款"),
                WeChatReceiptParser.TRANSFER, NOW).direction);
    }

    @Test public void ambiguousTransferRequiresDirection() {
        assertEquals(0, WeChatReceiptParser.parse(transfer("轉賬已被接收"),
                WeChatReceiptParser.TRANSFER, NOW).direction);
    }

    @Test public void receivedHongKongPacketRequiresManualDate() {
        WeChatReceiptParser.Receipt r = WeChatReceiptParser.parse(
                packet("已存入餘額，可轉入零錢通賺收益"), WeChatReceiptParser.RED_PACKET, NOW);
        assertNotNull(r);
        assertEquals(888, r.cents);
        assertEquals(1, r.direction);
        assertEquals(0, r.occurredAt);
    }

    @Test public void simplifiedPacketWorks() {
        List<WeChatReceiptParser.Line> lines = packet("已存入零钱");
        lines.set(0, line("示例的红包", 200, 30));
        assertNotNull(WeChatReceiptParser.parse(lines, WeChatReceiptParser.RED_PACKET, NOW));
    }

    @Test public void unpaidExpiredRefundedPagesDoNotBecomeProposals() {
        for (String state : new String[]{"待收款", "等待收款", "已退還", "已退回", "已過期", "未领取"}) {
            assertNull(state, WeChatReceiptParser.parse(transfer(state), WeChatReceiptParser.TRANSFER, NOW));
        }
    }

    @Test public void otherRecipientsAreNotOwnIncome() {
        assertNull(WeChatReceiptParser.parse(packet("已領完，已被領取"), WeChatReceiptParser.RED_PACKET, NOW));
    }

    @Test public void passwordScreensAreRejected() {
        List<WeChatReceiptParser.Line> lines = transfer("你已收款");
        lines.add(line("输入支付密码", 1000, 40));
        assertNull(WeChatReceiptParser.parse(lines, WeChatReceiptParser.TRANSFER, NOW));
    }

    @Test public void largeMainAmountWinsOverRecipientList() {
        List<WeChatReceiptParser.Line> lines = packet("已存入余额");
        lines.add(line("1.23元", 900, 28));
        lines.add(line("共100.00元，已领取10/10个", 800, 28));
        assertEquals(888, WeChatReceiptParser.parse(lines, WeChatReceiptParser.RED_PACKET, NOW).cents);
    }

    @Test public void competingLargeAmountsAreRejected() {
        List<WeChatReceiptParser.Line> lines = packet("已存入余额");
        lines.add(line("9.99元", 800, 85));
        assertNull(WeChatReceiptParser.parse(lines, WeChatReceiptParser.RED_PACKET, NOW));
    }

    @Test public void ocrLetterIsNotGuessedAsDigit() {
        List<WeChatReceiptParser.Line> lines = packet("已存入余额");
        lines.set(1, line("8.OO元", 440, 90));
        assertNull(WeChatReceiptParser.parse(lines, WeChatReceiptParser.RED_PACKET, NOW));
    }

    @Test public void datesAndTransactionNumbersAreNotAmounts() {
        List<WeChatReceiptParser.Line> lines = transfer("你已收款");
        lines.remove(1);
        lines.add(line("100005678901234567890", 440, 100));
        assertNull(WeChatReceiptParser.parse(lines, WeChatReceiptParser.TRANSFER, NOW));
    }

    @Test public void integerNeedsCurrencyOrAdjacentYuan() {
        List<WeChatReceiptParser.Line> lines = packet("已存入余额");
        lines.set(1, line("8", 440, 90));
        assertNull(WeChatReceiptParser.parse(lines, WeChatReceiptParser.RED_PACKET, NOW));
        lines.add(new WeChatReceiptParser.Line("元", 555, 465, 35, 30));
        assertEquals(800, WeChatReceiptParser.parse(lines, WeChatReceiptParser.RED_PACKET, NOW).cents);
    }

    @Test public void commaAndFullWidthMoneyAreAccepted() {
        assertEquals(123456, WeChatReceiptParser.cents("￥１,２３４.５６元"));
        assertEquals(0, WeChatReceiptParser.cents("8.888"));
        assertEquals(0, WeChatReceiptParser.cents("NaN"));
        assertEquals(0, WeChatReceiptParser.cents("-8.00"));
        assertEquals(0, WeChatReceiptParser.cents("0"));
        assertEquals(0, WeChatReceiptParser.cents("1元2"));
        assertEquals(0, WeChatReceiptParser.cents("1,23.45"));
    }

    @Test public void clockDoesNotChangePageFingerprint() {
        List<WeChatReceiptParser.Line> first = packet("已存入余额");
        first.add(line("12:34", 30, 30));
        List<WeChatReceiptParser.Line> second = packet("已存入余额");
        second.add(line("12:35", 30, 30));
        assertEquals(WeChatReceiptParser.parse(first, WeChatReceiptParser.RED_PACKET, NOW).fingerprint,
                WeChatReceiptParser.parse(second, WeChatReceiptParser.RED_PACKET, NOW).fingerprint);
    }

    @Test public void invalidOrFutureDateRequiresManualDate() {
        List<WeChatReceiptParser.Line> lines = transfer("你已收款");
        lines.removeIf(line -> line.text.contains("时间") || line.text.startsWith("2026"));
        lines.add(line("收款时间 2099-01-01 12:00:00", 900, 25));
        assertEquals(0, WeChatReceiptParser.parse(lines, WeChatReceiptParser.TRANSFER, NOW).occurredAt);
        lines.set(lines.size() - 1, line("收款时间 2026-02-31 12:00:00", 900, 25));
        assertEquals(0, WeChatReceiptParser.parse(lines, WeChatReceiptParser.TRANSFER, NOW).occurredAt);
    }

    @Test public void unknownPageKindIsRejected() {
        assertNull(WeChatReceiptParser.parse(packet("已存入余额"), "chat", NOW));
    }

    @Test public void ocrTrailingPunctuationPreservesCompleteAmountDigits() {
        List<WeChatReceiptParser.Line> lines = packet("已存入餘額");
        lines.set(1, line("8.88.", 440, 90));
        assertEquals(888, WeChatReceiptParser.parse(lines, WeChatReceiptParser.RED_PACKET, NOW).cents);
        assertEquals(0, WeChatReceiptParser.cents("8.88."));
        lines.set(1, line("8.888.", 440, 90));
        assertNull(WeChatReceiptParser.parse(lines, WeChatReceiptParser.RED_PACKET, NOW));
        lines.set(1, line("8.8O.", 440, 90));
        assertNull(WeChatReceiptParser.parse(lines, WeChatReceiptParser.RED_PACKET, NOW));
    }

    @Test public void dateColumnsUseRowGeometryAndIgnoreOcrWhitespace() throws Exception {
        List<WeChatReceiptParser.Line> lines = transfer("你已收款");
        lines.removeIf(line -> line.text.contains("时间") || line.text.startsWith("2026"));
        lines.add(new WeChatReceiptParser.Line("转账时间", 30, 900, 150, 25));
        lines.add(new WeChatReceiptParser.Line("收款时间", 30, 970, 150, 25));
        lines.add(new WeChatReceiptParser.Line("2026年 10月 01日 12 : 30 : 00", 400, 900, 400, 25));
        lines.add(new WeChatReceiptParser.Line("2026年 10月 02日 13 : 31 : 02", 400, 970, 400, 25));
        assertEquals(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                .parse("2026-10-02 13:31:02").getTime(),
                WeChatReceiptParser.parse(lines, WeChatReceiptParser.TRANSFER, NOW).occurredAt);
    }
}
