package com.lele.ledger;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Draws a category ring with a clockwise entrance and interruptible data transitions. */
public class PieChartView extends View {
    public static final class Slice {
        public final String label;
        public final double value;
        public final int color;

        public Slice(String label, double value, int color) {
            this.label = label;
            this.value = value;
            this.color = color;
        }
    }

    private static final class Arc {
        final String label;
        final int color;
        float start, sweep, alpha;

        Arc(String label, int color, float start, float sweep, float alpha) {
            this.label = label;
            this.color = color;
            this.start = start;
            this.sweep = sweep;
            this.alpha = alpha;
        }

        Arc copy() { return new Arc(label, color, start, sweep, alpha); }
    }

    private final List<Slice> slices = new ArrayList<>();
    private final List<Arc> arcs = new ArrayList<>();
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bigPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private ValueAnimator animator;
    private double total;
    private double displayedAmount;
    private float entranceProgress = 1f;
    private boolean entrancePending;
    private String centerTop = "";
    private String centerBottom = "";
    private String summaryTop = "";
    private String summaryBottom = "";
    private String selectedLabel;

    public PieChartView(Context context) { this(context, null); }

    public PieChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        arcPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStyle(Paint.Style.STROKE);
        bigPaint.setTextAlign(Paint.Align.CENTER);
        bigPaint.setFakeBoldText(true);
        smallPaint.setTextAlign(Paint.Align.CENTER);
        refreshThemeColors();
    }

    /** Returns false for an unchanged dataset so refreshes do not restart the animation. */
    public boolean setSlices(List<Slice> next, String top, String bottom) {
        String nextTop = top == null ? "" : top;
        String nextBottom = bottom == null ? "" : bottom;
        int count = next == null ? 0 : next.size();
        boolean same = count == slices.size() && summaryTop.equals(nextTop)
                && summaryBottom.equals(nextBottom);
        for (int index = 0; same && index < count; index++) {
            Slice before = slices.get(index), after = next.get(index);
            same = before.label.equals(after.label) && before.color == after.color
                    && Double.compare(before.value, after.value) == 0;
        }
        if (same) return false;

        slices.clear();
        if (next != null) slices.addAll(next);
        total = 0;
        for (Slice slice : slices) total += slice.value;
        summaryTop = nextTop;
        summaryBottom = nextBottom;
        selectedLabel = null;
        updateSemanticSummary();
        if (canAnimate()) {
            entrancePending = false;
            morphTo(targetArcs(), total, 640);
        } else {
            finishAnimation();
            entrancePending = total > 0;
        }
        return true;
    }

    public void animateEntranceIfNeeded() {
        if (!entrancePending || !isShown() || !isLaidOut()) return;
        entrancePending = false;
        finishAnimation();
        if (!canAnimate() || total <= 0) return;
        entranceProgress = 0f;
        displayedAmount = 0;
        animator = ValueAnimator.ofFloat(0f, 1f);
        updateCenter();
        animator.setDuration(760);
        animator.setInterpolator(new DecelerateInterpolator(1.2f));
        animator.addUpdateListener(frame -> {
            entranceProgress = (float) frame.getAnimatedValue();
            displayedAmount = total * entranceProgress;
            updateCenter();
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (animation != animator) return;
                animator = null;
                finishAnimation();
            }
        });
        animator.start();
    }

    public void selectSlice(String label) {
        selectedLabel = null;
        for (Slice slice : slices) {
            if (slice.label.equals(label)) { selectedLabel = label; break; }
        }
        entrancePending = false;
        updateSemanticSummary();
        if (canAnimate()) morphTo(targetArcs(), selectedAmount(), 260);
        else finishAnimation();
        announceForAccessibility(getContentDescription());
    }

    private boolean canAnimate() {
        return isAttachedToWindow() && isShown() && isLaidOut() && ValueAnimator.areAnimatorsEnabled();
    }

    private double selectedAmount() {
        for (Slice slice : slices) if (slice.label.equals(selectedLabel)) return slice.value;
        return total;
    }

    private void updateSemanticSummary() {
        centerTop = selectedLabel == null ? summaryTop : selectedLabel;
        String value = selectedLabel == null ? summaryBottom : money(selectedAmount());
        setContentDescription(centerTop + " " + value);
    }

    private void updateCenter() {
        centerTop = selectedLabel == null ? summaryTop : selectedLabel;
        centerBottom = animator == null && selectedLabel == null
                ? summaryBottom : money(displayedAmount);
    }

    private static String money(double amount) {
        return String.format(Locale.CHINA, "¥%,.2f", Math.max(0, amount));
    }

    private List<Arc> targetArcs() {
        List<Arc> result = new ArrayList<>();
        float start = -90f;
        for (Slice slice : slices) {
            float sweep = total > 0 ? (float) (360 * slice.value / total) : 0;
            float alpha = selectedLabel == null || slice.label.equals(selectedLabel) ? 1f : 0.28f;
            result.add(new Arc(slice.label, slice.color, start, sweep, alpha));
            start += sweep;
        }
        return result;
    }

    private List<Arc> visibleArcs() {
        List<Arc> result = new ArrayList<>();
        for (Arc arc : arcs) {
            Arc copy = arc.copy();
            if (entranceProgress < 1) {
                copy.sweep = Math.max(0, Math.min(copy.sweep,
                        entranceProgress * 360 - (copy.start + 90)));
            }
            if (copy.sweep > 0.01f && copy.alpha > 0.001f) result.add(copy);
        }
        return result;
    }

    private void morphTo(List<Arc> targets, double amount, long duration) {
        List<Arc> from = visibleArcs();
        double fromAmount = displayedAmount;
        cancelAnimator();
        entranceProgress = 1;
        Map<String, Arc> remaining = new LinkedHashMap<>();
        for (Arc target : targets) remaining.put(target.label, target.copy());
        List<Arc> to = new ArrayList<>();
        for (Arc before : from) {
            Arc after = remaining.remove(before.label);
            if (after == null) {
                after = new Arc(before.label, before.color,
                        before.start + before.sweep / 2, 0, 0);
            } else {
                while (after.start - before.start > 180) after.start -= 360;
                while (after.start - before.start < -180) after.start += 360;
            }
            to.add(after);
        }
        for (Arc after : remaining.values()) {
            from.add(new Arc(after.label, after.color, after.start + after.sweep / 2, 0, 0));
            to.add(after);
        }
        arcs.clear();
        for (Arc before : from) arcs.add(before.copy());
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(duration);
        animator.setInterpolator(new DecelerateInterpolator(1.2f));
        animator.addUpdateListener(frame -> {
            float progress = (float) frame.getAnimatedValue();
            for (int index = 0; index < arcs.size(); index++) {
                Arc before = from.get(index), after = to.get(index), current = arcs.get(index);
                current.start = before.start + (after.start - before.start) * progress;
                current.sweep = before.sweep + (after.sweep - before.sweep) * progress;
                current.alpha = before.alpha + (after.alpha - before.alpha) * progress;
            }
            displayedAmount = fromAmount + (amount - fromAmount) * progress;
            updateCenter();
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (animation != animator) return;
                animator = null;
                finishAnimation();
            }
        });
        animator.start();
    }

    private void cancelAnimator() {
        ValueAnimator previous = animator;
        animator = null;
        if (previous != null) previous.cancel();
    }

    public void finishAnimation() {
        cancelAnimator();
        entranceProgress = 1;
        displayedAmount = selectedAmount();
        arcs.clear();
        arcs.addAll(targetArcs());
        updateCenter();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float stroke = dp(22);
        float radius = (Math.min(getWidth(), getHeight()) - dp(16) - stroke) / 2;
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        arcPaint.setStrokeWidth(stroke);
        trackPaint.setStrokeWidth(stroke);
        canvas.drawArc(rect, 0, 360, false, trackPaint);
        if (total <= 0 && arcs.isEmpty()) {
            smallPaint.setTextSize(sp(12));
            canvas.drawText("还没有数据", cx, cy + dp(4), smallPaint);
            return;
        }
        for (Arc arc : arcs) {
            float sweep = arc.sweep;
            if (entranceProgress < 1) {
                sweep = Math.max(0, Math.min(sweep, entranceProgress * 360 - (arc.start + 90)));
            }
            if (sweep <= 0 || arc.alpha <= 0) continue;
            arcPaint.setColor(arc.color);
            arcPaint.setAlpha(Math.round(arc.alpha * 255));
            float draw = sweep >= 6 ? sweep - 2 : sweep;
            canvas.drawArc(rect, arc.start, draw, false, arcPaint);
        }
        smallPaint.setTextSize(sp(12));
        canvas.drawText(centerTop, cx, cy - dp(6), smallPaint);
        bigPaint.setTextSize(sp(24));
        float available = Math.max(dp(60), radius * 2 - stroke - dp(16));
        float measured = bigPaint.measureText(centerBottom);
        if (measured > available) bigPaint.setTextSize(bigPaint.getTextSize() * available / measured);
        canvas.drawText(centerBottom, cx, cy + dp(18), bigPaint);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private float sp(float value) { return value * getResources().getDisplayMetrics().scaledDensity; }

    public void refreshThemeColors() {
        trackPaint.setColor(ContextCompat.getColor(getContext(), R.color.divider));
        bigPaint.setColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        smallPaint.setColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (entrancePending && width > 0 && height > 0) post(this::animateEntranceIfNeeded);
    }

    @Override protected void onDetachedFromWindow() {
        finishAnimation();
        super.onDetachedFromWindow();
    }
}
