package com.fadcam.utils;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * Video codec of Faditor exports. JaCam records HEVC by default, so forcing H.264
 * re-encodes every frame of every export, a plain trim included, and blocks the
 * trim shortcut that copies the untouched part of the video.
 */
public enum EditorExportFormat {
    COMPATIBLE("compatible", R.string.editor_export_compatible, R.string.editor_export_compatible_desc),
    ORIGINAL("original", R.string.editor_export_original, R.string.editor_export_original_desc);

    private static final String PREF_MODE = "pref_editor_export_format";

    public final String id;
    @StringRes public final int labelRes;
    @StringRes public final int descriptionRes;

    EditorExportFormat(String id, @StringRes int labelRes, @StringRes int descriptionRes) {
        this.id = id;
        this.labelRes = labelRes;
        this.descriptionRes = descriptionRes;
    }

    @Nullable
    public static EditorExportFormat fromId(@Nullable String id) {
        for (EditorExportFormat m : values()) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    public static EditorExportFormat current(SharedPreferencesManager prefs) {
        EditorExportFormat saved = fromId(prefs.sharedPreferences.getString(PREF_MODE, null));
        return saved != null ? saved : COMPATIBLE;
    }

    public static void save(SharedPreferencesManager prefs, EditorExportFormat mode) {
        prefs.sharedPreferences.edit().putString(PREF_MODE, mode.id).apply();
    }
}
