package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UpdateRelaunchTest {
    @Test public void pendingWindowHonorsExpiryAndZero() {
        assertFalse(UpdateInstallReceiver.isWithinWindow(0L, 1_000L));
        assertTrue(UpdateInstallReceiver.isWithinWindow(2_000L, 1_000L));
        assertTrue(UpdateInstallReceiver.isWithinWindow(2_000L, 2_000L));
        assertFalse(UpdateInstallReceiver.isWithinWindow(2_000L, 2_001L));
    }

    @Test public void adbHomeCommandMatchesDefaultHomePolicy() {
        assertEquals(
                "am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.gathin.tvhome/com.gathin.tvhome.MainActivity",
                UpdateInstallReceiver.adbStartHomeCommand("com.gathin.tvhome", false));
        assertEquals(
                "am start -n com.gathin.tvhome/com.gathin.tvhome.MainActivity -c android.intent.category.HOME",
                UpdateInstallReceiver.adbStartHomeCommand("com.gathin.tvhome", true));
    }
}
