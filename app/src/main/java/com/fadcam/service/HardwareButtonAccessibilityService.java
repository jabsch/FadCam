package com.fadcam.service;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import com.fadcam.FLog;
import com.fadcam.shortcuts.HardwareButtonBindings;
import com.fadcam.shortcuts.ShortcutsManager;

/**
 * Runs FadCam shortcuts from hardware buttons, including with the screen off or
 * another app open. Holding a bound volume key runs its shortcut; a short press
 * still changes the volume. Unbound keys are never touched, and nothing is
 * intercepted during calls.
 */
public class HardwareButtonAccessibilityService extends AccessibilityService {
    private static final String TAG = "HwButtonService";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int heldKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private boolean holdFired;
    private Runnable pendingHold;

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        String shortcutId = HardwareButtonBindings.getHoldBinding(this, keyCode);
        if (shortcutId == null || isInCall()) {
            return false;
        }
        switch (event.getAction()) {
            case KeyEvent.ACTION_DOWN:
                if (event.getRepeatCount() == 0) {
                    beginHold(keyCode, shortcutId);
                }
                return true;
            case KeyEvent.ACTION_UP:
                if (keyCode == heldKeyCode) {
                    boolean fired = holdFired;
                    cancelHold();
                    if (!fired) {
                        adjustVolume(keyCode);
                    }
                }
                return true;
            default:
                return true;
        }
    }

    private void beginHold(int keyCode, String shortcutId) {
        cancelHold();
        heldKeyCode = keyCode;
        holdFired = false;
        pendingHold = () -> {
            holdFired = true;
            runShortcut(shortcutId);
        };
        handler.postDelayed(pendingHold, ViewConfiguration.getLongPressTimeout());
    }

    private void cancelHold() {
        if (pendingHold != null) {
            handler.removeCallbacks(pendingHold);
            pendingHold = null;
        }
        heldKeyCode = KeyEvent.KEYCODE_UNKNOWN;
        holdFired = false;
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
        return audio != null && audio.getMode() != AudioManager.MODE_NORMAL;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Key events only.
    }

    @Override
    public void onInterrupt() {
        cancelHold();
    }

    @Override
    public void onDestroy() {
        cancelHold();
        super.onDestroy();
    }
}
