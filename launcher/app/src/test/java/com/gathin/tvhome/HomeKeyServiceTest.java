package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HomeKeyServiceTest {
    private static final long SOON = 45_000L;

    @Test public void aTapIsAShortPress() {
        assertTrue(HomeKeyService.isShortPress(1_000, 1_120, 400));
    }

    @Test public void holdingPastTheTimeoutIsALongPress() {
        assertFalse(HomeKeyService.isShortPress(1_000, 1_400, 400));
        assertFalse(HomeKeyService.isShortPress(1_000, 3_000, 400));
    }

    @Test public void firstBindOfANewBootProbes() {
        assertTrue(HomeKeyService.firstBindAfterBoot(12, 11, SOON));
        assertTrue(HomeKeyService.firstBindAfterBoot(1, -1, SOON));
    }

    @Test public void aRebindInTheSameBootDoesNot() {
        assertFalse(HomeKeyService.firstBindAfterBoot(12, 12, SOON));
    }

    @Test public void aBindLongAfterBootDoesNot() {
        assertFalse(HomeKeyService.firstBindAfterBoot(12, 11, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void noBootCountMeansNoProbe() {
        assertFalse(HomeKeyService.firstBindAfterBoot(-1, -1, SOON));
    }

    private static final String TV = "com.mediatek.wwtv.tvcenter";

    @Test public void liveTvBeforeAnyKeyGoesHome() {
        assertTrue(HomeKeyService.powerOnRedirect(TV, TV, SOON, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void otherAppsAndInputsAreLeftAlone() {
        assertFalse(HomeKeyService.powerOnRedirect(TV, "com.netflix.ninja", SOON, HomeKeyService.BOOT_WINDOW_MS));
        assertFalse(HomeKeyService.powerOnRedirect(TV, null, SOON, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void afterAKeyOrTheWindowLiveTvStays() {
        assertFalse(HomeKeyService.powerOnRedirect(TV, TV, SOON, 0));
        assertFalse(HomeKeyService.powerOnRedirect(TV, TV, HomeKeyService.BOOT_WINDOW_MS, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void offMeansNothing() {
        assertFalse(HomeKeyService.powerOnRedirect("", TV, SOON, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void alwaysGoesHomeAfterScreenOnUntilAKey() {
        assertTrue(HomeKeyService.stillArmed(SOON, SOON + HomeKeyService.SCREEN_ON_WINDOW_MS));
        assertFalse(HomeKeyService.stillArmed(SOON, 0));
    }

    @Test public void stockHomeCandidateMatchesOtherTvLaunchersOnly() {
        String self = "com.gathin.tvhome";
        assertTrue(HomeKeyService.isStockHomeCandidate(self, "com.google.android.tvlauncher",
                "com.google.android.tvlauncher.MainActivity"));
        assertTrue(HomeKeyService.isStockHomeCandidate(self, "com.google.android.apps.tv.launcherx",
                "com.google.android.apps.tv.launcherx.home.HomeActivity"));
        assertFalse(HomeKeyService.isStockHomeCandidate(self, self, "com.gathin.tvhome.MainActivity"));
        assertFalse(HomeKeyService.isStockHomeCandidate(self, "com.android.tv.settings",
                "com.android.tv.settings.system.FallbackHome"));
        assertFalse(HomeKeyService.isStockHomeCandidate(self, "android",
                "com.android.internal.app.ResolverActivity"));
    }

    @Test public void autoStockHomeRedirectDebouncesBurstEvents() {
        assertTrue(HomeKeyService.shouldRedirectStockHome(1_000, 0));
        assertFalse(HomeKeyService.shouldRedirectStockHome(1_200, 1_000));
        assertTrue(HomeKeyService.shouldRedirectStockHome(
                1_000 + HomeKeyService.AUTO_STOCK_REDIRECT_DEBOUNCE_MS, 1_000));
    }
}
