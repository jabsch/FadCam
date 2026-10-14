package com.fadcam.services;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import com.fadcam.FLog;

/**
 * Watches the device thermal status while a recording runs and steps the
 * encoder load down before the OS throttles or disables the camera itself.
 *
 * <ul>
 *   <li>SEVERE: halve the encoder bitrate (less encoder and storage work).</li>
 *   <li>Back to LIGHT or cooler: restore the configured bitrate.</li>
 *   <li>CRITICAL or worse: stop gracefully so the file is finalized, instead of
 *   the camera HAL cutting the session off and leaving a broken file.</li>
 * </ul>
 * With frame-rate stepping on (the "1080p Auto fps" preset), MODERATE also drops
 * the camera to 30 fps, and LIGHT or cooler brings the full rate back.
 */
public final class RecordingThermalGuard {
    private static final String TAG = "RecordingThermalGuard";

    // Mirror PowerManager.THERMAL_STATUS_* so the decision logic is JVM-testable.
    static final int STATUS_LIGHT = 1;
    static final int STATUS_MODERATE = 2;
    static final int STATUS_SEVERE = 3;
    static final int STATUS_CRITICAL = 4;

    enum Action { NONE, REDUCE, RESTORE, STOP }

    enum FpsAction { NONE, LOWER, RAISE }

    public interface Callbacks {
        void onReduceLoad();
        void onRestoreLoad();
        void onStopForHeat();
        void onLowerFrameRate();
        void onRaiseFrameRate();
    }

    private final Callbacks callbacks;
    private PowerManager powerManager;
    // Typed as Object: OnThermalStatusChangedListener only exists on API 29+.
    private Object listener;
    private boolean reduced;
    private boolean stopped;
    private boolean stepFps;
    private boolean fpsLowered;

    public RecordingThermalGuard(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    /** Pure decision step; {@code reduced} is whether load is already shed. */
    static Action decide(int status, boolean reduced) {
        if (status >= STATUS_CRITICAL) {
            return Action.STOP;
        }
        if (status >= STATUS_SEVERE) {
            return reduced ? Action.NONE : Action.REDUCE;
        }
        // Hysteresis: hold the reduced bitrate through MODERATE so it does not
        // flap at the SEVERE boundary.
        if (reduced && status <= STATUS_LIGHT) {
            return Action.RESTORE;
        }
        return Action.NONE;
    }

    /**
     * Pure frame-rate step; {@code lowered} is whether the rate is already down.
     * Drops one level earlier than the bitrate cut (MODERATE), and like it only
     * comes back once the phone is at LIGHT or cooler.
     */
    static FpsAction decideFps(int status, boolean lowered) {
        if (!lowered && status >= STATUS_MODERATE && status < STATUS_CRITICAL) {
            return FpsAction.LOWER;
        }
        if (lowered && status <= STATUS_LIGHT) {
            return FpsAction.RAISE;
        }
        return FpsAction.NONE;
    }

    /**
     * Begins watching; the listener fires immediately with the current status.
     * {@code stepFrameRate} enables the 30 fps step for the Auto fps preset.
     */
    public synchronized void start(Context context, boolean stepFrameRate) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || listener != null) {
            return;
        }
        powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) {
            return;
        }
        reduced = false;
        stopped = false;
        stepFps = stepFrameRate;
        fpsLowered = false;
        PowerManager.OnThermalStatusChangedListener l = this::onStatus;
        listener = l;
        powerManager.addThermalStatusListener(context.getMainExecutor(), l);
    }

    public synchronized void stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null && listener != null) {
            try {
                powerManager.removeThermalStatusListener(
                        (PowerManager.OnThermalStatusChangedListener) listener);
            } catch (Exception e) {
                FLog.w(TAG, "Failed to remove thermal listener", e);
            }
        }
        listener = null;
        powerManager = null;
        reduced = false;
        fpsLowered = false;
    }

    private synchronized void onStatus(int status) {
        if (listener == null || stopped) {
            return;
        }
        Action action = decide(status, reduced);
        if (action != Action.NONE) {
            FLog.w(TAG, "Thermal status " + status + " -> " + action);
        }
        switch (action) {
            case REDUCE:
                reduced = true;
                callbacks.onReduceLoad();
                break;
            case RESTORE:
                reduced = false;
                callbacks.onRestoreLoad();
                break;
            case STOP:
                stopped = true;
                callbacks.onStopForHeat();
                break;
            default:
                break;
        }
        if (stopped || !stepFps) {
            return;
        }
        FpsAction fpsAction = decideFps(status, fpsLowered);
        if (fpsAction != FpsAction.NONE) {
            FLog.w(TAG, "Thermal status " + status + " -> fps " + fpsAction);
        }
        if (fpsAction == FpsAction.LOWER) {
            fpsLowered = true;
            callbacks.onLowerFrameRate();
        } else if (fpsAction == FpsAction.RAISE) {
            fpsLowered = false;
            callbacks.onRaiseFrameRate();
        }
    }

    /** Whether the Auto fps step currently has the camera at 30 fps. */
    public synchronized boolean isFrameRateLowered() {
        return fpsLowered;
    }
}
