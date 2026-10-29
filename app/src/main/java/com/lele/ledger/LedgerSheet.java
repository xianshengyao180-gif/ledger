package com.lele.ledger;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatButton;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/** Shared bottom panel using the same surfaces, spacing and actions as the home editor. */
final class LedgerSheet {
    private final Context context;
    private final Dialog dialog;
    private final LinearLayout root, content, actions;
    private boolean dismissing;

    LedgerSheet(Context context, String title, String note) {
        this.context = context;
        dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_sheet);
        root.setPadding(dp(24), dp(12), dp(24), dp(20));
        View handle = new View(context);
        handle.setBackgroundResource(R.drawable.bg_handle);
        LinearLayout.LayoutParams handleSize = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleSize.gravity = Gravity.CENTER_HORIZONTAL;
        handleSize.bottomMargin = dp(22);
        root.addView(handle, handleSize);
        TextView heading = text(context, title, 25, R.color.text_primary);
        heading.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        ViewCompat.setAccessibilityHeading(heading, true);
        root.addView(heading);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(12), 0, dp(14));
        if (note != null && !note.isEmpty()) {
            TextView description = text(context, note, 13, R.color.text_secondary);
            description.setLineSpacing(dp(3), 1);
            content.addView(description);
        }
        ScrollView scroll = new ScrollView(context) {
            @Override protected void onMeasure(int width, int height) {
                int cap = (int) (getResources().getDisplayMetrics().heightPixels * 0.54f);
                if (MeasureSpec.getMode(height) != MeasureSpec.UNSPECIFIED) cap = Math.min(cap, MeasureSpec.getSize(height));
                super.onMeasure(width, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
            }
        };
        scroll.setClipToPadding(false);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);
        root.addView(actions);
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(.45f);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.setNavigationBarColor(context.getColor(R.color.card));
            window.setStatusBarColor(context.getColor(R.color.bg));
            int flags = window.getDecorView().getSystemUiVisibility();
            int lightBars = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (context.getResources().getBoolean(R.bool.light_system_bars)) flags |= lightBars;
            else flags &= ~lightBars;
            window.getDecorView().setSystemUiVisibility(flags);
        }
        ViewCompat.setAccessibilityPaneTitle(root, title);
    }

    LedgerSheet content(View view) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = dp(16);
        content.addView(view, size);
        return this;
    }

    LedgerSheet secondary(String label, Runnable action) {
        actions.addView(button(label, false, action), new LinearLayout.LayoutParams(0, dp(52), 1));
        return this;
    }

    LedgerSheet primary(String label, Runnable action) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(52), 1);
        if (actions.getChildCount() > 0) size.leftMargin = dp(12);
        actions.addView(button(label, true, action), size);
        return this;
    }

    LedgerSheet extra(String label, Runnable action) {
        AppCompatButton button = button(label, false, action);
        button.setBackgroundColor(Color.TRANSPARENT);
        content(button);
        return this;
    }

    private AppCompatButton button(String label, boolean primary, Runnable action) {
        AppCompatButton button = new AppCompatButton(context);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setTextColor(context.getColor(primary ? R.color.button_text : R.color.text_primary));
        button.setBackgroundResource(primary ? R.drawable.bg_primary_button : R.drawable.bg_pill);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    void show() {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            boolean light = context.getResources().getBoolean(R.bool.light_system_bars);
            WindowInsetsControllerCompat bars = new WindowInsetsControllerCompat(window, window.getDecorView());
            bars.setAppearanceLightStatusBars(light);
            bars.setAppearanceLightNavigationBars(light);
        }
        if (ValueAnimator.areAnimatorsEnabled()) {
            root.setTranslationY(dp(32));
            root.setAlpha(0);
            root.animate().translationY(0).alpha(1).setDuration(240)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    void dismiss() {
        if (dismissing) return;
        dismissing = true;
        if (ValueAnimator.areAnimatorsEnabled()) {
            root.animate().translationY(dp(32)).alpha(0).setDuration(140)
                    .withEndAction(dialog::dismiss).start();
        } else dialog.dismiss();
    }

    static TextView text(Context context, String value, int size, int color) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(context.getColor(color));
        return text;
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
