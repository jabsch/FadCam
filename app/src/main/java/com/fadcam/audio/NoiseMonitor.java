package com.fadcam.audio;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import androidx.core.content.ContextCompat;

import com.fadcam.FLog;

public class NoiseMonitor {
    private static final String TAG = "NoiseMonitor";
    private static NoiseMonitor instance;

    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    /** Samples per blocking read: 250 ms of mono audio. */
    private static final int READ_SAMPLES = SAMPLE_RATE / 4;

    private AudioRecord audioRecord = null;
    /** Whether a noise level is wanted (watermark noise on and monitor started). */
    private volatile boolean isRecording = false;
    /** Whether this monitor's own AudioRecord loop is running. */
    private volatile boolean ownCaptureRunning = false;
    private Thread recordingThread = null;
    private Context appContext;

    private volatile double currentDb = 0f;

    private static final double REF_AMPLITUDE = 32767.0;
    private static final double DB_REFERENCE = 90.0;

    /**
     * Set while a video recording's audio thread is reading the mic. The level
     * then comes from that PCM ({@link #reportPcm16}) instead of a second
     * AudioRecord, so the mic isn't captured twice during a recording.
     */
    private static volatile boolean externalFeedActive = false;

    private NoiseMonitor() {
        FLog.d(TAG, "NoiseMonitor initialized");
    }

    public static synchronized NoiseMonitor getInstance() {
        if (instance == null) {
            instance = new NoiseMonitor();
        }
        return instance;
    }

    /** Called by the recording pipeline when its audio capture starts or stops. */
    public static void setExternalFeedActive(boolean active) {
        NoiseMonitor monitor;
        synchronized (NoiseMonitor.class) {
            externalFeedActive = active;
            monitor = instance;
        }
        // Going inactive leaves the last level in place: it happens when the
        // recording stops, and the service stops the monitor right after.
        if (active && monitor != null && monitor.isRecording) {
            monitor.stopOwnCapture();
        }
    }

    /**
     * Updates the level from the recording's 16-bit little-endian PCM. Cheap
     * no-op unless the noise watermark is running.
     */
    public static void reportPcm16(byte[] pcm, int length) {
        NoiseMonitor monitor = instance;
        if (monitor == null || !monitor.isRecording || length < 2) {
            return;
        }
        long sum = 0;
        int samples = length / 2;
        for (int i = 0; i + 1 < length; i += 2) {
            sum += Math.abs((short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8)));
        }
        monitor.currentDb = toDb((double) sum / samples);
    }

    private static double toDb(double averageAmplitude) {
        double db = DB_REFERENCE + 20 * Math.log10(averageAmplitude / REF_AMPLITUDE);
        return Math.max(0, Math.min(120.0, db));
    }

    public synchronized boolean start(Context context) {
        if (isRecording) {
            return true;
        }
        appContext = context.getApplicationContext();
        isRecording = true;
        if (externalFeedActive) {
            FLog.d(TAG, "NoiseMonitor started on the recording's audio feed");
            return true;
        }
        if (!startOwnCapture()) {
            isRecording = false;
            return false;
        }
        return true;
    }

    private synchronized boolean startOwnCapture() {
        if (ownCaptureRunning) {
            return true;
        }
        Context context = appContext;
        if (context == null) {
            return false;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            FLog.w(TAG, "RECORD_AUDIO permission not granted");
            return false;
        }

        try {
            int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                FLog.e(TAG, "Invalid buffer size");
                return false;
            }

            // Room for two reads (bytes: 16-bit mono) so a blocking read never overruns.
            bufferSize = Math.max(bufferSize, READ_SAMPLES * 2 * 2);

            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                FLog.e(TAG, "AudioRecord not initialized");
                release();
                return false;
            }

            audioRecord.startRecording();
            ownCaptureRunning = true;

            final AudioRecord record = audioRecord;
            final int localBufferSize = bufferSize;
            recordingThread = new Thread(() -> {
                // One blocking read per 250 ms of audio: 4 wakeups a second instead
                // of 10, and every sample counts (read + sleep skipped audio). The
                // watermark shows the level once a second.
                short[] buffer = new short[READ_SAMPLES];
                while (ownCaptureRunning) {
                    int read = record.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        double sum = 0;
                        for (int i = 0; i < read; i++) {
                            sum += Math.abs(buffer[i]);
                        }
                        currentDb = toDb(sum / read);
                    } else if (read < 0) {
                        break; // recorder stopped or failed
                    }
                }
            }, "NoiseMonitorThread");
            recordingThread.start();

            FLog.d(TAG, "NoiseMonitor own capture started, bufferSize=" + localBufferSize);
            return true;

        } catch (Exception e) {
            FLog.e(TAG, "Error starting NoiseMonitor", e);
            ownCaptureRunning = false;
            release();
            return false;
        }
    }

    private synchronized void stopOwnCapture() {
        ownCaptureRunning = false;
        if (recordingThread != null) {
            try {
                recordingThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            recordingThread = null;
        }
        release();
    }

    public synchronized void stop() {
        isRecording = false;
        stopOwnCapture();
        FLog.d(TAG, "NoiseMonitor stopped");
    }

    private void release() {
        if (audioRecord != null) {
            try {
                if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception e) {
                FLog.e(TAG, "Error releasing AudioRecord", e);
            }
            audioRecord = null;
        }
    }

    public double getCurrentDb() {
        return currentDb;
    }

    public String getReadableDb() {
        return String.format("%.0fdB", currentDb);
    }

    public boolean isRunning() {
        return isRecording;
    }

    public static synchronized void resetInstance() {
        if (instance != null) {
            instance.stop();
            instance = null;
        }
    }
}