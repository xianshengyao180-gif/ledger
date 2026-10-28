package com.lele.ledger;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.core.content.ContextCompat;

/** Displays budget usage without changing stored budget or expense totals. */
public final class BudgetRingView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private float progress;
    private float target = -1f;
    private int progressColor;
    private ValueAnimator animator;

    public BudgetRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        progressColor = ContextCompat.getColor(context, R.color.budget_ok);
    }

    public void setProgress(float value, int color) {
        float next = Math.max(0f, Math.min(1f, value));
        progressColor = color;
        if (Float.compare(target, next) == 0) { invalidate(); return; }
        if (animator != null) animator.cancel();
        boolean animate = target >= 0 && isAttachedToWindow() && ValueAnimator.areAnimatorsEnabled();
        target = next;
        if (!animate) { progress = next; invalidate(); return; }
        animator = ValueAnimator.ofFloat(progress, next);
        animator.setDuration(480);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(frame -> { progress = (float) frame.getAnimatedValue(); invalidate(); });
        animator.start();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float stroke = 7 * getResources().getDisplayMetrics().density;
        float radius = (Math.min(getWidth(), getHeight()) - stroke) / 2f;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
        paint.setStrokeWidth(stroke);
        paint.setColor(ContextCompat.getColor(getContext(), R.color.budget_track));
        canvas.drawOval(bounds, paint);
        if (progress > 0) {
            paint.setColor(progressColor);
            canvas.drawArc(bounds, -90, 360 * progress, false, paint);
        }
    }

    @Override protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }
}
