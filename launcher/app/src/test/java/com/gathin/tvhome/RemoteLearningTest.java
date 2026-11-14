package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteLearningTest {
    @Test public void tclSharedScanCodeKeepsButtonsDistinct() {
        assertEquals("83", RemoteLearning.buttonId(83, 240));
        assertEquals("190", RemoteLearning.buttonId(190, 240));
        assertNotEquals(RemoteLearning.buttonId(83, 240), RemoteLearning.buttonId(190, 240));
    }

    @Test public void unknownKeyRetainsScanCodeFallback() {
        assertEquals("scan:384", RemoteLearning.buttonId(0, 384));
        assertEquals("190", RemoteLearning.buttonId(190, 0));
    }

    @Test public void homeBackAndVolumeStayReserved() {
        assertTrue(RemoteLearning.reserved(3));
        assertTrue(RemoteLearning.reserved(4));
        assertTrue(RemoteLearning.reserved(24));
        assertFalse(RemoteLearning.reserved(190));
    }
}
