package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** Adapted from ButtonMapper in techgenius1/tvOS-Launcher (Apache-2.0).
 * Changes: existing launcher preferences/actions, no ADB or permission changes.
 * Attribution and license: assets/licenses/tvos-launcher-NOTICE.txt and tvos-launcher-LICENSE.txt. */
final class RemoteButtonMapper {
    static final String HOME_FIX = "remote_home_fix";
    static final String TAPS = "remote_taps";
    static final String HOME = "launcher:home", CONTROL = "launcher:control", INPUTS = "launcher:inputs";
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Map<String, Integer> counts = new HashMap<>();
    private static final Map<String, Runnable> pending = new HashMap<>();

    static String target(Context context, String button, int taps) {
        JSONObject choices = FeaturePreferences.object(FeaturePreferences.prefs(context).getString(TAPS, "{}"));
        String key = button + ":" + taps;
        if (choices.has(key)) return choices.optString(key, "");
        return taps == 1 ? FeaturePreferences.object(FeaturePreferences.prefs(context)
                .getString("remote_mappings", "{}")).optString(button, "") : "";
    }

    static Set<String> buttons(Context context) {
        Set<String> result = new LinkedHashSet<>();
        JSONObject old = FeaturePreferences.object(FeaturePreferences.prefs(context).getString("remote_mappings", "{}"));
        java.util.Iterator<String> keys = old.keys();
        while (keys.hasNext()) result.add(keys.next());
        JSONObject choices = FeaturePreferences.object(FeaturePreferences.prefs(context).getString(TAPS, "{}"));
        keys = choices.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            int split = key.lastIndexOf(':');
            if (split > 0 && !choices.optString(key).isEmpty()) result.add(key.substring(0, split));
        }
        return result;
    }

    static void save(Context context, String button, int taps, String target) {
        cancelPending();
        if (taps == 1) FeaturePreferences.putApp(context, "remote_mappings", button, null);
        FeaturePreferences.putApp(context, TAPS, button + ":" + taps, target);
    }

    static boolean handle(Context context, KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_HOME && FeaturePreferences.prefs(context).getBoolean(HOME_FIX, false)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                main.post(() -> launch(context.getApplicationContext(), HOME));
            return true;
        }
        if (RemoteLearning.reserved(code)) return false;
        String button = RemoteLearning.buttonId(code, event.getScanCode());
        if (!buttons(context).contains(button) && event.getScanCode() > 0) button = "scan:" + event.getScanCode();
        String single = target(context, button, 1), twice = target(context, button, 2), triple = target(context, button, 3);
        int maximum = TapSequence.maximum(single, twice, triple);
        if (maximum == 0) return false;
        if (event.isCanceled()) { cancelPending(); return true; }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            String key = button;
            Runnable previous = pending.remove(key);
            if (previous != null) main.removeCallbacks(previous);
            int count = counts.containsKey(key) ? counts.get(key) + 1 : 1;
            counts.put(key, count);
            Runnable dispatch = () -> {
                pending.remove(key); counts.remove(key);
                launch(context.getApplicationContext(), TapSequence.target(count, single, twice, triple));
            };
            pending.put(key, dispatch);
            main.postDelayed(dispatch, count >= maximum ? 0 : 300);
        }
        return true;
    }

    static void cancelPending() {
        for (Runnable action : pending.values()) main.removeCallbacks(action);
        pending.clear(); counts.clear();
    }

    private static void launch(Context context, String target) {
        if (target.isEmpty()) return;
        if (HOME.equals(target) || CONTROL.equals(target) || INPUTS.equals(target)) {
            String action = CONTROL.equals(target) ? MainActivity.ACTION_QUICK_SETTINGS
                    : INPUTS.equals(target) ? MainActivity.ACTION_VIEW_INPUTS : Intent.ACTION_MAIN;
            try {
                context.startActivity(new Intent(context, MainActivity.class).setAction(action)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            } catch (android.content.ActivityNotFoundException | SecurityException ignored) { }
        } else RemoteKeys.launch(context, target);
    }
}
