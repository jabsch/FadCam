package com.fadcam.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PreviewResolutionModeTest {

    private static final int[][] PIXEL_SIZES = {
        {4032, 3024}, {3840, 2160}, {2560, 1440}, {1920, 1440}, {1920, 1080},
        {1440, 1080}, {1280, 720}, {640, 480},
    };

    @Test
    public void fourKPreviewsAt1080p() {
        assertArrayEquals(new int[] {1920, 1080},
                PreviewResolutionMode.pickScreenSize(3840, 2160, PIXEL_SIZES));
    }

    @Test
    public void portraitVideoKeepsItsOrientation() {
        assertArrayEquals(new int[] {1080, 1920},
                PreviewResolutionMode.pickScreenSize(2160, 3840, PIXEL_SIZES));
    }

    @Test
    public void fourByThreeKeepsItsShape() {
        assertArrayEquals(new int[] {1440, 1080},
                PreviewResolutionMode.pickScreenSize(4032, 3024, PIXEL_SIZES));
    }

    @Test
    public void smallVideoKeepsItsSize() {
        assertNull(PreviewResolutionMode.pickScreenSize(1920, 1080, PIXEL_SIZES));
        assertNull(PreviewResolutionMode.pickScreenSize(1280, 720, PIXEL_SIZES));
    }

    @Test
    public void noSizeOfTheSameShapeKeepsTheVideoSize() {
        assertNull(PreviewResolutionMode.pickScreenSize(3840, 2160, new int[][] {{1440, 1080}, {640, 480}}));
        assertNull(PreviewResolutionMode.pickScreenSize(3840, 2160, null));
    }

    @Test
    public void idsRoundTrip() {
        for (PreviewResolutionMode m : PreviewResolutionMode.values()) {
            assertEquals(m, PreviewResolutionMode.fromId(m.id));
        }
        assertNull(PreviewResolutionMode.fromId("huge"));
    }
}
