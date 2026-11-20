package com.gathin.tvhome;

import android.content.pm.ApplicationInfo;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeCleanerTest {
    @Test public void onlyEnabledSystemCleanerIsLaunched() {
        assertTrue(QuickSettingsActions.nativeCleanerEnabled(ApplicationInfo.FLAG_SYSTEM, true));
        assertTrue(QuickSettingsActions.nativeCleanerEnabled(ApplicationInfo.FLAG_UPDATED_SYSTEM_APP, true));
        assertFalse(QuickSettingsActions.nativeCleanerEnabled(ApplicationInfo.FLAG_SYSTEM, false));
        assertFalse(QuickSettingsActions.nativeCleanerEnabled(0, true));
        assertFalse(QuickSettingsActions.nativeCleanerEnabled(0, false));
    }

    @Test public void adbCleanupCommandFiltersUnsafePackagesAndFitsAdbPacket() {
        String cmd = QuickSettingsActions.buildAdbCleanupCommand(
                Arrays.asList("com.gathin.tvhome", "com.wireguard.android", "bad;rm -rf /", ""));
        assertTrue(cmd.contains(" com.gathin.tvhome "));
        assertTrue(cmd.contains(" com.wireguard.android "));
        assertFalse(cmd.contains("rm -rf"));
        assertTrue(cmd.contains("am kill-all"));
        assertTrue(cmd.contains("pm trim-caches"));
        assertTrue(cmd.length() < 2048);
    }

    @Test public void adbCleanupOutputParsesFreedRamAndStoppedCount() {
        QuickSettingsActions.CleanupResult ok = QuickSettingsActions.parseCleanupOutput(
                "BEFORE=1676548\nAFTER=1848280\nSTOPPED=3\n");
        assertEquals(3, ok.stoppedApps);
        assertEquals(167, ok.freedMb);

        QuickSettingsActions.CleanupResult clamped = QuickSettingsActions.parseCleanupOutput(
                "BEFORE=2000000\nAFTER=1900000\nSTOPPED=0\n");
        assertEquals(0, clamped.stoppedApps);
        assertEquals(0, clamped.freedMb);

        QuickSettingsActions.CleanupResult empty = QuickSettingsActions.parseCleanupOutput("");
        assertEquals(0, empty.stoppedApps);
        assertEquals(0, empty.freedMb);
    }
}
