package com.fadcam.dualcam.service;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.Manifest;
import android.app.PendingIntent;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;

import com.fadcam.Constants;
import com.fadcam.MaximumRecordingDuration;
import com.fadcam.R;
import com.fadcam.RecordingDurationLimitController;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.dualcam.DualCameraCapability;
import com.fadcam.dualcam.DualCameraConfig;
import com.fadcam.dualcam.DualCameraState;
import com.fadcam.opengl.GLRecordingPipeline;
import com.fadcam.opengl.WatermarkInfoProvider;
import com.fadcam.utils.PhotoStorageHelper;
import com.fadcam.utils.RecordingStoragePaths;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Collections;

/**
 * Foreground service for dual camera (PiP) recording.
 *
 * <p>Manages <b>two</b> {@link CameraDevice} instances (front + back) simultaneously
 * and delegates compositing/encoding to {@link GLRecordingPipeline} with PiP support.
 *
 * <h3>Intent Actions</h3>
 * <ul>
 *   <li>{@link Constants#INTENT_ACTION_START_DUAL_RECORDING}</li>
 *   <li>{@link Constants#INTENT_ACTION_STOP_DUAL_RECORDING}</li>
 *   <li>{@link Constants#INTENT_ACTION_PAUSE_DUAL_RECORDING}</li>
 *   <li>{@link Constants#INTENT_ACTION_RESUME_DUAL_RECORDING}</li>
 *   <li>{@link Constants#INTENT_ACTION_SWAP_DUAL_CAMERAS}</li>
 *   <li>{@link Constants#INTENT_ACTION_UPDATE_PIP_CONFIG}</li>
 * </ul>
 *
 * <h3>Broadcasts</h3>
 * <ul>
 *   <li>{@link Constants#BROADCAST_ON_DUAL_RECORDING_STARTED}</li>
 *   <li>{@link Constants#BROADCAST_ON_DUAL_RECORDING_STOPPED}</li>
 *   <li>{@link Constants#BROADCAST_ON_DUAL_RECORDING_PAUSED}</li>
 *   <li>{@link Constants#BROADCAST_ON_DUAL_RECORDING_RESUMED}</li>
 *   <li>{@link Constants#BROADCAST_ON_DUAL_CAMERA_ERROR}</li>
 *   <li>{@link Constants#BROADCAST_ON_DUAL_CAMERAS_SWAPPED}</li>
 * </ul>
 */
public class DualCameraRecordingService extends Service {

    private static final String TAG = "DualCamService";

    // Reuse RecordingService notification channel for consistent UX
    private static final String CHANNEL_ID = "RecordingServiceChannel";
    private static final int NOTIFICATION_ID = 2; // Different ID from RecordingService (1)

    // ── Camera fields ──────────────────────────────────────────────────

    private CameraManager cameraManager;

    /** Primary camera — fills the full frame. */
    private CameraDevice primaryCameraDevice;
    private CameraCaptureSession primarySession;
    private CaptureRequest.Builder primaryRequestBuilder;

    /** Secondary camera — rendered in PiP overlay. */
    private CameraDevice secondaryCameraDevice;
    private CameraCaptureSession secondarySession;
    private CaptureRequest.Builder secondaryRequestBuilder;

    /** Resolved camera IDs from {@link DualCameraCapability}. */
    private String frontCameraId;
    private String backCameraId;

    /** Current torch state for the primary camera. */
    private boolean isTorchOn = false;

    // ── Pipeline ───────────────────────────────────────────────────────

    /** Unified recording pipeline — same as single-camera mode, but with PiP enabled. */
    private GLRecordingPipeline recordingPipeline;

    /**
     * Same heat steps as single-camera recording: halve the bitrate at SEVERE, stop and
     * save at CRITICAL. Two sensors and a double-size encode is the hottest mode, and it
     * had no guard at all.
     */
    private final com.fadcam.services.RecordingThermalGuard thermalGuard =
            new com.fadcam.services.RecordingThermalGuard(
                    new com.fadcam.services.RecordingThermalGuard.Callbacks() {
                        @Override
                        public void onReduceLoad() {
                            GLRecordingPipeline pipeline = recordingPipeline;
                            if (pipeline != null) {
                                pipeline.setRuntimeVideoBitrate(pipeline.getConfiguredVideoBitrate() / 2);
                            }
                            Toast.makeText(getApplicationContext(), R.string.thermal_guard_reduced,
                                    Toast.LENGTH_LONG).show();
                        }

                        @Override
                        public void onRestoreLoad() {
                            GLRecordingPipeline pipeline = recordingPipeline;
                            if (pipeline != null) {
                                pipeline.setRuntimeVideoBitrate(pipeline.getConfiguredVideoBitrate());
                            }
                        }

                        @Override
                        public void onStopForHeat() {
                            Toast.makeText(getApplicationContext(), R.string.thermal_guard_stopped,
                                    Toast.LENGTH_LONG).show();
                            handleStopDualRecording();
                        }

                        @Override
                        public void onWarm(boolean lowerFrameRate) {
                            applyPreviewFrameRateCap();
                        }

                        @Override
                        public void onCooled(boolean raiseFrameRate) {
                            applyPreviewFrameRateCap();
                        }
                    });

    /**
     * Pauses the preview pass while the display is off. Dual recording drew the
     * full-screen camera, the PiP and the watermark to the preview on every
     * frame even with the screen off; single-camera recording already skipped it.
     */
    @Nullable
    private android.content.BroadcastReceiver screenStateReceiver;

    private void registerScreenStateReceiver() {
        if (screenStateReceiver != null) return;
        screenStateReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                applyScreenStateToPipeline();
            }
        };
        android.content.IntentFilter filter = new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        androidx.core.content.ContextCompat.registerReceiver(this, screenStateReceiver, filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        applyScreenStateToPipeline();
    }

    private void unregisterScreenStateReceiver() {
        if (screenStateReceiver == null) return;
        try {
            unregisterReceiver(screenStateReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        screenStateReceiver = null;
        GLRecordingPipeline pipeline = recordingPipeline;
        if (pipeline != null) {
            pipeline.setPreviewSuspended(false);
        }
    }

    private void applyScreenStateToPipeline() {
        GLRecordingPipeline pipeline = recordingPipeline;
        if (pipeline == null) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        pipeline.setPreviewSuspended(pm != null && !pm.isInteractive());
    }

    /**
     * Applies Settings > Video > Preview Frame Rate, which dual recording ignored
     * (its preview redrew both cameras at the full camera rate). Read when the
     * recording starts and at each thermal step.
     */
    private void applyPreviewFrameRateCap() {
        GLRecordingPipeline pipeline = recordingPipeline;
        if (pipeline == null || prefs == null) return;
        int cap = com.fadcam.utils.PreviewFrameRateMode.current(prefs).capFps(thermalGuard.isWarm());
        pipeline.setPreviewFrameRateCap(cap);
        FLog.i(TAG, "Preview frame-rate cap: " + (cap > 0 ? cap + " fps" : "none"));
    }

    // ── State ──────────────────────────────────────────────────────────

    private volatile DualCameraState state = DualCameraState.DISABLED;
    private DualCameraConfig config;
    private long recordingStartTime;
    private long pauseStartedAt;
    private long accumulatedPausedDurationMs;

    // ── Threading ──────────────────────────────────────────────────────

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private RecordingDurationLimitController durationLimitController;
    private SharedPreferences.OnSharedPreferenceChangeListener durationPreferenceListener;

    // ── System ─────────────────────────────────────────────────────────

    private SharedPreferencesManager prefs;
    private com.fadcam.watermark.WatermarkManager watermarkManager;
    private DualCameraCapability capability;
    private PowerManager.WakeLock wakeLock;

    // Storage location support
    private android.os.ParcelFileDescriptor safRecordingPfd;  // ParcelFileDescriptor for SAF mode
    private Uri safRecordingUri;  // SAF URI
    private String safOutputFileName;   // Filename for SAF
    @Nullable
    private String lastRecordingUriString;

    // Guard against duplicate open/close races
    private volatile boolean isStopping = false;
    private int camerasOpened = 0; // Track how many cameras have opened successfully

    /**
     * Fallback mode flag: when the device cannot open both cameras simultaneously,
     * we record with only the primary camera streaming continuously, and periodically
     * open the secondary camera to capture a single frame for the PiP overlay.
     */
    private volatile boolean fallbackMode = false;

    /**
     * Black frame fallback mode: when dual camera is not supported at all (capability
     * check fails), we use only the primary camera and leave secondary as black.
     * This allows testing dual camera UI/settings on any device.
     */
    private volatile boolean useBlackFrameFallback = false;

    /** The resolved secondary camera ID — stored for use in fallback periodic snapshots. */
    private String resolvedSecondaryId;

    // ════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ════════════════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        FLog.d(TAG, "onCreate");

        prefs = SharedPreferencesManager.getInstance(getApplicationContext());
        initializeDurationLimitController();
        capability = new DualCameraCapability(this);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        if (cameraManager == null) {
            FLog.e(TAG, "CameraManager unavailable — cannot start dual camera service");
            stopSelf();
            return;
        }

        startBackgroundThread();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            FLog.w(TAG, "onStartCommand: null intent/action");
            // A null intent is a sticky restart after a process kill; with
            // nothing recording, don't linger idle (or ask to restart again).
            if (state == DualCameraState.DISABLED) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            return START_STICKY;
        }

        String action = intent.getAction();
        FLog.d(TAG, "onStartCommand: action=" + action);

        switch (action) {
            case Constants.INTENT_ACTION_START_DUAL_RECORDING:
                handleStartDualRecording();
                break;

            case Constants.INTENT_ACTION_STOP_DUAL_RECORDING:
                handleStopDualRecording();
                break;

            case Constants.INTENT_ACTION_PAUSE_DUAL_RECORDING:
                handlePauseDualRecording();
                break;

            case Constants.INTENT_ACTION_RESUME_DUAL_RECORDING:
                handleResumeDualRecording();
                break;

            case Constants.INTENT_ACTION_SWAP_DUAL_CAMERAS:
                handleSwapCameras();
                break;

            case Constants.INTENT_ACTION_UPDATE_PIP_CONFIG:
                handleUpdatePipConfig();
                break;

            case Constants.INTENT_ACTION_CHANGE_SURFACE:
                handleChangeSurface(intent);
                break;

            case Constants.INTENT_ACTION_TOGGLE_RECORDING_TORCH:
                handleToggleTorch();
                break;

            case Constants.INTENT_ACTION_SET_FRONT_VIDEO_MIRROR:
                handleSetFrontVideoMirror(intent);
                break;

            case Constants.INTENT_ACTION_SET_EXPOSURE_COMPENSATION:
                handleSetExposureCompensation(intent);
                break;

            case Constants.INTENT_ACTION_TOGGLE_AE_LOCK:
                handleToggleAeLock(intent);
                break;

            case Constants.INTENT_ACTION_SET_AF_MODE:
                handleSetAfMode(intent);
                break;

            case Constants.INTENT_ACTION_TAP_TO_FOCUS:
                handleTapToFocus();
                break;

            case Constants.INTENT_ACTION_SET_ZOOM_RATIO:
                handleSetZoomRatio(intent);
                break;

            case Constants.INTENT_ACTION_CAPTURE_PHOTO:
                handleCapturePhoto();
                break;

            case Constants.INTENT_ACTION_SET_AUDIO_MUTED:
                // Home's mute button sends this to whichever service records.
                if (recordingPipeline != null) {
                    recordingPipeline.setAudioMuted(
                            intent.getBooleanExtra(Constants.EXTRA_AUDIO_MUTED, false));
                }
                break;

            default:
                FLog.w(TAG, "Unknown action: " + action);
                break;
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        FLog.d(TAG, "onDestroy");
        releaseDurationLimitController();
        releaseAllResources();
        stopBackgroundThread();
        releaseWakeLock();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }

    // ════════════════════════════════════════════════════════════════════
    // ACTION HANDLERS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Start dual camera recording.
     * 1. Validate permissions + capability
     * 2. Show foreground notification
     * 3. Open both cameras sequentially
     * 4. Create pipeline + start encoding
     */
    private void handleStartDualRecording() {
        if (state != DualCameraState.DISABLED) {
            FLog.w(TAG, "Cannot start dual recording — state=" + state);
            return;
        }
        if (durationLimitController != null) {
            durationLimitController.stopSession();
        }
        lastRecordingUriString = null;

        // ── Permission check ──────────────────────────────────────────
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
                || (prefs.isRecordAudioEnabled()
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED)) {
            FLog.e(TAG, "Camera or audio permission not granted");
            failStart(getString(R.string.toast_permissions_required));
            return;
        }

        // ── Capability check ──────────────────────────────────────────
        if (!capability.isSupported()) {
            FLog.w(TAG, "Dual camera not supported: " + capability.getUnsupportedReason());
            FLog.i(TAG, "⚡ Enabling black frame fallback mode for testing");
            useBlackFrameFallback = true;
            
            // For testing: use any available camera for both feeds
            // Try back camera first, fall back to front
            try {
                String[] cameraIds = cameraManager.getCameraIdList();
                for (String id : cameraIds) {
                    CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                    Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        frontCameraId = id; // Use back camera for both
                        backCameraId = id;
                        break;
                    }
                }
                // If no back camera, use first available
                if (frontCameraId == null && cameraIds.length > 0) {
                    frontCameraId = cameraIds[0];
                    backCameraId = cameraIds[0];
                }
            } catch (CameraAccessException e) {
                FLog.e(TAG, "Failed to enumerate cameras for fallback", e);
                failStart(getString(R.string.dual_camera_no_cameras));
                return;
            }
            
            if (frontCameraId == null) {
                failStart(getString(R.string.dual_camera_no_cameras));
                return;
            }
        } else {
            frontCameraId = capability.getConcurrentFrontCameraId();
            backCameraId = capability.getConcurrentBackCameraId();

            if (frontCameraId == null || backCameraId == null) {
                FLog.e(TAG, "Could not resolve front/back camera IDs");
                failStart(getString(R.string.dual_camera_no_cameras));
                return;
            }
        }

        // ── Load config ───────────────────────────────────────────────
        config = prefs.getDualCameraConfig();
        state = DualCameraState.INITIALIZING;
        isStopping = false;
        camerasOpened = 0;

        // ── Foreground notification ───────────────────────────────────
        startForegroundNotification();

        // ── Acquire wake lock ─────────────────────────────────────────
        acquireWakeLock();

        // ── Open cameras ──────────────────────────────────────────────
        openBothCameras();
    }

    /**
     * Stop dual camera recording and clean up all resources.
     */
    // Stops the recording cleanly before the disk is full (writes otherwise fail silently)
    private final com.fadcam.utils.RecordingStorageWatchdog storageWatchdog =
            new com.fadcam.utils.RecordingStorageWatchdog(this,
                    () -> state == DualCameraState.RECORDING || state == DualCameraState.PAUSED,
                    () -> {
                        Toast.makeText(getApplicationContext(), R.string.recording_stopped_storage_full,
                                Toast.LENGTH_LONG).show();
                        handleStopDualRecording();
                    });

    private void handleStopDualRecording() {
        storageWatchdog.stop();
        thermalGuard.stop();
        unregisterScreenStateReceiver();
        if (durationLimitController != null && durationLimitController.stopSession()) {
            FLog.d(TAG, "Cancelled maximum recording duration for current dual session");
        }
        if (state == DualCameraState.DISABLED) {
            FLog.w(TAG, "Already stopped / disabled");
            stopSelf();
            return;
        }

        FLog.i(TAG, "Stopping dual camera recording");
        isStopping = true;
        // A stop during startup (cameras not streaming yet) leaves an empty file
        final boolean wasRecording = state == DualCameraState.RECORDING || state == DualCameraState.PAUSED;
        boolean savedOk = wasRecording;
        state = DualCameraState.DISABLED;
        // Tactile confirmation that dual recording stopped (service-side,
        // gated by the Haptic feedback setting).
        com.fadcam.Utils.vibrateRecordingStop(this);
        fallbackMode = false;
        useBlackFrameFallback = false;
        isCapturingSnapshot = false;

        // Stop pipeline first (drains encoders, finalises muxer)
        if (recordingPipeline != null) {
            try {
                recordingPipeline.stopRecording();
            } catch (Exception e) {
                FLog.e(TAG, "Error stopping pipeline", e);
                savedOk = false;
            }
            recordingPipeline = null;
        }

        // Destroy watermark sensor providers
        if (watermarkManager != null) {
            watermarkManager.destroy();
            watermarkManager = null;
        }

        // Close cameras
        closeCamera(primarySession, primaryCameraDevice, "primary");
        closeCamera(secondarySession, secondaryCameraDevice, "secondary");
        primarySession = null;
        primaryCameraDevice = null;
        secondarySession = null;
        secondaryCameraDevice = null;

        // Close SAF file descriptor if used
        if (safRecordingPfd != null) {
            try {
                safRecordingPfd.close();
                FLog.d(TAG, "Closed SAF ParcelFileDescriptor");
            } catch (Exception e) {
                FLog.e(TAG, "Error closing ParcelFileDescriptor", e);
            }
            safRecordingPfd = null;
            safRecordingUri = null;
            safOutputFileName = null;
        }

        prefs.setRecordingInProgress(false);
        com.fadcam.services.RecordingTileService.requestTileRefresh(this);
        releaseWakeLock();
        clearRecordingTimelineState();
        pauseStartedAt = 0;
        accumulatedPausedDurationMs = 0;

        if (!wasRecording) {
            // Don't leave behind a file that never got any frames. A failed stop of a real
            // recording keeps its file (it may still be partly playable).
            deleteUnfinishedOutput(lastRecordingUriString);
        }
        broadcastRecordingComplete(savedOk);
        lastRecordingUriString = null;
        broadcastAction(Constants.BROADCAST_ON_DUAL_RECORDING_STOPPED);
        stopSelf();
    }

    private void handlePauseDualRecording() {
        if (state != DualCameraState.RECORDING) {
            FLog.w(TAG, "Cannot pause — state=" + state);
            return;
        }

        if (recordingPipeline != null) {
            recordingPipeline.pauseRecording();
        }
        if (watermarkManager != null) watermarkManager.pauseSensors();
        state = DualCameraState.PAUSED;
        // Nothing is encoded while paused, so both camera streams (two sensors,
        // the ISP and a GPU pass per frame) stop until resume. The sessions stay
        // configured so resume is a single request. Like single-camera pause,
        // the preview holds its last frame meanwhile.
        stopCameraStreams();
        if (backgroundHandler != null) backgroundHandler.removeCallbacks(snapshotRunnable);
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (RuntimeException e) {
                FLog.w(TAG, "Could not release wake lock for pause", e);
            }
        }
        pauseStartedAt = SystemClock.elapsedRealtime();
        if (durationLimitController != null && durationLimitController.pauseSession()) {
            FLog.d(TAG, "Paused maximum recording duration countdown");
        }
        persistRecordingTimelineState();
        broadcastActionWithTiming(Constants.BROADCAST_ON_DUAL_RECORDING_PAUSED);
        updatePausedNotification();
        FLog.i(TAG, "Dual recording paused");
    }

    private void handleResumeDualRecording() {
        if (state != DualCameraState.PAUSED) {
            FLog.w(TAG, "Cannot resume — state=" + state);
            return;
        }

        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }
        restartCameraStreams();
        if (recordingPipeline != null) {
            // Frames left over from before the pause are old.
            recordingPipeline.discardPendingCameraFrames();
            recordingPipeline.resumeRecording();
        }
        if (watermarkManager != null) watermarkManager.resumeSensors(null);
        state = DualCameraState.RECORDING;
        if (fallbackMode && !useBlackFrameFallback) {
            scheduleNextSnapshot();
        }
        if (pauseStartedAt > 0L) {
            accumulatedPausedDurationMs += Math.max(0L, SystemClock.elapsedRealtime() - pauseStartedAt);
            pauseStartedAt = 0L;
        }
        if (durationLimitController != null && durationLimitController.resumeSession()) {
            FLog.d(TAG, "Resumed maximum recording duration countdown");
        }
        persistRecordingTimelineState();
        broadcastActionWithTiming(Constants.BROADCAST_ON_DUAL_RECORDING_RESUMED);
        updateResumedNotification();
        FLog.i(TAG, "Dual recording resumed");
    }

    /**
     * Swap primary ↔ secondary cameras by closing and recreating capture sessions.
     * Cameras are reassigned to surfaces so the GL renderer needs no swap logic.
     */
    private void handleSwapCameras() {
        if (state != DualCameraState.RECORDING && state != DualCameraState.PAUSED) {
            FLog.w(TAG, "Cannot swap cameras — state=" + state);
            return;
        }
        if (fallbackMode) {
            // Only the main camera streams here (the PiP camera is opened for
            // snapshots), so a swap would leave the full-screen picture frozen.
            FLog.w(TAG, "Cannot swap cameras in single-stream fallback mode");
            return;
        }

        DualCameraConfig.PrimaryCamera newPrimary =
                (config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.BACK)
                        ? DualCameraConfig.PrimaryCamera.FRONT
                        : DualCameraConfig.PrimaryCamera.BACK;

        config = new DualCameraConfig.Builder(config)
                .primaryCamera(newPrimary)
                .build();
        prefs.saveDualCameraConfig(config);

        // Notify renderer which camera is now front (for rotation/flip logic)
        if (recordingPipeline != null) {
            recordingPipeline.setFullscreenCameraIsFront(
                newPrimary == DualCameraConfig.PrimaryCamera.FRONT);
        }

        // Close existing sessions and stop repeating requests
        if (primarySession != null) {
            try { primarySession.stopRepeating(); primarySession.close(); } catch (Exception ignored) {}
            primarySession = null;
        }
        if (secondarySession != null) {
            try { secondarySession.stopRepeating(); secondarySession.close(); } catch (Exception ignored) {}
            secondarySession = null;
        }

        // Swap camera device references
        CameraDevice tmp = primaryCameraDevice;
        primaryCameraDevice = secondaryCameraDevice;
        secondaryCameraDevice = tmp;
        // Snapshot fallback opens this id if the PiP camera is lost later
        resolvedSecondaryId = (newPrimary == DualCameraConfig.PrimaryCamera.BACK)
                ? frontCameraId : backCameraId;

        // Recreate sessions: each camera targets the SAME surface as before
        // (primaryCameraDevice → cameraInputSurface, secondaryCameraDevice → pipCameraInputSurface)
        if (recordingPipeline != null) {
            Surface ps = recordingPipeline.getPrimaryCameraInputSurface();
            Surface ss = recordingPipeline.getSecondaryCameraInputSurface();
            if (ps != null && primaryCameraDevice != null) {
                createCaptureSession(primaryCameraDevice, ps, true);
            }
            if (ss != null && secondaryCameraDevice != null) {
                createCaptureSession(secondaryCameraDevice, ss, false);
            }
        }

        broadcastAction(Constants.BROADCAST_ON_DUAL_CAMERAS_SWAPPED);
        FLog.i(TAG, "Cameras swapped — new primary: " + newPrimary);
    }

    /**
     * Hot-update PiP configuration (position, size, border) without restarting recording.
     */
    private void handleUpdatePipConfig() {
        // Layout changes apply live; which camera is full-screen stays as the open sessions
        // are, since switching that needs a swap
        DualCameraConfig.PrimaryCamera running = config != null ? config.getPrimaryCamera() : null;
        config = prefs.getDualCameraConfig();
        if (running != null && config.getPrimaryCamera() != running) {
            config = new DualCameraConfig.Builder(config).primaryCamera(running).build();
        }
        if (recordingPipeline != null) {
            recordingPipeline.updateConfig(config);
        }
        FLog.d(TAG, "PiP config updated live");
    }

    /**
     * Handles a preview surface change sent from the UI (HomeFragment).
     * Forwards the surface to the recording pipeline for live preview rendering.
     *
     * @param intent Intent containing "SURFACE" extra and optional dimensions.
     */
    private void handleChangeSurface(@NonNull Intent intent) {
        Surface surface = com.fadcam.Utils.getParcelableExtraCompat(intent, "SURFACE", android.view.Surface.class);
        int surfaceW = intent.getIntExtra("SURFACE_WIDTH", 0);
        int surfaceH = intent.getIntExtra("SURFACE_HEIGHT", 0);
        boolean isFullscreenTransition = intent.getBooleanExtra("IS_FULLSCREEN_TRANSITION", false);

        if (recordingPipeline != null) {
            // Use IMMEDIATE mode for fullscreen to bypass debounce
            if (isFullscreenTransition && surface != null && surface.isValid()) {
                FLog.d(TAG, "Setting preview surface IMMEDIATE (fullscreen transition)");
                recordingPipeline.setPreviewSurfaceImmediate(surface);
            } else {
                recordingPipeline.setPreviewSurface(surface);
            }
            if (surfaceW > 0 && surfaceH > 0) {
                recordingPipeline.updateSurfaceDimensions(surfaceW, surfaceH);
            }
            FLog.d(TAG, "Preview surface updated: " +
                    (surface != null && surface.isValid() ? surfaceW + "x" + surfaceH : "null") +
                    " (immediate=" + isFullscreenTransition + ")");
        } else {
            FLog.w(TAG, "handleChangeSurface: pipeline not ready, surface change ignored");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // RUNTIME CAMERA CONTROLS (torch, zoom, exposure, AF)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Toggles the torch on whichever running camera supports flash.
     * When the primary camera (e.g. front) lacks hardware flash, the secondary
     * (back) camera's session is used instead so torch always works.
     */
    private void handleToggleTorch() {
        if (primaryRequestBuilder == null || primarySession == null) {
            FLog.w(TAG, "handleToggleTorch: no active primary session");
            return;
        }
        isTorchOn = !isTorchOn;

        // Determine which camera session supports torch
        CameraCaptureSession torchSession = primarySession;
        CaptureRequest.Builder torchBuilder = primaryRequestBuilder;
        boolean primaryHasFlash = false;
        try {
            String primaryId = (config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.BACK)
                    ? backCameraId : frontCameraId;
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(primaryId);
            Boolean flashAvail = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            primaryHasFlash = (flashAvail != null && flashAvail);
        } catch (Exception e) {
            FLog.w(TAG, "Could not check flash availability for primary camera", e);
        }

        if (!primaryHasFlash && secondarySession != null && secondaryRequestBuilder != null) {
            torchSession = secondarySession;
            torchBuilder = secondaryRequestBuilder;
            FLog.d(TAG, "Primary camera has no flash — using secondary camera torch");
        }

        torchBuilder.set(CaptureRequest.FLASH_MODE,
                isTorchOn ? CaptureRequest.FLASH_MODE_TORCH
                          : CaptureRequest.FLASH_MODE_OFF);

        try {
            // Also while paused: the torch only lights with a running request,
            // so this session streams again until resume.
            torchSession.setRepeatingRequest(torchBuilder.build(), null, backgroundHandler);
            FLog.d(TAG, "Torch toggled: " + (isTorchOn ? "ON" : "OFF")
                    + " (using " + (torchSession == primarySession ? "primary" : "secondary") + " session)");
        } catch (CameraAccessException | IllegalStateException e) {
            FLog.e(TAG, "Failed to toggle torch", e);
            isTorchOn = !isTorchOn;
            return;
        }

        prefs.sharedPreferences.edit()
                .putBoolean(Constants.PREF_TORCH_STATE, isTorchOn).apply();
        Intent broadcast = new Intent(Constants.BROADCAST_ON_TORCH_STATE_CHANGED);
        broadcast.putExtra(Constants.INTENT_EXTRA_TORCH_STATE, isTorchOn);
        sendBroadcast(broadcast.setPackage(getPackageName()));
    }

    private void handleSetFrontVideoMirror(Intent intent) {
        boolean enabled = intent.getBooleanExtra(
                Constants.EXTRA_FRONT_VIDEO_MIRROR_ENABLED,
                prefs.isFrontVideoMirrorEnabled());
        prefs.setFrontVideoMirrorEnabled(enabled);
        if (recordingPipeline != null) {
            recordingPipeline.setFrontVideoMirrorEnabled(enabled);
        }
        Intent mirrorBcast = new Intent(Constants.BROADCAST_ON_MIRROR_CHANGED);
        mirrorBcast.putExtra(Constants.EXTRA_MIRROR_ENABLED, enabled);
        this.sendBroadcast(mirrorBcast.setPackage(this.getPackageName()));
        FLog.i(TAG, "Front video mirror set: " + enabled);
    }

    /**
     * Sets the exposure compensation value on the primary camera.
     */
    private void handleSetExposureCompensation(@NonNull Intent intent) {
        int ev = intent.getIntExtra(Constants.EXTRA_EXPOSURE_COMPENSATION, 0);
        try {
            prefs.setSavedExposureCompensation(ev);
        } catch (Exception ignored) {
        }
        if (primaryRequestBuilder == null || primarySession == null) return;
        primaryRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev);
        if (applyPrimaryRepeating()) {
            FLog.d(TAG, "Exposure compensation set to: " + ev);
        }
    }

    /**
     * Toggles the AE lock on the primary camera.
     */
    private void handleToggleAeLock(@NonNull Intent intent) {
        boolean lock = intent.getBooleanExtra(Constants.EXTRA_AE_LOCK, false);
        try {
            prefs.setSavedAeLock(lock);
        } catch (Exception ignored) {
        }
        if (primaryRequestBuilder == null || primarySession == null) return;
        primaryRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, lock);
        if (applyPrimaryRepeating()) {
            FLog.d(TAG, "AE lock set to: " + lock);
        }
    }

    /**
     * Sets the autofocus mode on the primary camera.
     */
    private void handleSetAfMode(@NonNull Intent intent) {
        int mode = intent.getIntExtra(Constants.EXTRA_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        try {
            prefs.setSavedAfMode(mode);
        } catch (Exception ignored) {
        }
        if (primaryRequestBuilder == null || primarySession == null) return;
        primaryRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, mode);
        if (applyPrimaryRepeating()) {
            FLog.d(TAG, "AF mode set to: " + mode);
        }
    }

    /**
     * Triggers an AF scan on the primary camera.
     */
    private void handleTapToFocus() {
        if (primaryRequestBuilder == null || primarySession == null) return;
        try {
            primaryRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_START);
            primarySession.capture(primaryRequestBuilder.build(),
                    null, backgroundHandler);
            // Reset trigger for subsequent repeating requests
            primaryRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            FLog.d(TAG, "Tap-to-focus triggered");
        } catch (CameraAccessException e) {
            FLog.e(TAG, "Tap-to-focus failed", e);
        }
    }

    private void handleCapturePhoto() {
        if (state == DualCameraState.PAUSED) {
            // Paused sessions draw no new frames, so the shot would be stale or black
            mainHandler.post(() -> Toast.makeText(getApplicationContext(),
                    R.string.photo_capture_resume_first, Toast.LENGTH_SHORT).show());
            return;
        }
        if (recordingPipeline == null || (state != DualCameraState.RECORDING && state != DualCameraState.PAUSED)) {
            mainHandler.post(() -> Toast.makeText(getApplicationContext(),
                    R.string.photo_capture_preview_unavailable, Toast.LENGTH_SHORT).show());
            return;
        }
        recordingPipeline.capturePhotoFrame(bitmap -> {
            if (bitmap == null) {
                mainHandler.post(() -> Toast.makeText(getApplicationContext(),
                        R.string.photo_capture_failed, Toast.LENGTH_SHORT).show());
                return;
            }
            if (backgroundHandler == null) {
                bitmap.recycle();
                return;
            }
            backgroundHandler.post(() -> {
                // The pipeline drops its video watermark for the snapshot, so stamp
                // the photo watermark here, as single-camera photos do
                Uri savedUri = PhotoStorageHelper.saveJpegBitmap(
                        getApplicationContext(),
                        bitmap,
                        true,
                        PhotoStorageHelper.ShotSource.BACK);
                bitmap.recycle();
                if (savedUri != null) {
                    Intent recordingCompleteIntent = new Intent(Constants.ACTION_RECORDING_COMPLETE);
                    recordingCompleteIntent.putExtra(Constants.EXTRA_RECORDING_SUCCESS, true);
                    recordingCompleteIntent.putExtra(Constants.EXTRA_RECORDING_URI_STRING, savedUri.toString());
                    sendBroadcast(recordingCompleteIntent.setPackage(getPackageName()));
                    mainHandler.post(() -> Toast.makeText(getApplicationContext(),
                            R.string.photo_capture_saved, Toast.LENGTH_SHORT).show());
                } else {
                    mainHandler.post(() -> Toast.makeText(getApplicationContext(),
                            R.string.photo_capture_failed, Toast.LENGTH_SHORT).show());
                }
            });
        });
    }

    /**
     * Sets the zoom ratio on the primary camera (API 30+).
     */
    private void handleSetZoomRatio(@NonNull Intent intent) {
        if (primaryRequestBuilder == null || primarySession == null) return;
        float zoom = intent.getFloatExtra(Constants.EXTRA_ZOOM_RATIO, 1.0f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            primaryRequestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
        }
        if (applyPrimaryRepeating()) {
            FLog.d(TAG, "Zoom ratio set to: " + zoom);
        }
    }

    /**
     * Applies the current primary request builder as a repeating request.
     *
     * @return {@code true} if the request was applied successfully.
     */
    private boolean applyPrimaryRepeating() {
        if (state == DualCameraState.PAUSED) {
            // Kept in the builder; resume starts the stream with it.
            return true;
        }
        try {
            primarySession.setRepeatingRequest(
                    primaryRequestBuilder.build(), null, backgroundHandler);
            return true;
        } catch (CameraAccessException e) {
            FLog.e(TAG, "Failed to apply primary repeating request", e);
            return false;
        } catch (IllegalStateException e) {
            FLog.w(TAG, "Primary session already closed", e);
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // CAMERA OPENING
    // ════════════════════════════════════════════════════════════════════

    /**
     * Opens both cameras <b>sequentially</b> — primary first, then secondary
     * only after the primary is confirmed open. This improves compatibility
     * on devices that do not officially support concurrent camera streams.
     * <p>
     * If {@link #useBlackFrameFallback} is true, opens only the primary camera
     * for testing UI/settings on unsupported devices.
     */
    private void openBothCameras() {
        // ── BLACK FRAME FALLBACK (TEST MODE) ─────────────────────────────
        if (useBlackFrameFallback) {
            FLog.i(TAG, "Black frame fallback: opening only primary camera for testing");
            fallbackMode = true; // Treat as fallback mode (secondary won't stream)
            // Continue to open primary camera only (secondary will remain null)
        }

        // ── REAL CAMERA MODE ──────────────────────────────────────────
        // Determine which physical camera is primary based on config
        String primaryId = (config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.BACK)
                ? backCameraId : frontCameraId;
        String secondaryId = (config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.BACK)
                ? frontCameraId : backCameraId;

        FLog.d(TAG, "Opening primary camera: " + primaryId + ", secondary: " + secondaryId);
        resolvedSecondaryId = secondaryId;

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                FLog.e(TAG, "Camera permission denied");
                transitionToError("Camera permission denied");
                return;
            }

            // Step 1: Open primary camera first
            cameraManager.openCamera(primaryId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    if (isStopping) {
                        camera.close();
                        return;
                    }
                    FLog.d(TAG, "Primary camera opened: " + camera.getId());
                    primaryCameraDevice = camera;

                    if (useBlackFrameFallback) {
                        // Black frame test mode: skip secondary camera entirely
                        FLog.i(TAG, "Black frame test mode: skipping secondary camera");
                        camerasOpened = 1;
                        onPrimaryCameraReadyForFallback();
                    } else {
                        // Step 2: Open secondary camera AFTER primary is confirmed open
                        // Small delay helps on devices with shared camera hardware pipelines
                        backgroundHandler.postDelayed(() -> openSecondaryCamera(secondaryId), 300);
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    FLog.w(TAG, "Primary camera disconnected: " + camera.getId());
                    camera.close();
                    if (lostCameraWasPip(camera)) {
                        return; // Swapped into the PiP: snapshots took over
                    }
                    primaryCameraDevice = null;
                    transitionToError("Primary camera disconnected");
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    FLog.e(TAG, "Primary camera error: " + error);
                    camera.close();
                    if (lostCameraWasPip(camera)) {
                        return;
                    }
                    primaryCameraDevice = null;
                    transitionToError("Primary camera error (code " + error + ")");
                }
            }, backgroundHandler);

        } catch (CameraAccessException | SecurityException e) {
            FLog.e(TAG, "Error opening primary camera", e);
            transitionToError("Failed to open camera: " + e.getMessage());
        }
    }

    /**
     * Opens the secondary (PiP) camera after the primary is already open.
     */
    private void openSecondaryCamera(@NonNull String secondaryId) {
        if (isStopping || primaryCameraDevice == null) {
            FLog.w(TAG, "openSecondaryCamera: Aborting — stopping=" + isStopping);
            return;
        }

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                transitionToError("Camera permission denied");
                return;
            }

            cameraManager.openCamera(secondaryId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    if (isStopping) {
                        camera.close();
                        return;
                    }
                    FLog.d(TAG, "Secondary camera opened: " + camera.getId());
                    secondaryCameraDevice = camera;
                    // Both cameras now open — proceed to pipeline setup
                    camerasOpened = 2;
                    onCameraOpened();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    FLog.w(TAG, "Secondary camera disconnected: " + camera.getId());
                    camera.close();
                    if (camera == primaryCameraDevice) {
                        // Swapped to full screen: the recording can't go on without it
                        primaryCameraDevice = null;
                        transitionToError("Main camera disconnected");
                        return;
                    }
                    secondaryCameraDevice = null;
                    // A call or face unlock taking the front camera lands here, not
                    // in onError: keep recording with snapshots instead of ending it.
                    if (continueWithSnapshotPip()) {
                        return;
                    }
                    transitionToError("Secondary camera disconnected");
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    FLog.w(TAG, "Secondary camera error: " + error
                            + " — falling back to periodic snapshot mode");
                    camera.close();
                    if (camera == primaryCameraDevice) {
                        primaryCameraDevice = null;
                        transitionToError("Main camera error (code " + error + ")");
                        return;
                    }
                    secondaryCameraDevice = null;

                    if (continueWithSnapshotPip()) {
                        return;
                    }

                    // ── FALLBACK: device cannot open both cameras simultaneously ──
                    // Record with primary camera only; periodically snapshot the
                    // secondary camera for the PiP overlay.
                    fallbackMode = true;
                    onPrimaryCameraReadyForFallback();
                }
            }, backgroundHandler);

        } catch (CameraAccessException | SecurityException e) {
            FLog.e(TAG, "Error opening secondary camera", e);
            transitionToError("Failed to open secondary camera: " + e.getMessage());
        }
    }

    /**
     * Each camera's callbacks stay tied to the camera it was opened as, but a swap
     * moves the devices between full screen and PiP. When the lost camera is the
     * current PiP, hands it to the snapshot fallback and returns true.
     */
    private boolean lostCameraWasPip(@NonNull CameraDevice camera) {
        if (camera != secondaryCameraDevice) {
            return false;
        }
        secondaryCameraDevice = null;
        return continueWithSnapshotPip();
    }

    /**
     * The PiP camera was lost after the pipeline was built (recording, paused, or
     * still configuring its sessions). Keeps the pipeline and file and feeds the PiP
     * from snapshots: rebuilding the pipeline orphaned the recording, and its
     * sessions counted past 2 so the new one never started. Returns false when no
     * pipeline exists yet, so the caller's open-time handling applies.
     */
    private boolean continueWithSnapshotPip() {
        if (recordingPipeline == null) {
            return false;
        }
        if (isStopping) {
            return true;
        }
        boolean secondaryCounted = secondarySession != null;
        fallbackMode = true;
        secondarySession = null;
        secondaryRequestBuilder = null;
        snapshotFailStreak = 0;
        if (!secondaryCounted && state != DualCameraState.RECORDING
                && state != DualCameraState.PAUSED) {
            // Its session never configured: count it as done so the primary's
            // session still starts the recording.
            onSessionConfigured(false);
        }
        scheduleNextSnapshot();
        return true;
    }

    /**
     * Called when both cameras are confirmed open. Validates state and
     * proceeds to create the recording pipeline.
     */
    private synchronized void onCameraOpened() {
        FLog.d(TAG, "onCameraOpened — both cameras ready, camerasOpened=" + camerasOpened);

        if (primaryCameraDevice == null || secondaryCameraDevice == null) {
            FLog.e(TAG, "One of the cameras is null despite both reported open");
            transitionToError("Camera initialization failed");
            return;
        }

        // Both cameras ready — build pipeline and start recording
        startDualRecording();
    }

    /**
     * Called when the secondary camera cannot be opened concurrently.
     * Starts recording with only the primary camera and schedules periodic
     * snapshots from the secondary camera to keep the PiP overlay updating
     * (unless in black frame test mode).
     */
    private void onPrimaryCameraReadyForFallback() {
        FLog.i(TAG, "⚡ Entering fallback mode — primary-only recording");

        if (primaryCameraDevice == null) {
            FLog.e(TAG, "Primary camera is null in fallback mode");
            transitionToError("Camera initialization failed");
            return;
        }

        // Proceed to set up pipeline with primary camera only.
        // The pipeline still creates both SurfaceTextures (primary + secondary),
        // but only the primary receives a continuous camera stream.
        startDualRecording();

        // Schedule periodic secondary camera snapshots ONLY if not in black frame test mode
        if (!useBlackFrameFallback) {
            // Delay the first snapshot to let the pipeline stabilise.
            snapshotFailStreak = 0;
            backgroundHandler.removeCallbacks(snapshotRunnable);
            backgroundHandler.postDelayed(snapshotRunnable, 2000);
        } else {
            FLog.d(TAG, "Black frame test mode: skipping periodic snapshots (secondary will remain black)");
        }
    }

    // ── Fallback: periodic secondary camera snapshot ──────────────────

    /** Interval between PiP snapshot updates in fallback mode (ms). */
    private static final long FALLBACK_SNAPSHOT_INTERVAL_MS = 3000;

    /** Flag to prevent overlapping snapshot attempts. */
    private volatile boolean isCapturingSnapshot = false;

    /** One instance, so a pending snapshot can be cancelled and never runs twice. */
    private final Runnable snapshotRunnable = this::captureSecondarySnapshot;

    /**
     * Snapshots in a row that didn't deliver a frame. Each one doubles the wait before
     * the next try (3 s up to 48 s), so a busy camera isn't reopened every 3 s forever.
     */
    private int snapshotFailStreak;

    /**
     * Opens the secondary camera, captures a single frame to the pipeline's
     * secondary SurfaceTexture, then closes it. Reschedules itself.
     */
    private void captureSecondarySnapshot() {
        if (isStopping || state == DualCameraState.DISABLED || state == DualCameraState.ERROR) {
            return;
        }
        if (isCapturingSnapshot) {
            // Previous snapshot still in progress; it schedules the next one when done.
            return;
        }

        isCapturingSnapshot = true;
        // Cleared when a frame arrives; anything else counts as a failure.
        snapshotFailStreak++;
        String secId = resolvedSecondaryId;
        if (secId == null || recordingPipeline == null) {
            isCapturingSnapshot = false;
            return;
        }

        FLog.d(TAG, "Fallback: capturing PiP snapshot from camera " + secId);

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                isCapturingSnapshot = false;
                return;
            }

            cameraManager.openCamera(secId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    if (isStopping) {
                        camera.close();
                        isCapturingSnapshot = false;
                        return;
                    }
                    FLog.d(TAG, "Fallback: secondary camera opened for snapshot");
                    captureOneFrameAndClose(camera);
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    isCapturingSnapshot = false;
                    scheduleNextSnapshot();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    FLog.w(TAG, "Fallback: secondary camera snapshot error: " + error);
                    camera.close();
                    isCapturingSnapshot = false;
                    scheduleNextSnapshot();
                }
            }, backgroundHandler);

        } catch (CameraAccessException | SecurityException e) {
            FLog.w(TAG, "Fallback: cannot open secondary camera for snapshot", e);
            isCapturingSnapshot = false;
            scheduleNextSnapshot();
        }
    }

    /**
     * Captures a single frame from the given camera device targeting the
     * pipeline's secondary SurfaceTexture, then closes the camera.
     */
    @SuppressWarnings("deprecation") // legacy createCaptureSession fallback for < API 28
    private void captureOneFrameAndClose(@NonNull CameraDevice camera) {
        try {
            Surface secondarySurface = recordingPipeline.getSecondaryCameraInputSurface();

            CaptureRequest.Builder builder =
                    camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(secondarySurface);
            builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.hardware.camera2.params.SessionConfiguration sessionConfig =
                    new android.hardware.camera2.params.SessionConfiguration(
                            android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                            Collections.singletonList(
                                    new android.hardware.camera2.params.OutputConfiguration(secondarySurface)),
                            backgroundHandler::post,
                            new android.hardware.camera2.CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (isStopping) {
                                session.close();
                                camera.close();
                                isCapturingSnapshot = false;
                                return;
                            }

                            try {
                                // Capture a few frames to let AE/AF settle, then close
                                builder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                                        CaptureRequest.CONTROL_AF_TRIGGER_START);
                                session.capture(builder.build(),
                                        snapshotCaptureCallback(session, camera), backgroundHandler);

                            } catch (CameraAccessException e) {
                                FLog.w(TAG, "Fallback: capture request failed", e);
                                session.close();
                                camera.close();
                                isCapturingSnapshot = false;
                                scheduleNextSnapshot();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            FLog.w(TAG, "Fallback: snapshot session config failed");
                            session.close();
                            camera.close();
                            isCapturingSnapshot = false;
                            scheduleNextSnapshot();
                        }
                    });

            camera.createCaptureSession(sessionConfig);
            } else {
                camera.createCaptureSession(
                        Collections.singletonList(secondarySurface),
                        new android.hardware.camera2.CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(@NonNull CameraCaptureSession session) {
                                try {
                                    session.capture(builder.build(),
                                            snapshotCaptureCallback(session, camera), backgroundHandler);
                                } catch (CameraAccessException e) {
                                    FLog.w(TAG, "Fallback: capture request failed", e);
                                    session.close();
                                    camera.close();
                                    isCapturingSnapshot = false;
                                    scheduleNextSnapshot();
                                }
                            }

                            @Override
                            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                FLog.w(TAG, "Fallback: snapshot session config failed");
                                session.close();
                                camera.close();
                                isCapturingSnapshot = false;
                                scheduleNextSnapshot();
                            }
                        }, backgroundHandler);
            }

        } catch (CameraAccessException e) {
            FLog.w(TAG, "Fallback: error setting up snapshot session", e);
            camera.close();
            isCapturingSnapshot = false;
            scheduleNextSnapshot();
        }
    }

    /** Schedules the next PiP snapshot in fallback mode. */
    /**
     * One-shot PiP snapshot callback. A failed or aborted capture used to leave
     * the secondary camera open and isCapturingSnapshot stuck, which ended the
     * snapshot loop for the rest of the recording; now every outcome closes the
     * camera and schedules the next try (with backoff on failure).
     */
    private CameraCaptureSession.CaptureCallback snapshotCaptureCallback(
            @NonNull CameraCaptureSession session, @NonNull CameraDevice camera) {
        return new CameraCaptureSession.CaptureCallback() {
            private boolean done;

            private void finish(boolean success) {
                if (done) {
                    return;
                }
                done = true;
                if (success) {
                    FLog.d(TAG, "Fallback: PiP snapshot captured");
                    snapshotFailStreak = 0;
                } else {
                    FLog.w(TAG, "Fallback: PiP snapshot capture failed");
                }
                session.close();
                camera.close();
                isCapturingSnapshot = false;
                scheduleNextSnapshot();
            }

            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession s,
                    @NonNull CaptureRequest request,
                    @NonNull android.hardware.camera2.TotalCaptureResult result) {
                finish(true);
            }

            @Override
            public void onCaptureFailed(@NonNull CameraCaptureSession s,
                    @NonNull CaptureRequest request,
                    @NonNull android.hardware.camera2.CaptureFailure failure) {
                finish(false);
            }

            @Override
            public void onCaptureSequenceAborted(@NonNull CameraCaptureSession s, int sequenceId) {
                finish(false);
            }
        };
    }

    private void scheduleNextSnapshot() {
        if (!isStopping && fallbackMode && state != DualCameraState.DISABLED
                && state != DualCameraState.PAUSED) {
            long delay = FALLBACK_SNAPSHOT_INTERVAL_MS << Math.min(Math.max(snapshotFailStreak, 0), 4);
            backgroundHandler.removeCallbacks(snapshotRunnable);
            backgroundHandler.postDelayed(snapshotRunnable, delay);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // RECORDING SETUP
    // ════════════════════════════════════════════════════════════════════

    /**
     * Builds the {@link GLRecordingPipeline} with PiP support, prepares surfaces,
     * creates Camera2 capture sessions, and starts encoding.
     *
     * <p>Uses the same proven pipeline as single-camera recording, but with
     * {@link DualCameraConfig} to enable PiP compositing in the GL renderer.
     */
    private void startDualRecording() {
        FLog.d(TAG, "startDualRecording: setting up unified pipeline");

        try {
            // ── Resolution + codec ────────────────────────────────────
            Size resolution = prefs.getCameraResolution();
            int videoWidth = resolution.getWidth();
            int videoHeight = resolution.getHeight();
            int fps = prefs.getSpecificVideoFrameRate(
                    config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.BACK
                            ? com.fadcam.CameraType.BACK : com.fadcam.CameraType.FRONT);
            com.fadcam.VideoCodec codec = prefs.getVideoCodec();
            String orientation = prefs.getVideoOrientation();

            // ── Sensor orientation for primary camera ─────────────────
            String primaryId = (config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.BACK)
                    ? backCameraId : frontCameraId;
            int sensorOrientation = 0;
            try {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(primaryId);
                Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (so != null) sensorOrientation = so;
            } catch (CameraAccessException e) {
                FLog.e(TAG, "Error reading sensor orientation", e);
            }

            // ── Output file ───────────────────────────────────────────
            File outputFile = createOutputFile();
            if (safRecordingPfd != null && safRecordingUri != null) {
                lastRecordingUriString = safRecordingUri.toString();
            } else if (outputFile != null) {
                lastRecordingUriString = Uri.fromFile(outputFile).toString();
            } else {
                lastRecordingUriString = null;
            }

            // ── Watermark provider (shared WatermarkManager) ──────────
            watermarkManager = new com.fadcam.watermark.WatermarkManager(this, prefs);
            // DualCameraRecordingService doesn't have its own LocationHelper,
            // so WatermarkManager creates one internally.
            watermarkManager.initialize(null);
            WatermarkInfoProvider watermarkProvider = watermarkManager;
            
            // ── Build unified pipeline with DualCameraConfig ──────────
            if (safRecordingPfd != null) {
                // SAF mode: use FileDescriptor constructor
                FLog.d(TAG, "Building unified pipeline with FileDescriptor (SAF mode)");
                recordingPipeline = new GLRecordingPipeline(
                        this,
                        watermarkProvider,
                        videoWidth, videoHeight,
                        fps,
                        safRecordingPfd.getFileDescriptor(),
                        Long.MAX_VALUE,     // No segment splitting for dual cam
                        1,                  // Segment number
                        null,               // No segment callback
                        null,               // Preview surface (set later if available)
                        orientation,
                        sensorOrientation,
                        codec,
                        null, null,         // No location metadata for now
                        config);            // DualCameraConfig enables PiP
            } else {
                // Internal storage mode: use file path constructor
                if (outputFile == null) {
                    transitionToError("Cannot create output file");
                    return;
                }
                FLog.d(TAG, "Building unified pipeline with file path (internal mode)");
                recordingPipeline = new GLRecordingPipeline(
                        this,
                        watermarkProvider,
                        videoWidth, videoHeight,
                        fps,
                        outputFile.getAbsolutePath(),
                        Long.MAX_VALUE,     // No segment splitting for dual cam
                        1,                  // Segment number
                        null,               // No segment callback
                        null,               // Preview surface (set later if available)
                        orientation,
                        sensorOrientation,
                        codec,
                        null, null,         // No location metadata for now
                        config);            // DualCameraConfig enables PiP
            }

            android.util.Size pipStream = choosePipStreamSize(videoWidth, videoHeight);
            if (pipStream != null) {
                recordingPipeline.setPipStreamSize(pipStream.getWidth(), pipStream.getHeight());
                FLog.i(TAG, "PiP camera stream " + pipStream + " (video " + videoWidth + "x" + videoHeight + ")");
            }
            recordingPipeline.prepareSurfaces();
            // Only a swap used to set this, so a recording that started with the front camera
            // full-screen (after a swap or picking Front) came out rotated, mirroring the PiP
            recordingPipeline.setFullscreenCameraIsFront(
                    config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.FRONT);
            // Home's mute button state carries over to the next recording.
            recordingPipeline.setAudioMuted(prefs.isAudioMuted());

            // ── Create capture sessions ───────────────────────────────
            createCaptureSession(
                    primaryCameraDevice,
                    recordingPipeline.getPrimaryCameraInputSurface(),
                    true /* isPrimary */);

            if (!fallbackMode && secondaryCameraDevice != null) {
                // Normal mode: both cameras stream concurrently
                Surface secondarySurface = recordingPipeline.getSecondaryCameraInputSurface();
                if (secondarySurface != null && secondarySurface.isValid()) {
                    createCaptureSession(
                            secondaryCameraDevice,
                            secondarySurface,
                            false /* isPrimary */);
                } else {
                    FLog.w(TAG, "Secondary camera surface not available, entering fallback mode");
                    fallbackMode = true;
                    onSessionConfigured(false);
                }
            } else {
                // Fallback mode: only primary camera streams; secondary gets
                // periodic snapshots. Mark secondary session as "configured"
                // immediately so the pipeline can start.
                FLog.i(TAG, "Fallback mode: skipping secondary capture session (periodic snapshots)");
                onSessionConfigured(false);
            }

        } catch (Exception e) {
            FLog.e(TAG, "Failed to start dual recording", e);
            transitionToError("Recording setup failed: " + e.getMessage());
        }
    }

    /**
     * Picks a smaller stream for the PiP camera. The PiP box is at most 40% of the
     * output width, yet the second camera streamed at the full video size (1080p
     * or 4K), which costs sensor, ISP and GPU texture work on every frame. The
     * target short side is half the video's, but at least 720 (or the video's own
     * if smaller), which still leaves more pixels than the largest box shows. The
     * size must have the video's aspect ratio and be offered by both cameras,
     * since swapping cameras moves either one into the PiP. Returns null to keep
     * the full video size.
     */
    @Nullable
    private android.util.Size choosePipStreamSize(int videoWidth, int videoHeight) {
        if (cameraManager == null || frontCameraId == null || backCameraId == null) {
            return null;
        }
        int videoLong = Math.max(videoWidth, videoHeight);
        int videoShort = Math.min(videoWidth, videoHeight);
        if (videoShort <= 0) {
            return null;
        }
        int targetShort = Math.max(Math.min(videoShort, 720), videoShort / 2);
        if (targetShort >= videoShort) {
            return null;
        }
        try {
            android.util.Size[] back = streamSizes(backCameraId);
            android.util.Size[] front = streamSizes(frontCameraId);
            if (back == null || front == null) {
                return null;
            }
            java.util.Set<android.util.Size> frontSet = new java.util.HashSet<>(java.util.Arrays.asList(front));
            android.util.Size best = null;
            for (android.util.Size size : back) {
                if (!frontSet.contains(size)) {
                    continue;
                }
                int sLong = Math.max(size.getWidth(), size.getHeight());
                int sShort = Math.min(size.getWidth(), size.getHeight());
                // Same aspect ratio within 1%.
                if (Math.abs((long) sLong * videoShort - (long) sShort * videoLong) * 100L
                        > (long) videoLong * sShort) {
                    continue;
                }
                if (sShort < targetShort || sShort >= videoShort) {
                    continue;
                }
                if (best == null || sShort < Math.min(best.getWidth(), best.getHeight())) {
                    best = size;
                }
            }
            if (best == null) {
                return null;
            }
            // Keep the video's orientation (landscape or portrait sizes).
            boolean videoLandscape = videoWidth >= videoHeight;
            int bLong = Math.max(best.getWidth(), best.getHeight());
            int bShort = Math.min(best.getWidth(), best.getHeight());
            return videoLandscape ? new android.util.Size(bLong, bShort) : new android.util.Size(bShort, bLong);
        } catch (Exception e) {
            FLog.w(TAG, "Could not pick a PiP stream size; using the video size", e);
            return null;
        }
    }

    @Nullable
    private android.util.Size[] streamSizes(String cameraId) throws CameraAccessException {
        CameraCharacteristics chars = cameraManager.getCameraCharacteristics(cameraId);
        android.hardware.camera2.params.StreamConfigurationMap map =
                chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        return map != null ? map.getOutputSizes(android.graphics.SurfaceTexture.class) : null;
    }

    /** PiP Camera Frame Rate setting: caps the secondary camera's AE range. */
    private void applyPipFrameRate(@NonNull CaptureRequest.Builder builder, @NonNull String cameraId) {
        com.fadcam.utils.PipFrameRate mode = com.fadcam.utils.PipFrameRate.current(prefs);
        if (mode.fps <= 0 || cameraManager == null) {
            return;
        }
        try {
            android.util.Range<Integer> range = com.fadcam.utils.PipFrameRate.pickRange(
                    cameraManager.getCameraCharacteristics(cameraId)
                            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES),
                    mode.fps);
            if (range != null) {
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range);
                FLog.i(TAG, "PiP camera fps range " + range);
            }
        } catch (Exception e) {
            FLog.w(TAG, "Could not set the PiP camera frame rate", e);
        }
    }

    /**
     * Creates a Camera2 capture session that targets the given surface.
     */
    @SuppressWarnings("deprecation") // legacy createCaptureSession fallback for < API 28
    private void createCaptureSession(
            @NonNull CameraDevice camera,
            @NonNull Surface targetSurface,
            boolean isPrimary) {

        try {
            CaptureRequest.Builder builder =
                    camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(targetSurface);

            // AF + AE auto
            builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);
            if (!isPrimary) {
                applyPipFrameRate(builder, camera.getId());
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.hardware.camera2.params.SessionConfiguration sessionConfig =
                    new android.hardware.camera2.params.SessionConfiguration(
                            android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                            Collections.singletonList(
                                    new android.hardware.camera2.params.OutputConfiguration(targetSurface)),
                            backgroundHandler::post,
                            new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (isStopping) return;
                            if (!isPrimary && fallbackMode) {
                                // PiP camera lost while configuring; snapshots took over
                                session.close();
                                return;
                            }

                            try {
                                session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                            } catch (CameraAccessException e) {
                                FLog.e(TAG, "Failed to start repeating request", e);
                                transitionToError("Capture request failed");
                                return;
                            }

                            if (isPrimary) {
                                primarySession = session;
                                primaryRequestBuilder = builder;
                            } else {
                                secondarySession = session;
                                secondaryRequestBuilder = builder;
                            }

                            FLog.d(TAG, (isPrimary ? "Primary" : "Secondary")
                                    + " capture session configured");

                            onSessionConfigured(isPrimary);
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            FLog.e(TAG, (isPrimary ? "Primary" : "Secondary")
                                    + " capture session configuration failed");
                            if (!isPrimary && fallbackMode) {
                                return; // PiP camera lost; snapshots took over
                            }
                            transitionToError("Camera session failed");
                        }
                    });

            camera.createCaptureSession(sessionConfig);
            } else {
                camera.createCaptureSession(
                        Collections.singletonList(targetSurface),
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(@NonNull CameraCaptureSession session) {
                                if (isStopping) return;
                                if (!isPrimary && fallbackMode) {
                                    session.close();
                                    return;
                                }
                                try {
                                    session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                                } catch (CameraAccessException e) {
                                    FLog.e(TAG, "Failed to start repeating request", e);
                                    transitionToError("Capture request failed");
                                    return;
                                }
                                if (isPrimary) {
                                    primarySession = session;
                                    primaryRequestBuilder = builder;
                                } else {
                                    secondarySession = session;
                                    secondaryRequestBuilder = builder;
                                }
                                FLog.d(TAG, (isPrimary ? "Primary" : "Secondary")
                                        + " capture session configured");
                                onSessionConfigured(isPrimary);
                            }

                            @Override
                            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                FLog.e(TAG, (isPrimary ? "Primary" : "Secondary")
                                        + " capture session configuration failed");
                                if (!isPrimary && fallbackMode) {
                                    return;
                                }
                                transitionToError("Camera session failed");
                            }
                        }, backgroundHandler);
            }

        } catch (CameraAccessException e) {
            FLog.e(TAG, "Error creating capture session", e);
            transitionToError("Camera session creation error");
        }
    }

    /** Tracks configured sessions; starts pipeline when both are ready. */
    private int sessionsConfigured = 0;

    private synchronized void onSessionConfigured(boolean isPrimary) {
        sessionsConfigured++;
        FLog.d(TAG, "Session configured (" + (isPrimary ? "primary" : "secondary")
                + ") — " + sessionsConfigured + "/2");

        if (sessionsConfigured < 2) return;
        if (sessionsConfigured > 2) {
            // A camera swap recreated this session. The recording is already
            // running: starting it again reset the timer, the duration limit and
            // the thermal guard, and vibrated.
            if (state == DualCameraState.PAUSED) {
                stopRepeatingQuietly(isPrimary ? primarySession : secondarySession);
            }
            return;
        }

        // Both sessions ready — start encoding
        try {
            recordingPipeline.startRecording();
            state = DualCameraState.RECORDING;
            recordingStartTime = SystemClock.elapsedRealtime();
            thermalGuard.start(getApplicationContext(), false);
            applyPreviewFrameRateCap();
            registerScreenStateReceiver();
            prefs.setRecordingInProgress(true);
            com.fadcam.services.RecordingTileService.requestTileRefresh(this);
            // Tactile confirmation that dual recording started (service-side,
            // gated by the Haptic feedback setting).
            com.fadcam.Utils.vibrateRecordingStart(this);

            // Save start time for timer recovery
            getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(Constants.PREF_RECORDING_START_TIME, recordingStartTime)
                    .commit();

            persistRecordingTimelineState();
            startDurationLimitSession();
            storageWatchdog.start();
            broadcastActionWithTiming(Constants.BROADCAST_ON_DUAL_RECORDING_STARTED);
            FLog.i(TAG, "✅ Dual camera recording started");
        } catch (Exception e) {
            FLog.e(TAG, "Failed to start pipeline encoding", e);
            transitionToError("Encoder start failed");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Returns the current timestamp formatted for watermark display.
     * Uses the same format as RecordingService for consistency.
     *
     * @return Formatted timestamp string.
     */
    // ── Thread management ─────────────────────────────────────────────

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("DualCamBg");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    // ── Wake lock ─────────────────────────────────────────────────────

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FadCam:DualCamRecording");
            // No timeout: a recording past 4 hours lost its wake lock and could
            // stall with the screen off. Stop, destroy and pause release it.
            wakeLock.acquire();
        }
    }

    // ── Pause: camera streams ─────────────────────────────────────────

    private void stopCameraStreams() {
        stopRepeatingQuietly(primarySession);
        stopRepeatingQuietly(secondarySession);
    }

    private void stopRepeatingQuietly(@Nullable CameraCaptureSession session) {
        if (session == null) return;
        try {
            session.stopRepeating();
        } catch (CameraAccessException | IllegalStateException e) {
            FLog.w(TAG, "Could not stop camera stream", e);
        }
    }

    private void restartCameraStreams() {
        restartRepeating(primarySession, primaryRequestBuilder);
        restartRepeating(secondarySession, secondaryRequestBuilder);
    }

    private void restartRepeating(@Nullable CameraCaptureSession session,
                                  @Nullable CaptureRequest.Builder builder) {
        if (session == null || builder == null) return;
        try {
            session.setRepeatingRequest(builder.build(), null, backgroundHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            FLog.e(TAG, "Could not restart camera stream after pause", e);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception e) {
                FLog.w(TAG, "Error releasing wake lock", e);
            }
            wakeLock = null;
        }
    }

    // ── Camera close helper ───────────────────────────────────────────

    private void closeCamera(@Nullable CameraCaptureSession session,
                             @Nullable CameraDevice device,
                             @NonNull String label) {
        if (session != null) {
            try {
                session.close();
            } catch (Exception e) {
                FLog.w(TAG, "Error closing " + label + " session", e);
            }
        }
        if (device != null) {
            try {
                device.close();
            } catch (Exception e) {
                FLog.w(TAG, "Error closing " + label + " camera", e);
            }
        }
    }

    /** Release everything in case of crash/destroy. */
    private void releaseAllResources() {
        storageWatchdog.stop();
        if (durationLimitController != null) {
            durationLimitController.stopSession();
        }
        thermalGuard.stop();
        unregisterScreenStateReceiver();
        // Stop fallback snapshot loop
        fallbackMode = false;
        isCapturingSnapshot = false;

        if (recordingPipeline != null) {
            try {
                recordingPipeline.stopRecording();
            } catch (Exception e) {
                FLog.e(TAG, "Error stopping pipeline on destroy", e);
            }
            recordingPipeline = null;
        }

        // Stop the watermark's GPS, compass, noise and geocoder like a normal stop
        // does; the error/destroy path used to leave GPS polling for the process lifetime.
        if (watermarkManager != null) {
            watermarkManager.destroy();
            watermarkManager = null;
        }

        closeCamera(primarySession, primaryCameraDevice, "primary");
        closeCamera(secondarySession, secondaryCameraDevice, "secondary");
        primarySession = null;
        primaryCameraDevice = null;
        secondarySession = null;
        secondaryCameraDevice = null;

        state = DualCameraState.DISABLED;
        sessionsConfigured = 0;
        camerasOpened = 0;
    }

    private void initializeDurationLimitController() {
        durationLimitController = new RecordingDurationLimitController(
                new RecordingDurationLimitController.Scheduler() {
                    @Override
                    public long elapsedRealtime() {
                        return SystemClock.elapsedRealtime();
                    }

                    @Override
                    public void postDelayed(Runnable runnable, long delayMs) {
                        mainHandler.postDelayed(runnable, delayMs);
                    }

                    @Override
                    public void removeCallbacks(Runnable runnable) {
                        mainHandler.removeCallbacks(runnable);
                    }
                },
                prefs::getMaximumRecordingDurationMs,
                () -> {
                    FLog.i(TAG, "Maximum recording duration reached; stopping current dual recording");
                    handleStopDualRecording();
                });

        // Haptic tick for the final 10 seconds of the countdown (gated by the
        // master + "Buttons & controls" toggles inside Utils).
        durationLimitController.setRemainingTickListener(remainingSeconds -> {
            try {
                com.fadcam.Utils.vibrateCountdownTick(DualCameraRecordingService.this, remainingSeconds);
            } catch (Exception ignored) {
            }
        });

        durationPreferenceListener = (preferences, key) -> {
            if (isNotificationPrefKey(key)) {
                // Preset, custom text and the Stop button apply to the running notification
                // too; debounced since custom text is saved on every keystroke
                mainHandler.removeCallbacks(notificationPrefRefresh);
                mainHandler.postDelayed(notificationPrefRefresh, 300);
                return;
            }
            boolean customValueKey =
                    SharedPreferencesManager.PREF_MAX_RECORDING_DURATION_CUSTOM_MINUTES.equals(key)
                            || SharedPreferencesManager.PREF_MAX_RECORDING_DURATION_CUSTOM_SECONDS.equals(key);
            if (!SharedPreferencesManager.PREF_MAX_RECORDING_DURATION_OPTION.equals(key)
                    && !customValueKey) {
                return;
            }
            if (customValueKey
                    && !MaximumRecordingDuration.OPTION_CUSTOM.equals(
                    prefs.getMaximumRecordingDurationOption())) {
                return;
            }
            if (durationLimitController.onLimitChanged()) {
                long limitMs = prefs.getMaximumRecordingDurationMs();
                if (limitMs == 0L) {
                    FLog.i(TAG, "Maximum recording duration disabled for current dual session");
                } else {
                    FLog.i(TAG, "Maximum recording duration updated for current dual session: "
                            + limitMs + " ms");
                }
            }
        };
        prefs.sharedPreferences.registerOnSharedPreferenceChangeListener(durationPreferenceListener);
    }

    private void startDurationLimitSession() {
        if (durationLimitController == null) {
            return;
        }
        long limitMs = durationLimitController.startSession();
        if (limitMs == 0L) {
            FLog.d(TAG, "Maximum recording duration is disabled for this dual session");
        } else {
            FLog.i(TAG, "Maximum recording duration scheduled for current dual session: "
                    + limitMs + " ms");
        }
    }

    private void releaseDurationLimitController() {
        if (durationLimitController != null) {
            durationLimitController.stopSession();
        }
        mainHandler.removeCallbacks(notificationPrefRefresh);
        if (prefs != null && durationPreferenceListener != null) {
            prefs.sharedPreferences.unregisterOnSharedPreferenceChangeListener(
                    durationPreferenceListener);
            durationPreferenceListener = null;
        }
    }

    // ── Error handling ────────────────────────────────────────────────

    private void transitionToError(@NonNull String reason) {
        DualCameraState previousState = state;
        FLog.e(TAG, "Dual camera error: " + reason);
        state = DualCameraState.ERROR;
        broadcastError(reason);
        if (previousState == DualCameraState.RECORDING || previousState == DualCameraState.PAUSED) {
            broadcastRecordingComplete(false);
        }

        mainHandler.post(() ->
                Toast.makeText(getApplicationContext(), reason, Toast.LENGTH_LONG).show());

        // Clean up and stop
        releaseAllResources();
        prefs.setRecordingInProgress(false);
        // Clear the timeline like a normal stop: a leftover start time kept the tile and
        // other screens showing an active recording
        clearRecordingTimelineState();
        pauseStartedAt = 0;
        accumulatedPausedDurationMs = 0;
        com.fadcam.services.RecordingTileService.requestTileRefresh(this);
        releaseWakeLock();
        if (previousState == DualCameraState.RECORDING || previousState == DualCameraState.PAUSED) {
            broadcastAction(Constants.BROADCAST_ON_DUAL_RECORDING_STOPPED);
        }
        stopSelf();
    }

    // ── Output file creation ────────────────────────────────────────────

    @Nullable
    private File createOutputFile() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String filename = "DualCam_" + timestamp + "." + Constants.RECORDING_FILE_EXTENSION;
        
        String storageMode = prefs.getStorageMode();
        
        if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(storageMode)) {
            try {
                String treeUriString = prefs.getCustomStorageUri();
                if (treeUriString == null || treeUriString.isEmpty()) {
                    FLog.e(TAG, "No custom storage location configured");
                    return null;
                }
                DocumentFile treeDoc = com.fadcam.utils.RecordingStoragePaths.getSafCameraSourceDir(
                        this, treeUriString,
                        com.fadcam.utils.RecordingStoragePaths.CameraSource.DUAL, true);
                if (treeDoc == null || !treeDoc.exists() || !treeDoc.canWrite()) {
                    FLog.e(TAG, "Cannot write to custom storage location");
                    return null;
                }
                DocumentFile videoFile = treeDoc.createFile("video/mp4", filename);
                if (videoFile == null) {
                    FLog.e(TAG, "Failed to create SAF file: " + filename);
                    return null;
                }
                safRecordingPfd = getContentResolver().openFileDescriptor(videoFile.getUri(), "w");
                safRecordingUri = videoFile.getUri();
                safOutputFileName = filename;
                FLog.d(TAG, "SAF mode: created file descriptor for " + filename);
                return null;
            } catch (Exception e) {
                FLog.e(TAG, "Error creating SAF file", e);
                return null;
            }
        } else {
            File videoDir = com.fadcam.utils.RecordingStoragePaths.getInternalCameraSourceDir(
                    this, com.fadcam.utils.RecordingStoragePaths.CameraSource.DUAL, true);
            if (videoDir == null) {
                FLog.e(TAG, "Cannot create recording directory for dual camera");
                return null;
            }
            safRecordingPfd = null;
            safRecordingUri = null;
            safOutputFileName = null;
            return new File(videoDir, filename);
        }
    }

    // ── Notification ───────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            // Null on the default preset; a channel needs a name (same fallback as RecordingService)
            String channelName = prefs.getNotificationChannelName();
            CharSequence name = channelName != null ? channelName
                    : getString(R.string.notification_channel_recording, getString(R.string.app_name));
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    CHANNEL_ID, name, android.app.NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            channel.enableVibration(false);
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        } catch (IllegalArgumentException e) {
            FLog.w(TAG, "Notification channel already exists, reusing: " + e.getMessage());
        }
    }

    private NotificationCompat.Builder buildDualCameraNotification() {
        return buildDualCameraNotification(false);
    }

    private NotificationCompat.Builder buildDualCameraNotification(boolean paused) {
        String title = prefs.getNotificationTitle();
        if (title == null || title.isEmpty()) {
            title = getString(R.string.notification_video_recording);
        }
        String preset = prefs.getNotificationPreset();
        String text = prefs.getNotificationText(paused);
        if (text == null || text.isEmpty()) {
            text = getString(paused ? R.string.notification_video_recording_paused_description
                    : R.string.notification_dual_recording);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        int smallIconResId;
        switch (preset) {
            case SharedPreferencesManager.NOTIFICATION_PRESET_SYSTEM_UPDATE:
            case SharedPreferencesManager.NOTIFICATION_PRESET_DOWNLOADING:
                smallIconResId = android.R.drawable.stat_sys_download;
                break;
            case SharedPreferencesManager.NOTIFICATION_PRESET_SYNCING:
                smallIconResId = android.R.drawable.stat_notify_sync;
                break;
            default:
                smallIconResId = R.drawable.ic_notification_icon;
                break;
        }
        builder.setSmallIcon(smallIconResId);

        if (!SharedPreferencesManager.NOTIFICATION_PRESET_DEFAULT.equals(preset)) {
            Intent emptyIntent = new Intent();
            PendingIntent emptyPi = PendingIntent.getActivity(this, 0, emptyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.setContentIntent(emptyPi);
        } else {
            // Tapping opens the app, as the single-camera notification does
            Intent openIntent = new Intent(this, com.fadcam.MainActivity.class)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            builder.setContentIntent(PendingIntent.getActivity(this, 2021, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }

        if (paused) {
            Intent resumeIntent = new Intent(this, DualCameraRecordingService.class)
                    .setAction(Constants.INTENT_ACTION_RESUME_DUAL_RECORDING);
            PendingIntent resumePi = PendingIntent.getService(this, 2022, resumeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(R.drawable.play_button_rounded, getString(R.string.button_resume), resumePi);
        }

        if (!prefs.isNotificationStopButtonHidden()) {
            Intent stopIntent = new Intent(this, DualCameraRecordingService.class);
            stopIntent.setAction(Constants.INTENT_ACTION_STOP_DUAL_RECORDING);
            PendingIntent stopPi = PendingIntent.getService(this, 2020, stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(R.drawable.stop_rounded, getString(R.string.stop_recording), stopPi);
        }

        return builder;
    }

    /**
     * Ends a rejected start. START_DUAL arrives via startForegroundService, and stopping
     * before startForeground() crashes the app ("did not then call startForeground").
     */
    private void failStart(String message) {
        broadcastError(message);
        try {
            startForegroundNotification();
        } catch (Exception e) {
            FLog.w(TAG, "startForeground refused while failing start", e);
        }
        com.fadcam.Utils.stopForegroundCompat(this, true);
        stopSelf();
    }

    private void startForegroundNotification() {
        createNotificationChannel();
        Notification notification = buildDualCameraNotification().build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            // Declaring the mic type without the permission throws on Android 14+
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForeground(NOTIFICATION_ID, notification, type);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private final Runnable notificationPrefRefresh = () -> {
        if (isStopping) return;
        if (state == DualCameraState.RECORDING) {
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) return;
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildDualCameraNotification(false).build());
        } else if (state == DualCameraState.PAUSED) {
            updatePausedNotification();
        }
    };

    private static boolean isNotificationPrefKey(String key) {
        return com.fadcam.services.RecordingService.isNotificationPrefKey(key);
    }

    private void updatePausedNotification() {
        // Always re-post: with the default preset there's no custom paused text, and the
        // notification kept saying it was recording, with no way to resume
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildDualCameraNotification(true).build());
    }

    private void updateResumedNotification() {
        startForegroundNotification();
    }

    // ── Timing persistence ────────────────────────────────────────────

    private void persistRecordingTimelineState() {
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(Constants.PREF_RECORDING_START_TIME, recordingStartTime)
            .putLong(Constants.PREF_RECORDING_PAUSE_STARTED_AT, pauseStartedAt)
            .putLong(Constants.PREF_RECORDING_ACCUMULATED_PAUSED_DURATION, accumulatedPausedDurationMs)
            .commit();
    }

    private void deleteUnfinishedOutput(@Nullable String uriString) {
        if (uriString == null) return;
        try {
            Uri uri = Uri.parse(uriString);
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                //noinspection ResultOfMethodCallIgnored
                new File(uri.getPath()).delete();
            } else {
                android.provider.DocumentsContract.deleteDocument(getContentResolver(), uri);
            }
            FLog.i(TAG, "Removed unfinished dual recording: " + uriString);
        } catch (Exception e) {
            FLog.w(TAG, "Could not remove unfinished dual recording", e);
        }
    }

    private void clearRecordingTimelineState() {
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(Constants.PREF_RECORDING_START_TIME)
            .remove(Constants.PREF_RECORDING_PAUSE_STARTED_AT)
            .remove(Constants.PREF_RECORDING_ACCUMULATED_PAUSED_DURATION)
            .apply();
        FLog.d(TAG, "Cleared dual recording timeline state");
    }

    // ── Broadcasting ──────────────────────────────────────────────────

    /**
     * Sends a regular broadcast (not LocalBroadcastManager) so that receivers
     * registered with {@code context.registerReceiver()} in HomeFragment
     * can receive the events — matching the pattern used by RecordingService.
     */
    private void broadcastAction(@NonNull String action) {
        Intent intent = new Intent(action);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    /** Sends a broadcast with recording timing extras attached. */
    private void broadcastActionWithTiming(@NonNull String action) {
        Intent intent = new Intent(action);
        intent.setPackage(getPackageName());
        intent.putExtra(Constants.INTENT_EXTRA_RECORDING_START_TIME, recordingStartTime);
        intent.putExtra(Constants.INTENT_EXTRA_RECORDING_PAUSE_STARTED_AT, pauseStartedAt);
        intent.putExtra(Constants.INTENT_EXTRA_RECORDING_ACCUMULATED_PAUSED_DURATION, accumulatedPausedDurationMs);
        sendBroadcast(intent);
    }

    private void broadcastError(@NonNull String reason) {
        Intent intent = new Intent(Constants.BROADCAST_ON_DUAL_CAMERA_ERROR);
        intent.putExtra("error_reason", reason);
        sendBroadcast(intent.setPackage(getPackageName()));
    }

    private void broadcastRecordingComplete(boolean success) {
        try {
            Intent recordingCompleteIntent = new Intent(Constants.ACTION_RECORDING_COMPLETE);
            recordingCompleteIntent.putExtra(Constants.EXTRA_RECORDING_SUCCESS, success);
            if (lastRecordingUriString != null && !lastRecordingUriString.isEmpty()) {
                recordingCompleteIntent.putExtra(Constants.EXTRA_RECORDING_URI_STRING, lastRecordingUriString);
            }
            sendBroadcast(recordingCompleteIntent.setPackage(getPackageName()));
            FLog.d(TAG, "Broadcasted ACTION_RECORDING_COMPLETE for dual recording. success=" + success);
        } catch (Exception e) {
            FLog.e(TAG, "Error broadcasting ACTION_RECORDING_COMPLETE for dual recording", e);
        }
    }
}
