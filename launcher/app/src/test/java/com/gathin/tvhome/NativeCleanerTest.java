package com.gathin.tvhome;

import android.content.pm.ApplicationInfo;
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
}
