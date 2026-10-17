package com.fadcam.fadrec.ui;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;

import com.fadcam.Constants;
import com.fadcam.fadrec.MediaProjectionHelper;

/**
 * Transparent activity to request screen capture permission without showing the main app.
 * This allows starting recording from the overlay without bringing the app to foreground.
 * Uses ComponentActivity (not AppCompatActivity) to support transparent theme.
 */
public class TransparentPermissionActivity extends ComponentActivity {
    private static final String TAG = "TransparentPermission";
    
    private MediaProjectionHelper mediaProjectionHelper;
    private ActivityResultLauncher<Intent> screenCapturePermissionLauncher;
    private boolean permissionRequested = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        FLog.d(TAG, "TransparentPermissionActivity created");
        
        // Activity recreated while the prompt is open: the launcher below must still be
        // registered so the pending result is delivered; requestScreenCapturePermission() skips the relaunch
        if (savedInstanceState != null) {
            permissionRequested = savedInstanceState.getBoolean("permissionRequested", false);
            FLog.d(TAG, "Activity recreated, permissionRequested: " + permissionRequested);
        }
        
        // Initialize MediaProjectionHelper
        mediaProjectionHelper = new MediaProjectionHelper(this);
        
        // Register activity result launcher for screen capture permission
        screenCapturePermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                FLog.d(TAG, "Screen capture permission result: " + result.getResultCode());
                FLog.d(TAG, "Result data is null: " + (result.getData() == null));
                
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    // Permission granted, start recording
                    Intent data = result.getData();
                    
                    FLog.d(TAG, "Permission GRANTED - resultCode: " + result.getResultCode());
                    
                    // Start the service directly while this activity is visible, so recording starts
                    // even when FadRec's screen (the old broadcast receiver) is gone and the app is
                    // in the background
                    mediaProjectionHelper.startScreenRecording(result.getResultCode(), data, false);
                } else {
                    // Permission denied or data is null
                    FLog.w(TAG, "Screen capture permission DENIED or data null - resultCode: " + result.getResultCode());
                    
                    Intent broadcastIntent = new Intent(Constants.ACTION_SCREEN_RECORDING_PERMISSION_DENIED);
                    this.sendBroadcast(broadcastIntent.setPackage(this.getPackageName()));
                }
                
                // Close this transparent activity
                finish();
            }
        );
        
        // Request permission immediately (only if not already requested)
        requestScreenCapturePermission();
    }
    
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("permissionRequested", permissionRequested);
        FLog.d(TAG, "Saving state - permissionRequested: " + permissionRequested);
    }
    
    private void requestScreenCapturePermission() {
        if (permissionRequested) {
            FLog.d(TAG, "Permission already requested, skipping");
            return;
        }
        
        try {
            Intent permissionIntent = mediaProjectionHelper.createScreenCaptureIntent();
            if (permissionIntent != null) {
                permissionRequested = true;
                FLog.d(TAG, "Launching permission dialog");
                screenCapturePermissionLauncher.launch(permissionIntent);
            } else {
                FLog.e(TAG, "Failed to create screen capture intent");
                finish();
            }
        } catch (Exception e) {
            FLog.e(TAG, "Error requesting screen capture permission", e);
            finish();
        }
    }
}
