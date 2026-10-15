package com.fadcam.utils;

import androidx.annotation.Nullable;
import com.fadcam.Constants;
import com.fadcam.SharedPreferencesManager;

/**
 * "Direct encode" (Video settings, off by default): the camera writes the video
 * encoder's input itself instead of every frame being redrawn through OpenGL
 * first. That removes a full-resolution GPU pass per recorded frame, along with
 * everything that pass adds: watermarks, front-camera mirroring, Motion Lab, live
 * camera switching and the app's exposure boost. A recording it can't handle
 * (120 fps and up, or a size the camera can't send to the encoder) uses the
 * normal path, still without watermarks.
 */
public final class DirectEncodeMode {
    private static final String PREF_ENABLED = "pref_direct_encode_enabled";

    private DirectEncodeMode() {}

    public static boolean isEnabled(@Nullable SharedPreferencesManager prefs) {
        return prefs != null && prefs.safeGetBoolean(PREF_ENABLED, false);
    }

    public static void setEnabled(SharedPreferencesManager prefs, boolean enabled) {
        prefs.sharedPreferences.edit().putBoolean(PREF_ENABLED, enabled).apply();
    }

    /** Turns it on and switches off the settings it can't record. */
    public static void enable(SharedPreferencesManager prefs) {
        prefs.sharedPreferences.edit().putString(Constants.PREF_WATERMARK_OPTION, "no_watermark").apply();
        prefs.setDigitalForensicsEnabled(false);
        prefs.setFrontVideoMirrorEnabled(false);
        prefs.setMotionModeEnabled(false);
        setEnabled(prefs, true);
    }
}
