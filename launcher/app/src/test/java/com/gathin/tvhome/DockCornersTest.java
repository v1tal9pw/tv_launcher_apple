package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The dock band's corners are concentric with its tiles' corners. */
public class DockCornersTest {
    @Test public void fiveAppsInSixSlotsAreCenteredAtDifferentWindowSizes() {
        assertEquals(100, DockDecoration.partialRowOffset(1200, 6, 5));
        assertEquals(160, DockDecoration.partialRowOffset(1920, 6, 5));
        assertEquals(320, DockDecoration.partialRowOffset(3840, 6, 5));
        // Equal and opposite item offsets move the row without shrinking tile widths.
        int shift = DockDecoration.partialRowOffset(1200, 6, 5);
        assertEquals(1200, 2 * shift + 5 * (1200 / 6));
    }

    @Test public void fullAndEmptyDocksKeepTheirLayout() {
        assertEquals(0, DockDecoration.partialRowOffset(1200, 6, 6));
        assertEquals(0, DockDecoration.partialRowOffset(1200, 6, 12));
        assertEquals(0, DockDecoration.partialRowOffset(1200, 6, 0));
        assertEquals(0, DockDecoration.partialRowOffset(0, 6, 5));
    }

    private static final float DENSITY = 2f;   // 1080p TV, 320 dpi
    /** activity_main.xml: 56dp root padding and 10dp grid padding on each side of a 1920 px screen. */
    private static final int INNER = 1920 - 2 * 56 * 2 - 2 * 10 * 2;

    @Test public void bandRadiusIsTileRadiusPlusPadding() {
        assertEquals(77f, DockDecoration.radiusPx(25f, 52f), 0f);
    }

    @Test public void evenPaddingIsTheGapBetweenTilesUnlessTheEdgeIsTighter() {
        assertEquals(50f, DockDecoration.evenPaddingPx(1000, 4, 200, 80f), 0.001f);
        assertEquals(30f, DockDecoration.evenPaddingPx(1000, 4, 200, 30f), 0.001f);
        assertEquals(0f, DockDecoration.evenPaddingPx(1000, 0, 200, 30f), 0.001f);
    }

    @Test public void evenPaddingStaysPositiveAndBelowTheCellFromFourToSevenColumns() {
        for (int columns = 4; columns <= 7; columns++) {
            int tile = AppAdapter.tileWidthPx(INNER, columns, DENSITY);
            float gap = INNER / (float) columns - tile;
            float pad = DockDecoration.evenPaddingPx(INNER, columns, tile, Float.MAX_VALUE);
            assertEquals("columns " + columns, gap, pad, 0.001f);
            float radius = DockDecoration.radiusPx(AppAdapter.cornerRadius(tile, DENSITY), pad);
            assertEquals("columns " + columns, pad, radius - AppAdapter.cornerRadius(tile, DENSITY), 0.001f);
        }
    }
}
