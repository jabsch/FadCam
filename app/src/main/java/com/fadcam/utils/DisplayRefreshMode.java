package com.fadcam.utils;

import android.app.Activity;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * Screen refresh rate while JaCam is on screen. SYSTEM leaves it to the phone
 * (up to 120 Hz on a Pixel with Smooth Display on); CAP_60 asks for the 60 Hz
 * display mode in JaCam's windows, which costs less display power, mostly
 * while the camera preview keeps the screen busy.
 */
public enum DisplayRefreshMode {
    SYSTEM("system", R.string.display_refresh_system, R.string.display_refresh_system_desc),
    CAP_60("cap_60", R.string.display_refresh_60, R.string.display_refresh_60_desc);

    private static final String PREF_MODE = "pref_display_refresh_mode";
    private static final float CAP_HZ = 60f;

    public final String id;
    @StringRes public final int labelRes;
    @StringRes public final int descriptionRes;

    DisplayRefreshMode(String id, @StringRes int labelRes, @StringRes int descriptionRes) {
        this.id = id;
        this.labelRes = labelRes;
        this.descriptionRes = descriptionRes;
    }

    @Nullable
    public static DisplayRefreshMode fromId(@Nullable String id) {
        for (DisplayRefreshMode m : values()) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    public static DisplayRefreshMode current(SharedPreferencesManager prefs) {
        DisplayRefreshMode saved = fromId(prefs.sharedPreferences.getString(PREF_MODE, null));
        return saved != null ? saved : SYSTEM;
    }

    public static void save(SharedPreferencesManager prefs, DisplayRefreshMode mode) {
        prefs.sharedPreferences.edit().putString(PREF_MODE, mode.id).apply();
    }

    /** Applies the saved mode to the activity's window. Cheap when nothing changes. */
    @SuppressWarnings("deprecation") // getDefaultDisplay: Activity.getDisplay needs API 30
    public static void apply(Activity activity) {
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        boolean cap = current(SharedPreferencesManager.getInstance(activity)) == CAP_60;
        int modeId = 0;
        float rate = 0f;
        if (cap) {
            Display.Mode mode = pickCapMode(activity.getWindowManager().getDefaultDisplay());
            if (mode != null) {
                modeId = mode.getModeId();
                rate = mode.getRefreshRate();
            }
        }
        WindowManager.LayoutParams lp = window.getAttributes();
        if (lp.preferredDisplayModeId == modeId && lp.preferredRefreshRate == rate) {
            return;
        }
        lp.preferredDisplayModeId = modeId;
        lp.preferredRefreshRate = rate;
        window.setAttributes(lp);
    }

    /** The current resolution's mode closest to 60 Hz, or null if there is none at or below it. */
    @Nullable
    private static Display.Mode pickCapMode(@Nullable Display display) {
        if (display == null) {
            return null;
        }
        Display.Mode current = display.getMode();
        Display.Mode best = null;
        for (Display.Mode m : display.getSupportedModes()) {
            if (m.getPhysicalWidth() != current.getPhysicalWidth()
                    || m.getPhysicalHeight() != current.getPhysicalHeight()
                    || m.getRefreshRate() > CAP_HZ + 1f) {
                continue;
            }
            if (best == null || m.getRefreshRate() > best.getRefreshRate()) {
                best = m;
            }
        }
        return best;
    }
}
