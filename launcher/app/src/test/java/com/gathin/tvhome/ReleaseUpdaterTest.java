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
}
