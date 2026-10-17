package com.fadcam.utils;

import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;

import com.fadcam.Constants;
import com.fadcam.R;

/** Maps the theme name saved in Appearance to its style, for activities outside MainActivity. */
public final class AppThemeStyle {
    private AppThemeStyle() {
    }

    @StyleRes
    public static int forName(@Nullable String themeName) {
        if (themeName == null) themeName = Constants.DEFAULT_APP_THEME;
        switch (themeName) {
            case "Faded Night":   return R.style.Theme_FadCam_Amoled;
            case "Midnight Dusk": return R.style.Theme_FadCam_MidnightDusk;
            case "Premium Gold":  return R.style.Theme_FadCam_Gold;
            case "Silent Forest": return R.style.Theme_FadCam_SilentForest;
            case "Shadow Alloy":  return R.style.Theme_FadCam_ShadowAlloy;
            case "Pookie Pink":   return R.style.Theme_FadCam_PookiePink;
            case "Snow Veil":     return R.style.Theme_FadCam_SnowVeil;
            case "Crimson Bloom":
            default:              return R.style.Theme_FadCam_Red;
        }
    }
}
