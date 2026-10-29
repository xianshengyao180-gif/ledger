package com.lele.ledger;

import androidx.appcompat.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 全部界面：一个汇总条 + 三个按钮 + 一个列表。就这些。
 * 没有登录、没有云同步、没有设置项迷宫。
 */
public class MainActivity extends AppCompatActivity {

    /** 监听服务记下一笔账后发出的广播。用于让界面实时刷新，不必切后台再回来。 */
    public static final String ACTION_BILLS_CHANGED = "com.lele.ledger.BILLS_CHANGED";

    private Db db;
    private BillAdapter adapter;
    private View statisticsView;
    private CircularPageLayout pageContent;
    private AnimatedTabBar tabBar;
    private StatisticsPanel statisticsPanel;
    private boolean statisticsVisible;
    private TextView tvExpense;
    private TextView tvIncome;
    private TextView tvNet;
    private TextView tvAliveText;
    private View dotAlive;
    private View tvEmpty;
    private View tvBudgetSet;
    private View boxBudget;
    private TextView tvBudgetLine;
    private TextView tvBudgetSub;
    private BudgetRingView budgetRing;
    private TextView tvBudgetTitle, tvBudgetRemaining, tvBudgetTotal;
    private TextView banner;
    private TextView bannerTransfer;
    private TextView bannerReceipt;

    /** 多选操作栏 */
    private View multiBar;
    private TextView tvMultiCount;
    private View btnMultiDelete;
    private View btnMultiCancel;
    private View btnMultiSelectAll;

    /** 删除确认浮动卡片（动态 inflate，添加到 DecorView 确保全屏覆盖） */
    private View confirmOverlay;
    private TextView confirmTitle;
    private TextView confirmMessage;
    private TextView confirmDetail;
    private View confirmCancel;
    private View confirmDeleteBtn;

    /** 当前待确认的删除操作 */
    private Runnable pendingDeleteAction;

    /** 编辑账单浮动卡片 */
    private View editOverlay;
    private android.widget.EditText etEditAmount;
    private TextView tvEditCategory;
    private Bill editingBill;
    private String editingCategoryBefore;

    /** 手动记账浮动卡片 */
    private View addOverlay;
    private android.widget.EditText etAddAmount;
    private TextView tvAddCategory;
    private TextView tvAddSign;
    private View btnAddExpense;
    private View btnAddIncome;
    /** 手动记账当前方向：true=收入。默认支出——绝大多数手动补记都是花钱。 */
    private boolean addIsIncome = false;


    private boolean receiverRegistered = false;
    private final Runnable refreshAfterStart = this::refresh;

    /** 月份偏移：0=本月，-1=上月，+1=下月 */
    private int monthOffset = 0;
    private TextView tvMonth;
    private View btnPrevMonth, btnNextMonth;
    private TextView tvListHint;

    /** 按偏移月返回当月1日0点时间戳 */
    private long offsetMonthStart() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.add(java.util.Calendar.MONTH, monthOffset);
        c.set(java.util.Calendar.DAY_OF_MONTH, 1);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private final BroadcastReceiver billsChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("dark_mode", false) ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        BillNotify.clearCategoryReminder(this);
        pageContent = findViewById(R.id.pageContent);
        tabBar = findViewById(R.id.tabBar);
        pageContent.setProgressListener(tabBar::setProgress);
        if (savedInstanceState != null) {
            monthOffset = savedInstanceState.getInt("month_offset", 0);
            statisticsVisible = savedInstanceState.getBoolean("statistics_visible", false);
        }
        statisticsView = findViewById(R.id.statisticsPanel);
        statisticsPanel = new StatisticsPanel(statisticsView, savedInstanceState);
        statisticsView.findViewById(R.id.btnStatsPrevMonth).setOnClickListener(v -> {
            monthOffset--;
            refresh();
        });
        statisticsView.findViewById(R.id.btnStatsNextMonth).setOnClickListener(v -> {
            if (monthOffset < 0) {
                monthOffset++;
                refresh();
            }
        });

        ListView list = findViewById(R.id.lvBills);
        View header = getLayoutInflater().inflate(R.layout.view_ledger_header, list, false);
        list.addHeaderView(header, null, false);

        db = new Db(this);
        tvExpense = header.findViewById(R.id.tvExpense);
        tvIncome = header.findViewById(R.id.tvIncome);
        tvNet = header.findViewById(R.id.tvNet);
        tvAliveText = findViewById(R.id.tvAliveText);
        dotAlive = findViewById(R.id.dotAlive);
        tvEmpty = header.findViewById(R.id.tvEmpty);
        tvMonth = header.findViewById(R.id.tvMonth);
        btnPrevMonth = header.findViewById(R.id.btnPrevMonth);
        btnNextMonth = header.findViewById(R.id.btnNextMonth);
        tvBudgetSet = header.findViewById(R.id.tvBudgetSet);
        boxBudget = header.findViewById(R.id.boxBudget);
        tvBudgetLine = header.findViewById(R.id.tvBudgetLine);
        tvBudgetSub = header.findViewById(R.id.tvBudgetSub);
        tvBudgetTitle = header.findViewById(R.id.tvBudgetTitle);
        tvBudgetRemaining = header.findViewById(R.id.tvBudgetRemaining);
        tvBudgetTotal = header.findViewById(R.id.tvBudgetTotal);
        budgetRing = header.findViewById(R.id.budgetRing);
        tvListHint = header.findViewById(R.id.tvListHint);
        // 下拉刷新已去除
        banner = header.findViewById(R.id.banner);
        bannerTransfer = header.findViewById(R.id.bannerTransfer);
        bannerReceipt = header.findViewById(R.id.bannerReceipt);
        bannerReceipt.setOnClickListener(v -> WeChatReceiptReview.show(this, db, this::refresh));

        // 预算两处入口：没设时点提示行，设了点进度条本身
        tvBudgetSet.setOnClickListener(v -> showBudgetDialog());
        boxBudget.setOnClickListener(v -> showBudgetDialog());

        adapter = new BillAdapter(this);

        // 右划删除——弹确认
        adapter.setOnBillDelete(b -> confirmDelete(b));
        // 点击无操作（记录本身不响应点击）
        adapter.setOnBillClick(this::openCategorize);
        // 长按进入多选模式
        adapter.setOnBillLongClick(this::enterMultiSelect);
        // 多选模式选择变化
        adapter.setOnSelectionChanged(count -> {
            if (count > 0) {
                multiBar.setVisibility(View.VISIBLE);
                tvMultiCount.setText(String.format(Locale.CHINA, "已选 %d 笔", count));
            } else {
                multiBar.setVisibility(View.GONE);
            }
        });
        list.setAdapter(adapter);

        // 月份切换
        btnPrevMonth.setOnClickListener(v -> {
            monthOffset--;
            refresh();
        });
        btnNextMonth.setOnClickListener(v -> {
            monthOffset++;
            refresh();
        });

        // 多选操作栏
        multiBar = findViewById(R.id.multiBar);
        tvMultiCount = findViewById(R.id.tvMultiCount);
        btnMultiDelete = findViewById(R.id.btnMultiDelete);
        btnMultiCancel = findViewById(R.id.btnMultiCancel);
        btnMultiSelectAll = findViewById(R.id.btnMultiSelectAll);

        btnMultiDelete.setOnClickListener(v -> confirmMultiDelete());
        btnMultiCancel.setOnClickListener(v -> exitMultiSelect());
        btnMultiSelectAll.setOnClickListener(v -> {
            // 如果当前是全选状态，再点就取消全选
            if ("all".equals(btnMultiSelectAll.getTag())) {
                adapter.clearSelection();
                // clearSelection 自动退出多选，同步状态
                ((TextView) btnMultiSelectAll).setText("全选");
                btnMultiSelectAll.setTag(null);
                multiBar.setVisibility(View.GONE);
            } else {
                selectAllItems();
            }
        });

        // 编辑账单浮动卡片
        editOverlay = findViewById(R.id.editOverlay);
        etEditAmount = findViewById(R.id.etEditAmount);
        tvEditCategory = findViewById(R.id.tvEditCategory);
        editOverlay.setOnClickListener(v -> hideBillEditor());
        editOverlay.findViewById(R.id.btnEditClose).setOnClickListener(v -> hideBillEditor());
        editOverlay.findViewById(R.id.editCard).setOnClickListener(null);
        editOverlay.findViewById(R.id.btnEditSave).setOnClickListener(v -> saveBillEdit());
        editOverlay.findViewById(R.id.btnEditCancel).setOnClickListener(v -> hideBillEditor());
        editOverlay.findViewById(R.id.btnEditDelete).setOnClickListener(v -> {
            Bill toDelete = editingBill;
            hideBillEditor();
            if (toDelete != null) confirmDelete(toDelete);
        });
        editOverlay.findViewById(R.id.btnEditCategory).setOnClickListener(v -> {
            if (editingBill != null) {
                showCategoryPicker();
            }
        });

        // 手动记一笔浮动卡片
        addOverlay = findViewById(R.id.addOverlay);
        etAddAmount = findViewById(R.id.etAddAmount);
        tvAddCategory = findViewById(R.id.tvAddCategory);
        tvAddSign = findViewById(R.id.tvAddSign);
        btnAddExpense = findViewById(R.id.btnAddExpense);
        btnAddIncome = findViewById(R.id.btnAddIncome);
        addOverlay.setOnClickListener(v -> hideAddCard());
        addOverlay.findViewById(R.id.addCard).setOnClickListener(null);
        addOverlay.findViewById(R.id.btnAddClose).setOnClickListener(v -> hideAddCard());
        addOverlay.findViewById(R.id.btnAddCancel).setOnClickListener(v -> hideAddCard());
        addOverlay.findViewById(R.id.btnAddSave).setOnClickListener(v -> saveAddedBill());
        // 方向切换：影响金额前缀颜色，也决定用途词表（支出 12 类 / 收入 8 类）
        btnAddExpense.setOnClickListener(v -> setAddDirection(false));
        btnAddIncome.setOnClickListener(v -> setAddDirection(true));
        addOverlay.findViewById(R.id.btnAddCategory).setOnClickListener(v -> showAddCategoryPicker());
        // 悬浮按钮：入口只有一个，不再往抽屉里塞
        findViewById(R.id.btnAdd).setOnClickListener(v -> showAddCard());

        // 删除确认浮动卡片（动态 inflate 的覆盖层，全屏覆盖）
        ViewGroup decorView = (ViewGroup) getWindow().getDecorView();
        confirmOverlay = getLayoutInflater().inflate(R.layout.confirm_delete, decorView, false);
        confirmTitle = confirmOverlay.findViewById(R.id.confirmTitle);
        confirmMessage = confirmOverlay.findViewById(R.id.confirmMessage);
        confirmDetail = confirmOverlay.findViewById(R.id.confirmDetail);
        confirmCancel = confirmOverlay.findViewById(R.id.confirmCancel);
        confirmDeleteBtn = confirmOverlay.findViewById(R.id.confirmDelete);
        // 加到 DecorView 确保覆盖全屏和系统栏
        decorView.addView(confirmOverlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        confirmCancel.setOnClickListener(v -> hideConfirmCard());
        confirmDeleteBtn.setOnClickListener(v -> {
            Runnable action = pendingDeleteAction;
            hideConfirmCard();
            if (action != null) {
                action.run();
            }
        });
        // 点击遮罩关闭
        confirmOverlay.setOnClickListener(v -> hideConfirmCard());
        // 阻止卡片区域的点击穿透
        confirmOverlay.findViewById(R.id.confirmCard).setOnClickListener(null);

        findViewById(R.id.btnLedger).setOnClickListener(v -> {
            if (statisticsVisible) {
                showStatistics(false);
            } else {
                list.smoothScrollToPosition(0);
            }
        });
        findViewById(R.id.btnPie).setOnClickListener(v -> showStatistics(true));
        showStatistics(statisticsVisible);
        findViewById(R.id.btnBudgetSettings).setOnClickListener(v -> showBudgetDialog());
        // 待分类横幅：点进去显示所有未分类记录列表，跟主界面一样可滑动/点选
        banner.setOnClickListener(v ->
                startActivity(new Intent(this, UncategorizedBulkActivity.class)));
        // 待确认转账横幅：金额都还没定，比选用途更紧急，所以排在上面
        bannerTransfer.setOnClickListener(v ->
                startActivity(new Intent(this, TransferActivity.class)));

        findViewById(R.id.btnImport).setOnClickListener(v ->
                startActivity(new Intent(this, ImportActivity.class)));
        findViewById(R.id.btnListener).setOnClickListener(v -> openListenerSettings());
        findViewById(R.id.btnWechatReader).setOnClickListener(v -> openWechatReaderSettings());
        findViewById(R.id.btnBattery).setOnClickListener(v -> requestBatteryExemption());
        findViewById(R.id.btnDebug).setOnClickListener(v -> showRawNotifications());

        // 点状态行直达「自启动管理」——ColorOS 上不放开这里，常驻服务活不过重启
        findViewById(R.id.tvAlive).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.open_listener)
                .setMessage(findViewById(R.id.tvAlive).getContentDescription())
                .setPositiveButton(R.string.open_listener, (dialog, which) -> openListenerSettings())
                .setNeutralButton(R.string.open_autostart, (dialog, which) -> openAutoStartSettings())
                .setNegativeButton(R.string.add_cancel, null).show());

        // 列表为空时，点空白区域也能直接看原始通知，省得找不到按钮
        tvEmpty.setOnClickListener(v -> showRawNotifications());

        // 侧边抽屉开关（英雄卡右上角齿轮 + 旧工具栏按钮）
        View drawerOverlay = findViewById(R.id.drawerOverlay);
        View drawerPanel = findViewById(R.id.drawerPanel);
        View.OnClickListener toggleDrawerListener = v -> toggleDrawer(drawerOverlay, drawerPanel);
        // btnDrawer 入口已统一到 btnDrawerHero
        // 英雄卡右上角齿轮也绑定同一切换
        header.findViewById(R.id.btnDrawerHero).setOnClickListener(toggleDrawerListener);
        statisticsView.findViewById(R.id.btnStatsSettings).setOnClickListener(toggleDrawerListener);
        // 点击遮罩关闭抽屉
        drawerOverlay.setOnClickListener(v -> closeDrawer(drawerOverlay, drawerPanel));
        // 阻止面板区域点击穿透（点面板本身不关闭）
        drawerPanel.setOnClickListener(null);

        // 如果是从深色模式切换重建过来的，自动打开抽屉
        if (getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("keep_drawer_open", false)) {
            getSharedPreferences("prefs", MODE_PRIVATE).edit()
                    .putBoolean("keep_drawer_open", false).apply();
            drawerOverlay.post(() -> openDrawer(drawerOverlay, drawerPanel));
        }

        // 深色模式按钮在抽屉内
        // 深色模式按钮在抽屉内
        TextView btnDarkMode = findViewById(R.id.btnDarkMode);
        boolean isDark = getSharedPreferences("prefs", MODE_PRIVATE)
                .getBoolean("dark_mode", false);
        btnDarkMode.setText(isDark ? R.string.light_mode : R.string.dark_mode);
        btnDarkMode.setOnClickListener(v -> {
            boolean nowDark = !getSharedPreferences("prefs", MODE_PRIVATE)
                    .getBoolean("dark_mode", false);
            getSharedPreferences("prefs", MODE_PRIVATE).edit()
                    .putBoolean("dark_mode", nowDark).apply();
            getSharedPreferences("prefs", MODE_PRIVATE).edit()
                    .putBoolean("keep_drawer_open", true).apply();
            AppCompatDelegate.setDefaultNightMode(
                    nowDark ? AppCompatDelegate.MODE_NIGHT_YES
                            : AppCompatDelegate.MODE_NIGHT_NO);
            recreate();
        });

        // 一打开就把常驻服务拉起来。
        // 这不是「优化」而是必需：ColorOS 拒绝为未运行的进程绑定通知监听服务，
        // 进程不活着，通知一条都收不到（详见 KeepAliveService 的注释）。
        KeepAliveService.start(this);

        // Android 13+ 需要用户授权才能显示常驻通知（不给授权服务仍能跑，但不显示）
        requestNotiPermissionIfNeeded();
        // 待分类记录只在主页提示，不发送系统催办通知。

        // 账单文件只通过导入入口或系统文件分享读取。

    }

    private void showStatistics(boolean show) {
        if (show && adapter.isMultiSelectMode()) exitMultiSelect();
        statisticsVisible = show;
        findViewById(R.id.btnLedger).setSelected(!show);
        findViewById(R.id.btnPie).setSelected(show);
        pageContent.showStatistics(show,
                tabBar.originFor(findViewById(show ? R.id.btnPie : R.id.btnLedger)), true);
        if (show) statisticsPanel.onShown();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("month_offset", monthOffset);
        outState.putBoolean("statistics_visible", statisticsVisible);
        statisticsPanel.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    /** 点某条记录 → 弹出编辑卡片修改金额/用途。 */
    private void openCategorize(Bill b) {
        showBillEditor(b);
    }

    /** 弹出编辑卡片，填充当前记录的数据。 */
    private void showBillEditor(Bill b) {
        if (adapter.isMultiSelectMode()) return;
        editingBill = b;
        etEditAmount.setText(String.format(Locale.CHINA, "%.2f", Math.abs(b.amount)));
        tvEditCategory.setText(b.uncategorized() ? "请选择" : b.category);
        editingCategoryBefore = b.category;
        editOverlay.setVisibility(View.VISIBLE);
        editOverlay.setAlpha(0f);
        editOverlay.findViewById(R.id.editCard).setTranslationY(100f);
        editOverlay.animate().alpha(1f).setDuration(200).start();
        editOverlay.findViewById(R.id.editCard).animate().translationY(0f).setDuration(200).start();
    }

    /** 隐藏编辑卡片。 */
    private void hideBillEditor() {
        editOverlay.setVisibility(View.GONE);
        editingBill = null;
    }

    /** 保存编辑修改。 */
    private void saveBillEdit() {
        if (editingBill == null) return;
        String amountStr = etEditAmount.getText().toString().trim();
        if (amountStr.isEmpty()) {
            Toast.makeText(this, "请输入金额", Toast.LENGTH_SHORT).show();
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(amountStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "金额格式不对", Toast.LENGTH_SHORT).show();
            return;
        }
        // 保持收支方向
        double finalAmount = editingBill.amount >= 0 ? amount : -amount;
        String cat = tvEditCategory.getText().toString();
        if ("请选择".equals(cat)) cat = "";
        db.updateAmount(editingBill.id, finalAmount);
        db.updateCategory(editingBill.id, cat);

        // 分类预算检查：新分类有设预算则检查是否超支
        double spent = db.monthTotals(Budget.monthStart())[1];
        Budget.checkAndAlert(this, spent);
        if (!cat.isEmpty()) {
            Budget.checkTagAndAlert(this, db, cat);
        }

        hideBillEditor();
        refresh();
        showSaveFeedback();
    }

    /** 显示分类选择弹窗。 */
    /** 弹出用途选择面板（与主界面一致的 Grid 风格，不跳出 AlertDialog）。 */
    private void showCategoryPicker() {
        if (editingBill == null) return;

        final ViewGroup pickerBox = editOverlay.findViewById(R.id.editCategoryGrid);
        boolean visible = pickerBox.getVisibility() == View.VISIBLE;
        if (visible) {
            // 已展开则收起
            pickerBox.animate().alpha(0f).setDuration(150).withEndAction(() ->
                pickerBox.setVisibility(View.GONE)
            ).start();
            return;
        }

        pickerBox.removeAllViews();
        pickerBox.setAlpha(0f);
        pickerBox.setVisibility(View.VISIBLE);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(0, dp(12), 0, 0);
        CategoryChips.fill(this, grid, editingBill.amount,
            editingBill.category, name -> {
                tvEditCategory.setText(name);
                pickerBox.animate().alpha(0f).setDuration(150).withEndAction(() ->
                    pickerBox.setVisibility(View.GONE)
                ).start();
            });
        pickerBox.addView(grid);
        pickerBox.animate().alpha(1f).setDuration(200).start();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    // ---------------- 手动记一笔 ----------------

    /** 打开「记一笔」卡片。每次都重置成干净的空白状态，避免带出上一次的输入。 */
    private void showAddCard() {
        if (adapter.isMultiSelectMode()) {
            exitMultiSelect();
        }
        addIsIncome = false;
        etAddAmount.setText("");
        tvAddCategory.setText(R.string.add_category_none);
        tvAddCategory.setTag(null);
        addOverlay.findViewById(R.id.addCategoryGrid).setVisibility(View.GONE);
        applyAddDirection();

        addOverlay.setVisibility(View.VISIBLE);
        // 必须把悬浮按钮藏掉。真机实测（一加 Ace 3，1080x2376）：
        // FAB 在 [852,2088]-[1020,2256]，正好压住卡片里「保存」按钮
        // [756,2085]-[972,2196] 的右半边，且 FAB 的绘制层级更高，
        // 于是每一次点「保存」都被 FAB 吃掉——表现为按了没反应、账记不上。
        // 同排的「取消」（x 到 726 为止）不重叠，所以它一直正常，
        // 这恰恰是最容易把人带偏的地方：一半按钮好用，一半"坏了"。
        findViewById(R.id.btnAdd).setVisibility(View.GONE);
        addOverlay.setAlpha(0f);
        addOverlay.findViewById(R.id.addCard).setTranslationY(100f);
        addOverlay.animate().alpha(1f).setDuration(200).start();
        addOverlay.findViewById(R.id.addCard).animate().translationY(0f).setDuration(200).start();

        // 刻意「不」自动聚焦金额框。
        //
        // 一开始写了 requestFocus()，结果在真机上（一加 Ace 3 / 1080x2376）键盘一弹出就把
        // 卡片下半截盖住：用途行和保存按钮全被挡住，点不到，看着像功能坏了。
        // 编辑卡片从来没这个问题，就因为它不抢焦点。
        // 用户要输金额会自己点一下，代价远小于按钮被键盘埋掉。
        // 卡片本身够高（约 1584px），键盘弹出后仍留足空间给用途网格。
    }

    private void hideAddCard() {
        addOverlay.setVisibility(View.GONE);
        // 卡片收起，把悬浮按钮放回来（见 showAddCard 里的说明）
        findViewById(R.id.btnAdd).setVisibility(View.VISIBLE);
        // 收起键盘，否则关了卡片输入法还杵在那儿
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(etAddAmount.getWindowToken(), 0);
        }
    }

    /**
     * 切换收支方向。
     *
     * 换方向时必须把已选的用途清掉：支出和收入是两套词表，
     * 「餐饮」在收入里根本不存在，留着会变成一个选不中也存不进去的幽灵值。
     */
    private void setAddDirection(boolean income) {
        if (addIsIncome != income) {
            tvAddCategory.setText(R.string.add_category_none);
            tvAddCategory.setTag(null);
            addOverlay.findViewById(R.id.addCategoryGrid).setVisibility(View.GONE);
        }
        addIsIncome = income;
        applyAddDirection();
    }

    /** 方向切换后的视觉状态：金额前缀 + 两个胶囊的选中态。 */
    private void applyAddDirection() {
        tvAddSign.setText(addIsIncome ? "+¥" : "-¥");
        tvAddSign.setTextColor(getColorCompat(addIsIncome ? R.color.income : R.color.expense));

        btnAddExpense.setBackgroundResource(addIsIncome ? R.drawable.bg_pill : R.drawable.bg_pill_sel);
        btnAddExpense.setAlpha(addIsIncome ? 0.6f : 1f);
        ((TextView) btnAddExpense).setTextColor(getColorCompat(addIsIncome ? R.color.text_secondary : R.color.accent));
        ((TextView) btnAddExpense).setTypeface(null, addIsIncome
                ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);

        btnAddIncome.setBackgroundResource(addIsIncome ? R.drawable.bg_pill_sel : R.drawable.bg_pill);
        btnAddIncome.setAlpha(addIsIncome ? 1f : 0.6f);
        ((TextView) btnAddIncome).setTextColor(getColorCompat(addIsIncome ? R.color.accent : R.color.text_secondary));
        ((TextView) btnAddIncome).setTypeface(null, addIsIncome
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private int getColorCompat(int resId) {
        return ContextCompat.getColor(this, resId);
    }

    /** 展开用途 Grid。词表随当前方向切换。 */
    private void showAddCategoryPicker() {
        final ViewGroup pickerBox = addOverlay.findViewById(R.id.addCategoryGrid);
        if (pickerBox.getVisibility() == View.VISIBLE) {
            pickerBox.animate().alpha(0f).setDuration(150).withEndAction(() ->
                    pickerBox.setVisibility(View.GONE)).start();
            return;
        }
        pickerBox.removeAllViews();
        pickerBox.setAlpha(0f);
        pickerBox.setVisibility(View.VISIBLE);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(0, dp(12), 0, 0);
        // 传 ±1 只是为了让 Category.forAmount 挑对词表，与真实金额无关
        String current = (String) tvAddCategory.getTag();
        CategoryChips.fill(this, grid, addIsIncome ? 1 : -1, current, name -> {
            tvAddCategory.setText(name);
            tvAddCategory.setTag(name);
            pickerBox.animate().alpha(0f).setDuration(150).withEndAction(() ->
                    pickerBox.setVisibility(View.GONE)).start();
        });
        pickerBox.addView(grid);
        pickerBox.animate().alpha(1f).setDuration(200).start();
    }

    /**
     * 保存手动记的这一笔。
     *
     * 与自动记账走同一张 bills 表、同一套金额符号约定（正=收入、负=支出），
     * 所以汇总、饼图、预算全都能直接用，不需要为新来源加任何分支。
     */
    private void saveAddedBill() {
        String amountStr = etAddAmount.getText().toString().trim();
        if (amountStr.isEmpty()) {
            Toast.makeText(this, R.string.add_no_amount, Toast.LENGTH_SHORT).show();
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(amountStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, R.string.add_bad_amount, Toast.LENGTH_SHORT).show();
            return;
        }
        if (amount <= 0) {
            Toast.makeText(this, R.string.add_zero_amount, Toast.LENGTH_SHORT).show();
            return;
        }

        double signed = addIsIncome ? amount : -amount;
        String cat = (String) tvAddCategory.getTag();
        if (cat == null) {
            cat = "";
        }

        // nkey 留空：手动记录没有通知标识，也不需要去重——
        // 用户连点两次保存，那就是他真想记两笔，拦下来反而更坏。
        long now = System.currentTimeMillis();
        long id = db.insert(signed, "手动", getString(R.string.add_bill), now, null);
        if (id <= 0) {
            Toast.makeText(this, "保存失败，请重试", Toast.LENGTH_SHORT).show();
            return;
        }
        db.updateCategory(id, cat);

        // 保存后保留预算预警，待分类记录在主页统一处理。
        if (signed < 0) {
            Budget.checkAndAlert(this, db.monthTotals(Budget.monthStart())[1]);
        }
        if (!cat.isEmpty()) {
            Budget.checkTagAndAlert(this, db, cat);
        }

        hideAddCard();
        refresh();
        showSaveFeedback();
    }

    private void showSaveFeedback() {
        View feedback = findViewById(R.id.saveFeedback);
        feedback.animate().cancel();
        feedback.setVisibility(View.VISIBLE);
        feedback.setAlpha(0f);
        feedback.setTranslationY(dp(10));
        feedback.animate().setStartDelay(0).alpha(1f).translationY(0).setDuration(180)
                .withEndAction(() -> feedback.animate().alpha(0f).setStartDelay(1400)
                        .setDuration(180).withEndAction(() -> feedback.setVisibility(View.GONE)).start()).start();
        feedback.announceForAccessibility(getString(R.string.save_complete));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 实时刷新的关键：服务一记上账就广播，这里立刻重查数据库
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(this, billsChanged,
                    new IntentFilter(ACTION_BILLS_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
        refresh();
        // 常驻服务是异步启动的，onResume 时它可能还没跑起来，
        // 于是首屏会误报「常驻未开」。稍后再刷一次（服务起来时会广播，这是双保险）。
        dotAlive.postDelayed(refreshAfterStart, 1500);
    }

    @Override
    protected void onPause() {
        pageContent.finishTransition();
        statisticsPanel.finishTransition();
        super.onPause();
        dotAlive.removeCallbacks(refreshAfterStart);
        if (receiverRegistered) {
            try {
                unregisterReceiver(billsChanged);
            } catch (Exception ignored) {
                // 已经注销了就无所谓
            }
            receiverRegistered = false;
        }
    }

    @Override
    protected void onDestroy() {
        db.close();
        super.onDestroy();
    }

    private void refresh() {
        long start = offsetMonthStart();

        // 查偏移月的账单
        List<Bill> bills = db.recentInMonth(start);
        adapter.setItems(bills);
        statisticsPanel.refresh(start, bills, monthOffset < 0);
        tvEmpty.setVisibility(bills.isEmpty() ? View.VISIBLE : View.GONE);
        tvListHint.setText(getString(R.string.bill_count, bills.size()));

        // 偏移月的汇总
        double[] t = db.monthTotals(start);
        tvExpense.setText(String.format(Locale.CHINA, "¥%,.2f", t[1]));
        tvIncome.setText(String.format(Locale.CHINA, "¥%,.2f", t[0]));
        double net = t[0] - t[1];
        tvNet.setText(String.format(Locale.CHINA, "%s¥%,.2f", net < 0 ? "−" : "", Math.abs(net)));

        // 更新时间标签和下页禁用
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.add(java.util.Calendar.MONTH, monthOffset);
        tvMonth.setText(String.format(Locale.CHINA, "%d年 %d月",
                c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1));
        // 不能翻到下个月（还没过完）
        btnNextMonth.setEnabled(monthOffset < 0);
        btnNextMonth.setAlpha(monthOffset < 0 ? 0.5f : 0.15f);

        refreshBudget(t[1]);

        boolean on = NotificationManagerCompat.getEnabledListenerPackages(this)
                .contains(getPackageName());
        ((TextView) findViewById(R.id.btnListener))
                .setText(on ? "通知：已开启" : "通知：未开启");

        // 待确认转账横幅：转账的金额还不知道，排在最上面
        int pending = db.countPending();
        if (pending > 0) {
            bannerTransfer.setVisibility(View.VISIBLE);
            bannerTransfer.setText(getString(R.string.pending_banner, pending));
        } else {
            bannerTransfer.setVisibility(View.GONE);
        }

        // 待分类横幅：有积压才出现，多少笔直接写在上面
        int uncat = db.countUncategorized();
        if (uncat > 0) {
            banner.setVisibility(View.VISIBLE);
            banner.setText(getString(R.string.uncategorized_banner, uncat));
        } else {
            banner.setVisibility(View.GONE);
        }

        ((TextView) findViewById(R.id.btnWechatReader)).setText(isWechatReaderEnabled()
                ? R.string.wechat_reader_enabled : R.string.wechat_reader_disabled);
        int receipts = WeChatReceiptStore.count(db);
        bannerReceipt.setVisibility(receipts > 0 ? View.VISIBLE : View.GONE);
        bannerReceipt.setText(getString(R.string.wechat_receipt_pending, receipts));
        refreshAlive(on);

        // 多选模式：如果列表已刷新（比如删除后），退出多选
        if (!adapter.isMultiSelectMode()) {
            multiBar.setVisibility(View.GONE);
        }
    }

    @Override
    public void onBackPressed() {
        // 卡片开着时，返回键先收卡片——直接退出应用会让人以为刚填的东西丢了
        if (addOverlay != null && addOverlay.getVisibility() == View.VISIBLE) {
            hideAddCard();
            return;
        }
        if (editOverlay != null && editOverlay.getVisibility() == View.VISIBLE) {
            hideBillEditor();
            return;
        }
        View drawer = findViewById(R.id.drawerOverlay);
        if (drawer.getVisibility() == View.VISIBLE) {
            closeDrawer(drawer, findViewById(R.id.drawerPanel));
            return;
        }
        if (adapter.isMultiSelectMode()) {
            exitMultiSelect();
            return;
        }
        if (statisticsVisible) {
            showStatistics(false);
            return;
        }
        super.onBackPressed();
    }

    /**
     * 预算行。没设预算就显示一行提示；设了就变成进度条。
     *
     * 颜色随进度变：白（安全）→ 黄（已达预警线）→ 红（超支）。
     * 这样「要不要悠着点」不需要读具体数字，扫一眼进度条颜色就知道。
     */
    private void refreshBudget(double spent) {
        double budget = Budget.amount(this);
        if (budget <= 0) {
            tvBudgetSet.setVisibility(View.VISIBLE);
            tvBudgetTitle.setText(R.string.budget_unset_title);
            tvBudgetRemaining.setText(R.string.budget_unset_value);
            tvBudgetTotal.setText(R.string.budget_unset_note);
            tvBudgetLine.setText(R.string.budget_no_limit);
            tvBudgetSub.setText("");
            tvBudgetLine.setTextColor(getColorCompat(R.color.hero_muted));
            tvBudgetRemaining.setTextColor(getColorCompat(R.color.hero_text));
            budgetRing.setProgress(0, getColorCompat(R.color.budget_ok));
            boxBudget.setContentDescription(getString(R.string.budget_disabled));
            return;
        }
        tvBudgetSet.setVisibility(View.GONE);
        double pct = spent * 100 / budget;
        boolean over = spent > budget;
        int th = Budget.threshold(this);
        boolean warn = !over && th > 0 && pct >= th;
        budgetRing.setProgress((float) (spent / budget), getColorCompat(
                over ? R.color.budget_over : warn ? R.color.budget_warn : R.color.budget_ok));
        tvBudgetTitle.setText(over ? R.string.budget_exceeded : R.string.budget_remaining);
        tvBudgetRemaining.setText(String.format(Locale.CHINA, "¥%,.2f", Math.abs(budget - spent)));
        tvBudgetRemaining.setTextColor(getColorCompat(over ? R.color.budget_over_text : R.color.hero_text));
        tvBudgetTotal.setText(getString(monthOffset == 0 ? R.string.budget_total : R.string.budget_reference, budget));
        tvBudgetLine.setText(getString(R.string.budget_used, pct));
        tvBudgetLine.setTextColor(getColorCompat(over ? R.color.budget_over_text : R.color.hero_muted));
        tvBudgetSub.setText(monthOffset != 0 ? getString(R.string.budget_history_note)
                : over ? getString(R.string.budget_adjust)
                : getString(R.string.budget_daily, (budget - spent) / Budget.daysLeft()));
        boxBudget.setContentDescription(getString(R.string.budget_a11y,
                tvBudgetTitle.getText(), tvBudgetRemaining.getText(), pct));
    }

    /** 设置月度预算与预警阈值。全屏 Activity，风格统一。 */
    private void showBudgetDialog() {
        startActivity(new Intent(this, BudgetActivity.class));
    }

    /**
     * 右划或确认对话框删除一条记录。
     *
     * 只删 bills 里这一行，raw_noti（原始通知）不动：那是排查用的证据，
     * 跟用户看到的账目是两回事。
     */
    private void confirmDelete(Bill b) {
        String sign = b.amount >= 0 ? "+" : "-";
        String title = b.title == null || b.title.isEmpty() ? b.channel : b.title;
        String msg = String.format(Locale.CHINA, "%s  %s¥%.2f", title, sign, Math.abs(b.amount));
        confirmTitle.setText("删除这条记录？");
        confirmMessage.setText(msg);
        confirmDetail.setVisibility(View.GONE);
        pendingDeleteAction = () -> doDelete(b);
        showConfirmCard();
    }

    /** 批量删除确认。 */
    private void confirmMultiDelete() {
        int n = adapter.getSelectedCount();
        if (n == 0) return;
        String msg = String.format(Locale.CHINA, "确定删除选中的 %d 笔记录？此操作不可撤销。", n);
        confirmTitle.setText("批量删除");
        confirmMessage.setText(msg);
        confirmDetail.setVisibility(View.GONE);
        pendingDeleteAction = () -> {
            List<Bill> list = adapter.getSelectedItems();
            int ok = 0;
            for (Bill b : list) {
                if (db.delete(b.id) > 0) ok++;
            }
            exitMultiSelect();
            Toast.makeText(this, "已删除 " + ok + " 笔", Toast.LENGTH_SHORT).show();
            refresh();
        };
        showConfirmCard();
    }

    /** 实际执行删除并刷新。 */
    private void doDelete(Bill b) {
        int n = db.delete(b.id);
        if (n > 0) {
            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "没删成功，这条可能已经不在了", Toast.LENGTH_SHORT).show();
        }
        refresh();
    }

    /** 进入多选模式。长按记录时触发。 */
    private void enterMultiSelect(Bill b) {
        adapter.setMultiSelectMode(true);
        adapter.toggleSelection(b.id);
    }

    /** 退出多选模式。 */
    private void exitMultiSelect() {
        adapter.setMultiSelectMode(false);
        multiBar.setVisibility(View.GONE);
        // 重置全选按钮文字
        ((TextView) btnMultiSelectAll).setText("全选");
        btnMultiSelectAll.setTag(null);
    }

    /** 全选当前列表中所有可见项。 */
    private void selectAllItems() {
        int n = adapter.selectAll();
        tvMultiCount.setText("已选 " + n + " 笔");
        TextView btn = (TextView) btnMultiSelectAll;
        if (n > 0 && n == adapter.getCount()) {
            btn.setText("取消全选");
            btn.setTag("all");
        }
    }

    // ---------------- 删除确认浮动卡片 ----------------

    /** 弹出底部确认卡片（带入场动画）。 */
    private void showConfirmCard() {
        confirmOverlay.setVisibility(View.VISIBLE);
        confirmOverlay.setAlpha(0f);
        confirmOverlay.findViewById(R.id.confirmCard).setTranslationY(100f);
        confirmOverlay.animate()
                .alpha(1f)
                .setDuration(200)
                .start();
        confirmOverlay.findViewById(R.id.confirmCard)
                .animate().translationY(0f).setDuration(300)
                .setInterpolator(new android.view.animation.OvershootInterpolator(0.7f))
                .start();
    }

    /** 收起底部确认卡片（带出场动画）。 */
    private void hideConfirmCard() {
        pendingDeleteAction = null;
        confirmOverlay.findViewById(R.id.confirmCard)
                .animate().translationY(100f).setDuration(180)
                .withEndAction(() -> {
                    confirmOverlay.setVisibility(View.GONE);
                    confirmOverlay.setAlpha(0f);
                })
                .start();
    }

    /** 切换侧边抽屉的打开/关闭。 */
    private void toggleDrawer(View overlay, View panel) {
        if (overlay.getVisibility() == View.VISIBLE) {
            closeDrawer(overlay, panel);
        } else {
            openDrawer(overlay, panel);
        }
    }

    /** 打开侧边抽屉：遮罩淡入，面板从左滑入。 */
        private void openDrawer(View overlay, View panel) {
        panel.animate().cancel();
        overlay.animate().cancel();
        panel.setTranslationY(dp(40));
        overlay.setAlpha(0f);
        overlay.setVisibility(View.VISIBLE);
        overlay.animate().alpha(1f).setDuration(150).start();
        panel.animate().translationY(0).setDuration(280)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }
    /** 关闭侧边抽屉：遮罩淡出，面板向左滑出。 */
        private void closeDrawer(View overlay, View panel) {
        overlay.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> overlay.setVisibility(View.GONE))
                .start();
        panel.animate().translationY(dp(40)).setDuration(200).start();
    }
    /**
     * 首页直接回答「监听服务现在到底还活着吗」——这是排查某笔没记上时最关键的证据。
     *
     * 只看「是否授权」是不够的：授权了也可能被系统杀掉。所以额外读最近一次心跳标记：
     * 心跳新鲜 → 服务在跑（那笔没记上是因为对方压根没发通知）；
     * 心跳很旧 → 服务被杀，那才是保活问题。
     */
    private void refreshAlive(boolean authorized) {
        if (!authorized) {
            dotAlive.setBackgroundResource(R.drawable.dot_off);
            tvAliveText.setText(R.string.listening_permission);
            findViewById(R.id.tvAlive).setContentDescription("监听服务未授权，通知收不到，点击设置");
            return;
        }
        long last = db.lastMarkTs();
        String beat;
        boolean ok = true;
        if (last <= 0) {
            beat = "等待首次心跳";
            ok = false;
        } else {
            long ageSec = (System.currentTimeMillis() - last) / 1000;
            String ago;
            if (ageSec < 60) {
                ago = ageSec + " 秒前";
            } else if (ageSec < 3600) {
                ago = (ageSec / 60) + " 分钟前";
            } else {
                ago = (ageSec / 3600) + " 小时前";
            }
            // 心跳间隔 10 分钟，超过 25 分钟没有任何心跳就当作可疑
            boolean alive = ageSec <= 25 * 60;
            ok = alive;
            beat = (alive ? "运行中" : "可能已被系统杀掉") + "　·　心跳 " + ago;
        }
        // 常驻服务是关键：ColorOS 只会给「活着的进程」绑定监听服务
        String keep = KeepAliveService.RUNNING ? "常驻已开" : "常驻未开，点此设自启动";
        dotAlive.setBackgroundResource(ok && KeepAliveService.RUNNING
                ? R.drawable.dot_ok : R.drawable.dot_off);
        tvAliveText.setText(ok && KeepAliveService.RUNNING ? R.string.listening_active : R.string.listening_attention);
        findViewById(R.id.tvAlive).setContentDescription("监听服务：" + beat + "，" + keep);
    }

    /**
     * Android 13+ 展示常驻通知需要用户授权。
     * 不授权常驻服务仍能运行，只是通知不可见——所以只请求，不强制。
     */
    private void requestNotiPermissionIfNeeded() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ContextCompat.checkSelfPermission(this,
                    "android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this,
                        new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
            }
        } catch (Throwable ignored) {
            // 请求失败不影响记账主流程
        }
        // 顺手也申请 READ_SMS，用于监听银行到账短信
        // Android 14+ 对 READ_SMS 限制严格，这里尽力而为，不强制
        requestSmsPermission();
    }

    private void requestSmsPermission() {
        try {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU) {
                // Android 14+ 只有默认短信 App 才能用 READ_SMS，不弹窗了
                return;
            }
            if (ContextCompat.checkSelfPermission(this,
                    "android.permission.READ_SMS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this,
                        new String[]{"android.permission.READ_SMS"}, 1002);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 打开系统的「自启动管理」。
     *
     * ColorOS 上这个开关决定了两件事：开机后能否自动拉起常驻服务、
     * 以及系统能否把通知监听服务绑上来。各厂商设置页组件名不同，
     * 所以逐个尝试，都不行就退回应用详情页让用户自己找。
     */
    private void openAutoStartSettings() {
        String[][] targets = {
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                {"com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"},
                {"com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerConsumptionActivity"},
        };
        for (String[] t : targets) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(t[0], t[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                Toast.makeText(this, "请在列表中允许「极简记账」自启动", Toast.LENGTH_LONG).show();
                return;
            } catch (Exception ignored) {
                // 这个机型没这个页，试下一个
            }
        }
        // 兜底：打开本应用的详情页，用户可在这里找权限/启动相关项
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
            Toast.makeText(this, "找不到自启动页，请在设置里手动允许自启动", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "请手动到 设置 → 应用 → 自启动管理", Toast.LENGTH_LONG).show();
        }
    }

    private static long monthStart() {
        return Budget.monthStart();
    }

    private boolean isWechatReaderEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName expected = new ComponentName(this, WeChatAccessibilityService.class);
        for (String value : enabled.split(":")) {
            if (expected.equals(ComponentName.unflattenFromString(value))) return true;
        }
        return false;
    }

    private void openWechatReaderSettings() {
        boolean local = WeChatAccessibilityService.hasScreenshotConsent(this);
        String status = getSharedPreferences(WeChatAccessibilityService.PREFS, MODE_PRIVATE)
                .getString(WeChatAccessibilityService.STATUS, "开启服务后，重新打开已完成的微信详情");
        LedgerSheet sheet = new LedgerSheet(this, getString(R.string.wechat_reader_service_name),
                (isWechatReaderEnabled() ? "辅助功能已开启" : "辅助功能未开启")
                        + "\n本地文字识别：" + (local ? "已开启" : "未开启") + "\n\n" + status
                        + "\n\n识别结果只在账本内待核对，不弹通知，不自动入账。");
        sheet.extra(local ? "停用本地文字识别" : "开启本地文字识别", () -> {
                    sheet.dismiss();
                    if (local) {
                        getSharedPreferences(WeChatAccessibilityService.PREFS, MODE_PRIVATE).edit()
                                .putBoolean(WeChatAccessibilityService.CONSENT, false).apply();
                    } else {
                        LedgerSheet consent = new LedgerSheet(this, "本地识别微信详情",
                                "微信未提供页面文字时，读取已完成红包或转账的窗口图像，"
                                        + "在手机本地识别金额和收付款状态。\n\n"
                                        + "截图只在内存处理，不保存、不上传。聊天和支付密码页面不在读取范围。"
                                        + "结果需回到账本核对后才能入账。可随时在这里关闭。");
                        consent.secondary("暂不开启", consent::dismiss)
                                .primary("同意并开启", () -> {
                                    getSharedPreferences(WeChatAccessibilityService.PREFS, MODE_PRIVATE).edit()
                                            .putBoolean(WeChatAccessibilityService.CONSENT, true).apply();
                                    consent.dismiss();
                                    Toast.makeText(this, "已开启，请重新打开已完成的微信详情", Toast.LENGTH_LONG).show();
                                }).show();
                    }
                });
        sheet.secondary("关闭", sheet::dismiss).primary("辅助功能设置", () -> {
                    sheet.dismiss();
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Exception error) {
                        Toast.makeText(this, "请在系统辅助功能中开启微信红包与转账读取", Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void openListenerSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "请手动到 设置 → 通知 → 通知使用权", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 申请把本应用加入电池优化白名单。
     *
     * 坑（实测）：ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 的定向申请页
     * 要求调用方持有 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限。没声明时，
     * ColorOS 的该页会启动后立即自己 finish()——**不抛异常**，
     * 所以旧版那种「catch 到异常就退回列表页」的兜底永远不会触发，
     * 用户看到的现象就是「点了没反应」。权限已在清单里声明。
     */
    private void requestBatteryExemption() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "已在白名单里 ✓", Toast.LENGTH_SHORT).show();
            return;
        }
        // 先试定向申请（体验最好：一个「允许」按钮）；
        // 意图在本机解析不到时才退回电池优化列表页。
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            if (i.resolveActivity(getPackageManager()) != null) {
                startActivity(i);
                return;
            }
        } catch (Exception ignored) {
            // 落到下一档
        }
        openBatteryList();
    }

    /** 兜底：直接打开电池优化列表页，让用户自己找到本应用设为「不优化」。 */
    private void openBatteryList() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            Toast.makeText(this, "请在列表里找到「极简记账」，选「不优化」",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "请手动到 设置 → 电池 → 电池优化", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 排查视图：把收到的原始通知原文原样列出来。
     *
     * 用途：通知文案会随 App 版本变。某笔没记上时，这里能看到微信到底发了什么，
     * 而不必去猜或翻系统日志。
     *
     * 带「复制」按钮是刻意的：把原文复制出来就能直接粘贴转发，
     * 比截图更省事，也不依赖对方能不能看图。
     */
    private void showRawNotifications() {
        List<String[]> rows = db.recentRaw(60);
        if (rows.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("原始通知（空）")
                    .setMessage("还没收到任何通知。\n\n"
                            + "如果刚付过款却没东西：\n"
                            + "1. 确认「通知使用权」已开启\n"
                            + "2. 确认微信的付款通知没有被关闭或静音\n"
                            + "3. 再付一笔 0.01 元后回来刷新")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }

        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);
        StringBuilder sb = new StringBuilder();
        for (String[] r : rows) {
            long ts = 0;
            try {
                ts = Long.parseLong(r[0]);
            } catch (NumberFormatException ignored) {
                // 时间戳坏了不影响看内容
            }
            sb.append(fmt.format(new Date(ts)))
                    .append("  [").append(shortPkg(r[1])).append("]\n");
            if (!TextUtils.isEmpty(r[2])) {
                sb.append("  标题: ").append(r[2]).append('\n');
            }
            if (!TextUtils.isEmpty(r[3])) {
                sb.append("  正文: ").append(r[3]).append('\n');
            }
            sb.append('\n');
        }
        final String all = sb.toString();

        new AlertDialog.Builder(this)
                .setTitle("原始通知（最近 " + rows.size() + " 条）")
                .setMessage(all)
                .setPositiveButton("关闭", null)
                .setNegativeButton("复制", (d, w) -> copyToClipboard("原始通知", all))
                .setNeutralButton("清空", (d, w) -> {
                    db.clearRaw();
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void copyToClipboard(String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                Toast.makeText(this, "复制失败：剪贴板不可用", Toast.LENGTH_SHORT).show();
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            Toast.makeText(this, "已复制全文", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    private static String shortPkg(String pkg) {
        if (pkg == null) {
            return "?";
        }
        if ("★".equals(pkg)) {
            return "★服务";
        }
        if ("com.tencent.mm".equals(pkg)) {
            return "微信";
        }
        if (pkg.startsWith("com.alipay") || "com.eg.android.AlipayGphone".equals(pkg)) {
            return "支付宝";
        }
        return pkg;
    }
}
