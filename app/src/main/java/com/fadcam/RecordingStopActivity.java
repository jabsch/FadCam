package com.fadcam;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

public class RecordingStopActivity extends Activity {
    private static final String TAG = "RecordingStopActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            RecordingShortcutDispatcher.stop(this);
        } catch (Exception e) {
            FLog.e(TAG, "Error stopping recording via shortcut", e);
            Toast.makeText(this, "Failed to stop recording", Toast.LENGTH_SHORT).show();
        } finally {
            RecordingStartActivity.finishWithoutUi(this);
        }
    }
}
