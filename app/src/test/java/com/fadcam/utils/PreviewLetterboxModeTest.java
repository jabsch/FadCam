package com.fadcam.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PreviewLetterboxModeTest {

    @Test
    public void onlyCameraFillDrawsTheBars() {
        assertTrue(PreviewLetterboxMode.CAMERA_FILL.fillsBars());
        assertFalse(PreviewLetterboxMode.BLACK.fillsBars());
    }

    @Test
    public void idsRoundTrip() {
        for (PreviewLetterboxMode m : PreviewLetterboxMode.values()) {
            assertEquals(m, PreviewLetterboxMode.fromId(m.id));
        }
        assertNull(PreviewLetterboxMode.fromId("blur"));
    }
}
