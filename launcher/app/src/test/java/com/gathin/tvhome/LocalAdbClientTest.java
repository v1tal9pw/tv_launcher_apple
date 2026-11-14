package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class LocalAdbClientTest {
    @Test public void shellExitMustBeConfirmed() {
        assertEquals("2000", LocalAdbClient.checkedOutput("2000\r\n__TVHOME_EXIT__0\r\n"));
        for (String reply : new String[]{"permission denied", "Success\n__TVHOME_EXIT__1\n"}) {
            try {
                LocalAdbClient.checkedOutput(reply);
                fail("Unconfirmed ADB command must fail");
            } catch (IllegalStateException expected) { }
        }
    }
}
