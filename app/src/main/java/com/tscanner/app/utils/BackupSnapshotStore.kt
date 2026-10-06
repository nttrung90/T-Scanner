package com.tscanner.app.utils

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.work.WorkManager
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Manages the lifecycle of immutable document snapshots created for Google Drive cloud backup.
 *
 * Guarantees:
 * 1. Snapshots are durable and immutable prior to work enqueue.
 * 2. Snapshots bound to active (pending, running, retry) WorkManager tasks are NEVER deleted,
 *    even if they exceed 24 hours.
 * 3. Terminal, cancelled, and replaced work snapshots are safely cleaned up.
 * 4. WorkManager query errors NEVER cause snapshot deletion ("fail-safe retention").
 */
object BackupSnapshotStore {

    private const val TAG = "BackupSnapshotStore"
    const val SNAPSHOT_DIR_NAME = "backup_snapshots"
    const val TAG_PREFIX = "snap_file_"
    const val WORK_QUERY_TIMEOUT_SECONDS = 3L

    enum class WorkSnapshotStatus {
        ACTIVE,      // Work is ENQUEUED, RUNNING, or BLOCKED (in retry backoff)
        TERMINAL,    // Work is SUCCEEDED, FAILED, or CANCELLED (safe to clean up)
        UNKNOWN,     // No work record found in WorkManager
        QUERY_ERROR  // Lookup error/exception (must retain snapshot)
    }

    @VisibleForTesting
    var baseDirOverride: File? = null

    @VisibleForTesting
    var workStateChecker: ((Context, File) -> WorkSnapshotStatus)? = null

    @VisibleForTesting
    var workManagerProvider: ((Context) -> WorkManager)? = null

    @VisibleForTesting
    var orphanCleanupInterceptor: ((Context, Long) -> Int)? = null

    @VisibleForTesting
    var snapshotCopyHook: (() -> Unit)? = null

    @VisibleForTesting
    var workQueryFutureProvider: ((String) -> com.google.common.util.concurrent.ListenableFuture<List<androidx.work.WorkInfo>>)? = null

    @VisibleForTesting
    var persistentRootOverride: File? = null

    /**
     * Returns the persistent app-private snapshot directory (no-backup storage).
     * Protected against OS cache eviction.
     */
    fun getSnapshotDir(context: Context): File {
        val root = persistentRootOverride ?: try {
            context.noBackupFilesDir ?: context.filesDir
        } catch (e: Throwable) {
            try { context.filesDir } catch (t: Throwable) { context.cacheDir }
        }
        val dir = baseDirOverride ?: File(root, SNAPSHOT_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Returns the legacy cache snapshot directory (used prior to S08 migration).
     */
    fun getLegacyCacheSnapshotDir(context: Context): File {
        return try {
            File(context.cacheDir, SNAPSHOT_DIR_NAME)
        } catch (e: Throwable) {
            File(getSnapshotDir(context), "legacy_cache")
        }
    }

    fun getSnapshotTag(snapshotFile: File): String {
        return "$TAG_PREFIX${snapshotFile.name}"
    }

    /**
     * Creates an immutable snapshot of [sourcePdf] in the backup snapshots directory.
     * Uses atomic write (flush, fsync to temp file, then rename) to prevent partial snapshot creation.
     * Returns null if [sourcePdf] is invalid or if the copy fails.
     */
    fun createSnapshot(
        context: Context,
        docId: String,
        revision: Long,
        sourcePdf: File
    ): File? {
        if (!sourcePdf.exists() || sourcePdf.length() == 0L) {
            Log.w(TAG, "Source PDF does not exist or is empty for docId=$docId: ${sourcePdf.absolutePath}")
            return null
        }

        val dir = getSnapshotDir(context)
        val uniqueTag = UUID.randomUUID().toString().take(8)
        val destFile = File(dir, "snapshot_${docId}_rev${revision}_$uniqueTag.pdf")
        val tempFile = File(dir, "${destFile.name}.tmp")

        return try {
            java.io.FileInputStream(sourcePdf).use { input ->
                java.io.FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                    try {
                        output.fd.sync()
                    } catch (e: Exception) {
                        // Some virtual/in-memory file systems do not support sync
                    }
                }
            }
            snapshotCopyHook?.invoke()
            if (tempFile.exists() && tempFile.length() == sourcePdf.length()) {
                val renamed = tempFile.renameTo(destFile)
                if (renamed && destFile.exists() && destFile.length() > 0L) {
                    Log.d(TAG, "Created immutable backup snapshot: ${destFile.name} (${destFile.length()} bytes)")
                    destFile
                } else {
                    // Fallback in case renameTo fails across file boundaries
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                    if (destFile.exists() && destFile.length() > 0L) destFile else null
                }
            } else {
                tempFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create immutable backup snapshot for docId=$docId", e)
            if (tempFile.exists()) {
                tempFile.delete()
            }
            null
        }
    }

    /**
     * Deletes a snapshot file safely.
     */
    fun deleteSnapshot(snapshotFile: File?): Boolean {
        if (snapshotFile == null || !snapshotFile.exists()) return false
        return try {
            val deleted = snapshotFile.delete()
            if (deleted) {
                Log.d(TAG, "Deleted snapshot file: ${snapshotFile.name}")
            }
            deleted
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete snapshot file: ${snapshotFile.absolutePath}", e)
            false
        }
    }

    /**
     * Inspects WorkManager to check the execution status of the work bound to [snapshotFile].
     */
    fun checkWorkStatus(context: Context, snapshotFile: File): WorkSnapshotStatus {
        val customChecker = workStateChecker
        if (customChecker != null) {
            return customChecker(context, snapshotFile)
        }

        return try {
            val tag = getSnapshotTag(snapshotFile)
            val futureProvider = workQueryFutureProvider
            val workInfosFuture = if (futureProvider != null) {
                futureProvider(tag)
            } else {
                val wm = try {
                    workManagerProvider?.invoke(context) ?: WorkManager.getInstance(context)
                } catch (e: Throwable) {
                    Log.w(TAG, "WorkManager is not initialized or accessible", e)
                    return WorkSnapshotStatus.QUERY_ERROR
                }
                wm.getWorkInfosByTag(tag)
            }
            val workInfos = try {
                workInfosFuture.get(WORK_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (te: TimeoutException) {
                Log.w(TAG, "Timeout querying WorkManager for snapshot: ${snapshotFile.name}")
                workInfosFuture.cancel(true)
                return WorkSnapshotStatus.QUERY_ERROR
            }
            if (workInfos.isNullOrEmpty()) {
                WorkSnapshotStatus.UNKNOWN
            } else {
                val hasActive = workInfos.any { !it.state.isFinished }
                if (hasActive) {
                    WorkSnapshotStatus.ACTIVE
                } else {
                    WorkSnapshotStatus.TERMINAL
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error querying WorkManager for snapshot: ${snapshotFile.name}", e)
            WorkSnapshotStatus.QUERY_ERROR
        }
    }

    /**
     * Cleans up orphaned or finished snapshot files:
     * - ACTIVE work snapshots are NEVER deleted (even if > 24h).
     * - QUERY_ERROR snapshots are PRESERVED (fail-safe).
     * - TERMINAL work snapshots are deleted immediately.
     * - UNKNOWN snapshots (no WorkManager record) are deleted only if older than [maxAgeMs] (default 24h).
     * - Lingering temporary files (.tmp) older than 15 minutes are cleaned up.
     *
     * @return Number of cleaned up snapshot files.
     */
    fun cleanOrphanSnapshots(context: Context, maxAgeMs: Long = 86400000L): Int {
        val interceptor = orphanCleanupInterceptor
        if (interceptor != null) {
            return interceptor(context, maxAgeMs)
        }

        val persistentDir = getSnapshotDir(context)
        var cleanedCount = cleanDirectorySnapshots(context, persistentDir, maxAgeMs)

        // S08: If legacy cache directory exists and is distinct from persistentDir, drain it safely
        try {
            val legacyDir = getLegacyCacheSnapshotDir(context)
            if (legacyDir.exists() && legacyDir.canonicalPath != persistentDir.canonicalPath) {
                cleanedCount += cleanDirectorySnapshots(context, legacyDir, maxAgeMs)
                val remaining = legacyDir.listFiles()
                if (remaining.isNullOrEmpty()) {
                    legacyDir.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning legacy cache snapshot directory", e)
        }

        return cleanedCount
    }

    private fun cleanDirectorySnapshots(context: Context, dir: File, maxAgeMs: Long): Int {
        val files = dir.listFiles() ?: return 0
        val now = System.currentTimeMillis()
        var cleaned = 0

        for (file in files) {
            if (file.name.endsWith(".tmp")) {
                // Delete lingering temp files older than 15 minutes
                if (now - file.lastModified() > 900000L) {
                    if (file.delete()) cleaned++
                }
                continue
            }

            if (!file.name.endsWith(".pdf")) continue

            val age = now - file.lastModified()
            val status = checkWorkStatus(context, file)

            when (status) {
                WorkSnapshotStatus.ACTIVE -> {
                    // Work is pending, running, or in retry backoff.
                    // Strictly retain regardless of age!
                    Log.d(TAG, "Retaining active snapshot ${file.name} (age: ${age}ms)")
                }
                WorkSnapshotStatus.QUERY_ERROR -> {
                    // Fail-safe: do not delete on lookup failure!
                    Log.w(TAG, "Retaining snapshot ${file.name} due to lookup error.")
                }
                WorkSnapshotStatus.TERMINAL -> {
                    // Task is confirmed finished (succeeded, failed, cancelled, replaced)
                    Log.d(TAG, "Cleaning up snapshot ${file.name} for finished work.")
                    if (file.delete()) cleaned++
                }
                WorkSnapshotStatus.UNKNOWN -> {
                    // No task found: only delete if older than 24h to avoid race condition during enqueue
                    if (age >= maxAgeMs) {
                        Log.i(TAG, "Cleaning up orphan snapshot ${file.name} older than 24h without WorkManager record.")
                        if (file.delete()) cleaned++
                    }
                }
            }
        }
        return cleaned
    }

    @VisibleForTesting
    fun resetForTesting() {
        baseDirOverride = null
        persistentRootOverride = null
        workStateChecker = null
        workManagerProvider = null
        orphanCleanupInterceptor = null
        snapshotCopyHook = null
        workQueryFutureProvider = null
    }
}
