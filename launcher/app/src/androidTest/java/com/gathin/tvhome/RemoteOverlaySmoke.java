package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Verifies Control Center overlay over a foreground non-launcher app, including both
 * RemoteControlOverlay.toggle() and RemoteButtonMapper's CONTROL / INPUTS actions.
 */
public final class RemoteOverlaySmoke extends Instrumentation {
    private int checks;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            AccessibilityService service = waitForService();
            check(service != null, "InputKeyService must be enabled and connected");
            check(!MainActivity.remotePanelForeground, "A non-launcher app must be in the foreground");

            // 1. Toggle overlay via RemoteControlOverlay.toggle()
            runOnMainSync(() -> RemoteControlOverlay.toggle(service));
            settle();
            FrameLayout root = overlayRoot();
            QuickSettingsPanel panel = activePanel();
            check(root != null, "Overlay root window was added");
            check(root.getWidth() > 0 && root.getHeight() > 0, "Overlay root laid out");
            check(root.isHardwareAccelerated(), "Overlay window is hardware accelerated");
            check(panel != null && panel.isShowing(), "QuickSettingsPanel is attached and showing");
            View scroll = panelScroll(panel);
            check(scroll != null && scroll.getAlpha() > 0.95f,
                    "QuickSettingsPanel animated to visible alpha=" + (scroll == null ? -1f : scroll.getAlpha()));
            check(panel.getCurrentFocus() != null, "Overlay panel received initial focus");

            // D-pad navigation inside overlay
            int beforeFocus = panel.getCurrentFocus().getId();
            runOnMainSync(() -> {
                panel.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
                panel.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN));
            });
            SystemClock.sleep(150);
            check(panel.getCurrentFocus() != null && panel.getCurrentFocus().getId() != beforeFocus,
                    "D-pad Down moves focus inside overlay");

            // Second toggle closes overlay cleanly
            runOnMainSync(() -> RemoteControlOverlay.toggle(service));
            settle();
            check(overlayRoot() == null, "Second toggle dismissed overlay root");
            check(activePanel() == null, "Second toggle cleared activePanel");

            // 2. RemoteButtonMapper CONTROL target opens overlay over foreground app (not MainActivity)
            Method launch = RemoteButtonMapper.class.getDeclaredMethod("launch", android.content.Context.class, String.class);
            launch.setAccessible(true);
            runOnMainSync(() -> {
                try { launch.invoke(null, getTargetContext().getApplicationContext(), RemoteButtonMapper.CONTROL); }
                catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            settle();
            check(!MainActivity.remotePanelForeground, "RemoteButtonMapper CONTROL stays over current app");
            check(overlayRoot() != null && activePanel() != null && activePanel().isShowing(),
                    "RemoteButtonMapper CONTROL opens overlay over current app");
            View scroll2 = panelScroll(activePanel());
            check(scroll2 != null && scroll2.getAlpha() > 0.95f,
                    "RemoteButtonMapper overlay is fully visible alpha=" + (scroll2 == null ? -1f : scroll2.getAlpha()));

            // Second CONTROL press toggles it closed
            runOnMainSync(() -> {
                try { launch.invoke(null, getTargetContext().getApplicationContext(), RemoteButtonMapper.CONTROL); }
                catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            settle();
            check(overlayRoot() == null, "Second RemoteButtonMapper CONTROL closes overlay");

            result.putString(Instrumentation.REPORT_KEY_STREAMRESULT,
                    "RemoteOverlaySmoke OK (" + checks + " checks)\n");
            finish(android.app.Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            runOnMainSync(RemoteControlOverlay::closeIfShowing);
            result.putString(Instrumentation.REPORT_KEY_STREAMRESULT,
                    "RemoteOverlaySmoke FAILED: " + failure + "\n");
            finish(android.app.Activity.RESULT_CANCELED, result);
        }
    }

    private AccessibilityService waitForService() {
        long deadline = SystemClock.uptimeMillis() + 8000L;
        while (SystemClock.uptimeMillis() < deadline) {
            if (HomeKeyService.running != null) return HomeKeyService.running;
            SystemClock.sleep(100);
        }
        return HomeKeyService.running;
    }

    private FrameLayout overlayRoot() {
        try {
            Field f = RemoteControlOverlay.class.getDeclaredField("root");
            f.setAccessible(true);
            return (FrameLayout) f.get(null);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    private QuickSettingsPanel activePanel() {
        try {
            Field f = RemoteControlOverlay.class.getDeclaredField("activePanel");
            f.setAccessible(true);
            return (QuickSettingsPanel) f.get(null);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    private View panelScroll(QuickSettingsPanel panel) {
        try {
            Field f = QuickSettingsPanel.class.getDeclaredField("scroll");
            f.setAccessible(true);
            return (View) f.get(panel);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    private void settle() {
        SystemClock.sleep(900);
    }

    private void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }
}
