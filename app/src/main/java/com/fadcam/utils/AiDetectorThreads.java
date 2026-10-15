package com.fadcam.utils;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * How many CPU cores Motion Lab's AI person/object detector may use. More cores
 * answer sooner but run the phone's big cores harder, which is most of the heat
 * the detector makes.
 */
public enum AiDetectorThreads {
    COOL("1", 1, R.string.ai_threads_cool, R.string.ai_threads_cool_desc),
    BALANCED("2", 2, R.string.ai_threads_balanced, R.string.ai_threads_balanced_desc),
    FAST("4", 4, R.string.ai_threads_fast, R.string.ai_threads_fast_desc);

    private static final String PREF_THREADS = "pref_motion_ai_threads";

    public final String id;
    public final int threads;
    @StringRes public final int labelRes;
    @StringRes public final int descriptionRes;

    AiDetectorThreads(String id, int threads, @StringRes int labelRes, @StringRes int descriptionRes) {
        this.id = id;
        this.threads = threads;
        this.labelRes = labelRes;
        this.descriptionRes = descriptionRes;
    }

    @Nullable
    public static AiDetectorThreads fromId(@Nullable String id) {
        for (AiDetectorThreads t : values()) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        return null;
    }

    public static AiDetectorThreads current(SharedPreferencesManager prefs) {
        AiDetectorThreads saved = fromId(prefs.sharedPreferences.getString(PREF_THREADS, null));
        return saved != null ? saved : BALANCED;
    }

    public static void save(SharedPreferencesManager prefs, AiDetectorThreads value) {
        prefs.sharedPreferences.edit().putString(PREF_THREADS, value.id).apply();
    }
}
