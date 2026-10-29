package com.lele.ledger;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.widget.TextViewCompat;

/** Keeps tab feedback on the same animation clock as the page reveal. */
public final class AnimatedTabBar extends LinearLayout {
    private final Paint indicator = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int accent;
    private final int secondary;
    private TextView ledger;
    private TextView statistics;
    private float progress;
    private float touchX;
    private float touchY;
    private long touchTime;

    public AnimatedTabBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        accent = ContextCompat.getColor(context, R.color.accent);
        secondary = ContextCompat.getColor(context, R.color.text_secondary);
        indicator.setColor(ColorUtils.setAlphaComponent(accent, 26));
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        ledger = findViewById(R.id.btnLedger);
        statistics = findViewById(R.id.btnPie);
    }

    public void setProgress(float value) {
        progress = value;
        if (ledger != null && statistics != null) {
            tint(ledger, ColorUtils.blendARGB(accent, secondary, value));
            tint(statistics, ColorUtils.blendARGB(secondary, accent, value));
        }
        invalidate();
    }

    private void tint(TextView tab, int color) {
        tab.setTextColor(color);
        TextViewCompat.setCompoundDrawableTintList(tab, ColorStateList.valueOf(color));
    }

    public float[] originFor(View anchor) {
        int[] position = new int[2];
        anchor.getLocationOnScreen(position);
        boolean recentTouch = SystemClock.uptimeMillis() - touchTime < 800
                && touchX >= position[0] && touchX <= position[0] + anchor.getWidth()
                && touchY >= position[1] && touchY <= position[1] + anchor.getHeight();
        float[] origin = recentTouch ? new float[]{touchX, touchY}
                : new float[]{position[0] + anchor.getWidth() / 2f,
                        position[1] + anchor.getHeight() / 2f};
        touchTime = 0;
        return origin;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touchX = event.getRawX();
            touchY = event.getRawY();
            touchTime = SystemClock.uptimeMillis();
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (ledger == null || statistics == null) return;
        float density = getResources().getDisplayMetrics().density;
        float leftCenter = (ledger.getLeft() + ledger.getRight()) / 2f;
        float rightCenter = (statistics.getLeft() + statistics.getRight()) / 2f;
        float center = leftCenter + (rightCenter - leftCenter) * progress;
        float top = ledger.getTop() + 2 * density;
        canvas.drawRoundRect(center - 24 * density, top, center + 24 * density,
                top + 32 * density, 16 * density, 16 * density, indicator);
    }
}
