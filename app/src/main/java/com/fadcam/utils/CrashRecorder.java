package com.fadcam.utils;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.NonNull;

import com.fadcam.BuildConfig;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Saves the stack trace of a crash, or of an error a screen caught and survived, as
 * a text file in Download/JaCam crash reports, so it can be read and shared without
 * a computer attached.
 */
public final class CrashRecorder {

    private static final String FOLDER = Environment.DIRECTORY_DOWNLOADS + "/JaCam crash reports";
    /** A caught error that repeats (say, on every progress update) is saved once a minute. */
    private static final long NON_FATAL_REPEAT_MS = 60_000L;
    private static final int MAX_NON_FATAL_PER_RUN = 20;
    private static final java.util.Map<String, Long> lastNonFatalAt = new java.util.HashMap<>();
    private static int nonFatalCount;
    private static volatile Context appContext;

    private CrashRecorder() {
    }

    public static void install(@NonNull Context context) {
        appContext = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                write("crash", thread, error);
            } catch (Throwable ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    /** Records an error that was caught, so it can still be reported. */
    public static void recordNonFatal(@NonNull String where, @NonNull Throwable error) {
        String key = where + "|" + error.getClass().getName();
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (lastNonFatalAt) {
            Long last = lastNonFatalAt.get(key);
            if (nonFatalCount >= MAX_NON_FATAL_PER_RUN
                    || (last != null && now - last < NON_FATAL_REPEAT_MS)) {
                return;
            }
            lastNonFatalAt.put(key, now);
            nonFatalCount++;
        }
        final Thread thread = Thread.currentThread();
        // Saved off the calling thread: callers include the main thread.
        new Thread(() -> {
            try {
                write("error-" + where, thread, error);
            } catch (Throwable ignored) {
            }
        }, "CrashRecorder").start();
    }

    private static void write(@NonNull String kind, @NonNull Thread thread, @NonNull Throwable error)
            throws Exception {
        Context context = appContext;
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        StringWriter trace = new StringWriter();
        try (PrintWriter writer = new PrintWriter(trace)) {
            writer.println("JaCam " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
            writer.println("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), "
                    + Build.MANUFACTURER + " " + Build.MODEL);
            writer.println("Thread: " + thread.getName());
            writer.println();
            error.printStackTrace(writer);
        }
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "JaCam-" + kind + "-" + stamp + ".txt");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER);
        Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            return;
        }
        try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            if (out != null) {
                out.write(trace.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
