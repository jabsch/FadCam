package com.fadcam.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.fadcam.FLog;
import com.fadcam.SharedPreferencesManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Stops a recording cleanly before the disk is full. A full disk made every frame write
 * fail silently: the recording kept "running" while saving nothing. This checks free space
 * on the recording volume every few seconds (off the main thread) and calls {@code onLow}
 * on the main thread once it drops below the threshold, so the footage so far is finalized.
 */
public final class RecordingStorageWatchdog {
    private static final String TAG = "StorageWatchdog";
    private static final long INTERVAL_MS = 10_000L;
    // About 10 s of 4K at 100+ Mbps, plus room to finalize the file
    private static final long STOP_THRESHOLD_BYTES = 200L * 1024L * 1024L;

    /** Whether the recording is still running; checked on the main thread. */
    public interface ActiveCheck {
        boolean isActive();
    }

    // Services create this as a field, before their base context exists, so the
    // context is only resolved in start()
    private final Context context;
    private Context appContext;
    private SharedPreferencesManager prefs;
    private final ActiveCheck activeCheck;
    private final Runnable onLow;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService executor;

    public RecordingStorageWatchdog(Context context, ActiveCheck activeCheck, Runnable onLow) {
        this.context = context;
        this.activeCheck = activeCheck;
        this.onLow = onLow;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            ExecutorService exec = executor;
            if (exec == null || exec.isShutdown() || !activeCheck.isActive()) return;
            try {
                exec.execute(() -> {
                    long free;
                    try {
                        StorageInfoCache.clearCache();
                        free = StorageInfoCache.calculateAndCacheStorageInfo(appContext, prefs).availableBytes;
                    } catch (Exception e) {
                        FLog.w(TAG, "Free space check failed", e);
                        free = Long.MAX_VALUE;
                    }
                    final long freeBytes = free;
                    mainHandler.post(() -> {
                        if (executor == null || !activeCheck.isActive()) return;
                        if (freeBytes < STOP_THRESHOLD_BYTES && !isStreamOnly()) {
                            FLog.w(TAG, freeBytes + " bytes free, stopping recording");
                            stop();
                            onLow.run();
                            return;
                        }
                        mainHandler.postDelayed(tick, INTERVAL_MS);
                    });
                });
            } catch (RejectedExecutionException ignored) {
            }
        }
    };

    /** Nothing is written to disk in stream-only mode. */
    private boolean isStreamOnly() {
        try {
            return com.fadcam.streaming.RemoteStreamManager.getInstance().isStreamingEnabled()
                    && prefs.getStreamingMode()
                    == com.fadcam.streaming.RemoteStreamManager.StreamingMode.STREAM_ONLY;
        } catch (Exception e) {
            return false;
        }
    }

    public void start() {
        stop();
        appContext = context.getApplicationContext();
        prefs = SharedPreferencesManager.getInstance(appContext);
        executor = Executors.newSingleThreadExecutor();
        mainHandler.postDelayed(tick, INTERVAL_MS);
    }

    public void stop() {
        mainHandler.removeCallbacks(tick);
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
