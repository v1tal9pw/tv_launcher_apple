package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;
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
 * Settings, and overlays so audio feedback stays in sync with TV focus animations.
 * Uses a pre-armed pool of native-rate low-latency static AudioTracks on an
 * URGENT_AUDIO thread so the hot path only calls {@link AudioTrack#play()}.
 */
final class NavSound {
    static final int DEFAULT_VOLUME = 60;
    private static final int DEFAULT_SAMPLE_RATE = 48000;
    private static final int POOL_SIZE = 3;
    private static final long DEBOUNCE_MS = 25L;
    private static final ExecutorService AUDIO_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            } catch (Throwable ignored) {
            }
            r.run();
        }, "TvHome-NavSound");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean PENDING_PLAY = new AtomicBoolean(false);
    private static volatile AudioTrack[] clickPool;
    private static int[] appliedVolumes;
    private static boolean[] needsReload;
    private static int nextIndex;
    private static volatile int volumePercent = DEFAULT_VOLUME;
    private static volatile long lastPlayedAt;

    private NavSound() { }

    static int clampVolume(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    static float gainForPercent(int percent) {
        return clampVolume(percent) / 100f;
    }

    static int chooseSampleRate(int nativeRate) {
        return nativeRate >= 8000 && nativeRate <= 192000 ? nativeRate : DEFAULT_SAMPLE_RATE;
    }

    /**
     * A {@link AudioTrack#MODE_STATIC} track starts in {@link AudioTrack#STATE_NO_STATIC_DATA}
     * right after build() and only transitions to {@link AudioTrack#STATE_INITIALIZED} after
     * {@link AudioTrack#write(short[], int, int)}.
     */
    static boolean isStaticStateBeforeWrite(int state) {
        return state != AudioTrack.STATE_UNINITIALIZED;
    }

    static boolean isStaticStateAfterWrite(int state) {
        return state == AudioTrack.STATE_INITIALIZED;
    }

    static short[] buildClickPcm(int sampleRate) {
        int rate = chooseSampleRate(sampleRate);
        int numSamples = Math.max(1, (rate * 8) / 1000); // 8 ms crisp transient click
        short[] buffer = new short[numSamples];
        for (int i = 0; i < numSamples; i++) {
            double t = (double) i / rate;
            double decay = Math.exp(-t * 580.0);
            buffer[i] = (short) Math.round(Math.sin(2.0 * Math.PI * 950.0 * t) * decay * 24000.0);
        }
        return buffer;
    }

    static int getVolume() {
        return volumePercent;
    }

    static void setVolume(int percent) {
        int clamped = clampVolume(percent);
        if (volumePercent == clamped) return;
        volumePercent = clamped;
        AUDIO_EXECUTOR.execute(() -> applyVolumeOnAudioThread(clamped));
    }

    static void prepare() {
        if (clickPool != null) return;
        AUDIO_EXECUTOR.execute(NavSound::prepareOnAudioThread);
    }

    private static synchronized void applyVolumeOnAudioThread(int percent) {
        AudioTrack[] pool = clickPool;
        if (pool == null) return;
        float gain = gainForPercent(percent);
        for (int i = 0; i < pool.length; i++) {
            if (appliedVolumes[i] == percent) continue;
            try {
                pool[i].setVolume(gain);
                appliedVolumes[i] = percent;
            } catch (Throwable ignored) {
            }
        }
    }

    private static AudioTrack createStaticTrack(int sampleRate, short[] buffer, float gain,
                                                boolean lowLatency) {
        AudioTrack track = null;
        try {
            AudioTrack.Builder builder = new AudioTrack.Builder()
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
                    .setTransferMode(AudioTrack.MODE_STATIC);
            if (lowLatency) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }
            track = builder.build();
            if (!isStaticStateBeforeWrite(track.getState())) {
                track.release();
                return null;
            }
            int written = track.write(buffer, 0, buffer.length);
            if (written <= 0 || !isStaticStateAfterWrite(track.getState())) {
                track.release();
                return null;
            }
            track.setVolume(gain);
            return track;
        } catch (Throwable ignored) {
            if (track != null) {
                try { track.release(); } catch (Throwable ignored2) { }
            }
            return null;
        }
    }

    private static synchronized void releasePoolOnAudioThread() {
        AudioTrack[] pool = clickPool;
        clickPool = null;
        appliedVolumes = null;
        needsReload = null;
        nextIndex = 0;
        if (pool == null) return;
        for (AudioTrack track : pool) {
            if (track == null) continue;
            try { track.stop(); } catch (Throwable ignored) { }
            try { track.release(); } catch (Throwable ignored) { }
        }
    }

    private static synchronized void prepareOnAudioThread() {
        if (clickPool != null) return;
        try {
            int nativeRate = 0;
            try {
                nativeRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_SYSTEM);
            } catch (Throwable ignored) {
            }
            int sampleRate = chooseSampleRate(nativeRate);
            short[] buffer = buildClickPcm(sampleRate);
            int vol = volumePercent;
            float gain = gainForPercent(vol);
            AudioTrack[] created = new AudioTrack[POOL_SIZE];
            int count = 0;
            boolean lowLatency = true;
            for (int i = 0; i < POOL_SIZE; i++) {
                AudioTrack track = createStaticTrack(sampleRate, buffer, gain, lowLatency);
                if (track == null && lowLatency) {
                    lowLatency = false;
                    track = createStaticTrack(sampleRate, buffer, gain, false);
                }
                if (track == null) break;
                created[count++] = track;
            }
            if (count == 0) return;
            AudioTrack[] pool = count == POOL_SIZE ? created : java.util.Arrays.copyOf(created, count);
            int[] volumes = new int[count];
            java.util.Arrays.fill(volumes, vol);
            appliedVolumes = volumes;
            needsReload = new boolean[count];
            nextIndex = 0;
            clickPool = pool;
        } catch (Throwable ignored) {
        }
    }

    private static boolean rearmTrack(AudioTrack track) {
        if (track.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) {
            track.stop();
        }
        return track.reloadStaticData() == AudioTrack.SUCCESS;
    }

    static void play(boolean enabled) {
        if (!enabled || volumePercent <= 0) return;
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
        int vol = volumePercent;
        if (vol <= 0) return;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                prepareOnAudioThread();
                AudioTrack[] pool = clickPool;
                if (pool == null || pool.length == 0) return;
                int index = nextIndex;
                AudioTrack track = pool[index];
                if (!isStaticStateAfterWrite(track.getState())) {
                    releasePoolOnAudioThread();
                    continue;
                }
                if (appliedVolumes[index] != vol) {
                    track.setVolume(gainForPercent(vol));
                    appliedVolumes[index] = vol;
                }
                if (needsReload[index]) {
                    if (!rearmTrack(track)) {
                        releasePoolOnAudioThread();
                        continue;
                    }
                    needsReload[index] = false;
                }
                track.play();
                needsReload[index] = true;
                int next = (index + 1) % pool.length;
                nextIndex = next;
                if (pool.length > 1 && needsReload[next]) {
                    try {
                        if (rearmTrack(pool[next])) {
                            needsReload[next] = false;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return;
            } catch (Throwable ignored) {
                releasePoolOnAudioThread();
            }
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
