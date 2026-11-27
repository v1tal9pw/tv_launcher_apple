package com.gathin.tvhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BannerShapeTest {
    @Test
    public void wideBannersAreKept() {
        assertTrue(AppAdapter.isBannerShaped(320, 180));
        assertTrue(AppAdapter.isBannerShaped(1920, 1080));
    }

    @Test
    public void squareAndTallPicturesAreNotBanners() {
        assertFalse(AppAdapter.isBannerShaped(64, 64));
        assertFalse(AppAdapter.isBannerShaped(256, 256));
        assertFalse(AppAdapter.isBannerShaped(100, 160));
    }

    @Test
    public void drawablesWithoutAnIntrinsicSizeStillFillTheTile() {
        assertTrue(AppAdapter.isBannerShaped(-1, -1));
        assertTrue(AppAdapter.isBannerShaped(0, 0));
    }

    @Test
    public void artworkDensityTargetsAtLeastXxxhdpi() {
        assertTrue(AppArtwork.targetArtworkDensity(213) >= 640);
        assertTrue(AppArtwork.targetArtworkDensity(320) >= 640);
        assertTrue(AppArtwork.targetArtworkDensity(640) >= 640);
        assertTrue(AppArtwork.targetArtworkDensity(800) == 800);
    }

    @Test
    public void customBannerSampleSizeKeepsHighResolutionUpTo1024Px() {
        assertTrue(AppArtwork.customBannerSampleSize(640, 360) == 1);
        assertTrue(AppArtwork.customBannerSampleSize(1024, 576) == 1);
        assertTrue(AppArtwork.customBannerSampleSize(1920, 1080) == 2);
        assertTrue(AppArtwork.customBannerSampleSize(3840, 2160) == 4);
    }
}
