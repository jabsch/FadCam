package com.fadcam;

import android.content.Context;
import android.content.Intent;
import androidx.core.content.ContextCompat;
import com.fadcam.dualcam.service.DualCameraRecordingService;
import com.fadcam.services.RecordingService;
import com.fadcam.utils.ServiceUtils;

/**
 * Shared start/stop dispatch for the no-UI recording entry points (launcher
 * shortcuts, home-screen pins, the Quick Settings tile). Sends the service
 * command directly so a toggle costs one invisible activity, not a chain of two.
 */
public final class RecordingShortcutDispatcher {
    private static final String TAG = "RecordingShortcutDispatcher";

    private RecordingShortcutDispatcher() {}

    /**
     * True when a recording (or paused recording) is live. The persisted flags
     * survive a process kill, so they only count while a recording service is
     * actually running in the foreground; otherwise they are stale and get cleared here, which
     * stops a dead session from making every toggle press route to "stop".
     */
    public static boolean hasActiveSession(Context context) {
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(context);
        boolean flaggedRecording = prefs.isRecordingInProgress();
        boolean hasTimeline = prefs.sharedPreferences
                .getLong(Constants.PREF_RECORDING_START_TIME, 0L) > 0L;
        if (!flaggedRecording && !hasTimeline) {
            return false;
        }
        // A sticky restart after a process kill brings the service back idle and in
        // the background, so only a foreground service counts as a live session.
        boolean serviceRecording = ServiceUtils.isServiceRunningInForeground(context, RecordingService.class)
                || ServiceUtils.isServiceRunningInForeground(context, DualCameraRecordingService.class);
        if (!serviceRecording) {
            FLog.w(TAG, "Clearing stale recording flags: no recording service is in the foreground");
            prefs.setRecordingInProgress(false);
            prefs.sharedPreferences.edit()
                    .remove(Constants.PREF_RECORDING_START_TIME)
                    .remove(Constants.PREF_RECORDING_PAUSE_STARTED_AT)
                    .remove(Constants.PREF_RECORDING_ACCUMULATED_PAUSED_DURATION)
                    .apply();
            return false;
        }
        return true;
    }

    /**
     * Starts recording with the given {@link RecordingStartActivity} camera mode;
     * a non-null zoom ratio starts the back camera at that zoom instead (see
     * {@link #applyStartZoom}).
     */
    public static void start(Context context, String mode, Float zoomRatio) {
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(context);
        if (zoomRatio != null) {
            mode = RecordingStartActivity.CAMERA_MODE_BACK;
            applyStartZoom(prefs, zoomRatio);
        }
        if (mode == null) {
            mode = RecordingStartActivity.CAMERA_MODE_BACK;
        }
        String forcedCamera = null;
        if (RecordingStartActivity.CAMERA_MODE_FRONT.equals(mode)) {
            forcedCamera = CameraType.FRONT.name();
        } else if (RecordingStartActivity.CAMERA_MODE_BACK.equals(mode)) {
            forcedCamera = CameraType.BACK.name();
        } else if (RecordingStartActivity.CAMERA_MODE_DUAL.equals(mode)) {
            forcedCamera = CameraType.DUAL_PIP.name();
        }
        if (forcedCamera != null) {
            prefs.sharedPreferences.edit()
                    .putString(Constants.PREF_CAMERA_SELECTION, forcedCamera)
                    .apply();
        }

        CameraType selectedCamera = prefs.getCameraSelection();
        boolean startDual = RecordingStartActivity.CAMERA_MODE_DUAL.equals(mode)
                || (RecordingStartActivity.CAMERA_MODE_CURRENT.equals(mode)
                && selectedCamera != null
                && selectedCamera.isDual());

        Intent startIntent = startDual
                ? new Intent(context, DualCameraRecordingService.class)
                        .setAction(Constants.INTENT_ACTION_START_DUAL_RECORDING)
                : new Intent(context, RecordingService.class)
                        .setAction(Constants.INTENT_ACTION_START_RECORDING);
        startIntent.putExtra(Constants.INTENT_EXTRA_HEADLESS_START, true);
        ContextCompat.startForegroundService(context, startIntent);
    }

    /**
     * Saves the zoom the recording starts at. Ratios count from the main lens
     * (0.5x ultrawide, 5x telephoto), so the main back camera is selected: on
     * phones like the Pixel it is the logical camera, which switches to the
     * ultrawide or telephoto lens for that zoom by itself. A physical lens picked
     * in settings would read the ratio against its own field of view instead.
     * The recording clamps the ratio to what the camera supports.
     */
    private static void applyStartZoom(SharedPreferencesManager prefs, float zoomRatio) {
        prefs.setSelectedBackCameraId(Constants.DEFAULT_BACK_CAMERA_ID);
        prefs.setSpecificZoomRatio(CameraType.BACK, zoomRatio);
        prefs.setSpecificPan(CameraType.BACK, 0f, 0f);
    }

    /** Stops whichever recording service owns the live session. */
    public static void stop(Context context) {
        // Sent from the visible trampoline activity, so the service is already
        // foreground-eligible; startService avoids FGS-start timeout edge cases.
        context.startService(stopIntent(context));
    }

    /** The stop intent for whichever recording service owns the live session. */
    public static Intent stopIntent(Context context) {
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(context);
        CameraType selected = prefs.getCameraSelection();
        boolean dualRunning = ServiceUtils.isServiceRunning(context, DualCameraRecordingService.class)
                || (selected != null && selected.isDual() && prefs.isRecordingInProgress());
        Intent stopIntent = dualRunning
                ? new Intent(context, DualCameraRecordingService.class)
                        .setAction(Constants.INTENT_ACTION_STOP_DUAL_RECORDING)
                : new Intent(context, RecordingService.class)
                        .setAction(Constants.INTENT_ACTION_STOP_RECORDING);
        return stopIntent;
    }
}
