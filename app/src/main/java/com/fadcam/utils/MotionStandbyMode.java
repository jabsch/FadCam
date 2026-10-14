package com.fadcam.utils;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * What the camera does while a Motion Lab recording waits, paused, for motion.
 * Nothing is recorded or drawn then, but the camera stays open for the motion
 * check. {@link #LOW_POWER} stops sending it full-size video frames and runs it
 * at {@link #STANDBY_FPS} until recording resumes; {@link #READY} keeps the
 * full feed so the first frames after a trigger arrive without delay.
 */
public enum MotionStandbyMode {
    LOW_POWER("low", R.string.motion_standby_low, R.string.motion_standby_low_desc),
    READY("ready", R.string.motion_standby_ready, R.string.motion_standby_ready_desc);

    private static final String PREF_MODE = "pref_motion_standby_mode";
    /** Camera frame rate in low-power standby; above the motion check's 6 fps maximum. */
    public static final int STANDBY_FPS = 15;

    public final String id;
    @StringRes public final int labelRes;
    @StringRes public final int descriptionRes;

    MotionStandbyMode(String id, @StringRes int labelRes, @StringRes int descriptionRes) {
        this.id = id;
        this.labelRes = labelRes;
        this.descriptionRes = descriptionRes;
    }

    /** Camera frame rate in low-power standby, given the recording rate. */
    public static int standbyFps(int recordingFps) {
        return Math.min(recordingFps, STANDBY_FPS);
    }

    @Nullable
    public static MotionStandbyMode fromId(@Nullable String id) {
        for (MotionStandbyMode m : values()) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    public static MotionStandbyMode current(SharedPreferencesManager prefs) {
        MotionStandbyMode saved = fromId(prefs.sharedPreferences.getString(PREF_MODE, null));
        return saved != null ? saved : LOW_POWER;
    }

    public static void save(SharedPreferencesManager prefs, MotionStandbyMode mode) {
        prefs.sharedPreferences.edit().putString(PREF_MODE, mode.id).apply();
    }
}
