package com.fadcam;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;

/**
 * No-UI entry point for the toggle shortcut: starts recording when idle and
 * stops it when a session is live, dispatching the service command directly.
 */
public class RecordingToggleActivity extends Activity {
    private static final String TAG = "RecordingToggleActivity";
    private static final RecordingToggleGuard TOGGLE_GUARD = new RecordingToggleGuard();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!ExternalTriggerGate.allows(this)) {
            RecordingStartActivity.finishWithoutUi(this);
            return;
        }

        try {
            if (!TOGGLE_GUARD.tryAcquire(SystemClock.elapsedRealtime())) {
                FLog.w(TAG, "Ignoring duplicate recording toggle request");
                return;
            }
            if (RecordingShortcutDispatcher.hasActiveSession(this)) {
                FLog.i(TAG, "Toggle: stopping recording");
                RecordingShortcutDispatcher.stop(this);
            } else if (com.fadcam.services.VoiceRecordingService.isRecording()) {
                // Both can't use the microphone at once; one file would come out silent
                android.widget.Toast.makeText(this, R.string.video_recording_voice_busy,
                        android.widget.Toast.LENGTH_LONG).show();
            } else {
                FLog.i(TAG, "Toggle: starting recording");
                RecordingShortcutDispatcher.start(this, RecordingStartActivity.CAMERA_MODE_CURRENT, null);
            }
        } catch (Exception e) {
            FLog.e(TAG, "Error toggling recording via shortcut", e);
        } finally {
            RecordingStartActivity.finishWithoutUi(this);
        }
    }
}
