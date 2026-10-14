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
 */
public final class RecordingThermalGuard {
    private static final String TAG = "RecordingThermalGuard";

    // Mirror PowerManager.THERMAL_STATUS_* so the decision logic is JVM-testable.
    static final int STATUS_LIGHT = 1;
    static final int STATUS_MODERATE = 2;
    static final int STATUS_SEVERE = 3;
    static final int STATUS_CRITICAL = 4;

    enum Action { NONE, REDUCE, RESTORE, STOP }

    public interface Callbacks {
        void onReduceLoad();
        void onRestoreLoad();
        void onStopForHeat();
    }

    private final Callbacks callbacks;
    private PowerManager powerManager;
    // Typed as Object: OnThermalStatusChangedListener only exists on API 29+.
    private Object listener;
    private boolean reduced;
    private boolean stopped;

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

    /** Begins watching; the listener fires immediately with the current status. */
    public synchronized void start(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || listener != null) {
            return;
        }
        powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) {
            return;
        }
        reduced = false;
        stopped = false;
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
    }
}
