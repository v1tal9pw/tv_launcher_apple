package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.LongSparseArray;

/**
 * A capsule sampled from a pre-blurred backdrop via hardware BitmapShader (no clipPath stencil pass),
 * with a luminous glass rim and shared size-keyed gradients across tiles.
 */
final class QuickGlassDrawable extends Drawable {
    /** Shares LinearGradient shaders across same-sized tiles/circles inside one panel. */
    static final class ShaderCache {
        private final LongSparseArray<Shader[]> bySize = new LongSparseArray<>(4);

        Shader[] get(int width, int height) {
            long key = (((long) width) << 32) | (height & 0xFFFFFFFFL);
            Shader[] cached = bySize.get(key);
            if (cached != null) return cached;
            Shader[] created = new Shader[]{
                    new LinearGradient(0, 0, width, height,
                            new int[]{0x64D5DFF2, 0x3EAAB9D2, 0x589AAAC6}, null, Shader.TileMode.CLAMP),
                    new LinearGradient(0, 0, width, height,
                            new int[]{0xE6FFFFFF, 0x5EECF1FF, 0xB8CEDEFF}, null, Shader.TileMode.CLAMP),
                    new LinearGradient(0, 0, width, height,
                            new int[]{0xFFB9E0FF, 0xFF59AFFF, 0xFF238CFF}, null, Shader.TileMode.CLAMP)
            };
            bySize.put(key, created);
            return created;
        }
    }

    private Bitmap backdrop;
    private BitmapShader backdropShader;
    private final ShaderCache shaderCache;
    private final Matrix shaderMatrix = new Matrix();
    private final Rect source = new Rect();
    private final RectF shape = new RectF();
    private final RectF glowRect = new RectF();
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float unit;
    private int alpha = 255;
    private boolean focused;
    private float radius;

    QuickGlassDrawable(Bitmap backdrop, float unit) {
        this(backdrop, unit, null);
    }

    QuickGlassDrawable(Bitmap backdrop, float unit, ShaderCache shaderCache) {
        this.shaderCache = shaderCache;
        this.unit = unit;
        setBackdrop(backdrop);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(unit * .8f);
        focusRim.setStyle(Paint.Style.STROKE);
        focusRim.setStrokeWidth(unit);
        focusGlow.setStyle(Paint.Style.STROKE);
        focusGlow.setColor(0xFF0A84FF);
        light.setColor(0xFFFFFFFF);
    }

    void setBackdrop(Bitmap next) {
        Bitmap valid = next != null && !next.isRecycled() ? next : null;
        if (valid == backdrop) return;
        backdrop = valid;
        backdropShader = valid != null
                ? new BitmapShader(valid, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) : null;
        image.setShader(backdropShader);
        source.setEmpty();
        invalidateSelf();
    }

    void setRegion(Rect region, int homeWidth, int homeHeight) {
        if (backdrop == null || backdropShader == null || homeWidth <= 0 || homeHeight <= 0) return;
        int l = Math.max(0, region.left * backdrop.getWidth() / homeWidth);
        int t = Math.max(0, region.top * backdrop.getHeight() / homeHeight);
        int r = Math.min(backdrop.getWidth(), Math.max(l + 1, region.right * backdrop.getWidth() / homeWidth));
        int b = Math.min(backdrop.getHeight(), Math.max(t + 1, region.bottom * backdrop.getHeight() / homeHeight));
        if (source.left == l && source.top == t && source.right == r && source.bottom == b) return;
        source.set(l, t, r, b);
        updateShaderMatrix();
        invalidateSelf();
    }

    private void updateShaderMatrix() {
        if (backdropShader == null || source.isEmpty() || shape.isEmpty()) return;
        float sx = shape.width() / Math.max(1f, source.width());
        float sy = shape.height() / Math.max(1f, source.height());
        shaderMatrix.reset();
        shaderMatrix.setTranslate(-source.left, -source.top);
        shaderMatrix.postScale(sx, sy);
        shaderMatrix.postTranslate(shape.left, shape.top);
        backdropShader.setLocalMatrix(shaderMatrix);
    }

    @Override protected void onBoundsChange(Rect bounds) {
        shape.set(bounds);
        shape.inset(unit, unit);
        radius = Math.min(shape.height() / 2f, 22f * unit);
        updateShaderMatrix();
        int w = bounds.width(), h = bounds.height();
        if (shaderCache != null) {
            Shader[] shaders = shaderCache.get(w, h);
            fill.setShader(shaders[0]);
            rim.setShader(shaders[1]);
            focusRim.setShader(shaders[2]);
        } else {
            fill.setShader(new LinearGradient(0, 0, w, h,
                    new int[]{0x64D5DFF2, 0x3EAAB9D2, 0x589AAAC6}, null, Shader.TileMode.CLAMP));
            rim.setShader(new LinearGradient(0, 0, w, h,
                    new int[]{0xE6FFFFFF, 0x5EECF1FF, 0xB8CEDEFF}, null, Shader.TileMode.CLAMP));
            focusRim.setShader(new LinearGradient(0, 0, w, h,
                    new int[]{0xFFB9E0FF, 0xFF59AFFF, 0xFF238CFF}, null, Shader.TileMode.CLAMP));
        }
    }

    @Override public boolean isStateful() { return true; }
    @Override protected boolean onStateChange(int[] state) {
        boolean next = false;
        for (int value : state) if (value == android.R.attr.state_focused) next = true;
        if (next == focused) return false;
        focused = next;
        invalidateSelf();
        return true;
    }

    @Override public void draw(Canvas canvas) {
        if (backdropShader != null && !source.isEmpty()) {
            canvas.drawRoundRect(shape, radius, radius, image);
        }
        canvas.drawRoundRect(shape, radius, radius, fill);
        if (focused) {
            light.setAlpha(alpha * 32 / 255);
            canvas.drawRoundRect(shape, radius, radius, light);
            // Diffuse the blue strictly inward without expensive clipPath masks.
            drawInnerGlow(canvas, 3.0f * unit, 10);
            drawInnerGlow(canvas, 1.75f * unit, 16);
            drawInnerGlow(canvas, 1.0f * unit, 24);
        }
        Paint edge = focused ? focusRim : rim;
        edge.setAlpha(alpha);
        canvas.drawRoundRect(shape, radius, radius, edge);
    }

    private void drawInnerGlow(Canvas canvas, float strokeWidth, int strength) {
        float inset = strokeWidth * 0.5f;
        glowRect.set(shape);
        glowRect.inset(inset, inset);
        float innerRadius = Math.max(0f, radius - inset);
        focusGlow.setStrokeWidth(strokeWidth);
        focusGlow.setAlpha(alpha * strength / 255);
        canvas.drawRoundRect(glowRect, innerRadius, innerRadius, focusGlow);
    }

    @Override public void setAlpha(int alpha) {
        if (this.alpha == alpha) return;
        this.alpha = alpha;
        image.setAlpha(alpha);
        fill.setAlpha(alpha);
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter filter) {
        image.setColorFilter(filter);
        fill.setColorFilter(filter);
    }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
