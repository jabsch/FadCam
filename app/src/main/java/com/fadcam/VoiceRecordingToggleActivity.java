package com.fadcam;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import com.fadcam.services.VoiceRecordingService;

/**
 * No-UI entry point for the voice recording shortcut: starts an audio-only
 * recording when idle and stops (and saves) it when one is running.
 */
public class VoiceRecordingToggleActivity extends Activity {
    private static final String TAG = "VoiceRecordingToggle";
    private static final RecordingToggleGuard TOGGLE_GUARD = new RecordingToggleGuard();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!ExternalTriggerGate.allows(this)) {
            finish();
            return;
        }
        try {
            if (!TOGGLE_GUARD.tryAcquire(SystemClock.elapsedRealtime())) {
                FLog.w(TAG, "Ignoring duplicate voice toggle request");
                return;
            }
            Intent intent = new Intent(this, VoiceRecordingService.class);
            if (VoiceRecordingService.isRecording()) {
                startService(intent.setAction(VoiceRecordingService.ACTION_STOP));
            } else if (RecordingShortcutDispatcher.hasActiveSession(this)) {
                // Two recordings can't share the mic: one of them would record silence
                Toast.makeText(this, R.string.voice_recording_busy, Toast.LENGTH_LONG).show();
            } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.voice_recording_needs_mic, Toast.LENGTH_LONG).show();
            } else {
                ContextCompat.startForegroundService(this,
                        intent.setAction(VoiceRecordingService.ACTION_START));
            }
        } catch (Exception e) {
            FLog.e(TAG, "Error toggling voice recording", e);
        } finally {
            RecordingStartActivity.finishWithoutUi(this);
        }
    }
}
