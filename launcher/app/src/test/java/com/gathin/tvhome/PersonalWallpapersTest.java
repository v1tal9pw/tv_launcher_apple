package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class PersonalWallpapersTest {
    @Test public void invalidNamesCannotEscapePrivateStorage() {
        for (String value : new String[]{"personal:../../video.mp4", "personal:/video.mp4", "personal:bad.mp4", null})
            assertNull(PersonalWallpapers.file(null, value));
        assertTrue(PersonalWallpapers.video(PersonalWallpapers.COAST_VIDEO));
        assertFalse(PersonalWallpapers.video("personal:image.jpg"));
    }
    @Test public void videoLimitsAcceptPortraitAndLandscapeWithoutOversizedFrames() {
        assertTrue(PersonalWallpapers.acceptedVideo(1920, 1080, "29.97"));
        assertTrue(PersonalWallpapers.acceptedVideo(720, 1280, "24"));
        assertTrue(PersonalWallpapers.acceptedVideo(1280, 720, null));
        assertFalse(PersonalWallpapers.acceptedVideo(3840, 2160, "24"));
        assertFalse(PersonalWallpapers.acceptedVideo(1920, 1920, "24"));
        assertFalse(PersonalWallpapers.acceptedVideo(1920, 1080, "60"));
        assertFalse(PersonalWallpapers.acceptedVideo(0, 1080, "24"));
        assertFalse(PersonalWallpapers.acceptedVideo(1920, 1080, "NaN"));
        assertFalse(PersonalWallpapers.acceptedVideo(1920, 1080, "bad"));
    }
}
