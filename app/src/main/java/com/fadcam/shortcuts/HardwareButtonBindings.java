package com.fadcam.shortcuts;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.fadcam.service.HardwareButtonAccessibilityService;

/**
 * Which JaCam shortcut each hardware button gesture runs: hold, double tap or
 * triple tap of volume up or volume down. A single short press keeps changing the volume.
 * Values are {@link ShortcutsManager} ids, empty = unbound.
 */
public final class HardwareButtonBindings {
    private static final String PREFS = "hardware_button_bindings";
    private static final String KEY_CAMERA_APPS_KEEP_KEYS = "camera_apps_keep_volume_keys";

    private HardwareButtonBindings() {}

    /** Preference key for a key code's gesture, or null if not bindable. */
    @Nullable
    static String keyFor(int keyCode, @NonNull VolumeKeyGestureDetector.Gesture gesture) {
        String key;
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP: key = "volume_up"; break;
            case KeyEvent.KEYCODE_VOLUME_DOWN: key = "volume_down"; break;
            default: return null;
        }
        switch (gesture) {
            case HOLD: return key + "_hold";
            case TRIPLE_TAP: return key + "_triple";
            default: return key + "_double";
        }
    }

    /** Shortcut id bound to this key and gesture, or null when nothing is bound. */
    @Nullable
    public static String getBinding(@NonNull Context context, int keyCode,
            @NonNull VolumeKeyGestureDetector.Gesture gesture) {
        String key = keyFor(keyCode, gesture);
        if (key == null) return null;
        String id = prefs(context).getString(key, "");
        return TextUtils.isEmpty(id) ? null : id;
    }

    /** Binds a shortcut id to this key and gesture; null or empty clears it. */
    public static void setBinding(@NonNull Context context, int keyCode,
            @NonNull VolumeKeyGestureDetector.Gesture gesture, @Nullable String shortcutId) {
        String key = keyFor(keyCode, gesture);
        if (key == null) return;
        prefs(context).edit().putString(key, shortcutId == null ? "" : shortcutId).apply();
    }

    /**
     * Whether volume keys go straight to a camera app while it is on screen, so
     * its volume-key shutter keeps working. On by default.
     */
    public static boolean cameraAppsKeepVolumeKeys(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_CAMERA_APPS_KEEP_KEYS, true);
    }

    public static void setCameraAppsKeepVolumeKeys(@NonNull Context context, boolean keep) {
        prefs(context).edit().putBoolean(KEY_CAMERA_APPS_KEEP_KEYS, keep).apply();
    }

    /** Whether the user has turned on JaCam's button service in Accessibility settings. */
    public static boolean isServiceEnabled(@NonNull Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName self = new ComponentName(context, HardwareButtonAccessibilityService.class);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        for (String entry : splitter) {
            ComponentName cn = ComponentName.unflattenFromString(entry);
            if (self.equals(cn)) return true;
        }
        return false;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
