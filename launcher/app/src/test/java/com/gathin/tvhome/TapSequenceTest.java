package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class TapSequenceTest {
    @Test public void singleOnlyDoesNotWaitForMorePresses() {
        assertEquals(1, TapSequence.maximum("app", "", ""));
        assertEquals(0, TapSequence.maximum("", "", ""));
    }
    @Test public void highestConfiguredPressCountDeterminesWindow() {
        assertEquals(2, TapSequence.maximum("one", "two", ""));
        assertEquals(3, TapSequence.maximum("", "", "three"));
    }
    @Test public void eachGestureHasAnIndependentAction() {
        assertEquals("one", TapSequence.target(1, "one", "two", "three"));
        assertEquals("two", TapSequence.target(2, "one", "two", "three"));
        assertEquals("three", TapSequence.target(3, "one", "two", "three"));
    }
    @Test public void missingGestureFallsBackToLowerConfiguredAction() {
        assertEquals("one", TapSequence.target(2, "one", "", "three"));
        assertEquals("two", TapSequence.target(3, "one", "two", ""));
        assertEquals("", TapSequence.target(1, "", "", "three"));
    }
}
