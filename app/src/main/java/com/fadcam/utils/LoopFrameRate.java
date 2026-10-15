package com.fadcam.utils;

import android.os.Build;
import android.view.View;
import androidx.annotation.Nullable;

/**
 * Frame rate hints for slow decorative loops (breathing, floating, twinkle). Each
 * animated view votes for the panel's full rate (120 Hz on a Pixel Pro) unless it
 * asks for less; a low vote lets the panel drop while only these loops run.
 * Android 15 and newer; older versions ignore it.
 */
public final class LoopFrameRate {

    private LoopFrameRate() {}

    /** Asks for a low frame rate while {@code slow}, the default otherwise. */
    public static void request(@Nullable View view, boolean slow) {
        if (view == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return;
        }
        view.setRequestedFrameRate(slow ? View.REQUESTED_FRAME_RATE_CATEGORY_LOW
                : View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT);
    }
}
