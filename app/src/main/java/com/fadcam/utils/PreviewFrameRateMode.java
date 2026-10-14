package com.fadcam.utils;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * How often the on-screen preview is redrawn. While recording, the recording
 * itself always gets every camera frame; this only limits the extra full-screen
 * draw (and the display composition) that the preview costs, and {@link #AUTO}
 * keeps the full rate until the phone is warm, then drops to 15 fps. When only
 * previewing (nothing recording), the camera itself runs at the cap, so the
 * sensor, ISP and GPU all do less; {@link #AUTO} uses 30 fps there.
 */
public enum PreviewFrameRateMode {
    FULL("full", 0, R.string.preview_fps_full, R.string.preview_fps_full_desc),
    FPS_30("30", 30, R.string.preview_fps_30, R.string.preview_fps_30_desc),
    FPS_15("15", 15, R.string.preview_fps_15, R.string.preview_fps_15_desc),
    AUTO("auto", 0, R.string.preview_fps_auto, R.string.preview_fps_auto_desc);

    private static final String PREF_MODE = "pref_preview_fps_mode";
    static final int WARM_FPS = 15;
    static final int AUTO_PREVIEW_ONLY_FPS = 30;

    public final String id;
    private final int capFps;
    @StringRes public final int labelRes;
    @StringRes public final int descriptionRes;

    PreviewFrameRateMode(String id, int capFps, @StringRes int labelRes, @StringRes int descriptionRes) {
        this.id = id;
        this.capFps = capFps;
        this.labelRes = labelRes;
        this.descriptionRes = descriptionRes;
    }

    /** Preview frame-rate cap in fps, 0 for uncapped; {@code warm} is the thermal guard's warm step. */
    public int capFps(boolean warm) {
        return this == AUTO ? (warm ? WARM_FPS : 0) : capFps;
    }

    /** Camera frame-rate cap while only previewing, 0 for none (the recording frame rate). */
    public int previewOnlyFps() {
        return this == AUTO ? AUTO_PREVIEW_ONLY_FPS : capFps;
    }

    @Nullable
    public static PreviewFrameRateMode fromId(@Nullable String id) {
        for (PreviewFrameRateMode m : values()) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    public static PreviewFrameRateMode current(SharedPreferencesManager prefs) {
        PreviewFrameRateMode saved = fromId(prefs.sharedPreferences.getString(PREF_MODE, null));
        return saved != null ? saved : AUTO;
    }

    public static void save(SharedPreferencesManager prefs, PreviewFrameRateMode mode) {
        prefs.sharedPreferences.edit().putString(PREF_MODE, mode.id).apply();
    }
}
