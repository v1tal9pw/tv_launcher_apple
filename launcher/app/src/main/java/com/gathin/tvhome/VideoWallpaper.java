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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Muted local video. No decoder or playback survives leaving Home. */
final class VideoWallpaper implements TextureView.SurfaceTextureListener {
    private static final ExecutorService RELEASE_WORKER = Executors.newSingleThreadExecutor();
    private final Context context;
    private final File file;
    private final int dim;
    private int extraDim;
    private final Consumer<Boolean> onVisible;
    private final TextureView texture;
    private final View dimmer;
    private MediaPlayer player;
    private Surface surface;
    private boolean active, failed, releasing;

    VideoWallpaper(Context context, FrameLayout root, File file, int dim, Consumer<Boolean> onVisible) {
        this.context = context;
        this.file = file;
        this.dim = dim;
        this.onVisible = onVisible;
        texture = new TextureView(context);
        texture.setOpaque(true);
        texture.setVisibility(View.INVISIBLE);
        texture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        texture.setSurfaceTextureListener(this);
        root.addView(texture, 1, new FrameLayout.LayoutParams(-1, -1));
        dimmer = new View(context);
        dimmer.setBackgroundColor((dim * 255 / 100) << 24);
        dimmer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(dimmer, 2, new FrameLayout.LayoutParams(-1, -1));
        dimmer.setVisibility(View.GONE);
    }

    void setExtraDim(int extraPercent) {
        int clamped = Math.max(0, Math.min(85, dim + Math.max(0, extraPercent)));
        if (clamped == extraDim) return;
        extraDim = clamped;
        dimmer.setBackgroundColor((clamped * 255 / 100) << 24);
        if (texture.getVisibility() == View.VISIBLE) {
            dimmer.setVisibility(clamped > 0 ? View.VISIBLE : View.GONE);
        }
    }

    void start() {
        active = true;
        if (!failed && !releasing && texture.isAvailable()) prepare(texture.getSurfaceTexture());
    }

    void stop() {
        active = false;
        hideVideo();
        releaseAsync(null);
    }

    private void hideVideo() {
        texture.setVisibility(View.INVISIBLE);
        dimmer.setVisibility(View.GONE);
        if (onVisible != null) onVisible.accept(false);
    }

    private void prepare(SurfaceTexture target) {
        if (!active || failed || releasing || player != null || target == null) return;
        texture.setVisibility(View.INVISIBLE);
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
                    texture.setVisibility(View.VISIBLE);
                    if (dim > 0) dimmer.setVisibility(View.VISIBLE);
                    if (onVisible != null) onVisible.accept(true);
                }
                return false;
            });
            next.setOnErrorListener((media, what, extra) -> { fail(); return true; });
            next.prepareAsync();
        } catch (Exception unavailable) { fail(); }
    }

    private void fail() {
        failed = true;
        hideVideo();
        releaseAsync(null);
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

    private boolean releaseAsync(SurfaceTexture destroySurfaceTexture) {
        MediaPlayer previous = player;
        Surface previousSurface = surface;
        player = null;
        surface = null;
        if (previous == null && previousSurface == null) return false;
        releasing = true;
        RELEASE_WORKER.execute(() -> {
            try { if (previous != null) previous.release(); } catch (Exception ignored) { }
            try { if (previousSurface != null) previousSurface.release(); } catch (Exception ignored) { }
            try { if (destroySurfaceTexture != null) destroySurfaceTexture.release(); } catch (Exception ignored) { }
            texture.post(() -> {
                releasing = false;
                if (active && !failed && player == null && texture.isAvailable())
                    prepare(texture.getSurfaceTexture());
            });
        });
        return true;
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) { prepare(surface); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        hideVideo();
        return !releaseAsync(surface);
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        // The Activity is landscape-only, but its TV window can still resize.
        if (player != null) try { crop(player.getVideoWidth(), player.getVideoHeight()); }
        catch (IllegalStateException unavailable) { fail(); }
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
}
