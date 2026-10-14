package com.fadcam.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class MotionStandbyModeTest {

    @Test
    public void standbyCapsTheFrameRate() {
        assertEquals(MotionStandbyMode.STANDBY_FPS, MotionStandbyMode.standbyFps(60));
        assertEquals(MotionStandbyMode.STANDBY_FPS, MotionStandbyMode.standbyFps(30));
        assertEquals(10, MotionStandbyMode.standbyFps(10));
    }

    @Test
    public void idsRoundTrip() {
        for (MotionStandbyMode m : MotionStandbyMode.values()) {
            assertEquals(m, MotionStandbyMode.fromId(m.id));
        }
        assertNull(MotionStandbyMode.fromId("off"));
    }
}
