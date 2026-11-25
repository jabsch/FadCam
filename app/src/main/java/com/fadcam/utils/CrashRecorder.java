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
        StringWriter trace = new StringWriter();
        try (PrintWriter writer = new PrintWriter(trace)) {
            writer.println("Thread: " + thread.getName());
            writer.println();
            error.printStackTrace(writer);
        }
        writeReport(kind, trace.toString());
    }

    private static final String PREFS = "crash_recorder";
    private static final String KEY_LAST_EXIT_TS = "last_reported_exit_ts";
    /** An ANR trace is plain text; keep the start of it (the main thread is dumped first). */
    private static final int MAX_TRACE_CHARS = 256 * 1024;

    /**
     * Reports how the previous processes died when Java never saw it: ANRs, native crashes,
     * low-memory kills while JaCam was in the foreground or recording, and the like. Uses
     * ApplicationExitInfo (Android 11+); each exit is reported once. Ported in spirit from
     * upstream FadCam's DiagnosticsStore. Java crashes are left out: install() already saved them.
     * Runs off the calling thread.
     */
    public static void recordPreviousExits(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                reportExits(app);
            } catch (Throwable ignored) {
            }
        }, "CrashRecorder-exits").start();
    }

    @androidx.annotation.RequiresApi(api = Build.VERSION_CODES.R)
    private static void reportExits(@NonNull Context app) throws Exception {
        android.app.ActivityManager am =
                (android.app.ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return;
        }
        java.util.List<android.app.ApplicationExitInfo> exits =
                am.getHistoricalProcessExitReasons(app.getPackageName(), 0, 16);
        if (exits == null || exits.isEmpty()) {
            return;
        }
        android.content.SharedPreferences prefs =
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long watermark = prefs.getLong(KEY_LAST_EXIT_TS, -1L);
        long newest = watermark;
        for (android.app.ApplicationExitInfo exit : exits) {
            newest = Math.max(newest, exit.getTimestamp());
        }
        prefs.edit().putLong(KEY_LAST_EXIT_TS, newest).apply();
        if (watermark < 0) {
            // First run with this check: don't dump the whole history the system kept.
            return;
        }
        for (android.app.ApplicationExitInfo exit : exits) {
            if (exit.getTimestamp() <= watermark || !isWorthReporting(exit)) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Previous process exit: ").append(reasonName(exit.getReason()))
                    .append(" (reason=").append(exit.getReason())
                    .append(", status=").append(exit.getStatus()).append(")\n");
            sb.append("When: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date(exit.getTimestamp()))).append("\n");
            sb.append("Process: ").append(exit.getProcessName())
                    .append(", importance=").append(exit.getImportance())
                    .append(", pss=").append(exit.getPss() / 1024).append(" MB")
                    .append(", rss=").append(exit.getRss() / 1024).append(" MB\n");
            if (exit.getDescription() != null) {
                sb.append("Description: ").append(exit.getDescription()).append("\n");
            }
            if (exit.getReason() == android.app.ApplicationExitInfo.REASON_ANR) {
                appendTrace(sb, exit);
            }
            writeReport("exit-" + reasonName(exit.getReason()).toLowerCase(Locale.US), sb.toString());
        }
    }

    @androidx.annotation.RequiresApi(api = Build.VERSION_CODES.R)
    private static boolean isWorthReporting(android.app.ApplicationExitInfo exit) {
        switch (exit.getReason()) {
            case android.app.ApplicationExitInfo.REASON_ANR:
            case android.app.ApplicationExitInfo.REASON_CRASH_NATIVE:
            case android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
            case android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return true;
            case android.app.ApplicationExitInfo.REASON_LOW_MEMORY:
                // A cached app being reclaimed is normal; one killed while visible or
                // recording (foreground service) is not.
                return exit.getImportance()
                        <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
            default:
                return false;
        }
    }

    @androidx.annotation.RequiresApi(api = Build.VERSION_CODES.R)
    private static void appendTrace(StringBuilder sb, android.app.ApplicationExitInfo exit) {
        try (java.io.InputStream in = exit.getTraceInputStream()) {
            if (in == null) {
                return;
            }
            sb.append("\n");
            java.io.Reader reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8);
            char[] buf = new char[8192];
            int total = 0;
            int n;
            while ((n = reader.read(buf)) > 0 && total < MAX_TRACE_CHARS) {
                int take = Math.min(n, MAX_TRACE_CHARS - total);
                sb.append(buf, 0, take);
                total += take;
            }
        } catch (Exception e) {
            sb.append("\n(trace unavailable: ").append(e.getClass().getSimpleName()).append(")\n");
        }
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case 2: return "SIGNALED";
            case 3: return "LOW_MEMORY";
            case 5: return "NATIVE_CRASH";
            case 6: return "ANR";
            case 7: return "INIT_FAILURE";
            case 9: return "EXCESSIVE_RESOURCE_USAGE";
            default: return "REASON_" + reason;
        }
    }

    private static void writeReport(@NonNull String kind, @NonNull String body) throws Exception {
        Context context = appContext;
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        StringWriter trace = new StringWriter();
        try (PrintWriter writer = new PrintWriter(trace)) {
            writer.println("JaCam " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
            writer.println("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), "
                    + Build.MANUFACTURER + " " + Build.MODEL);
            writer.print(body);
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
