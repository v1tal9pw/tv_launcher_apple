package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/** Frosted-glass live frequency-response curve preview with Low / Mid / High zone indicators. */
public final class EqualizerCurveView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        zonePaint.setTextAlign(Paint.Align.CENTER);
        zonePaint.setTextSize(dp(9.5f));
        zonePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
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
        float padT = dp(24);
        float padB = dp(24);
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
            float norm = (levels[i] - minMb) / span;
            ys[i] = padT + plotH * (1f - norm);
        }

        // Top zone labels (НЧ / СЧ / ВЧ) centered over each zone's bands
        drawZoneLabels(canvas, xs, count, dp(14));

        // Vertical zone-colored bars behind the curve
        paint.setStyle(Paint.Style.FILL);
        float barW = Math.min(dp(12), plotW / Math.max(count * 2.5f, 4f));
        for (int i = 0; i < count; i++) {
            boolean active = i == focusedBand;
            int zoneColor = SystemEqualizer.zoneAccentColor(SystemEqualizer.bandZone(freqs[i]));
            paint.setColor(eqEnabled
                    ? withAlpha(zoneColor, active ? 0xB3 : 0x45)
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

        int topColor = eqEnabled ? 0x480A84FF : 0x20FFFFFF;
        fillPaint.setShader(new LinearGradient(0, padT, 0, padT + plotH, topColor, 0x000A84FF, Shader.TileMode.CLAMP));
        canvas.drawPath(fillPath, fillPaint);

        if (eqEnabled) {
            curvePaint.setShader(new LinearGradient(padL, 0, w - padR, 0,
                    new int[]{0xFF0A84FF, 0xFF30D158, 0xFF64D2FF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        } else {
            curvePaint.setShader(null);
            curvePaint.setColor(0x7AFFFFFF);
        }
        canvas.drawPath(curvePath, curvePaint);

        // Band points & short frequency labels
        for (int i = 0; i < count; i++) {
            boolean active = i == focusedBand;
            int zoneColor = SystemEqualizer.zoneAccentColor(SystemEqualizer.bandZone(freqs[i]));
            paint.setColor(active ? 0xFFFFFFFF : (eqEnabled ? zoneColor : 0x99FFFFFF));
            canvas.drawCircle(xs[i], ys[i], dp(active ? 4.5f : 3.2f), paint);

            textPaint.setColor(active ? 0xFFFFFFFF : 0x8CFFFFFF);
            canvas.drawText(SystemEqualizer.formatShortFrequency(freqs[i]), xs[i], h - dp(7), textPaint);
        }
    }

    private void drawZoneLabels(Canvas canvas, float[] xs, int count, float y) {
        int[] labels = {R.string.equalizer_zone_low_short, R.string.equalizer_zone_mid_short, R.string.equalizer_zone_high_short};
        int focusedZone = focusedBand >= 0 && focusedBand < count ? SystemEqualizer.bandZone(freqs[focusedBand]) : -1;
        for (int zone = SystemEqualizer.ZONE_LOW; zone <= SystemEqualizer.ZONE_HIGH; zone++) {
            float sumX = 0f;
            int n = 0;
            for (int i = 0; i < count; i++) {
                if (SystemEqualizer.bandZone(freqs[i]) == zone) {
                    sumX += xs[i];
                    n++;
                }
            }
            if (n == 0) continue;
            int color = SystemEqualizer.zoneAccentColor(zone);
            zonePaint.setColor(eqEnabled
                    ? (zone == focusedZone ? 0xFFFFFFFF : withAlpha(color, 0xCC))
                    : (zone == focusedZone ? 0xCCFFFFFF : 0x55FFFFFF));
            canvas.drawText(getContext().getString(labels[zone]), sumX / n, y, zonePaint);
        }
    }

    private static int withAlpha(int rgb, int alpha) {
        return (rgb & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private float dp(float value) {
        return value * density;
    }
}
