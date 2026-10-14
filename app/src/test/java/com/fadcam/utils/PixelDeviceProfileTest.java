package com.fadcam.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PixelDeviceProfileTest {

    @Test
    public void pixel11ProXlIsOptimizedPro() {
        PixelDeviceProfile p = PixelDeviceProfile.from("Google", "Pixel 11 Pro XL");
        assertTrue(p.isPixel());
        assertEquals(11, p.getGeneration());
        assertTrue(p.isPro());
        assertTrue(p.supportsOptimizedMode());
    }

    @Test
    public void pixel11FamilyVariants() {
        assertTrue(PixelDeviceProfile.from("Google", "Pixel 11 Pro").isPro());
        assertTrue(PixelDeviceProfile.from("Google", "Pixel 11 Pro Fold").isPro());
        assertFalse(PixelDeviceProfile.from("Google", "Pixel 11").isPro());
        assertFalse(PixelDeviceProfile.from("Google", "Pixel 11a").isPro());
        assertTrue(PixelDeviceProfile.from("google", "  pixel  11a ").supportsOptimizedMode());
    }

    @Test
    public void olderPixelIsDetectedButNotOptimized() {
        PixelDeviceProfile p = PixelDeviceProfile.from("Google", "Pixel 8 Pro");
        assertTrue(p.isPixel());
        assertEquals(8, p.getGeneration());
        assertFalse(p.supportsOptimizedMode());
    }

    @Test
    public void nonPixelDevicesAreRejected() {
        assertFalse(PixelDeviceProfile.from("samsung", "SM-S928B").isPixel());
        assertFalse(PixelDeviceProfile.from("Google", "Pixel Tablet").isPixel());
        assertFalse(PixelDeviceProfile.from("Xiaomi", "Pixel 11 Pro").isPixel());
        assertFalse(PixelDeviceProfile.from(null, null).isPixel());
    }
}
