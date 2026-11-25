package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.SoundPool;
import android.os.Process;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Unified low-latency navigation click synthesizer used across Home, Control Center,
 * Settings, and overlays so audio feedback stays in sync with TV focus animations.
 * Uses Android's native {@link SoundPool} (matching SystemUI's SoundEffectsHelper) with
 * automatic recovery on audio-route changes and a hardened {@link AudioTrack} fallback
 * while the sample loads.
 */
final class NavSound {
    static final int DEFAULT_VOLUME = 60;
    private static final int DEFAULT_SAMPLE_RATE = 48000;
    private static final int MAX_STREAMS = 6;
    private static final int FALLBACK_POOL_SIZE = 4;
    private static final int MAX_QUEUED_PLAYS = 2;
    private static final long DEBOUNCE_MS = 25L;
    private static final long MIN_REARM_AGE_MS = 180L;
    private static final long LOAD_TIMEOUT_MS = 3000L;
    private static final long DEVICE_CALLBACK_ARM_MS = 1200L;
    private static final long REFRESH_DEBOUNCE_MS = 600L;
    private static final String WAV_FILE_PREFIX = "tvhome_nav_click_";
    private static final String WAV_FILE_SUFFIX = "_v3.wav";

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

    private static final AtomicInteger PENDING_PLAYS = new AtomicInteger(0);
    private static volatile Context appContext;
    private static volatile boolean deviceCallbackRegistered;
    private static volatile long deviceCallbackRegisteredAt;

    private static volatile SoundPool soundPool;
    private static volatile int loadedSoundId;
    private static volatile boolean soundPoolReady;
    private static long soundPoolLoadStartedAt;
    private static long lastRefreshAt;

    private static volatile AudioTrack[] fallbackPool;
    private static boolean[] fallbackNeedsReload;
    private static long[] fallbackPlayedAt;
    private static int fallbackNextIndex;

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

    /**
     * Builds a 32 ms native-rate PCM buffer containing a crisp 18 ms Apple-style transient
     * click followed by a silent tail so TV AudioFlinger mixers (10-20 ms periods) and waking
     * DAC/DSP pipelines never drop or clip the sound.
     */
    static short[] buildClickPcm(int sampleRate) {
        int rate = chooseSampleRate(sampleRate);
        int totalSamples = Math.max(1, (rate * 32) / 1000);
        int activeSamples = Math.min(totalSamples, Math.max(1, (rate * 18) / 1000));
        double attackSec = 0.0005;
        double activeSec = (double) activeSamples / rate;
        double fadeOutStart = Math.max(attackSec, activeSec - 0.002);
        short[] buffer = new short[totalSamples];
        for (int i = 0; i < activeSamples; i++) {
            double t = (double) i / rate;
            double env = Math.exp(-t * 280.0);
            if (t < attackSec) {
                env *= 0.5 * (1.0 - Math.cos(Math.PI * t / attackSec));
            } else if (t > fadeOutStart) {
                double frac = Math.min(1.0, (t - fadeOutStart) / (activeSec - fadeOutStart));
                env *= 0.5 * (1.0 + Math.cos(Math.PI * frac));
            }
            double wave = Math.sin(2.0 * Math.PI * 950.0 * t)
                    + 0.18 * Math.sin(2.0 * Math.PI * 1900.0 * t);
            long sample = Math.round(wave * env * 22500.0);
            if (sample > Short.MAX_VALUE) sample = Short.MAX_VALUE;
            else if (sample < Short.MIN_VALUE) sample = Short.MIN_VALUE;
            buffer[i] = (short) sample;
        }
        return buffer;
    }

    static byte[] buildWavBytes(int sampleRate, short[] pcm) {
        int rate = chooseSampleRate(sampleRate);
        int dataSize = pcm.length * 2;
        int riffSize = 36 + dataSize;
        int byteRate = rate * 2;
        byte[] wav = new byte[44 + dataSize];
        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        putIntLE(wav, 4, riffSize);
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';
        wav[12] = 'f'; wav[13] = 'm'; wav[14] = 't'; wav[15] = ' ';
        putIntLE(wav, 16, 16);        // PCM chunk size
        putShortLE(wav, 20, 1);       // PCM format
        putShortLE(wav, 22, 1);       // mono
        putIntLE(wav, 24, rate);      // sample rate
        putIntLE(wav, 28, byteRate);  // byte rate
        putShortLE(wav, 32, 2);       // block align
        putShortLE(wav, 34, 16);      // bits per sample
        wav[36] = 'd'; wav[37] = 'a'; wav[38] = 't'; wav[39] = 'a';
        putIntLE(wav, 40, dataSize);
        for (int i = 0; i < pcm.length; i++) {
            putShortLE(wav, 44 + i * 2, pcm[i]);
        }
        return wav;
    }

    private static void putIntLE(byte[] out, int offset, int value) {
        out[offset] = (byte) (value & 0xFF);
        out[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        out[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        out[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static void putShortLE(byte[] out, int offset, int value) {
        out[offset] = (byte) (value & 0xFF);
        out[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }

    static boolean focusOrSelectionChanged(Object beforeFocus, int beforePos,
                                           Object afterFocus, int afterPos) {
        if (afterFocus == null) return false;
        if (afterFocus != beforeFocus) return true;
        return afterPos != AdapterView.INVALID_POSITION && afterPos != beforePos;
    }

    private static int selectedPosition(View view) {
        if (view instanceof AdapterView<?>) {
            return ((AdapterView<?>) view).getSelectedItemPosition();
        }
        return AdapterView.INVALID_POSITION;
    }

    private static long nowMs() {
        try { return SystemClock.uptimeMillis(); }
        catch (Throwable ignored) { return 0L; }
    }

    static int getVolume() {
        return volumePercent;
    }

    static void setVolume(int percent) {
        volumePercent = clampVolume(percent);
    }

    static void prepare() {
        prepare(appContext);
    }

    static void prepare(Context context) {
        captureContext(context);
        if (soundPool != null && soundPoolReady && loadedSoundId != 0) return;
        AUDIO_EXECUTOR.execute(NavSound::prepareSoundPoolOnAudioThread);
    }

    /**
     * Rebuilds the audio engine after returning from another app, TV standby, or an audio
     * output route switch (HDMI/eARC/Bluetooth/FlexConnect) so stale AudioFlinger tracks
     * never silence navigation clicks.
     */
    static void refresh(Context context) {
        captureContext(context);
        AUDIO_EXECUTOR.execute(NavSound::refreshOnAudioThread);
    }

    private static void captureContext(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (app != null) appContext = app;
        registerAudioDeviceCallbackIfNeeded(app != null ? app : context);
    }

    private static void registerAudioDeviceCallbackIfNeeded(Context context) {
        if (deviceCallbackRegistered || context == null) return;
        synchronized (NavSound.class) {
            if (deviceCallbackRegistered) return;
            try {
                AudioManager am = context.getSystemService(AudioManager.class);
                if (am == null) return;
                deviceCallbackRegistered = true;
                deviceCallbackRegisteredAt = nowMs();
                am.registerAudioDeviceCallback(new AudioDeviceCallback() {
                    @Override public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                        handleAudioRouteChanged(addedDevices);
                    }
                    @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                        handleAudioRouteChanged(removedDevices);
                    }
                }, null);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void handleAudioRouteChanged(AudioDeviceInfo[] devices) {
        if (devices == null || devices.length == 0) return;
        long now = nowMs();
        if (now != 0L && now - deviceCallbackRegisteredAt < DEVICE_CALLBACK_ARM_MS) return;
        boolean hasSink = false;
        for (AudioDeviceInfo info : devices) {
            if (info != null && info.isSink()) {
                hasSink = true;
                break;
            }
        }
        if (hasSink) {
            AUDIO_EXECUTOR.execute(NavSound::refreshOnAudioThread);
        }
    }

    private static int detectSampleRate() {
        int nativeRate = 0;
        try {
            nativeRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_SYSTEM);
        } catch (Throwable ignored) {
        }
        return chooseSampleRate(nativeRate);
    }

    private static File ensureClickWavFile(Context ctx, int sampleRate) {
        try {
            File dir = ctx.getCacheDir();
            if (dir == null) return null;
            short[] pcm = buildClickPcm(sampleRate);
            byte[] wav = buildWavBytes(sampleRate, pcm);
            File file = new File(dir, WAV_FILE_PREFIX + sampleRate + WAV_FILE_SUFFIX);
            if (file.isFile() && file.length() == wav.length) {
                return file;
            }
            File tmp = new File(dir, file.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(wav);
                out.getFD().sync();
            }
            if (!tmp.renameTo(file)) {
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(wav);
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            return file.isFile() && file.length() == wav.length ? file : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static AudioAttributes sonificationAttributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
    }

    private static synchronized void releaseSoundPoolOnAudioThread() {
        SoundPool sp = soundPool;
        soundPool = null;
        loadedSoundId = 0;
        soundPoolReady = false;
        soundPoolLoadStartedAt = 0L;
        if (sp != null) {
            try { sp.release(); } catch (Throwable ignored) { }
        }
    }

    private static synchronized void onSoundPoolLoadedOnAudioThread(SoundPool sp, int sampleId) {
        if (sp == null || sp != soundPool) return;
        loadedSoundId = sampleId;
        soundPoolReady = true;
        releaseFallbackPoolOnAudioThread();
    }

    private static synchronized void prepareSoundPoolOnAudioThread() {
        Context ctx = appContext;
        if (ctx == null) return;
        long now = nowMs();
        if (soundPool != null) {
            if (soundPoolReady && loadedSoundId != 0) return;
            if (now != 0L && now - soundPoolLoadStartedAt < LOAD_TIMEOUT_MS) return;
            releaseSoundPoolOnAudioThread();
        }
        try {
            int sampleRate = detectSampleRate();
            File wavFile = ensureClickWavFile(ctx, sampleRate);
            if (wavFile == null) return;
            SoundPool sp = new SoundPool.Builder()
                    .setMaxStreams(MAX_STREAMS)
                    .setAudioAttributes(sonificationAttributes())
                    .build();
            sp.setOnLoadCompleteListener((pool, sampleId, status) -> {
                if (status == 0) {
                    AUDIO_EXECUTOR.execute(() -> onSoundPoolLoadedOnAudioThread(pool, sampleId));
                }
            });
            soundPool = sp;
            soundPoolReady = false;
            loadedSoundId = 0;
            soundPoolLoadStartedAt = now;
            int sid = sp.load(wavFile.getAbsolutePath(), 1);
            if (sid <= 0) {
                releaseSoundPoolOnAudioThread();
            }
        } catch (Throwable ignored) {
            releaseSoundPoolOnAudioThread();
        }
    }

    private static synchronized void refreshOnAudioThread() {
        long now = nowMs();
        if (now != 0L && lastRefreshAt != 0L && now - lastRefreshAt < REFRESH_DEBOUNCE_MS) {
            return;
        }
        lastRefreshAt = now;
        releaseSoundPoolOnAudioThread();
        releaseFallbackPoolOnAudioThread();
        prepareSoundPoolOnAudioThread();
    }

    private static AudioTrack createFallbackTrack(int sampleRate, short[] buffer, float gain) {
        AudioTrack track = null;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(sonificationAttributes())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(buffer.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            if (!isStaticStateBeforeWrite(track.getState())) {
                track.release();
                return null;
            }
            int written = track.write(buffer, 0, buffer.length);
            if (written <= 0 || !isStaticStateAfterWrite(track.getState())) {
                track.release();
                return null;
            }
            if (track.setVolume(gain) != AudioTrack.SUCCESS) {
                track.release();
                return null;
            }
            return track;
        } catch (Throwable ignored) {
            if (track != null) {
                try { track.release(); } catch (Throwable ignored2) { }
            }
            return null;
        }
    }

    private static synchronized void releaseFallbackPoolOnAudioThread() {
        AudioTrack[] pool = fallbackPool;
        fallbackPool = null;
        fallbackNeedsReload = null;
        fallbackPlayedAt = null;
        fallbackNextIndex = 0;
        if (pool == null) return;
        for (AudioTrack track : pool) {
            if (track == null) continue;
            try { track.stop(); } catch (Throwable ignored) { }
            try { track.release(); } catch (Throwable ignored) { }
        }
    }

    private static synchronized void prepareFallbackPoolOnAudioThread() {
        if (fallbackPool != null) return;
        try {
            int sampleRate = detectSampleRate();
            short[] buffer = buildClickPcm(sampleRate);
            float gain = gainForPercent(volumePercent);
            AudioTrack[] created = new AudioTrack[FALLBACK_POOL_SIZE];
            int count = 0;
            for (int i = 0; i < FALLBACK_POOL_SIZE; i++) {
                AudioTrack track = createFallbackTrack(sampleRate, buffer, gain);
                if (track == null) break;
                created[count++] = track;
            }
            if (count == 0) return;
            fallbackPool = count == FALLBACK_POOL_SIZE ? created : java.util.Arrays.copyOf(created, count);
            fallbackNeedsReload = new boolean[count];
            fallbackPlayedAt = new long[count];
            fallbackNextIndex = 0;
        } catch (Throwable ignored) {
        }
    }

    private static boolean rearmTrack(AudioTrack track) {
        if (track.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) {
            track.stop();
        }
        return track.reloadStaticData() == AudioTrack.SUCCESS;
    }

    private static void playWithFallbackOnAudioThread(float gain) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                prepareFallbackPoolOnAudioThread();
                AudioTrack[] pool = fallbackPool;
                if (pool == null || pool.length == 0) return;
                int index = fallbackNextIndex;
                AudioTrack track = pool[index];
                if (!isStaticStateAfterWrite(track.getState())) {
                    releaseFallbackPoolOnAudioThread();
                    continue;
                }
                if (fallbackNeedsReload[index]) {
                    if (!rearmTrack(track)) {
                        releaseFallbackPoolOnAudioThread();
                        continue;
                    }
                    fallbackNeedsReload[index] = false;
                }
                // setVolume() validates the native AudioTrack and returns ERROR_DEAD_OBJECT if dead.
                if (track.setVolume(gain) != AudioTrack.SUCCESS) {
                    releaseFallbackPoolOnAudioThread();
                    continue;
                }
                track.play();
                long now = nowMs();
                fallbackNeedsReload[index] = true;
                fallbackPlayedAt[index] = now;
                int next = (index + 1) % pool.length;
                fallbackNextIndex = next;
                if (pool.length > 1 && fallbackNeedsReload[next]
                        && (now == 0L || now - fallbackPlayedAt[next] >= MIN_REARM_AGE_MS)) {
                    try {
                        if (rearmTrack(pool[next])) {
                            fallbackNeedsReload[next] = false;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return;
            } catch (Throwable ignored) {
                releaseFallbackPoolOnAudioThread();
            }
        }
    }

    static void play(boolean enabled) {
        if (!enabled || volumePercent <= 0) return;
        long now = nowMs();
        if (now != 0L && now - lastPlayedAt < DEBOUNCE_MS) return;
        lastPlayedAt = now;
        if (PENDING_PLAYS.getAndUpdate(v -> v < MAX_QUEUED_PLAYS ? v + 1 : v) < MAX_QUEUED_PLAYS) {
            AUDIO_EXECUTOR.execute(NavSound::playOnAudioThread);
        }
    }

    private static synchronized void playOnAudioThread() {
        PENDING_PLAYS.updateAndGet(v -> v > 0 ? v - 1 : 0);
        int vol = volumePercent;
        if (vol <= 0) return;
        float gain = gainForPercent(vol);

        SoundPool sp = soundPool;
        if (sp != null && soundPoolReady && loadedSoundId != 0) {
            try {
                int streamId = sp.play(loadedSoundId, gain, gain, 1, 0, 1.0f);
                if (streamId != 0) return;
            } catch (Throwable ignored) {
            }
            // SoundPool failed (dead AudioServer or invalid sample): rebuild it and cover this click via fallback.
            releaseSoundPoolOnAudioThread();
            prepareSoundPoolOnAudioThread();
        } else if (appContext != null) {
            prepareSoundPoolOnAudioThread();
        }

        playWithFallbackOnAudioThread(gain);
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
     * D-pad navigation moves focus or list selection, or a confirm key activates a control.
     */
    static boolean dispatchActivityKey(Activity activity, KeyEvent event, boolean enabled,
                                       BooleanSupplier superDispatch) {
        if (!enabled || event.getAction() != KeyEvent.ACTION_DOWN) return superDispatch.getAsBoolean();
        int code = event.getKeyCode();
        if (isDpadDirection(code)) {
            View before = activity.getCurrentFocus();
            int beforePos = selectedPosition(before);
            boolean handled = superDispatch.getAsBoolean();
            View after = activity.getCurrentFocus();
            int afterPos = selectedPosition(after);
            if (focusOrSelectionChanged(before, beforePos, after, afterPos)) play(true);
            return handled;
        }
        if (isConfirmKey(code) && event.getRepeatCount() == 0) {
            play(true);
        }
        return superDispatch.getAsBoolean();
    }

    /**
     * Wraps a dialog's Window.Callback so D-pad focus/list-selection changes and OK presses
     * inside settings dialogs produce the same navigation click sound.
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
                                int beforePos = selectedPosition(before);
                                Object result = method.invoke(original, args);
                                View after = window.getCurrentFocus();
                                int afterPos = selectedPosition(after);
                                if (focusOrSelectionChanged(before, beforePos, after, afterPos)) {
                                    play(true);
                                }
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
