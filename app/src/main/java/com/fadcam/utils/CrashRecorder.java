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
        try {
            write("error-" + where, Thread.currentThread(), error);
        } catch (Throwable ignored) {
        }
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
