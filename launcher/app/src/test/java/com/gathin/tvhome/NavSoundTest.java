package com.gathin.tvhome;

import android.media.AudioTrack;

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

    @Test public void choosesSampleRateAndBuildsShortDecayingClick() {
        assertEquals(48000, NavSound.chooseSampleRate(48000));
        assertEquals(44100, NavSound.chooseSampleRate(44100));
        assertEquals(22050, NavSound.chooseSampleRate(22050));
        assertEquals(48000, NavSound.chooseSampleRate(0));
        assertEquals(48000, NavSound.chooseSampleRate(-1));
        assertEquals(48000, NavSound.chooseSampleRate(999999));

        short[] pcm = NavSound.buildClickPcm(48000);
        assertEquals(1536, pcm.length); // 32 ms at 48 kHz (safely above 10-20 ms TV mixer period)
        int earlyPeak = 0;
        for (int i = 0; i < 160; i++) earlyPeak = Math.max(earlyPeak, Math.abs(pcm[i]));
        int tailPeak = 0;
        for (int i = pcm.length - 400; i < pcm.length; i++) tailPeak = Math.max(tailPeak, Math.abs(pcm[i]));
        assertTrue("Click starts with crisp transient", earlyPeak > 10000);
        assertEquals("Trailing buffer tail is clean silence", 0, tailPeak);

        byte[] wav = NavSound.buildWavBytes(48000, pcm);
        assertEquals(44 + pcm.length * 2, wav.length);
        assertEquals('R', wav[0]); assertEquals('I', wav[1]); assertEquals('F', wav[2]); assertEquals('F', wav[3]);
        assertEquals('W', wav[8]); assertEquals('A', wav[9]); assertEquals('V', wav[10]); assertEquals('E', wav[11]);
        assertEquals('d', wav[36]); assertEquals('a', wav[37]); assertEquals('t', wav[38]); assertEquals('a', wav[39]);
        int parsedRate = (wav[24] & 0xFF) | ((wav[25] & 0xFF) << 8)
                | ((wav[26] & 0xFF) << 16) | ((wav[27] & 0xFF) << 24);
        assertEquals(48000, parsedRate);
    }

    @Test public void detectsFocusAndAdapterViewSelectionChanges() {
        Object list = new Object();
        Object buttonA = new Object();
        Object buttonB = new Object();
        assertTrue(NavSound.focusOrSelectionChanged(buttonA, -1, buttonB, -1));
        assertFalse(NavSound.focusOrSelectionChanged(buttonA, -1, buttonA, -1));
        assertFalse(NavSound.focusOrSelectionChanged(buttonA, -1, null, -1));
        // Inside AlertDialog ListView focus stays on the same View while selectedPosition changes
        assertTrue(NavSound.focusOrSelectionChanged(list, 1, list, 2));
        assertFalse(NavSound.focusOrSelectionChanged(list, 2, list, 2));
    }

    @Test public void staticAudioTrackStatesAllowNoStaticDataBeforeWriteOnly() {
        // MODE_STATIC starts in STATE_NO_STATIC_DATA before write() and moves to STATE_INITIALIZED after write().
        assertTrue(NavSound.isStaticStateBeforeWrite(AudioTrack.STATE_NO_STATIC_DATA));
        assertTrue(NavSound.isStaticStateBeforeWrite(AudioTrack.STATE_INITIALIZED));
        assertFalse(NavSound.isStaticStateBeforeWrite(AudioTrack.STATE_UNINITIALIZED));

        assertTrue(NavSound.isStaticStateAfterWrite(AudioTrack.STATE_INITIALIZED));
        assertFalse(NavSound.isStaticStateAfterWrite(AudioTrack.STATE_NO_STATIC_DATA));
        assertFalse(NavSound.isStaticStateAfterWrite(AudioTrack.STATE_UNINITIALIZED));
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
