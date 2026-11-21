package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

/** A control panel over the current app, hosted by the enabled accessibility service. */
final class RemoteControlOverlay {
    private static final long BACK_WINDOW_MS = 3_000L;

    private static AccessibilityService owner;
    private static WindowManager windows;
    private static FrameLayout root;
    private static QuickSettingsPanel activePanel;
    private static View activeInputsOverlay;

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
            if (activeInputsOverlay != null) {
                close();
                return;
            }
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
        show(service, R.id.quickAllSettings, false);
    }

    static void showInputs(AccessibilityService service) {
        clearPendingReturn();
        if (root != null && activePanel != null && activePanel.isShowing()) {
            if (activeInputsOverlay == null) {
                openInputsOverlay(service, root, activePanel, Config.load(service));
            }
            return;
        }
        if (MainActivity.remotePanelForeground) {
            open(service, new Intent(service, MainActivity.class)
                    .setAction(MainActivity.ACTION_VIEW_INPUTS));
            return;
        }
        show(service, R.id.quickInputs, true);
    }

    static void closeFor(AccessibilityService service) {
        if (owner == service) {
            clearPendingReturn();
            close();
        }
    }

    static boolean dismissForHome(AccessibilityService service, Runnable after) {
        if (owner != service || root == null) return false;
        clearPendingReturn();
        if (activeInputsOverlay == null && activePanel != null
                && activePanel.isShowing() && !activePanel.isClosing()) {
            activePanel.closeForAction(() -> {
                close();
                if (after != null) after.run();
            });
            return true;
        }
        close();
        if (after != null) after.run();
        return true;
    }

    static void closeIfShowing() {
        if (root == null) return;
        clearPendingReturn();
        if (activeInputsOverlay == null && activePanel != null && activePanel.isShowing()) {
            activePanel.dismiss();
        } else {
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
                show(service, control, false);
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

    private static void show(AccessibilityService service, int focusControl, boolean openInputsImmediately) {
        WindowManager manager = service.getSystemService(WindowManager.class);
        if (manager == null) return;
        final String host = currentHostPackage;
        FrameLayout panelRoot = new FrameLayout(service) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if (activeInputsOverlay == null && (activePanel == null || !activePanel.isShowing())
                        && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) close();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        try {
            manager.addView(panelRoot, params);
            windows = manager;
            root = panelRoot;
            owner = service;
            activePanel = null;
            activeInputsOverlay = null;
            Config config = Config.load(service);
            if (config.navigationSounds) NavSound.prepare();
            Runnable attachPanel = new Runnable() {
                private boolean opened;
                @Override public void run() {
                    if (opened || root != panelRoot || panelRoot.getWidth() == 0) return;
                    opened = true;
                    try {
                        QuickSettingsPanel panel = new QuickSettingsPanel(LauncherLocale.wrap(service), panelRoot,
                                null, config.reducedMotion, config.animations, config.navigationSounds,
                                new QuickSettingsPanel.Listener() {
                                    public void onAction(int control) {
                                        if (control == R.id.quickClearAll) {
                                            QuickSettingsActions.clearBackground(service, host, () -> {
                                                armPendingReturn(control, host);
                                                close();
                                                open(service, new Intent(service, OptimizationActivity.class));
                                            });
                                            return;
                                        }
                                        if (control == R.id.quickInputs) {
                                            openInputsOverlay(service, panelRoot, activePanel, config);
                                            return;
                                        }
                                        if (control == R.id.quickBluetooth) {
                                            QuickSettingsActions.resolveBluetooth(service, resolved -> {
                                                if (root != panelRoot) return;
                                                armPendingReturn(control, host);
                                                close();
                                                if (!open(service, resolved.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))) {
                                                    clearPendingReturn();
                                                    show(service, control, false);
                                                }
                                            });
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
                                            show(service, control, false);
                                        }
                                    }

                                    @Override public void onOpened() {
                                        if (openInputsImmediately && root == panelRoot && activePanel != null) {
                                            openInputsOverlay(service, panelRoot, activePanel, config);
                                        }
                                    }

                                    public void onClosed(boolean restoreFocus) {
                                        close();
                                    }
                                });
                        activePanel = panel;
                        panel.open(focusControl);
                    } catch (RuntimeException error) {
                        close();
                        open(service, new Intent(service, MainActivity.class)
                                .setAction(openInputsImmediately ? MainActivity.ACTION_VIEW_INPUTS : MainActivity.ACTION_QUICK_SETTINGS)
                                .putExtra(MainActivity.EXTRA_QUICK_FOCUS, focusControl));
                    }
                }
            };
            panelRoot.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                     int oldL, int oldT, int oldR, int oldB) {
                    if (v.getWidth() == 0) return;
                    v.removeOnLayoutChangeListener(this);
                    v.post(attachPanel);
                }
            });
            panelRoot.post(attachPanel);
        } catch (RuntimeException unavailable) {
            close();
            // Some firmware refuses accessibility overlays; open the launcher's panel instead.
            open(service, new Intent(service, MainActivity.class)
                    .setAction(openInputsImmediately ? MainActivity.ACTION_VIEW_INPUTS : MainActivity.ACTION_QUICK_SETTINGS)
                    .putExtra(MainActivity.EXTRA_QUICK_FOCUS, focusControl));
        }
    }

    /**
     * Shows the glass Inputs selector inside the existing accessibility overlay over the active app,
     * so choosing or cancelling Inputs never launches MainActivity or closes the foreground app.
     */
    private static void openInputsOverlay(AccessibilityService service, FrameLayout panelRoot,
                                          QuickSettingsPanel panel, Config config) {
        if (root != panelRoot || panel == null || activeInputsOverlay != null) return;
        Context localized = LauncherLocale.wrap(service);
        if (TvInputsMenu.prefersDirectSourcePanel(localized, config)) {
            clearPendingReturn();
            close();
            TvInputsMenu.openSourcePanel(localized, config);
            return;
        }
        float density = localized.getResources().getDisplayMetrics().density;
        View card = LayoutInflater.from(localized).inflate(R.layout.popup_inputs, panelRoot, false);
        card.setBackgroundResource(R.drawable.quick_panel_background);
        LinearLayout rows = card.findViewById(R.id.rows);
        if (!TvInputsMenu.populate(localized, LayoutInflater.from(localized), rows, config,
                density, config.navigationSounds, () -> {
                    clearPendingReturn();
                    close();
                })) {
            Toast.makeText(localized, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        int[] settingsKeycodes;
        try { settingsKeycodes = localized.getResources().getIntArray(R.array.settings_key_keycodes); }
        catch (RuntimeException ignored) { settingsKeycodes = new int[0]; }
        final int[] gearKeys = settingsKeycodes;

        FrameLayout backdrop = new FrameLayout(localized) {
            private boolean backDown;
            private boolean gearDown;

            private boolean isGear(int code) {
                if (code == KeyEvent.KEYCODE_SETTINGS) return true;
                for (int listed : gearKeys) if (listed == code) return true;
                return false;
            }

            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                int code = event.getKeyCode();
                if (code == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) backDown = true;
                    else if (event.getAction() == KeyEvent.ACTION_UP) {
                        boolean dismiss = backDown && !event.isCanceled();
                        backDown = false;
                        if (dismiss) dismissInputsOverlay(panelRoot, panel);
                    }
                    return true;
                }
                if (isGear(code)) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) gearDown = true;
                    else if (event.getAction() == KeyEvent.ACTION_UP) {
                        boolean closeAll = gearDown && !event.isCanceled();
                        gearDown = false;
                        if (closeAll) close();
                    }
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && event.getAction() == KeyEvent.ACTION_DOWN) {
                    NavSound.play(config.navigationSounds);
                    dismissInputsOverlay(panelRoot, panel);
                    return true;
                }
                if (config.navigationSounds && event.getAction() == KeyEvent.ACTION_DOWN
                        && NavSound.isDpadDirection(code)) {
                    View before = findFocus();
                    boolean handled = super.dispatchKeyEvent(event);
                    View after = findFocus();
                    if (after != null && after != before) NavSound.play(true);
                    return handled;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        backdrop.setClickable(true);
        backdrop.setOnClickListener(v -> dismissInputsOverlay(panelRoot, panel));

        int minWidth = Math.round(210 * density);
        int maxWidth = Math.round(360 * density);
        card.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(panelRoot.getHeight(), View.MeasureSpec.AT_MOST));
        int width = Math.max(minWidth, Math.min(card.getMeasuredWidth(), maxWidth));
        int height = card.getMeasuredHeight();

        View quickPanel = panel.findViewById(R.id.quickPanel);
        View anchor = panel.findViewById(R.id.quickInputs);
        int margin = Math.round(14 * density);
        int left;
        int top;
        if (quickPanel != null && anchor != null) {
            int[] panelLoc = new int[2], anchorLoc = new int[2], rootLoc = new int[2];
            quickPanel.getLocationOnScreen(panelLoc);
            anchor.getLocationOnScreen(anchorLoc);
            panelRoot.getLocationOnScreen(rootLoc);
            left = panelLoc[0] - rootLoc[0] - width - margin;
            top = anchorLoc[1] - rootLoc[1];
        } else {
            left = panelRoot.getWidth() - width - Math.round(360 * density);
            top = Math.round(120 * density);
        }
        left = Math.max(margin, Math.min(panelRoot.getWidth() - width - margin, left));
        top = Math.max(margin, Math.min(panelRoot.getHeight() - height - margin, top));

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT);
        cardParams.leftMargin = left;
        cardParams.topMargin = top;
        backdrop.addView(card, cardParams);

        panel.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        activeInputsOverlay = backdrop;
        panelRoot.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.setAlpha(0f);
        card.setTranslationX(10 * density);
        card.animate().alpha(1f).translationX(0f)
                .setDuration(config.animations && !config.reducedMotion ? 180 : 0).start();
        for (int i = 0; i < rows.getChildCount(); i++) {
            View child = rows.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.isFocusable()) {
                child.requestFocus();
                break;
            }
        }
    }

    private static void dismissInputsOverlay(FrameLayout panelRoot, QuickSettingsPanel panel) {
        if (activeInputsOverlay != null) {
            panelRoot.removeView(activeInputsOverlay);
            activeInputsOverlay = null;
        }
        if (panel != null && panel.isShowing()) {
            panel.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            View inputs = panel.findViewById(R.id.quickInputs);
            if (inputs != null) inputs.requestFocusFromTouch();
        }
    }

    private static boolean open(AccessibilityService service, Intent intent) {
        try {
            service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(LauncherLocale.wrap(service), R.string.not_available, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private static void close() {
        if (root != null && windows != null) {
            try { windows.removeView(root); } catch (RuntimeException ignored) { }
        }
        activeInputsOverlay = null;
        activePanel = null;
        root = null;
        windows = null;
        owner = null;
    }

    private RemoteControlOverlay() { }
}
