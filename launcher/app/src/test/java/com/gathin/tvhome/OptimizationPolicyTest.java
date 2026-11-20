package com.gathin.tvhome;
import org.junit.Test;
import static org.junit.Assert.*;
public class OptimizationPolicyTest {
    @Test public void everyCleanupCandidateHasSpecificGuidance() {
        for (String pkg : OptimizationPolicy.TCL_C6K_PACKAGES)
            assertNotEquals(pkg, R.string.optimize_hint_unknown, OptimizationPolicy.guidance(pkg));
        assertEquals(R.string.optimize_hint_unknown, OptimizationPolicy.guidance(null));
        assertEquals(R.string.optimize_hint_unknown, OptimizationPolicy.guidance("unknown.package"));
        assertEquals(R.string.optimize_hint_peripheral, OptimizationPolicy.guidance("com.tcl.UpdatePeripheral"));
        assertEquals(R.string.optimize_hint_assistant, OptimizationPolicy.guidance("com.google.android.katniss"));
    }
    @Test public void onlyExplicitCandidatesCanBeChanged() {
        assertTrue(OptimizationPolicy.launcher("com.google.android.apps.tv.launcherx"));
        assertTrue(OptimizationPolicy.launcher("com.google.android.leanbacklauncher"));
        assertTrue(OptimizationPolicy.launcher("com.tcl.tv.launcher"));
        assertTrue(OptimizationPolicy.launcher("com.tcl.launcher"));
        assertTrue(OptimizationPolicy.allowed("com.google.android.tvrecommendations"));
        assertTrue(OptimizationPolicy.allowed("com.tcl.logkit"));
        assertTrue(OptimizationPolicy.allowed("com.tcl.messagebox"));
        assertFalse(OptimizationPolicy.launcher("com.tcl.messagebox"));
        for (String pkg : new String[]{null, "android", "com.google.android.gms", "com.android.vending",
                "com.android.tv.settings", "com.google.android.tungsten.setupwraith", "com.cvte.tv.launcher",
                "com.tcl.tv", "com.tcl.tvinput", "com.tcl.suspension", "com.tcl.cyberui",
                "com.google.android.apps.tv.launcherx;reboot", "com.google.android.apps.tv.launcherx.other"})
            assertFalse(String.valueOf(pkg), OptimizationPolicy.allowed(pkg));
    }
    @Test public void c6kProfileKeepsCriticalServicesAndRequiresMatchingModel() {
        assertEquals(27, OptimizationPolicy.TCL_C6K_PACKAGES.length);
        assertTrue(OptimizationPolicy.tclC6k("Smart TV Pro", "G08", "G08_4K_GB"));
        assertTrue(OptimizationPolicy.tclC6k("Smart TV Pro\n", "G08\n", "G08_4K_GB\n"));
        assertTrue(OptimizationPolicy.tclC6k("TCL 65C6K", "unknown", "unknown"));
        assertFalse(OptimizationPolicy.tclC6k("TCL C755", "G07", "G07_4K"));
        assertFalse(OptimizationPolicy.tclC6k("Emulator", "sdk", "sdk"));
        assertTrue(OptimizationPolicy.candidates(true).contains("com.tcl.dashboard"));
        assertEquals(27, OptimizationPolicy.candidates(true).size());
        assertFalse(OptimizationPolicy.candidates(true).contains("com.google.android.apps.tv.launcherx"));
        assertTrue(OptimizationPolicy.candidates(false).isEmpty());
        assertFalse(OptimizationPolicy.candidates(false).contains("com.tcl.dashboard"));
        for (String pkg : new String[]{"com.tcl.airplay2", "com.mediatek.AirplayAPK",
                "com.google.android.apps.mediashell", "com.tcl.autopair", "com.tcl.tv",
                "com.tcl.tvinput", "com.android.permissioncontroller", "android.ext.services",
                "com.google.android.gms", "com.android.vending", "com.android.systemui",
                "com.google.android.tungsten.setupwraith"}) {
            assertFalse(pkg, OptimizationPolicy.allowed(pkg));
        }
        assertFalse(OptimizationPolicy.allowed("com.tcl.dashboard;reboot"));
    }
}
