package com.tscanner.app.utils

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tscanner.app.data.model.SyncStatus
import com.tscanner.app.data.repository.DocumentRepo
import java.io.File
import java.util.UUID

class GoogleDriveBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DriveBackupWorker"
        const val KEY_DOC_ID = "key_doc_id"
        const val KEY_PDF_PATH = "key_pdf_path"
        const val KEY_DOC_TITLE = "key_doc_title"
        const val KEY_OWNER_ID = "key_owner_id"
    }

    override suspend fun doWork(): Result {
        val docId = inputData.getString(KEY_DOC_ID) ?: return Result.failure()
        val pdfPath = inputData.getString(KEY_PDF_PATH) ?: return Result.failure()
        val docTitle = inputData.getString(KEY_DOC_TITLE) ?: "Document"
        val expectedOwnerId = inputData.getString(KEY_OWNER_ID)

        val repo = DocumentRepo.getInstance(applicationContext)

        // 1. Check if user is still VIP and within 1-year valid subscription
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
        if (doc?.ownerId != null && doc.ownerId != currentUser.id) {
            Log.w(TAG, "Document owner mismatch for docId=$docId: doc.ownerId=${doc.ownerId}, current=${currentUser.id}. Aborting backup.")
            return Result.failure()
        }

        val pdfFile = File(pdfPath)
        if (!pdfFile.exists() || pdfFile.length() == 0L) {
            Log.e(TAG, "PDF file missing or empty: $pdfPath")
            repo.updateSyncStatus(docId, SyncStatus.FAILED)
            return Result.failure()
        }

        // 2. Set status to SYNCING
        repo.updateSyncStatus(docId, SyncStatus.SYNCING)

        // 3. Obtain OAuth Token
        val token = GoogleDriveService.getAccessToken(applicationContext, currentUser.email)
        if (token.isNullOrEmpty()) {
            Log.e(TAG, "Failed to get Google Drive OAuth token for ${currentUser.email}")
            repo.updateSyncStatus(docId, SyncStatus.FAILED)
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }

        // 4. Ensure "T-Scanner Documents" folder exists on Drive
        val folderId = GoogleDriveService.getOrCreateAppFolder(token)
        if (folderId.isNullOrEmpty()) {
            Log.e(TAG, "Failed to get or create folder on Google Drive")
            repo.updateSyncStatus(docId, SyncStatus.FAILED)
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }

        // 5. Upload or update PDF file on Drive via REST API
        val existingDriveId = doc?.driveFileId
        val isExistingValidDrive = existingDriveId != null && existingDriveId.isNotEmpty() && !existingDriveId.startsWith("drive_mock_")
        val driveFileId = if (isExistingValidDrive) {
            Log.d(TAG, "Updating existing PDF file on Google Drive: $existingDriveId")
            val updatedId = GoogleDriveService.updatePdfFile(token, existingDriveId!!, pdfFile)
            updatedId ?: GoogleDriveService.uploadPdfFile(token, folderId, pdfFile, docTitle)
        } else {
            GoogleDriveService.uploadPdfFile(token, folderId, pdfFile, docTitle)
        }

        return if (!driveFileId.isNullOrEmpty()) {
            Log.i(TAG, "Successfully backed up document '$docTitle' ($docId) to Google Drive: $driveFileId")
            repo.updateSyncStatus(docId, SyncStatus.SYNCED, driveFileId, System.currentTimeMillis())
            Result.success()
        } else {
            Log.e(TAG, "Failed to upload file to Google Drive: docId=$docId")
            repo.updateSyncStatus(docId, SyncStatus.FAILED)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
