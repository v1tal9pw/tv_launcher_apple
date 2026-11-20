package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/** Builds lightweight tintable alpha masks for the focused app's ambient glow styles. */
final class AmbientGlow {
    static final int MASK_WIDTH = 240;
    static final int MASK_HEIGHT = 135;
    private static final float EDGE_BAND_FRACTION = 0.34f;
    private static final int EDGE_MAX_ALPHA = 156;

    private AmbientGlow() {}

    /**
     * Generates a white alpha mask for the requested glow style. Tinting the single cached
     * drawable with {@code 0xFF000000 | appColor} avoids per-focus redraws or texture uploads.
     */
    static Bitmap createMask(String style) {
        String normalized = Config.glowStyle(style);
        if (Config.GLOW_EDGE.equals(normalized)) return createEdgeMask(MASK_WIDTH, MASK_HEIGHT);
        int w = 480;
        int h = 270;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (Config.GLOW_HORIZON.equals(normalized)) {
            paint.setShader(new LinearGradient(
                    0f, 0f, 0f, h * 0.78f,
                    new int[]{0x6EFFFFFF, 0x36FFFFFF, 0x0EFFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.32f, 0.68f, 1f},
                    Shader.TileMode.CLAMP));
        } else {
            paint.setShader(new RadialGradient(
                    w * 0.32f, h * 0.42f, w * 0.62f,
                    new int[]{0x5CFFFFFF, 0x26FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, 0, w, h, paint);
        return bitmap;
    }

    private static Bitmap createEdgeMask(int w, int h) {
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int alpha = edgeAlpha(x, y, w, h);
                if (alpha > 0) pixels[row + x] = (alpha << 24) | 0x00FFFFFF;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
        return bitmap;
    }

    /**
     * Smooth Ambilight-style perimeter falloff in [0, 255]. Uses an aspect-matched band width
     * so top/bottom and left/right borders share the same physical thickness on 16:9 TVs, and
     * screen-blends horizontal and vertical edges so corners stay seamless without X-seams.
     */
    static int edgeAlpha(int x, int y, int w, int h) {
        if (w <= 1 || h <= 1) return 0;
        float band = Math.max(1f, Math.min(w, h) * EDGE_BAND_FRACTION);
        float dx = Math.min(x, w - 1 - x) / band;
        float dy = Math.min(y, h - 1 - y) / band;
        float fx = falloff(dx);
        float fy = falloff(dy);
        if (fx <= 0f && fy <= 0f) return 0;
        float combined = 1f - (1f - fx) * (1f - fy);
        int alpha = Math.round(EDGE_MAX_ALPHA * combined);
        return Math.max(0, Math.min(255, alpha));
    }

    private static float falloff(float normalizedDistance) {
        if (normalizedDistance >= 1f) return 0f;
        float inv = 1f - Math.max(0f, normalizedDistance);
        return (float) Math.pow(inv, 2.2);
    }
}
