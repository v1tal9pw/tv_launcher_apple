package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QuickSoundRoutingTest {
    @Test
    public void pictureOnlyActivitiesAreRejected() {
        assertTrue(QuickSettingsActions.isPictureOnlyClassName("com.tcl.settings.PictureActivity"));
        assertTrue(QuickSettingsActions.isPictureOnlyClassName(
                "com.android.tv.settings.partnercustomizer.picture.PictureActivity"));
        assertTrue(QuickSettingsActions.isPictureOnlyClassName("com.tcl.settings.DisplayActivity"));
        assertTrue(QuickSettingsActions.isPictureOnlyClassName("com.tcl.settings.ScreenSettingsActivity"));
        assertFalse(QuickSettingsActions.isPictureOnlyClassName("com.tcl.settings.DisplayAndSoundActivity"));
        assertFalse(QuickSettingsActions.isPictureOnlyClassName("com.tcl.settings.SoundActivity"));
        assertFalse(QuickSettingsActions.isPictureOnlyClassName("com.tcl.settings.AudioSettingsActivity"));
    }

    @Test
    public void vendorDisplayAndSoundRanksAboveSoundAndNeverPicksPicture() {
        String displayPkg = "com.tcl.settings";
        String displayClass = "com.tcl.settings.PictureActivity";

        int picture = QuickSettingsActions.scoreSoundActivityName(
                displayPkg, displayClass, displayPkg, displayClass);
        int displayAndSound = QuickSettingsActions.scoreSoundActivityName(
                displayPkg, "com.tcl.settings.DisplayAndSoundActivity", displayPkg, displayClass);
        int sound = QuickSettingsActions.scoreSoundActivityName(
                displayPkg, "com.tcl.settings.SoundActivity", displayPkg, displayClass);
        int audioMenu = QuickSettingsActions.scoreSoundActivityName(
                displayPkg, "com.tcl.settings.AudioMenuActivity", displayPkg, displayClass);

        assertEquals(0, picture);
        assertTrue(displayAndSound > sound);
        assertTrue(sound > audioMenu);
        assertTrue(audioMenu > 0);
    }

    @Test
    public void partnerCustomizerSubpackageInsideTvSettingsIsDiscovered() {
        String pkg = "com.android.tv.settings";
        String displayClass = "com.android.tv.settings.partnercustomizer.picture.PictureActivity";

        assertEquals(0, QuickSettingsActions.scoreSoundActivityName(pkg, displayClass, pkg, displayClass));
        assertTrue(QuickSettingsActions.scoreSoundActivityName(
                pkg, "com.android.tv.settings.partnercustomizer.sound.SoundActivity", pkg, displayClass) > 0);
        assertTrue(QuickSettingsActions.scoreSoundActivityName(
                pkg, "com.android.tv.settings.partnercustomizer.DisplayAndSoundActivity", pkg, displayClass) > 0);
        // Generic AOSP TvSettings activities must not be matched as vendor overrides.
        assertEquals(0, QuickSettingsActions.scoreSoundActivityName(
                pkg, "com.android.tv.settings.device.displaysound.DisplaySoundActivity",
                pkg, "com.android.tv.settings.device.displaysound.DisplaySoundActivity"));
        assertEquals(0, QuickSettingsActions.scoreSoundActivityName(
                pkg, "com.android.tv.settings.device.sound.SoundActivity",
                pkg, "com.android.tv.settings.device.displaysound.DisplaySoundActivity"));
    }

    @Test
    public void audioOnlyEqualizerAndDummyActivitiesAreExcluded() {
        String pkg = "com.tcl.settings";
        String displayClass = "com.tcl.settings.PictureActivity";

        assertEquals(0, QuickSettingsActions.scoreSoundActivityName(
                pkg, "com.tcl.settings.AudioOnlyActivity", pkg, displayClass));
        assertEquals(0, QuickSettingsActions.scoreSoundActivityName(
                pkg, "com.tcl.settings.EqualizerActivity", pkg, displayClass));
        assertEquals(0, QuickSettingsActions.scoreSoundActivityName(
                pkg, "com.tcl.settings.SoundBarActivity", pkg, displayClass));
        assertEquals(0, QuickSettingsActions.scoreSoundActivityName(
                pkg, "com.tcl.settings.CTSDummySoundActivity", pkg, displayClass));
    }
}
