package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

/**
 * In-process TV sleep timer with presets (Off -> 15 -> 30 -> 60 -> 90 min). Warns 1 minute before
 * sleep and puts the TV into standby via local ADB ({@code input keyevent 223}), falling back to
 * Android TV's sleep action intent when ADB is unavailable.
 */
final class SleepTimer {
    static final int[] PRESETS = {0, 15, 30, 60, 90};
    private static Handler main;
    private static long deadlineElapsedMs;
    private static int selectedMinutes;
    private static Runnable warningTask;
    private static Runnable sleepTask;

    private SleepTimer() { }

    private static synchronized Handler mainHandler() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    static int nextPreset(int currentMinutes) {
        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i] == currentMinutes) return PRESETS[(i + 1) % PRESETS.length];
        }
        return PRESETS[1];
    }

    static int remainingMinutes(long deadlineMs, long nowMs) {
        if (deadlineMs <= 0 || nowMs >= deadlineMs) return 0;
        long leftMs = deadlineMs - nowMs;
        return (int) Math.max(1L, (leftMs + 59_999L) / 60_000L);
    }

    static synchronized int remainingMinutes() {
        return remainingMinutes(deadlineElapsedMs, SystemClock.elapsedRealtime());
    }

    static synchronized boolean isActive() {
        return remainingMinutes() > 0;
    }

    /** Cycles through Off -> 15 -> 30 -> 60 -> 90 -> Off and returns the newly scheduled minutes. */
    static synchronized int cycle(Context context) {
        int current = isActive() ? selectedMinutes : 0;
        int next = nextPreset(current);
        schedule(context, next);
        return next;
    }

    static synchronized void cancel() {
        deadlineElapsedMs = 0L;
        selectedMinutes = 0;
        Handler h = main;
        if (h != null && warningTask != null) {
            h.removeCallbacks(warningTask);
        }
        warningTask = null;
        if (h != null && sleepTask != null) {
            h.removeCallbacks(sleepTask);
        }
        sleepTask = null;
    }

    static synchronized void schedule(Context context, int minutes) {
        cancel();
        if (context == null || minutes <= 0) return;
        Context app = context.getApplicationContext();
        Handler h = mainHandler();
        selectedMinutes = minutes;
        long delayMs = minutes * 60_000L;
        deadlineElapsedMs = SystemClock.elapsedRealtime() + delayMs;
        if (minutes > 1) {
            warningTask = () -> {
                Context localized = LauncherLocale.wrap(app);
                Toast.makeText(localized, R.string.quick_sleep_warning, Toast.LENGTH_LONG).show();
            };
            h.postDelayed(warningTask, delayMs - 60_000L);
        }
        sleepTask = () -> {
            synchronized (SleepTimer.class) {
                deadlineElapsedMs = 0L;
                selectedMinutes = 0;
                warningTask = null;
                sleepTask = null;
            }
            triggerSleep(app);
        };
        h.postDelayed(sleepTask, delayMs);
    }

    private static void triggerSleep(Context app) {
        FeatureNetwork.WORKER.execute(() -> {
            try {
                LocalAdbClient.run(app, "input keyevent 223");
            } catch (Exception ignored) {
                mainHandler().post(() -> {
                    try {
                        Intent sleep = new Intent("com.google.android.pano.action.SLEEP")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        app.startActivity(sleep);
                    } catch (Exception unavailable) {
                        Context localized = LauncherLocale.wrap(app);
                        Toast.makeText(localized, R.string.quick_sleep_adb_hint, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }
}
