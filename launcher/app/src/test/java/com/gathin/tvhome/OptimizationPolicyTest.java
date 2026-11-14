package com.gathin.tvhome;
import org.junit.Test;
import static org.junit.Assert.*;
public class OptimizationPolicyTest {
    @Test public void onlyExplicitCandidatesCanBeChanged() {
        assertTrue(OptimizationPolicy.launcher("com.google.android.apps.tv.launcherx"));
        assertTrue(OptimizationPolicy.allowed("com.google.android.tvrecommendations"));
        assertTrue(OptimizationPolicy.allowed("com.tcl.logkit"));
        assertTrue(OptimizationPolicy.allowed("com.tcl.messagebox"));
        assertFalse(OptimizationPolicy.launcher("com.tcl.messagebox"));
        for (String pkg : new String[]{null, "android", "com.google.android.gms", "com.android.vending",
                "com.android.tv.settings", "com.google.android.tungsten.setupwraith", "com.cvte.tv.launcher",
                "com.tcl.tv", "com.tcl.tvinput", "com.tcl.suspension", "com.tcl.dashboard",
                "com.google.android.apps.tv.launcherx;reboot", "com.google.android.apps.tv.launcherx.other"})
            assertFalse(String.valueOf(pkg), OptimizationPolicy.allowed(pkg));
    }
}
