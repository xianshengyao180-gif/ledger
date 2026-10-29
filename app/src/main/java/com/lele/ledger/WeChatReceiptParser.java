package com.lele.ledger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict, local receipt parsing. A result is a proposal, never permission to write a bill. */
final class WeChatReceiptParser {
    static final String TRANSFER = "transfer";
    static final String RED_PACKET = "red_packet";
    private static final Pattern MONEY = Pattern.compile(
            "^(?:人民币)?([¥￥])?([0-9]{1,9}|[0-9]{1,3}(?:,[0-9]{3})+)(\\.[0-9]{1,2})?(元)?$");
    private static final Pattern DATE = Pattern.compile(
            "(20[0-9]{2})[-/年]([0-9]{1,2})[-/月]([0-9]{1,2})日?\\s*([0-9]{1,2}):([0-9]{2})(?::([0-9]{2}))?");

    static final class Line {
        final String text;
        final int left, top, width, height;

        Line(String text, int left, int top, int width, int height) {
            this.text = normalize(text);
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = Math.max(1, height);
        }
    }

    static final class Receipt {
        final String kind, fingerprint, withoutTimeFingerprint;
        final long cents, occurredAt;
        final int direction; // 1 income, -1 expense, 0 requires a choice.

        Receipt(String kind, long cents, int direction, long occurredAt, String fingerprint, String withoutTimeFingerprint) {
            this.kind = kind;
            this.cents = cents;
            this.direction = direction;
            this.occurredAt = occurredAt;
            this.fingerprint = fingerprint;
            this.withoutTimeFingerprint = withoutTimeFingerprint;
        }
    }

    private WeChatReceiptParser() {}

    /** Diagnostic metadata only. Digits and contacts are never returned. */
    static String diagnostics(List<Line> lines) {
        StringBuilder text = new StringBuilder(), shapes = new StringBuilder();
        for (Line line : lines) {
            text.append(line.text.replaceAll("\\s+", ""));
            if (line.text.matches("[0-9\\s.,¥￥元OoIl|·•。]+")) {
                shapes.append(line.text.replaceAll("[0-9]", "#")).append(';');
            }
        }
        return "packet=" + containsAny(text.toString(), "红包", "利是")
                + "; transfer=" + text.toString().contains("转账")
                + "; stored=" + containsAny(text.toString(), "已存入余额", "已存入零钱")
                + "; money_shapes=" + shapes;
    }

    static Receipt parse(List<Line> lines, String kind, long now) {
        if (!TRANSFER.equals(kind) && !RED_PACKET.equals(kind)) return null;
        StringBuilder page = new StringBuilder();
        for (Line line : lines) {
            // A clock in the status bar must not change a receipt's identity.
            if (line.top < 160 && line.text.matches("[0-9]{1,2}:[0-9]{2}")) continue;
            page.append(line.text).append('\n');
        }
        String text = page.toString();
        String compact = text.replaceAll("\\s+", "");
        if (containsAny(compact, "待收款", "等待收款", "未收款", "未领取", "待领取",
                "已退还", "已退回", "已退款", "已过期", "已逾期", "支付密码", "输入密码")) return null;

        int direction = 0;
        if (TRANSFER.equals(kind)) {
            if (!compact.contains("转账")) return null;
            boolean income = containsAny(compact, "你已收款", "您已收款", "已存入余额", "已存入零钱");
            boolean expense = containsAny(compact, "对方已收款", "已被对方接收");
            boolean completed = income || expense || containsAny(compact, "已被接收", "已收款");
            if (!completed || (income && expense)) return null;
            direction = income ? 1 : expense ? -1 : 0;
        } else {
            if (!containsAny(compact, "红包", "利是")) return null;
            // Lists of other people's receipts are not proof that this user received money.
            if (!containsAny(compact, "已存入余额", "已存入零钱")) return null;
            direction = 1;
        }

        List<Line> amounts = new ArrayList<>();
        int maxHeight = 0;
        for (Line line : lines) {
            String value = amountText(line.text);
            Matcher matcher = MONEY.matcher(value);
            if (!matcher.matches()) continue;
            boolean currency = matcher.group(1) != null || matcher.group(4) != null;
            boolean decimal = matcher.group(3) != null;
            if (!currency && !decimal && !nearYuan(line, lines)) continue;
            long cents = cents(value);
            if (cents <= 0) continue;
            amounts.add(line);
            maxHeight = Math.max(maxHeight, line.height);
        }
        long amount = 0;
        for (Line line : amounts) {
            // The main amount uses larger text than receipt lists. Refuse tied, different amounts.
            if (line.height < maxHeight * 0.8f) continue;
            long candidate = cents(amountText(line.text));
            if (amount != 0 && amount != candidate) return null;
            amount = candidate;
        }
        if (amount <= 0) return null;
        long timestamp = TRANSFER.equals(kind) ? receiptTime(lines, text, now) : 0;
        String identity = kind + '|' + amount + '|' + direction + '|' + timestamp + '|'
                + compact;
        String withoutTime = kind + '|' + amount + '|' + direction + "|0|" + compact;
        return new Receipt(kind, amount, direction, timestamp, digest(identity), digest(withoutTime));
    }

    private static boolean nearYuan(Line amount, List<Line> lines) {
        for (Line unit : lines) {
            if (!"元".equals(unit.text)) continue;
            if (Math.abs(unit.top - amount.top) <= amount.height
                    && unit.left >= amount.left
                    && unit.left - amount.left - amount.width < amount.height * 2) return true;
        }
        return false;
    }

    private static String amountText(String text) {
        String value = text.replaceAll("\\s+", "");
        // OCR can read the smaller currency unit as a final dot. Preserve every amount digit.
        // Only a complete two-decimal amount may have one trailing punctuation mark removed.
        if (value.matches("[¥￥]?(?:[0-9]{1,9}|[0-9]{1,3}(?:,[0-9]{3})+)\\.[0-9]{2}[.。]")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    static long cents(String value) {
        try {
            String normalized = normalize(value).replaceAll("\\s+", "");
            if (!MONEY.matcher(normalized).matches()) return 0;
            String cleaned = normalized.replaceAll("[¥￥元,]", "").replace("人民币", "");
            if (!cleaned.matches("[0-9]{1,9}(?:\\.[0-9]{1,2})?")) return 0;
            long cents = new BigDecimal(cleaned).movePointRight(2).longValueExact();
            return cents > 0 && cents <= 100_000_000_00L ? cents : 0;
        } catch (ArithmeticException | NumberFormatException error) {
            return 0;
        }
    }

    private static long receiptTime(List<Line> lines, String text, long now) {
        // Prefer collection time. Never substitute the current time for a historical receipt.
        for (String label : new String[]{"收款时间", "转账时间"}) {
            for (Line heading : lines) {
                String compact = heading.text.replaceAll("\\s+", "");
                if (!compact.contains(label)) continue;
                long sameLine = parseDate(compact.substring(compact.indexOf(label) + label.length()), now);
                if (sameLine > 0) return sameLine;
                Line closest = null;
                int distance = Integer.MAX_VALUE;
                for (Line value : lines) {
                    if (value == heading || parseDate(value.text, now) <= 0) continue;
                    int gap = Math.abs((value.top + value.height / 2) - (heading.top + heading.height / 2));
                    boolean sameRow = gap <= Math.max(value.height, heading.height)
                            && value.left > heading.left;
                    boolean below = value.top >= heading.top && value.top - heading.top <= heading.height * 2;
                    if ((sameRow || below) && gap < distance) { closest = value; distance = gap; }
                }
                if (closest != null) return parseDate(closest.text, now);
            }
            int index = text.indexOf(label);
            if (index < 0) continue;
            String tail = text.substring(index + label.length());
            long fallback = parseDate(tail, now);
            if (fallback > 0) return fallback;
        }
        return 0;
    }

    private static long parseDate(String text, long now) {
            Matcher match = DATE.matcher(text.replaceAll("\\s+", ""));
            if (!match.find() || match.start() > 12) return 0;
            String value = match.group(1) + "-" + match.group(2) + "-" + match.group(3)
                    + " " + match.group(4) + ":" + match.group(5) + ":"
                    + (match.group(6) == null ? "00" : match.group(6));
            SimpleDateFormat format = new SimpleDateFormat("yyyy-M-d H:mm:ss", Locale.ROOT);
            format.setLenient(false);
            ParsePosition position = new ParsePosition(0);
            Date date = format.parse(value, position);
            if (date != null && position.getIndex() == value.length()
                    && date.getTime() <= now + 300_000L) return date.getTime();
        return 0;
    }

    static String normalize(String value) {
        if (value == null) return "";
        String s = Normalizer.normalize(value, Normalizer.Form.NFKC);
        String from = "轉賬紅領取餘額零錢資時對還過錄詳發現已款來間與";
        String to   = "转账红领取余额零钱资时对还过录详发现已款来间与";
        for (int i = 0; i < from.length(); i++) s = s.replace(from.charAt(i), to.charAt(i));
        return s.trim();
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
