package com.fadcam.utils;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * How often Motion Lab runs its AI person/object detector. The detector is by
 * far the heaviest part of motion analysis (a neural network on up to four CPU
 * cores); the motion check before it is cheap. {@link #ON_MOVEMENT} and
 * {@link #ON_MOVEMENT_SLOW} skip the detector while the motion check sees a still
 * scene, reusing the last result and refreshing it every 2 s or 10 s.
 */
public enum PersonCheckMode {
    ON_MOVEMENT("movement", R.string.person_check_movement, R.string.person_check_movement_desc, 2000L),
    ON_MOVEMENT_SLOW("movement_slow", R.string.person_check_movement_slow,
            R.string.person_check_movement_slow_desc, 10_000L),
    EVERY_FRAME("every", R.string.person_check_every, R.string.person_check_every_desc, 0L);

    private static final String PREF_MODE = "pref_motion_person_check_mode";

    public final String id;
    @StringRes public final int labelRes;
    @StringRes public final int descriptionRes;
    /** How often a still scene is checked again (0 = every frame). */
    public final long stillRefreshMs;

    PersonCheckMode(String id, @StringRes int labelRes, @StringRes int descriptionRes, long stillRefreshMs) {
        this.id = id;
        this.labelRes = labelRes;
        this.descriptionRes = descriptionRes;
        this.stillRefreshMs = stillRefreshMs;
    }

    /**
     * Whether to run the detector on this analysed frame.
     *
     * @param sceneStill  the motion check found nothing moving
     * @param msSinceLast time since the detector last ran
     */
    public boolean shouldDetect(boolean sceneStill, long msSinceLast) {
        return this == EVERY_FRAME || !sceneStill || msSinceLast >= stillRefreshMs;
    }

    @Nullable
    public static PersonCheckMode fromId(@Nullable String id) {
        for (PersonCheckMode m : values()) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    public static PersonCheckMode current(SharedPreferencesManager prefs) {
        PersonCheckMode saved = fromId(prefs.sharedPreferences.getString(PREF_MODE, null));
        return saved != null ? saved : ON_MOVEMENT;
    }

    public static void save(SharedPreferencesManager prefs, PersonCheckMode mode) {
        prefs.sharedPreferences.edit().putString(PREF_MODE, mode.id).apply();
    }
}
