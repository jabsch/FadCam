package com.fadcam.utils;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * Whether cloud remote mode uploads recorded video to the relay all the time, or
 * only while someone has the stream open. Each upload encrypts a segment and sends
 * it over HTTPS every couple of seconds, which keeps the radio and CPU busy.
 */
public enum CloudUploadMode {
    ALWAYS("always", R.string.cloud_upload_always, R.string.cloud_upload_always_desc),
    WHEN_WATCHED("watched", R.string.cloud_upload_watched, R.string.cloud_upload_watched_desc);

    private static final String PREF_MODE = "pref_cloud_upload_mode";

    public final String id;
    @StringRes public final int labelRes;
    @StringRes public final int descriptionRes;

    CloudUploadMode(String id, @StringRes int labelRes, @StringRes int descriptionRes) {
        this.id = id;
        this.labelRes = labelRes;
        this.descriptionRes = descriptionRes;
    }

    @Nullable
    public static CloudUploadMode fromId(@Nullable String id) {
        for (CloudUploadMode m : values()) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    public static CloudUploadMode current(SharedPreferencesManager prefs) {
        CloudUploadMode saved = fromId(prefs.sharedPreferences.getString(PREF_MODE, null));
        return saved != null ? saved : ALWAYS;
    }

    public static void save(SharedPreferencesManager prefs, CloudUploadMode mode) {
        prefs.sharedPreferences.edit().putString(PREF_MODE, mode.id).apply();
    }
}
