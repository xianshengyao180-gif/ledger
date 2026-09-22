package com.lele.ledger;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 「选个用途」页面。
 *
 * 从通知点进来，也可以从主界面点某条记录进来（那时带着记录的 id）。
 * 选完就关——用户是在付完钱之后顺手点进来的，多看一屏都是负担。
 */
public class CategorizeActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "bill_id";

    private Db db;
    private long billId;
    private Bill bill;

    private final SimpleDateFormat fmt =
            new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_categorize);

        db = new Db(this);
        billId = getIntent().getLongExtra(EXTRA_ID, -1L);

        // 没带 id（例如通知被系统恢复后又点进来）就取最近一笔未分类的，
        // 总比开一个空白的分类页强。
        bill = billId > 0 ? db.byId(billId) : db.nextUncategorized();
        if (bill == null) {
            finish();
            return;
        }
        billId = bill.id;

        TextView tvKind = findViewById(R.id.tvKind);
        TextView tvAmount = findViewById(R.id.tvAmount);
        TextView tvMeta = findViewById(R.id.tvMeta);

        tvKind.setText(bill.amount >= 0 ? "这笔是收入" : "这笔是支出");
        tvAmount.setText(String.format(Locale.CHINA, "%s¥%.2f",
                bill.amount >= 0 ? "+" : "-", Math.abs(bill.amount)));
        tvMeta.setText(bill.channel + " · " + fmt.format(new Date(bill.ts)));

        LinearLayout box = findViewById(R.id.boxChips);
        CategoryChips.fill(this, box, bill.amount, bill.category, this::pick);

        // 已经选过的才需要「清除」；没选过时留着这个按钮只会让人困惑
        View btnClear = findViewById(R.id.btnClear);
        btnClear.setVisibility(bill.uncategorized() ? View.GONE : View.VISIBLE);
        btnClear.setOnClickListener(v -> clear());

        findViewById(R.id.btnSkip).setOnClickListener(v -> nextOrFinish());
    }

    /** 清掉用途，退回「未分类」——选错了得能改回来。 */
    private void clear() {
        db.updateCategory(billId, "");
        BillNotify.refresh(this, db);
        nextOrFinish();
    }

    /** 选中用途：写库、刷新待分类提醒，然后继续下一笔（没有就关页）。 */
    private void pick(String name) {
        db.updateCategory(billId, name);
        BillNotify.refresh(this, db);
        nextOrFinish();
    }

    private void nextOrFinish() {
        Bill next = db.nextUncategorized();
        if (next == null) {
            BillNotify.cancel(this);
            finish();
            return;
        }
        // 还有没分类的，直接换内容继续问，省得用户再点一次通知
        startActivity(new Intent(this, CategorizeActivity.class)
                .putExtra(EXTRA_ID, next.id));
        finish();
    }

    @Override
    protected void onDestroy() {
        db.close();
        super.onDestroy();
    }
}
