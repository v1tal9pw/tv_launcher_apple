package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SettingsSliderTest {
    @Test public void stepsByFiveWithinZeroToHundred() {
        assertEquals(5, SettingsSliderView.stepValue(0, 1, 0, 100, 5));
        assertEquals(95, SettingsSliderView.stepValue(100, -1, 0, 100, 5));
        assertEquals(55, SettingsSliderView.stepValue(50, 1, 0, 100, 5));
        assertEquals(45, SettingsSliderView.stepValue(50, -1, 0, 100, 5));
    }

    @Test public void clampsAtRangeBoundaries() {
        assertEquals(0, SettingsSliderView.stepValue(0, -1, 0, 100, 5));
        assertEquals(100, SettingsSliderView.stepValue(100, 1, 0, 100, 5));
        assertEquals(0, SettingsSliderView.stepValue(-10, 0, 0, 100, 5));
        assertEquals(100, SettingsSliderView.stepValue(120, 0, 0, 100, 5));
    }

    @Test public void snapsLegacyOffGridValuesOntoFivePercentGrid() {
        assertEquals(35, SettingsSliderView.stepValue(33, 1, 0, 100, 5));
        assertEquals(30, SettingsSliderView.stepValue(33, -1, 0, 100, 5));
        assertEquals(10, SettingsSliderView.stepValue(12, -1, 0, 100, 5));
        assertEquals(15, SettingsSliderView.stepValue(12, 1, 0, 100, 5));
    }

    @Test public void tileGlowIntensityExplicitValueIsIndependentOfBackgroundGlow() {
        assertEquals(65, Config.resolveTileGlowIntensity(65, false, 0));
        assertEquals(0, Config.resolveTileGlowIntensity(0, true, 100));
        assertEquals(100, Config.resolveTileGlowIntensity(100, true, 0));
    }

    @Test public void tileGlowIntensityFallsBackToLegacyPreferences() {
        assertEquals(80, Config.resolveTileGlowIntensity(-1, true, 80));
        assertEquals(0, Config.resolveTileGlowIntensity(-1, false, 80));
        assertEquals(0, Config.resolveTileGlowIntensity(-1, true, 0));
        assertEquals(100, Config.resolveTileGlowIntensity(-1, true, 150));
    }
}
