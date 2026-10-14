package com.fadcam.utils;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * How often cloud remote mode talks to the relay: pushing the phone's status
 * to the dashboard and checking for commands sent from it. Each exchange is an
 * HTTPS request, and frequent requests keep the cellular/Wi-Fi radio in its
 * high-power state.
 */
public enum CloudUpdateRate {
    FAST("fast", 2000L, 1500L, R.string.cloud_rate_fast, R.string.cloud_rate_fast_desc),
    BALANCED("balanced", 4000L, 3000L, R.string.cloud_rate_balanced, R.string.cloud_rate_balanced_desc),
    SAVER("saver", 8000L, 6000L, R.string.cloud_rate_saver, R.string.cloud_rate_saver_desc);

    private static final String PREF_MODE = "pref_cloud_update_rate";

    public final String id;
    public final long statusIntervalMs;
    public final long commandIntervalMs;
    @StringRes public final int labelRes;
    @StringRes public final int descriptionRes;

    CloudUpdateRate(String id, long statusIntervalMs, long commandIntervalMs,
            @StringRes int labelRes, @StringRes int descriptionRes) {
        this.id = id;
        this.statusIntervalMs = statusIntervalMs;
        this.commandIntervalMs = commandIntervalMs;
        this.labelRes = labelRes;
        this.descriptionRes = descriptionRes;
    }

    @Nullable
    public static CloudUpdateRate fromId(@Nullable String id) {
        for (CloudUpdateRate r : values()) {
            if (r.id.equals(id)) {
                return r;
            }
        }
        return null;
    }

    public static CloudUpdateRate current(SharedPreferencesManager prefs) {
        CloudUpdateRate saved = fromId(prefs.sharedPreferences.getString(PREF_MODE, null));
        return saved != null ? saved : BALANCED;
    }

    public static void save(SharedPreferencesManager prefs, CloudUpdateRate rate) {
        prefs.sharedPreferences.edit().putString(PREF_MODE, rate.id).apply();
    }
}
