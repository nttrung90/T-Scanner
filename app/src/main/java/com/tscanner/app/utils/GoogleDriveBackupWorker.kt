package com.tscanner.app.utils

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tscanner.app.data.model.SyncStatus
import com.tscanner.app.data.repository.DocumentRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.UUID

class GoogleDriveBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return performBackup(applicationContext, inputData, runAttemptCount)
    }

    companion object {
        private const val TAG = "DriveBackupWorker"
        const val KEY_DOC_ID = "key_doc_id"
        const val KEY_PDF_PATH = "key_pdf_path"
        const val KEY_SNAPSHOT_PATH = "key_snapshot_path"
        const val KEY_DOC_TITLE = "key_doc_title"
        const val KEY_OWNER_ID = "key_owner_id"
        const val KEY_REVISION = "key_revision"

        @androidx.annotation.VisibleForTesting
        internal var tokenProviderForTesting: ((Context, String) -> String?)? = null

        @androidx.annotation.VisibleForTesting
        internal var driveUploaderForTesting: ((token: String, folderId: String, file: File, title: String, docId: String) -> DriveOperationResult<String>)? = null

        @androidx.annotation.VisibleForTesting
        fun resetForTesting() {
            tokenProviderForTesting = null
            driveUploaderForTesting = null
        }

        internal suspend fun performBackup(
            context: Context,
            inputData: androidx.work.Data,
            runAttemptCount: Int
        ): Result {
        val docId = inputData.getString(KEY_DOC_ID) ?: return Result.failure()
        val pdfPath = inputData.getString(KEY_PDF_PATH) ?: return Result.failure()
        val snapshotPath = inputData.getString(KEY_SNAPSHOT_PATH)
        val docTitle = inputData.getString(KEY_DOC_TITLE) ?: "Document"
        val expectedOwnerId = inputData.getString(KEY_OWNER_ID)
        val expectedRevision = inputData.getLong(KEY_REVISION, -1L)

        val repo = DocumentRepo.getInstance(context)
        var isRetrying = false
        var uploadFile: File? = null
        val initialSessionGen = AppAuthManager.getSessionGeneration()

        try {
            // 1. Check if user is still VIP and within valid subscription
            if (!AppAuthManager.isUserVip()) {
                Log.d(TAG, "User is not VIP or VIP has expired. Skipping cloud backup for docId=$docId")
                repo.updateSyncStatus(docId, SyncStatus.LOCAL_ONLY)
                return Result.success()
            }

            val currentUser = AppAuthManager.getCurrentUser()
            if (currentUser == null || currentUser.email.isBlank()) {
                Log.w(TAG, "No logged in user found for backup.")
                repo.updateSyncStatus(docId, SyncStatus.FAILED)
                return Result.failure()
            }

            // Enforce owner binding: prevent backing up documents under another user's Google Drive
            if (expectedOwnerId != null && expectedOwnerId != currentUser.id) {
                Log.w(TAG, "Owner mismatch for docId=$docId: expected=$expectedOwnerId, current=${currentUser.id}. Aborting backup.")
                return Result.failure()
            }

            val doc = repo.getDocument(docId)
            if (doc == null) {
                Log.w(TAG, "Document $docId was deleted from repository. Aborting obsolete upload.")
                return Result.success()
            }

            if (doc.ownerId != null && doc.ownerId != currentUser.id) {
                Log.w(TAG, "Document owner mismatch for docId=$docId: doc.ownerId=${doc.ownerId}, current=${currentUser.id}. Aborting backup.")
                return Result.failure()
            }

            if (doc.isConflict) {
                Log.w(TAG, "Document $docId is in conflict state. Aborting automatic backup.")
                return Result.failure()
            }

            // Invariant: Do not upload if local document has moved past the revision that was enqueued
            if (expectedRevision != -1L && doc.contentRevision != expectedRevision) {
                Log.d(TAG, "Document $docId revision changed (expected=$expectedRevision, current=${doc.contentRevision}). Skipping obsolete upload.")
                return Result.success()
            }

            val currentRevision = if (expectedRevision != -1L) expectedRevision else doc.contentRevision

            // Snapshot is strictly mandatory. Fallback to live file is forbidden by policy.
            if (snapshotPath.isNullOrBlank()) {
                Log.e(TAG, "Missing mandatory snapshot path for docId=$docId. Live file fallback forbidden.")
                repo.updateSyncStatusCas(docId, currentUser.id, currentRevision, SyncStatus.FAILED)
                return Result.failure()
            }

            var resolvedFile = File(snapshotPath)
            if (!resolvedFile.exists() || resolvedFile.length() == 0L) {
                // S08: If the job was enqueued prior to storage migration (holding legacy cache path),
                // check if the snapshot exists in persistent storage by filename.
                val persistentFallback = File(BackupSnapshotStore.getSnapshotDir(context), resolvedFile.name)
                if (persistentFallback.exists() && persistentFallback.length() > 0L) {
                    Log.i(TAG, "Resolved legacy snapshot path to persistent storage: ${persistentFallback.absolutePath}")
                    resolvedFile = persistentFallback
                } else {
                    val legacyFallback = File(BackupSnapshotStore.getLegacyCacheSnapshotDir(context), resolvedFile.name)
                    if (legacyFallback.exists() && legacyFallback.length() > 0L) {
                        Log.i(TAG, "Resolved snapshot path to legacy cache storage: ${legacyFallback.absolutePath}")
                        resolvedFile = legacyFallback
                    }
                }
            }

            if (!resolvedFile.exists() || resolvedFile.length() == 0L) {
                Log.e(TAG, "Upload snapshot file missing or empty: $snapshotPath (live file fallback forbidden)")
                repo.updateSyncStatusCas(docId, currentUser.id, currentRevision, SyncStatus.FAILED)
                return Result.failure()
            }
            uploadFile = resolvedFile

            // 2. Set status to SYNCING via CAS
            val markSyncing = repo.updateSyncStatusCas(
                docId = docId,
                expectedOwnerId = currentUser.id,
                expectedRevision = currentRevision,
                status = SyncStatus.SYNCING
            )
            if (!markSyncing) {
                Log.d(TAG, "Document $docId was modified or reallocated before SYNCING state. Aborting obsolete upload.")
                return Result.success()
            }

            // 3. Obtain OAuth Token
            val token = tokenProviderForTesting?.invoke(context, currentUser.email)
                ?: GoogleDriveService.getAccessToken(context, currentUser.email)
            if (token.isNullOrEmpty()) {
                Log.e(TAG, "Failed to get Google Drive OAuth token for ${currentUser.email}")
                repo.updateSyncStatusCas(docId, currentUser.id, currentRevision, SyncStatus.FAILED)
                return if (runAttemptCount < 3) {
                    isRetrying = true
                    Result.retry()
                } else {
                    Result.failure()
                }
            }

            // Checkpoint 3A: Recheck VIP status, session generation, and cooperative cancellation after token wait (F10, R11)
            currentCoroutineContext().ensureActive()
            if (!AppAuthManager.isUserVip()) {
                Log.w(TAG, "VIP status was revoked during OAuth token wait. Aborting backup for docId=$docId")
                repo.updateSyncStatusCas(docId, currentUser.id, currentRevision, SyncStatus.LOCAL_ONLY)
                return Result.success()
            }
            val userAfterToken = AppAuthManager.getCurrentUser()
            if (userAfterToken == null || userAfterToken.id != currentUser.id || AppAuthManager.getSessionGeneration() != initialSessionGen) {
                Log.w(TAG, "User session changed or logged out during token wait. Aborting backup for docId=$docId")
                return Result.failure()
            }

            // 4. Ensure "T-Scanner Documents" folder exists on Drive
            val folderId = if (driveUploaderForTesting != null) "folder_test" else GoogleDriveService.getOrCreateAppFolder(token)
            if (folderId.isNullOrEmpty()) {
                Log.e(TAG, "Failed to get or create folder on Google Drive")
                repo.updateSyncStatusCas(docId, currentUser.id, currentRevision, SyncStatus.FAILED)
                return if (runAttemptCount < 3) {
                    isRetrying = true
                    Result.retry()
                } else {
                    Result.failure()
                }
            }

            // Checkpoint 4A: Recheck VIP status, session generation, and cooperative cancellation after folder lookup (F10, R11)
            currentCoroutineContext().ensureActive()
            if (!AppAuthManager.isUserVip()) {
                Log.w(TAG, "VIP status was revoked before upload. Aborting backup for docId=$docId")
                repo.updateSyncStatusCas(docId, currentUser.id, currentRevision, SyncStatus.LOCAL_ONLY)
                return Result.success()
            }
            val userBeforeUpload = AppAuthManager.getCurrentUser()
            if (userBeforeUpload == null || userBeforeUpload.id != currentUser.id || AppAuthManager.getSessionGeneration() != initialSessionGen) {
                Log.w(TAG, "User session changed before upload. Aborting backup for docId=$docId")
                return Result.failure()
            }

            // 5. Upload or update PDF file on Drive via REST API
            val customUploader = driveUploaderForTesting
            val uploadResult: DriveOperationResult<String> = if (customUploader != null) {
                customUploader(token, folderId, uploadFile, docTitle, docId)
            } else {
                val existingDriveId = doc.driveFileId
                val isExistingValidDrive = existingDriveId != null && existingDriveId.isNotEmpty() && !existingDriveId.startsWith("drive_mock_")
                if (isExistingValidDrive) {
                    Log.d(TAG, "Updating existing PDF file on Google Drive: $existingDriveId")
                    val updateRes = GoogleDriveService.updatePdfFile(token, existingDriveId!!, uploadFile, docTitle)
                    when (updateRes) {
                        is DriveOperationResult.Success -> updateRes
                        is DriveOperationResult.FileNotFound -> {
                            // File was actually deleted on Drive (404). Re-create in folder with docId idempotency.
                            currentCoroutineContext().ensureActive()
                            Log.w(TAG, "Drive file $existingDriveId not found (404). Re-uploading document as new file.")
                            GoogleDriveService.uploadPdfFile(token, folderId, uploadFile, docTitle, docId)
                        }
                        is DriveOperationResult.TransientError -> {
                            // Transient failure (5xx, 429, timeout). DO NOT recreate or duplicate!
                            Log.w(TAG, "Transient error updating Drive file $existingDriveId: ${updateRes.message}. Will retry without duplicating.")
                            updateRes
                        }
                        is DriveOperationResult.AuthError,
                        is DriveOperationResult.QuotaExceeded,
                        is DriveOperationResult.PermanentError -> {
                            // Auth, quota or permanent error. DO NOT recreate or duplicate!
                            Log.e(TAG, "Non-transient error updating Drive file $existingDriveId: ${updateRes.errorMessage}")
                            updateRes
                        }
                    }
                } else {
                    currentCoroutineContext().ensureActive()
                    GoogleDriveService.uploadPdfFile(token, folderId, uploadFile, docTitle, docId)
                }
            }

            return when (uploadResult) {
                is DriveOperationResult.Success -> {
                    val driveFileId = uploadResult.data
                    Log.i(TAG, "Successfully backed up document '$docTitle' ($docId) to Google Drive: $driveFileId")

                    val userBeforeCommit = AppAuthManager.getCurrentUser()
                    if (userBeforeCommit == null || userBeforeCommit.id != currentUser.id || AppAuthManager.getSessionGeneration() != initialSessionGen) {
                        Log.w(TAG, "Session changed after remote upload for docId=$docId; suppressing local commit to SYNCED under obsolete session.")
                        return Result.failure()
                    }

                    currentCoroutineContext().ensureActive()
                    val casSuccess = repo.updateSyncStatusCas(
                        docId = docId,
                        expectedOwnerId = currentUser.id,
                        expectedRevision = currentRevision,
                        status = SyncStatus.SYNCED,
                        driveFileId = driveFileId,
                        syncedAt = System.currentTimeMillis()
                    )
                    if (!casSuccess) {
                        Log.w(TAG, "CAS sync commit rejected for docId=$docId rev=$currentRevision. Newer edits exist locally!")
                    }
                    Result.success()
                }
                is DriveOperationResult.TransientError -> {
                    Log.w(TAG, "Transient upload error for docId=$docId: ${uploadResult.message}. Attempt $runAttemptCount of 3.")
                    if (runAttemptCount < 3) {
                        isRetrying = true
                        Result.retry()
                    } else {
                        repo.updateSyncStatusCas(
                            docId = docId,
                            expectedOwnerId = currentUser.id,
                            expectedRevision = currentRevision,
                            status = SyncStatus.FAILED
                        )
                        Result.failure()
                    }
                }
                is DriveOperationResult.FileNotFound,
                is DriveOperationResult.AuthError,
                is DriveOperationResult.QuotaExceeded,
                is DriveOperationResult.PermanentError -> {
                    Log.e(TAG, "Permanent/Auth/Quota upload error for docId=$docId: ${uploadResult.errorMessage}")
                    repo.updateSyncStatusCas(
                        docId = docId,
                        expectedOwnerId = currentUser.id,
                        expectedRevision = currentRevision,
                        status = SyncStatus.FAILED
                    )
                    Result.failure()
                }
            }
        } catch (c: CancellationException) {
            Log.w(TAG, "Backup for docId=$docId canceled cooperatively: ${c.message}")
            throw c
        } finally {
            val isJobActive = try {
                currentCoroutineContext()[Job]?.isActive ?: true
            } catch (_: Exception) { true }

            // Clean up temporary immutable snapshot file only upon terminal work completion (not on retry or cancel)
            if (!isRetrying && isJobActive) {
                val fileToDelete = uploadFile
                if (fileToDelete != null && fileToDelete.exists()) {
                    BackupSnapshotStore.deleteSnapshot(fileToDelete)
                }
                if (snapshotPath != null && snapshotPath != fileToDelete?.absolutePath) {
                    val originalFile = File(snapshotPath)
                    if (originalFile.exists()) {
                        BackupSnapshotStore.deleteSnapshot(originalFile)
                    }
                }
            }
        }
    }
}
}


