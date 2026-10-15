package com.fadcam.service;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class RecordsDeletionSessionStore {

    private static final String PREFS_NAME = "records_deletion_session_store";
    private static final String KEY_SNAPSHOT_JSON = "snapshot_json";

    @NonNull
    private final SharedPreferences prefs;
    /** What this store last wrote, so progress updates don't reparse the stored JSON. */
    @Nullable
    private RecordsDeletionSessionSnapshot lastWritten;

    public RecordsDeletionSessionStore(@NonNull Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Nullable
    public synchronized RecordsDeletionSessionSnapshot read() {
        return RecordsDeletionSessionSnapshot.fromJson(prefs.getString(KEY_SNAPSHOT_JSON, null));
    }

    @NonNull
    public synchronized RecordsDeletionSessionSnapshot readOrIdle() {
        RecordsDeletionSessionSnapshot snapshot = read();
        return snapshot == null ? new RecordsDeletionSessionSnapshot() : snapshot;
    }

    public synchronized void write(@NonNull RecordsDeletionSessionSnapshot snapshot) {
        prefs.edit().putString(KEY_SNAPSHOT_JSON, RecordsDeletionSessionSnapshot.toJson(snapshot)).apply();
        lastWritten = snapshot;
    }

    /**
     * The snapshot this store instance last wrote, or null when it hasn't written one.
     * Only for the service, which owns the single instance that writes the session.
     */
    @Nullable
    public synchronized RecordsDeletionSessionSnapshot lastWritten() {
        return lastWritten;
    }

    public synchronized void clear() {
        prefs.edit().remove(KEY_SNAPSHOT_JSON).apply();
        lastWritten = null;
    }
}
