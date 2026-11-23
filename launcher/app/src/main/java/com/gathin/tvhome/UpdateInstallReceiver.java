package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

/** Handles PackageInstaller callbacks and brings Home back to the foreground after an update. */
public final class UpdateInstallReceiver extends BroadcastReceiver {
    private static final String TAG = "UpdateInstall";
    private static final String PREFS = "launcher_update_relaunch";
    private static final String KEY_UNTIL = "relaunch_until";
    static final long INSTALL_WINDOW_MS = 120_000L;
    static final long POST_REPLACE_WINDOW_MS = 20_000L;
    private static final long DEBOUNCE_MS = 2_500L;
    private static final long FALLBACK_DELAY_MS = 900L;
    private static volatile long lastLaunchAttemptAt;

    @Override public void onReceive(Context context, Intent result) {
        if (result == null) return;
        int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            markPendingRelaunch(context, INSTALL_WINDOW_MS);
            Intent confirm = result.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                try { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (RuntimeException ignored) {
                    clearPendingRelaunch(context);
                    Toast.makeText(context, R.string.update_failed, Toast.LENGTH_LONG).show();
                }
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            relaunchAfterUpdate(context, this);
        } else {
            clearPendingRelaunch(context);
            if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                Toast.makeText(context, R.string.update_failed, Toast.LENGTH_LONG).show();
            }
        }
    }

    static void markPendingRelaunch(Context context, long windowMs) {
        long until = System.currentTimeMillis() + Math.max(0L, windowMs);
        prefs(context).edit().putLong(KEY_UNTIL, until).apply();
    }

    static void clearPendingRelaunch(Context context) {
        prefs(context).edit().remove(KEY_UNTIL).apply();
    }

    static boolean isPendingRelaunch(Context context) {
        long until = prefs(context).getLong(KEY_UNTIL, 0L);
        return isWithinWindow(until, System.currentTimeMillis());
    }

    static boolean consumePendingRelaunch(Context context) {
        boolean pending = isPendingRelaunch(context);
        clearPendingRelaunch(context);
        return pending;
    }

    static boolean isWithinWindow(long untilMs, long nowMs) {
        return untilMs > 0L && nowMs <= untilMs;
    }

    static void markLaunchAttempt() {
        lastLaunchAttemptAt = SystemClock.elapsedRealtime();
    }

    static void relaunchAfterUpdate(Context context, BroadcastReceiver receiver) {
        Context app = context.getApplicationContext();
        PowerManager power = app.getSystemService(PowerManager.class);
        if (power != null && !power.isInteractive()) {
            clearPendingRelaunch(app);
            return;
        }
        if (MainActivity.remotePanelForeground) {
            clearPendingRelaunch(app);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastLaunchAttemptAt != 0L && now - lastLaunchAttemptAt < DEBOUNCE_MS) return;
        lastLaunchAttemptAt = now;
        markPendingRelaunch(app, POST_REPLACE_WINDOW_MS);

        HomeKeyService helper = HomeKeyService.running;
        if (helper != null) {
            helper.openHomeAfterUpdate();
            return;
        }

        try {
            app.startActivity(buildHomeLaunchIntent(app));
        } catch (RuntimeException error) {
            Log.w(TAG, "Direct post-update launch failed", error);
        }

        PendingResult pending = receiver != null ? receiver.goAsync() : null;
        new Thread(() -> {
            try {
                Thread.sleep(FALLBACK_DELAY_MS);
                if (MainActivity.remotePanelForeground || !isPendingRelaunch(app)) return;
                HomeKeyService bound = HomeKeyService.running;
                if (bound != null) {
                    new Handler(Looper.getMainLooper()).post(bound::openHomeAfterUpdate);
                    return;
                }
                if (LocalAdbClient.hasSavedKeys(app) && LocalAdbClient.portOpen()) {
                    try {
                        LocalAdbClient.run(app, adbStartHomeCommand(app.getPackageName(), stockHomeStays(app)));
                    } catch (Exception error) {
                        Log.w(TAG, "ADB post-update launch failed", error);
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                if (pending != null) pending.finish();
            }
        }, "tvhome-relaunch").start();
    }

    static Intent buildHomeLaunchIntent(Context context) {
        Intent open = stockHomeStays(context)
                ? new Intent(context, MainActivity.class)
                : new Intent(Intent.ACTION_MAIN).setPackage(context.getPackageName());
        return open.addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    static String adbStartHomeCommand(String packageName, boolean stockHomeStays) {
        String component = packageName + "/" + MainActivity.class.getName();
        if (stockHomeStays) {
            return "am start -n " + component + " -c android.intent.category.HOME";
        }
        return "am start -a android.intent.action.MAIN -c android.intent.category.HOME -n " + component;
    }

    static boolean stockHomeStays(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        ResolveInfo home = context.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        if (home == null || home.activityInfo == null) return false;
        return !context.getPackageName().equals(home.activityInfo.packageName)
                && !"android".equals(home.activityInfo.packageName);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
