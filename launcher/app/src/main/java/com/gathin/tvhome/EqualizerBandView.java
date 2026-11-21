package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Remote-friendly horizontal glass slider row inside a SettingsGroup.
 * D-pad Left/Right adjusts live without leaving focus; OK resets to default (0 dB / 0%).
 */
public final class EqualizerBandView extends View {
    interface Listener {
        void onStep(int direction);
        void onReset();
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private String label = "";
    private String valueText = "";
    private int minValue = -1500;
    private int maxValue = 1500;
    private int currentValue;
    private boolean bipolar = true;
    private int accentColor = 0xFF0A84FF;
    private Listener listener;

    public EqualizerBandView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setFocusable(true);
        setClickable(true);
        setDefaultFocusHighlightEnabled(false);
        setBackgroundResource(R.drawable.settings_choice_bg);
        labelPaint.setTextSize(sp(16));
        labelPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        valuePaint.setTextSize(sp(15));
        valuePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        valuePaint.setTextAlign(Paint.Align.RIGHT);
    }

    void bind(String label, String valueText, int minValue, int maxValue, int currentValue,
              boolean bipolar, int accentColor, Listener listener) {
        this.label = label == null ? "" : label;
        this.valueText = valueText == null ? "" : valueText;
        this.minValue = minValue;
        this.maxValue = Math.max(minValue + 1, maxValue);
        this.currentValue = Math.max(minValue, Math.min(this.maxValue, currentValue));
        this.bipolar = bipolar;
        this.accentColor = accentColor;
        this.listener = listener;
        setContentDescription(this.label + ", " + this.valueText);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, Math.round(dp(52)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float cy = h / 2f;
        boolean focused = isFocused();
        boolean active = isEnabled();

        labelPaint.setColor(focused ? 0xFF141519 : (active ? 0xF2FFFFFF : 0x7AFFFFFF));
        canvas.drawText(label, dp(22), cy - (labelPaint.ascent() + labelPaint.descent()) / 2f, labelPaint);

        valuePaint.setColor(focused ? 0xFF141519 : (active ? 0xCCB7BBC6 : 0x66B7BBC6));
        String shown = focused && active ? "◀  " + valueText + "  ▶" : valueText;
        canvas.drawText(shown, w - dp(20), cy - (valuePaint.ascent() + valuePaint.descent()) / 2f, valuePaint);

        // Slider bar between the frequency label and value text, sized dynamically to avoid any overlap
        float trackLeft = Math.max(dp(140), dp(22) + labelPaint.measureText(label) + dp(22));
        float reserveRight = Math.max(dp(128), dp(20) + valuePaint.measureText("◀  +15 дБ  ▶") + dp(18));
        float trackRight = w - reserveRight;
        if (trackRight <= trackLeft + dp(40)) return;
        float trackH = dp(6);
        float trackTop = cy - trackH / 2f;
        float trackBottom = cy + trackH / 2f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(focused ? 0x26000000 : 0x28FFFFFF);
        canvas.drawRoundRect(trackLeft, trackTop, trackRight, trackBottom, trackH / 2f, trackH / 2f, paint);

        float fraction = (currentValue - minValue) / (float) (maxValue - minValue);
        float thumbX = trackLeft + fraction * (trackRight - trackLeft);

        float fillLeft;
        float fillRight;
        if (bipolar) {
            float zeroFraction = (0 - minValue) / (float) (maxValue - minValue);
            float zeroX = trackLeft + zeroFraction * (trackRight - trackLeft);
            // Center 0 dB marker
            paint.setColor(focused ? 0x55000000 : 0x66FFFFFF);
            canvas.drawRoundRect(zeroX - dp(1), cy - dp(7), zeroX + dp(1), cy + dp(7), dp(1), dp(1), paint);
            fillLeft = Math.min(zeroX, thumbX);
            fillRight = Math.max(zeroX, thumbX);
        } else {
            fillLeft = trackLeft;
            fillRight = thumbX;
        }

        if (fillRight > fillLeft + dp(1)) {
            paint.setColor(active ? accentColor : 0x55FFFFFF);
            canvas.drawRoundRect(fillLeft, trackTop, fillRight, trackBottom, trackH / 2f, trackH / 2f, paint);
        }

        paint.setColor(focused ? accentColor : (active ? 0xFFFFFFFF : 0x88FFFFFF));
        canvas.drawCircle(thumbX, cy, dp(focused ? 8.5f : 6.5f), paint);
        if (focused) {
            paint.setColor(0xFFFFFFFF);
            canvas.drawCircle(thumbX, cy, dp(3.5f), paint);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isEnabled() && listener != null) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                listener.onStep(-1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                listener.onStep(1);
                return true;
            }
            if (NavSound.isConfirmKey(keyCode) && event.getRepeatCount() == 0) {
                listener.onReset();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (isEnabled() && listener != null) {
            listener.onReset();
            return true;
        }
        return false;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
    }

    private float dp(float value) { return value * density; }
    private float sp(float value) { return value * getResources().getDisplayMetrics().scaledDensity; }
}
