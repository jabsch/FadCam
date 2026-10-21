package com.fadcam.shortcuts;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies customization to ShortcutInfoCompat before pinning/requesting dynamic shortcuts.
 */
public class ShortcutsManager {

    public static final String ID_TORCH = "torch_toggle";
    public static final String ID_TOGGLE = "record_toggle";
    public static final String ID_START = "record_start";
    public static final String ID_START_FRONT = "record_start_front";
    public static final String ID_START_CURRENT = "record_start_current";
    public static final String ID_START_DUAL = "record_start_dual";
    public static final String ID_STOP = "record_stop";
    public static final String ID_SHOT = "capture_photo";
    public static final String ID_SHOT_FRONT = "capture_photo_front";
    public static final String ID_SCREENSHOT = "capture_screenshot";
    public static final String ID_VOICE = "voice_toggle";
    public static final String ID_START_ZOOM_0_5 = "record_start_zoom_0_5";
    public static final String ID_START_ZOOM_1 = "record_start_zoom_1";
    public static final String ID_START_ZOOM_2 = "record_start_zoom_2";
    public static final String ID_START_ZOOM_5 = "record_start_zoom_5";

    /** Every shortcut id, in the order the shortcuts screen lists them. */
    public static final String[] ALL_IDS = {
        ID_TOGGLE, ID_START, ID_STOP, ID_TORCH, ID_START_FRONT, ID_START_CURRENT,
        ID_START_DUAL, ID_VOICE, ID_START_ZOOM_0_5, ID_START_ZOOM_1, ID_START_ZOOM_2,
        ID_START_ZOOM_5, ID_SHOT, ID_SHOT_FRONT, ID_SCREENSHOT
    };

    /** Back-camera zoom a zoom start shortcut records at, or null for other ids. */
    @Nullable
    public static String zoomRatioFor(@NonNull String id) {
        switch (id) {
            case ID_START_ZOOM_0_5: return "0.5";
            case ID_START_ZOOM_1: return "1";
            case ID_START_ZOOM_2: return "2";
            case ID_START_ZOOM_5: return "5";
            default: return null;
        }
    }

    /**
     * Intent that performs the given shortcut, or null for an unknown id. Used by
     * entry points other than the launcher (e.g. hardware button bindings).
     */
    @Nullable
    public static Intent intentFor(@NonNull Context context, @NonNull String id) {
        String zoom = zoomRatioFor(id);
        if (zoom != null) {
            // Toggles: start at this zoom when idle, stop a live recording otherwise
            return activityIntent(context, "com.fadcam.RecordingToggleActivity")
                .putExtra(com.fadcam.RecordingStartActivity.EXTRA_SHORTCUT_ZOOM_RATIO, zoom);
        }
        switch (id) {
            case ID_TOGGLE:
                return activityIntent(context, "com.fadcam.RecordingToggleActivity");
            case ID_START:
                return startIntent(context, com.fadcam.RecordingStartActivity.CAMERA_MODE_BACK);
            case ID_START_FRONT:
                return startIntent(context, com.fadcam.RecordingStartActivity.CAMERA_MODE_FRONT);
            case ID_START_CURRENT:
                return startIntent(context, com.fadcam.RecordingStartActivity.CAMERA_MODE_CURRENT);
            case ID_START_DUAL:
                return startIntent(context, com.fadcam.RecordingStartActivity.CAMERA_MODE_DUAL);
            case ID_STOP:
                return activityIntent(context, "com.fadcam.RecordingStopActivity");
            case ID_TORCH:
                return activityIntent(context, "com.fadcam.TorchToggleActivity");
            case ID_SHOT:
                return activityIntent(context, "com.fadcam.PhotoCaptureActivity");
            case ID_SHOT_FRONT:
                return activityIntent(context, "com.fadcam.PhotoCaptureActivity").putExtra(
                    com.fadcam.PhotoCaptureActivity.EXTRA_SHORTCUT_PHOTO_CAMERA_MODE,
                    com.fadcam.PhotoCaptureActivity.PHOTO_CAMERA_MODE_FRONT);
            case ID_SCREENSHOT:
                return activityIntent(context, "com.fadcam.ScreenShotCaptureActivity");
            case ID_VOICE:
                return activityIntent(context, "com.fadcam.VoiceRecordingToggleActivity");
            default:
                return null;
        }
    }

    /** User-facing name of a shortcut id (the default, not a custom label). */
    @NonNull
    public static String defaultLabelFor(@NonNull Context context, @NonNull String id) {
        int res;
        switch (id) {
            case ID_TOGGLE: res = com.fadcam.R.string.shortcut_toggle_recording; break;
            case ID_START: res = com.fadcam.R.string.shortcut_start_back; break;
            case ID_START_FRONT: res = com.fadcam.R.string.shortcut_start_front; break;
            case ID_START_CURRENT: res = com.fadcam.R.string.shortcut_start_current; break;
            case ID_START_DUAL: res = com.fadcam.R.string.shortcut_start_dual; break;
            case ID_STOP: res = com.fadcam.R.string.stop_recording; break;
            case ID_TORCH: res = com.fadcam.R.string.torch_shortcut_short_label; break;
            case ID_SHOT: res = com.fadcam.R.string.shortcut_take_photo; break;
            case ID_SHOT_FRONT: res = com.fadcam.R.string.shortcut_take_photo_front; break;
            case ID_SCREENSHOT: res = com.fadcam.R.string.shortcut_take_screenshot; break;
            case ID_VOICE: res = com.fadcam.R.string.shortcut_voice_recording; break;
            case ID_START_ZOOM_0_5: res = com.fadcam.R.string.shortcut_start_zoom_0_5; break;
            case ID_START_ZOOM_1: res = com.fadcam.R.string.shortcut_start_zoom_1; break;
            case ID_START_ZOOM_2: res = com.fadcam.R.string.shortcut_start_zoom_2; break;
            case ID_START_ZOOM_5: res = com.fadcam.R.string.shortcut_start_zoom_5; break;
            default: return id;
        }
        return context.getString(res);
    }

    /** Built-in icon of a shortcut id. */
    @DrawableRes
    public static int defaultIconFor(@NonNull String id) {
        switch (id) {
            case ID_TOGGLE: return com.fadcam.R.drawable.toggle_recording_shortcut;
            case ID_START: return com.fadcam.R.drawable.start_back_shortcut;
            case ID_START_FRONT: return com.fadcam.R.drawable.start_front_shortcut;
            case ID_START_CURRENT: return com.fadcam.R.drawable.start_current_shortcut;
            case ID_START_DUAL: return com.fadcam.R.drawable.start_dual_shortcut;
            case ID_STOP: return com.fadcam.R.drawable.stop_shortcut;
            case ID_SHOT: return com.fadcam.R.drawable.fadshot_shortcut;
            case ID_SHOT_FRONT: return com.fadcam.R.drawable.fadshot_front_shortcut;
            case ID_SCREENSHOT: return com.fadcam.R.drawable.fadrec_screenshot_shortcut;
            case ID_VOICE: return com.fadcam.R.drawable.voice_shortcut;
            case ID_START_ZOOM_0_5: return com.fadcam.R.drawable.start_zoom_0_5_shortcut;
            case ID_START_ZOOM_1: return com.fadcam.R.drawable.start_zoom_1_shortcut;
            case ID_START_ZOOM_2: return com.fadcam.R.drawable.start_zoom_2_shortcut;
            case ID_START_ZOOM_5: return com.fadcam.R.drawable.start_zoom_5_shortcut;
            default: return com.fadcam.R.drawable.flashlight_shortcut;
        }
    }

    private static Intent activityIntent(Context context, String className) {
        return new Intent(Intent.ACTION_VIEW).setClassName(context, className);
    }

    private static Intent startIntent(Context context, String mode) {
        return activityIntent(context, "com.fadcam.RecordingStartActivity")
            .putExtra(com.fadcam.RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE, mode);
    }

    private final Context ctx;
    private final ShortcutsPreferences prefs;

    public ShortcutsManager(@NonNull Context context) {
        this.ctx = context.getApplicationContext();
        this.prefs = new ShortcutsPreferences(ctx);
    }

    public ShortcutInfoCompat buildShortcut(
        @NonNull String id,
        @NonNull Intent intent,
        @DrawableRes int defaultIcon,
        @NonNull CharSequence defaultLabel
    ) {
        return buildShortcut(id, intent, defaultIcon, defaultLabel, defaultLabel);
    }

    public ShortcutInfoCompat buildShortcut(
        @NonNull String id,
        @NonNull Intent intent,
        @DrawableRes int defaultIcon,
        @NonNull CharSequence defaultShortLabel,
        @NonNull CharSequence defaultLongLabel
    ) {
        ShortcutInfoCompat.Builder b = new ShortcutInfoCompat.Builder(ctx, id);
        String customLabel = prefs.getCustomLabel(id);
        b.setShortLabel(customLabel != null ? customLabel : defaultShortLabel);
        b.setLongLabel(customLabel != null ? customLabel : defaultLongLabel);
        IconCompat icon = resolveIcon(id, defaultIcon);
        if (icon != null) b.setIcon(icon);
        b.setIntent(intent);
        return b.build();
    }

    /**
     * Build a ShortcutInfoCompat specifically for pinning. If there's customization for the given
     * id, we use a distinct id (id + "_custom") to avoid launchers falling back to static XML.
     */
    public ShortcutInfoCompat buildShortcutForPin(
        @NonNull String id,
        @NonNull Intent intent,
        @DrawableRes int defaultIcon,
        @NonNull CharSequence defaultLabel
    ) {
        boolean hasCustom =
            prefs.getCustomLabel(id) != null ||
            prefs.getCustomIconPath(id) != null;
        // Use a dedicated pinned id namespace to avoid launchers reusing stale static shortcut metadata.
        String pinId = hasCustom ? (id + "_pin_custom") : (id + "_pin");
        return buildPinned(id, pinId, intent, defaultIcon, defaultLabel);
    }

    private ShortcutInfoCompat buildPinned(
        @NonNull String id,
        @NonNull String pinId,
        @NonNull Intent intent,
        @DrawableRes int defaultIcon,
        @NonNull CharSequence defaultLabel
    ) {
        ShortcutInfoCompat.Builder b = new ShortcutInfoCompat.Builder(
            ctx,
            pinId
        );
        String customLabel = prefs.getCustomLabel(id);
        CharSequence label = customLabel != null ? customLabel : defaultLabel;
        b.setShortLabel(label);
        b.setLongLabel(label);
        IconCompat icon = resolveIcon(id, defaultIcon);
        if (icon != null) b.setIcon(icon);
        b.setIntent(intent);
        return b.build();
    }

    @Nullable
    private IconCompat resolveIcon(
        @NonNull String id,
        @DrawableRes int fallback
    ) {
        // Prefer resource id when present to preserve adaptive icon masks and scaling
        int resId = prefs.getCustomIconRes(id);
        if (resId != 0) {
            return IconCompat.createWithResource(ctx, resId);
        }
        String path = prefs.getCustomIconPath(id);
        if (path != null) {
            File f = new File(path);
            if (f.exists()) {
                android.graphics.Bitmap bmp = BitmapFactory.decodeFile(path);
                if (bmp != null) {
                    if (
                        android.os.Build.VERSION.SDK_INT >=
                        android.os.Build.VERSION_CODES.O
                    ) {
                        try {
                            return IconCompat.createWithAdaptiveBitmap(bmp);
                        } catch (Throwable ignored) {}
                    }
                    return IconCompat.createWithBitmap(bmp);
                }
            }
        }
        return IconCompat.createWithResource(ctx, fallback);
    }

    public void reset(@NonNull String id) {
        prefs.reset(id);
    }

    public void setCustomLabel(@NonNull String id, @Nullable String label) {
        prefs.setCustomLabel(id, label);
    }

    public boolean setCustomIconFromUri(
        @NonNull String id,
        @NonNull android.net.Uri uri
    ) {
        return prefs.setCustomIconFromUri(id, uri) != null;
    }

    /**
     * Persists a custom icon for the given shortcut from a Bitmap.
     * Returns true if saved successfully.
     */
    public boolean setCustomIconFromBitmap(
        @NonNull String id,
        @NonNull android.graphics.Bitmap bitmap
    ) {
        return prefs.setCustomIconFromBitmap(id, bitmap) != null;
    }


    /**
     * Persists a custom icon for the given shortcut from a drawable resource (including adaptive icons).
     * The drawable is rasterized to a 192x192 bitmap before saving.
     */
    public boolean setCustomIconFromResId(
        @NonNull String id,
        @DrawableRes int resId
    ) {
        try {
            // Store the resource id directly so launcher can apply the correct mask/scale
            prefs.setCustomIconRes(id, resId);
            // Ensure any stale bitmap path is ignored (handled inside prefs as well)
            return true;
        } catch (Throwable t) {
            return false;
        }
    }


    public boolean isPinSupported() {
        return ShortcutManagerCompat.isRequestPinShortcutSupported(ctx);
    }

    public void requestPin(@NonNull ShortcutInfoCompat info) {
        // The pin request carries the label and icon itself. Pushing it as a dynamic
        // shortcut too listed it twice in the launcher long-press menu.
        ShortcutManagerCompat.requestPinShortcut(ctx, info, null);
    }

    /**
     * Publishes/replaces dynamic shortcuts so that launcher long-press shows customized entries.
     */
    public void publishAllDynamic() {
        List<ShortcutInfoCompat> list = new ArrayList<>();
        // Toggle recording
        list.add(
            buildShortcut(
                ID_TOGGLE,
                new Intent(Intent.ACTION_VIEW).setClassName(
                    ctx,
                    "com.fadcam.RecordingToggleActivity"
                ),
                com.fadcam.R.drawable.toggle_recording_shortcut,
                ctx.getString(com.fadcam.R.string.shortcut_toggle_recording),
                ctx.getString(com.fadcam.R.string.shortcut_toggle_recording_long)
            )
        );
        // Voice (audio-only) recording toggle, near the top so launchers that
        // show only the first few dynamic shortcuts still offer it.
        list.add(
            buildShortcut(
                ID_VOICE,
                intentFor(ctx, ID_VOICE),
                com.fadcam.R.drawable.voice_shortcut,
                ctx.getString(com.fadcam.R.string.shortcut_voice_recording),
                ctx.getString(com.fadcam.R.string.shortcut_voice_recording_long)
            )
        );
        // Start
        list.add(
            buildShortcut(
                ID_START,
                new Intent(Intent.ACTION_VIEW).setClassName(
                    ctx,
                    "com.fadcam.RecordingStartActivity"
                ).putExtra(
                    com.fadcam.RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE,
                    com.fadcam.RecordingStartActivity.CAMERA_MODE_BACK
                ),
                com.fadcam.R.drawable.start_back_shortcut,
                ctx.getString(com.fadcam.R.string.shortcut_start_back)
            )
        );
        // Stop
        list.add(
            buildShortcut(
                ID_STOP,
                new Intent(Intent.ACTION_VIEW).setClassName(
                    ctx,
                    "com.fadcam.RecordingStopActivity"
                ),
                com.fadcam.R.drawable.stop_shortcut,
                ctx.getString(com.fadcam.R.string.stop_recording)
            )
        );
        list.add(
            buildShortcut(
                ID_START_FRONT,
                new Intent(Intent.ACTION_VIEW).setClassName(
                    ctx,
                    "com.fadcam.RecordingStartActivity"
                ).putExtra(
                    com.fadcam.RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE,
                    com.fadcam.RecordingStartActivity.CAMERA_MODE_FRONT
                ),
                com.fadcam.R.drawable.start_front_shortcut,
                ctx.getString(com.fadcam.R.string.shortcut_start_front)
            )
        );
        list.add(
            buildShortcut(
                ID_START_CURRENT,
                new Intent(Intent.ACTION_VIEW).setClassName(
                    ctx,
                    "com.fadcam.RecordingStartActivity"
                ).putExtra(
                    com.fadcam.RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE,
                    com.fadcam.RecordingStartActivity.CAMERA_MODE_CURRENT
                ),
                com.fadcam.R.drawable.start_current_shortcut,
                ctx.getString(com.fadcam.R.string.shortcut_start_current)
            )
        );
        list.add(
            buildShortcut(
                ID_START_DUAL,
                new Intent(Intent.ACTION_VIEW).setClassName(
                    ctx,
                    "com.fadcam.RecordingStartActivity"
                ).putExtra(
                    com.fadcam.RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE,
                    com.fadcam.RecordingStartActivity.CAMERA_MODE_DUAL
                ),
                com.fadcam.R.drawable.start_dual_shortcut,
                ctx.getString(com.fadcam.R.string.shortcut_start_dual)
            )
        );
        // Torch
        list.add(
            buildShortcut(
                ID_TORCH,
                new Intent(Intent.ACTION_VIEW).setClassName(
                    ctx,
                    "com.fadcam.TorchToggleActivity"
                ),
                com.fadcam.R.drawable.flashlight_shortcut,
                ctx.getString(com.fadcam.R.string.torch_shortcut_short_label)
            )
        );
        // Photo
        list.add(
            buildShortcut(
                ID_SHOT,
                new Intent(Intent.ACTION_VIEW).setClassName(
                    ctx,
                    "com.fadcam.PhotoCaptureActivity"
                ),
                com.fadcam.R.drawable.fadshot_shortcut,
                ctx.getString(com.fadcam.R.string.shortcut_take_photo)
            )
        );
        // Photo (front)
        list.add(
            buildShortcut(
                ID_SHOT_FRONT,
                new Intent(Intent.ACTION_VIEW).setClassName(
                    ctx,
                    "com.fadcam.PhotoCaptureActivity"
                ).putExtra(
                    com.fadcam.PhotoCaptureActivity.EXTRA_SHORTCUT_PHOTO_CAMERA_MODE,
                    com.fadcam.PhotoCaptureActivity.PHOTO_CAMERA_MODE_FRONT
                ),
                com.fadcam.R.drawable.fadshot_front_shortcut,
                ctx.getString(com.fadcam.R.string.shortcut_take_photo_front)
            )
        );
        // Screenshot
        list.add(
            buildShortcut(
                ID_SCREENSHOT,
                new Intent(Intent.ACTION_VIEW).setClassName(
                    ctx,
                    "com.fadcam.ScreenShotCaptureActivity"
                ),
                com.fadcam.R.drawable.fadrec_screenshot_shortcut,
                ctx.getString(com.fadcam.R.string.shortcut_take_screenshot)
            )
        );
        // Zoom starts last: the list is cut to the launcher's limit from the end
        for (String id : new String[] {
                ID_START_ZOOM_0_5, ID_START_ZOOM_1, ID_START_ZOOM_2, ID_START_ZOOM_5 }) {
            list.add(buildShortcut(id, intentFor(ctx, id), defaultIconFor(id),
                defaultLabelFor(ctx, id)));
        }
        List<ShortcutInfoCompat> published = list;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N_MR1) {
            android.content.pm.ShortcutManager shortcutManager =
                ctx.getSystemService(android.content.pm.ShortcutManager.class);
            if (shortcutManager != null) {
                int maxCount = shortcutManager.getMaxShortcutCountPerActivity();
                if (maxCount > 0 && list.size() > maxCount) {
                    published = new ArrayList<>(list.subList(0, maxCount));
                }
            }
        }
        ShortcutManagerCompat.setDynamicShortcuts(ctx, published);
    }

    /**
     * Refresh dynamic and pinned shortcuts to ensure launcher reflects current
     * labels and icons (useful after app icon or shortcut icon changes).
     *
     * This method performs a best-effort refresh:
     * - republishes dynamic shortcuts (which most launchers update immediately)
     * - attempts to re-request pinned shortcuts so launchers that allow update
     *   via requesting will refresh their icons/labels
     */
    public void refreshShortcuts() {
        try {
            // Republish dynamic shortcuts (ensures updated icons/labels for long-press menus)
            publishAllDynamic();

            // Attempt to refresh pinned shortcuts as a best-effort.
            // Some launchers keep pinned shortcuts tied to the original icon; re-requesting
            // may prompt some launchers to refresh the icon. This will not remove existing
            // shortcuts but tries to update their metadata where supported.
            if (isPinSupported()) {
                // A pinned shortcut keeps the id it was pinned with ("_pin" or
                // "_pin_custom"), and customising it later doesn't change that, so
                // update both forms. Ids that were never pinned are ignored.
                List<ShortcutInfoCompat> pinned = new ArrayList<>();
                for (String id : ALL_IDS) {
                    Intent intent = intentFor(ctx, id);
                    if (intent == null) continue;
                    int icon = defaultIconFor(id);
                    String label = defaultLabelFor(ctx, id);
                    pinned.add(buildPinned(id, id + "_pin", intent, icon, label));
                    pinned.add(buildPinned(id, id + "_pin_custom", intent, icon, label));
                }

                // Do not call requestPinShortcut here — that triggers the launcher "Add to Home" prompt.
                // Instead, attempt to update already-pinned shortcuts via the native ShortcutManager
                // (no user prompt) so launchers that support updates will refresh the icon/label.
                if (
                    android.os.Build.VERSION.SDK_INT >=
                    android.os.Build.VERSION_CODES.N_MR1
                ) {
                    try {
                        android.content.pm.ShortcutManager sm =
                            (android.content.pm.ShortcutManager) ctx.getSystemService(
                                android.content.Context.SHORTCUT_SERVICE
                            );
                        if (sm != null) {
                            List<android.content.pm.ShortcutInfo> nativeList =
                                new ArrayList<>();
                            for (ShortcutInfoCompat sc : pinned) {
                                try {
                                    android.content.pm.ShortcutInfo nativeInfo =
                                        sc.toShortcutInfo();
                                    if (nativeInfo != null) nativeList.add(
                                        nativeInfo
                                    );
                                } catch (Throwable ignored) {
                                    // Ignore conversion issues for individual items
                                }
                            }
                            if (!nativeList.isEmpty()) {
                                try {
                                    sm.updateShortcuts(nativeList);
                                } catch (Throwable ignored) {
                                    // Best-effort only
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        // Best-effort only
                    }
                }
            }
        } catch (Throwable t) {
            // Non-fatal: shortcut refresh is best-effort only.
        }
    }
}
