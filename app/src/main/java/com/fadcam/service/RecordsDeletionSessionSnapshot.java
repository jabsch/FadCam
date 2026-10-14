package com.fadcam.service;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class RecordsDeletionSessionSnapshot {

    private static final Gson GSON = new Gson();

    public enum State {
        IDLE,
        QUEUED,
        RUNNING,
        CANCELLED,
        COMPLETED_SUCCESS,
        COMPLETED_PARTIAL,
        COMPLETED_FAILED
    }

    public enum OperationKind {
        DELETE,
        SAVE_COPY_TO_GALLERY,
        SAVE_MOVE_TO_GALLERY,
        SAVE_EXPORT_TO_CUSTOM_TREE
    }

    @NonNull public String sessionId = UUID.randomUUID().toString();
    @NonNull public State state = State.IDLE;
    @NonNull public OperationKind operationKind = OperationKind.DELETE;
    public int totalItemCount;
    public int completedItemCount;
    public int failedItemCount;
    public long totalBytes;
    public long processedBytes;
    @Nullable public String currentItemName;
    public int currentItemIndex;
    /** Item the worker took off the queue and has not finished; requeued if the process died. */
    @Nullable public RecordsDeletionRequestItem inFlightItem;
    /** Bytes already copied for the in-flight item. */
    public long currentItemBytesDone;
    public long startedAtMs;
    public long lastUpdatedAtMs;
    public long finishedAtMs;
    public boolean completionAcknowledged;
    @NonNull public List<RecordsDeletionRequestItem> pendingItems = new ArrayList<>();
    @NonNull public List<String> completedUriStrings = new ArrayList<>();
    @NonNull public List<String> failedUriStrings = new ArrayList<>();
    @NonNull public List<String> errorSummaries = new ArrayList<>();
    @Nullable public String customTreeUri;  // For SAVE_EXPORT_TO_CUSTOM_TREE operations

    public boolean isActive() {
        return state == State.QUEUED || state == State.RUNNING;
    }

    public boolean isFinished() {
        return state == State.CANCELLED
                || state == State.COMPLETED_SUCCESS
                || state == State.COMPLETED_PARTIAL
                || state == State.COMPLETED_FAILED;
    }

    public int getProgressPercent() {
        int itemPercent = 0;
        if (totalItemCount > 0) {
            itemPercent = Math.max(0, Math.min(100, ((completedItemCount + failedItemCount) * 100) / totalItemCount));
        }
        if (totalBytes > 0L) {
            int bytePercent = (int) Math.max(0L, Math.min(100L, (getBytesDone() * 100L) / totalBytes));
            return Math.max(bytePercent, itemPercent);
        }
        return itemPercent;
    }

    /** Bytes of finished items plus progress on the in-flight one. */
    public long getBytesDone() {
        return Math.max(0L, processedBytes) + Math.max(0L, currentItemBytesDone);
    }

    @NonNull
    public static RecordsDeletionSessionSnapshot create(@NonNull List<RecordsDeletionRequestItem> items) {
        return create(items, OperationKind.DELETE);
    }

    @NonNull
    public static RecordsDeletionSessionSnapshot create(
            @NonNull List<RecordsDeletionRequestItem> items,
            @NonNull OperationKind operationKind
    ) {
        RecordsDeletionSessionSnapshot snapshot = new RecordsDeletionSessionSnapshot();
        snapshot.pendingItems = new ArrayList<>(items);
        snapshot.operationKind = operationKind;
        snapshot.totalItemCount = items.size();
        snapshot.totalBytes = sumBytes(items);
        long now = System.currentTimeMillis();
        snapshot.startedAtMs = now;
        snapshot.lastUpdatedAtMs = now;
        snapshot.state = items.isEmpty() ? State.COMPLETED_SUCCESS : State.QUEUED;
        return snapshot;
    }

    public void appendItems(@NonNull List<RecordsDeletionRequestItem> items) {
        // Skip items already queued, in flight or done, so a repeated request can't
        // count the same file twice.
        Set<String> known = new HashSet<>(completedUriStrings);
        for (RecordsDeletionRequestItem item : pendingItems) {
            known.add(item.uriString);
        }
        if (inFlightItem != null) {
            known.add(inFlightItem.uriString);
        }
        List<RecordsDeletionRequestItem> added = new ArrayList<>(items.size());
        for (RecordsDeletionRequestItem item : items) {
            if (known.add(item.uriString)) {
                added.add(item);
            }
        }
        if (added.isEmpty()) {
            return;
        }
        pendingItems.addAll(added);
        totalItemCount += added.size();
        totalBytes += sumBytes(added);
        lastUpdatedAtMs = System.currentTimeMillis();
        if (!isFinished()) {
            state = State.QUEUED;
        }
    }

    @NonNull
    public RecordsDeletionSessionSnapshot copy() {
        RecordsDeletionSessionSnapshot copy = fromJson(toJson(this));
        return copy == null ? new RecordsDeletionSessionSnapshot() : copy;
    }

    @NonNull
    public static String toJson(@NonNull RecordsDeletionSessionSnapshot snapshot) {
        return GSON.toJson(snapshot);
    }

    @Nullable
    public static RecordsDeletionSessionSnapshot fromJson(@Nullable String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            RecordsDeletionSessionSnapshot snapshot = GSON.fromJson(json, RecordsDeletionSessionSnapshot.class);
            // No readable state means the stored session is unusable: treat it as none.
            return snapshot == null || snapshot.state == null ? null : snapshot.withDefaults();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Gson can leave fields null (for example when a minified build created the object
     * without running field initializers), so restore the non-null defaults.
     */
    @NonNull
    private RecordsDeletionSessionSnapshot withDefaults() {
        if (sessionId == null) sessionId = UUID.randomUUID().toString();
        if (operationKind == null) operationKind = OperationKind.DELETE;
        if (pendingItems == null) pendingItems = new ArrayList<>();
        if (completedUriStrings == null) completedUriStrings = new ArrayList<>();
        if (failedUriStrings == null) failedUriStrings = new ArrayList<>();
        if (errorSummaries == null) errorSummaries = new ArrayList<>();
        pendingItems.removeIf(item -> item == null || item.uriString == null);
        if (inFlightItem != null && inFlightItem.uriString == null) inFlightItem = null;
        return this;
    }

    private static long sumBytes(@NonNull List<RecordsDeletionRequestItem> items) {
        long total = 0L;
        for (RecordsDeletionRequestItem item : items) {
            total += Math.max(0L, item.sizeBytes);
        }
        return total;
    }
}
