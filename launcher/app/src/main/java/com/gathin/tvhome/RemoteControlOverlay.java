package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** A control panel over the current app, hosted by the enabled accessibility service. */
final class RemoteControlOverlay {
    private static final long BACK_WINDOW_MS = 3_000L;

    private static AccessibilityService owner;
    private static WindowManager windows;
    private static FrameLayout root;
    private static QuickSettingsPanel activePanel;

    /** Foreground non-settings package observed before opening the overlay. */
    private static String currentHostPackage = "";
    /** Control tile to refocus when returning via Back from a launched settings screen. */
    private static int pendingReturnControl;
    private static String pendingHostPackage = "";
    private static boolean pendingLeftHost;
    private static long lastBackAt;

    static void toggle(AccessibilityService service) {
        clearPendingReturn();
        if (root != null) {
            if (activePanel != null && activePanel.isShowing()) activePanel.dismiss();
            else close();
            return;
        }
        if (MainActivity.toggleForegroundPanel()) return;
        if (MainActivity.remotePanelForeground) {
            open(service, new Intent(service, MainActivity.class)
                    .setAction(MainActivity.ACTION_QUICK_SETTINGS));
            return;
        }
        show(service, R.id.quickAllSettings);
    }

    static void closeFor(AccessibilityService service) {
        if (owner == service) {
            clearPendingReturn();
            close();
        }
    }

    static void clearPendingReturn() {
        pendingReturnControl = 0;
        pendingHostPackage = "";
        pendingLeftHost = false;
    }

    static void noteBackKey() {
        lastBackAt = SystemClock.elapsedRealtime();
    }

    static void onWindowChanged(AccessibilityService service, CharSequence pkgSeq, CharSequence clsSeq) {
        if (pkgSeq == null || pkgSeq.length() == 0) return;
        String pkg = pkgSeq.toString();
        String cls = clsSeq == null ? "" : clsSeq.toString();
        if (isIgnoredSystemWindow(pkg)) return;
        if (root != null) return;

        String ownPackage = service.getPackageName();
        boolean launcherSettings = isLauncherSettingsWindow(ownPackage, pkg, cls);
        boolean mainActivity = ownPackage.equals(pkg) && cls.endsWith(".MainActivity");

        if (pendingReturnControl == 0) {
            if (!launcherSettings) currentHostPackage = pkg;
            return;
        }

        if (!pendingLeftHost) {
            if (launcherSettings || (!pkg.equals(pendingHostPackage) && !ownPackage.equals(pkg))) {
                pendingLeftHost = true;
            }
            return;
        }

        if (launcherSettings || isSettingsPackage(pkg)) return;

        boolean returnedToHost = !pendingHostPackage.isEmpty() && pendingHostPackage.equals(pkg);
        boolean recentBack = SystemClock.elapsedRealtime() - lastBackAt <= BACK_WINDOW_MS;
        if (returnedToHost || (pendingHostPackage.isEmpty() && recentBack)) {
            int control = pendingReturnControl;
            clearPendingReturn();
            currentHostPackage = pkg;
            if (mainActivity || MainActivity.remotePanelForeground) {
                open(service, new Intent(service, MainActivity.class)
                        .setAction(MainActivity.ACTION_QUICK_SETTINGS)
                        .putExtra(MainActivity.EXTRA_QUICK_FOCUS, control));
            } else {
                show(service, control);
            }
        } else if (!recentBack) {
            clearPendingReturn();
            currentHostPackage = pkg;
        }
    }

    private static void armPendingReturn(int control, String hostPackage) {
        pendingReturnControl = control;
        pendingHostPackage = hostPackage == null ? "" : hostPackage;
        pendingLeftHost = false;
    }

    private static boolean isIgnoredSystemWindow(String pkg) {
        return "com.android.systemui".equals(pkg) || "android".equals(pkg);
    }

    private static boolean isLauncherSettingsWindow(String ownPackage, String pkg, String cls) {
        if (!ownPackage.equals(pkg)) return false;
        return cls.endsWith(".LauncherSettingsActivity")
                || cls.endsWith(".FeatureSettingsActivity")
                || cls.endsWith(".OptimizationActivity")
                || cls.endsWith(".UpdateActivity");
    }

    private static boolean isSettingsPackage(String pkg) {
        return "com.android.tv.settings".equals(pkg)
                || "com.google.android.tv.settings".equals(pkg)
                || "com.tcl.settings".equals(pkg)
                || "com.android.settings".equals(pkg)
                || "com.tcl.guard".equals(pkg)
                || "com.xiaomi.mitv.settings".equals(pkg);
    }

    private static void show(AccessibilityService service, int focusControl) {
        WindowManager manager = service.getSystemService(WindowManager.class);
        if (manager == null) return;
        final String host = currentHostPackage;
        FrameLayout panelRoot = new FrameLayout(service);
        panelRoot.setFocusableInTouchMode(true);
        panelRoot.setOnClickListener(v -> {
            if (activePanel != null && activePanel.isShowing()) activePanel.dismiss();
            else close();
        });
        View shortcuts = new View(service);
        shortcuts.setId(R.id.topButtons);
        panelRoot.addView(shortcuts, new FrameLayout.LayoutParams(1, 1));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        try {
            manager.addView(panelRoot, params);
            windows = manager;
            root = panelRoot;
            owner = service;
            activePanel = null;
            panelRoot.post(new Runnable() {
                public void run() {
                    if (root != panelRoot) return;
                    if (panelRoot.getWidth() == 0) {
                        panelRoot.postDelayed(this, 16);
                        return;
                    }
                    Config config = Config.load(service);
                    QuickSettingsPanel panel = new QuickSettingsPanel(LauncherLocale.wrap(service), panelRoot,
                            config.reducedMotion, config.animations, config.navigationSounds,
                            new QuickSettingsPanel.Listener() {
                                public void onAction(int control) {
                                    if (control == R.id.quickClearAll) {
                                        armPendingReturn(control, host);
                                        close();
                                        QuickSettingsActions.clearBackground(service);
                                        return;
                                    }
                                    if (control == R.id.quickInputs) {
                                        clearPendingReturn();
                                        close();
                                        open(service, new Intent(service, MainActivity.class)
                                                .setAction(MainActivity.ACTION_VIEW_INPUTS));
                                        return;
                                    }
                                    armPendingReturn(control, host);
                                    close();
                                    Intent action = control == R.id.quickLauncherSettings
                                            ? new Intent(service, LauncherSettingsActivity.class)
                                            : QuickSettingsActions.intent(service, control)
                                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                    if (!open(service, action)) {
                                        clearPendingReturn();
                                        show(service, control);
                                    }
                                }

                                public void onClosed(boolean restoreFocus) {
                                    close();
                                }
                            });
                    activePanel = panel;
                    panel.open(focusControl);
                }
            });
        } catch (RuntimeException unavailable) {
            // Some firmware refuses accessibility overlays; open the launcher's panel instead.
            open(service, new Intent(service, MainActivity.class)
                    .setAction(MainActivity.ACTION_QUICK_SETTINGS)
                    .putExtra(MainActivity.EXTRA_QUICK_FOCUS, focusControl));
        }
    }

    private static boolean open(AccessibilityService service, Intent intent) {
        try {
            service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            android.widget.Toast.makeText(LauncherLocale.wrap(service), R.string.not_available,
                    android.widget.Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private static void close() {
        if (root != null && windows != null) {
            try { windows.removeView(root); } catch (RuntimeException ignored) { }
        }
        activePanel = null;
        root = null;
        windows = null;
        owner = null;
    }

    private RemoteControlOverlay() { }
}
