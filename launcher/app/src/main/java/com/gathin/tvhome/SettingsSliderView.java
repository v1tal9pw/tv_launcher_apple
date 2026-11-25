package com.gathin.tvhome;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Apple TV-style horizontal glass slider row for percentage settings (0–100%, step 5%).
 * Matches {@link SettingsRowView} height and icon badge while allowing live D-pad Left/Right
 * adjustment and OK reset-to-default without opening a modal dialog.
 */
public final class SettingsSliderView extends View {
    interface Listener {
        void onValueChanged(int value);
    }

    static final int DEFAULT_MIN = 0;
    static final int DEFAULT_MAX = 100;
    static final int DEFAULT_STEP = 5;

    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();
    private final RectF fillRect = new RectF();
    private final float density;

    private CharSequence label = "";
    private Drawable icon;
    private int iconColor;
    private int min = DEFAULT_MIN;
    private int max = DEFAULT_MAX;
    private int step = DEFAULT_STEP;
    private int value = DEFAULT_MAX;
    private int defaultValue = DEFAULT_MAX;
    private int accentColor = 0xFF0A84FF;
    private boolean stepSoundEnabled;
    private Listener listener;

    public SettingsSliderView(Context context) {
        this(context, null);
    }

    public SettingsSliderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setFocusable(true);
        setClickable(true);
        setDefaultFocusHighlightEnabled(false);
        setBackgroundResource(R.drawable.settings_choice_bg);

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, new int[]{android.R.attr.text});
            try {
                CharSequence text = a.getText(0);
                if (text != null) label = text;
            } finally {
                a.recycle();
            }
        }

        labelPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        labelPaint.setTextSize(sp(15.5f));
        valuePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        valuePaint.setTextSize(sp(14.5f));
        updateAccessibility();
    }

    void setLabel(CharSequence text) {
        label = text == null ? "" : text;
        updateAccessibility();
        invalidate();
    }

    void setIcon(int resource, int color) {
        Drawable d = getContext().getDrawable(resource);
        icon = d == null ? null : d.mutate();
        iconColor = color;
        invalidate();
    }

    void setStepSoundEnabled(boolean enabled) {
        stepSoundEnabled = enabled;
    }

    void bind(int min, int max, int step, int value, int defaultValue, int accentColor, Listener listener) {
        this.min = min;
        this.max = Math.max(min + 1, max);
        this.step = Math.max(1, step);
        this.defaultValue = clamp(defaultValue, this.min, this.max);
        this.value = clamp(value, this.min, this.max);
        this.accentColor = accentColor;
        this.listener = listener;
        updateAccessibility();
        invalidate();
    }

    void setValue(int value) {
        int clamped = clamp(value, min, max);
        if (this.value == clamped) return;
        this.value = clamped;
        updateAccessibility();
        invalidate();
    }

    int getValue() {
        return value;
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Moves {@code current} by one {@code step} in {@code delta} direction (-1 or +1),
     * snapping legacy off-grid values onto the step grid and clamping to {@code [min, max]}.
     */
    static int stepValue(int current, int delta, int min, int max, int step) {
        int safeStep = Math.max(1, step);
        int clamped = clamp(current, min, max);
        if (delta == 0) return clamped;
        int offset = clamped - min;
        int next;
        if (delta > 0) {
            next = min + ((offset / safeStep) + 1) * safeStep;
        } else {
            int rem = offset % safeStep;
            next = rem != 0 ? clamped - rem : clamped - safeStep;
        }
        return clamp(next, min, max);
    }

    private String valueText() {
        return value + "%";
    }

    private void updateAccessibility() {
        String text = label.length() == 0 ? valueText() : label + ", " + valueText();
        setContentDescription(text);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int h = Math.round(dp(52));
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                resolveSize(h, heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        boolean focused = isFocused();
        boolean active = isEnabled();
        float cy = h / 2f;

        if (icon != null) {
            float iconX = rtl ? w - dp(46) : dp(18);
            iconBgPaint.setColor(iconColor);
            canvas.drawRoundRect(iconX, cy - dp(14), iconX + dp(28), cy + dp(14), dp(7), dp(7), iconBgPaint);
            icon.setBounds(Math.round(iconX + dp(5)), Math.round(cy - dp(9)),
                    Math.round(iconX + dp(23)), Math.round(cy + dp(9)));
            icon.draw(canvas);
        }

        String renderedValue = focused && active ? "◀  " + valueText() + "  ▶" : valueText();
        valuePaint.setColor(!active ? 0x7AFFFFFF : focused ? 0xFF141519 : 0xFFB7BBC6);
        valuePaint.setTextAlign(rtl ? Paint.Align.LEFT : Paint.Align.RIGHT);
        float valueBaseline = cy - (valuePaint.ascent() + valuePaint.descent()) / 2f;

        float maxValueWidth = valuePaint.measureText("◀  " + max + "%  ▶");
        float labelStart = icon != null ? dp(62) : dp(18);
        float valueOuter = dp(20);
        float gap = dp(12);
        float labelWidth = labelPaint.measureText(label, 0, label.length());

        float trackStartEdge;
        float trackEndEdge;
        float labelAvailable;
        if (!rtl) {
            trackEndEdge = w - valueOuter - maxValueWidth - gap;
            float idealStart = trackEndEdge - dp(136);
            float maxStart = trackEndEdge - dp(112);
            trackStartEdge = Math.min(maxStart, Math.max(idealStart, labelStart + labelWidth + gap));
            labelAvailable = Math.max(0f, trackStartEdge - labelStart - gap);
        } else {
            trackStartEdge = valueOuter + maxValueWidth + gap;
            float idealEnd = trackStartEdge + dp(136);
            float minEnd = trackStartEdge + dp(112);
            float labelRight = w - labelStart;
            trackEndEdge = Math.max(minEnd, Math.min(idealEnd, labelRight - labelWidth - gap));
            labelAvailable = Math.max(0f, labelRight - trackEndEdge - gap);
        }

        labelPaint.setColor(!active ? 0x7AFFFFFF : focused ? 0xFF141519 : 0xF2FFFFFF);
        labelPaint.setTextAlign(rtl ? Paint.Align.RIGHT : Paint.Align.LEFT);
        CharSequence ellipsized = TextUtils.ellipsize(label, labelPaint, labelAvailable, TextUtils.TruncateAt.END);
        float labelBaseline = cy - (labelPaint.ascent() + labelPaint.descent()) / 2f;
        canvas.drawText(ellipsized, 0, ellipsized.length(),
                rtl ? w - labelStart : labelStart, labelBaseline, labelPaint);

        if (trackEndEdge - trackStartEdge >= dp(40)) {
            float trackHeight = dp(6);
            float radius = trackHeight / 2f;
            trackRect.set(trackStartEdge, cy - radius, trackEndEdge, cy + radius);
            trackPaint.setColor(focused ? 0x26000000 : 0x28FFFFFF);
            canvas.drawRoundRect(trackRect, radius, radius, trackPaint);

            float frac = (value - min) / (float) (max - min);
            frac = Math.max(0f, Math.min(1f, frac));
            float thumbX = rtl
                    ? trackEndEdge - frac * (trackEndEdge - trackStartEdge)
                    : trackStartEdge + frac * (trackEndEdge - trackStartEdge);

            if (frac > 0f) {
                fillPaint.setColor(active ? accentColor : 0x55FFFFFF);
                if (rtl) fillRect.set(thumbX, cy - radius, trackEndEdge, cy + radius);
                else fillRect.set(trackStartEdge, cy - radius, thumbX, cy + radius);
                canvas.drawRoundRect(fillRect, radius, radius, fillPaint);
            }

            float thumbRadius = focused && active ? dp(8.5f) : dp(6.5f);
            thumbPaint.setColor(!active ? 0x80FFFFFF : focused ? accentColor : 0xFFFFFFFF);
            canvas.drawCircle(thumbX, cy, thumbRadius, thumbPaint);
            if (focused && active) {
                thumbPaint.setColor(0xFFFFFFFF);
                canvas.drawCircle(thumbX, cy, dp(3.5f), thumbPaint);
            }
        }

        canvas.drawText(renderedValue, rtl ? valueOuter : w - valueOuter, valueBaseline, valuePaint);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!isEnabled()) return super.onKeyDown(keyCode, event);
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int dir = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
            if (rtl) dir = -dir;
            stepBy(dir);
            return true;
        }
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A)
                && event.getRepeatCount() == 0) {
            resetToDefault();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean performClick() {
        super.performClick();
        if (isEnabled()) resetToDefault();
        return true;
    }

    private void stepBy(int delta) {
        int next = stepValue(value, delta, min, max, step);
        if (next == value) return;
        value = next;
        updateAccessibility();
        invalidate();
        if (stepSoundEnabled) NavSound.play(true);
        if (listener != null) listener.onValueChanged(value);
    }

    private void resetToDefault() {
        if (value == defaultValue) return;
        value = defaultValue;
        updateAccessibility();
        invalidate();
        if (listener != null) listener.onValueChanged(value);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, min, max, value));
    }

    private float dp(float v) { return v * density; }
    private float sp(float v) { return v * getResources().getDisplayMetrics().scaledDensity; }
}
