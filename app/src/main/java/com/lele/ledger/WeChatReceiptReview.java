package com.lele.ledger;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Local receipt review uses the same panel and controls as manual bookkeeping. */
final class WeChatReceiptReview {
    static void show(Context context, Db db, Runnable changed) {
        List<WeChatReceiptStore.Pending> rows = WeChatReceiptStore.pending(db);
        if (rows.isEmpty()) {
            Toast.makeText(context, "没有待核对的微信详情", Toast.LENGTH_SHORT).show();
            return;
        }
        if (rows.size() == 1) {
            review(context, db, rows.get(0), changed);
            return;
        }
        LedgerSheet sheet = new LedgerSheet(context, "微信详情待核对", "只保留识别结果，确认后才写入账本。请选择一笔核对。");
        for (WeChatReceiptStore.Pending row : rows) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16));
            card.setBackgroundResource(R.drawable.bg_pill);
            String title = WeChatReceiptParser.RED_PACKET.equals(row.kind) ? "微信红包" : "微信转账";
            card.addView(LedgerSheet.text(context, title + "    ¥" + money(row.cents), 18, R.color.text_primary));
            TextView date = LedgerSheet.text(context, row.occurredAt > 0 ? date(row.occurredAt) : "到账时间待补充", 12, R.color.text_secondary);
            date.setPadding(0, dp(context, 6), 0, 0);
            card.addView(date);
            card.setFocusable(true);
            card.setOnClickListener(view -> {
                sheet.dismiss();
                review(context, db, row, changed);
            });
            sheet.content(card);
        }
        sheet.secondary("稍后核对", sheet::dismiss).show();
    }

    private static void review(Context context, Db db, WeChatReceiptStore.Pending pending, Runnable changed) {
        String title = WeChatReceiptParser.RED_PACKET.equals(pending.kind) ? "核对微信红包" : "核对微信转账";
        LedgerSheet sheet = new LedgerSheet(context, title, "核对金额、方向和到账时间。历史详情不会按今天入账。");
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.addView(LedgerSheet.text(context, "金额（元）", 12, R.color.text_secondary));
        EditText amount = new EditText(context);
        amount.setContentDescription("识别金额");
        amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amount.setText(money(pending.cents));
        amount.setTextSize(32);
        amount.setTextColor(context.getColor(R.color.text_primary));
        amount.setBackgroundResource(R.drawable.bg_pill);
        amount.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));
        LinearLayout.LayoutParams inputSize = new LinearLayout.LayoutParams(-1, -2);
        inputSize.topMargin = dp(context, 8);
        form.addView(amount, inputSize);

        LinearLayout direction = new LinearLayout(context);
        direction.setOrientation(LinearLayout.HORIZONTAL);
        TextView income = directionButton(context, "收入");
        TextView expense = directionButton(context, "支出");
        direction.addView(income, new LinearLayout.LayoutParams(0, dp(context, 48), 1));
        LinearLayout.LayoutParams expenseSize = new LinearLayout.LayoutParams(0, dp(context, 48), 1);
        expenseSize.leftMargin = dp(context, 12);
        direction.addView(expense, expenseSize);
        LinearLayout.LayoutParams directionSize = new LinearLayout.LayoutParams(-1, -2);
        directionSize.topMargin = dp(context, 16);
        form.addView(direction, directionSize);
        int[] sign = {pending.direction};
        Runnable selection = () -> {
            select(context, income, sign[0] == 1);
            select(context, expense, sign[0] == -1);
        };
        selection.run();
        income.setOnClickListener(view -> { sign[0] = 1; selection.run(); });
        expense.setOnClickListener(view -> { sign[0] = -1; selection.run(); });

        long[] time = {pending.occurredAt};
        TextView date = LedgerSheet.text(context, "", 14, R.color.accent);
        date.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16));
        date.setBackgroundResource(R.drawable.bg_pill);
        date.setFocusable(true);
        Runnable dateLabel = () -> date.setText(time[0] > 0 ? "到账时间\n" + date(time[0]) : "选择到账时间\n页面未提供日期，需要手动补充");
        dateLabel.run();
        date.setOnClickListener(view -> {
            Calendar calendar = Calendar.getInstance();
            if (time[0] > 0) calendar.setTimeInMillis(time[0]);
            DatePickerDialog picker = new DatePickerDialog(context, R.style.Theme_Ledger_Picker, (dialog, year, month, day) -> {
                calendar.set(year, month, day);
                new TimePickerDialog(context, R.style.Theme_Ledger_Picker, (clock, hour, minute) -> {
                    calendar.set(Calendar.HOUR_OF_DAY, hour);
                    calendar.set(Calendar.MINUTE, minute);
                    calendar.set(Calendar.SECOND, 0);
                    calendar.set(Calendar.MILLISECOND, 0);
                    time[0] = calendar.getTimeInMillis();
                    dateLabel.run();
                }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show();
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH));
            picker.getDatePicker().setMaxDate(System.currentTimeMillis());
            picker.show();
        });
        LinearLayout.LayoutParams dateSize = new LinearLayout.LayoutParams(-1, -2);
        dateSize.topMargin = dp(context, 16);
        form.addView(date, dateSize);
        TextView note = LedgerSheet.text(context, "相同页面内容只保留一次。同人同额的另一笔，请手动记账。", 11, R.color.text_secondary);
        note.setPadding(0, dp(context, 14), 0, 0);
        form.addView(note);
        sheet.content(form);
        sheet.extra("忽略这条识别结果", () -> {
            WeChatReceiptStore.ignore(db, pending.id);
            changed.run();
            sheet.dismiss();
        });
        sheet.secondary("稍后", sheet::dismiss);
        sheet.primary("确认入账", () -> {
            long cents = WeChatReceiptParser.cents(amount.getText().toString());
            if (cents <= 0) {
                amount.setError("请输入大于 0、最多两位小数的金额");
                return;
            }
            if (sign[0] == 0 || time[0] <= 0 || time[0] > System.currentTimeMillis() + 300_000L) {
                Toast.makeText(context, "请选择收支方向和有效的到账时间", Toast.LENGTH_LONG).show();
                return;
            }
            Runnable save = () -> {
                long id = WeChatReceiptStore.accept(db, pending, cents, sign[0], time[0]);
                if (id <= 0) {
                    Toast.makeText(context, "未保存，详情可能已核对过，请刷新检查", Toast.LENGTH_LONG).show();
                    return;
                }
                Budget.checkAndAlert(context, db.monthTotals(Budget.monthStart())[1]);
                context.sendBroadcast(new Intent(MainActivity.ACTION_BILLS_CHANGED).setPackage(context.getPackageName()));
                changed.run();
                sheet.dismiss();
                context.startActivity(new Intent(context, CategorizeActivity.class).putExtra(CategorizeActivity.EXTRA_ID, id));
            };
            if (WeChatReceiptStore.similarBill(db, sign[0] * cents / 100.0, time[0])) {
                LedgerSheet warning = new LedgerSheet(context, "附近日期已有同额账单",
                        "通知或导入可能已经记过这笔。请检查账本；仅在确认是另一笔时继续保存。");
                warning.secondary("返回检查", warning::dismiss).primary("仍然保存", () -> {
                    warning.dismiss();
                    save.run();
                }).show();
            } else save.run();
        });
        sheet.show();
    }

    private static TextView directionButton(Context context, String value) {
        TextView view = LedgerSheet.text(context, value, 15, R.color.text_primary);
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true);
        return view;
    }

    private static void select(Context context, TextView view, boolean selected) {
        view.setSelected(selected);
        view.setBackgroundResource(selected ? R.drawable.bg_pill_sel : R.drawable.bg_pill);
        view.setTextColor(context.getColor(selected ? R.color.accent : R.color.text_secondary));
    }

    private static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static String money(long cents) { return String.format(Locale.ROOT, "%.2f", cents / 100.0); }
    private static String date(long time) { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(time)); }
    private WeChatReceiptReview() {}
}
