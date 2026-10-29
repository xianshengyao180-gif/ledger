package com.lele.ledger;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

/** Reveals statistics over the ledger without replacing either page's view state. */
public final class CircularPageLayout extends FrameLayout {
    public interface ProgressListener {
        void onProgress(float progress);
    }

    private final Path revealPath = new Path();
    private final int[] location = new int[2];
    private View ledger;
    private View statistics;
    private ValueAnimator animator;
    private ProgressListener progressListener;
    private float progress;
    private float originX;
    private float originY;
    private float radius;
    private boolean statisticsSelected;
    private boolean hasOrigin;

    public CircularPageLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        ledger = findViewById(R.id.lvBills);
        statistics = findViewById(R.id.statisticsPanel);
    }

    public void setProgressListener(ProgressListener listener) {
        progressListener = listener;
        setProgress(progress);
    }

    public void showStatistics(boolean show, float[] screenOrigin, boolean animate) {
        statisticsSelected = show;
        float target = show ? 1f : 0f;
        ledger.setImportantForAccessibility(show
                ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        statistics.setImportantForAccessibility(show
                ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if (!animate || !isLaidOut() || !isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) {
            finishTransition();
            return;
        }
        if (Float.compare(progress, target) == 0) {
            finishTransition();
            return;
        }
        cancelAnimator();
        if (!hasOrigin || progress == 0f) {
            getLocationOnScreen(location);
            originX = screenOrigin[0] - location[0];
            originY = screenOrigin[1] - location[1];
            hasOrigin = true;
            updateRadius();
        }
        ledger.setVisibility(VISIBLE);
        statistics.setVisibility(VISIBLE);
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(Math.max(120L, (long) (380 * Math.abs(target - progress))));
        animator.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        animator.addUpdateListener(frame -> setProgress((float) frame.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animation != animator) return;
                animator = null;
                settleVisibility();
            }
        });
        animator.start();
    }

    public void finishTransition() {
        cancelAnimator();
        setProgress(statisticsSelected ? 1f : 0f);
        settleVisibility();
    }

    private void cancelAnimator() {
        ValueAnimator previous = animator;
        animator = null;
        if (previous != null) previous.cancel();
    }

    private void setProgress(float value) {
        progress = value;
        if (progressListener != null) progressListener.onProgress(value);
        invalidate();
    }

    private void settleVisibility() {
        ledger.setVisibility(statisticsSelected ? GONE : VISIBLE);
        statistics.setVisibility(statisticsSelected ? VISIBLE : GONE);
        if (!statisticsSelected) hasOrigin = false;
    }

    private void updateRadius() {
        float farX = Math.max(Math.abs(originX), Math.abs(getWidth() - originX));
        float farY = Math.max(Math.abs(originY), Math.abs(getHeight() - originY));
        radius = (float) Math.hypot(farX, farY) + 1f;
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (child != statistics || progress >= 1f) return super.drawChild(canvas, child, drawingTime);
        if (progress <= 0f) return false;
        int checkpoint = canvas.save();
        revealPath.reset();
        revealPath.addCircle(originX, originY, radius * progress, Path.Direction.CW);
        canvas.clipPath(revealPath);
        boolean drawn = super.drawChild(canvas, child, drawingTime);
        canvas.restoreToCount(checkpoint);
        return drawn;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return animator != null || super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (animator != null) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (animator != null) finishTransition();
        updateRadius();
    }

    @Override
    protected void onDetachedFromWindow() {
        finishTransition();
        super.onDetachedFromWindow();
    }
}
