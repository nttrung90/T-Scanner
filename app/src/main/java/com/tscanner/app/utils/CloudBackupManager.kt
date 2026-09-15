package com.tscanner.app.utils

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.model.SyncStatus
import com.tscanner.app.data.repository.DocumentRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

object CloudBackupManager {

    private const val TAG = "CloudBackupManager"

    /**
     * Enqueues a single document for background Google Drive backup.
     */
    fun enqueueBackup(context: Context, docItem: DocumentItem) {
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
        val ownerId = docItem.ownerId ?: currentUser?.id

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = Data.Builder()
            .putString(GoogleDriveBackupWorker.KEY_DOC_ID, docItem.id)
            .putString(GoogleDriveBackupWorker.KEY_PDF_PATH, pdfPath)
            .putString(GoogleDriveBackupWorker.KEY_DOC_TITLE, docItem.title)
            .apply {
                if (ownerId != null) {
                    putString(GoogleDriveBackupWorker.KEY_OWNER_ID, ownerId)
                }
            }
            .build()

        val backupWorkRequest = OneTimeWorkRequestBuilder<GoogleDriveBackupWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "backup_${docItem.id}",
            ExistingWorkPolicy.REPLACE,
            backupWorkRequest
        )
        Log.d(TAG, "Enqueued cloud backup for document: ${docItem.title} (${docItem.id})")
    }

    /**
     * Enqueues all unsynced documents on the device for backup (e.g. after VIP upgrade).
     */
    fun enqueueBatchBackup(context: Context, docs: List<DocumentItem>) {
        val unsynced = docs.filter { !it.isSynced && it.pdfPath != null && File(it.pdfPath).exists() }
        Log.i(TAG, "Enqueuing batch cloud backup for ${unsynced.size} documents")
        for (doc in unsynced) {
            enqueueBackup(context, doc)
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
                repo.updateSyncStatus(doc.id, SyncStatus.LOCAL_ONLY, null, null)
            }
        }
    }

    /**
     * Synchronizes file catalog from Google Drive for cross-device synchronization (Lazy Loading).
     */
    fun syncCatalogFromDrive(context: Context, onComplete: (newFilesCount: Int) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val user = AppAuthManager.getCurrentUser()
            if (user == null || !AppAuthManager.isUserVip() || user.email.isBlank()) {
                withContext(Dispatchers.Main) { onComplete(0) }
                return@launch
            }

            val token = GoogleDriveService.getAccessToken(context, user.email)
            if (token.isNullOrEmpty()) {
                withContext(Dispatchers.Main) { onComplete(0) }
                return@launch
            }

            val folderId = GoogleDriveService.getOrCreateAppFolder(token)
            if (folderId.isNullOrEmpty()) {
                withContext(Dispatchers.Main) { onComplete(0) }
                return@launch
            }

            val driveFiles = GoogleDriveService.queryFolderFiles(token, folderId)
            val repo = DocumentRepo.getInstance(context)
            val localDocs = repo.documents.value ?: emptyList()
            val existingDriveIds = localDocs.mapNotNull { it.driveFileId }.toSet()

            var addedCount = 0
            for (df in driveFiles) {
                if (!existingDriveIds.contains(df.id)) {
                    val newDoc = DocumentItem(
                        id = UUID.randomUUID().toString(),
                        title = df.name.removeSuffix(".pdf"),
                        pdfPath = null, // Lazy: PDF will be downloaded when user opens it
                        thumbnailPath = null,
                        pagePaths = emptyList(),
                        pageCount = 1,
                        sizeBytes = df.sizeBytes,
                        createdAt = df.modifiedTime,
                        isSynced = true,
                        driveFileId = df.id,
                        lastSyncedAt = df.modifiedTime,
                        syncStatus = SyncStatus.SYNCED,
                        ownerId = user.id
                    )
                    repo.addDocument(newDoc)
                    addedCount++
                }
            }

            Log.i(TAG, "Cross-device sync completed: added $addedCount new documents from Drive")
            withContext(Dispatchers.Main) {
                onComplete(addedCount)
            }
        }
    }

    /**
     * Downloads a document from Google Drive if it hasn't been cached locally yet.
     */
    fun downloadDocument(
        context: Context,
        docItem: DocumentItem,
        onComplete: (File?) -> Unit
    ) {
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

        CoroutineScope(Dispatchers.IO).launch {
            val user = AppAuthManager.getCurrentUser()
            val email = user?.email ?: ""
            val token = GoogleDriveService.getAccessToken(context, email)
            if (token.isNullOrEmpty()) {
                withContext(Dispatchers.Main) { onComplete(null) }
                return@launch
            }

            val docDir = FileUtils.getDocumentsDir(context)
            val destFile = File(docDir, "doc_${docItem.id}.pdf")

            val success = GoogleDriveService.downloadPdfFile(token, driveId, destFile)
            if (success && destFile.exists()) {
                val thumbFile = File(FileUtils.getThumbnailsDir(context), "thumb_${docItem.id}.jpg")
                val thumbPath = if (PdfConverterHelper.renderPdfFirstPage(destFile, thumbFile)) {
                    thumbFile.absolutePath
                } else null

                DocumentRepo.getInstance(context).updateDocumentPdfPath(
                    docId = docItem.id,
                    newPath = destFile.absolutePath,
                    fileSize = destFile.length(),
                    thumbPath = thumbPath
                )
                withContext(Dispatchers.Main) {
                    onComplete(destFile)
                }
            } else {
                withContext(Dispatchers.Main) {
                    onComplete(null)
                }
            }
        }
    }
}
