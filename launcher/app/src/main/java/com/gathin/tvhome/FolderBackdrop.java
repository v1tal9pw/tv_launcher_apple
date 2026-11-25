package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

/** Cached backdrop snapshots for quick settings and small menu/dialog surfaces. */
final class FolderBackdrop {
    static Bitmap capture(View home) {
        int width = 160, height = Math.max(1, width * home.getHeight() / Math.max(1, home.getWidth()));
        return capture(home, new android.graphics.Rect(0, 0, home.getWidth(), home.getHeight()), width, height, 5);
    }

    /** Downscales and blurs an existing bitmap off the UI thread for instant Control Center glass. */
    static Bitmap fromBitmap(Bitmap source, int width, int height, int radius) {
        if (source == null || source.isRecycled() || width <= 0 || height <= 0) return null;
        Bitmap small = Bitmap.createScaledBitmap(source, width, height, true);
        if (small == source) {
            small = small.copy(Bitmap.Config.ARGB_8888, true);
        } else if (small.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap argb = small.copy(Bitmap.Config.ARGB_8888, true);
            small.recycle();
            small = argb;
        }
        if (small == null) return null;
        return soften(small, radius);
    }

    /** Center-crops and blurs an artwork bitmap to match a full-screen centerCrop backdrop 1:1. */
    static Bitmap fromBitmapCenterCrop(Bitmap source, int width, int height, int radius) {
        if (source == null || source.isRecycled() || width <= 0 || height <= 0) return null;
        int sw = source.getWidth(), sh = source.getHeight();
        if (sw <= 0 || sh <= 0) return null;
        Bitmap cropped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(cropped);
        float scale = Math.max(width / (float) sw, height / (float) sh);
        float dx = (width - sw * scale) * 0.5f;
        float dy = (height - sh * scale) * 0.5f;
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(scale, scale);
        m.postTranslate(dx, dy);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(source, m, p);
        return soften(cropped, radius);
    }

    /** Capture only the material's footprint, so neighbouring app text cannot show through sharply. */
    static Bitmap capture(View home, android.graphics.Rect region, int width, int height, int radius) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(width / (float) region.width(), height / (float) region.height());
        canvas.translate(-region.left, -region.top);
        home.draw(canvas);
        return soften(bitmap, radius);
    }

    private static Bitmap soften(Bitmap bitmap, int radius) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] pixels = new int[width * height], scratch = new int[pixels.length];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int pass = 0; pass < 3; pass++) {
            blur(pixels, scratch, width, height, true, radius);
            blur(scratch, pixels, width, height, false, radius);
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private static void blur(int[] source, int[] target, int width, int height, boolean horizontal, int radius) {
        int count = radius * 2 + 1;
        int lines = horizontal ? height : width, length = horizontal ? width : height;
        int step = horizontal ? 1 : width;
        for (int line = 0; line < lines; line++) {
            int base = horizontal ? line * width : line;
            int red = 0, green = 0, blue = 0;
            for (int i = -radius; i <= radius; i++) {
                int pixel = source[base + Math.max(0, Math.min(length - 1, i)) * step];
                red += (pixel >> 16) & 255; green += (pixel >> 8) & 255; blue += pixel & 255;
            }
            for (int i = 0; i < length; i++) {
                target[base + i * step] = 0xFF000000 | (red / count) << 16 | (green / count) << 8 | blue / count;
                int removed = source[base + Math.max(0, i - radius) * step];
                int added = source[base + Math.min(length - 1, i + radius + 1) * step];
                red += ((added >> 16) & 255) - ((removed >> 16) & 255);
                green += ((added >> 8) & 255) - ((removed >> 8) & 255);
                blue += (added & 255) - (removed & 255);
            }
        }
    }
}
