package com.fadcam.shortcuts;

/**
 * Turns volume key presses into hold and double-tap gestures. Pure logic: the
 * host supplies the bindings, runs shortcuts, replays volume changes and owns
 * the timers, so this can be tested off device.
 *
 * <ul>
 *   <li>Hold: fires after the long-press timeout while the key is still down.</li>
 *   <li>Double tap: a second press of the same key within the double-tap window
 *       fires on that second press.</li>
 *   <li>A single short press still changes the volume. When a double tap is bound
 *       to that key, the change waits for the double-tap window to pass.</li>
 *   <li>Keys with nothing bound are never consumed.</li>
 * </ul>
 */
public final class VolumeKeyGestureDetector {

    /** Gestures a volume key can be bound to. */
    public enum Gesture { HOLD, DOUBLE_TAP }

    public interface Host {
        /** Shortcut id bound to this key and gesture, or null. */
        String binding(int keyCode, Gesture gesture);

        void runShortcut(String shortcutId);

        /** Replays one normal volume step for this key. */
        void adjustVolume(int keyCode);

        void schedule(Runnable task, long delayMs);

        void cancel(Runnable task);
    }

    private static final int NO_KEY = 0; // KeyEvent.KEYCODE_UNKNOWN

    private final Host host;
    private final long holdTimeoutMs;
    private final long doubleTapTimeoutMs;

    private int downKey = NO_KEY;
    private boolean holdFired;
    private boolean secondTap;
    private boolean repeating;
    private Runnable pendingHold;

    private int pendingTapKey = NO_KEY;
    private Runnable pendingTap;

    public VolumeKeyGestureDetector(Host host, long holdTimeoutMs, long doubleTapTimeoutMs) {
        this.host = host;
        this.holdTimeoutMs = holdTimeoutMs;
        this.doubleTapTimeoutMs = doubleTapTimeoutMs;
    }

    /** @return true when the event was consumed. */
    public boolean onKeyDown(int keyCode, int repeatCount) {
        String hold = host.binding(keyCode, Gesture.HOLD);
        String doubleTap = host.binding(keyCode, Gesture.DOUBLE_TAP);
        if (hold == null && doubleTap == null) {
            return keyCode == downKey;
        }
        if (repeatCount > 0) {
            // Key auto-repeat. With no hold bound, holding still steps the volume.
            if (keyCode == downKey && hold == null && !secondTap) {
                repeating = true;
                host.adjustVolume(keyCode);
            }
            return true;
        }
        if (pendingTapKey != NO_KEY && pendingTapKey != keyCode) {
            flushPendingTap();
        }
        if (pendingTapKey == keyCode && doubleTap != null) {
            clearPendingTap();
            cancelHold();
            downKey = keyCode;
            secondTap = true;
            host.runShortcut(doubleTap);
            return true;
        }
        cancelHold();
        downKey = keyCode;
        if (hold != null) {
            final String holdId = hold;
            pendingHold = () -> {
                pendingHold = null;
                holdFired = true;
                host.runShortcut(holdId);
            };
            host.schedule(pendingHold, holdTimeoutMs);
        }
        return true;
    }

    /** @return true when the event was consumed. */
    public boolean onKeyUp(int keyCode) {
        if (keyCode != downKey) {
            return host.binding(keyCode, Gesture.HOLD) != null
                    || host.binding(keyCode, Gesture.DOUBLE_TAP) != null;
        }
        boolean consumedAsGesture = holdFired || secondTap || repeating;
        cancelHold();
        if (consumedAsGesture) {
            return true;
        }
        if (host.binding(keyCode, Gesture.DOUBLE_TAP) != null) {
            pendingTapKey = keyCode;
            pendingTap = this::flushPendingTap;
            host.schedule(pendingTap, doubleTapTimeoutMs);
        } else {
            host.adjustVolume(keyCode);
        }
        return true;
    }

    /** Drops any press in progress (service interrupted or destroyed). */
    public void reset() {
        cancelHold();
        clearPendingTap();
    }

    private void flushPendingTap() {
        int key = pendingTapKey;
        clearPendingTap();
        if (key != NO_KEY) {
            host.adjustVolume(key);
        }
    }

    private void clearPendingTap() {
        if (pendingTap != null) {
            host.cancel(pendingTap);
            pendingTap = null;
        }
        pendingTapKey = NO_KEY;
    }

    private void cancelHold() {
        if (pendingHold != null) {
            host.cancel(pendingHold);
            pendingHold = null;
        }
        downKey = NO_KEY;
        holdFired = false;
        secondTap = false;
        repeating = false;
    }
}
