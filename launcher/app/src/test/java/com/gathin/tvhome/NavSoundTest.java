package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class NavSoundTest {
    @Test public void volumeClampsAndConvertsToLinearGain() {
        assertEquals(0, NavSound.clampVolume(-20));
        assertEquals(0, NavSound.clampVolume(0));
        assertEquals(60, NavSound.clampVolume(60));
        assertEquals(100, NavSound.clampVolume(150));
        assertEquals(0.0f, NavSound.gainForPercent(-10), 0.0001f);
        assertEquals(0.25f, NavSound.gainForPercent(25), 0.0001f);
        assertEquals(0.60f, NavSound.gainForPercent(60), 0.0001f);
        assertEquals(1.0f, NavSound.gainForPercent(100), 0.0001f);
    }

    @Test public void choosesNativeSampleRateAndBuildsShortDecayingClick() {
        assertEquals(48000, NavSound.chooseSampleRate(48000));
        assertEquals(44100, NavSound.chooseSampleRate(44100));
        assertEquals(48000, NavSound.chooseSampleRate(0));
        assertEquals(48000, NavSound.chooseSampleRate(-1));
        assertEquals(48000, NavSound.chooseSampleRate(999999));

        short[] pcm = NavSound.buildClickPcm(48000);
        assertEquals(384, pcm.length); // 8 ms at 48 kHz
        int earlyPeak = 0;
        for (int i = 0; i < 64; i++) earlyPeak = Math.max(earlyPeak, Math.abs(pcm[i]));
        int tailPeak = 0;
        for (int i = pcm.length - 32; i < pcm.length; i++) tailPeak = Math.max(tailPeak, Math.abs(pcm[i]));
        assertTrue("Click starts with crisp transient", earlyPeak > 10000);
        assertTrue("Click decays cleanly by tail", tailPeak < earlyPeak / 10);
    }

    @Test public void overlayAutoReturnRequiresRecentBackNotConfirm() {
        // Normal Back press within window returns to Control Center
        assertTrue(RemoteControlOverlay.shouldAutoReturn(true, false, 5_000L, 1_000L, 5_500L));
        // Pressing OK inside Settings (e.g. opening Sound) must NOT reopen Control Center
        assertFalse(RemoteControlOverlay.shouldAutoReturn(true, false, 0L, 5_000L, 5_500L));
        assertFalse(RemoteControlOverlay.shouldAutoReturn(true, false, 4_000L, 5_000L, 5_500L));
        // Stale Back press outside window does NOT reopen Control Center
        assertFalse(RemoteControlOverlay.shouldAutoReturn(true, false, 1_000L, 0L, 5_500L));
        // Confirm without subsequent Back cancels MainActivity quick resume
        assertTrue(RemoteControlOverlay.isConfirmWithoutBack(3_000L, 0L, 1_000L));
        assertTrue(RemoteControlOverlay.isConfirmWithoutBack(3_000L, 2_000L, 1_000L));
        assertFalse(RemoteControlOverlay.isConfirmWithoutBack(3_000L, 4_000L, 1_000L));
        assertFalse(RemoteControlOverlay.isConfirmWithoutBack(900L, 0L, 1_000L));
    }
}
