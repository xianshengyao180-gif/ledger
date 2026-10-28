package com.lele.ledger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 用途占比圆环图。
 *
 * 自己画而不是引三方库：这应用一直坚持「零多余依赖、离线可用」，
 * 一个扇形图不值得为它拉进来几百 KB 的图表库，也省得被库的版本升级牵着走。
 */
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

    private final List<Slice> slices = new ArrayList<>();
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bigPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private double total;
    private String centerTop = "";
    private String centerBottom = "";
    private String summaryTop = "";
    private String summaryBottom = "";
    private String selectedLabel;

    public PieChartView(Context c) {
        this(c, null);
    }

    public PieChartView(Context c, AttributeSet a) {
        super(c, a);
        arcPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStyle(Paint.Style.STROKE);
        bigPaint.setTextAlign(Paint.Align.CENTER);
        bigPaint.setFakeBoldText(true);
        smallPaint.setTextAlign(Paint.Align.CENTER);
        refreshThemeColors();
    }

    public void setSlices(List<Slice> s, String top, String bottom) {
        slices.clear();
        if (s != null) {
            slices.addAll(s);
        }
        total = 0;
        for (Slice x : slices) {
            total += x.value;
        }
        centerTop = top == null ? "" : top;
        centerBottom = bottom == null ? "" : bottom;
        summaryTop = centerTop;
        summaryBottom = centerBottom;
        selectedLabel = null;
        setContentDescription(centerTop + " " + centerBottom);
        invalidate();
    }

    public void selectSlice(String label) {
        selectedLabel = label;
        centerTop = summaryTop;
        centerBottom = summaryBottom;
        for (Slice slice : slices) {
            if (slice.label.equals(label)) {
                centerTop = slice.label;
                centerBottom = String.format(java.util.Locale.CHINA, "¥%,.2f", slice.value);
                break;
            }
        }
        setContentDescription(centerTop + " " + centerBottom);
        announceForAccessibility(getContentDescription());
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int w = getWidth();
        int h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float stroke = dp(22);
        float size = Math.min(w, h) - dp(16);
        float r = size / 2f - stroke / 2f;
        rect.set(cx - r, cy - r, cx + r, cy + r);

        arcPaint.setStrokeWidth(stroke);
        trackPaint.setStrokeWidth(stroke);

        if (total <= 0) {
            cv.drawArc(rect, 0, 360, false, trackPaint);
            smallPaint.setTextSize(sp(12));
            cv.drawText("还没有数据", cx, cy + dp(4), smallPaint);
            return;
        }

        float start = -90f;
        for (Slice s : slices) {
            float sweep = (float) (360.0 * s.value / total);
            arcPaint.setColor(s.color);
            arcPaint.setAlpha(selectedLabel == null || s.label.equals(selectedLabel) ? 255 : 70);
            // 留 2° 缝，相邻两块不糊在一起；太窄的块不留缝，否则会细到看不见
            float draw = sweep >= 6f ? sweep - 2f : sweep;
            cv.drawArc(rect, start, draw, false, arcPaint);
            start += sweep;
        }

        smallPaint.setTextSize(sp(12));
        cv.drawText(centerTop, cx, cy - dp(6), smallPaint);
        bigPaint.setTextSize(sp(24));
        float available = Math.max(dp(60), r * 2 - stroke - dp(16));
        float measured = bigPaint.measureText(centerBottom);
        if (measured > available) bigPaint.setTextSize(bigPaint.getTextSize() * available / measured);
        cv.drawText(centerBottom, cx, cy + dp(18), bigPaint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }

    /**
     * 重新读取主题色并重绘。深色模式切换后由 Activity 调用。
     */
    public void refreshThemeColors() {
        int themeDivider = ContextCompat.getColor(getContext(), R.color.divider);
        int themePrimary = ContextCompat.getColor(getContext(), R.color.text_primary);
        int themeSecondary = ContextCompat.getColor(getContext(), R.color.text_secondary);

        trackPaint.setColor(themeDivider);
        bigPaint.setColor(themePrimary);
        smallPaint.setColor(themeSecondary);
        invalidate();
    }
}
