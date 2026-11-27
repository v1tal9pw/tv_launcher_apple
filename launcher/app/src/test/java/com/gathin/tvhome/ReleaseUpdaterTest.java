package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReleaseUpdaterTest {
    @Test public void versionTagsAreStrictAndNumeric() {
        assertEquals(43, ReleaseUpdater.tagCode("v43"));
        assertEquals(-1, ReleaseUpdater.tagCode("v3.40"));
        assertEquals(-1, ReleaseUpdater.tagCode("v0"));
        assertEquals(-1, ReleaseUpdater.tagCode("v43-beta"));
    }

    @Test public void manualCheckIgnoresAutomaticCooldown() {
        long now = 20_000_000L;
        assertFalse(ReleaseUpdater.shouldCheck(now - 1000, now, false));
        assertTrue(ReleaseUpdater.shouldCheck(now - 3L * 60 * 60 * 1000, now, false));
        assertTrue(ReleaseUpdater.shouldCheck(now - 1000, now, true));
    }

    @Test public void badgeShowsOnlyWhenCachedVersionIsNewerThanInstalled() {
        assertTrue(ReleaseUpdater.isUpdateNewer(72, 71));
        assertFalse(ReleaseUpdater.isUpdateNewer(71, 71));
        assertFalse(ReleaseUpdater.isUpdateNewer(70, 71));
        assertFalse(ReleaseUpdater.isUpdateNewer(0, 71));
    }

    @Test public void releaseNotesAreBoundedForTheTvDialog() {
        assertEquals("", ReleaseUpdater.limitedNotes(null));
        assertEquals("Changed weather", ReleaseUpdater.limitedNotes("  Changed weather  "));
        assertEquals(3001, ReleaseUpdater.limitedNotes("x".repeat(4000)).length());
    }

    @Test public void powerOnCheckBypassesHourlyCooldownButDebouncesDuplicates() {
        long now = 50_000_000L;
        assertTrue(ReleaseUpdater.shouldRunPowerOnCheck(0L, now, false));
        assertFalse(ReleaseUpdater.shouldRunPowerOnCheck(now - 5_000L, now, false));
        assertFalse(ReleaseUpdater.shouldRunPowerOnCheck(0L, now, true));
        assertTrue(ReleaseUpdater.shouldRunPowerOnCheck(now - ReleaseUpdater.POWER_ON_DEBOUNCE_MS, now, false));
    }

    @Test public void powerOnRetryWaitsBackoffAndStopsAtCap() {
        long now = 50_000_000L;
        assertFalse(ReleaseUpdater.shouldRetryPowerOn(false, 0, 0L, now, false));
        assertFalse(ReleaseUpdater.shouldRetryPowerOn(true, 1, now - 2_000L, now, false));
        assertTrue(ReleaseUpdater.shouldRetryPowerOn(true, 1, now - ReleaseUpdater.POWER_ON_RETRY_MS, now, false));
        assertFalse(ReleaseUpdater.shouldRetryPowerOn(true, ReleaseUpdater.MAX_POWER_ON_RETRIES, 0L, now, false));
        assertFalse(ReleaseUpdater.shouldRetryPowerOn(true, 1, 0L, now, true));
    }

    @Test public void cdnAssetValidationEnforcesRepoUrlHashAndSize() {
        String validUrl = "https://github.com/" + ReleaseUpdater.REPOSITORY + "/releases/download/v78/tvhome-78.apk";
        String validSha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        ReleaseUpdater.Release ok = ReleaseUpdater.validateReleaseAsset(
                78, 77, "tvhome-78.apk", validUrl, validSha.toUpperCase(), 1_450_000L, "  Notes  ");
        assertNotNull(ok);
        assertEquals(78, ok.code);
        assertEquals(validSha, ok.sha256);
        assertEquals("Notes", ok.notes);

        // Same or older version rejected
        assertNull(ReleaseUpdater.validateReleaseAsset(77, 77, "tvhome-77.apk", validUrl, validSha, 1_450_000L, ""));
        // Wrong filename rejected
        assertNull(ReleaseUpdater.validateReleaseAsset(78, 77, "other.apk", validUrl, validSha, 1_450_000L, ""));
        // Wrong host rejected
        assertNull(ReleaseUpdater.validateReleaseAsset(78, 77, "tvhome-78.apk", "https://evil.example/tvhome-78.apk", validSha, 1_450_000L, ""));
        // Bad SHA rejected
        assertNull(ReleaseUpdater.validateReleaseAsset(78, 77, "tvhome-78.apk", validUrl, "deadbeef", 1_450_000L, ""));
        // Oversized APK rejected
        assertNull(ReleaseUpdater.validateReleaseAsset(78, 77, "tvhome-78.apk", validUrl, validSha, 9_000_000L, ""));
    }

    @Test public void cdnRedirectFilterAllowsOnlyGithubHttpsHosts() {
        assertTrue(ReleaseUpdater.isAllowedCdnUrl("https://github.com/v1tal9pw/tv_launcher_apple/releases/latest/download/latest.json"));
        assertTrue(ReleaseUpdater.isAllowedCdnUrl("https://objects.githubusercontent.com/github-production-release-asset/foo"));
        assertTrue(ReleaseUpdater.isAllowedCdnUrl("https://release-assets.githubusercontent.com/bar"));
        assertFalse(ReleaseUpdater.isAllowedCdnUrl("http://github.com/v1tal9pw/tv_launcher_apple"));
        assertFalse(ReleaseUpdater.isAllowedCdnUrl("https://evil.com/latest.json"));
    }
}
