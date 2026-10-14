package com.fadcam.utils;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import com.fadcam.CameraType;
import com.fadcam.Constants;
import com.fadcam.FLog;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.VideoCodec;

/**
 * "Pixel optimized" mode for Pixel 11 series phones: applies recording defaults
 * tuned for long, cool-running sessions on Tensor hardware and hides settings
 * rows that do nothing on these phones. On by default on supported Pixels and
 * switchable from Video settings, where turning it off brings every row back.
 */
public final class PixelOptimizedMode {
    private static final String TAG = "PixelOptimizedMode";

    private static final String PREF_ENABLED = "pixel_optimized_mode_enabled";
    private static final String PREF_PROFILE_VERSION = "pixel_optimized_profile_version";
    /** Bump when the tuned profile changes so it is re-applied once. */
    private static final int PROFILE_VERSION = 1;

    // Tensor's HEVC encoder at 1080p30 keeps quality high while running far
    // cooler than 4K or 60 fps on long background recordings.
    private static final int TUNED_WIDTH = 1920;
    private static final int TUNED_HEIGHT = 1080;
    private static final int TUNED_FPS = 30;

    private PixelOptimizedMode() {}

    /** Whether this phone gets the mode at all. */
    public static boolean isSupported() {
        return PixelDeviceProfile.current().supportsOptimizedMode();
    }

    public static boolean isEnabled(SharedPreferencesManager prefs) {
        return isSupported() && prefs.safeGetBoolean(PREF_ENABLED, true);
    }

    /** Turns the mode on or off; turning it on applies the tuned profile. */
    public static void setEnabled(SharedPreferencesManager prefs, boolean enabled) {
        prefs.sharedPreferences.edit().putBoolean(PREF_ENABLED, enabled).apply();
        if (enabled) {
            applyTunedProfile(prefs);
        }
    }

    /**
     * Applies the tuned profile once per {@link #PROFILE_VERSION} on supported
     * phones with the mode on. Safe to call on every app start.
     */
    public static void applyOnceIfNeeded(Context context) {
        if (!isSupported()) {
            return;
        }
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(context);
        if (!isEnabled(prefs) || prefs.safeGetInt(PREF_PROFILE_VERSION, 0) >= PROFILE_VERSION) {
            return;
        }
        applyTunedProfile(prefs);
    }

    private static void applyTunedProfile(SharedPreferencesManager prefs) {
        FLog.i(TAG, "Applying Pixel optimized recording profile v" + PROFILE_VERSION);
        prefs.sharedPreferences.edit()
                .putString(Constants.PREF_VIDEO_CODEC, VideoCodec.HEVC.toString())
                .putInt(Constants.PREF_VIDEO_RESOLUTION_WIDTH, TUNED_WIDTH)
                .putInt(Constants.PREF_VIDEO_RESOLUTION_HEIGHT, TUNED_HEIGHT)
                .putBoolean("bitrate_mode_custom", false)
                .putInt(PREF_PROFILE_VERSION, PROFILE_VERSION)
                .apply();
        prefs.setSpecificVideoFrameRate(CameraType.BACK, TUNED_FPS);
        prefs.setSpecificVideoFrameRate(CameraType.FRONT, TUNED_FPS);
        RecordingPreset.markCurrent(prefs, RecordingPreset.HD_30);
        // OIS is nearly free; EIS warps every frame and adds heat on long recordings.
        VideoStabilizationMode.save(prefs, VideoStabilizationMode.OPTICAL);
    }

    /**
     * Hides the given settings rows when the mode is on, together with the
     * divider drawn just above each one.
     */
    public static void hideRowsIfEnabled(View root, int... rowIds) {
        if (root == null || !isEnabled(SharedPreferencesManager.getInstance(root.getContext()))) {
            return;
        }
        for (int id : rowIds) {
            View row = root.findViewById(id);
            if (row == null) {
                continue;
            }
            row.setVisibility(View.GONE);
            if (row.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) row.getParent();
                int index = parent.indexOfChild(row);
                View above = index > 0 ? parent.getChildAt(index - 1) : null;
                if (above != null && above.getId() == View.NO_ID && above.getClass() == View.class) {
                    above.setVisibility(View.GONE);
                }
            }
        }
    }
}
