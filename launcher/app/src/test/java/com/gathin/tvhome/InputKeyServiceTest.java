package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InputKeyServiceTest {
    private static final long SOON = 45_000L;

    @Test public void aTapIsAShortPress() {
        assertTrue(InputKeyService.isShortPress(1_000, 1_120, 400));
    }

    @Test public void holdingPastTheTimeoutIsALongPress() {
        assertFalse(InputKeyService.isShortPress(1_000, 1_400, 400));
        assertFalse(InputKeyService.isShortPress(1_000, 3_000, 400));
    }

    @Test public void firstBindOfANewBootProbes() {
        assertTrue(InputKeyService.firstBindAfterBoot(12, 11, SOON));
        assertTrue(InputKeyService.firstBindAfterBoot(1, -1, SOON));
    }

    @Test public void aRebindInTheSameBootDoesNot() {
        assertFalse(InputKeyService.firstBindAfterBoot(12, 12, SOON));
    }

    @Test public void aBindLongAfterBootDoesNot() {
        assertFalse(InputKeyService.firstBindAfterBoot(12, 11, InputKeyService.BOOT_WINDOW_MS));
    }

    @Test public void noBootCountMeansNoProbe() {
        assertFalse(InputKeyService.firstBindAfterBoot(-1, -1, SOON));
    }

    private static final String TV = "com.mediatek.wwtv.tvcenter";

    @Test public void liveTvBeforeAnyKeyGoesHome() {
        assertTrue(InputKeyService.powerOnRedirect(TV, TV, SOON, InputKeyService.BOOT_WINDOW_MS));
    }

    @Test public void otherAppsAndInputsAreLeftAlone() {
        assertFalse(InputKeyService.powerOnRedirect(TV, "com.netflix.ninja", SOON, InputKeyService.BOOT_WINDOW_MS));
        assertFalse(InputKeyService.powerOnRedirect(TV, null, SOON, InputKeyService.BOOT_WINDOW_MS));
    }

    @Test public void afterAKeyOrTheWindowLiveTvStays() {
        assertFalse(InputKeyService.powerOnRedirect(TV, TV, SOON, 0));
        assertFalse(InputKeyService.powerOnRedirect(TV, TV, InputKeyService.BOOT_WINDOW_MS, InputKeyService.BOOT_WINDOW_MS));
    }

    @Test public void offMeansNothing() {
        assertFalse(InputKeyService.powerOnRedirect("", TV, SOON, InputKeyService.BOOT_WINDOW_MS));
    }

    @Test public void alwaysGoesHomeAfterScreenOnUntilAKey() {
        assertTrue(InputKeyService.stillArmed(SOON, SOON + InputKeyService.SCREEN_ON_WINDOW_MS));
        assertFalse(InputKeyService.stillArmed(SOON, 0));
    }
}
