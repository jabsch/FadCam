package com.fadcam.utils;

import android.media.AudioManager;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * What music and other apps' audio do while a video records with sound. Android
 * doesn't need any of them to stop: JaCam asks for audio focus, and apps react.
 */
public enum OtherAudioMode {
    PAUSE("pause", AudioManager.AUDIOFOCUS_GAIN, R.string.other_audio_pause, R.string.other_audio_pause_desc),
    DUCK("duck", AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, R.string.other_audio_duck, R.string.other_audio_duck_desc),
    KEEP("keep", 0, R.string.other_audio_keep, R.string.other_audio_keep_desc);

    private static final String PREF_MODE = "pref_other_audio_mode";

    public final String id;
    /** Audio focus to request when recording starts; 0 requests none. */
    public final int focusGain;
    @StringRes public final int labelRes;
    @StringRes public final int descriptionRes;

    OtherAudioMode(String id, int focusGain, @StringRes int labelRes, @StringRes int descriptionRes) {
        this.id = id;
        this.focusGain = focusGain;
        this.labelRes = labelRes;
        this.descriptionRes = descriptionRes;
    }

    @Nullable
    public static OtherAudioMode fromId(@Nullable String id) {
        for (OtherAudioMode m : values()) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    public static OtherAudioMode current(SharedPreferencesManager prefs) {
        OtherAudioMode saved = fromId(prefs.sharedPreferences.getString(PREF_MODE, null));
        return saved != null ? saved : PAUSE;
    }

    public static void save(SharedPreferencesManager prefs, OtherAudioMode mode) {
        prefs.sharedPreferences.edit().putString(PREF_MODE, mode.id).apply();
    }
}
