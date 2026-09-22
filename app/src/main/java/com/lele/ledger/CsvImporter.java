package com.lele.ledger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 微信账单 CSV 解析器。
 *
 * 微信导出路径：我 → 服务 → 钱包 → 账单 → 常见问题 → 下载账单 → 用于个人对账
 * CSV 编码为 GBK（Windows）或 UTF-8（手机端）。
 *
 * CSV 列序（微信 2025 年格式）：
 *   交易时间, 交易类型, 交易对方, 商品, 收/支, 金额(元), 支付方式, 当前状态,
 *   交易单号, 商户单号, 备注
 *
 * 核心原则：
 * 1. 只要「当前状态」是「已到账」才导入
 * 2. 用「交易单号」做精确去重（nkey 前缀 wx_import_）
 * 3. 金额方向由「收/支」列明确判断，不用正负号猜
 * 4. 只处理有效行，忽略页面页脚、统计摘要等非数据行
 */
public final class CsvImporter {

    /** 解析结果回传 */
    public static final class Result {
        public int imported;       // 成功导入笔数
        public int duplicate;     // 已存在跳过
        public int skipped;       // 不符条件跳过（未到账等）
        public int invalid;       // 格式错误无法解析
        public final List<String> errors = new ArrayList<>(); // 每行错误描述
    }

    /**
     * 从输入流读取 CSV 并导入数据库。
     * @param ctx  用于打开数据库
     * @param in   CSV 输入流（InputStream）
     * @param charset 编码，如 "GBK" 或 "UTF-8"
     */
    public static Result importCsv(Context ctx, InputStream in, String charset) {
        Result r = new Result();
        Db db = new Db(ctx);
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(in, Charset.forName(charset)));
            String line;
            int lineNo = 0;
            boolean headerFound = false;

            while ((line = br.readLine()) != null) {
                lineNo++;
                if (line.trim().isEmpty()) continue;

                // ---- 跳过页头/非数据行 ----
                // 微信 CSV 横幅：数据前 2-3 行是一些概要：微信号、时间范围……
                // 特征是没有任何列和值。我们找到表头行再开始解析。
                if (!headerFound) {
                    // 表头包含"交易时间"、"交易类型"等列名
                    if (line.contains("交易时间") && line.contains("交易类型") && line.contains("金额")) {
                        headerFound = true;
                    }
                    continue;
                }

                // 跳过页尾：微信 CSV 最后几行是统计摘要（"共 N 笔交易" 等）
                if (line.startsWith("----------------------") || line.startsWith("导出") || line.startsWith("微信支付")) {
                    break;
                }

                // ---- 解析单行 ----
                try {
                    parseAndInsert(db, line, r);
                } catch (Exception e) {
                    r.invalid++;
                    r.errors.add("第 " + lineNo + " 行解析失败: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            r.errors.add("文件读取失败: " + e.getMessage());
        } finally {
            if (br != null) try { br.close(); } catch (Exception ignored) {}
            db.close();
        }
        return r;
    }

    /**
     * 解析 CSV 一行并写入 bills 表。
     *
     * 微信 CSV 列序（经实测验证）：
     *   0-交易时间  1-交易类型  2-交易对方  3-商品  4-收/支  5-金额(元)
     *   6-支付方式  7-当前状态  8-交易单号  9-商户单号  10-备注
     *
     * 注意 CSV 里字段可能被引号包裹，也可能内嵌逗号（"对方,xxx"），
     * 所以不能简单 split(",")。这里用标准 CSV 解析：
     * 把一行切成字段，逐字段处理引号。
     */
    private static void parseAndInsert(Db db, String rawLine, Result r) {
        // ---- 1. 按 RFC 4180 标准拆分 CSV 行（支持引号内逗号/换行符） ----
        // 这里 OneNote 的行不会内嵌换行，所以只处理引号内逗号即可
        List<String> cols = splitCsvLine(rawLine);
        if (cols.size() < 9) {
            r.invalid++;
            r.errors.add("列数不足 (仅 " + cols.size() + " 列)，需要至少 9 列: " + rawLine.substring(0, Math.min(rawLine.length(), 60)));
            return;
        }

        // ---- 2. 提取各字段 ----
        String timeStr    = cols.get(0).trim();      // 交易时间
        String type       = cols.get(1).trim();      // 交易类型
        String peer       = cols.get(2).trim();      // 交易对方
        String goods      = cols.get(3).trim();      // 商品
        String flow       = cols.get(4).trim();      // 收/支
        String amountStr  = cols.get(5).trim();      // 金额(元)
        String payment    = cols.get(6).trim();      // 支付方式
        String status     = cols.get(7).trim();      // 当前状态
        String tradeNo    = cols.get(8).trim();      // 交易单号
        // cols[9]=商户单号, cols[10]=备注（备查）

        // ---- 3. 只处理「已到账」的记录 ----
        if (!"已到账".equals(status) && !"已存管".equals(status)) {
            r.skipped++;
            return;
        }

        // ---- 4. 交易单号去重 ----
        String nkey = "wx_import_" + tradeNo;
        if (db.seenKey(nkey, 0)) {
            r.duplicate++;
            return;
        }

        // ---- 5. 解析金额与方向 ----
        double amount;
        try {
            // 金额可能含 ¥ 符号或逗号千分位
            String cleaned = amountStr.replace("¥", "").replace("￥", "")
                    .replace(",", "").replace("，", "").trim();
            amount = Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            r.invalid++;
            r.errors.add("金额解析失败: " + amountStr);
            return;
        }
        if (amount < 0) amount = -amount; // 金额本身应 >0

        // 方向：「支出」为负，「收入」为正
        if ("支出".equals(flow) || "付".equals(flow)) {
            amount = -amount;
        } else if ("收入".equals(flow) || "存入".equals(flow)) {
            // 收入保持正值
        } else {
            // 微信里通常不含其他值，防一手
            r.skipped++;
            return;
        }

        // ---- 6. 组合标题 ----
        // 用「交易对方 + 商品」作为标题。如果商品为空则只用交易对方
        String title;
        if (peer.isEmpty() && goods.isEmpty()) {
            title = type;
        } else if (peer.isEmpty()) {
            title = goods;
        } else if (goods.isEmpty() || goods.equals(peer)) {
            title = peer;
        } else {
            title = peer + " · " + goods;
        }
        if (title.length() > 200) title = title.substring(0, 200);

        // ---- 7. 解析时间戳 ----
        long ts;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
            Date d = sdf.parse(timeStr);
            ts = d != null ? d.getTime() : System.currentTimeMillis();
        } catch (ParseException e) {
            // 有可能是 "yyyy-M-d H:mm:ss" 格式，再试一次
            try {
                SimpleDateFormat sdf2 = new SimpleDateFormat("yyyy-M-d H:mm:ss", Locale.CHINA);
                Date d = sdf2.parse(timeStr);
                ts = d != null ? d.getTime() : System.currentTimeMillis();
            } catch (ParseException e2) {
                r.invalid++;
                r.errors.add("时间解析失败: " + timeStr);
                return;
            }
        }

        // ---- 8. 写入 bills ----
        // 用交易单号去重，交易类型放 channel 字段，原始时间戳也写入
        db.insert(amount, "微信导入", title, ts, nkey);
        r.imported++;
    }

    /**
     * 标准 CSV 行拆分。
     *
     * 规则：
     * - 双引号包裹的字段内可以包含逗号
     * - 双引号自身用 "" 转义
     * - 字段外的空格/制表符是字段内容的一部分（不是分隔符）——但微信 CSV 里没有
     */
    private static List<String> splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        int len = line.length();
        for (int i = 0; i < len; i++) {
            char c = line.charAt(i);
            if (inQuote) {
                if (c == '"') {
                    if (i + 1 < len && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++; // skip next quote
                    } else {
                        inQuote = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == '"') {
                    inQuote = true;
                } else if (c == ',') {
                    fields.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
        }
        fields.add(cur.toString()); // 最后一个字段
        return fields;
    }
}