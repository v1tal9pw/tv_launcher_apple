package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import java.util.Collections;
import java.util.Map;

/**
 * Maps the remote's Input button to the system input switcher, from any app. Remotes such as the Chromecast voice
 * remote send KEYCODE_TV for it, others (KTC and Kalley Realtek Google TV remotes) KEYCODE_TV_INPUT; on Google TV
 * the stock launcher handles both, so with the stock launcher disabled the button does nothing. Buttons listed in
 * input_key_keycodes (config.xml or a theme) open the input switcher the same way, and app_keys start an app (a
 * YouTube button that only the stock launcher answered). Every other key passes through untouched.
 * Opt-in: INPUT_KEY_SERVICE=1 build-and-install.sh (or -InputKeyService on Windows) enables it.
 */
public class InputKeyService extends AccessibilityService {
    static boolean connected;

    private static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";

    private int[] inputKeycodes = new int[0];
    private Map<Integer, String> appKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();
    private Map<Integer, String> holdAppKeys = Collections.emptyMap();
    private Map<Integer, String> holdAppScancodes = Collections.emptyMap();
    /** restore_key_services was on when the config was read: this helper is noted for ServiceRepair. */
    private boolean repairRecord;
    /** consume_volume_keys: Volume Up/Down go to VolumeKeys instead of on to the firmware. */
    private boolean consumeVolumeKeys;
    /** Stops key filtering while a game controller is connected; null unless gamepad_passthrough and key_filter. */
    private GamepadPassthrough gamepads;
    private android.content.SharedPreferences featureChoices;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean longHomeOpenedPanel;
    private final Runnable longHomePanel = () -> {
        longHomeOpenedPanel = true;
        RemoteControlOverlay.toggle(this);
    };
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener mappingChanges = (prefs, key) -> {
        if (key == null || "remote_mappings".equals(key)) reloadAppKeys();
    };

    private void reloadAppKeys() {
        Config config = Config.load(this);
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        holdAppKeys = RemoteKeys.parseHoldAppKeys(config.appKeys);
        holdAppScancodes = RemoteKeys.parseHoldAppScancodes(config.appKeys);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        featureChoices = FeaturePreferences.prefs(this);
        featureChoices.registerOnSharedPreferenceChangeListener(mappingChanges);
        // From config.xml or a theme's config.json; theme.sh rebinds this service after a push.
        Config config = Config.load(this);
        // restore_key_services: note that this helper runs, so Home can put it back after a force stop.
        repairRecord = config.restoreKeyServices;
        if (repairRecord) ServiceRepair.connected(this, getClass());
        inputKeycodes = config.inputKeycodes;
        consumeVolumeKeys = config.consumeVolumeKeys;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        RemoteKeys.applyKeyFilter(this, config.keyFilter);
        holdAppKeys = RemoteKeys.parseHoldAppKeys(config.appKeys);
        holdAppScancodes = RemoteKeys.parseHoldAppScancodes(config.appKeys);
        // gamepad_passthrough, off by default. Only with key_filter on: off, the service never filters keys.
        if (config.gamepadPassthrough && config.keyFilter && gamepads == null) {
            gamepads = new GamepadPassthrough(this);
            gamepads.start();
        }
    }

    private boolean isInputKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_TV || keyCode == KeyEvent.KEYCODE_TV_INPUT) return true;
        for (int code : inputKeycodes) if (keyCode == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (RemoteLearning.handle(event)) return true;
        if (RemotePanelKeys.handle(this, event)) return true;
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME
                && featureChoices.getBoolean(RemotePanelKeys.LONG_HOME, false)
                && !HomeKeyService.canOpenPowerMenu()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                longHomeOpenedPanel = false;
                handler.removeCallbacks(longHomePanel);
                handler.postDelayed(longHomePanel, ViewConfiguration.getLongPressTimeout());
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(longHomePanel);
                if (!event.isCanceled() && !longHomeOpenedPanel) {
                    try {
                        startActivity(new Intent(this, MainActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                    } catch (ActivityNotFoundException | SecurityException ignored) { }
                }
                longHomeOpenedPanel = false;
            }
            return true;
        }
        if (!(event.getKeyCode() == KeyEvent.KEYCODE_HOME
                && featureChoices.getBoolean(RemotePanelKeys.LONG_HOME, false))
                && RemoteButtonMapper.handle(this, event)) return true;
        if (consumeVolumeKeys && VolumeKeys.handle(this, event)) return true;
        // A scancode entry is more specific than any keycode, so it goes first.
        if (RemoteKeys.handle(this, event, appScancodes.get(event.getScanCode()),
                holdAppScancodes.get(event.getScanCode()))) return true;
        if (RemoteKeys.handle(this, event, appKeys.get(event.getKeyCode()),
                holdAppKeys.get(event.getKeyCode()))) return true;
        if (!isInputKey(event.getKeyCode())) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            try {
                startActivity(new Intent(ACTION_VIEW_INPUTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ignored) {
            }
        }
        return true;
    }

    /** Unbound by Android: the user turned the helper off, so Home doesn't put it back (ServiceRepair). */
    @Override
    public boolean onUnbind(Intent intent) {
        handler.removeCallbacks(longHomePanel);
        RemoteControlOverlay.closeFor(this);
        connected = false;
        RemoteButtonMapper.cancelPending();
        if (repairRecord) ServiceRepair.disconnected(this, getClass());
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(longHomePanel);
        RemoteControlOverlay.closeFor(this);
        connected = false;
        RemoteButtonMapper.cancelPending();
        if (featureChoices != null) featureChoices.unregisterOnSharedPreferenceChangeListener(mappingChanges);
        if (gamepads != null) gamepads.stop();
        if (consumeVolumeKeys) VolumeKeys.stop();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
