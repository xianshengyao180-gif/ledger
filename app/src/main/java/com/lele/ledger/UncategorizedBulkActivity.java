package com.lele.ledger;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/**
 * 待分类记录列表。点黄色横幅进来，
 * 点击整条记录弹出 CategorizeActivity 选择用途，长按进入多选可批量删除。
 * 全部补完之后自动显示空状态。
 */
public class UncategorizedBulkActivity extends AppCompatActivity {

    private Db db;
    private BillAdapter adapter;
    private ListView list;
    private TextView tvHeader;
    private View tvEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_uncategorized_bulk);

        db = new Db(this);
        list = findViewById(R.id.lvUncategorized);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvHeader = findViewById(R.id.tvHeader);

        adapter = new BillAdapter(this);

        // 点击整条记录 → 弹出选择用途卡片
        adapter.setOnBillClick(b -> {
            Intent intent = new Intent(this, CategorizeActivity.class);
            intent.putExtra(CategorizeActivity.EXTRA_ID, b.id);
            startActivity(intent);
        });

        // 长按进入多选
        adapter.setOnBillLongClick(b -> {
            adapter.setMultiSelectMode(true);
            adapter.toggleSelection(b.id);
        });

        // 多选删除
        adapter.setOnSelectionChanged(count -> {
            if (count == 0) {
                adapter.setMultiSelectMode(false);
            }
        });

        list.setAdapter(adapter);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        refresh();
    }

    private void refresh() {
        List<Bill> items = db.listUncategorized();
        if (items.isEmpty()) {
            list.setVisibility(android.view.View.GONE);
            tvEmpty.setVisibility(android.view.View.VISIBLE);
        } else {
            list.setVisibility(android.view.View.VISIBLE);
            tvEmpty.setVisibility(android.view.View.GONE);
            tvHeader.setText("待分类 · " + items.size() + " 笔");
        }
        adapter.setItems(items);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        db.close();
        super.onDestroy();
    }
}