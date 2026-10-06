package com.tscanner.app.ocr.data

import android.content.Context
import android.util.Log
import com.tscanner.app.ocr.model.OcrDocument
import com.tscanner.app.utils.SafeFileWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

/**
 * Result wrapper for OCR Document Repository operations.
 */
sealed class RepositoryResult<out T> {
    data class Success<out T>(val value: T) : RepositoryResult<T>()
    data class Conflict(val message: String, val currentRevision: Long) : RepositoryResult<Nothing>()
    data class NotFound(val message: String) : RepositoryResult<Nothing>()
    data class Corrupted(val message: String, val cause: Throwable? = null) : RepositoryResult<Nothing>()
    data class Error(val message: String, val cause: Throwable? = null) : RepositoryResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    fun getOrNull(): T? = (this as? Success)?.value
}

/**
 * Robust, atomic, revision-guarded repository for OCR Documents.
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S03).
 */
class OcrDocumentRepository(
    private val baseDir: File
) {
    companion object {
        private const val TAG = "OcrDocRepo"
        const val CURRENT_SCHEMA_VERSION = 1
        private const val MANIFEST_FILE_NAME = "document.json"
        private const val BACKUP_FILE_NAME = "document.json.bak"
        private const val IMAGES_DIR_NAME = "images"

        @Volatile
        private var instance: OcrDocumentRepository? = null

        fun getInstance(context: Context): OcrDocumentRepository {
            return instance ?: synchronized(this) {
                instance ?: OcrDocumentRepository(
                    File(context.filesDir, "ocr_documents")
                ).also { instance = it }
            }
        }
    }

    private val documentLocks = ConcurrentHashMap<String, Mutex>()
    private val lastCommittedRevisions = ConcurrentHashMap<String, Long>()

    fun getPersistedRevision(docId: String): Long? = lastCommittedRevisions[docId]

    private fun getDocumentLock(docId: String): Mutex {
        return documentLocks.computeIfAbsent(docId) { Mutex() }
    }

    // Seams for fault injection testing
    internal var preCommitFaultHook: (suspend (tempFile: File) -> Unit)? = null
    internal var postCommitFaultHook: (suspend (destFile: File) -> Unit)? = null

    private fun getDocumentDirectory(docId: String): File {
        return File(baseDir, docId)
    }

    private fun getManifestFile(docId: String): File {
        return File(getDocumentDirectory(docId), MANIFEST_FILE_NAME)
    }

    private fun getBackupFile(docId: String): File {
        return File(getDocumentDirectory(docId), BACKUP_FILE_NAME)
    }

    fun getImagesDirectory(docId: String): File {
        return File(getDocumentDirectory(docId), IMAGES_DIR_NAME)
    }

    /**
     * Atomically saves or updates an OCR document with optimistic revision check (CAS).
     *
     * @param document The document to save.
     * @param expectedRevision If provided, asserts that the currently persisted revision matches this value.
     */
    suspend fun saveDocument(
        document: OcrDocument,
        expectedRevision: Long? = null,
        commitToken: String = UUID.randomUUID().toString()
    ): RepositoryResult<OcrDocument> = withContext(Dispatchers.IO) {
        val lock = getDocumentLock(document.id)
        lock.withLock {
            try {
                val docDir = getDocumentDirectory(document.id)
                if (!docDir.exists()) {
                    docDir.mkdirs()
                }

                val manifestFile = getManifestFile(document.id)
                val backupFile = getBackupFile(document.id)

                // 1. Revision Check (CAS)
                if (manifestFile.exists()) {
                    val currentDiskDoc = readDocumentFromFile(manifestFile)
                    if (currentDiskDoc != null) {
                        if (expectedRevision != null && currentDiskDoc.revision != expectedRevision) {
                            return@withContext RepositoryResult.Conflict(
                                "Revision conflict on document ${document.id}: expected $expectedRevision but disk has ${currentDiskDoc.revision}",
                                currentDiskDoc.revision
                            )
                        }
                    }
                } else if (expectedRevision != null && expectedRevision > 1L) {
                    return@withContext RepositoryResult.Conflict(
                        "Revision conflict on document ${document.id}: expected $expectedRevision but document does not exist yet",
                        0L
                    )
                }

                // 2. Prepare target document with incremented revision and timestamp
                val nextRevision = (expectedRevision ?: document.revision) + 1L
                val docToSave = document.copy(
                    schemaVersion = CURRENT_SCHEMA_VERSION,
                    revision = nextRevision,
                    updatedAt = System.currentTimeMillis(),
                    lastCommitToken = commitToken
                )

                // 3. Write atomically using SafeFileWriter
                val jsonContent = docToSave.toJsonString(2)
                val writeResult = SafeFileWriter.writeSafely(
                    destinationFile = manifestFile,
                    validator = { tempFile ->
                        try {
                            val parsed = JSONObject(tempFile.readText(Charsets.UTF_8))
                            parsed.getString("id") == document.id
                        } catch (_: Exception) {
                            false
                        }
                    },
                    writer = { tempFile ->
                        // Pre-commit fault injection hook for testing
                        preCommitFaultHook?.invoke(tempFile)
                        tempFile.writeText(jsonContent, Charsets.UTF_8)
                        true
                    }
                )

                when (writeResult) {
                    is SafeFileWriter.Result.Success -> {
                        lastCommittedRevisions[document.id] = docToSave.revision
                        // Maintain rolling backup of the last valid manifest
                        try {
                            manifestFile.copyTo(backupFile, overwrite = true)
                        } catch (t: Throwable) {
                            Log.w(TAG, "Failed to update backup file for ${document.id}: ${t.message}")
                        }

                        // Post-commit fault injection hook for testing
                        postCommitFaultHook?.invoke(manifestFile)

                        RepositoryResult.Success(docToSave)
                    }
                    is SafeFileWriter.Result.Error -> {
                        RepositoryResult.Error("Failed to atomically commit manifest: ${writeResult.message}", writeResult.cause)
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "Error saving document ${document.id}: ${t.message}", t)
                RepositoryResult.Error("Exception during document save: ${t.message}", t)
            }
        }
    }

    /**
     * Loads a document by ID. If manifest is corrupted, automatically recovers from backup file.
     */
    suspend fun loadDocument(docId: String): RepositoryResult<OcrDocument> = withContext(Dispatchers.IO) {
        val lock = getDocumentLock(docId)
        lock.withLock {
            try {
                val manifestFile = getManifestFile(docId)
                val backupFile = getBackupFile(docId)

                if (!manifestFile.exists() && !backupFile.exists()) {
                    return@withContext RepositoryResult.NotFound("Document with ID '$docId' does not exist")
                }

                // Try reading primary manifest
                if (manifestFile.exists()) {
                    try {
                        val doc = readDocumentFromFile(manifestFile)
                        if (doc != null) {
                            val migrated = applySchemaMigration(doc)
                            return@withContext RepositoryResult.Success(migrated)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Manifest for $docId is corrupted. Attempting backup recovery: ${t.message}")
                    }
                }

                // Fallback: Attempt recovery from backup file
                if (backupFile.exists()) {
                    try {
                        val recoveredDoc = readDocumentFromFile(backupFile)
                        if (recoveredDoc != null) {
                            Log.i(TAG, "Successfully recovered document $docId from backup file")
                            // Re-persist recovered document to primary manifest
                            try {
                                backupFile.copyTo(manifestFile, overwrite = true)
                            } catch (_: Exception) {}
                            val migrated = applySchemaMigration(recoveredDoc)
                            return@withContext RepositoryResult.Success(migrated)
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Backup for $docId is also corrupted: ${t.message}", t)
                    }
                }

                RepositoryResult.Corrupted("Document $docId is corrupted and cannot be recovered")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "Unexpected error loading document $docId: ${t.message}", t)
                RepositoryResult.Error("Error loading document: ${t.message}", t)
            }
        }
    }

    /**
     * Copies a page image into the document's private image storage.
     */
    suspend fun importPageImage(
        docId: String,
        pageId: String,
        sourceImageFile: File
    ): File = withContext(Dispatchers.IO) {
        val imagesDir = getImagesDirectory(docId)
        if (!imagesDir.exists()) {
            imagesDir.mkdirs()
        }
        val targetFile = File(imagesDir, "${pageId}.jpg")
        FileInputStream(sourceImageFile).use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
        targetFile
    }

    /**
     * Cleans up image files in the document's images directory that are no longer referenced by any page.
     */
    suspend fun cleanupUnreferencedImages(document: OcrDocument): Int = withContext(Dispatchers.IO) {
        val imagesDir = getImagesDirectory(document.id)
        if (!imagesDir.exists() || !imagesDir.isDirectory) return@withContext 0

        val referencedNames = document.pages
            .mapNotNull { it.imageInfo?.localUri }
            .map { uriStr ->
                try {
                    if (uriStr.startsWith("file:")) {
                        File(java.net.URI(uriStr).path).name
                    } else {
                        File(uriStr).name
                    }
                } catch (_: Exception) {
                    File(uriStr.substringAfterLast('/')).name
                }
            }
            .toSet()

        var deletedCount = 0
        val files = imagesDir.listFiles() ?: return@withContext 0
        for (f in files) {
            if (f.isFile && !referencedNames.contains(f.name)) {
                if (f.delete()) {
                    deletedCount++
                }
            }
        }
        deletedCount
    }

    /**
     * Deletes a document and all associated files (manifest, backup, images).
     */
    suspend fun deleteDocument(docId: String): Boolean = withContext(Dispatchers.IO) {
        val lock = getDocumentLock(docId)
        lock.withLock {
            val docDir = getDocumentDirectory(docId)
            if (docDir.exists()) {
                docDir.deleteRecursively()
            } else {
                false
            }
        }
    }

    private fun readDocumentFromFile(file: File): OcrDocument? {
        if (!file.exists() || file.length() == 0L) return null
        val content = file.readText(Charsets.UTF_8)
        val json = JSONObject(content)
        return OcrDocument.fromJson(json)
    }

    private fun applySchemaMigration(doc: OcrDocument): OcrDocument {
        if (doc.schemaVersion == CURRENT_SCHEMA_VERSION) {
            return doc
        }
        // Hook for future schema migrations
        return doc.copy(schemaVersion = CURRENT_SCHEMA_VERSION)
    }
}
