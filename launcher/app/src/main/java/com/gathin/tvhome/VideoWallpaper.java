package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.io.File;

/** Muted local video. No decoder or playback survives leaving Home. */
final class VideoWallpaper implements TextureView.SurfaceTextureListener {
    private final Context context;
    private final File file;
    private final TextureView texture;
    private final View dimmer;
    private MediaPlayer player;
    private Surface surface;
    private boolean active, failed;

    VideoWallpaper(Context context, FrameLayout root, File file, int dim) {
        this.context = context;
        this.file = file;
        texture = new TextureView(context);
        texture.setOpaque(false);
        texture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        texture.setSurfaceTextureListener(this);
        root.addView(texture, 1, new FrameLayout.LayoutParams(-1, -1));
        dimmer = new View(context);
        dimmer.setBackgroundColor((dim * 255 / 100) << 24);
        dimmer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(dimmer, 2, new FrameLayout.LayoutParams(-1, -1));
        dimmer.setVisibility(View.GONE);
    }

    void start() {
        active = true;
        if (!failed && texture.isAvailable()) prepare(texture.getSurfaceTexture());
    }

    void stop() {
        active = false;
        release();
        texture.setAlpha(0f);
        dimmer.setVisibility(View.GONE);
    }

    private void prepare(SurfaceTexture target) {
        if (!active || failed || player != null || target == null) return;
        texture.setAlpha(0f);
        try {
            surface = new Surface(target);
            MediaPlayer next = new MediaPlayer();
            player = next;
            next.setDataSource(file.getPath());
            next.setSurface(surface);
            next.setVolume(0f, 0f);
            next.setLooping(true);
            next.setOnPreparedListener(ready -> {
                if (!active || player != ready) return;
                crop(ready.getVideoWidth(), ready.getVideoHeight());
                ready.start();
            });
            next.setOnInfoListener((media, what, extra) -> {
                if (player == media && active && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    texture.setAlpha(1f);
                    dimmer.setVisibility(View.VISIBLE);
                }
                return false;
            });
            next.setOnErrorListener((media, what, extra) -> { fail(); return true; });
            next.prepareAsync();
        } catch (Exception unavailable) { fail(); }
    }

    private void fail() {
        failed = true;
        release();
        texture.setAlpha(0f);
        dimmer.setVisibility(View.GONE);
        Log.w("TvHome.Wallpaper", "Video wallpaper unavailable, showing its still image");
        Toast.makeText(context, R.string.personal_wallpaper_video_failed, Toast.LENGTH_LONG).show();
    }

    private void crop(int videoWidth, int videoHeight) {
        if (texture.getWidth() == 0 || texture.getHeight() == 0 || videoWidth <= 0 || videoHeight <= 0) return;
        float width = texture.getWidth(), height = texture.getHeight();
        float scale = Math.max(width / videoWidth, height / videoHeight);
        Matrix transform = new Matrix();
        transform.setScale(videoWidth * scale / width, videoHeight * scale / height, width / 2, height / 2);
        texture.setTransform(transform);
    }

    private void release() {
        MediaPlayer previous = player;
        player = null;
        if (previous != null) previous.release();
        if (surface != null) { surface.release(); surface = null; }
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) { prepare(surface); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { release(); return true; }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        // The Activity is landscape-only, but its TV window can still resize.
        if (player != null) try { crop(player.getVideoWidth(), player.getVideoHeight()); }
        catch (IllegalStateException unavailable) { fail(); }
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
}
