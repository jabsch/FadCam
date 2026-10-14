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
 * Which FadCam shortcut each hardware button gesture runs. Gestures are
 * "hold volume up" and "hold volume down"; a short press keeps changing the
 * volume as usual. Values are {@link ShortcutsManager} ids, empty = unbound.
 */
public final class HardwareButtonBindings {
    private static final String PREFS = "hardware_button_bindings";
    private static final String KEY_VOLUME_UP_HOLD = "volume_up_hold";
    private static final String KEY_VOLUME_DOWN_HOLD = "volume_down_hold";

    private HardwareButtonBindings() {}

    /** Preference key for a key code's hold gesture, or null if not bindable. */
    @Nullable
    static String keyFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP: return KEY_VOLUME_UP_HOLD;
            case KeyEvent.KEYCODE_VOLUME_DOWN: return KEY_VOLUME_DOWN_HOLD;
            default: return null;
        }
    }

    /** Shortcut id bound to holding this key, or null when nothing is bound. */
    @Nullable
    public static String getHoldBinding(@NonNull Context context, int keyCode) {
        String key = keyFor(keyCode);
        if (key == null) return null;
        String id = prefs(context).getString(key, "");
        return TextUtils.isEmpty(id) ? null : id;
    }

    /** Binds a shortcut id to holding this key; null or empty clears it. */
    public static void setHoldBinding(@NonNull Context context, int keyCode, @Nullable String shortcutId) {
        String key = keyFor(keyCode);
        if (key == null) return;
        prefs(context).edit().putString(key, shortcutId == null ? "" : shortcutId).apply();
    }

    /** Whether the user has turned on FadCam's button service in Accessibility settings. */
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
