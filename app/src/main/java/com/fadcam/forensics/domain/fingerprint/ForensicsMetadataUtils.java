package com.fadcam.forensics.domain.fingerprint;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

public final class ForensicsMetadataUtils {

    private ForensicsMetadataUtils() {
    }

    public static long extractDurationMs(Context context, Uri uri) {
        String durationRaw = extractMetadata(context, uri, MediaMetadataRetriever.METADATA_KEY_DURATION);
        if (durationRaw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(durationRaw);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    public static String extractCodecInfo(Context context, Uri uri) {
        return extractMetadata(context, uri, MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
    }

    /** Duration and MIME type. */
    public static final class Basic {
        public final long durationMs;
        public final String codecInfo;

        Basic(long durationMs, String codecInfo) {
            this.durationMs = durationMs;
            this.codecInfo = codecInfo;
        }
    }

    /**
     * Duration and MIME type from one retriever. Asking for each separately
     * opened and parsed the file twice.
     */
    public static Basic extractBasic(Context context, Uri uri) {
        if (context == null || uri == null) {
            return new Basic(0L, null);
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            long durationMs = 0L;
            String durationRaw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationRaw != null) {
                try {
                    durationMs = Long.parseLong(durationRaw);
                } catch (NumberFormatException ignored) {
                }
            }
            return new Basic(durationMs, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE));
        } catch (Exception ignored) {
            return new Basic(0L, null);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static String extractMetadata(Context context, Uri uri, int key) {
        if (context == null || uri == null) {
            return null;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            return retriever.extractMetadata(key);
        } catch (Exception ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }
}
