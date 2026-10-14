package com.fadcam.utils;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * Video stabilization choice from Video settings. Optical stabilization (OIS)
 * moves the lens and costs almost nothing; electronic stabilization (EIS)
 * crops and warps every frame in the camera pipeline, which adds steady heat
 * on long recordings. {@link #AUTO} keeps both until the phone is warm, then
 * drops EIS until it cools down.
 */
public enum VideoStabilizationMode {
    OFF("off", R.string.stabilization_off, R.string.stabilization_off_desc),
    OPTICAL("optical", R.string.stabilization_optical, R.string.stabilization_optical_desc),
    FULL("full", R.string.stabilization_full, R.string.stabilization_full_desc),
    AUTO("auto", R.string.stabilization_auto, R.string.stabilization_auto_desc);

    private static final String PREF_MODE = "pref_video_stabilization_mode";

    public final String id;
    @StringRes public final int labelRes;
    @StringRes public final int descriptionRes;

    VideoStabilizationMode(String id, @StringRes int labelRes, @StringRes int descriptionRes) {
        this.id = id;
        this.labelRes = labelRes;
        this.descriptionRes = descriptionRes;
    }

    public boolean usesOptical() {
        return this != OFF;
    }

    /** Whether to request EIS; {@code warm} is the thermal guard's warm step for {@link #AUTO}. */
    public boolean usesElectronic(boolean warm) {
        return this == FULL || (this == AUTO && !warm);
    }

    @Nullable
    public static VideoStabilizationMode fromId(@Nullable String id) {
        for (VideoStabilizationMode m : values()) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    /**
     * The saved mode. Before this setting existed stabilization was an on/off
     * switch (default on), which maps to {@link #FULL} or {@link #OFF}.
     */
    public static VideoStabilizationMode current(SharedPreferencesManager prefs) {
        VideoStabilizationMode saved = fromId(prefs.sharedPreferences.getString(PREF_MODE, null));
        if (saved != null) {
            return saved;
        }
        return prefs.sharedPreferences.getBoolean(Constants.PREF_VIDEO_STABILIZATION_ENABLED, true) ? FULL : OFF;
    }

    public static void save(SharedPreferencesManager prefs, VideoStabilizationMode mode) {
        prefs.sharedPreferences.edit().putString(PREF_MODE, mode.id).apply();
    }
}
