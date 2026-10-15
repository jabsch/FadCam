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
 * MODERATE also counts as "warm" until the phone is back to LIGHT or cooler.
 * While warm, the "1080p Auto fps" preset drops to 30 fps and the "Auto"
 * stabilization mode turns electronic stabilization off.
 * The "Early" heat response ({@link com.fadcam.utils.HeatResponse}) moves the
 * warm step and the bitrate cut one level earlier; the stop stays at CRITICAL.
 */
public final class RecordingThermalGuard {
    private static final String TAG = "RecordingThermalGuard";

    // Mirror PowerManager.THERMAL_STATUS_* so the decision logic is JVM-testable.
    static final int STATUS_LIGHT = 1;
    static final int STATUS_MODERATE = 2;
    static final int STATUS_SEVERE = 3;
    static final int STATUS_CRITICAL = 4;

    enum Action { NONE, REDUCE, RESTORE, STOP }

    enum WarmAction { NONE, WARM, COOLED }

    public interface Callbacks {
        void onReduceLoad();
        void onRestoreLoad();
        void onStopForHeat();
        /** The phone reached MODERATE; {@code lowerFrameRate} for the Auto fps step. */
        void onWarm(boolean lowerFrameRate);
        /** The phone is back to LIGHT or cooler; {@code raiseFrameRate} undoes the Auto fps step. */
        void onCooled(boolean raiseFrameRate);
    }

    private final Callbacks callbacks;
    private PowerManager powerManager;
    // Typed as Object: OnThermalStatusChangedListener only exists on API 29+.
    private Object listener;
    private boolean reduced;
    private boolean stopped;
    private boolean stepFps;
    private boolean warm;
    /** Heat Response setting, read at start. */
    private boolean early;

    public RecordingThermalGuard(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    /** Pure decision step; {@code reduced} is whether load is already shed. */
    static Action decide(int status, boolean reduced) {
        return decide(status, reduced, false);
    }

    /** As {@link #decide(int, boolean)}; {@code early} cuts at MODERATE and restores at NONE. */
    static Action decide(int status, boolean reduced, boolean early) {
        int step = early ? 1 : 0;
        if (status >= STATUS_CRITICAL) {
            return Action.STOP;
        }
        if (status >= STATUS_SEVERE - step) {
            return reduced ? Action.NONE : Action.REDUCE;
        }
        // Hysteresis: hold the reduced bitrate one level below the cut so it
        // does not flap at the boundary.
        if (reduced && status <= STATUS_LIGHT - step) {
            return Action.RESTORE;
        }
        return Action.NONE;
    }

    /**
     * Pure warm step; {@code warm} is whether optional work is already shed.
     * Steps one level earlier than the bitrate cut (MODERATE), and like it only
     * comes back once the phone is at LIGHT or cooler.
     */
    static WarmAction decideWarmStep(int status, boolean warm) {
        return decideWarmStep(status, warm, false);
    }

    /** As {@link #decideWarmStep(int, boolean)}; {@code early} steps at LIGHT and cools at NONE. */
    static WarmAction decideWarmStep(int status, boolean warm, boolean early) {
        int step = early ? 1 : 0;
        if (!warm && status >= STATUS_MODERATE - step && status < STATUS_CRITICAL) {
            return WarmAction.WARM;
        }
        if (warm && status <= STATUS_LIGHT - step) {
            return WarmAction.COOLED;
        }
        return WarmAction.NONE;
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
        warm = false;
        early = com.fadcam.utils.HeatResponse.current(
                com.fadcam.SharedPreferencesManager.getInstance(context)) == com.fadcam.utils.HeatResponse.EARLY;
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
        warm = false;
    }

    private synchronized void onStatus(int status) {
        if (listener == null || stopped) {
            return;
        }
        Action action = decide(status, reduced, early);
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
        if (stopped) {
            return;
        }
        WarmAction warmAction = decideWarmStep(status, warm, early);
        if (warmAction != WarmAction.NONE) {
            FLog.w(TAG, "Thermal status " + status + " -> " + warmAction);
        }
        if (warmAction == WarmAction.WARM) {
            warm = true;
            callbacks.onWarm(stepFps);
        } else if (warmAction == WarmAction.COOLED) {
            warm = false;
            callbacks.onCooled(stepFps);
        }
    }

    /** Whether the Auto fps step currently has the camera at 30 fps. */
    public synchronized boolean isFrameRateLowered() {
        return warm && stepFps;
    }

    /** Whether the phone is at MODERATE heat or above (until it cools to LIGHT). */
    public synchronized boolean isWarm() {
        return warm;
    }
}
