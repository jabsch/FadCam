package com.fadcam.service;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import com.fadcam.FLog;
import com.fadcam.shortcuts.HardwareButtonBindings;
import com.fadcam.shortcuts.ShortcutsManager;
import com.fadcam.shortcuts.VolumeKeyGestureDetector;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs JaCam shortcuts from hardware buttons, including with the screen off or
 * another app open. Holding or double-tapping a bound volume key runs its
 * shortcut; a single short press still changes the volume, and holding a key
 * whose hold is unbound keeps changing it. Unbound keys are never touched, and
 * nothing is intercepted during calls. While a camera app is on screen its
 * volume-key shutter gets the keys instead, unless the user turned that off.
 */
public class HardwareButtonAccessibilityService extends AccessibilityService {
    private static final String TAG = "HwButtonService";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private VolumeKeyGestureDetector detector;
    /** App whose window last came to the front, or null before the first one. */
    private String foregroundPackage;
    private final Map<String, Boolean> cameraPackages = new HashMap<>();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        detector = new VolumeKeyGestureDetector(new VolumeKeyGestureDetector.Host() {
            @Override
            public String binding(int keyCode, VolumeKeyGestureDetector.Gesture gesture) {
                return HardwareButtonBindings.getBinding(HardwareButtonAccessibilityService.this,
                        keyCode, gesture);
            }

            @Override
            public void runShortcut(String shortcutId) {
                HardwareButtonAccessibilityService.this.runShortcut(shortcutId);
            }

            @Override
            public void adjustVolume(int keyCode) {
                HardwareButtonAccessibilityService.this.adjustVolume(keyCode);
            }

            @Override
            public void schedule(Runnable task, long delayMs) {
                handler.postDelayed(task, delayMs);
            }

            @Override
            public void cancel(Runnable task) {
                handler.removeCallbacks(task);
            }
        }, ViewConfiguration.getLongPressTimeout(), ViewConfiguration.getDoubleTapTimeout(),
                ViewConfiguration.getKeyRepeatDelay());
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (detector == null) {
            return false;
        }
        if (isInCall() || cameraAppHasVolumeKeys()) {
            detector.finishPending();
            return false;
        }
        switch (event.getAction()) {
            case KeyEvent.ACTION_DOWN:
                return detector.onKeyDown(event.getKeyCode(), event.getRepeatCount());
            case KeyEvent.ACTION_UP:
                return detector.onKeyUp(event.getKeyCode());
            default:
                return false;
        }
    }

    private void runShortcut(String shortcutId) {
        Intent intent = ShortcutsManager.intentFor(this, shortcutId);
        if (intent == null) {
            FLog.w(TAG, "Unknown shortcut bound to button: " + shortcutId);
            return;
        }
        FLog.i(TAG, "Hardware button running shortcut " + shortcutId);
        try {
            // A bound accessibility service may start activities from the
            // background; the trampoline then makes the camera start legal.
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            FLog.e(TAG, "Failed to run shortcut " + shortcutId, e);
        }
    }

    /** Replays the short press we consumed as a normal volume change. */
    private void adjustVolume(int keyCode) {
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return;
        int direction = keyCode == KeyEvent.KEYCODE_VOLUME_UP
                ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
        audio.adjustSuggestedStreamVolume(direction,
                AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
    }

    private boolean isInCall() {
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) {
            return false;
        }
        int mode = audio.getMode();
        // Communication mode that JaCam set for a Bluetooth mic recording is not a call
        if (mode == AudioManager.MODE_IN_COMMUNICATION
                && com.fadcam.utils.OwnCommunicationMode.isHeld()) {
            return false;
        }
        return mode != AudioManager.MODE_NORMAL;
    }

    /**
     * A camera app on screen uses volume keys as its shutter. An accessibility
     * service can't hand a consumed press back, so they must not be consumed.
     */
    private boolean cameraAppHasVolumeKeys() {
        if (foregroundPackage == null || !isCameraPackage(foregroundPackage)) {
            return false;
        }
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (power != null && !power.isInteractive()) {
            // Screen off: nothing is on screen to take a photo
            return false;
        }
        return HardwareButtonBindings.cameraAppsKeepVolumeKeys(this);
    }

    private boolean isOwnActivity(CharSequence className) {
        if (className == null) return false;
        try {
            getPackageManager().getActivityInfo(
                    new ComponentName(getPackageName(), className.toString()), 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean isCameraPackage(String pkg) {
        Boolean cached = cameraPackages.get(pkg);
        if (cached == null) {
            cached = handles(pkg, MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    || handles(pkg, MediaStore.ACTION_IMAGE_CAPTURE)
                    || handles(pkg, MediaStore.INTENT_ACTION_VIDEO_CAMERA);
            cameraPackages.put(pkg, cached);
        }
        return cached;
    }

    private boolean handles(String pkg, String action) {
        try {
            return !getPackageManager()
                    .queryIntentActivities(new Intent(action).setPackage(pkg), 0).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getPackageName() == null) {
            return;
        }
        String pkg = event.getPackageName().toString();
        // System UI (shade, volume panel, lock screen) and JaCam's floating
        // overlays sit on top of the app in front; they don't change which app that is.
        if (pkg.equals("com.android.systemui")
                || (pkg.equals(getPackageName()) && !isOwnActivity(event.getClassName()))) {
            return;
        }
        foregroundPackage = pkg;
    }

    @Override
    public void onInterrupt() {
        if (detector != null) detector.reset();
    }

    @Override
    public void onDestroy() {
        if (detector != null) detector.reset();
        super.onDestroy();
    }
}
