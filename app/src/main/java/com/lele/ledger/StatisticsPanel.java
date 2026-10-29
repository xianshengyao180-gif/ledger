package com.lele.ledger;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;

/** 主页面内的用途统计面板，与明细共用月份和账单数据。 */
final class StatisticsPanel {

    private final Context context;
    private final View root;
    private List<Bill> bills = Collections.emptyList();
    private PieChartView pie;
    private LinearLayout boxLegend;
    private TextView tvNoData;
    private TextView tvHint;
    private TextView btnExpense;
    private TextView btnIncome;

    private boolean income = false;
    private String selectedLabel;

    StatisticsPanel(View root, Bundle savedInstanceState) {
        this.root = root;
        context = root.getContext();
        if (savedInstanceState != null) {
            income = savedInstanceState.getBoolean("statistics_income", false);
        }
        pie = root.findViewById(R.id.pie);
        boxLegend = root.findViewById(R.id.boxLegend);
        tvNoData = root.findViewById(R.id.tvNoData);
        tvHint = root.findViewById(R.id.tvHint);
        btnExpense = root.findViewById(R.id.btnExpense);
        btnIncome = root.findViewById(R.id.btnIncome);

        btnExpense.setOnClickListener(v -> select(false));
        btnIncome.setOnClickListener(v -> select(true));

        select(income);
    }

    void saveState(Bundle outState) {
        outState.putBoolean("statistics_income", income);
    }

    void refresh(long monthStart, List<Bill> bills, boolean hasNextMonth) {
        this.bills = bills;
        Calendar month = Calendar.getInstance();
        month.setTimeInMillis(monthStart);
        ((TextView) root.findViewById(R.id.tvStatsMonth)).setText(String.format(Locale.CHINA,
                "%d年 %d月", month.get(Calendar.YEAR), month.get(Calendar.MONTH) + 1));
        View nextMonth = root.findViewById(R.id.btnStatsNextMonth);
        nextMonth.setEnabled(hasNextMonth);
        nextMonth.setAlpha(hasNextMonth ? 0.5f : 0.15f);
        render();
    }

    private void select(boolean wantIncome) {
        income = wantIncome;
        btnExpense.setBackgroundResource(income ? R.drawable.bg_pill : R.drawable.bg_pill_accent);
        btnExpense.setTextColor(ContextCompat.getColor(context,
                income ? R.color.text_secondary : R.color.on_accent));
        btnIncome.setBackgroundResource(income ? R.drawable.bg_pill_accent : R.drawable.bg_pill);
        btnIncome.setTextColor(ContextCompat.getColor(context,
                income ? R.color.on_accent : R.color.text_secondary));
        btnExpense.setSelected(!income);
        btnIncome.setSelected(income);

        render();
    }

    private void render() {
        Map<String, Double> totals = new LinkedHashMap<>();
        int uncat = 0;
        for (Bill bill : bills) {
            if ((bill.amount >= 0) != income) continue;
            String category = bill.uncategorized() ? Category.NONE : bill.category;
            totals.put(category, totals.getOrDefault(category, 0d) + Math.abs(bill.amount));
            if (bill.uncategorized()) uncat++;
        }

        List<PieChartView.Slice> slices = new ArrayList<>();
        double total = 0;
        for (Map.Entry<String, Double> entry : totals.entrySet()) {
            double v = entry.getValue();
            if (v > 0) {
                slices.add(new PieChartView.Slice(entry.getKey(), v, Category.colorOf(entry.getKey())));
                total += v;
            }
        }
        slices.sort((left, right) -> Double.compare(right.value, left.value));
        selectedLabel = null;

        pie.setSlices(slices, income ? "收入合计" : "支出合计",
                String.format(Locale.CHINA, "¥%,.2f", total));

        boxLegend.removeAllViews();
        for (PieChartView.Slice s : slices) {
            boxLegend.addView(legendRow(s, total));
        }

        tvNoData.setVisibility(slices.isEmpty() ? View.VISIBLE : View.GONE);

        if (uncat > 0) {
            // 直接说清「饼图里那块灰的是什么、怎么消掉」，比让用户自己猜好
            tvHint.setText(context.getString(R.string.stats_uncategorized_hint, uncat));
            tvHint.setVisibility(View.VISIBLE);
        } else {
            tvHint.setVisibility(View.GONE);
        }
    }

    private View legendRow(PieChartView.Slice s, double total) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(12), dp(10), dp(12));
        row.setMinimumHeight(dp(52));
        row.setFocusable(true);
        row.setOnClickListener(v -> {
            selectedLabel = s.label.equals(selectedLabel) ? null : s.label;
            pie.selectSlice(selectedLabel);
            for (int index = 0; index < boxLegend.getChildCount(); index++) {
                View legend = boxLegend.getChildAt(index);
                boolean selected = selectedLabel != null && selectedLabel.equals(legend.getTag());
                legend.setSelected(selected);
                legend.setBackgroundResource(selected ? R.drawable.bg_pill : android.R.color.transparent);
            }
        });
        row.setTag(s.label);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(8);
        row.setLayoutParams(rlp);

        View dot = new View(context);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(s.color);
        dot.setBackground(d);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dlp.rightMargin = dp(8);
        row.addView(dot, dlp);

        TextView name = new TextView(context);
        name.setText(s.label);
        name.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        name.setTextSize(14);
        row.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView pct = new TextView(context);
        pct.setText(total > 0
                ? String.format(Locale.CHINA, "%.1f%%", s.value * 100 / total)
                : "—");
        pct.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        pct.setTextSize(13);
        pct.setGravity(Gravity.END);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                dp(48), ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.rightMargin = dp(10);
        row.addView(pct, plp);

        TextView amount = new TextView(context);
        amount.setText(String.format(Locale.CHINA, "¥%,.2f", s.value));
        amount.setTextColor(ContextCompat.getColor(context,
                R.color.text_primary));
        amount.setTextSize(14);
        amount.setGravity(Gravity.END);
        row.addView(amount, new LinearLayout.LayoutParams(
                dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));

        return row;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                context.getResources().getDisplayMetrics());
    }

}
