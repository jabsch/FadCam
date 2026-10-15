package com.fadcam.utils;

import android.util.Range;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * Frame rate of the picture-in-picture camera in dual-camera recording. The PiP is
 * a small box, so 15 fps there costs the second camera, its ISP and the GPU about
 * half the frames while the main video keeps its full rate.
 */
public enum PipFrameRate {
    SAME("same", 0, R.string.pip_fps_same, R.string.pip_fps_same_desc),
    FPS_15("15", 15, R.string.pip_fps_15, R.string.pip_fps_15_desc);

    private static final String PREF_MODE = "pref_pip_camera_fps";

    public final String id;
    /** Target fps, 0 for the camera's default (same as the main camera). */
    public final int fps;
    @StringRes public final int labelRes;
    @StringRes public final int descriptionRes;

    PipFrameRate(String id, int fps, @StringRes int labelRes, @StringRes int descriptionRes) {
        this.id = id;
        this.fps = fps;
        this.labelRes = labelRes;
        this.descriptionRes = descriptionRes;
    }

    /**
     * Supported AE range for {@code target}: exactly [target,target], else the
     * range containing it with the lowest top. Null when none contains it.
     */
    @Nullable
    public static Range<Integer> pickRange(@Nullable Range<Integer>[] ranges, int target) {
        if (ranges == null || target <= 0) {
            return null;
        }
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            if (r.getLower() == target && r.getUpper() == target) {
                return r;
            }
            if (r.getLower() <= target && r.getUpper() >= target
                    && (best == null || r.getUpper() < best.getUpper())) {
                best = r;
            }
        }
        return best;
    }

    @Nullable
    public static PipFrameRate fromId(@Nullable String id) {
        for (PipFrameRate m : values()) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    public static PipFrameRate current(SharedPreferencesManager prefs) {
        PipFrameRate saved = fromId(prefs.sharedPreferences.getString(PREF_MODE, null));
        return saved != null ? saved : SAME;
    }

    public static void save(SharedPreferencesManager prefs, PipFrameRate mode) {
        prefs.sharedPreferences.edit().putString(PREF_MODE, mode.id).apply();
    }
}
