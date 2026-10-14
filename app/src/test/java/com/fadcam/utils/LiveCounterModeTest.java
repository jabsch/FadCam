package com.fadcam.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LiveCounterModeTest {

    @Test
    public void calmSlidesOnlyWhenNotRecording() {
        assertFalse(LiveCounterMode.CALM.slides(true));
        assertTrue(LiveCounterMode.CALM.slides(false));
    }

    @Test
    public void smoothAlwaysSlides() {
        assertTrue(LiveCounterMode.SMOOTH.slides(true));
        assertTrue(LiveCounterMode.SMOOTH.slides(false));
    }

    @Test
    public void idsRoundTrip() {
        for (LiveCounterMode m : LiveCounterMode.values()) {
            assertEquals(m, LiveCounterMode.fromId(m.id));
        }
        assertNull(LiveCounterMode.fromId("off"));
    }
}
