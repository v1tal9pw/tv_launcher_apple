package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * tvOS-style specular glass sheen for a focused tile: a subtle top-edge light rim plus a brief
 * diagonal reflection sweep that runs once when the tile gains focus.
 */
final class TileSheenDrawable extends Drawable {
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.4f);

    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private float sweepProgress = -1f;
    private ValueAnimator animator;
    private int lastW = -1, lastH = -1;

    TileSheenDrawable(float density) {
        this.density = density;
    }

    void playSweep(boolean animated) {
        cancel();
        if (!animated) {
            sweepProgress = -1f;
            invalidateSelf();
            return;
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(360);
        animator.setInterpolator(EASE);
        animator.addUpdateListener(a -> {
            sweepProgress = (float) a.getAnimatedValue();
            invalidateSelf();
        });
        animator.start();
    }

    void cancel() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        sweepProgress = -1f;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        int w = bounds.width();
        int h = bounds.height();
        if (w <= 0 || h <= 0) return;

        if (w != lastW || h != lastH) {
            lastW = w;
            lastH = h;
            // Subtle top-left glass specular glow that stays while focused.
            rimPaint.setShader(new LinearGradient(
                    0f, 0f, w * 0.25f, h * 0.55f,
                    new int[]{0x38FFFFFF, 0x10FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.35f, 1f},
                    Shader.TileMode.CLAMP));
        }
        canvas.drawRect(bounds, rimPaint);

        if (sweepProgress >= 0f && sweepProgress < 1f) {
            float fade = sweepProgress < 0.25f
                    ? sweepProgress / 0.25f
                    : (1f - sweepProgress) / 0.75f;
            int alpha = Math.round(0x46 * Math.max(0f, Math.min(1f, fade)));
            if (alpha > 1) {
                float travel = w + h * 0.8f;
                float center = -h * 0.4f + travel * sweepProgress;
                float band = Math.max(44f * density, w * 0.26f);
                sweepPaint.setShader(new LinearGradient(
                        center - band, 0f,
                        center + band, h * 0.65f,
                        new int[]{0x00FFFFFF, (alpha << 24) | 0xFFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.5f, 1f},
                        Shader.TileMode.CLAMP));
                canvas.drawRect(bounds, sweepPaint);
            }
        }
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(@Nullable ColorFilter colorFilter) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
