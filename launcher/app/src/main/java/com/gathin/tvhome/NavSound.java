package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;

import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Unified low-latency navigation click synthesizer used across Home, Control Center,
 * Settings, and overlays so audio feedback works consistently on TV firmwares where
 * system sound effects are muted or queued behind focus rendering.
 */
final class NavSound {
    private static final long DEBOUNCE_MS = 25L;
    private static final ExecutorService AUDIO_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "TvHome-NavSound");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean PENDING_PLAY = new AtomicBoolean(false);
    private static AudioTrack clickTrack;
    private static volatile long lastPlayedAt;

    private NavSound() { }

    static void prepare() {
        if (clickTrack != null) return;
        AUDIO_EXECUTOR.execute(NavSound::prepareOnAudioThread);
    }

    private static synchronized void prepareOnAudioThread() {
        if (clickTrack != null) return;
        try {
            int sampleRate = 22050;
            int numSamples = sampleRate / 100; // 10 ms crisp click
            short[] buffer = new short[numSamples];
            for (int i = 0; i < numSamples; i++) {
                double t = (double) i / sampleRate;
                double decay = Math.exp(-t * 500.0);
                buffer[i] = (short) (Math.sin(2 * Math.PI * 900.0 * t) * decay * 14000);
            }
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(buffer.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            track.write(buffer, 0, buffer.length);
            clickTrack = track;
        } catch (Throwable ignored) {
        }
    }

    static void play(boolean enabled) {
        if (!enabled) return;
        long now;
        try { now = SystemClock.uptimeMillis(); }
        catch (Throwable ignored) { now = 0L; }
        if (now != 0L && now - lastPlayedAt < DEBOUNCE_MS) return;
        lastPlayedAt = now;
        if (PENDING_PLAY.compareAndSet(false, true)) {
            AUDIO_EXECUTOR.execute(NavSound::playOnAudioThread);
        }
    }

    private static synchronized void playOnAudioThread() {
        PENDING_PLAY.set(false);
        try {
            prepareOnAudioThread();
            if (clickTrack == null) return;
            clickTrack.stop();
            clickTrack.reloadStaticData();
            clickTrack.play();
        } catch (Throwable ignored) {
        }
    }

    static boolean isDpadDirection(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    static boolean isConfirmKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A;
    }

    /**
     * Dispatches a key event through an Activity and plays the synthetic click whenever
     * D-pad navigation moves focus or a confirm key activates a control.
     */
    static boolean dispatchActivityKey(Activity activity, KeyEvent event, boolean enabled,
                                       BooleanSupplier superDispatch) {
        if (!enabled || event.getAction() != KeyEvent.ACTION_DOWN) return superDispatch.getAsBoolean();
        int code = event.getKeyCode();
        if (isDpadDirection(code)) {
            View before = activity.getCurrentFocus();
            boolean handled = superDispatch.getAsBoolean();
            View after = activity.getCurrentFocus();
            if (after != null && after != before) play(true);
            return handled;
        }
        if (isConfirmKey(code) && event.getRepeatCount() == 0) {
            play(true);
        }
        return superDispatch.getAsBoolean();
    }

    /**
     * Wraps a dialog's Window.Callback so D-pad focus changes and OK presses inside
     * settings dialogs produce the same navigation click sound.
     */
    static void attachDialog(AlertDialog dialog, BooleanSupplier enabled) {
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window == null) return;
        Window.Callback original = window.getCallback();
        if (original == null || Proxy.isProxyClass(original.getClass())) return;
        Window.Callback wrapped = (Window.Callback) Proxy.newProxyInstance(
                Window.Callback.class.getClassLoader(),
                new Class<?>[]{Window.Callback.class},
                (proxy, method, args) -> {
                    if ("dispatchKeyEvent".equals(method.getName()) && args != null && args.length == 1
                            && args[0] instanceof KeyEvent) {
                        KeyEvent event = (KeyEvent) args[0];
                        boolean soundOn = enabled.getAsBoolean();
                        if (soundOn && event.getAction() == KeyEvent.ACTION_DOWN) {
                            int code = event.getKeyCode();
                            if (isDpadDirection(code)) {
                                View before = window.getCurrentFocus();
                                Object result = method.invoke(original, args);
                                View after = window.getCurrentFocus();
                                if (after != null && after != before) play(true);
                                return result;
                            }
                            if (isConfirmKey(code) && event.getRepeatCount() == 0) {
                                play(true);
                            }
                        }
                    }
                    return method.invoke(original, args);
                });
        window.setCallback(wrapped);
    }
}
