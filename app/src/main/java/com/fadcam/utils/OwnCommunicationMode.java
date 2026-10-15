package com.fadcam.utils;

/**
 * Whether JaCam itself put the phone in communication mode, to capture a Bluetooth
 * headset mic over SCO. Hardware buttons step aside in any non-normal audio mode
 * (a call), so without this they ignored every press during such a recording,
 * including the one meant to stop it.
 */
public final class OwnCommunicationMode {

    private static volatile boolean held;

    private OwnCommunicationMode() {}

    public static void set(boolean ours) {
        held = ours;
    }

    public static boolean isHeld() {
        return held;
    }
}
