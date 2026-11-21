package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.Equalizer;
import android.util.Log;

import java.util.Locale;

/**
 * Manages Android's built-in system Equalizer attached to the global output mix (audio session 0),
 * persisting user presets and per-band levels across launcher sessions.
 *
 * Legacy BassBoost is intentionally omitted because attaching it to session 0 disrupts wireless
 * Dolby Atmos FlexConnect / external subwoofers (e.g. TCL Z100SW). Low frequencies are adjusted
 * safely through the Equalizer's own 60 Hz / 230 Hz bands instead.
 */
final class SystemEqualizer {
    private static final String TAG = "SystemEqualizer";
    static final String PREFS = "system_equalizer";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_PRESET = "preset";
    static final String KEY_PRESET_NAME = "preset_name";
    static final String KEY_BAND_PREFIX = "band_";
    private static final String LEGACY_BASS_BOOST_KEY = "bass_boost";
    static final int PRESET_CUSTOM = -1;
    static final int STEP_MB = 100; // 1 dB per D-pad step

    private static Equalizer equalizer;

    static final class Snapshot {
        final boolean available;
        final boolean enabled;
        final short minLevelMb;
        final short maxLevelMb;
        final int[] centerFreqsHz;
        final short[] bandLevelsMb;
        final String[] presetNames;
        final int presetIndex;

        Snapshot(boolean available, boolean enabled, short minLevelMb, short maxLevelMb,
                 int[] centerFreqsHz, short[] bandLevelsMb, String[] presetNames, int presetIndex) {
            this.available = available;
            this.enabled = enabled;
            this.minLevelMb = minLevelMb;
            this.maxLevelMb = maxLevelMb;
            this.centerFreqsHz = centerFreqsHz;
            this.bandLevelsMb = bandLevelsMb;
            this.presetNames = presetNames;
            this.presetIndex = presetIndex;
        }
    }

    private SystemEqualizer() { }

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    /** Fast summary for the settings row without touching AudioFlinger. */
    static String summary(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.getBoolean(KEY_ENABLED, false)) return context.getString(R.string.off);
        int preset = p.getInt(KEY_PRESET, PRESET_CUSTOM);
        if (preset >= 0) {
            String raw = p.getString(KEY_PRESET_NAME, "");
            if (!raw.isEmpty()) return presetLabel(context, raw);
        }
        return context.getString(R.string.settings_custom);
    }

    /** Restores saved equalizer state if enabled; does nothing when disabled. */
    static synchronized void applySaved(Context context) {
        clearLegacyBassBoost(context);
        if (!isEnabled(context)) {
            release();
            return;
        }
        if (!ensureEqualizer()) return;
        applyPreferencesToEffects(context, true);
    }

    /** Opens or reuses the built-in Android Equalizer so the settings UI can inspect & edit bands. */
    static synchronized Snapshot openForSettings(Context context) {
        clearLegacyBassBoost(context);
        if (!ensureEqualizer()) {
            return unavailable();
        }
        boolean enabled = isEnabled(context);
        applyPreferencesToEffects(context, enabled);
        return capture(context, enabled);
    }

    /** Releases the audio effect when the settings screen closes while the equalizer is turned off. */
    static synchronized void releaseIfDisabled(Context context) {
        if (!isEnabled(context)) release();
    }

    static synchronized Snapshot setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).remove(LEGACY_BASS_BOOST_KEY).apply();
        if (!ensureEqualizer()) return unavailable();
        applyPreferencesToEffects(context, enabled);
        return capture(context, enabled);
    }

    static synchronized Snapshot selectPreset(Context context, int presetIndex) {
        if (!ensureEqualizer()) return unavailable();
        SharedPreferences p = prefs(context);
        SharedPreferences.Editor edit = p.edit();
        int presetCount = safePresetCount();
        if (presetIndex >= 0 && presetIndex < presetCount) {
            try {
                equalizer.usePreset((short) presetIndex);
                edit.putInt(KEY_PRESET, presetIndex);
                edit.putString(KEY_PRESET_NAME, safePresetName(presetIndex));
                int bands = safeBandCount();
                for (int i = 0; i < bands; i++) {
                    edit.putInt(KEY_BAND_PREFIX + i, equalizer.getBandLevel((short) i));
                }
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to apply preset " + presetIndex, error);
            }
        } else {
            edit.putInt(KEY_PRESET, PRESET_CUSTOM);
            edit.remove(KEY_PRESET_NAME);
        }
        edit.apply();
        boolean enabled = p.getBoolean(KEY_ENABLED, false);
        applyPreferencesToEffects(context, enabled);
        return capture(context, enabled);
    }

    static synchronized Snapshot adjustBand(Context context, int bandIndex, int deltaMb) {
        if (!ensureEqualizer()) return unavailable();
        short[] range = safeLevelRange();
        int current = prefs(context).getInt(KEY_BAND_PREFIX + bandIndex, safeBandLevel(bandIndex));
        short next = clampLevel(current + deltaMb, range[0], range[1]);
        return setBandLevel(context, bandIndex, next);
    }

    static synchronized Snapshot setBandLevel(Context context, int bandIndex, int levelMb) {
        if (!ensureEqualizer()) return unavailable();
        short[] range = safeLevelRange();
        short clamped = clampLevel(levelMb, range[0], range[1]);
        prefs(context).edit()
                .putInt(KEY_PRESET, PRESET_CUSTOM)
                .remove(KEY_PRESET_NAME)
                .putInt(KEY_BAND_PREFIX + bandIndex, clamped)
                .apply();
        boolean enabled = isEnabled(context);
        try {
            equalizer.setBandLevel((short) bandIndex, clamped);
            equalizer.setEnabled(enabled);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to set band " + bandIndex + " to " + clamped, error);
        }
        return capture(context, enabled);
    }

    static synchronized Snapshot resetFlat(Context context) {
        if (!ensureEqualizer()) return unavailable();
        int bands = safeBandCount();
        SharedPreferences.Editor edit = prefs(context).edit()
                .putInt(KEY_PRESET, PRESET_CUSTOM)
                .remove(KEY_PRESET_NAME)
                .remove(LEGACY_BASS_BOOST_KEY);
        for (int i = 0; i < bands; i++) edit.putInt(KEY_BAND_PREFIX + i, 0);
        edit.apply();
        boolean enabled = isEnabled(context);
        applyPreferencesToEffects(context, enabled);
        return capture(context, enabled);
    }

    /** Optional system AudioEffect control panel, if the TV firmware ships one. */
    static Intent systemPanelIntent(Context context) {
        Intent intent = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);
        intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
        intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MOVIE);
        intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0);
        return intent.resolveActivity(context.getPackageManager()) != null ? intent : null;
    }

    static synchronized void release() {
        if (equalizer != null) {
            try { equalizer.setEnabled(false); } catch (Throwable ignored) { }
            try { equalizer.release(); } catch (Throwable ignored) { }
            equalizer = null;
        }
    }

    private static void clearLegacyBassBoost(Context context) {
        SharedPreferences p = prefs(context);
        if (p.contains(LEGACY_BASS_BOOST_KEY)) p.edit().remove(LEGACY_BASS_BOOST_KEY).apply();
    }

    private static boolean ensureEqualizer() {
        if (equalizer != null) {
            try {
                if (equalizer.hasControl()) return true;
                equalizer.release();
            } catch (Throwable ignored) {
            }
            equalizer = null;
        }
        try {
            equalizer = new Equalizer(0, 0);
            return equalizer.getNumberOfBands() > 0;
        } catch (Throwable error) {
            Log.w(TAG, "Built-in Equalizer unavailable on session 0", error);
            equalizer = null;
            return false;
        }
    }

    private static void applyPreferencesToEffects(Context context, boolean enabled) {
        if (equalizer == null) return;
        SharedPreferences p = prefs(context);
        int bands = safeBandCount();
        short[] range = safeLevelRange();
        int preset = p.getInt(KEY_PRESET, PRESET_CUSTOM);
        int presetCount = safePresetCount();
        try {
            if (preset >= 0 && preset < presetCount) {
                equalizer.usePreset((short) preset);
            } else {
                for (int i = 0; i < bands; i++) {
                    short level = clampLevel(p.getInt(KEY_BAND_PREFIX + i, 0), range[0], range[1]);
                    equalizer.setBandLevel((short) i, level);
                }
            }
            equalizer.setEnabled(enabled);
        } catch (Throwable error) {
            Log.w(TAG, "Failed applying equalizer settings", error);
        }
    }

    private static Snapshot capture(Context context, boolean enabled) {
        SharedPreferences p = prefs(context);
        int bands = safeBandCount();
        short[] range = safeLevelRange();
        int[] freqs = new int[bands];
        short[] levels = new short[bands];
        for (int i = 0; i < bands; i++) {
            try {
                freqs[i] = Math.max(1, equalizer.getCenterFreq((short) i) / 1000);
            } catch (RuntimeException ignored) {
                freqs[i] = 0;
            }
            int fallback = safeBandLevel(i);
            int saved = p.contains(KEY_BAND_PREFIX + i) ? p.getInt(KEY_BAND_PREFIX + i, fallback) : fallback;
            levels[i] = clampLevel(saved, range[0], range[1]);
        }
        int presetCount = safePresetCount();
        String[] presets = new String[presetCount];
        for (int i = 0; i < presetCount; i++) presets[i] = safePresetName(i);
        int preset = p.getInt(KEY_PRESET, PRESET_CUSTOM);
        if (preset >= presetCount) preset = PRESET_CUSTOM;
        return new Snapshot(true, enabled, range[0], range[1], freqs, levels, presets, preset);
    }

    private static Snapshot unavailable() {
        return new Snapshot(false, false, (short) -1500, (short) 1500, new int[0], new short[0],
                new String[0], PRESET_CUSTOM);
    }

    private static int safeBandCount() {
        try { return equalizer == null ? 0 : Math.max(0, equalizer.getNumberOfBands()); }
        catch (RuntimeException ignored) { return 0; }
    }

    private static int safePresetCount() {
        try { return equalizer == null ? 0 : Math.max(0, equalizer.getNumberOfPresets()); }
        catch (RuntimeException ignored) { return 0; }
    }

    private static String safePresetName(int index) {
        try {
            String name = equalizer == null ? "" : equalizer.getPresetName((short) index);
            return name == null ? "" : name.trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static short safeBandLevel(int band) {
        try { return equalizer == null ? 0 : equalizer.getBandLevel((short) band); }
        catch (RuntimeException ignored) { return 0; }
    }

    private static short[] safeLevelRange() {
        try {
            short[] range = equalizer == null ? null : equalizer.getBandLevelRange();
            if (range != null && range.length == 2 && range[0] < range[1]) return range;
        } catch (RuntimeException ignored) {
        }
        return new short[]{(short) -1500, (short) 1500};
    }

    static short clampLevel(int levelMb, short minMb, short maxMb) {
        return (short) Math.max(minMb, Math.min(maxMb, levelMb));
    }

    static String formatFrequency(int hz, String hzUnit, String khzUnit) {
        if (hz >= 1000) {
            int tenths = Math.round(hz / 100f);
            if (tenths % 10 == 0) return (tenths / 10) + " " + khzUnit;
            return String.format(Locale.US, "%.1f %s", tenths / 10f, khzUnit);
        }
        return hz + " " + hzUnit;
    }

    static String formatShortFrequency(int hz) {
        if (hz >= 1000) {
            int tenths = Math.round(hz / 100f);
            return tenths % 10 == 0 ? (tenths / 10) + "k" : String.format(Locale.US, "%.1fk", tenths / 10f);
        }
        return String.valueOf(hz);
    }

    static String formatDb(int millibels, String dbUnit) {
        int db = Math.round(millibels / 100f);
        if (db > 0) return "+" + db + " " + dbUnit;
        return db + " " + dbUnit;
    }

    static final int ZONE_LOW = 0;
    static final int ZONE_MID = 1;
    static final int ZONE_HIGH = 2;

    static int bandZone(int hz) {
        if (hz <= 250) return ZONE_LOW;
        if (hz <= 2000) return ZONE_MID;
        return ZONE_HIGH;
    }

    static int zoneTitleRes(int zone) {
        if (zone == ZONE_LOW) return R.string.equalizer_zone_low;
        if (zone == ZONE_MID) return R.string.equalizer_zone_mid;
        return R.string.equalizer_zone_high;
    }

    static int zoneAccentColor(int zone) {
        if (zone == ZONE_LOW) return 0xFF0A84FF;
        if (zone == ZONE_MID) return 0xFF30D158;
        return 0xFF64D2FF;
    }

    static int bandRoleRes(int hz) {
        if (hz <= 100) return R.string.eq_role_sub_bass;
        if (hz <= 250) return R.string.eq_role_bass;
        if (hz <= 550) return R.string.eq_role_low_mid;
        if (hz <= 2000) return R.string.eq_role_vocals;
        if (hz <= 6000) return R.string.eq_role_clarity;
        return R.string.eq_role_brilliance;
    }

    static int bandHintRes(int hz) {
        if (hz <= 100) return R.string.eq_hint_sub_bass;
        if (hz <= 250) return R.string.eq_hint_bass;
        if (hz <= 550) return R.string.eq_hint_low_mid;
        if (hz <= 2000) return R.string.eq_hint_vocals;
        if (hz <= 6000) return R.string.eq_hint_clarity;
        return R.string.eq_hint_brilliance;
    }

    static String presetLabel(Context context, String rawName) {
        int res = localizedPresetRes(rawName);
        return res != 0 ? context.getString(res) : rawName;
    }

    static int localizedPresetRes(String rawName) {
        if (rawName == null) return 0;
        switch (rawName.trim().toLowerCase(Locale.ROOT)) {
            case "normal": return R.string.eq_preset_normal;
            case "classical": return R.string.eq_preset_classical;
            case "dance": return R.string.eq_preset_dance;
            case "flat": return R.string.eq_preset_flat;
            case "folk": return R.string.eq_preset_folk;
            case "heavy metal": return R.string.eq_preset_heavy_metal;
            case "hip hop": return R.string.eq_preset_hip_hop;
            case "jazz": return R.string.eq_preset_jazz;
            case "pop": return R.string.eq_preset_pop;
            case "rock": return R.string.eq_preset_rock;
            case "fx booster": return R.string.eq_preset_fx_booster;
            default: return 0;
        }
    }
}
