package com.lele.ledger;

import android.content.Context;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 微信账单 xlsx 解析器。
 *
 * 微信 PC 端「下载账单」导出的是 .xlsx（表格），与手机端导出的 .csv 结构一致，
 * 但表头更啰嗦：前 16 行是昵称、时间范围、导出类型、统计摘要等概要信息，
 * 第 17 行才是真正的列标题，第 18 行起是数据行。
 *
 * xlsx 列序（微信 2025 年格式）：
 *   交易时间, 交易类型, 交易对方, 商品, 收/支, 金额(元), 支付方式, 当前状态,
 *   交易单号, 商户单号, 备注
 *
 * 与 CsvImporter 的区别：
 * 1. 时间列是 Excel 序列号（如 46284.79467592593），用 DateUtil.getJavaDate 转换
 * 2. 当前状态可导入的范围更宽：支付成功 / 对方已收钱 / 已到账 / 已存管
 *
 * 核心原则（与 CsvImporter 完全一致）：
 * 1. 用「交易单号」做精确去重（nkey 前缀 wx_import_）
 * 2. 金额方向由「收/支」列明确判断，不用正负号猜
 * 3. 中性交易（充值/提现）直接跳过
 * 4. 只处理有效行，忽略表头、统计摘要等非数据行
 */
public final class ExcelImporter {

    /** 数据行之前的行数：前 16 行是表头信息，第 17 行（下标 16）是列标题。 */
    private static final int COLUMN_TITLE_ROW = 16;

    /** 文本时间兜底格式（时间列非序列号时才用到）。 */
    private static final String[] TEXT_DATE_FORMATS = {
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-M-d H:mm:ss",
            "yyyy/M/d HH:mm:ss",
            "yyyy/M/d H:mm"
    };

    /**
     * 从输入流读取 xlsx 并导入数据库。
     * @param ctx 用于打开数据库
     * @param in  xlsx 输入流（InputStream）
     */
    public static CsvImporter.Result importExcel(Context ctx, InputStream in) {
        CsvImporter.Result r = new CsvImporter.Result();
        Db db = new Db(ctx);
        XSSFWorkbook wb = null;
        try {
            wb = new XSSFWorkbook(in);
            Sheet sheet = wb.getSheetAt(0);

            for (int i = 0; i <= sheet.getLastRowNum(); i++) {
                // ---- 跳过前 16 行表头信息 + 第 17 行列标题 ----
                if (i <= COLUMN_TITLE_ROW) continue;

                Row row = sheet.getRow(i);
                if (row == null || isEmptyRow(row)) continue;

                // ---- 跳过统计行（"共 391 笔记录" 等，位于最后一组末尾）----
                String first = cellString(row.getCell(0));
                if (first.contains("共") && first.contains("笔")) {
                    break;
                }

                // ---- 解析单行 ----
                try {
                    parseAndInsert(db, row, r);
                } catch (Exception e) {
                    r.invalid++;
                    r.errors.add("第 " + (i + 1) + " 行解析失败: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            r.errors.add("文件读取失败: " + e.getMessage());
        } finally {
            if (wb != null) {
                try { wb.close(); } catch (Exception ignored) {}
            }
            db.close();
        }
        return r;
    }

    /**
     * 解析 xlsx 一行并写入 bills 表。
     *
     * 列序（0 基）：
     *   0-交易时间  1-交易类型  2-交易对方  3-商品  4-收/支  5-金额(元)
     *   6-支付方式  7-当前状态  8-交易单号  9-商户单号  10-备注
     */
    private static void parseAndInsert(Db db, Row row, CsvImporter.Result r) {
        // ---- 1. 提取各字段 ----
        String type      = cellString(row.getCell(1)); // 交易类型
        String peer      = cellString(row.getCell(2)); // 交易对方
        String goods     = cellString(row.getCell(3)); // 商品
        String flow      = cellString(row.getCell(4)); // 收/支
        String amountStr = cellString(row.getCell(5)); // 金额(元)
        String status    = cellString(row.getCell(7)); // 当前状态
        String tradeNo   = cellString(row.getCell(8)); // 交易单号

        // ---- 2. 只处理成功状态的记录 ----
        if (!isSuccessStatus(status)) {
            r.skipped++;
            return;
        }

        // ---- 3. 交易单号去重 ----
        if (tradeNo.isEmpty()) {
            r.invalid++;
            r.errors.add("缺少交易单号: " + peer + (goods.isEmpty() ? "" : " · " + goods));
            return;
        }
        String nkey = "wx_import_" + tradeNo;
        if (db.seenKey(nkey, 0)) {
            r.duplicate++;
            return;
        }

        // ---- 4. 解析金额与方向 ----
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

        // 方向：「支出」为负，「收入」为正；中性交易（充值/提现）落入 else 跳过
        if ("支出".equals(flow) || "付".equals(flow)) {
            amount = -amount;
        } else if ("收入".equals(flow) || "存入".equals(flow)) {
            // 收入保持正值
        } else {
            r.skipped++;
            return;
        }

        // ---- 5. 组合标题 ----
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

        // ---- 6. 解析时间戳（Excel 序列号）----
        long ts;
        try {
            ts = parseTimeCell(row.getCell(0));
        } catch (Exception e) {
            r.invalid++;
            r.errors.add("时间解析失败: " + cellString(row.getCell(0)));
            return;
        }

        // ---- 7. 写入 bills ----
        db.insert(amount, "微信导入", title, ts, nkey);
        r.imported++;
    }

    /** 成功状态判定：支付成功 / 对方已收钱 / 已到账 / 已存管。 */
    private static boolean isSuccessStatus(String status) {
        return "支付成功".equals(status)
                || "对方已收钱".equals(status)
                || "已到账".equals(status)
                || "已存管".equals(status);
    }

    /** 时间列转时间戳：序列号优先，文本格式兜底。 */
    private static long parseTimeCell(Cell c) throws Exception {
        if (c == null) throw new IllegalArgumentException("时间列缺失");
        if (c.getCellType() == CellType.NUMERIC) {
            Date d = DateUtil.getJavaDate(c.getNumericCellValue());
            if (d != null) return d.getTime();
            throw new IllegalArgumentException("时间序列号无效");
        }
        String s = cellString(c);
        for (String fmt : TEXT_DATE_FORMATS) {
            try {
                Date d = new SimpleDateFormat(fmt, Locale.CHINA).parse(s);
                if (d != null) return d.getTime();
            } catch (ParseException ignored) {
            }
        }
        throw new IllegalArgumentException("时间格式无法识别: " + s);
    }

    /** 取单元格字符串；空单元格返回空串。 */
    private static String cellString(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();
            case NUMERIC:
                double d = cell.getNumericCellValue();
                // 整数且在 long 精确范围内：去掉小数点，保留完整数字（交易单号/金额）
                if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 9.0e15) {
                    return String.valueOf((long) d);
                }
                return String.valueOf(d);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue().trim();
                } catch (Exception e) {
                    return cell.getCellFormula();
                }
            default:
                return "";
        }
    }

    /** 整行是否为空（无任何文本内容）。 */
    private static boolean isEmptyRow(Row row) {
        if (row == null) return true;
        int first = row.getFirstCellNum();
        int last = row.getLastCellNum();
        if (first < 0) return true;
        for (int c = first; c < last; c++) {
            Cell cell = row.getCell(c);
            if (cell != null && !cellString(cell).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
