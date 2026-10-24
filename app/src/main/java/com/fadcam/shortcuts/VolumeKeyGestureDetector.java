package com.fadcam.shortcuts;

/**
 * Turns volume key presses into hold, double-tap and triple-tap gestures. Pure
 * logic: the host supplies the bindings, runs shortcuts, replays volume changes
 * and owns the timers, so this can be tested off device.
 *
 * <ul>
 *   <li>Hold: fires after the long-press timeout while the key is still down.</li>
 *   <li>Double tap: a second press of the same key within the tap window. With
 *       no triple tap bound it fires on that second press. With a triple tap
 *       bound it fires once the window after the second tap passes.</li>
 *   <li>Triple tap: a third press of the same key, each within the tap window
 *       of the last, fires on that third press.</li>
 *   <li>A single short press still changes the volume. When a double or triple
 *       tap is bound to that key, the change waits for the tap window to pass.
 *       Taps that end up matching no bound gesture each step the volume.</li>
 *   <li>Holding a key with no hold bound keeps stepping the volume, as without
 *       JaCam. A consumed key press gets no auto-repeat from the system, so the
 *       repeat is done here.</li>
 *   <li>Keys with nothing bound are never consumed.</li>
 * </ul>
 */
public final class VolumeKeyGestureDetector {

    /** Gestures a volume key can be bound to. */
    public enum Gesture { HOLD, DOUBLE_TAP, TRIPLE_TAP }

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
    private final long keyRepeatDelayMs;

    private int downKey = NO_KEY;
    private boolean holdFired;
    /** A tap gesture already ran on this press, so its key up does nothing. */
    private boolean tapFired;
    /** This press is the second tap, waiting to see if a third follows. */
    private boolean secondTapPending;
    private boolean repeating;
    private Runnable pendingHold;

    private int pendingTapKey = NO_KEY;
    /** Finished taps of pendingTapKey still waiting for the window to pass. */
    private int pendingTapCount;
    private Runnable pendingTap;

    /**
     * @param holdTimeoutMs    how long a key is down before it counts as held
     * @param keyRepeatDelayMs interval between volume steps while an unbound hold lasts
     */
    public VolumeKeyGestureDetector(Host host, long holdTimeoutMs, long doubleTapTimeoutMs,
            long keyRepeatDelayMs) {
        this.host = host;
        this.holdTimeoutMs = holdTimeoutMs;
        this.doubleTapTimeoutMs = doubleTapTimeoutMs;
        this.keyRepeatDelayMs = keyRepeatDelayMs;
    }

    /** @return true when the event was consumed. */
    public boolean onKeyDown(int keyCode, int repeatCount) {
        String hold = host.binding(keyCode, Gesture.HOLD);
        String doubleTap = host.binding(keyCode, Gesture.DOUBLE_TAP);
        String tripleTap = host.binding(keyCode, Gesture.TRIPLE_TAP);
        if (pendingTapKey != NO_KEY && pendingTapKey != keyCode) {
            // Apply a waiting tap before this key's own step, keeping their order
            flushPendingTap();
        }
        if (hold == null && doubleTap == null && tripleTap == null) {
            return keyCode == downKey;
        }
        if (repeatCount > 0) {
            // The hold repeat below steps the volume; a system repeat would double it
            return true;
        }
        if (pendingTapKey == keyCode) {
            int taps = pendingTapCount + 1;
            if (taps == 3 && tripleTap != null) {
                clearPendingTap();
                cancelHold();
                downKey = keyCode;
                tapFired = true;
                host.runShortcut(tripleTap);
                return true;
            }
            if (taps == 2 && tripleTap != null) {
                // Wait for a possible third tap; key up starts the window again
                clearPendingTap();
                cancelHold();
                downKey = keyCode;
                secondTapPending = true;
                return true;
            }
            if (taps == 2 && doubleTap != null) {
                clearPendingTap();
                cancelHold();
                downKey = keyCode;
                tapFired = true;
                host.runShortcut(doubleTap);
                return true;
            }
            // Bindings changed while waiting: settle the old taps, start afresh
            flushPendingTap();
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
        } else {
            // Only taps are bound: holding steps the volume like a normal hold
            pendingHold = new Runnable() {
                @Override
                public void run() {
                    repeating = true;
                    host.adjustVolume(keyCode);
                    host.schedule(this, keyRepeatDelayMs);
                }
            };
            host.schedule(pendingHold, holdTimeoutMs);
        }
        return true;
    }

    /** @return true when the event was consumed. */
    public boolean onKeyUp(int keyCode) {
        if (keyCode != downKey) {
            return host.binding(keyCode, Gesture.HOLD) != null
                    || host.binding(keyCode, Gesture.DOUBLE_TAP) != null
                    || host.binding(keyCode, Gesture.TRIPLE_TAP) != null;
        }
        boolean consumedAsGesture = holdFired || tapFired || repeating;
        boolean second = secondTapPending;
        cancelHold();
        if (consumedAsGesture) {
            return true;
        }
        if (second) {
            waitForNextTap(keyCode, 2);
        } else if (host.binding(keyCode, Gesture.DOUBLE_TAP) != null
                || host.binding(keyCode, Gesture.TRIPLE_TAP) != null) {
            waitForNextTap(keyCode, 1);
        } else {
            host.adjustVolume(keyCode);
        }
        return true;
    }

    /** Ends a press in progress but still settles waiting taps. */
    public void finishPending() {
        cancelHold();
        flushPendingTap();
    }

    /** Drops any press in progress (service interrupted or destroyed). */
    public void reset() {
        cancelHold();
        clearPendingTap();
    }

    private void waitForNextTap(int keyCode, int taps) {
        pendingTapKey = keyCode;
        pendingTapCount = taps;
        pendingTap = this::flushPendingTap;
        host.schedule(pendingTap, doubleTapTimeoutMs);
    }

    /** No further tap came: run a double tap if two taps match one, else step the volume. */
    private void flushPendingTap() {
        int key = pendingTapKey;
        int taps = pendingTapCount;
        clearPendingTap();
        if (key == NO_KEY) {
            return;
        }
        String doubleTap = taps == 2 ? host.binding(key, Gesture.DOUBLE_TAP) : null;
        if (doubleTap != null) {
            host.runShortcut(doubleTap);
            return;
        }
        for (int i = 0; i < taps; i++) {
            host.adjustVolume(key);
        }
    }

    private void clearPendingTap() {
        if (pendingTap != null) {
            host.cancel(pendingTap);
            pendingTap = null;
        }
        pendingTapKey = NO_KEY;
        pendingTapCount = 0;
    }

    private void cancelHold() {
        if (pendingHold != null) {
            host.cancel(pendingHold);
            pendingHold = null;
        }
        downKey = NO_KEY;
        holdFired = false;
        tapFired = false;
        secondTapPending = false;
        repeating = false;
    }
}
