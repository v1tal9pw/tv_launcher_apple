package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;

/** The folder covers Home's tiles with its live background, sharing the existing decoded picture. */
public final class HomeBackdropView extends View {
    private WallpaperPlayer wallpaper;
    public HomeBackdropView(Context context, AttributeSet attrs) { super(context, attrs); }
    void bind(WallpaperPlayer wallpaper) {
        this.wallpaper = wallpaper;
        wallpaper.setMirror(this); invalidate();
    }
    @Override public boolean isOpaque() { return true; }
    @Override protected void onDraw(Canvas canvas) {
        if (wallpaper == null) { canvas.drawColor(android.graphics.Color.BLACK); return; }
        wallpaper.drawBackground(canvas);
    }
}
