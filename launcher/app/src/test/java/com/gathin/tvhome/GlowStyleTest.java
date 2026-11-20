package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** glow_style from config.xml or a theme: horizon, edge, off by name, radial for anything else. */
public class GlowStyleTest {

    @Test
    public void horizonByNameAnyCase() {
        assertEquals(Config.GLOW_HORIZON, Config.glowStyle("horizon"));
        assertEquals(Config.GLOW_HORIZON, Config.glowStyle(" Horizon "));
    }

    @Test
    public void edgeAndOffByNameAnyCase() {
        assertEquals(Config.GLOW_EDGE, Config.glowStyle("edge"));
        assertEquals(Config.GLOW_EDGE, Config.glowStyle(" EDGE "));
        assertEquals(Config.GLOW_OFF, Config.glowStyle("off"));
        assertEquals(Config.GLOW_OFF, Config.glowStyle(" Off "));
    }

    @Test
    public void anythingElseIsRadial() {
        assertEquals(Config.GLOW_RADIAL, Config.glowStyle("radial"));
        assertEquals(Config.GLOW_RADIAL, Config.glowStyle(""));
        assertEquals(Config.GLOW_RADIAL, Config.glowStyle(null));
        assertEquals(Config.GLOW_RADIAL, Config.glowStyle("aurora"));
    }
}
