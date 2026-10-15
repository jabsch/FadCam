package com.fadcam.utils;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * Camera size while only previewing (nothing recording). The preview fills a
 * screen-sized view, so a 4K camera stream makes the sensor, image processor and
 * GPU handle four times the pixels of 1080p for nothing. Starting a recording
 * opens a new session at the full recording resolution either way.
 */
public enum PreviewResolutionMode {
    SCREEN("screen", R.string.preview_resolution_screen, R.string.preview_resolution_screen_desc),
    FULL("full", R.string.preview_resolution_full, R.string.preview_resolution_full_desc);

    private static final String PREF_MODE = "pref_preview_resolution_mode";
    /** {@link #SCREEN}'s limit on the short side, in pixels. */
    static final int SCREEN_MAX_SHORT_SIDE = 1080;

    public final String id;
    @StringRes public final int labelRes;
    @StringRes public final int descriptionRes;

    PreviewResolutionMode(String id, @StringRes int labelRes, @StringRes int descriptionRes) {
        this.id = id;
        this.labelRes = labelRes;
        this.descriptionRes = descriptionRes;
    }

    @Nullable
    public static PreviewResolutionMode fromId(@Nullable String id) {
        for (PreviewResolutionMode m : values()) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    public static PreviewResolutionMode current(SharedPreferencesManager prefs) {
        PreviewResolutionMode saved = fromId(prefs.sharedPreferences.getString(PREF_MODE, null));
        return saved != null ? saved : SCREEN;
    }

    public static void save(SharedPreferencesManager prefs, PreviewResolutionMode mode) {
        prefs.sharedPreferences.edit().putString(PREF_MODE, mode.id).apply();
    }

    /**
     * The largest camera output size with the video's shape and a short side of at
     * most {@link #SCREEN_MAX_SHORT_SIDE}, as {width, height} in the video's own
     * orientation; null to keep the video size (already that small, or no size of
     * the same shape). {@code candidates} are {width, height} pairs as the camera
     * lists them.
     */
    @Nullable
    public static int[] pickScreenSize(int videoWidth, int videoHeight, int[][] candidates) {
        long videoLong = Math.max(videoWidth, videoHeight);
        long videoShort = Math.min(videoWidth, videoHeight);
        if (videoShort <= SCREEN_MAX_SHORT_SIDE || candidates == null) {
            return null;
        }
        int bestLong = 0;
        int bestShort = 0;
        for (int[] c : candidates) {
            if (c == null || c.length < 2) {
                continue;
            }
            int cLong = Math.max(c[0], c[1]);
            int cShort = Math.min(c[0], c[1]);
            if (cShort <= 0 || cShort > SCREEN_MAX_SHORT_SIDE) {
                continue;
            }
            // Same shape within 1%: the preview must not crop or stretch differently
            long diff = Math.abs(videoLong * cShort - videoShort * cLong);
            if (diff * 100 > videoShort * cLong) {
                continue;
            }
            if (cShort > bestShort) {
                bestLong = cLong;
                bestShort = cShort;
            }
        }
        if (bestShort == 0) {
            return null;
        }
        return videoWidth >= videoHeight
                ? new int[] {bestLong, bestShort}
                : new int[] {bestShort, bestLong};
    }
}
