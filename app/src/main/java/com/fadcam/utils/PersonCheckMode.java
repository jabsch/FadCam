package com.fadcam.utils;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * How often Motion Lab runs its AI person/object detector. The detector is by
 * far the heaviest part of motion analysis (a neural network on four CPU
 * cores); the motion check before it is cheap. {@link #ON_MOVEMENT} skips the
 * detector while the motion check sees a still scene, reusing the last result
 * and refreshing it every {@link #STILL_REFRESH_MS}.
 */
public enum PersonCheckMode {
    ON_MOVEMENT("movement", R.string.person_check_movement, R.string.person_check_movement_desc),
    EVERY_FRAME("every", R.string.person_check_every, R.string.person_check_every_desc);

    private static final String PREF_MODE = "pref_motion_person_check_mode";
    public static final long STILL_REFRESH_MS = 2000L;

    public final String id;
    @StringRes public final int labelRes;
    @StringRes public final int descriptionRes;

    PersonCheckMode(String id, @StringRes int labelRes, @StringRes int descriptionRes) {
        this.id = id;
        this.labelRes = labelRes;
        this.descriptionRes = descriptionRes;
    }

    /**
     * Whether to run the detector on this analysed frame.
     *
     * @param sceneStill  the motion check found nothing moving
     * @param msSinceLast time since the detector last ran
     */
    public boolean shouldDetect(boolean sceneStill, long msSinceLast) {
        return this == EVERY_FRAME || !sceneStill || msSinceLast >= STILL_REFRESH_MS;
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
