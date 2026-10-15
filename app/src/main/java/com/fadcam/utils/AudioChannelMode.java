package com.fadcam.utils;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * Channels recorded from the phone's mics, for videos and voice recordings.
 * Mono halves the PCM the mic path captures, copies and AAC-encodes.
 */
public enum AudioChannelMode {
    STEREO("stereo", 2, R.string.audio_channels_stereo, R.string.audio_channels_stereo_desc),
    MONO("mono", 1, R.string.audio_channels_mono, R.string.audio_channels_mono_desc);

    private static final String PREF_MODE = "pref_audio_channel_mode";

    public final String id;
    public final int channelCount;
    @StringRes public final int labelRes;
    @StringRes public final int descriptionRes;

    AudioChannelMode(String id, int channelCount, @StringRes int labelRes, @StringRes int descriptionRes) {
        this.id = id;
        this.channelCount = channelCount;
        this.labelRes = labelRes;
        this.descriptionRes = descriptionRes;
    }

    @Nullable
    public static AudioChannelMode fromId(@Nullable String id) {
        for (AudioChannelMode m : values()) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    public static AudioChannelMode current(SharedPreferencesManager prefs) {
        AudioChannelMode saved = fromId(prefs.sharedPreferences.getString(PREF_MODE, null));
        return saved != null ? saved : STEREO;
    }

    public static void save(SharedPreferencesManager prefs, AudioChannelMode mode) {
        prefs.sharedPreferences.edit().putString(PREF_MODE, mode.id).apply();
    }
}
