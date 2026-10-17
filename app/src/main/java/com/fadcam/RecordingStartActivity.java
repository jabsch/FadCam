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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            if (RecordingShortcutDispatcher.hasActiveSession(this)) {
                Utils.showQuickToast(this, R.string.recording_already_active);
                return;
            }
            String mode = getIntent() != null
                    ? getIntent().getStringExtra(EXTRA_SHORTCUT_CAMERA_MODE)
                    : null;
            RecordingShortcutDispatcher.start(this, mode);
        } catch (Exception e) {
            FLog.e(TAG, "Error starting recording via shortcut", e);
            Toast.makeText(this, R.string.shortcut_start_failed, Toast.LENGTH_SHORT).show();
        } finally {
            // Runs in its own throwaway task (taskAffinity=""), so finishing is
            // enough to return to the launcher without touching the app's task.
            finishWithoutUi(this);
        }
    }

    /** Finishes a trampoline activity with no exit animation. */
    @SuppressWarnings("deprecation") // overridePendingTransition is the only option below API 34
    static void finishWithoutUi(Activity activity) {
        activity.finish();
        activity.overridePendingTransition(0, 0);
    }
}
