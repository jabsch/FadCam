package com.fadcam.utils;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Whether JaCam itself put the phone in communication mode, to capture a Bluetooth
 * headset mic over SCO. Hardware buttons step aside in any non-normal audio mode
 * (a call), so without this they ignored every press during such a recording,
 * including the one meant to stop it. A video and a voice recording can each hold
 * it, so it is a count: each holder releases only its own hold.
 */
public final class OwnCommunicationMode {

    private static final AtomicInteger holders = new AtomicInteger();

    private OwnCommunicationMode() {}

    public static void acquire() {
        holders.incrementAndGet();
    }

    public static void release() {
        holders.updateAndGet(n -> Math.max(0, n - 1));
    }

    public static boolean isHeld() {
        return holders.get() > 0;
    }
}
