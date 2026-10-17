package com.fadcam;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Process;

import androidx.annotation.NonNull;

/**
 * Settings > Security > Allow Other Apps to Trigger. The record/stop/toggle/photo/voice/
 * torch/screenshot trampolines stay exported so Tasker, automation apps and adb can use them;
 * with the setting off, a launch from any other app is refused. JaCam's own shortcuts, widget,
 * tiles and notifications run as JaCam, so they keep working either way.
 */
public final class ExternalTriggerGate {

    private static final String TAG = "ExternalTriggerGate";

    private ExternalTriggerGate() {}

    /**
     * @return true when the trampoline may run. When false the caller finishes at once; a
     * short toast says why, so a blocked Tasker task isn't a silent mystery.
     */
    public static boolean allows(@NonNull Activity activity) {
        if (SharedPreferencesManager.getInstance(activity).isExternalTriggersAllowed()) {
            return true;
        }
        if (launchedByThisApp(activity)) {
            return true;
        }
        FLog.w(TAG, "Refused a trigger from another app: " + activity.getClass().getSimpleName());
        Utils.showQuickToast(activity, R.string.external_triggers_blocked_toast);
        return false;
    }

    private static boolean launchedByThisApp(@NonNull Activity activity) {
        if (Build.VERSION.SDK_INT >= 34) {
            // Set by the system from the real caller; it can't be spoofed by the sender
            return activity.getLaunchedFromUid() == Process.myUid();
        }
        // Older Android: getReferrer() falls back to the calling package, but a sender can
        // set it through these extras, which JaCam itself never does
        Intent intent = activity.getIntent();
        if (intent != null && (intent.hasExtra(Intent.EXTRA_REFERRER)
                || intent.hasExtra(Intent.EXTRA_REFERRER_NAME))) {
            return false;
        }
        Uri referrer = activity.getReferrer();
        return referrer != null && activity.getPackageName().equals(referrer.getHost());
    }
}
