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
        // No watermark (also what Direct encode reads as): nothing draws these
        // fields, so the GPS ran a whole recording for nobody.
        String option = prefs.getWatermarkOption();
        if ("no_watermark".equals(option)) return false;
        // The badge style shows no location line, only the extra fields below.
        boolean showsLocationLine = !"badge_fadcam".equals(option);
        return (showsLocationLine && (prefs.isLocalisationEnabled() || prefs.isUtmEnabled()))
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

    /**
     * Calls startForeground with {@code baseType} plus the location type when a GPS feature is
     * on and location permission is granted (ported from upstream FadCam). Without the location
     * type, Android 10+ stops delivering fixes once JaCam leaves the screen (screen off, another
     * app on top), so speed, altitude and the address froze or went stale mid-recording.
     * If the system refuses the location type (for example a start from the background on
     * Android 14+), the start is retried with {@code baseType} alone, which is the old behaviour.
     */
    @androidx.annotation.RequiresApi(api = android.os.Build.VERSION_CODES.Q)
    public static void startForegroundWithLocation(android.app.Service service, int id,
            android.app.Notification notification, int baseType) {
        int withLocation = baseType | locationForegroundType(service);
        if (withLocation != baseType) {
            try {
                androidx.core.app.ServiceCompat.startForeground(service, id, notification, withLocation);
                return;
            } catch (RuntimeException e) {
                com.fadcam.FLog.w("LocationFeedGate",
                        "startForeground with location type refused; retrying without it", e);
            }
        }
        androidx.core.app.ServiceCompat.startForeground(service, id, notification, baseType);
    }

    private static int locationForegroundType(android.content.Context context) {
        if (!needsLocationData(SharedPreferencesManager.getInstance(context))) return 0;
        boolean fine = androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean coarse = androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        return (fine || coarse) ? android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0;
    }
}
