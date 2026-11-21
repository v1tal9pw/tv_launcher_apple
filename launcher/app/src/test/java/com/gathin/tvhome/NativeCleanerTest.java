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
                Arrays.asList("com.gathin.tvhome", "bad;rm -rf /", ""),
                Arrays.asList("com.google.android.youtube.tv", "bad$(id)"),
                Arrays.asList("com.v2raytun.android", "bad|cat"));
        assertTrue(cmd.contains(" com.gathin.tvhome "));
        assertTrue(cmd.contains("com.v2raytun.android "));
        assertTrue(cmd.contains(" com.google.android.youtube.tv"));
        assertFalse(cmd.contains("rm -rf"));
        assertFalse(cmd.contains("$(id)"));
        assertFalse(cmd.contains("|cat"));
        assertTrue(cmd.contains("dumpsys activity activities"));
        assertTrue(cmd.contains("dumpsys vpn_management"));
        assertTrue(cmd.contains("dumpsys connectivity"));
        assertTrue(cmd.contains("am kill-all"));
        assertTrue(cmd.contains("pm trim-caches"));
        assertTrue(cmd.length() < 3072);
    }

    @Test public void adbCleanupCommandOmitsVpnFallbackWhenDisconnected() {
        String cmd = QuickSettingsActions.buildAdbCleanupCommand(
                Arrays.asList("com.gathin.tvhome"),
                Arrays.asList("com.google.android.youtube.tv"),
                java.util.Collections.emptyList());
        assertTrue(cmd.contains("dumpsys vpn_management"));
        assertFalse(cmd.contains("com.v2raytun.android"));
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
