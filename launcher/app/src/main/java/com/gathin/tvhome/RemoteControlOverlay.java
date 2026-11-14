package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A small control panel over the current app, hosted by the enabled accessibility service. */
final class RemoteControlOverlay {
    private static AccessibilityService owner;
    private static WindowManager windows;
    private static FrameLayout root;

    static void toggle(AccessibilityService service) {
        if (root != null) { close(); return; }
        if (MainActivity.remotePanelForeground) {
            open(service, new Intent(service, MainActivity.class).setAction(MainActivity.ACTION_QUICK_SETTINGS));
            return;
        }
        show(service);
    }

    static void closeFor(AccessibilityService service) {
        if (owner == service) close();
    }

    private static void show(AccessibilityService service) {
        WindowManager manager = service.getSystemService(WindowManager.class);
        if (manager == null) return;
        FrameLayout panelRoot = new FrameLayout(service);
        panelRoot.setBackgroundColor(0xB0030C1A);
        panelRoot.setFocusableInTouchMode(true);
        panelRoot.setOnClickListener(v -> close());
        panelRoot.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) close();
            return true;
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
            panelRoot.post(new Runnable() { public void run() {
                if (root != panelRoot) return;
                if (panelRoot.getWidth() == 0) { panelRoot.postDelayed(this, 16); return; }
                Config config = Config.load(service);
                QuickSettingsPanel panel = new QuickSettingsPanel(LauncherLocale.wrap(service), panelRoot,
                        config.reducedMotion, config.animations, config.navigationSounds, new QuickSettingsPanel.Listener() {
                    public void onAction(int control) {
                        close();
                        Intent action = control == R.id.quickLauncherSettings
                                ? new Intent(service, LauncherSettingsActivity.class) : QuickSettingsActions.intent(service, control);
                        open(service, action);
                    }
                    public void onClosed(boolean restoreFocus) { close(); }
                });
                panel.open(R.id.quickAllSettings);
            }});
        } catch (RuntimeException unavailable) {
            // Some firmware refuses accessibility overlays; open the launcher's panel instead.
            open(service, new Intent(service, MainActivity.class).setAction(MainActivity.ACTION_QUICK_SETTINGS));
        }
    }

    private static void add(AccessibilityService service, LinearLayout column, int label, int action,
                            boolean home) {
        TextView row = new TextView(service);
        row.setText(label);
        row.setTextColor(Color.WHITE);
        row.setTextSize(19);
        row.setPadding(dp(service, 20), dp(service, 12), dp(service, 20), dp(service, 12));
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackgroundResource(R.drawable.glass_menu_row);
        row.setOnClickListener(v -> {
            close();
            if (action == -1) return;
            Intent intent = home ? new Intent(service, MainActivity.class)
                    : action == 0 ? new Intent(service, MainActivity.class).setAction(MainActivity.ACTION_QUICK_SETTINGS)
                    : QuickSettingsActions.intent(service, action);
            open(service, intent);
        });
        column.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private static void open(AccessibilityService service, Intent intent) {
        try { service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (ActivityNotFoundException | SecurityException ignored) { }
    }

    private static int dp(AccessibilityService service, int value) {
        return (int) (value * service.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void close() {
        if (root != null && windows != null) {
            try { windows.removeView(root); } catch (RuntimeException ignored) { }
        }
        root = null;
        windows = null;
        owner = null;
    }

    private RemoteControlOverlay() { }
}
