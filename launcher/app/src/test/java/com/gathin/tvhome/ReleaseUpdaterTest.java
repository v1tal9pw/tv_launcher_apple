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
        long now = 1_000_000L;
        assertFalse(ReleaseUpdater.shouldCheck(now - 1000, now, false));
        assertTrue(ReleaseUpdater.shouldCheck(now - 1000, now, true));
    }
}
