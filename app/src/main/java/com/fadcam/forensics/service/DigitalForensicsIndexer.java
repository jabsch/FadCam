package com.fadcam.forensics.service;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.Context;
import android.net.Uri;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.forensics.data.local.ForensicsDatabase;
import com.fadcam.forensics.data.local.dao.IntegrityLinkLogDao;
import com.fadcam.forensics.data.local.dao.MediaAssetDao;
import com.fadcam.forensics.data.local.entity.IntegrityLinkLogEntity;
import com.fadcam.forensics.data.local.entity.MediaAssetEntity;
import com.fadcam.forensics.domain.fingerprint.ForensicsMetadataUtils;
import com.fadcam.ui.VideoItem;

import java.util.List;
import java.util.UUID;

public class DigitalForensicsIndexer {

    private static final String TAG = "DigitalForensicsIndexer";

    private final Context appContext;
    private final SharedPreferencesManager prefs;
    private final MediaAssetDao mediaAssetDao;
    private final IntegrityLinkLogDao linkLogDao;
    /**
     * Files this process could not read a duration or codec from, by URI, with
     * the size at the time. They missed the fast path below, so every Records
     * load opened them again. Indexer runs on one thread.
     */
    private final java.util.Map<String, Long> failedProbeSizes = new java.util.HashMap<>();
    /** last_seen_at is not rewritten more often than this on Records loads. */
    private static final long LAST_SEEN_REFRESH_MS = 10 * 60 * 1000L;

    public DigitalForensicsIndexer(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = SharedPreferencesManager.getInstance(appContext);
        ForensicsDatabase db = ForensicsDatabase.getInstance(appContext);
        this.mediaAssetDao = db.mediaAssetDao();
        this.linkLogDao = db.integrityLinkLogDao();
    }

    public void index(List<VideoItem> videoItems) {
        if (!prefs.isDigitalForensicsEnabled() || videoItems == null || videoItems.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        for (VideoItem item : videoItems) {
            if (item == null || item.uri == null || item.mediaType != VideoItem.MediaType.VIDEO) {
                continue;
            }
            try {
                processVideoItem(item, now);
            } catch (Exception e) {
                FLog.w(TAG, "Skipping item after index failure: " + item.uri, e);
            }
        }
    }

    private void processVideoItem(VideoItem item, long now) {
        String uriString = item.uri.toString();
        MediaAssetEntity existingByUri = mediaAssetDao.findByCurrentUri(uriString);

        // Every Records load passes the whole library here. A row already probed
        // for this exact file only needs its last-seen time; opening each video
        // twice with MediaMetadataRetriever on every load was the bulk of the work.
        Long failedSize = failedProbeSizes.get(uriString);
        boolean probeFailedBefore = failedSize != null && failedSize == item.size;
        if (existingByUri != null
                && existingByUri.sizeBytes == item.size
                && ((existingByUri.durationMs > 0 && existingByUri.codecInfo != null) || probeFailedBefore)) {
            if (now - existingByUri.lastSeenAt < LAST_SEEN_REFRESH_MS
                    && java.util.Objects.equals(existingByUri.displayName, item.displayName)
                    && existingByUri.linkStatus != null) {
                // Seen moments ago: nothing to write
                return;
            }
            mediaAssetDao.updateLinkAndMetadata(
                existingByUri.mediaUid,
                uriString,
                item.displayName,
                item.size,
                existingByUri.durationMs,
                existingByUri.codecInfo,
                now,
                existingByUri.linkStatus == null ? "EXACT" : existingByUri.linkStatus
            );
            return;
        }

        ForensicsMetadataUtils.Basic probed = ForensicsMetadataUtils.extractBasic(appContext, item.uri);
        long durationMs = probed.durationMs;
        String codecInfo = probed.codecInfo;
        if (durationMs > 0 && codecInfo != null) {
            failedProbeSizes.remove(uriString);
        } else {
            failedProbeSizes.put(uriString, item.size);
        }

        if (existingByUri != null) {
            mediaAssetDao.updateLinkAndMetadata(
                existingByUri.mediaUid,
                uriString,
                item.displayName,
                item.size,
                durationMs,
                codecInfo,
                now,
                existingByUri.linkStatus == null ? "EXACT" : existingByUri.linkStatus
            );
            return;
        }

        MediaAssetEntity match = findProbableMatch(item, durationMs);
        if (match != null) {
            mediaAssetDao.updateLinkAndMetadata(
                match.mediaUid,
                uriString,
                item.displayName,
                item.size,
                durationMs,
                codecInfo,
                now,
                "PROBABLE"
            );
            logLink(match.mediaUid, "LINKED_PROBABLE", 0.90f, now);
            return;
        }

        MediaAssetEntity fresh = new MediaAssetEntity();
        fresh.mediaUid = UUID.randomUUID().toString();
        fresh.currentUri = uriString;
        fresh.displayName = item.displayName;
        fresh.categorySubtype = item.category + "/" + item.cameraSubtype;
        fresh.sizeBytes = item.size;
        fresh.durationMs = durationMs;
        fresh.codecInfo = codecInfo;
        fresh.exactFingerprint = null;
        fresh.visualFingerprint = null;
        fresh.firstSeenAt = now;
        fresh.lastSeenAt = now;
        fresh.linkStatus = "NEW";
        mediaAssetDao.insertIgnore(fresh);
    }

    private MediaAssetEntity findProbableMatch(VideoItem item, long durationMs) {
        long sizeTolerance = 1_500_000L;
        long durationToleranceMs = 2_500L;
        long minSize = Math.max(0L, item.size - sizeTolerance);
        long maxSize = item.size + sizeTolerance;
        long minDuration = Math.max(0L, durationMs - durationToleranceMs);
        long maxDuration = durationMs + durationToleranceMs;
        List<MediaAssetEntity> candidates = mediaAssetDao.findProbableCandidates(minSize, maxSize, minDuration, maxDuration);
        MediaAssetEntity best = null;
        float bestScore = 0f;
        for (MediaAssetEntity candidate : candidates) {
            float sizeScore = ratioScore(item.size, candidate.sizeBytes);
            float durationScore = ratioScore(durationMs, candidate.durationMs);
            float nameScore = displayNameScore(item.displayName, candidate.displayName);
            float score = (sizeScore * 0.45f) + (durationScore * 0.45f) + (nameScore * 0.10f);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return bestScore >= 0.92f ? best : null;
    }

    private float ratioScore(long a, long b) {
        if (a <= 0L || b <= 0L) return 0f;
        long min = Math.min(a, b);
        long max = Math.max(a, b);
        return min / (float) max;
    }

    private float displayNameScore(String current, String candidate) {
        if (current == null || candidate == null) return 0f;
        return current.equalsIgnoreCase(candidate) ? 1f : 0f;
    }

    private void logLink(String mediaUid, String action, float score, long now) {
        IntegrityLinkLogEntity log = new IntegrityLinkLogEntity();
        log.logUid = UUID.randomUUID().toString();
        log.mediaUid = mediaUid;
        log.action = action;
        log.score = score;
        log.timestamp = now;
        linkLogDao.upsert(log);
    }
}
