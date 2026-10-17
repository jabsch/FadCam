package com.fadcam.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.documentfile.provider.DocumentFile;
import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.utils.AudioDeviceResolver;
import com.fadcam.utils.RecordingStoragePaths;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Audio-only (voice) recording to AAC in an .m4a file, started and stopped from
 * the voice shortcut, a hardware button binding or this service's notification.
 * Files go to a "Voice" folder next to the video folders, in the same internal
 * or custom (SAF) location the app records video to. Input source, raw audio,
 * bitrate and sample rate follow the Audio settings used for video.
 */
public class VoiceRecordingService extends Service {
    private static final String TAG = "VoiceRecordingService";

    public static final String ACTION_START = "com.fadcam.action.START_VOICE_RECORDING";
    public static final String ACTION_STOP = "com.fadcam.action.STOP_VOICE_RECORDING";

    private static final String CHANNEL_ID = "voice_recording";
    private static final int NOTIFICATION_ID = 7341;
    private static final String VOICE_SUBDIR = "Voice";
    private static final String MIME_M4A = "audio/mp4";

    private static volatile boolean running;

    private MediaRecorder recorder;
    private ParcelFileDescriptor outputPfd;
    private String outputLabel;
    /** The file being written (one of the two), deleted when nothing could be saved. */
    private Uri outputUri;
    private File outputFile;
    /** Set while a Bluetooth mic's SCO link is up; restored on stop. */
    private AudioManager scoAudioManager;
    private int originalAudioMode;

    /** True while a voice recording is in progress in this process. */
    public static boolean isRecording() {
        return running;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopAndSave();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action) && recorder == null) {
            // Foreground first: a startForegroundService() caller has a few
            // seconds to get here, and the mic is only usable while foreground.
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(),
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0);
            } catch (RuntimeException e) {
                // Android 12+ refuses a background start, and 14+ refuses a mic
                // service the app isn't allowed to run right now (e.g. locked screen)
                FLog.e(TAG, "Voice recording refused by the system", e);
                Toast.makeText(this, R.string.voice_recording_failed, Toast.LENGTH_LONG).show();
                stopSelf();
                return START_NOT_STICKY;
            }
            if (!startRecorder()) {
                Toast.makeText(this, R.string.voice_recording_failed, Toast.LENGTH_LONG).show();
                stopSelfCleanly();
            }
        } else if (recorder == null) {
            // Restarted without a start command (or a duplicate stop): nothing to do.
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private boolean startRecorder() {
        String fileName = "Voice_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".m4a";
        MediaRecorder mr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new MediaRecorder(this) : new MediaRecorder();
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(this);
        AudioDeviceInfo externalMic = AudioDeviceResolver.resolvePreferredInput(this,
                prefs.getAudioInputSource(), prefs.getAudioInputDeviceType(), prefs.getAudioInputDeviceName());
        boolean bluetoothMic = externalMic != null && isBluetoothInput(externalMic.getType());
        int source = externalMic == null && prefs.isRawAudioEnabled()
                ? rawAudioSource() : MediaRecorder.AudioSource.MIC;
        try {
            mr.setAudioSource(source);
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mr.setAudioSamplingRate(prefs.getAudioSamplingRate());
            // Bluetooth headset mics are mono; the phone's mics follow Audio Channels, as in video.
            mr.setAudioChannels(bluetoothMic ? 1 : com.fadcam.utils.AudioChannelMode.current(prefs).channelCount);
            mr.setAudioEncodingBitRate(prefs.getAudioBitrate());
            if (externalMic != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                mr.setPreferredDevice(externalMic);
            }
            if (!setOutput(mr, fileName)) {
                mr.release();
                return false;
            }
            if (bluetoothMic) {
                startBluetoothSco();
            }
            // Media server death or a full disk: without this the service kept its
            // notification and "recording" state, so the next toggle sent stop
            mr.setOnErrorListener((r, what, extra) -> {
                FLog.e(TAG, "Voice recorder error what=" + what + " extra=" + extra);
                stopAndSave();
            });
            mr.prepare();
            mr.start();
        } catch (Exception e) {
            FLog.e(TAG, "Could not start voice recording", e);
            mr.release();
            deleteUnsavedOutput();
            stopBluetoothSco();
            return false;
        }
        FLog.i(TAG, "Voice recording audio: source=" + source + " rate=" + prefs.getAudioSamplingRate()
                + " bitrate=" + prefs.getAudioBitrate() + " externalMic="
                + (externalMic != null ? externalMic.getProductName() : "none"));
        recorder = mr;
        running = true;
        outputLabel = fileName;
        com.fadcam.Utils.vibrateRecordingStart(this);
        Toast.makeText(this, R.string.voice_recording_started, Toast.LENGTH_SHORT).show();
        FLog.i(TAG, "Voice recording started: " + fileName);
        return true;
    }

    /**
     * Raw audio as in video recording: UNPROCESSED where the phone offers it,
     * else VOICE_RECOGNITION (no noise suppression or AGC).
     */
    private int rawAudioSource() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        boolean unprocessed = am != null && "true".equalsIgnoreCase(
                am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        return unprocessed ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.VOICE_RECOGNITION;
    }

    private static boolean isBluetoothInput(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET;
    }

    /** Bluetooth headset mics only capture over an SCO link in communication mode. */
    private void startBluetoothSco() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return;
        }
        originalAudioMode = am.getMode();
        am.startBluetoothSco();
        am.setBluetoothScoOn(true);
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        com.fadcam.utils.OwnCommunicationMode.acquire();
        scoAudioManager = am;
    }

    private void stopBluetoothSco() {
        AudioManager am = scoAudioManager;
        scoAudioManager = null;
        if (am == null) {
            return;
        }
        try {
            am.stopBluetoothSco();
            am.setBluetoothScoOn(false);
            am.setMode(originalAudioMode);
        } catch (Exception e) {
            FLog.w(TAG, "Could not release Bluetooth SCO", e);
        } finally {
            com.fadcam.utils.OwnCommunicationMode.release();
        }
    }

    /** Points the recorder at the custom (SAF) folder when one is set, else internal storage. */
    private boolean setOutput(MediaRecorder mr, String fileName) throws Exception {
        // The service outlives a recording: never point at the previous, saved file
        outputUri = null;
        outputFile = null;
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(this);
        if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(prefs.getStorageMode())) {
            DocumentFile base = RecordingStoragePaths.getSafBaseDir(this, prefs.getCustomStorageUri());
            DocumentFile dir = base != null
                    ? RecordingStoragePaths.findOrCreateChildDirectory(base, VOICE_SUBDIR, true) : null;
            DocumentFile file = dir != null ? dir.createFile(MIME_M4A, fileName) : null;
            Uri uri = file != null ? file.getUri() : null;
            if (uri != null) {
                // Set first so a failed open still deletes the empty document
                outputUri = uri;
                outputPfd = getContentResolver().openFileDescriptor(uri, "w");
                if (outputPfd != null) {
                    mr.setOutputFile(outputPfd.getFileDescriptor());
                    return true;
                }
                file.delete();
                outputUri = null;
            }
            FLog.w(TAG, "Custom storage unavailable, saving voice recording internally");
        }
        File base = RecordingStoragePaths.getInternalBaseDir(this);
        if (base == null) {
            return false;
        }
        File dir = new File(base, VOICE_SUBDIR);
        if (!dir.exists() && !dir.mkdirs()) {
            return false;
        }
        File out = new File(dir, fileName);
        mr.setOutputFile(out.getAbsolutePath());
        outputFile = out;
        return true;
    }

    /** Removes a recording that could not be finished, which would not play. */
    private void deleteUnsavedOutput() {
        closeOutput();
        try {
            if (outputUri != null) {
                DocumentFile doc = DocumentFile.fromSingleUri(this, outputUri);
                if (doc != null) doc.delete();
            } else if (outputFile != null) {
                outputFile.delete();
            }
        } catch (Exception e) {
            FLog.w(TAG, "Could not delete unsaved voice recording", e);
        }
        outputUri = null;
        outputFile = null;
    }

    private void stopAndSave() {
        MediaRecorder mr = recorder;
        recorder = null;
        if (mr != null) {
            boolean saved = true;
            try {
                mr.stop();
            } catch (RuntimeException e) {
                // stop() throws when no audio was captured (stopped right after start).
                saved = false;
                FLog.w(TAG, "Voice recording stopped before any audio was captured", e);
            } finally {
                mr.release();
            }
            Toast.makeText(this, saved ? R.string.voice_recording_saved : R.string.voice_recording_failed,
                    Toast.LENGTH_SHORT).show();
            FLog.i(TAG, "Voice recording stopped: " + outputLabel + " saved=" + saved);
            if (!saved) {
                deleteUnsavedOutput();
            }
        }
        stopSelfCleanly();
    }

    private void stopSelfCleanly() {
        running = false;
        closeOutput();
        stopBluetoothSco();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void closeOutput() {
        if (outputPfd != null) {
            try {
                outputPfd.close();
            } catch (Exception ignored) {
            }
            outputPfd = null;
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null
                && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    getString(R.string.voice_recording_channel), NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, VoiceRecordingService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(getString(R.string.voice_recording_notification_title))
                .setContentText(getString(R.string.voice_recording_notification_text))
                .setOngoing(true)
                .setUsesChronometer(true)
                .setWhen(System.currentTimeMillis())
                // Tapping the body opens the app; only the Stop action stops (it used to stop too)
                .setContentIntent(PendingIntent.getActivity(this, 1,
                        new Intent(this, com.fadcam.MainActivity.class)
                                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(0, getString(R.string.voice_recording_stop), stop)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    @Override
    public void onDestroy() {
        if (recorder != null) {
            stopAndSave();
        }
        running = false;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
