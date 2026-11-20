package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;

/** Optional remote shortcuts for the control panel. All are off until chosen in Remote buttons. */
final class RemotePanelKeys {
    static final String PANEL_KEY = "remote_panel_key";
    static final String LONG_HOME = "remote_long_home_panel";
    static final String PLAY_PAUSE = "remote_play_pause_panel";
    static final int[] PANEL_CHOICES = {0, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PROG_RED,
            KeyEvent.KEYCODE_SETTINGS};

    static boolean isPanelKey(int selected, int code) {
        return selected != 0 && selected == code;
    }

    static boolean isPanelKey(int selected, int code, int[] settingsKeycodes) {
        if (isPanelKey(selected, code)) return true;
        if (selected == KeyEvent.KEYCODE_SETTINGS && settingsKeycodes != null) {
            for (int listed : settingsKeycodes) if (listed == code) return true;
        }
        return false;
    }

    static boolean interceptPlayPause(boolean enabled, boolean launcherForeground) {
        return enabled && !launcherForeground;
    }

    static boolean openOnRelease(int action, boolean canceled) {
        return action == KeyEvent.ACTION_UP && !canceled;
    }

    static boolean handle(AccessibilityService service, KeyEvent event) {
        return handle(service, event, null);
    }

    static boolean handle(AccessibilityService service, KeyEvent event, int[] settingsKeycodes) {
        android.content.SharedPreferences prefs = FeaturePreferences.prefs(service);
        int selected = prefs.getInt(PANEL_KEY, 0);
        int code = event.getKeyCode();
        if (!isPanelKey(selected, code, settingsKeycodes)
                && !(code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                && interceptPlayPause(prefs.getBoolean(PLAY_PAUSE, false), MainActivity.remotePanelForeground)))
            return false;
        if (openOnRelease(event.getAction(), event.isCanceled()))
            RemoteControlOverlay.toggle(service);
        return true;
    }

    private RemotePanelKeys() { }
}
