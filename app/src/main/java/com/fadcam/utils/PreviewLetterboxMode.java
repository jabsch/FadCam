package com.fadcam.utils;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * What fills the empty edges when the on-screen preview's shape doesn't match
 * the video's. {@link #CAMERA_FILL} draws a dimmed, zoomed copy of the camera
 * behind the preview, which costs two extra full-screen GPU passes on every
 * preview frame; {@link #BLACK} leaves the edges black.
 */
public enum PreviewLetterboxMode {
    CAMERA_FILL("fill", R.string.preview_bars_fill, R.string.preview_bars_fill_desc),
    BLACK("black", R.string.preview_bars_black, R.string.preview_bars_black_desc);

    private static final String PREF_MODE = "pref_preview_letterbox_mode";

    public final String id;
    @StringRes public final int labelRes;
    @StringRes public final int descriptionRes;

    PreviewLetterboxMode(String id, @StringRes int labelRes, @StringRes int descriptionRes) {
        this.id = id;
        this.labelRes = labelRes;
        this.descriptionRes = descriptionRes;
    }

    public boolean fillsBars() {
        return this == CAMERA_FILL;
    }

    @Nullable
    public static PreviewLetterboxMode fromId(@Nullable String id) {
        for (PreviewLetterboxMode m : values()) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    public static PreviewLetterboxMode current(SharedPreferencesManager prefs) {
        PreviewLetterboxMode saved = fromId(prefs.sharedPreferences.getString(PREF_MODE, null));
        return saved != null ? saved : CAMERA_FILL;
    }

    public static void save(SharedPreferencesManager prefs, PreviewLetterboxMode mode) {
        prefs.sharedPreferences.edit().putString(PREF_MODE, mode.id).apply();
    }
}
