package com.fadcam.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VideoStabilizationModeTest {

    @Test
    public void opticalIsOnForEveryModeButOff() {
        assertFalse(VideoStabilizationMode.OFF.usesOptical());
        assertTrue(VideoStabilizationMode.OPTICAL.usesOptical());
        assertTrue(VideoStabilizationMode.FULL.usesOptical());
        assertTrue(VideoStabilizationMode.AUTO.usesOptical());
    }

    @Test
    public void electronicFollowsModeAndAutoDropsItWhenWarm() {
        assertFalse(VideoStabilizationMode.OFF.usesElectronic(false));
        assertFalse(VideoStabilizationMode.OPTICAL.usesElectronic(false));
        assertTrue(VideoStabilizationMode.FULL.usesElectronic(false));
        assertTrue(VideoStabilizationMode.FULL.usesElectronic(true));
        assertTrue(VideoStabilizationMode.AUTO.usesElectronic(false));
        assertFalse(VideoStabilizationMode.AUTO.usesElectronic(true));
    }

    @Test
    public void idsRoundTrip() {
        for (VideoStabilizationMode m : VideoStabilizationMode.values()) {
            assertEquals(m, VideoStabilizationMode.fromId(m.id));
        }
        assertNull(VideoStabilizationMode.fromId("bogus"));
        assertNull(VideoStabilizationMode.fromId(null));
    }
}
