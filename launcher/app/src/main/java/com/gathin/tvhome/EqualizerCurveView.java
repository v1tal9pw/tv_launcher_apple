package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** Frosted-glass live frequency-response curve preview for the Equalizer settings screen. */
public final class EqualizerCurveView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path curvePath = new Path();
    private final Path fillPath = new Path();
    private final float density;

    private int[] freqs = new int[0];
    private short[] levels = new short[0];
    private short minMb = -1500;
    private short maxMb = 1500;
    private boolean eqEnabled;
    private int focusedBand = -1;

    public EqualizerCurveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        curvePaint.setStyle(Paint.Style.STROKE);
        curvePaint.setStrokeWidth(dp(2.4f));
        curvePaint.setStrokeCap(Paint.Cap.ROUND);
        curvePaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(dp(10));
    }

    void update(SystemEqualizer.Snapshot snapshot, int focusedBandIndex) {
        if (snapshot == null) {
            freqs = new int[0];
            levels = new short[0];
            eqEnabled = false;
        } else {
            freqs = snapshot.centerFreqsHz;
            levels = snapshot.bandLevelsMb;
            minMb = snapshot.minLevelMb;
            maxMb = snapshot.maxLevelMb;
            eqEnabled = snapshot.enabled;
        }
        focusedBand = focusedBandIndex;
        invalidate();
    }

    void setFocusedBand(int index) {
        if (focusedBand == index) return;
        focusedBand = index;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float padL = dp(18);
        float padR = dp(18);
        float padT = dp(18);
        float padB = dp(26);
        float plotW = w - padL - padR;
        float plotH = h - padT - padB;
        float midY = padT + plotH / 2f;

        // Subtle reference lines (+max, 0 dB, -min)
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0x18FFFFFF);
        canvas.drawLine(padL, padT + plotH * 0.18f, w - padR, padT + plotH * 0.18f, paint);
        canvas.drawLine(padL, padT + plotH * 0.82f, w - padR, padT + plotH * 0.82f, paint);
        paint.setColor(0x36FFFFFF);
        canvas.drawLine(padL, midY, w - padR, midY, paint);

        int count = Math.min(freqs.length, levels.length);
        if (count == 0) {
            paint.setStyle(Paint.Style.FILL);
            return;
        }

        float[] xs = new float[count];
        float[] ys = new float[count];
        float span = Math.max(1f, maxMb - minMb);
        for (int i = 0; i < count; i++) {
            xs[i] = count == 1 ? padL + plotW / 2f : padL + (plotW * i) / (count - 1);
            float norm = (levels[i] - minMb) / span; // 0 bottom .. 1 top
            ys[i] = padT + plotH * (1f - norm);
        }

        // Soft vertical bar indicators behind the curve
        paint.setStyle(Paint.Style.FILL);
        float barW = Math.min(dp(12), plotW / Math.max(count * 2.5f, 4f));
        for (int i = 0; i < count; i++) {
            boolean active = i == focusedBand;
            paint.setColor(eqEnabled
                    ? (active ? 0x9964D2FF : 0x380A84FF)
                    : (active ? 0x55FFFFFF : 0x22FFFFFF));
            float top = Math.min(midY, ys[i]);
            float bottom = Math.max(midY, ys[i]);
            if (bottom - top < dp(3)) {
                top = midY - dp(1.5f);
                bottom = midY + dp(1.5f);
            }
            canvas.drawRoundRect(xs[i] - barW / 2f, top, xs[i] + barW / 2f, bottom, barW / 2f, barW / 2f, paint);
        }

        // Smooth cubic curve
        curvePath.reset();
        curvePath.moveTo(xs[0], ys[0]);
        for (int i = 0; i < count - 1; i++) {
            float cx = (xs[i] + xs[i + 1]) / 2f;
            curvePath.cubicTo(cx, ys[i], cx, ys[i + 1], xs[i + 1], ys[i + 1]);
        }

        fillPath.reset();
        fillPath.addPath(curvePath);
        fillPath.lineTo(xs[count - 1], padT + plotH);
        fillPath.lineTo(xs[0], padT + plotH);
        fillPath.close();

        int topColor = eqEnabled ? 0x550A84FF : 0x24FFFFFF;
        fillPaint.setShader(new LinearGradient(0, padT, 0, padT + plotH, topColor, 0x000A84FF, Shader.TileMode.CLAMP));
        canvas.drawPath(fillPath, fillPaint);

        curvePaint.setColor(eqEnabled ? 0xFF64D2FF : 0x7AFFFFFF);
        canvas.drawPath(curvePath, curvePaint);

        // Band points & short frequency labels
        for (int i = 0; i < count; i++) {
            boolean active = i == focusedBand;
            paint.setColor(active ? 0xFFFFFFFF : (eqEnabled ? 0xFF64D2FF : 0x99FFFFFF));
            canvas.drawCircle(xs[i], ys[i], dp(active ? 4.5f : 3f), paint);

            textPaint.setColor(active ? 0xFFFFFFFF : 0x8CFFFFFF);
            canvas.drawText(SystemEqualizer.formatShortFrequency(freqs[i]), xs[i], h - dp(8), textPaint);
        }
    }

    private float dp(float value) {
        return value * density;
    }
}
