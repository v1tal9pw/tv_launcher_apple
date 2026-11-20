package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Pure math verification for the screen-edge Ambilight glow alpha falloff. */
public class AmbientGlowTest {

    private static final int W = AmbientGlow.MASK_WIDTH;
    private static final int H = AmbientGlow.MASK_HEIGHT;

    @Test
    public void allFourEdgesGlowBrightlyAndSymmetrically() {
        int top = AmbientGlow.edgeAlpha(W / 2, 0, W, H);
        int bottom = AmbientGlow.edgeAlpha(W / 2, H - 1, W, H);
        int left = AmbientGlow.edgeAlpha(0, H / 2, W, H);
        int right = AmbientGlow.edgeAlpha(W - 1, H / 2, W, H);

        assertTrue("top edge should glow", top > 120);
        assertEquals("top and bottom edges match", top, bottom);
        assertEquals("left and right edges match", left, right);
        assertEquals("horizontal and vertical outer rims match", top, left);
    }

    @Test
    public void centerIsCompletelyTransparent() {
        assertEquals(0, AmbientGlow.edgeAlpha(W / 2, H / 2, W, H));
    }

    @Test
    public void falloffDecreasesMonotonicallyTowardCenter() {
        int prev = AmbientGlow.edgeAlpha(W / 2, 0, W, H);
        for (int y = 1; y <= H / 2; y++) {
            int cur = AmbientGlow.edgeAlpha(W / 2, y, W, H);
            assertTrue("alpha must not increase inward at y=" + y, cur <= prev);
            prev = cur;
        }
        assertEquals(0, prev);
    }

    @Test
    public void cornersStayBoundedWithoutOverflow() {
        int corner = AmbientGlow.edgeAlpha(0, 0, W, H);
        int top = AmbientGlow.edgeAlpha(W / 2, 0, W, H);
        assertTrue("corner alpha stays in valid byte range", corner > 0 && corner <= 255);
        assertEquals("screen-blend at exact corner matches edge peak", top, corner);
    }
}
