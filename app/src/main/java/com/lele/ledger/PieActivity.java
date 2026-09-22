package com.lele.ledger;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 用途占比页：一张圆环图 + 一份图例。
 *
 * 只统计本月。跨月比较要靠切月份，那是另一个功能；
 * 这里先把「这个月钱花哪了」答清楚。
 */
public class PieActivity extends AppCompatActivity {

    private Db db;
    private PieChartView pie;
    private LinearLayout boxLegend;
    private TextView tvNoData;
    private TextView tvHint;
    private TextView btnExpense;
    private TextView btnIncome;

    private boolean income = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pie);

        db = new Db(this);
        pie = findViewById(R.id.pie);
        boxLegend = findViewById(R.id.boxLegend);
        tvNoData = findViewById(R.id.tvNoData);
        tvHint = findViewById(R.id.tvHint);
        btnExpense = findViewById(R.id.btnExpense);
        btnIncome = findViewById(R.id.btnIncome);

        btnExpense.setOnClickListener(v -> select(false));
        btnIncome.setOnClickListener(v -> select(true));

        select(false);
    }

    private void select(boolean wantIncome) {
        income = wantIncome;
        btnExpense.setBackgroundResource(income ? R.drawable.bg_pill : R.drawable.bg_pill_accent);
        btnExpense.setTextColor(ContextCompat.getColor(this,
                income ? R.color.text_secondary : android.R.color.white));
        btnIncome.setBackgroundResource(income ? R.drawable.bg_pill_accent : R.drawable.bg_pill);
        btnIncome.setTextColor(ContextCompat.getColor(this,
                income ? android.R.color.white : R.color.text_secondary));

        render();
    }

    private void render() {
        long since = Budget.monthStart();
        List<String[]> rows = db.categoryTotals(since, income);

        List<PieChartView.Slice> slices = new ArrayList<>();
        double total = 0;
        for (String[] r : rows) {
            double v = 0;
            try {
                v = Double.parseDouble(r[1]);
            } catch (NumberFormatException ignored) {
                // 解析不出就跳过，别让一条坏数据毁掉整张图
            }
            if (v > 0) {
                slices.add(new PieChartView.Slice(r[0], v, Category.colorOf(r[0])));
                total += v;
            }
        }

        pie.setSlices(slices, income ? "本月收入" : "本月支出",
                String.format(Locale.CHINA, "¥%.2f", total));

        boxLegend.removeAllViews();
        for (PieChartView.Slice s : slices) {
            boxLegend.addView(legendRow(s, total));
        }

        tvNoData.setVisibility(slices.isEmpty() ? View.VISIBLE : View.GONE);

        int uncat = db.uncategorizedCount(since, income);
        if (uncat > 0) {
            // 直接说清「饼图里那块灰的是什么、怎么消掉」，比让用户自己猜好
            tvHint.setText(String.format(Locale.CHINA,
                    "有 %d 笔还没选用途，已归入「未分类」。在主界面点一下那条记录即可补上。", uncat));
            tvHint.setVisibility(View.VISIBLE);
        } else {
            tvHint.setVisibility(View.GONE);
        }
    }

    private View legendRow(PieChartView.Slice s, double total) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(8);
        row.setLayoutParams(rlp);

        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(s.color);
        dot.setBackground(d);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dlp.rightMargin = dp(8);
        row.addView(dot, dlp);

        TextView name = new TextView(this);
        name.setText(s.label);
        name.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        name.setTextSize(14);
        row.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView pct = new TextView(this);
        pct.setText(total > 0
                ? String.format(Locale.CHINA, "%.0f%%", s.value * 100 / total)
                : "—");
        pct.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        pct.setTextSize(13);
        pct.setGravity(Gravity.END);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                dp(48), ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.rightMargin = dp(10);
        row.addView(pct, plp);

        TextView amount = new TextView(this);
        amount.setText(String.format(Locale.CHINA, "¥%.2f", s.value));
        amount.setTextColor(ContextCompat.getColor(this,
                income ? R.color.income : R.color.expense));
        amount.setTextSize(14);
        amount.setGravity(Gravity.END);
        row.addView(amount, new LinearLayout.LayoutParams(
                dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));

        return row;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onDestroy() {
        db.close();
        super.onDestroy();
    }
}
