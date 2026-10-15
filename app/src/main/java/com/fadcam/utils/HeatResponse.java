package com.fadcam.utils;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * How early recording sheds load as the phone heats up (see
 * {@link com.fadcam.services.RecordingThermalGuard}). STANDARD sheds optional
 * work at MODERATE and halves the bitrate at SEVERE; EARLY moves each step one
 * thermal level earlier so the phone stays cooler, at some cost in bitrate and
 * smoothness while warm. Both stop and save at CRITICAL.
 */
public enum HeatResponse {
    STANDARD("standard", R.string.heat_response_standard, R.string.heat_response_standard_desc),
    EARLY("early", R.string.heat_response_early, R.string.heat_response_early_desc);

    private static final String PREF_MODE = "pref_heat_response";

    public final String id;
    @StringRes public final int labelRes;
    @StringRes public final int descriptionRes;

    HeatResponse(String id, @StringRes int labelRes, @StringRes int descriptionRes) {
        this.id = id;
        this.labelRes = labelRes;
        this.descriptionRes = descriptionRes;
    }

    @Nullable
    public static HeatResponse fromId(@Nullable String id) {
        for (HeatResponse m : values()) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    public static HeatResponse current(SharedPreferencesManager prefs) {
        HeatResponse saved = fromId(prefs.sharedPreferences.getString(PREF_MODE, null));
        return saved != null ? saved : STANDARD;
    }

    public static void save(SharedPreferencesManager prefs, HeatResponse mode) {
        prefs.sharedPreferences.edit().putString(PREF_MODE, mode.id).apply();
    }
}
