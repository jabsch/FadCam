package com.fadcam.service;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import com.fadcam.Constants;
import com.fadcam.Utils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages file operation queue and execution
 */
public class FileOperationManager {
    
    private static final String TAG = "FileOperationManager";
    private static final int BUFFER_SIZE = 8192;
    private static final long PROGRESS_INTERVAL_MS = 500L;
    
    public interface OperationListener {
        void onTaskStarted(FileOperationTask task);
        void onTaskProgress(FileOperationTask task);
        void onTaskCompleted(FileOperationTask task);
        void onTaskFailed(FileOperationTask task, String error);
        void onAllTasksCompleted();
    }
    
    private final Context context;
    private final ExecutorService executorService;
    private final ConcurrentLinkedQueue<FileOperationTask> taskQueue;
    private final AtomicBoolean isProcessing;
    private final Handler mainHandler;
    private OperationListener listener;
    
    public FileOperationManager(Context context) {
        this.context = context;
        this.executorService = Executors.newSingleThreadExecutor();
        this.taskQueue = new ConcurrentLinkedQueue<>();
        this.isProcessing = new AtomicBoolean(false);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    public void setOperationListener(OperationListener listener) {
        this.listener = listener;
    }
    
    public void enqueueTask(FileOperationTask task) {
        taskQueue.offer(task);
        FLog.d(TAG, "Task enqueued: " + task);
        processNextTask();
    }
    
    public int getQueueSize() {
        return taskQueue.size();
    }
    
    public void shutdown() {
        executorService.shutdown();
    }
    
    private void processNextTask() {
        if (isProcessing.get()) {
            return; // Already processing
        }
        
        FileOperationTask task = taskQueue.poll();
        if (task == null) {
            notifyListener(listener -> listener.onAllTasksCompleted());
            return;
        }
        
        isProcessing.set(true);
        executorService.submit(() -> executeTask(task));
    }
    
    private void executeTask(FileOperationTask task) {
        FLog.d(TAG, "Starting task: " + task);
        task.status = FileOperationTask.TaskStatus.IN_PROGRESS;
        notifyListener(listener -> listener.onTaskStarted(task));
        
        try {
            switch (task.type) {
                case COPY_TO_GALLERY:
                    executeCopyToGallery(task, false);
                    break;
                case MOVE_TO_GALLERY:
                    executeCopyToGallery(task, true);
                    break;
                case DELETE_FILE:
                    executeDelete(task);
                    break;
                case RESTORE_FILE:
                    executeRestore(task);
                    break;
            }
            
            task.status = FileOperationTask.TaskStatus.COMPLETED;
            task.endTime = System.currentTimeMillis();
            FLog.d(TAG, "Task completed: " + task);
            notifyListener(listener -> listener.onTaskCompleted(task));
            
        } catch (Exception e) {
            FLog.e(TAG, "Task failed: " + task, e);
            task.status = FileOperationTask.TaskStatus.FAILED;
            task.errorMessage = e.getMessage();
            task.endTime = System.currentTimeMillis();
            notifyListener(listener -> listener.onTaskFailed(task, e.getMessage()));
        } finally {
            isProcessing.set(false);
            processNextTask(); // Process next task in queue
        }
    }
    
    private void executeCopyToGallery(FileOperationTask task, boolean moveFile) throws Exception {
        // Create destination directory
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File fadCamDir = new File(downloadsDir, Constants.RECORDING_DIRECTORY);
        if (!fadCamDir.exists() && !fadCamDir.mkdirs()) {
            throw new Exception("Cannot create directory: " + fadCamDir.getAbsolutePath());
        }
        
        // Handle filename conflicts
        File destFile = new File(fadCamDir, task.fileName);
        int counter = 0;
        while (destFile.exists()) {
            counter++;
            String nameWithoutExt = task.fileName;
            String extension = "";
            int dotIndex = task.fileName.lastIndexOf('.');
            if (dotIndex > 0 && dotIndex < task.fileName.length() - 1) {
                nameWithoutExt = task.fileName.substring(0, dotIndex);
                extension = task.fileName.substring(dotIndex);
            }
            destFile = new File(fadCamDir, nameWithoutExt + " (" + counter + ")" + extension);
        }
        
        // Get file size for progress tracking
        try {
            task.totalBytes = getFileSize(task.sourceUri);
        } catch (Exception e) {
            FLog.w(TAG, "Could not get file size for progress tracking", e);
        }
        
        // Copy file with progress tracking
        try (InputStream in = context.getContentResolver().openInputStream(task.sourceUri);
             OutputStream out = new FileOutputStream(destFile)) {
            
            if (in == null) {
                throw new Exception("Failed to open input stream for " + task.sourceUri);
            }
            
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            long lastProgressMs = 0L;
            boolean finalSent = false;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                task.progress += bytesRead;
                
                // Each update rebuilds and posts the notification; a few per second is
                // enough (it fired about every 400 KB, dozens of times a second)
                long now = android.os.SystemClock.elapsedRealtime();
                boolean reachedEnd = task.totalBytes > 0 && task.progress >= task.totalBytes;
                if (now - lastProgressMs >= PROGRESS_INTERVAL_MS || (reachedEnd && !finalSent)) {
                    lastProgressMs = now;
                    if (reachedEnd) finalSent = true;
                    notifyListener(listener -> listener.onTaskProgress(task));
                }
            }
            out.flush();
        }
        
        // Scan file for media store
        Utils.scanFileWithMediaStore(context, destFile.getAbsolutePath());
        FLog.i(TAG, "File copied to: " + destFile.getAbsolutePath());
        
        // Handle move operation (delete original)
        if (moveFile) {
            boolean deleted = false;
            if ("file".equals(task.sourceUri.getScheme())) {
                File originalFile = new File(task.sourceUri.getPath());
                if (originalFile.exists()) {
                    deleted = originalFile.delete();
                }
            } else {
                deleted = context.getContentResolver().delete(task.sourceUri, null, null) > 0;
            }
            
            if (!deleted) {
                FLog.w(TAG, "Could not delete original file: " + task.sourceUri);
                throw new Exception("File copied but original could not be deleted");
            }
            
            FLog.i(TAG, "Original file deleted: " + task.sourceUri);
        }
    }
    
    private void executeDelete(FileOperationTask task) throws Exception {
        boolean deleted = false;
        
        if ("file".equals(task.sourceUri.getScheme())) {
            File file = new File(task.sourceUri.getPath());
            if (file.exists()) {
                deleted = file.delete();
            }
        } else {
            deleted = context.getContentResolver().delete(task.sourceUri, null, null) > 0;
        }
        
        if (!deleted) {
            throw new Exception("Could not delete file: " + task.sourceUri);
        }
        
        FLog.i(TAG, "File deleted: " + task.sourceUri);
    }
    
    private long getFileSize(Uri uri) throws Exception {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            return new File(uri.getPath()).length();
        }
        // available() only reports what can be read without blocking, often a few KB,
        // which pinned progress at 100% and posted an update on every buffer
        try (android.content.res.AssetFileDescriptor afd =
                     context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            long length = afd != null ? afd.getLength() : -1;
            if (length > 0) return length;
        }
        // Some providers don't report a descriptor length; the size column usually has it
        try (android.database.Cursor c = context.getContentResolver().query(uri,
                new String[] {android.provider.OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return Math.max(0L, c.getLong(0));
            }
        }
        return 0;
    }
    
    /**
     * Execute restore operation (placeholder - actual implementation depends on specific requirements)
     */
    private void executeRestore(FileOperationTask task) throws Exception {
        FLog.d(TAG, "executeRestore: Starting restore for " + task.fileName);
        
        // Since restore operations are currently handled by TrashFragment directly,
        // this method serves as a placeholder for service notification purposes.
        // The actual restore logic remains in TrashManager.restoreItemsFromTrash()
        
        // Simulate progress for notification
        task.progress = 50;
        task.totalBytes = 100;
        notifyListener(listener -> listener.onTaskProgress(task));
        
        // Small delay to show progress
        Thread.sleep(500);
        
        task.progress = 100;
        notifyListener(listener -> listener.onTaskProgress(task));
        
        FLog.d(TAG, "executeRestore: Completed restore placeholder for " + task.fileName);
    }
    
    private void notifyListener(ListenerCallback callback) {
        if (listener != null) {
            mainHandler.post(() -> {
                try {
                    callback.call(listener);
                } catch (Exception e) {
                    FLog.e(TAG, "Error in listener callback", e);
                }
            });
        }
    }
    
    private interface ListenerCallback {
        void call(OperationListener listener);
    }
}