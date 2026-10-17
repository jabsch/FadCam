package com.fadcam.utils;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;

import com.fadcam.Constants;

/**
 * Switches the launcher entry to the activity alias for an app icon key. Settings > Appearance
 * uses it when an icon is picked, and settings Reset/Import when they change the saved icon,
 * which used to leave the old disguise on the home screen.
 */
public final class LauncherIconAliases {

    private LauncherIconAliases() {}

    private static final String DEFAULT_COMPONENT = "com.fadcam.SplashActivity";

    /** Icon key to launcher component; anything else falls back to the default icon. */
    private static final String[][] ALIASES = {
            {Constants.APP_ICON_DEFAULT, DEFAULT_COMPONENT},
            {Constants.APP_ICON_ALTERNATIVE, "com.fadcam.MainActivity.AlternativeIcon"},
            {Constants.APP_ICON_FADED, "com.fadcam.MainActivity.FadedIcon"},
            {Constants.APP_ICON_PALESTINE, "com.fadcam.MainActivity.PalestineIcon"},
            {Constants.APP_ICON_PAKISTAN, "com.fadcam.MainActivity.PakistanIcon"},
            {Constants.APP_ICON_FADSECLAB, "com.fadcam.MainActivity.FadSecLabIcon"},
            {Constants.APP_ICON_NOORISTIC, "com.fadcam.MainActivity.NooristicIcon"},
            {Constants.APP_ICON_BAT, "com.fadcam.MainActivity.BatIcon"},
            {Constants.APP_ICON_REDBINARY, "com.fadcam.MainActivity.RedBinaryIcon"},
            {Constants.APP_ICON_NOTES, "com.fadcam.MainActivity.NotesIcon"},
            {Constants.APP_ICON_CALCULATOR, "com.fadcam.MainActivity.CalculatorIcon"},
            {Constants.APP_ICON_CLOCK, "com.fadcam.MainActivity.ClockIcon"},
            {Constants.APP_ICON_WEATHER, "com.fadcam.MainActivity.WeatherIcon"},
            {Constants.APP_ICON_FOOTBALL, "com.fadcam.MainActivity.FootballIcon"},
            {Constants.APP_ICON_CAR, "com.fadcam.MainActivity.CarIcon"},
            {Constants.APP_ICON_JET, "com.fadcam.MainActivity.JetIcon"},
            {Constants.APP_ICON_MINIMAL, "com.fadcam.MainActivity.MinimalIcon"},
            {Constants.APP_ICON_BLACK, "com.fadcam.MainActivity.BlackIcon"},
    };

    public static void apply(@NonNull Context context, @NonNull String iconKey) {
        PackageManager pm = context.getPackageManager();
        String target = DEFAULT_COMPONENT;
        for (String[] alias : ALIASES) {
            if (alias[0].equals(iconKey)) {
                target = alias[1];
                break;
            }
        }
        // Enable the target first, then disable the rest, so there is never a moment
        // with no launcher entry and the live one is never toggled off
        pm.setComponentEnabledSetting(new ComponentName(context, target),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        for (String[] alias : ALIASES) {
            if (alias[1].equals(target)) continue;
            pm.setComponentEnabledSetting(new ComponentName(context, alias[1]),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
    }
}
