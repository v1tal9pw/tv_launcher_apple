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
}
