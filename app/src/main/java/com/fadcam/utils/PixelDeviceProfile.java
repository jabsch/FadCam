package com.fadcam.utils;

import android.os.Build;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identifies Google Pixel phones from build properties. Kept free of Android
 * runtime calls (except the {@link #current()} convenience) so it is unit-testable.
 */
public final class PixelDeviceProfile {

    /** First Pixel generation the "Pixel optimized" mode is tuned for. */
    public static final int OPTIMIZED_MIN_GENERATION = 11;

    // "Pixel 11", "Pixel 11 Pro", "Pixel 11 Pro XL", "Pixel 11 Pro Fold", "Pixel 11a"
    private static final Pattern PIXEL_MODEL = Pattern.compile("^pixel (\\d+)([a-z]?)(?: (.*))?$");

    private final boolean pixel;
    private final int generation;
    private final boolean pro;

    private PixelDeviceProfile(boolean pixel, int generation, boolean pro) {
        this.pixel = pixel;
        this.generation = generation;
        this.pro = pro;
    }

    public static PixelDeviceProfile current() {
        return from(Build.MANUFACTURER, Build.MODEL);
    }

    public static PixelDeviceProfile from(String manufacturer, String model) {
        if (manufacturer == null || model == null
                || !manufacturer.trim().toLowerCase(Locale.ROOT).equals("google")) {
            return new PixelDeviceProfile(false, -1, false);
        }
        String normalized = model.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        Matcher m = PIXEL_MODEL.matcher(normalized);
        if (!m.matches()) {
            return new PixelDeviceProfile(false, -1, false);
        }
        int generation;
        try {
            generation = Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return new PixelDeviceProfile(false, -1, false);
        }
        String rest = m.group(3);
        boolean pro = m.group(2).isEmpty() && rest != null && rest.startsWith("pro");
        return new PixelDeviceProfile(true, generation, pro);
    }

    public boolean isPixel() {
        return pixel;
    }

    /** Pixel generation number, or -1 when not a Pixel. */
    public int getGeneration() {
        return generation;
    }

    /** Pixel N Pro / Pro XL / Pro Fold. */
    public boolean isPro() {
        return pro;
    }

    /** Whether the Pixel optimized mode is offered (and turned on by default) here. */
    public boolean supportsOptimizedMode() {
        return pixel && generation >= OPTIMIZED_MIN_GENERATION;
    }
}
