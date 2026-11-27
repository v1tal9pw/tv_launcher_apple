package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NewFeaturesTest {

    @Test
    public void sleepTimerCyclesPresetsAndComputesRemainingMinutes() {
        assertEquals(15, SleepTimer.nextPreset(0));
        assertEquals(30, SleepTimer.nextPreset(15));
        assertEquals(60, SleepTimer.nextPreset(30));
        assertEquals(90, SleepTimer.nextPreset(60));
        assertEquals(0, SleepTimer.nextPreset(90));
        assertEquals(15, SleepTimer.nextPreset(42));

        assertEquals(0, SleepTimer.remainingMinutes(0L, 1000L));
        assertEquals(0, SleepTimer.remainingMinutes(5000L, 5000L));
        assertEquals(1, SleepTimer.remainingMinutes(10_000L, 9_500L));
        assertEquals(15, SleepTimer.remainingMinutes(15 * 60_000L, 0L));
        assertEquals(16, SleepTimer.remainingMinutes(15 * 60_000L + 1L, 0L));
    }

    @Test
    public void nightAutoDimActivatesOnlyAfterSunsetOrBeforeSunrise() {
        int sunrise = 6 * 60 + 30; // 06:30
        int sunset = 19 * 60 + 45; // 19:45

        assertEquals(0, WallpaperPlayer.nightDimExtraPercent(false, 23 * 60, sunrise, sunset));
        assertEquals(0, WallpaperPlayer.nightDimExtraPercent(true, 12 * 60, sunrise, sunset));
        assertEquals(0, WallpaperPlayer.nightDimExtraPercent(true, sunrise, sunrise, sunset));
        assertEquals(WallpaperPlayer.NIGHT_DIM_EXTRA_PERCENT,
                WallpaperPlayer.nightDimExtraPercent(true, sunset, sunrise, sunset));
        assertEquals(WallpaperPlayer.NIGHT_DIM_EXTRA_PERCENT,
                WallpaperPlayer.nightDimExtraPercent(true, 23 * 60 + 30, sunrise, sunset));
        assertEquals(WallpaperPlayer.NIGHT_DIM_EXTRA_PERCENT,
                WallpaperPlayer.nightDimExtraPercent(true, 3 * 60, sunrise, sunset));

        // Polar / invalid sunrise-sunset falls back to 21:00..06:00.
        assertEquals(0, WallpaperPlayer.nightDimExtraPercent(true, 15 * 60, -1, -1));
        assertEquals(WallpaperPlayer.NIGHT_DIM_EXTRA_PERCENT,
                WallpaperPlayer.nightDimExtraPercent(true, 22 * 60, -1, -1));

        // Overlay alpha clamps total dimming to <= 85%.
        assertEquals(51, WallpaperPlayer.effectiveNightOverlayAlpha(0, 20));
        assertTrue(WallpaperPlayer.effectiveNightOverlayAlpha(75, 20) > 0);
        assertEquals(0, WallpaperPlayer.effectiveNightOverlayAlpha(85, 20));
        assertEquals(0, WallpaperPlayer.effectiveNightOverlayAlpha(20, 0));
    }

    @Test
    public void settingsBackupTransientKeyFilterProtectsCachesAndKeepsUserChoices() {
        assertTrue(SettingsBackup.isTransientKey(null));
        assertTrue(SettingsBackup.isTransientKey("release_available_code"));
        assertTrue(SettingsBackup.isTransientKey("release_notified_code"));
        assertTrue(SettingsBackup.isTransientKey("weather_cache_temp"));
        assertTrue(SettingsBackup.isTransientKey("weather_last_updated"));
        assertTrue(SettingsBackup.isTransientKey("settings_changed_at"));

        assertFalse(SettingsBackup.isTransientKey("columns"));
        assertFalse(SettingsBackup.isTransientKey("wallpaper_night_dim"));
        assertFalse(SettingsBackup.isTransientKey("glow_intensity"));
        assertFalse(SettingsBackup.isTransientKey("city"));
        assertFalse(SettingsBackup.isTransientKey("remote_mappings"));
    }

    @Test
    public void updateBannerOnlyShowsOncePerReleaseCode() {
        assertTrue(ReleaseUpdater.shouldShowUpdateBanner(77, 0));
        assertTrue(ReleaseUpdater.shouldShowUpdateBanner(77, 76));
        assertFalse(ReleaseUpdater.shouldShowUpdateBanner(77, 77));
        assertFalse(ReleaseUpdater.shouldShowUpdateBanner(76, 77));
        assertFalse(ReleaseUpdater.shouldShowUpdateBanner(0, 0));
    }

    @Test
    public void forceStopProtectsLauncherAndCoreSystemApps() {
        String self = "com.gathin.tvhome";
        assertFalse(QuickSettingsActions.canForceStopApp(self, self));
        assertFalse(QuickSettingsActions.canForceStopApp("com.android.systemui", self));
        assertFalse(QuickSettingsActions.canForceStopApp("com.android.vending", self));
        assertFalse(QuickSettingsActions.canForceStopApp("com.tcl.settings", self));
        assertFalse(QuickSettingsActions.canForceStopApp("bad;rm -rf /", self));
        assertTrue(QuickSettingsActions.canForceStopApp("com.google.android.youtube.tv", self));
        assertTrue(QuickSettingsActions.canForceStopApp("ru.kinopoisk.tv", self));
    }
}
