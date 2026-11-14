package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.util.Collections;
import java.util.Map;

/** Upstream input/app-button helper; Home and Settings gestures live in HomeKeyService. */
public class InputKeyService extends AccessibilityService {
    private static final String TAG = "RemoteButtonHelper";
    private static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";
    static boolean connected;
    static InputKeyService running;

    private int[] inputKeycodes = new int[0];
    private Map<Integer, String> appKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();
    private Map<Integer, String> holdAppKeys = Collections.emptyMap();
    private Map<Integer, String> holdAppScancodes = Collections.emptyMap();
    private Map<Integer, String> frontAppKeys = Collections.emptyMap();
    private Map<Integer, String> frontAppScancodes = Collections.emptyMap();
    private boolean repairRecord;
    private boolean consumeVolumeKeys;
    private GamepadPassthrough gamepads;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        running = this;
        Log.i(TAG, "InputKeyService connected");
        Config config = Config.load(this);
        repairRecord = config.restoreKeyServices;
        if (repairRecord) ServiceRepair.connected(this, getClass());
        inputKeycodes = config.inputKeycodes;
        consumeVolumeKeys = config.consumeVolumeKeys;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        RemoteKeys.applyKeyFilter(this, config.keyFilter);
        holdAppKeys = RemoteKeys.parseHoldAppKeys(config.appKeys);
        holdAppScancodes = RemoteKeys.parseHoldAppScancodes(config.appKeys);
        frontAppKeys = RemoteKeys.parseFrontAppKeys(config.appKeys);
        frontAppScancodes = RemoteKeys.parseFrontAppScancodes(config.appKeys);
        if (config.gamepadPassthrough) {
            gamepads = new GamepadPassthrough(this);
            gamepads.start();
        }
    }

    private boolean isInputKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_TV || keyCode == KeyEvent.KEYCODE_TV_INPUT) return true;
        for (int code : inputKeycodes) if (keyCode == code) return true;
        return false;
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        try {
            if (consumeVolumeKeys && VolumeKeys.handle(this, event)) return true;
            if (RemoteKeys.handle(this, event, RemoteKeys.tap(appScancodes.get(event.getScanCode()),
                    frontAppScancodes.get(event.getScanCode())),
                    holdAppScancodes.get(event.getScanCode()))) return true;
            if (RemoteKeys.handle(this, event, RemoteKeys.tap(appKeys.get(event.getKeyCode()),
                    frontAppKeys.get(event.getKeyCode())),
                    holdAppKeys.get(event.getKeyCode()))) return true;
            if (!isInputKey(event.getKeyCode())) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                try {
                    startActivity(new Intent(ACTION_VIEW_INPUTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException | SecurityException unavailable) {
                    Log.w(TAG, "No TV input activity", unavailable);
                }
            }
            return true;
        } catch (RuntimeException error) {
            Log.e(TAG, "Input key handling failed", error);
            return false;
        }
    }

    @Override public boolean onUnbind(Intent intent) {
        connected = false;
        RemoteKeys.release(this);
        if (repairRecord) ServiceRepair.disconnected(this, getClass());
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        connected = false;
        if (running == this) running = null;
        RemoteKeys.release(this);
        if (gamepads != null) gamepads.stop();
        if (consumeVolumeKeys) VolumeKeys.stop();
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}
