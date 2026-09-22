package com.lele.ledger;

import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 预算设置页：总预算 + 按分类设额度。
 */
public class BudgetActivity extends AppCompatActivity {

    private EditText etBudget;
    private EditText etThreshold;
    private TextView tvTitle, tvAmount, tvSub, btnClear;
    private LinearLayout tagList;
    private View btnAddTag;

    private Db db;

    private final List<TagEntry> tags = new ArrayList<>();

    private static class TagEntry {
        final String name;
        double budget;
        int threshold;

        TagEntry(String name, double budget, int threshold) {
            this.name = name;
            this.budget = budget;
            this.threshold = threshold;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_budget);

        db = new Db(this);

        tvTitle = findViewById(R.id.tvTitle);
        tvAmount = findViewById(R.id.tvAmount);
        tvSub = findViewById(R.id.tvSub);
        btnClear = findViewById(R.id.btnClear);

        etBudget = findViewById(R.id.etBudget);
        etThreshold = findViewById(R.id.etThreshold);
        tagList = findViewById(R.id.tagList);
        btnAddTag = findViewById(R.id.btnAddTag);

        double curBudget = Budget.amount(this);
        int curThreshold = Budget.threshold(this);
        double spent = db.monthTotals(Budget.monthStart())[1];

        updateSummary(curBudget, spent);

        if (curBudget > 0) {
            etBudget.setText(String.format(Locale.CHINA, "%.2f", curBudget));
            etBudget.setSelection(etBudget.getText().length());
        }
        etThreshold.setText(String.valueOf(curThreshold));

        loadTags();

        btnClear.setVisibility(curBudget > 0 ? View.VISIBLE : View.GONE);
        btnClear.setOnClickListener(v -> {
            Budget.setAmount(this, 0);
            Toast.makeText(this, "已关闭预算提醒", Toast.LENGTH_SHORT).show();
            finish();
        });

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                double newBudget = parseOr(etBudget.getText().toString(), 0);
                updateSummary(newBudget, spent);
                btnClear.setVisibility(newBudget > 0 ? View.VISIBLE : View.GONE);
            }
        };
        etBudget.addTextChangedListener(watcher);

        btnAddTag.setOnClickListener(v -> promptAddTag());

        findViewById(R.id.btnSave).setOnClickListener(v -> save());
    }

    private void loadTags() {
        tags.clear();
        tagList.removeAllViews();
        List<String[]> rows = db.listBudgetTags();
        for (String[] row : rows) {
            String name = row[1];
            double budget = Double.parseDouble(row[2]);
            int threshold = row.length > 3 && row[3] != null
                    ? Integer.parseInt(row[3]) : Budget.DEFAULT_THRESHOLD;
            addTagRow(name, budget, threshold);
        }
    }

    private void addTagRow(String name, double budget, int threshold) {
        TagEntry entry = new TagEntry(name, budget, threshold);
        View row = LayoutInflater.from(this).inflate(R.layout.item_budget_tag, tagList, false);

        TextView tvTagName = row.findViewById(R.id.tvTagName);
        TextView etTagBudget = row.findViewById(R.id.etTagBudget);
        TextView etTagThreshold = row.findViewById(R.id.etTagThreshold);
        ImageView btnDeleteTag = row.findViewById(R.id.btnDeleteTag);

        // 整行背景根据用途着色
        int color = Category.colorOf(name);
        row.setBackgroundColor(color);

        tvTagName.setText(name);
        refreshTagRowDisplay(etTagBudget, etTagThreshold, entry);

        // 点击标签行任意位置 → 弹出对话框修改预算和阈值
        row.setOnClickListener(v -> promptEditTag(entry, etTagBudget, etTagThreshold));

        btnDeleteTag.setOnClickListener(v -> confirmDeleteTag(entry, row));

        tags.add(entry);
        tagList.addView(row);
    }

    /** 刷新一行标签的显示值 */
    private void refreshTagRowDisplay(TextView tvBudget, TextView tvThreshold, TagEntry entry) {
        tvBudget.setText(entry.budget > 0
                ? String.format(Locale.CHINA, "%.0f", entry.budget) : "");
        tvThreshold.setText(String.valueOf(entry.threshold));
    }

    /** 弹出双输入框对话框修改预算和阈值 */
    private void promptEditTag(TagEntry entry, TextView tvBudget, TextView tvThreshold) {
        ViewGroup root = findViewById(android.R.id.content);
        View overlay = LayoutInflater.from(this).inflate(R.layout.dialog_input, root, false);
        overlay.setBackgroundColor(0x99000000);
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);

        ((TextView) overlay.findViewById(R.id.dialogTitle)).setText(entry.name + " — 修改");

        EditText input = overlay.findViewById(R.id.dialogInput);
        input.setVisibility(View.VISIBLE);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("预算额度（元），0 表示不设");
        if (entry.budget > 0) {
            input.setText(String.format(Locale.CHINA, "%.0f", entry.budget));
            input.setSelection(input.getText().length());
        }

        // 在 budget 输入框下方插入阈值输入 EditText
        final EditText etThresholdExtra = new EditText(this);
        etThresholdExtra.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        etThresholdExtra.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etThresholdExtra.setHint("预警阈值（%）");
        etThresholdExtra.setText(String.valueOf(entry.threshold));
        etThresholdExtra.setSelection(etThresholdExtra.getText().length());
        etThresholdExtra.setBackgroundResource(R.drawable.bg_hero);
        etThresholdExtra.setPadding(dp(12), dp(12), dp(12), dp(12));
        etThresholdExtra.setTextColor(0xFFFFFFFF);
        etThresholdExtra.setTextSize(16);

        // 插到 input 后面
        ViewGroup cardRoot = (ViewGroup) overlay;
        int inputIx = cardRoot.indexOfChild(input);
        if (inputIx >= 0) {
            cardRoot.addView(etThresholdExtra, inputIx + 1);
        } else {
            cardRoot.addView(etThresholdExtra);
        }

        // 隐藏默认的 hint
        overlay.findViewById(R.id.dialogHint).setVisibility(View.GONE);

        overlay.findViewById(R.id.dialogCancel).setOnClickListener(v -> root.removeView(overlay));
        overlay.findViewById(R.id.dialogConfirm).setOnClickListener(v -> {
            double newBudget = parseOr(input.getText().toString(), 0);
            int newThreshold = (int) parseOr(etThresholdExtra.getText().toString(), Budget.DEFAULT_THRESHOLD);
            entry.budget = newBudget;
            entry.threshold = newThreshold;
            refreshTagRowDisplay(tvBudget, tvThreshold, entry);
            root.removeView(overlay);
        });

        overlay.setOnClickListener(v -> root.removeView(overlay));
        root.addView(overlay, lp);
    }

    /** 全屏浮动遮罩：选择已存在的用途分类作为预算标签。 */
    private void promptAddTag() {
        ViewGroup root = findViewById(android.R.id.content);
        View overlay = LayoutInflater.from(this).inflate(R.layout.dialog_input, root, false);
        overlay.setBackgroundColor(0x99000000);
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);

        ((TextView) overlay.findViewById(R.id.dialogTitle)).setText("选择用途");
        overlay.findViewById(R.id.dialogInput).setVisibility(View.GONE);
        overlay.findViewById(R.id.dialogHint).setVisibility(View.GONE);
        overlay.findViewById(R.id.dialogCancel).setVisibility(View.GONE);
        overlay.findViewById(R.id.dialogConfirm).setVisibility(View.GONE);

        ViewGroup card = (ViewGroup) overlay;
        LinearLayout gridBox = new LinearLayout(this);
        gridBox.setOrientation(LinearLayout.VERTICAL);
        gridBox.setPadding(0, dp(20), 0, 0);

        CategoryChips.fill(this, gridBox, -1, name -> {
            for (TagEntry t : tags) {
                if (t.name.equals(name)) {
                    Toast.makeText(this, "已添加「" + name + "」", Toast.LENGTH_SHORT).show();
                    root.removeView(overlay);
                    return;
                }
            }
            addTagRow(name, 0, Budget.DEFAULT_THRESHOLD);
            root.removeView(overlay);
        });

        card.addView(gridBox,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        overlay.setOnClickListener(v -> root.removeView(overlay));
        root.addView(overlay, lp);
    }

    /** 全屏浮动遮罩：删除确认。 */
    private void confirmDeleteTag(TagEntry entry, View row) {
        ViewGroup root = findViewById(android.R.id.content);
        View overlay = LayoutInflater.from(this).inflate(R.layout.dialog_input, root, false);
        overlay.setBackgroundColor(0x99000000);
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);

        ((TextView) overlay.findViewById(R.id.dialogTitle)).setText("删除标签");
        overlay.findViewById(R.id.dialogInput).setVisibility(View.GONE);
        TextView hint = overlay.findViewById(R.id.dialogHint);
        hint.setVisibility(View.VISIBLE);
        hint.setText("确定删除「" + entry.name + "」标签？");

        overlay.setOnClickListener(v -> root.removeView(overlay));
        root.addView(overlay, lp);

        overlay.findViewById(R.id.dialogCancel).setOnClickListener(v -> root.removeView(overlay));
        overlay.findViewById(R.id.dialogConfirm).setOnClickListener(v -> {
            db.getWritableDatabase().delete("budget_tags", "name=?", new String[]{entry.name});
            tags.remove(entry);
            tagList.removeView(row);
            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
            root.removeView(overlay);
        });
    }

    private void updateSummary(double budget, double spent) {
        if (budget <= 0) {
            tvTitle.setText("当前：未设预算");
            tvAmount.setText("请填写或关闭");
            tvSub.setText("本月已支出 ¥" + String.format(Locale.CHINA, "%.2f", spent));
        } else {
            int pct = (int) Math.floor(spent * 100 / budget);
            String label = "当前：设了预算";
            if (spent > budget) {
                label = "当前：已超支";
            } else if (pct >= Budget.threshold(this)) {
                label = "当前：已达预警线";
            }
            tvTitle.setText(label);
            tvAmount.setText(String.format(Locale.CHINA, "¥%.2f ／ ¥%.2f", spent, budget));
            tvSub.setText("已用 " + pct + "%　·　" +
                    String.format(Locale.CHINA, "剩余 ¥%.2f", budget - spent));
        }
    }

    private void save() {
        double totalBudget = parseOr(etBudget.getText().toString(), 0);
        int th = (int) parseOr(etThreshold.getText().toString(), Budget.DEFAULT_THRESHOLD);
        if (totalBudget < 0) totalBudget = 0;
        if (th < 0) th = 0;
        if (th > 100) th = 100;

        Budget.setAmount(this, totalBudget);
        Budget.setThreshold(this, th);

        db.getWritableDatabase().delete("budget_tags", null, null);
        for (TagEntry entry : tags) {
            db.upsertBudgetTag(entry.name, entry.budget, entry.threshold);
            Budget.setTagThreshold(this, entry.name, entry.threshold);
        }

        Toast.makeText(this,
                totalBudget > 0 ? "已保存" : "已关闭预算提醒",
                Toast.LENGTH_SHORT).show();

        if (totalBudget > 0) {
            double spent = db.monthTotals(Budget.monthStart())[1];
            Budget.checkAndAlert(this, spent);
        }

        finish();
    }

    private static double parseOr(String s, double def) {
        if (s == null || s.trim().isEmpty()) return def;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
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