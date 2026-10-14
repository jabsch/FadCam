package com.fadcam.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PreviewFrameRateModeTest {

    @Test
    public void fixedModesIgnoreHeat() {
        assertEquals(0, PreviewFrameRateMode.FULL.capFps(false));
        assertEquals(0, PreviewFrameRateMode.FULL.capFps(true));
        assertEquals(30, PreviewFrameRateMode.FPS_30.capFps(true));
        assertEquals(15, PreviewFrameRateMode.FPS_15.capFps(false));
    }

    @Test
    public void autoIsUncappedUntilWarm() {
        assertEquals(0, PreviewFrameRateMode.AUTO.capFps(false));
        assertEquals(PreviewFrameRateMode.WARM_FPS, PreviewFrameRateMode.AUTO.capFps(true));
    }

    @Test
    public void previewOnlyCapsTheCamera() {
        assertEquals(0, PreviewFrameRateMode.FULL.previewOnlyFps());
        assertEquals(30, PreviewFrameRateMode.FPS_30.previewOnlyFps());
        assertEquals(15, PreviewFrameRateMode.FPS_15.previewOnlyFps());
        assertEquals(PreviewFrameRateMode.AUTO_PREVIEW_ONLY_FPS, PreviewFrameRateMode.AUTO.previewOnlyFps());
    }

    @Test
    public void idsRoundTrip() {
        for (PreviewFrameRateMode m : PreviewFrameRateMode.values()) {
            assertEquals(m, PreviewFrameRateMode.fromId(m.id));
        }
        assertNull(PreviewFrameRateMode.fromId("60"));
    }
}
