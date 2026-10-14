package com.fadcam.utils;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
import android.util.Size;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.fadcam.CameraType;
import com.fadcam.Constants;
import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * One-tap resolution + frame rate presets (Video settings and the Quick
 * Settings tile). "Max" frame rates are read from the camera at apply time,
 * capped at 60 fps so the standard session is used (120 fps+ needs the
 * constrained high-speed path). {@link #HD_AUTO} records at 1080p max and
 * lets {@link com.fadcam.services.RecordingThermalGuard} drop to 30 fps while
 * the phone is warm.
 */
public enum RecordingPreset {
    HD_30("1080p30", 1920, 1080, 30, R.string.preset_1080p_30),
    HD_MAX("1080p_max", 1920, 1080, RecordingPreset.MAX_FPS, R.string.preset_1080p_max),
    UHD_MAX("4k_max", 3840, 2160, RecordingPreset.MAX_FPS, R.string.preset_4k_max),
    HD_AUTO("1080p_auto", 1920, 1080, RecordingPreset.MAX_FPS, R.string.preset_1080p_auto);

    private static final String TAG = "RecordingPreset";
    private static final String PREF_PRESET = "recording_preset_id";
    /** Sentinel: the highest standard-session rate the camera offers at this size. */
    private static final int MAX_FPS = 0;
    private static final int STANDARD_SESSION_FPS_CAP = 60;
    /** Frame rate 1080p Auto steps down to while the phone is warm. */
    public static final int AUTO_WARM_FPS = 30;

    public final String id;
    public final int width;
    public final int height;
    private final int fps;
    @StringRes public final int labelRes;

    RecordingPreset(String id, int width, int height, int fps, @StringRes int labelRes) {
        this.id = id;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.labelRes = labelRes;
    }

    /** The preset last applied, or null once resolution or fps was changed by hand. */
    @Nullable
    public static RecordingPreset current(SharedPreferencesManager prefs) {
        RecordingPreset preset = fromId(prefs.sharedPreferences.getString(PREF_PRESET, null));
        if (preset == null) {
            return null;
        }
        Size res = prefs.getCameraResolution();
        if (res == null || res.getWidth() != preset.width || res.getHeight() != preset.height) {
            return null;
        }
        return preset;
    }

    @Nullable
    public static RecordingPreset fromId(@Nullable String id) {
        for (RecordingPreset p : values()) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }

    /** Next preset in tile order, wrapping around; the first one after "custom". */
    public static RecordingPreset next(@Nullable RecordingPreset preset) {
        RecordingPreset[] all = values();
        return preset == null ? all[0] : all[(preset.ordinal() + 1) % all.length];
    }

    /** Whether the thermal guard should step the frame rate down when warm. */
    public static boolean isAutoFps(SharedPreferencesManager prefs) {
        return current(prefs) == HD_AUTO;
    }

    /** Forgets the preset, e.g. after the user picks a resolution or fps by hand. */
    public static void clear(SharedPreferencesManager prefs) {
        prefs.sharedPreferences.edit().remove(PREF_PRESET).apply();
    }

    /** Marks a preset as current without touching resolution or fps. */
    public static void markCurrent(SharedPreferencesManager prefs, RecordingPreset preset) {
        prefs.sharedPreferences.edit().putString(PREF_PRESET, preset.id).apply();
    }

    /** Writes this preset's resolution and per-camera frame rates. Takes effect on the next recording. */
    public void apply(Context context) {
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(context);
        int backFps = resolveFps(context, CameraMetadata.LENS_FACING_BACK);
        int frontFps = resolveFps(context, CameraMetadata.LENS_FACING_FRONT);
        prefs.sharedPreferences.edit()
                .putInt(Constants.PREF_VIDEO_RESOLUTION_WIDTH, width)
                .putInt(Constants.PREF_VIDEO_RESOLUTION_HEIGHT, height)
                // Auto bitrate follows the new resolution and fps.
                .putBoolean("bitrate_mode_custom", false)
                .putString(PREF_PRESET, id)
                .apply();
        prefs.setSpecificVideoFrameRate(CameraType.BACK, backFps);
        prefs.setSpecificVideoFrameRate(CameraType.FRONT, frontFps);
        FLog.i(TAG, "Applied preset " + id + ": " + width + "x" + height
                + " back=" + backFps + "fps front=" + frontFps + "fps");
    }

    private int resolveFps(Context context, int lensFacing) {
        if (fps != MAX_FPS) {
            return fps;
        }
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                return AUTO_WARM_FPS;
            }
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != lensFacing) {
                    continue;
                }
                return maxStandardFps(chars, new Size(width, height));
            }
        } catch (Exception e) {
            FLog.w(TAG, "Could not read camera frame rates for preset " + id, e);
        }
        return AUTO_WARM_FPS;
    }

    /**
     * Highest AE target rate (up to 60) that the camera can also sustain at
     * {@code size}; 30 when the size isn't offered or nothing is reported.
     */
    static int maxStandardFps(CameraCharacteristics chars, Size size) {
        int sizeLimit = STANDARD_SESSION_FPS_CAP;
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            boolean offered = false;
            Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
            if (sizes != null) {
                for (Size s : sizes) {
                    if (s.equals(size)) {
                        offered = true;
                        break;
                    }
                }
            }
            if (!offered) {
                return AUTO_WARM_FPS;
            }
            long minFrameNs = map.getOutputMinFrameDuration(SurfaceTexture.class, size);
            if (minFrameNs > 0) {
                sizeLimit = Math.min(sizeLimit, (int) Math.round(1_000_000_000.0 / minFrameNs));
            }
        }
        int best = 0;
        Range<Integer>[] ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges != null) {
            for (Range<Integer> r : ranges) {
                int upper = r.getUpper();
                if (upper <= sizeLimit && upper > best) {
                    best = upper;
                }
            }
        }
        return best > 0 ? best : AUTO_WARM_FPS;
    }
}
