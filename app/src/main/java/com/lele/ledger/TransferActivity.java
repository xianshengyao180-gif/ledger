package com.lele.ledger;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 「检测到一笔转账，确认一下」页面。
 *
 * 为什么需要这一步：微信转账「已被接收」的通知里**没有金额**，方向也有歧义
 * （可能是我转出的被对方收下了，也可能是我收下的）。按用户要求，
 * 这种通知只在「对方真的收了」之后才记——但那时的通知仍不够写账。
 *
 * 所以：绝不猜，问一句。确认后写入 bills，并把这条提案从待确认表里删掉。
 * 记账应用猜错金额或方向，比多问一句坏得多。
 */
public class TransferActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "bill_id";

    private Db db;
    private long pendingId;
    private Bill pending;
    /** true = 我转出的（支出）。实测那条通知就是这个方向，所以默认它。 */
    private boolean asExpense = true;

    private TextView btnOut;
    private TextView btnIn;
    private EditText etAmount;

    private final SimpleDateFormat fmt =
            new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transfer);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.tvHeader)).setText("确认转账");

        db = new Db(this);
        pendingId = getIntent().getLongExtra(EXTRA_ID, -1L);
        pending = pendingId > 0 ? db.pendingById(pendingId) : db.nextPending();
        if (pending == null) {
            finish();
            return;
        }
        pendingId = pending.id;

        TextView tvWho = findViewById(R.id.tvTransferWho);
        TextView tvMeta = findViewById(R.id.tvTransferMeta);
        btnOut = findViewById(R.id.btnOut);
        btnIn = findViewById(R.id.btnIn);
        etAmount = findViewById(R.id.etTransferAmount);

        tvWho.setText(TextUtils.isEmpty(pending.title) ? "转账" : pending.title);
        tvMeta.setText(pending.channel + " · " + fmt.format(new Date(pending.ts))
                + " · 通知：转账已被接收");

        // 通知里偶尔能捞到金额（某些版本会带）；有就预填，省得用户再敲
        if (pending.amount < 0) {
            etAmount.setText(String.format(Locale.CHINA, "%.2f", Math.abs(pending.amount)));
            etAmount.setSelection(etAmount.getText().length());
        }

        btnOut.setOnClickListener(v -> setDirection(true));
        btnIn.setOnClickListener(v -> setDirection(false));
        setDirection(true);

        findViewById(R.id.btnConfirm).setOnClickListener(v -> confirm());
        findViewById(R.id.btnIgnore).setOnClickListener(v -> ignore());
    }

    /** 方向按钮的选中态：选中的用主题蓝文字 + 淡蓝底，另一个保持灰。 */
    private void setDirection(boolean expense) {
        asExpense = expense;
        style(btnOut, expense);
        style(btnIn, !expense);
    }

    private void style(TextView tv, boolean selected) {
        tv.setTextColor(ContextCompat.getColor(this,
                selected ? R.color.accent : R.color.text_secondary));
        tv.setBackgroundResource(selected ? R.drawable.bg_pill_sel : R.drawable.bg_pill);
    }

    /** 确认：用户填的金额 + 方向 -> 写成一条真实账单，然后删掉提案。 */
    private void confirm() {
        double amount = parse(etAmount.getText().toString());
        if (amount <= 0) {
            Toast.makeText(this, "请填一个大于 0 的金额", Toast.LENGTH_SHORT).show();
            return;
        }
        double signed = asExpense ? -amount : amount;

        long id = db.insert(signed, pending.channel,
                TextUtils.isEmpty(pending.title) ? "转账" : pending.title,
                System.currentTimeMillis(), null);
        if (id <= 0) {
            // 写库失败就别删提案，否则这笔就彻底丢了
            Toast.makeText(this, "写入失败，这条先留着", Toast.LENGTH_SHORT).show();
            return;
        }
        db.deletePending(pendingId);
        Toast.makeText(this, "已记账，去选个用途吧", Toast.LENGTH_SHORT).show();

        // 记完直接进分类页：转账的用途（人情/还款…）比普通消费更需要马上定
        startActivity(new Intent(this, CategorizeActivity.class)
                .putExtra(CategorizeActivity.EXTRA_ID, id));
        nextOrFinish();
    }

    /** 忽略：这条不是我的钱（对方退回、看错了、重复通知），直接扔掉提案。 */
    private void ignore() {
        db.deletePending(pendingId);
        Toast.makeText(this, "已忽略", Toast.LENGTH_SHORT).show();
        nextOrFinish();
    }

    /** 还有待确认的就接着问下一笔，没有就关页。 */
    private void nextOrFinish() {
        // 用户正在处理转账队列，撤回提醒，不重复弹出剩余笔数。
        BillNotify.cancel(this);
        Bill next = db.nextPending();
        if (next == null) {
            finish();
            return;
        }
        startActivity(new Intent(this, TransferActivity.class)
                .putExtra(EXTRA_ID, next.id));
        finish();
    }

    private static double parse(String s) {
        if (s == null || s.trim().isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    protected void onDestroy() {
        db.close();
        super.onDestroy();
    }
}
