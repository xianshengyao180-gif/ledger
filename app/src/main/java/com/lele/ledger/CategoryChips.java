package com.lele.ledger;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 把「用途」铺成一格一格的方块。
 *
 * 应用内对话框和从通知点进来的分类页共用这一套，两边长得一样，
 * 用户不用重新学一次；改词表也只改一处。
 */
public final class CategoryChips {

    public interface OnPick {
        void onPick(String name);
    }

    private static final int PER_ROW = 3;

    private CategoryChips() {
    }

    public static void fill(Context ctx, LinearLayout box, double amount, OnPick cb) {
        fill(ctx, box, amount, null, cb);
    }

    /**
     * @param current 当前已选的用途；会加一圈描边。
     *                从主界面重新点进一条已分类的记录时，得让用户看出「现在是哪个」。
     */
    public static void fill(Context ctx, LinearLayout box, double amount,
                            String current, OnPick cb) {
        box.removeAllViews();
        String[] names = Category.forAmount(amount);
        LinearLayout row = null;
        for (int i = 0; i < names.length; i++) {
            if (i % PER_ROW == 0) {
                row = newRow(ctx, box);
            }
            row.addView(chip(ctx, names[i], names[i].equals(current), cb));
        }
        // 最后一行不满时补等宽空位，否则剩下两个按钮会被拉宽、跟上面几行对不齐
        if (row != null) {
            int rem = names.length % PER_ROW;
            if (rem != 0) {
                for (int k = 0; k < PER_ROW - rem; k++) {
                    row.addView(spacer(ctx));
                }
            }
        }
    }

    private static LinearLayout newRow(Context ctx, LinearLayout box) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 8);
        box.addView(row, lp);
        return row;
    }

    private static TextView chip(Context ctx, final String name, boolean selected, final OnPick cb) {
        TextView tv = new TextView(ctx);
        tv.setText(selected ? name + " ✓" : name);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(ctx, 11), 0, dp(ctx, 11));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(ctx, 14));
        bg.setColor(Category.colorOf(name));
        if (selected) {
            // 加一圈白描边：同色系里光靠文字很难一眼看出选中的是哪个
            bg.setStroke(dp(ctx, 2), 0xFFFFFFFF);
        }
        tv.setBackground(bg);

        tv.setOnClickListener(v -> cb.onPick(name));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(ctx, 4);
        lp.rightMargin = dp(ctx, 4);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static View spacer(Context ctx) {
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, 1, 1f);
        lp.leftMargin = dp(ctx, 4);
        lp.rightMargin = dp(ctx, 4);
        v.setLayoutParams(lp);
        return v;
    }

    private static int dp(Context ctx, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }
}
