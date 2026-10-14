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
 * Which JaCam shortcut each hardware button gesture runs: hold or double tap
 * of volume up or volume down. A single short press keeps changing the volume.
 * Values are {@link ShortcutsManager} ids, empty = unbound.
 */
public final class HardwareButtonBindings {
    private static final String PREFS = "hardware_button_bindings";

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
        return key + (gesture == VolumeKeyGestureDetector.Gesture.HOLD ? "_hold" : "_double");
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
