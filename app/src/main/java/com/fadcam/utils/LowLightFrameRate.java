package com.fadcam.utils;

import android.util.Range;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * Whether the camera may lower its frame rate in dim light. A fixed range makes
 * auto-exposure keep every frame short and boost gain; letting it drop to half
 * the target lets each frame expose longer, and the sensor, ISP, GPU and encoder
 * all handle fewer frames while it is dark.
 */
public enum LowLightFrameRate {
    STEADY("steady", R.string.low_light_fps_steady, R.string.low_light_fps_steady_desc),
    DROP("drop", R.string.low_light_fps_drop, R.string.low_light_fps_drop_desc);

    private static final String PREF_MODE = "pref_low_light_fps_mode";

    public final String id;
    @StringRes public final int labelRes;
    @StringRes public final int descriptionRes;

    LowLightFrameRate(String id, @StringRes int labelRes, @StringRes int descriptionRes) {
        this.id = id;
        this.labelRes = labelRes;
        this.descriptionRes = descriptionRes;
    }

    /**
     * The range to use for {@link #DROP}: top at {@code target}, bottom as low as
     * possible but not under half of it. Null when the camera offers no such range,
     * so the caller keeps its fixed-range choice.
     */
    @Nullable
    public static Range<Integer> pickDropRange(@Nullable Range<Integer>[] ranges, int target) {
        if (ranges == null || target <= 1) {
            return null;
        }
        int floor = (target + 1) / 2;
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            int lower = r.getLower();
            if (r.getUpper() == target && lower < target && lower >= floor
                    && (best == null || lower < best.getLower())) {
                best = r;
            }
        }
        return best;
    }

    @Nullable
    public static LowLightFrameRate fromId(@Nullable String id) {
        for (LowLightFrameRate m : values()) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    public static LowLightFrameRate current(SharedPreferencesManager prefs) {
        LowLightFrameRate saved = fromId(prefs.sharedPreferences.getString(PREF_MODE, null));
        return saved != null ? saved : STEADY;
    }

    public static void save(SharedPreferencesManager prefs, LowLightFrameRate mode) {
        prefs.sharedPreferences.edit().putString(PREF_MODE, mode.id).apply();
    }
}
