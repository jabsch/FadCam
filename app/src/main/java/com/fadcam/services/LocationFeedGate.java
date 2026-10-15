package com.fadcam.services;

import com.fadcam.SharedPreferencesManager;

/**
 * Pure decision logic for whether a recording session needs a live GPS feed.
 *
 * Extracted from {@link RecordingService} so it can be unit-tested without an
 * Android Service instance. Drives whether the LocationHelper exists at all —
 * if any feature needs location data but the helper is missing, speed/altitude
 * watermarks silently read 0 (the bug users reported when the "location
 * watermark" toggle was off).
 */
public final class LocationFeedGate {

    private LocationFeedGate() {}

    /**
     * True if any user-enabled watermark feature needs a live GPS feed.
     * Critically includes the extended sensor watermarks
     * (speed/altitude/accuracy/compass) and weather, NOT just the "location
     * watermark" toggle. Location embedding is not here: it has its own
     * one-fix GeotagHelper feed, and nothing reads this feed for it.
     */
    public static boolean needsLocationData(SharedPreferencesManager prefs) {
        if (prefs == null) return false;
        // Direct encode turns every watermark off, so nothing reads the feed.
        if (com.fadcam.utils.DirectEncodeMode.isEnabled(prefs)) return false;
        return prefs.isLocalisationEnabled()
                || prefs.isUtmEnabled()
                || prefs.isSpeedEnabled()
                || prefs.isAltitudeEnabled()
                || prefs.isAccuracyEnabled()
                || prefs.isCompassEnabled()
                || prefs.isWeatherEnabled();
    }

    /**
     * True if an enabled feature needs a fix every second: speed, altitude and
     * accuracy readouts, and the compass (which falls back to GPS bearing).
     * Location text, UTM and weather are fine with a slower
     * feed, which lets the GPS radio idle between fixes.
     */
    public static boolean needsFastLocation(SharedPreferencesManager prefs) {
        if (prefs == null) return false;
        return prefs.isSpeedEnabled()
                || prefs.isAltitudeEnabled()
                || prefs.isAccuracyEnabled()
                || prefs.isCompassEnabled();
    }
}
