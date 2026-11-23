package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QuickSoundRoutingTest {
    @Test
    public void pictureOnlyAndCombinedDisplaySoundClassesAreDetected() {
        assertTrue(QuickSettingsActions.isPictureOnlyClassName("com.tcl.settings.PictureActivity"));
        assertTrue(QuickSettingsActions.isPictureOnlyClassName(
                "com.android.tv.settings.partnercustomizer.picture.PictureActivity"));
        assertTrue(QuickSettingsActions.isPictureOnlyClassName("com.tcl.settings.DisplayActivity"));
        assertTrue(QuickSettingsActions.isPictureOnlyClassName("com.tcl.settings.ScreenSettingsActivity"));
        assertFalse(QuickSettingsActions.isPictureOnlyClassName("com.tcl.settings.DisplayAndSoundActivity"));
        assertFalse(QuickSettingsActions.isPictureOnlyClassName("com.tcl.settings.SoundActivity"));
        assertFalse(QuickSettingsActions.isPictureOnlyClassName("com.tcl.settings.AudioSettingsActivity"));

        assertTrue(QuickSettingsActions.isCombinedDisplayAndSoundClassName("com.tcl.settings.DisplayAndSoundActivity"));
        assertTrue(QuickSettingsActions.isCombinedDisplayAndSoundClassName(
                "com.android.tv.settings.device.displaysound.DisplaySoundActivity"));
        assertTrue(QuickSettingsActions.isCombinedDisplayAndSoundClassName("com.tcl.settings.PictureAndSoundActivity"));
        assertTrue(QuickSettingsActions.isCombinedDisplayAndSoundClassName("com.tcl.settings.VideoAndAudioActivity"));
        assertFalse(QuickSettingsActions.isCombinedDisplayAndSoundClassName("com.tcl.settings.SoundActivity"));
        assertFalse(QuickSettingsActions.isCombinedDisplayAndSoundClassName("com.tcl.settings.PictureActivity"));
    }

    @Test
    public void soundOnlyPreferenceAndSliceKeysExcludeDisplayAndSound() {
        assertTrue(QuickSettingsActions.isSoundOnlyPreferenceKey("sound"));
        assertTrue(QuickSettingsActions.isSoundOnlyPreferenceKey("audio"));
        assertTrue(QuickSettingsActions.isSoundOnlyPreferenceKey("sound_settings"));
        assertTrue(QuickSettingsActions.isSoundOnlyPreferenceKey("tcl_sound"));
        assertTrue(QuickSettingsActions.isSoundOnlyPreferenceKey("device_audio_settings"));
        assertFalse(QuickSettingsActions.isSoundOnlyPreferenceKey("display_and_sound"));
        assertFalse(QuickSettingsActions.isSoundOnlyPreferenceKey("picture"));
        assertFalse(QuickSettingsActions.isSoundOnlyPreferenceKey("sound_effects"));
        assertFalse(QuickSettingsActions.isSoundOnlyPreferenceKey("system_sounds"));
        assertFalse(QuickSettingsActions.isSoundOnlyPreferenceKey("default_audio_output"));
        assertFalse(QuickSettingsActions.isSoundOnlyPreferenceKey("audio_only"));
        assertFalse(QuickSettingsActions.isSoundOnlyPreferenceKey("advanced_sound_settings"));
        assertFalse(QuickSettingsActions.isSoundOnlyPreferenceKey("sound_slice"));

        assertTrue(QuickSettingsActions.isSoundOnlySliceKey("sound_slice"));
        assertTrue(QuickSettingsActions.isSoundOnlySliceKey("audio_slice"));
        assertTrue(QuickSettingsActions.isSoundOnlySliceKey("sound_settings_slice"));
        assertFalse(QuickSettingsActions.isSoundOnlySliceKey("display_and_sound_slice"));
        assertFalse(QuickSettingsActions.isSoundOnlySliceKey("audio_output_slice"));
        assertFalse(QuickSettingsActions.isSoundOnlySliceKey("sound"));
    }

    @Test
    public void pureSoundActivityWinsAndRejectsPictureAndCombinedDisplaySound() {
        String displayPkg = "com.tcl.settings";
        String displayClass = "com.tcl.settings.PictureActivity";

        int picture = QuickSettingsActions.scoreSoundActivityName(
                displayPkg, displayClass, displayPkg, displayClass);
        int displayAndSound = QuickSettingsActions.scoreSoundActivityName(
                displayPkg, "com.tcl.settings.DisplayAndSoundActivity", displayPkg, displayClass);
        int sound = QuickSettingsActions.scoreSoundActivityName(
                displayPkg, "com.tcl.settings.SoundActivity", displayPkg, displayClass);
        int quickSound = QuickSettingsActions.scoreSoundActivityName(
                displayPkg, "com.tcl.settings.QuickSoundActivity", displayPkg, displayClass);
        int audioMenu = QuickSettingsActions.scoreSoundActivityName(
                displayPkg, "com.tcl.settings.AudioMenuActivity", displayPkg, displayClass);

        assertEquals(0, picture);
        assertEquals(0, displayAndSound);
        assertTrue(sound > audioMenu);
        assertEquals(sound, quickSound);
        assertTrue(audioMenu > 0);
    }

    @Test
    public void partnerCustomizerSoundSubpackageInsideTvSettingsIsDiscovered() {
        String pkg = "com.android.tv.settings";
        String displayClass = "com.android.tv.settings.partnercustomizer.picture.PictureActivity";

        assertEquals(0, QuickSettingsActions.scoreSoundActivityName(pkg, displayClass, pkg, displayClass));
        assertTrue(QuickSettingsActions.scoreSoundActivityName(
                pkg, "com.android.tv.settings.partnercustomizer.sound.SoundActivity", pkg, displayClass) > 0);
        // Combined Display & Sound must be rejected even inside a partner customizer subpackage.
        assertEquals(0, QuickSettingsActions.scoreSoundActivityName(
                pkg, "com.android.tv.settings.partnercustomizer.DisplayAndSoundActivity", pkg, displayClass));
        // Generic AOSP TvSettings activities must not be matched as vendor overrides.
        assertEquals(0, QuickSettingsActions.scoreSoundActivityName(
                pkg, "com.android.tv.settings.device.displaysound.DisplaySoundActivity",
                pkg, "com.android.tv.settings.device.displaysound.DisplaySoundActivity"));
        assertEquals(0, QuickSettingsActions.scoreSoundActivityName(
                pkg, "com.android.tv.settings.device.sound.SoundActivity",
                pkg, "com.android.tv.settings.device.displaysound.DisplaySoundActivity"));
    }

    @Test
    public void audioOnlyOutputEqualizerAndDummyActivitiesAreExcluded() {
        String pkg = "com.tcl.settings";
        String displayClass = "com.tcl.settings.PictureActivity";

        assertEquals(0, QuickSettingsActions.scoreSoundActivityName(
                pkg, "com.tcl.settings.AudioOnlyActivity", pkg, displayClass));
        assertEquals(0, QuickSettingsActions.scoreSoundActivityName(
                pkg, "com.tcl.settings.AudioOutputActivity", pkg, displayClass));
        assertEquals(0, QuickSettingsActions.scoreSoundActivityName(
                pkg, "com.tcl.settings.EqualizerActivity", pkg, displayClass));
        assertEquals(0, QuickSettingsActions.scoreSoundActivityName(
                pkg, "com.tcl.settings.SoundBarActivity", pkg, displayClass));
        assertEquals(0, QuickSettingsActions.scoreSoundActivityName(
                pkg, "com.tcl.settings.CTSDummySoundActivity", pkg, displayClass));
    }
}
