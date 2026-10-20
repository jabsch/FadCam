package com.fadcam;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

public class RecordingStartActivity extends Activity {
    private static final String TAG = "RecordingStartActivity";
    public static final String EXTRA_SHORTCUT_CAMERA_MODE = "shortcut_camera_mode";
    public static final String CAMERA_MODE_BACK = "back";
    public static final String CAMERA_MODE_FRONT = "front";
    public static final String CAMERA_MODE_CURRENT = "current";
    public static final String CAMERA_MODE_DUAL = "dual";
    /**
     * Zoom ratio to start the back camera at, as a string ("0.5", "2") because
     * static shortcut XML extras can only carry strings.
     */
    public static final String EXTRA_SHORTCUT_ZOOM_RATIO = "shortcut_zoom_ratio";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!ExternalTriggerGate.allows(this)) {
            finishWithoutUi(this);
            return;
        }

        try {
            if (RecordingShortcutDispatcher.hasActiveSession(this)) {
                Utils.showQuickToast(this, R.string.recording_already_active);
                return;
            }
            if (com.fadcam.services.VoiceRecordingService.isRecording()) {
                // Both can't use the microphone at once; one file would come out silent
                Toast.makeText(this, R.string.video_recording_voice_busy, Toast.LENGTH_LONG).show();
                return;
            }
            String mode = getIntent() != null
                    ? getIntent().getStringExtra(EXTRA_SHORTCUT_CAMERA_MODE)
                    : null;
            RecordingShortcutDispatcher.start(this, mode, zoomRatio(getIntent()));
        } catch (Exception e) {
            FLog.e(TAG, "Error starting recording via shortcut", e);
            Toast.makeText(this, R.string.shortcut_start_failed, Toast.LENGTH_SHORT).show();
        } finally {
            // Runs in its own throwaway task (taskAffinity=""), so finishing is
            // enough to return to the launcher without touching the app's task.
            finishWithoutUi(this);
        }
    }

    /** The zoom extra as a positive ratio, or null when absent or unreadable. */
    @androidx.annotation.Nullable
    private static Float zoomRatio(android.content.Intent intent) {
        String value = intent != null ? intent.getStringExtra(EXTRA_SHORTCUT_ZOOM_RATIO) : null;
        if (value == null) return null;
        try {
            float ratio = Float.parseFloat(value.trim());
            return ratio > 0f && !Float.isInfinite(ratio) ? ratio : null;
        } catch (NumberFormatException e) {
            FLog.w(TAG, "Ignoring unreadable zoom ratio: " + value);
            return null;
        }
    }

    /** Finishes a trampoline activity with no exit animation. */
    @SuppressWarnings("deprecation") // overridePendingTransition is the only option below API 34
    static void finishWithoutUi(Activity activity) {
        activity.finish();
        activity.overridePendingTransition(0, 0);
    }
}
