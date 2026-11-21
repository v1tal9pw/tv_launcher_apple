package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public final class SystemEqualizerTest {
    @Test
    public void formatsFrequenciesInHzAndKhz() {
        assertEquals("60 Гц", SystemEqualizer.formatFrequency(60, "Гц", "кГц"));
        assertEquals("230 Гц", SystemEqualizer.formatFrequency(230, "Гц", "кГц"));
        assertEquals("910 Гц", SystemEqualizer.formatFrequency(910, "Гц", "кГц"));
        assertEquals("1 кГц", SystemEqualizer.formatFrequency(1000, "Гц", "кГц"));
        assertEquals("3.6 кГц", SystemEqualizer.formatFrequency(3600, "Гц", "кГц"));
        assertEquals("14 кГц", SystemEqualizer.formatFrequency(14000, "Гц", "кГц"));
        assertEquals("60", SystemEqualizer.formatShortFrequency(60));
        assertEquals("3.6k", SystemEqualizer.formatShortFrequency(3600));
        assertEquals("14k", SystemEqualizer.formatShortFrequency(14000));
    }

    @Test
    public void formatsDecibelsWithSign() {
        assertEquals("+4 дБ", SystemEqualizer.formatDb(400, "дБ"));
        assertEquals("0 дБ", SystemEqualizer.formatDb(0, "дБ"));
        assertEquals("-6 дБ", SystemEqualizer.formatDb(-600, "дБ"));
    }

    @Test
    public void clampsBandLevelsAndBassBoost() {
        assertEquals((short) -1500, SystemEqualizer.clampLevel(-2500, (short) -1500, (short) 1500));
        assertEquals((short) 1500, SystemEqualizer.clampLevel(3000, (short) -1500, (short) 1500));
        assertEquals((short) 300, SystemEqualizer.clampLevel(300, (short) -1500, (short) 1500));
        assertEquals(0, SystemEqualizer.clampBassPercent(-20));
        assertEquals(100, SystemEqualizer.clampBassPercent(150));
        assertEquals(40, SystemEqualizer.clampBassPercent(40));
    }

    @Test
    public void mapsKnownAndroidPresetsToLocalizedResources() {
        assertNotEquals(0, SystemEqualizer.localizedPresetRes("Normal"));
        assertNotEquals(0, SystemEqualizer.localizedPresetRes("Rock"));
        assertNotEquals(0, SystemEqualizer.localizedPresetRes("Jazz"));
        assertNotEquals(0, SystemEqualizer.localizedPresetRes("Heavy Metal"));
        assertNotEquals(0, SystemEqualizer.localizedPresetRes("  hip hop "));
        assertEquals(0, SystemEqualizer.localizedPresetRes("Custom OEM Preset"));
        assertEquals(0, SystemEqualizer.localizedPresetRes(null));
    }
}
