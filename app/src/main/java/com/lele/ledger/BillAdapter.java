package com.lele.ledger;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 账单列表。每条左侧一个渠道徽标（圆点+首字），右侧金额。
 * 点击一条可以补选用途，长按可以删除——账本是用户的真实资产，记错了必须能改。
 */
public class BillAdapter extends BaseAdapter {

    /** 用户点了某条记录。 */
    public interface OnBillClick {
        void onClick(Bill bill);
    }

    /** 长按某条记录，触发多选模式。 */
    public interface OnBillLongClick {
        void onLongClick(Bill bill);
    }

    /** 用户右划或删除确认触发。 */
    public interface OnBillDelete {
        void onDelete(Bill bill);
    }

    /** 多选模式下选择变化时回调。 */
    public interface OnSelectionChanged {
        void onChange(int count);
    }

    private final Context ctx;
    private final List<Bill> items = new ArrayList<>();
    private final SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
    private OnBillClick click;
    private OnBillLongClick longClick;
    private OnBillDelete deleteListener;
    private OnSelectionChanged selListener;

    /** 多选模式：为 true 时 item 显示勾选框，防止误右划。 */
    private boolean multiSelectMode = false;
    /** 当前选中的 id 集合。 */
    private final Set<Long> selected = new HashSet<>();

    public BillAdapter(Context ctx) {
        this.ctx = ctx;
    }

    public void setOnBillClick(OnBillClick l) {
        this.click = l;
    }

    public void setOnBillLongClick(OnBillLongClick l) {
        this.longClick = l;
    }

    public void setOnBillDelete(OnBillDelete l) {
        this.deleteListener = l;
    }

    public void setOnSelectionChanged(OnSelectionChanged l) {
        this.selListener = l;
    }

    /** 进入/退出多选模式。退出时清空已选。 */
    public void setMultiSelectMode(boolean on) {
        if (multiSelectMode == on) return;
        multiSelectMode = on;
        if (!on) {
            selected.clear();
        }
        notifyDataSetChanged();
        if (selListener != null) {
            selListener.onChange(selected.size());
        }
    }

    public boolean isMultiSelectMode() {
        return multiSelectMode;
    }

    /** 切换某条记录的选中状态。选完为零时自动退出多选。 */
    public void toggleSelection(long id) {
        if (!multiSelectMode) return;
        if (selected.contains(id)) {
            selected.remove(id);
        } else {
            selected.add(id);
        }
        if (selected.isEmpty()) {
            setMultiSelectMode(false);
        } else {
            notifyDataSetChanged();
            if (selListener != null) {
                selListener.onChange(selected.size());
            }
        }
    }

    /** 获取当前选中的 Bill 列表（按原始顺序）。 */
    public List<Bill> getSelectedItems() {
        List<Bill> out = new ArrayList<>();
        for (Bill b : items) {
            if (selected.contains(b.id)) {
                out.add(b);
            }
        }
        return out;
    }

    public int getSelectedCount() {
        return selected.size();
    }

    /** 全选当前列表所有项。返回选中的数量。 */
    public int selectAll() {
        selected.clear();
        for (Bill b : items) {
            selected.add(b.id);
        }
        notifyDataSetChanged();
        if (selListener != null) {
            selListener.onChange(selected.size());
        }
        return selected.size();
    }

    /** 清空已选（不退出多选模式）。*/
    public void clearSelection() {
        selected.clear();
        if (selected.isEmpty()) {
            setMultiSelectMode(false);
        } else {
            notifyDataSetChanged();
            if (selListener != null) {
                selListener.onChange(0);
            }
        }
    }

    public void setItems(List<Bill> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).id;
    }

    @Override
    public View getView(int position, View convert, ViewGroup parent) {
        ViewHolder h;
        if (convert == null) {
            convert = LayoutInflater.from(ctx).inflate(R.layout.item_bill, parent, false);
            h = new ViewHolder(convert);
            convert.setTag(h);
        } else {
            h = (ViewHolder) convert.getTag();
        }

        Bill b = items.get(position);
        String ch = b.channel == null ? "" : b.channel;

        h.tvBadge.setImageResource(LedgerIcons.forCategory(b.category));
        h.tvBadge.setColorFilter(ContextCompat.getColor(ctx, R.color.accent));

        h.tvTitle.setText(b.title == null || b.title.isEmpty() ? ch : b.title);
        h.tvSub.setText(subText(b, ch));

        String sign = b.amount >= 0 ? "+" : "−";
        h.tvAmount.setText(String.format(Locale.CHINA, "%s%,.2f", sign, Math.abs(b.amount)));
        h.tvAmount.setTextColor(ContextCompat.getColor(ctx,
                b.amount >= 0 ? R.color.income : R.color.expense));

        // 多选模式：显示勾选框，点它切换选中；隐藏删除按钮
        if (multiSelectMode) {
            h.deleteBtn.setVisibility(View.GONE);
            h.cbCheck.setVisibility(View.VISIBLE);
            h.cbCheck.setChecked(selected.contains(b.id));
            h.cbCheck.setOnClickListener(v -> toggleSelection(b.id));
            // 点整行也切换选中
            convert.setOnClickListener(v -> toggleSelection(b.id));
            convert.setOnTouchListener(null); // 多选模式不响应滑动
        } else {
            h.cbCheck.setVisibility(View.GONE);
            h.deleteBtn.setVisibility(View.GONE);
            h.contentView.setTranslationX(0f);
            // 点击整条记录弹出选择用途卡片
            convert.setOnClickListener(v -> {
                if (click != null) click.onClick(b);
            });
            // 长按进入多选模式
            convert.setOnLongClickListener(v -> {
                if (longClick != null) longClick.onLongClick(b);
                return true;
            });
        }

        return convert;
    }

    // ---------------- 右划手势处理 ----------------

    /**
     * 真实跟手滑动 + 弹性吸附/收回。
     * 手指滑多少，内容走多少；松手超过阈值则吸附到露出删除按钮，否则弹性收回。
     */
    private class SwipeGestureHandler implements View.OnTouchListener {
        private static final int DELETE_WIDTH = 80;  // dp，删除按钮宽度
        private static final float ADHESION_RATIO = 0.35f; // 露出按钮的 35% 就吸附

        private final ViewHolder h;
        private final Bill bill;
        private final int deleteWidthPx;

        private float downX, downY;
        private float startTx;
        private int pointerId;
        private boolean swiping;
        private boolean consumed;
        private boolean longClickFired;
        private final Runnable longClickCheck = new Runnable() {
            @Override
            public void run() {
                if (!swiping && !consumed && !longClickFired) {
                    longClickFired = true;
                    if (longClick != null) {
                        longClick.onLongClick(bill);
                    }
                }
            }
        };
        private android.os.Handler handler;

        SwipeGestureHandler(ViewHolder h, Bill bill, int pos) {
            this.h = h;
            this.bill = bill;
            this.deleteWidthPx = (int) (DELETE_WIDTH * ctx.getResources().getDisplayMetrics().density + 0.5f);
            this.handler = new android.os.Handler(android.os.Looper.getMainLooper());
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    downX = event.getX();
                    downY = event.getY();
                    startTx = h.contentView.getTranslationX();
                    pointerId = event.getPointerId(0);
                    swiping = false;
                    consumed = false;
                    longClickFired = false;
                    // 500ms 后检测长按
                    handler.postDelayed(longClickCheck, 500);
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }

                case MotionEvent.ACTION_MOVE: {
                    int idx = event.findPointerIndex(pointerId);
                    if (idx < 0) return false;

                    float dx = event.getX(idx) - downX;
                    float dy = Math.abs(event.getY(idx) - downY);

                    if (!swiping) {
                        // 一旦手指移动超过 8px，取消长按检测
                        if (Math.abs(dx) > 8 || dy > 8) {
                            handler.removeCallbacks(longClickCheck);
                        }
                        // 还没确认是滑动：垂直移动太多就放弃
                        if (dy > 20 && dy > Math.abs(dx) * 2) {
                            v.getParent().requestDisallowInterceptTouchEvent(false);
                            return false;
                        }
                        if (Math.abs(dx) > 12) {
                            swiping = true;
                        } else {
                            return true; // 还在点击容忍范围内
                        }
                    }

                    // 跟手：content 随手指左移，范围 -deleteWidthPx ~ 0
                    float tx = Math.max(-deleteWidthPx, Math.min(0, startTx + dx));
                    h.contentView.setTranslationX(tx);

                    // 删除按钮可见度：根据露出比例淡入
                    float ratio = -tx / (float) deleteWidthPx;
                    h.deleteBtn.setAlpha(ratio);
                    h.deleteBtn.setVisibility(ratio > 0.01f ? View.VISIBLE : View.GONE);

                    consumed = true;
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    handler.removeCallbacks(longClickCheck);
                    v.getParent().requestDisallowInterceptTouchEvent(false);

                    if (!swiping && !longClickFired) {
                        // 没有滑动也没有长按——让 setOnClickListener 处理点击
                        return false;
                    }

                    float currentTx = h.contentView.getTranslationX();
                    boolean shouldAdhere = currentTx < -deleteWidthPx * ADHESION_RATIO;
                    float targetTx = shouldAdhere ? -deleteWidthPx : 0f;

                    // 弹性动画
                    animateTo(h, currentTx, targetTx, () -> {
                        if (shouldAdhere) {
                            h.deleteBtn.setVisibility(View.VISIBLE);
                            h.deleteBtn.setAlpha(1f);
                            // 点击删除按钮触发确认
                            h.deleteBtn.setOnClickListener(btn -> {
                                animateTo(h, h.contentView.getTranslationX(), 0f, () -> {
                                    h.deleteBtn.setVisibility(View.GONE);
                                });
                                if (deleteListener != null) {
                                    deleteListener.onDelete(bill);
                                }
                            });
                        } else {
                            h.deleteBtn.setVisibility(View.GONE);
                        }
                    });

                    swiping = false;
                    return true;
                }
            }
            return false;
        }
    }

    /** 带弹性效果的平移动画。 */
    private static void animateTo(ViewHolder h, float from, float to, Runnable end) {
        ValueAnimator anim = ValueAnimator.ofFloat(from, to);
        anim.setDuration(280);
        anim.setInterpolator(new android.view.animation.OvershootInterpolator(1.2f));
        anim.addUpdateListener(a -> h.contentView.setTranslationX((Float) a.getAnimatedValue()));
        if (end != null) {
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    end.run();
                }
            });
        }
        anim.start();
    }

    private static void animateSwipe(ViewHolder h, boolean reveal) {
        float target = reveal ? -200f : 0f;
        ValueAnimator anim = ValueAnimator.ofFloat(h.contentView.getTranslationX(), target);
        anim.setDuration(200);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> h.contentView.setTranslationX((Float) a.getAnimatedValue()));
        anim.start();
    }

    // ---------------- ViewHolder ----------------

    private static class ViewHolder {
        final View contentView;  // 滑动主体
        final View deleteBtn;    // 滑动露出的删除按钮
        final CheckBox cbCheck;  // 多选勾选框
        final ImageView tvBadge;
        final TextView tvTitle;
        final TextView tvSub;
        final TextView tvAmount;

        ViewHolder(View root) {
            contentView = root.findViewById(R.id.swipe_content);
            deleteBtn = root.findViewById(R.id.btn_delete);
            cbCheck = root.findViewById(R.id.cb_check);
            tvBadge = root.findViewById(R.id.tvBadge);
            tvTitle = root.findViewById(R.id.tvTitle);
            tvSub = root.findViewById(R.id.tvSub);
            tvAmount = root.findViewById(R.id.tvAmount);
        }
    }

    /** 副标题：渠道 · 用途 · 时间。没选用途时留个记号，提醒能补。 */
    private String subText(Bill b, String ch) {
        String cat = b.uncategorized() ? "未分类" : b.category;
        return ch + " · " + cat + " · " + fmt.format(new Date(b.ts));
    }

    private static int channelColor(String ch) {
        if ("微信".equals(ch)) {
            return R.color.badge_wechat;
        }
        if ("支付宝".equals(ch)) {
            return R.color.badge_alipay;
        }
        return R.color.badge_other;
    }
}
