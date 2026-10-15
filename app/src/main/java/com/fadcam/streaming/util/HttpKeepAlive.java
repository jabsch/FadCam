package com.fadcam.streaming.util;

import java.io.InputStream;
import java.net.HttpURLConnection;

/**
 * Lets an {@link HttpURLConnection}'s socket go back to the keep-alive pool.
 *
 * <p>On Android, {@code disconnect()} on a response whose body was never read
 * closes the socket, so the next request to the relay paid a fresh TCP and TLS
 * handshake. Status pushes, command polls and segment uploads repeat every few
 * seconds, which kept the radio and CPU busy for nothing. Reading the body to
 * the end first releases the connection, after which {@code disconnect()} is a
 * no-op for it.
 */
public final class HttpKeepAlive {

    /** Bodies larger than this are not worth reading just to keep the socket. */
    private static final int MAX_DRAIN_BYTES = 64 * 1024;

    private HttpKeepAlive() {
    }

    /**
     * Reads and closes what is left of the response body. Call only after
     * {@code getResponseCode()} returned, so this never starts a new request.
     */
    public static void drain(HttpURLConnection conn, int responseCode) {
        if (conn == null || responseCode <= 0) {
            return;
        }
        InputStream in = null;
        try {
            in = responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) {
                return;
            }
            byte[] buf = new byte[2048];
            int total = 0;
            int n;
            while (total <= MAX_DRAIN_BYTES && (n = in.read(buf)) != -1) {
                total += n;
            }
        } catch (Exception ignored) {
            // Best effort: the socket is just not reused.
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
