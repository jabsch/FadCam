package com.fadcam.utils;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * How the video encoder spends bits while the remote stream server is on. Constant
 * holds the stream preset's bitrate on every frame, so a still scene costs as many
 * bytes to encode, encrypt, upload and store as a busy one. Variable lets a still
 * scene use far less, at the cost of bitrate spikes when things move. Read when a
 * recording starts; without the stream server, recordings always use variable.
 */
public enum StreamBitrateMode {
    CONSTANT("constant", R.string.stream_bitrate_constant, R.string.stream_bitrate_constant_desc),
    VARIABLE("variable", R.string.stream_bitrate_variable, R.string.stream_bitrate_variable_desc);

    private static final String PREF_MODE = "pref_stream_bitrate_mode";

    public final String id;
    @StringRes public final int labelRes;
    @StringRes public final int descriptionRes;

    StreamBitrateMode(String id, @StringRes int labelRes, @StringRes int descriptionRes) {
        this.id = id;
        this.labelRes = labelRes;
        this.descriptionRes = descriptionRes;
    }

    @Nullable
    public static StreamBitrateMode fromId(@Nullable String id) {
        for (StreamBitrateMode m : values()) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    public static StreamBitrateMode current(SharedPreferencesManager prefs) {
        StreamBitrateMode saved = fromId(prefs.sharedPreferences.getString(PREF_MODE, null));
        return saved != null ? saved : CONSTANT;
    }

    public static void save(SharedPreferencesManager prefs, StreamBitrateMode mode) {
        prefs.sharedPreferences.edit().putString(PREF_MODE, mode.id).apply();
    }
}
