package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class RemotePanelKeysTest {
    @Test public void panelKeyIsOffByDefault() {
        assertFalse(RemotePanelKeys.isPanelKey(0, 0));
        assertFalse(RemotePanelKeys.isPanelKey(0, 82));
    }

    @Test public void selectedKeyDoesNotTakeOtherRemoteButtons() {
        assertTrue(RemotePanelKeys.isPanelKey(82, 82));
        assertFalse(RemotePanelKeys.isPanelKey(82, 176));
        assertFalse(RemotePanelKeys.isPanelKey(82, 183));
        assertTrue(RemotePanelKeys.isPanelKey(176, 83, new int[]{83}));
        assertFalse(RemotePanelKeys.isPanelKey(82, 83, new int[]{83}));
    }

    @Test public void playPauseStaysWithVideoUntilExplicitlyEnabledOutsideLauncher() {
        assertFalse(RemotePanelKeys.interceptPlayPause(false, false));
        assertFalse(RemotePanelKeys.interceptPlayPause(true, true));
        assertTrue(RemotePanelKeys.interceptPlayPause(true, false));
    }

    @Test public void heldAndCanceledButtonsDoNotOpenThePanel() {
        assertFalse(RemotePanelKeys.openOnRelease(0, false));
        assertFalse(RemotePanelKeys.openOnRelease(1, true));
        assertTrue(RemotePanelKeys.openOnRelease(1, false));
    }
}
