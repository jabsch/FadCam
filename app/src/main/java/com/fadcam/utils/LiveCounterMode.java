package com.fadcam.utils;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * How the Home screen's live counters (elapsed time, time left, the timer on
 * the record button) change while recording. Each slide redraws the screen at
 * its full refresh rate for part of every second; {@link #CALM} changes the
 * numbers instantly while a recording runs and keeps the slide otherwise.
 */
public enum LiveCounterMode {
    CALM("calm", R.string.live_counter_calm, R.string.live_counter_calm_desc),
    SMOOTH("smooth", R.string.live_counter_smooth, R.string.live_counter_smooth_desc);

    private static final String PREF_MODE = "pref_live_counter_mode";

    public final String id;
    @StringRes public final int labelRes;
    @StringRes public final int descriptionRes;

    LiveCounterMode(String id, @StringRes int labelRes, @StringRes int descriptionRes) {
        this.id = id;
        this.labelRes = labelRes;
        this.descriptionRes = descriptionRes;
    }

    /** Whether a counter change should slide; {@code recording} covers paused too. */
    public boolean slides(boolean recording) {
        return this == SMOOTH || !recording;
    }

    @Nullable
    public static LiveCounterMode fromId(@Nullable String id) {
        for (LiveCounterMode m : values()) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    public static LiveCounterMode current(SharedPreferencesManager prefs) {
        LiveCounterMode saved = fromId(prefs.sharedPreferences.getString(PREF_MODE, null));
        return saved != null ? saved : CALM;
    }

    public static void save(SharedPreferencesManager prefs, LiveCounterMode mode) {
        prefs.sharedPreferences.edit().putString(PREF_MODE, mode.id).apply();
    }
}
