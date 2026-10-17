package com.fadcam;

import android.app.Application;
import android.app.ActivityManager;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import android.content.Intent;
import android.content.ComponentName;

public class FadCamApplication extends Application implements DefaultLifecycleObserver {
    @Override
    public void onCreate() {
        super.onCreate();
        com.fadcam.utils.CrashRecorder.install(this);
        // Set while the Motion Lab screen is open and cleared in its onStop. A process
        // death skips onStop, which would leave the debug preview encoding forever.
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(this);
        if (prefs.isMotionDebugUiActive()) {
            prefs.setMotionDebugUiActive(false);
        }
        // Same for a recording: a new process can't have one running, so flags left by a
        // killed process are stale. They blocked dual-camera starts and the repair scan.
        RecordingShortcutDispatcher.hasActiveSession(this);
        // And for the app lock: an unlock saved by a process that crashed or was killed in
        // the foreground (so onStop never ran) opened Records with no PIN
        prefs.setAppLockSessionUnlocked(false);
        // Trash auto-delete only ran when the Trash screen was opened, so expired
        // multi-GB videos kept using storage for weeks. Run it once per process start.
        final android.content.Context appCtx = this;
        new Thread(() -> {
            try {
                com.fadcam.utils.TrashManager.autoDeleteExpiredItems(appCtx,
                        SharedPreferencesManager.getInstance(appCtx).getTrashAutoDeleteMinutes());
            } catch (Exception e) {
                FLog.w("FadCamApplication", "Trash auto-delete at start failed", e);
            }
        }, "trash-auto-delete").start();
        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);
        // Settings > Appearance > Screen Refresh Rate, applied to every JaCam window.
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(@androidx.annotation.NonNull android.app.Activity activity) {
                com.fadcam.utils.DisplayRefreshMode.apply(activity);
                applyCloak(activity);
            }

            @Override
            public void onActivityCreated(@androidx.annotation.NonNull android.app.Activity activity,
                    @androidx.annotation.Nullable android.os.Bundle savedInstanceState) {
                applyCloak(activity);
            }

            @Override
            public void onActivityStarted(@androidx.annotation.NonNull android.app.Activity activity) {}

            @Override
            public void onActivityPaused(@androidx.annotation.NonNull android.app.Activity activity) {}

            @Override
            public void onActivityStopped(@androidx.annotation.NonNull android.app.Activity activity) {}

            @Override
            public void onActivitySaveInstanceState(@androidx.annotation.NonNull android.app.Activity activity,
                    @androidx.annotation.NonNull android.os.Bundle outState) {}

            @Override
            public void onActivityDestroyed(@androidx.annotation.NonNull android.app.Activity activity) {}
        });
        com.fadcam.utils.PixelOptimizedMode.applyOnceIfNeeded(this);
        // Room DB open + invalidation observer registration is deferred off the
        // main thread: cold start must not block on SQLite open. The observer
        // still catches post-kill index writes (invocation is on Room's own
        // background invalidation thread either way).
        new Thread(this::registerSelfHealingScanObserver, "selfheal-observer").start();
    }

    /**
     * Settings > Security > Cloak Recents for every screen but MainActivity, which applies
     * it itself. Only MainActivity had it, so the video player, image viewer and editor
     * showed footage in Recents and allowed screenshots. FadRec's overlay editors are left
     * out: a secure window over the screen would black out the screen recording itself.
     */
    private static void applyCloak(@androidx.annotation.NonNull android.app.Activity activity) {
        if (activity instanceof MainActivity || activity.getClass().getName().startsWith("com.fadcam.fadrec.")) {
            return;
        }
        try {
            boolean cloak = SharedPreferencesManager.getInstance(activity).isCloakRecentsEnabled();
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                activity.setRecentsScreenshotEnabled(!cloak);
            }
            if (cloak) {
                activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            } else {
                activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            }
        } catch (Throwable t) {
            FLog.w("Cloak", "apply failed for " + activity.getClass().getSimpleName(), t);
        }
    }

    /**
     * Native, instant self-healing trigger (issue #332): Room fires this callback
     * the moment ANY row is inserted/updated in the video index — e.g. an
     * abandoned recording being indexed after the process was killed. No polling,
     * no timers: the scan runs exactly when a new file enters the index and only
     * touches rows still marked pending (finalized=0). Single-flight coalescing
     * in the scan itself absorbs bursts.
     */
    private void registerSelfHealingScanObserver() {
        try {
            final android.content.Context app = this;
            androidx.room.RoomDatabase db = com.fadcam.data.VideoIndexDatabase.getInstance(this);
            db.getInvalidationTracker().addObserver(new androidx.room.InvalidationTracker.Observer(
                    new String[]{"video_index"}) {
                @Override
                public void onInvalidated(@androidx.annotation.NonNull java.util.Set<String> tables) {
                    // Runs on Room's invalidation thread (background).
                    try {
                        com.fadcam.services.RecordingService.runSelfHealingScan(app, null);
                    } catch (Exception e) {
                        com.fadcam.FLog.w("FadCamApplication", "Self-healing scan trigger failed", e);
                    }
                }
            });
            com.fadcam.FLog.d("FadCamApplication", "Self-healing scan observer registered (video_index)");
        } catch (Exception e) {
            com.fadcam.FLog.w("FadCamApplication", "Failed to register self-healing scan observer", e);
        }
    }

    @Override
    public void onStop(@androidx.annotation.NonNull LifecycleOwner owner) {
        // App is in background, reset AppLock session
        SharedPreferencesManager.getInstance(this).setAppLockSessionUnlocked(false);
        // Only notify a running service. Starting it just to say "backgrounded" spins
        // up RecordingService (GPS, camera thread, wakelock object) after every
        // shortcut press and leaves it alive as a sticky idle service.
        if (!com.fadcam.utils.ServiceUtils.isServiceRunning(this, com.fadcam.services.RecordingService.class)) {
            return;
        }
        Intent intent = new Intent(this, com.fadcam.services.RecordingService.class);
        intent.setAction("ACTION_APP_BACKGROUND");
        startService(intent);
    }

    @Override
    @SuppressWarnings("deprecation") // getRunningTasks is deprecated; only used to identify the focused activity
    public void onStart(@androidx.annotation.NonNull LifecycleOwner owner) {
        // Don't send it for TextEditorActivity, TransparentPermissionActivity, etc.
        // which are transparent/standalone and shouldn't wake up the main app

        // Like onStop: the action only resumes work of a running service (a live
        // preview or recording). Starting a fresh one just to deliver it left an
        // idle sticky RecordingService behind every time the app was opened.
        if (!com.fadcam.utils.ServiceUtils.isServiceRunning(this, com.fadcam.services.RecordingService.class)) {
            return;
        }
        
        // Get the currently focused activity
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am != null) {
            java.util.List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (!tasks.isEmpty()) {
                ComponentName topActivity = tasks.get(0).topActivity;
                if (topActivity != null) {
                    String activityClassName = topActivity.getClassName();
                    
                    // Only send ACTION_APP_FOREGROUND for MainActivity (camera) or FadRecHomeFragment
                    // Skip for transparent activities like TextEditorActivity, TransparentPermissionActivity
                    boolean isRecordingRelated = activityClassName.contains("MainActivity") || 
                                               activityClassName.contains("FadRecHomeActivity") ||
                                               activityClassName.contains("RecordingActivity");
                    
                    if (isRecordingRelated) {
                        Intent intent = new Intent(this, com.fadcam.services.RecordingService.class);
                        intent.setAction("ACTION_APP_FOREGROUND");
                        startService(intent);
                    }
                    return;
                }
            }
        }
        
        // Fallback: send the broadcast anyway (error case)
        Intent intent = new Intent(this, com.fadcam.services.RecordingService.class);
        intent.setAction("ACTION_APP_FOREGROUND");
        startService(intent);
    }
} 