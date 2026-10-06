package com.tscanner.app.utils

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.model.SyncStatus
import com.tscanner.app.data.repository.DocumentRepo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed class SyncCatalogResult {
    data class Success(val addedCount: Int, val totalDriveFiles: Int) : SyncCatalogResult()
    data class Partial(val addedCount: Int, val partialDriveFiles: Int, val error: String) : SyncCatalogResult()
    data class AuthRequired(val error: String) : SyncCatalogResult()
    data class Failure(val error: String) : SyncCatalogResult()
    data class Skipped(val reason: String) : SyncCatalogResult() {
        companion object {
            const val REASON_NOT_LOGGED_IN = "Chưa đăng nhập"
            const val REASON_NOT_VIP = "Chỉ dành cho tài khoản VIP"
        }
    }
}

object CloudBackupManager {

    private const val TAG = "CloudBackupManager"

    /**
     * Enqueues a single document for background Google Drive backup.
     *
     * Invariants (S07):
     * 1. Preserves owner/session tokens across async snapshot and enqueue.
     * 2. Rechecks current session and active user after snapshot copy; deletes draft
     *    snapshot and aborts enqueue if user switched or logged out (prevents Doc A leaking into User B's Drive).
     * 3. [skipCleanup]: when true (e.g. called from [enqueueBatchBackup]), avoids redundant orphan cleanup.
     */
    fun enqueueBackup(
        context: Context,
        docItem: DocumentItem,
        skipCleanup: Boolean = false,
        expectedUserId: String? = null,
        expectedSessionGen: Long? = null
    ) {
        val pdfPath = docItem.pdfPath
        if (pdfPath == null || !File(pdfPath).exists()) {
            Log.w(TAG, "Cannot backup document without valid PDF file: ${docItem.id}")
            return
        }

        // Only VIP accounts have auto cloud backup enabled
        if (!AppAuthManager.isUserVip()) {
            Log.d(TAG, "User is not VIP. Skipping cloud backup for ${docItem.title}")
            DocumentRepo.getInstance(context).updateSyncStatus(docItem.id, SyncStatus.LOCAL_ONLY)
            return
        }

        val currentUser = AppAuthManager.getCurrentUser()
        val currentSessionGen = AppAuthManager.getSessionGeneration()
        if (currentUser == null) {
            Log.w(TAG, "No logged in user found for backup.")
            return
        }

        val targetExpectedUser = expectedUserId ?: currentUser.id
        val targetExpectedSession = expectedSessionGen ?: currentSessionGen

        if (currentUser.id != targetExpectedUser) {
            Log.w(TAG, "User mismatch before backup for docId=${docItem.id}: current user ${currentUser.id} != expected $targetExpectedUser. Aborting backup.")
            return
        }

        if (currentSessionGen != targetExpectedSession) {
            Log.w(TAG, "Session mismatch before backup for docId=${docItem.id}: current session $currentSessionGen != expected $targetExpectedSession. Aborting backup.")
            return
        }

        if (docItem.isConflict) {
            Log.w(TAG, "Document ${docItem.id} has conflict status. Skipping automatic cloud backup.")
            return
        }

        if (docItem.ownerId != null && docItem.ownerId != currentUser.id) {
            Log.w(TAG, "Owner mismatch for docId=${docItem.id}: doc belongs to ${docItem.ownerId}, current user is ${currentUser.id}. Aborting backup.")
            return
        }

        val repo = DocumentRepo.getInstance(context)
        val ownerId = docItem.ownerId ?: run {
            repo.setDocumentOwner(docItem.id, currentUser.id)
            currentUser.id
        }

        val sourcePdf = File(pdfPath)
        if (!sourcePdf.exists() || sourcePdf.length() == 0L) {
            Log.w(TAG, "Source PDF does not exist or is empty for docId=${docItem.id}: $pdfPath. Aborting backup.")
            return
        }

        // Trigger safe orphan cleanup before creating new snapshot (unless skipped by batch enqueue)
        if (!skipCleanup) {
            BackupSnapshotStore.cleanOrphanSnapshots(context)
        }

        // Create immutable durable snapshot. Mandatory: no fallback to live file.
        val snapshotFile = BackupSnapshotStore.createSnapshot(
            context = context,
            docId = docItem.id,
            revision = docItem.contentRevision,
            sourcePdf = sourcePdf
        )
        if (snapshotFile == null || !snapshotFile.exists() || snapshotFile.length() == 0L) {
            Log.e(TAG, "Failed to create durable immutable snapshot for docId=${docItem.id}. Live file fallback is forbidden. Aborting backup.")
            return
        }

        // S07: Crucial recheck after snapshot copy!
        val postCopyUser = AppAuthManager.getCurrentUser()
        val postCopySessionGen = AppAuthManager.getSessionGeneration()
        if (postCopyUser?.id != targetExpectedUser || postCopySessionGen != targetExpectedSession || postCopyUser?.isVipActive != true) {
            Log.w(TAG, "Session changed or user logged out during snapshot creation for docId=${docItem.id}. Deleting snapshot and aborting work enqueue.")
            BackupSnapshotStore.deleteSnapshot(snapshotFile)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = Data.Builder()
            .putString(GoogleDriveBackupWorker.KEY_DOC_ID, docItem.id)
            .putString(GoogleDriveBackupWorker.KEY_PDF_PATH, pdfPath)
            .putString(GoogleDriveBackupWorker.KEY_SNAPSHOT_PATH, snapshotFile.absolutePath)
            .putString(GoogleDriveBackupWorker.KEY_DOC_TITLE, docItem.title)
            .putString(GoogleDriveBackupWorker.KEY_OWNER_ID, ownerId)
            .putLong(GoogleDriveBackupWorker.KEY_REVISION, docItem.contentRevision)
            .build()

        val snapTag = BackupSnapshotStore.getSnapshotTag(snapshotFile)

        val backupWorkRequest = OneTimeWorkRequestBuilder<GoogleDriveBackupWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag("cloud_backup")
            .addTag("owner_$ownerId")
            .addTag("doc_${docItem.id}")
            .addTag(snapTag)
            .build()

        enqueueWork(context, "backup_${docItem.id}", backupWorkRequest)
        Log.d(TAG, "Enqueued cloud backup for document: ${docItem.title} (${docItem.id}) with snapshot: ${snapshotFile.name}")
    }

    /**
     * Non-blocking asynchronous backup enqueue designed for UI caller threads.
     * Dispatches snapshot creation and work enqueue to [ioDispatcher] in [coroutineScope].
     */
    fun enqueueBackupAsync(
        context: Context,
        docItem: DocumentItem,
        coroutineScope: CoroutineScope = getOrCreateSessionScope(),
        ioDispatcher: CoroutineDispatcher = this.ioDispatcher,
        expectedUserId: String? = null,
        expectedSessionGen: Long? = null
    ): Job {
        val initialUser = expectedUserId ?: AppAuthManager.getCurrentUser()?.id
        val initialSessionGen = expectedSessionGen ?: AppAuthManager.getSessionGeneration()
        return coroutineScope.launch(ioDispatcher) {
            enqueueBackup(
                context = context,
                docItem = docItem,
                skipCleanup = false,
                expectedUserId = initialUser,
                expectedSessionGen = initialSessionGen
            )
        }
    }

    @androidx.annotation.VisibleForTesting
    var workEnqueuer: ((Context, String, ExistingWorkPolicy, OneTimeWorkRequest) -> Unit)? = null

    @androidx.annotation.VisibleForTesting
    var workManagerProvider: ((Context) -> WorkManager)? = null

    internal fun enqueueWork(
        context: Context,
        uniqueWorkName: String,
        workRequest: OneTimeWorkRequest
    ) {
        val customEnqueuer = workEnqueuer
        if (customEnqueuer != null) {
            customEnqueuer(context, uniqueWorkName, ExistingWorkPolicy.REPLACE, workRequest)
            return
        }
        val wm = workManagerProvider?.invoke(context) ?: WorkManager.getInstance(context)
        wm.enqueueUniqueWork(
            uniqueWorkName,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    /**
     * Enqueues all unsynced documents on the device for backup (e.g. after VIP upgrade).
     * S07: Cleans orphan snapshots EXACTLY ONCE for the entire batch.
     */
    fun enqueueBatchBackup(
        context: Context,
        docs: List<DocumentItem>,
        expectedUserId: String? = null,
        expectedSessionGen: Long? = null
    ) {
        val currentUser = AppAuthManager.getCurrentUser()
        val currentSessionGen = AppAuthManager.getSessionGeneration()
        val targetExpectedUser = expectedUserId ?: currentUser?.id
        val targetExpectedSession = expectedSessionGen ?: currentSessionGen

        if (currentUser == null || !AppAuthManager.isUserVip()) {
            return
        }
        if (targetExpectedUser != null && currentUser.id != targetExpectedUser) {
            Log.w(TAG, "Batch backup aborted: current user ${currentUser.id} != expected $targetExpectedUser")
            return
        }
        if (targetExpectedSession != null && currentSessionGen != targetExpectedSession) {
            Log.w(TAG, "Batch backup aborted: current session $currentSessionGen != expected $targetExpectedSession")
            return
        }

        val unsynced = docs.filter {
            val ownerMatch = (it.ownerId == null || it.ownerId == currentUser.id)
            ownerMatch && !it.isSynced && !it.isConflict && it.pdfPath != null && File(it.pdfPath).exists()
        }
        if (unsynced.isEmpty()) {
            return
        }

        Log.i(TAG, "Enqueuing batch cloud backup for ${unsynced.size} documents (single cleanup execution)")
        // S07: Clean orphan snapshots once for the whole batch
        BackupSnapshotStore.cleanOrphanSnapshots(context)

        for (doc in unsynced) {
            // Recheck user/session before each item
            val curUser = AppAuthManager.getCurrentUser()
            val curSession = AppAuthManager.getSessionGeneration()
            if (curUser?.id != targetExpectedUser || curSession != targetExpectedSession || curUser?.isVipActive != true) {
                Log.w(TAG, "User/session changed during batch backup processing. Aborting remainder of batch.")
                break
            }
            enqueueBackup(
                context = context,
                docItem = doc,
                skipCleanup = true,
                expectedUserId = targetExpectedUser,
                expectedSessionGen = targetExpectedSession
            )
        }
    }

    private val sessionLock = Any()
    private var currentSessionScope: CoroutineScope? = null

    private val activeDownloadsLock = Any()
    private val activeDownloads = mutableMapOf<String, MutableList<(File?) -> Unit>>()

    private val isSyncingCatalog = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Cancels all in-flight cloud tasks and invalidates current session scope.
     * Optionally cancels WorkManager jobs tagged for [previousUserId].
     */
    fun cancelActiveCloudTasks(context: Context? = null, previousUserId: String? = null) {
        synchronized(sessionLock) {
            currentSessionScope?.cancel("Session invalidated or user signed out/switched")
            currentSessionScope = null
        }
        synchronized(activeDownloadsLock) {
            val callbacks = activeDownloads.values.flatten()
            activeDownloads.clear()
            for (cb in callbacks) {
                try { cb(null) } catch (e: Exception) { Log.w(TAG, "Error invoking cancelled download callback", e) }
            }
        }
        if (context != null) {
            try {
                val wm = WorkManager.getInstance(context)
                if (previousUserId != null) {
                    wm.cancelAllWorkByTag("owner_$previousUserId")
                } else {
                    wm.cancelAllWorkByTag("cloud_backup")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel work manager jobs by tag", e)
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    internal fun getOrCreateSessionScope(): CoroutineScope {
        synchronized(sessionLock) {
            val existing = currentSessionScope
            if (existing != null && existing.isActive) {
                return existing
            }
            val newScope = CoroutineScope(SupervisorJob() + ioDispatcher)
            currentSessionScope = newScope
            return newScope
        }
    }

    /**
     * Cleans up any mock drive IDs from past demo testing, resetting them to LOCAL_ONLY.
     */
    fun cleanMockDriveBackups(context: Context) {
        val repo = DocumentRepo.getInstance(context)
        val docs = repo.documents.value ?: return
        for (doc in docs) {
            if (doc.driveFileId?.startsWith("drive_mock_") == true) {
                repo.updateSyncStatus(doc.id, SyncStatus.LOCAL_ONLY, null, null, clearDriveFileId = true)
            }
        }
    }

    /**
     * Synchronizes file catalog from Google Drive for cross-device synchronization (Lazy Loading).
     * Backwards-compatible callback returning count of newly added documents.
     */
    fun syncCatalogFromDrive(context: Context, onComplete: (newFilesCount: Int) -> Unit) {
        syncCatalogFromDriveWithResult(context) { result ->
            val count = when (result) {
                is SyncCatalogResult.Success -> result.addedCount
                is SyncCatalogResult.Partial -> result.addedCount
                is SyncCatalogResult.AuthRequired -> {
                    Log.w(TAG, "syncCatalogFromDrive: Authorization required: ${result.error}")
                    0
                }
                is SyncCatalogResult.Failure -> {
                    Log.w(TAG, "syncCatalogFromDrive: Failed: ${result.error}")
                    0
                }
                is SyncCatalogResult.Skipped -> {
                    Log.d(TAG, "syncCatalogFromDrive: Skipped: ${result.reason}")
                    0
                }
            }
            onComplete(count)
        }
    }

    @androidx.annotation.VisibleForTesting
    var syncCatalogOverrideForTesting: ((Context, (SyncCatalogResult) -> Unit) -> Unit)? = null

    /**
     * Synchronizes file catalog from Google Drive with typed result distinguishing
     * full success, partial results, authorization requirements, and failures.
     * Guarantees single-flight execution and atomic idempotency (V06/V10b).
     */
    fun syncCatalogFromDriveWithResult(
        context: Context,
        onResult: (SyncCatalogResult) -> Unit
    ) {
        val override = syncCatalogOverrideForTesting
        if (override != null) {
            override.invoke(context, onResult)
            return
        }

        val initialUser = AppAuthManager.getCurrentUser()
        val expectedSessionGen = AppAuthManager.getSessionGeneration()
        val expectedUserId = initialUser?.id

        if (initialUser == null || !AppAuthManager.isUserVip() || initialUser.email.isBlank()) {
            val reason = when {
                initialUser == null || initialUser.email.isBlank() -> SyncCatalogResult.Skipped.REASON_NOT_LOGGED_IN
                else -> SyncCatalogResult.Skipped.REASON_NOT_VIP
            }
            onResult(SyncCatalogResult.Skipped(reason))
            return
        }

        if (!isSyncingCatalog.compareAndSet(false, true)) {
            Log.d(TAG, "Catalog sync already in progress, skipping duplicate launch.")
            onResult(SyncCatalogResult.Failure("Sync already in progress"))
            return
        }

        // Reset previous sync error state (V10b)
        GoogleDriveService.lastSyncError = null

        val scope = getOrCreateSessionScope()
        scope.launch {
            try {
                if (!isActive || AppAuthManager.getSessionGeneration() != expectedSessionGen || AppAuthManager.getCurrentUser()?.id != expectedUserId) {
                    return@launch
                }

                val token = GoogleDriveService.getAccessToken(context, initialUser.email)
                if (token.isNullOrEmpty() || !isActive || AppAuthManager.getSessionGeneration() != expectedSessionGen) {
                    withContext(Dispatchers.Main) {
                        if (isActive && AppAuthManager.getSessionGeneration() == expectedSessionGen && AppAuthManager.getCurrentUser()?.id == expectedUserId) {
                            onResult(SyncCatalogResult.AuthRequired(GoogleDriveService.lastSyncError ?: "Chưa cấp quyền Google Drive"))
                        }
                    }
                    return@launch
                }

                val folderId = GoogleDriveService.getOrCreateAppFolder(token)
                if (folderId.isNullOrEmpty() || !isActive || AppAuthManager.getSessionGeneration() != expectedSessionGen) {
                    withContext(Dispatchers.Main) {
                        if (isActive && AppAuthManager.getSessionGeneration() == expectedSessionGen && AppAuthManager.getCurrentUser()?.id == expectedUserId) {
                            onResult(SyncCatalogResult.Failure(GoogleDriveService.lastSyncError ?: "Không thể tạo hoặc mở thư mục Drive"))
                        }
                    }
                    return@launch
                }

                val queryResult = GoogleDriveService.queryFolderFiles(token, folderId)
                if (!isActive || AppAuthManager.getSessionGeneration() != expectedSessionGen || AppAuthManager.getCurrentUser()?.id != expectedUserId) {
                    return@launch
                }

                val repo = DocumentRepo.getInstance(context)
                var addedCount = 0
                val driveFiles = queryResult.filesOrEmpty

                for (df in driveFiles) {
                    if (!isActive || AppAuthManager.getSessionGeneration() != expectedSessionGen) {
                        return@launch
                    }
                    val upsertRes = repo.upsertFromDrive(
                        ownerId = expectedUserId!!,
                        driveFileId = df.id,
                        title = df.name,
                        sizeBytes = df.sizeBytes,
                        modifiedTime = df.modifiedTime
                    )
                    if (upsertRes?.isNew == true) {
                        addedCount++
                    }
                }

                val finalResult = when (queryResult) {
                    is QueryFolderResult.Success -> SyncCatalogResult.Success(addedCount, driveFiles.size)
                    is QueryFolderResult.Partial -> SyncCatalogResult.Partial(addedCount, driveFiles.size, queryResult.error)
                    is QueryFolderResult.AuthRequired -> SyncCatalogResult.AuthRequired(queryResult.error)
                    is QueryFolderResult.Failure -> SyncCatalogResult.Failure(queryResult.error)
                }

                Log.i(TAG, "Cross-device sync completed: $finalResult")
                withContext(Dispatchers.Main) {
                    if (isActive && AppAuthManager.getSessionGeneration() == expectedSessionGen && AppAuthManager.getCurrentUser()?.id == expectedUserId) {
                        onResult(finalResult)
                    }
                }
            } finally {
                isSyncingCatalog.set(false)
            }
        }
    }

    /**
     * Downloads a document from Google Drive if it hasn't been cached locally yet.
     * Uses single-flight deduplication to avoid concurrent duplicate downloads.
     */
    fun downloadDocument(
        context: Context,
        docItem: DocumentItem,
        onComplete: (File?) -> Unit
    ) {
        val currentUser = AppAuthManager.getCurrentUser()
        if (docItem.ownerId != null && docItem.ownerId != currentUser?.id) {
            Log.w(TAG, "Cannot download document ${docItem.id}: owned by ${docItem.ownerId}, but current user is ${currentUser?.id}")
            onComplete(null)
            return
        }

        val existingPath = docItem.pdfPath
        if (existingPath != null && File(existingPath).exists()) {
            onComplete(File(existingPath))
            return
        }

        val driveId = docItem.driveFileId
        if (driveId.isNullOrEmpty()) {
            onComplete(null)
            return
        }

        val docId = docItem.id
        val shouldLaunch = synchronized(activeDownloadsLock) {
            val existing = activeDownloads[docId]
            if (existing != null) {
                existing.add(onComplete)
                false
            } else {
                activeDownloads[docId] = mutableListOf(onComplete)
                true
            }
        }
        if (!shouldLaunch) {
            Log.d(TAG, "Download for doc $docId is already in flight. Coalesced callback.")
            return
        }

        val expectedSessionGen = AppAuthManager.getSessionGeneration()
        val expectedUserId = currentUser?.id
        val scope = getOrCreateSessionScope()

        suspend fun finishDownload(resultFile: File?) {
            val callbacks = synchronized(activeDownloadsLock) {
                activeDownloads.remove(docId)
            } ?: emptyList()
            withContext(Dispatchers.Main) {
                val currentGen = AppAuthManager.getSessionGeneration()
                val currentUid = AppAuthManager.getCurrentUser()?.id
                val sessionValid = (currentGen == expectedSessionGen && currentUid == expectedUserId)
                val finalFile = if (sessionValid) resultFile else null
                for (cb in callbacks) {
                    try {
                        cb(finalFile)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error invoking download callback for $docId", e)
                    }
                }
            }
        }

        scope.launch {
            var completedFile: File? = null
            try {
                val user = AppAuthManager.getCurrentUser()
                val email = user?.email ?: ""
                if (!isActive || AppAuthManager.getSessionGeneration() != expectedSessionGen || user?.id != expectedUserId) {
                    return@launch
                }

                val token = GoogleDriveService.getAccessToken(context, email)
                if (token.isNullOrEmpty() || !isActive || AppAuthManager.getSessionGeneration() != expectedSessionGen) {
                    return@launch
                }

                val docDir = FileUtils.getDocumentsDir(context)
                val destFile = File(docDir, "doc_${docItem.id}.pdf")

                val success = GoogleDriveService.downloadPdfFile(token, driveId, destFile)
                if (!isActive || AppAuthManager.getSessionGeneration() != expectedSessionGen || AppAuthManager.getCurrentUser()?.id != expectedUserId) {
                    // User logged out or switched while downloading! Discard downloaded file!
                    if (destFile.exists()) destFile.delete()
                    return@launch
                }

                if (success && destFile.exists() && destFile.length() > 0L) {
                    val thumbFile = File(FileUtils.getThumbnailsDir(context), "thumb_${docItem.id}.jpg")
                    val thumbSuccess = PdfConverterHelper.renderPdfFirstPage(destFile, thumbFile)
                    val thumbPath = if (thumbSuccess) thumbFile.absolutePath else null

                    // Verify session one more time before committing to repository
                    if (!isActive || AppAuthManager.getSessionGeneration() != expectedSessionGen || AppAuthManager.getCurrentUser()?.id != expectedUserId) {
                        destFile.delete()
                        thumbFile.delete()
                        return@launch
                    }

                    val updated = DocumentRepo.getInstance(context).updateDocumentPdfPath(
                        docId = docItem.id,
                        newPath = destFile.absolutePath,
                        fileSize = destFile.length(),
                        thumbPath = thumbPath,
                        pageCount = null,
                        expectedOwnerId = expectedUserId,
                        expectedRevision = docItem.contentRevision
                    )
                    if (updated) {
                        completedFile = destFile
                    } else {
                        destFile.delete()
                        thumbFile.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during download of $docId: ${e.message}", e)
            } finally {
                finishDownload(completedFile)
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    fun resetForTesting() {
        isSyncingCatalog.set(false)
        syncCatalogOverrideForTesting = null
        workEnqueuer = null
        workManagerProvider = null
        ioDispatcher = Dispatchers.IO
    }
}
