package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.text.TextUtils;

import java.util.List;

/**
 * Reads the currently active TV media session (Spotify, YouTube, Kinopoisk, etc.) via
 * {@link GuardRevival} when notification access is enabled, and dispatches basic transport controls.
 */
final class NowPlayingController {

    static final class Snapshot {
        final MediaController controller;
        final String packageName;
        final String appLabel;
        final String title;
        final String artist;
        final boolean playing;
        final Bitmap art;

        Snapshot(MediaController controller, String packageName, String appLabel,
                 String title, String artist, boolean playing, Bitmap art) {
            this.controller = controller;
            this.packageName = packageName;
            this.appLabel = appLabel;
            this.title = title;
            this.artist = artist;
            this.playing = playing;
            this.art = art;
        }
    }

    private NowPlayingController() { }

    static Snapshot current(Context context) {
        if (context == null) return null;
        try {
            MediaSessionManager manager = context.getSystemService(MediaSessionManager.class);
            if (manager == null) return null;
            List<MediaController> controllers = manager.getActiveSessions(
                    new ComponentName(context, GuardRevival.class));
            if (controllers == null || controllers.isEmpty()) return null;
            MediaController best = pickBest(controllers);
            if (best == null) return null;
            return buildSnapshot(context, best);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static MediaController pickBest(List<MediaController> controllers) {
        MediaController pausedFallback = null;
        for (MediaController c : controllers) {
            if (c == null) continue;
            PlaybackState state = c.getPlaybackState();
            int s = state == null ? PlaybackState.STATE_NONE : state.getState();
            if (isPlayingState(s)) return c;
            if (pausedFallback == null && isDisplayableState(s) && hasUsefulMetadata(c.getMetadata())) {
                pausedFallback = c;
            }
        }
        return pausedFallback;
    }

    static boolean isPlayingState(int state) {
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
    }

    static boolean isDisplayableState(int state) {
        return isPlayingState(state) || state == PlaybackState.STATE_PAUSED;
    }

    private static boolean hasUsefulMetadata(MediaMetadata meta) {
        if (meta == null) return false;
        return !TextUtils.isEmpty(meta.getString(MediaMetadata.METADATA_KEY_TITLE))
                || !TextUtils.isEmpty(meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
    }

    private static Snapshot buildSnapshot(Context context, MediaController controller) {
        MediaMetadata meta = controller.getMetadata();
        PlaybackState state = controller.getPlaybackState();
        boolean playing = state != null && isPlayingState(state.getState());
        String pkg = controller.getPackageName();
        String appLabel = resolveAppLabel(context, pkg);
        String title = meta == null ? null : firstNonEmpty(
                meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                meta.getString(MediaMetadata.METADATA_KEY_TITLE));
        String artist = meta == null ? null : firstNonEmpty(
                meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                meta.getString(MediaMetadata.METADATA_KEY_ARTIST),
                meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                meta.getString(MediaMetadata.METADATA_KEY_ALBUM));
        if (TextUtils.isEmpty(title)) {
            if (!playing) return null;
            title = !TextUtils.isEmpty(appLabel) ? appLabel : pkg;
        }
        if (TextUtils.isEmpty(artist) && !TextUtils.isEmpty(appLabel) && !appLabel.equals(title)) {
            artist = appLabel;
        }
        Bitmap rawArt = meta == null ? null : firstBitmap(
                meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
                meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART),
                meta.getBitmap(MediaMetadata.METADATA_KEY_ART));
        Bitmap art = scaleSquare(rawArt, 96);
        return new Snapshot(controller, pkg == null ? "" : pkg, appLabel,
                title == null ? "" : title.trim(),
                artist == null ? "" : artist.trim(),
                playing, art);
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    private static Bitmap firstBitmap(Bitmap... values) {
        for (Bitmap b : values) {
            if (b != null && !b.isRecycled() && b.getWidth() > 0 && b.getHeight() > 0) return b;
        }
        return null;
    }

    private static Bitmap scaleSquare(Bitmap src, int maxPx) {
        if (src == null || src.isRecycled()) return null;
        try {
            int w = src.getWidth(), h = src.getHeight();
            if (w <= maxPx && h <= maxPx) return src;
            float scale = Math.min((float) maxPx / w, (float) maxPx / h);
            int tw = Math.max(1, Math.round(w * scale));
            int th = Math.max(1, Math.round(h * scale));
            return Bitmap.createScaledBitmap(src, tw, th, true);
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        }
    }

    private static String resolveAppLabel(Context context, String pkg) {
        if (TextUtils.isEmpty(pkg)) return "";
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(info);
            return label == null ? "" : label.toString().trim();
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return "";
        }
    }

    static boolean togglePlayPause(Snapshot snapshot) {
        if (snapshot == null || snapshot.controller == null) return false;
        try {
            MediaController.TransportControls controls = snapshot.controller.getTransportControls();
            if (controls == null) return false;
            if (snapshot.playing) controls.pause();
            else controls.play();
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean skipNext(Snapshot snapshot) {
        if (snapshot == null || snapshot.controller == null) return false;
        try {
            MediaController.TransportControls controls = snapshot.controller.getTransportControls();
            if (controls == null) return false;
            controls.skipToNext();
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean skipPrevious(Snapshot snapshot) {
        if (snapshot == null || snapshot.controller == null) return false;
        try {
            MediaController.TransportControls controls = snapshot.controller.getTransportControls();
            if (controls == null) return false;
            controls.skipToPrevious();
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean openPlayer(Context context, Snapshot snapshot) {
        if (context == null || snapshot == null || TextUtils.isEmpty(snapshot.packageName)) return false;
        try {
            PackageManager pm = context.getPackageManager();
            Intent launch = pm.getLeanbackLaunchIntentForPackage(snapshot.packageName);
            if (launch == null) launch = pm.getLaunchIntentForPackage(snapshot.packageName);
            if (launch == null) return false;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
