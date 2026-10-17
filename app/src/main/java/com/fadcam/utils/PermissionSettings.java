package com.fadcam.utils;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import com.fadcam.R;

/** What to do after a runtime permission request comes back denied. */
public final class PermissionSettings {
    private PermissionSettings() {}

    /**
     * Shows {@code deniedMessage}. When Android no longer shows the dialog for
     * {@code permission} ("Don't ask again"), the app's settings page opens too, since
     * that is the only place left to allow it. Call only from a request's result.
     */
    public static void showDenied(@NonNull Fragment fragment, @NonNull String permission,
                                  @StringRes int deniedMessage) {
        if (!fragment.isAdded()) return;
        Toast.makeText(fragment.requireContext(), deniedMessage, Toast.LENGTH_SHORT).show();
        if (fragment.shouldShowRequestPermissionRationale(permission)) return;
        Toast.makeText(fragment.requireContext(), R.string.permission_allow_in_settings, Toast.LENGTH_LONG).show();
        try {
            fragment.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", fragment.requireContext().getPackageName(), null)));
        } catch (ActivityNotFoundException ignored) {
        }
    }
}
