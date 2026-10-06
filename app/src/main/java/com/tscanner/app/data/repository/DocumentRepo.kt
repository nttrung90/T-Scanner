package com.tscanner.app.data.repository

import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.core.util.AtomicFile
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.model.DocumentManagementStats
import com.tscanner.app.data.model.FileTypeStat
import com.tscanner.app.data.model.FolderItem
import com.tscanner.app.data.model.ManagedFileItem
import com.tscanner.app.data.model.ManagedFileType
import com.tscanner.app.data.model.SyncStatus
import com.tscanner.app.utils.FileUtils
import com.tscanner.app.utils.SafeFileWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class DocumentRepo private constructor(private val context: Context) {

    private val dataFile = File(context.filesDir, "tscanner_data.json")

    private val memoryDocs = mutableListOf<DocumentItem>()
    private val memoryFolders = mutableListOf<FolderItem>()

    private val _documents = MutableLiveData<List<DocumentItem>>(emptyList())
    val documents: LiveData<List<DocumentItem>> = _documents

    private val _folders = MutableLiveData<List<FolderItem>>(emptyList())
    val folders: LiveData<List<FolderItem>> = _folders

    private val prefs by lazy { context.getSharedPreferences("tscanner_docs_prefs", Context.MODE_PRIVATE) }
    private val localTombstones = mutableSetOf<String>()

    @androidx.annotation.VisibleForTesting
    var backupDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

    @androidx.annotation.VisibleForTesting
    var backupScope: kotlinx.coroutines.CoroutineScope? = null

    @androidx.annotation.VisibleForTesting
    var autoBackupEnabled: Boolean = true

    private fun dispatchCloudBackup(docItem: DocumentItem) {
        if (!autoBackupEnabled) return
        val currentUser = com.tscanner.app.utils.AppAuthManager.getCurrentUser()
        val currentSessionGen = com.tscanner.app.utils.AppAuthManager.getSessionGeneration()
        val scope = backupScope ?: com.tscanner.app.utils.CloudBackupManager.getOrCreateSessionScope()
        com.tscanner.app.utils.CloudBackupManager.enqueueBackupAsync(
            context = context,
            docItem = docItem,
            coroutineScope = scope,
            ioDispatcher = backupDispatcher,
            expectedUserId = currentUser?.id,
            expectedSessionGen = currentSessionGen
        )
    }

    init {
        loadData()
    }

    @Synchronized
    private fun loadData() {
        try {
            localTombstones.clear()
            localTombstones.addAll(prefs.getStringSet("key_local_tombstones", emptySet()) ?: emptySet())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load local tombstones", e)
        }
        var needsCleanup = false
        val backupFile = File(dataFile.parentFile, "${dataFile.name}.bak")
        val exists = dataFile.exists() || backupFile.exists()

        if (exists) {
            try {
                val fileToRead = if (dataFile.exists()) dataFile else backupFile
                val jsonStr = fileToRead.readText(Charsets.UTF_8)
                val root = JSONObject(jsonStr)

                val parsedFolders = mutableListOf<FolderItem>()
                val foldersJson = root.optJSONArray("folders") ?: JSONArray()
                for (i in 0 until foldersJson.length()) {
                    val f = foldersJson.getJSONObject(i)
                    parsedFolders.add(
                        FolderItem(
                            id = f.getString("id"),
                            name = f.getString("name"),
                            createdAt = f.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }

                val parsedDocs = mutableListOf<DocumentItem>()
                val docsJson = root.optJSONArray("documents") ?: JSONArray()
                for (i in 0 until docsJson.length()) {
                    val d = docsJson.getJSONObject(i)
                    val pagesJson = d.optJSONArray("pagePaths") ?: JSONArray()
                    val pages = mutableListOf<String>()
                    for (j in 0 until pagesJson.length()) {
                        pages.add(pagesJson.getString(j))
                    }

                    val pdfPath = if (d.has("pdfPath") && !d.isNull("pdfPath")) d.getString("pdfPath") else null
                    val thumbPath = if (d.has("thumbnailPath") && !d.isNull("thumbnailPath")) d.getString("thumbnailPath") else null

                    // Verify if file physically exists on disk, or if it's a valid cloud document not yet downloaded
                    val pdfExists = pdfPath != null && File(pdfPath).exists()
                    val pagesExist = pages.any { File(it).exists() }
                    val thumbExists = thumbPath != null && File(thumbPath).exists()

                    val isSynced = d.optBoolean("isSynced", false)
                    val driveFileId = if (d.has("driveFileId") && !d.isNull("driveFileId")) d.getString("driveFileId") else null
                    val isCloudOnly = !driveFileId.isNullOrEmpty()

                    val lastSyncedAt = if (d.has("lastSyncedAt") && !d.isNull("lastSyncedAt")) d.optLong("lastSyncedAt") else null
                    val syncStatusStr = d.optString("syncStatus", if (isSynced) "synced" else "local_only")
                    val syncStatus = com.tscanner.app.data.model.SyncStatus.fromId(syncStatusStr)
                    val ownerId = if (d.has("ownerId") && !d.isNull("ownerId")) d.getString("ownerId") else null
                    val revision = d.optLong("contentRevision", 0L)
                    val mimeType = d.optString("mimeType", if (pdfPath != null) "application/pdf" else "image/jpeg")
                    val isConflict = d.optBoolean("isConflict", false)

                    if (pdfExists || pagesExist || thumbExists || isCloudOnly) {
                        parsedDocs.add(
                            DocumentItem(
                                id = d.getString("id"),
                                title = d.getString("title"),
                                pdfPath = pdfPath,
                                thumbnailPath = thumbPath,
                                pagePaths = pages,
                                pageCount = d.optInt("pageCount", 1),
                                sizeBytes = d.optLong("sizeBytes", 0L),
                                createdAt = d.optLong("createdAt", System.currentTimeMillis()),
                                folderId = if (d.has("folderId") && !d.isNull("folderId")) d.getString("folderId") else null,
                                isSynced = isSynced,
                                driveFileId = driveFileId,
                                lastSyncedAt = if (lastSyncedAt != null && lastSyncedAt > 0) lastSyncedAt else null,
                                syncStatus = syncStatus,
                                ownerId = ownerId,
                                contentRevision = revision,
                                mimeType = mimeType,
                                isConflict = isConflict
                            )
                        )
                    } else {
                        // Orphaned document record whose local files no longer exist AND has no cloud backup
                        needsCleanup = true
                    }
                }

                memoryFolders.clear()
                memoryFolders.addAll(parsedFolders)

                memoryDocs.clear()
                memoryDocs.addAll(parsedDocs)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing document catalog JSON; keeping existing state", e)
            }
        }

        memoryDocs.sortByDescending { it.createdAt }

        // Migrate any duplicate physical PDF paths from older app versions
        migrateDuplicateFilePaths()

        if (needsCleanup) {
            saveData()
        }

        publishFolders()
        publishDocuments()
    }

    @Synchronized
    private fun migrateDuplicateFilePaths() {
        val pathCounts = memoryDocs.mapNotNull { it.pdfPath }.groupingBy { it }.eachCount()
        var migrated = false
        val docDir = FileUtils.getDocumentsDir(context)

        for (i in memoryDocs.indices) {
            val doc = memoryDocs[i]
            val path = doc.pdfPath
            if (path != null && (pathCounts[path] ?: 0) > 1) {
                val sourceFile = File(path)
                if (sourceFile.exists()) {
                    val uniqueFile = File(docDir, "doc_${doc.id}.pdf")
                    if (!uniqueFile.exists()) {
                        try {
                            sourceFile.copyTo(uniqueFile, overwrite = false)
                            memoryDocs[i] = doc.copy(pdfPath = uniqueFile.absolutePath)
                            migrated = true
                            Log.i(TAG, "Migrated duplicated path for doc '${doc.title}' to ${uniqueFile.name}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to copy duplicate file for doc ${doc.id}", e)
                        }
                    } else if (path != uniqueFile.absolutePath) {
                        memoryDocs[i] = doc.copy(pdfPath = uniqueFile.absolutePath)
                        migrated = true
                    }
                }
            }
        }

        if (migrated) {
            saveData()
        }
    }

    @Synchronized
    private fun saveData(): Boolean {
        var fos: FileOutputStream? = null
        return try {
            val root = JSONObject()

            val foldersJson = JSONArray()
            memoryFolders.forEach { f ->
                val obj = JSONObject().apply {
                    put("id", f.id)
                    put("name", f.name)
                    put("createdAt", f.createdAt)
                }
                foldersJson.put(obj)
            }
            root.put("folders", foldersJson)

            val docsJson = JSONArray()
            memoryDocs.forEach { d ->
                val obj = JSONObject().apply {
                    put("id", d.id)
                    put("title", d.title)
                    put("pdfPath", d.pdfPath)
                    put("thumbnailPath", d.thumbnailPath)
                    put("pageCount", d.pageCount)
                    put("sizeBytes", d.sizeBytes)
                    put("createdAt", d.createdAt)
                    put("folderId", d.folderId)
                    put("isSynced", d.isSynced)
                    put("driveFileId", d.driveFileId)
                    put("lastSyncedAt", d.lastSyncedAt ?: -1L)
                    put("syncStatus", d.syncStatus.id)
                    put("ownerId", d.ownerId)
                    put("contentRevision", d.contentRevision)
                    put("mimeType", d.mimeType)
                    put("isConflict", d.isConflict)
                    val pagesArr = JSONArray()
                    d.pagePaths.forEach { pagesArr.put(it) }
                    put("pagePaths", pagesArr)
                }
                docsJson.put(obj)
            }
            root.put("documents", docsJson)

            val parent = dataFile.parentFile ?: context.filesDir
            if (!parent.exists()) parent.mkdirs()
            val tempFile = File(parent, "data_tmp_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}.json")
            tempFile.writeText(root.toString(), Charsets.UTF_8)
            val success = SafeFileWriter.commitAtomic(tempFile, dataFile)
            if (!success) {
                try { tempFile.delete() } catch (_: Exception) {}
                Log.e(TAG, "Failed to atomically commit document repository")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save document repository", e)
            false
        }
    }

    private fun publishDocuments() {
        val list = ArrayList(memoryDocs)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _documents.value = list
        } else {
            _documents.postValue(list)
        }
    }

    private fun publishFolders() {
        val list = ArrayList(memoryFolders)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _folders.value = list
        } else {
            _folders.postValue(list)
        }
    }

    @Synchronized
    fun addDocument(doc: DocumentItem): Boolean {
        val currentUserId = com.tscanner.app.utils.AppAuthManager.getCurrentUser()?.id
        val targetDoc = if (doc.ownerId == null && currentUserId != null) {
            doc.copy(ownerId = currentUserId)
        } else {
            doc
        }
        val backupDocs = ArrayList(memoryDocs)
        val existingIndex = memoryDocs.indexOfFirst { it.id == targetDoc.id }
        if (existingIndex != -1) {
            memoryDocs[existingIndex] = targetDoc
        } else {
            memoryDocs.add(0, targetDoc)
        }
        val saveSuccess = saveData()
        if (!saveSuccess) {
            memoryDocs.clear()
            memoryDocs.addAll(backupDocs)
            Log.e(TAG, "Failed to persist document to storage, rolled back memory docs")
            return false
        }
        publishDocuments()

        // Auto-backup to Google Drive if user is VIP
        if (targetDoc.syncStatus != com.tscanner.app.data.model.SyncStatus.SYNCED &&
            com.tscanner.app.utils.AppAuthManager.isUserVip() &&
            targetDoc.pdfPath != null
        ) {
            dispatchCloudBackup(targetDoc)
        }
        return true
    }

    @Synchronized
    fun deleteDocument(docId: String, userId: String? = null): Boolean {
        val index = memoryDocs.indexOfFirst { it.id == docId }
        if (index != -1) {
            val doc = memoryDocs[index]
            val hasAccess = if (userId == null) {
                doc.ownerId == null
            } else {
                doc.ownerId == null || doc.ownerId == userId
            }
            if (!hasAccess) {
                Log.w(TAG, "Cannot delete document $docId owned by ${doc.ownerId} by user $userId")
                return false
            }
            memoryDocs.removeAt(index)

            // V11c: Record local tombstone for deleted document so subsequent Drive catalog sync doesn't resurrect it
            doc.driveFileId?.let { driveId ->
                localTombstones.add(driveId)
            }
            localTombstones.add(docId)
            persistTombstones()

            // Immediately persist changes to disk
            val saved = saveData()
            if (!saved) {
                memoryDocs.add(index, doc)
                return false
            }

            // Delete associated physical files safely ONLY if no other document in memoryDocs references them
            try {
                doc.pdfPath?.let { path ->
                    val stillReferenced = memoryDocs.any { it.pdfPath == path }
                    if (!stillReferenced) {
                        val f = File(path)
                        if (f.exists()) f.delete()
                    }
                }
                doc.thumbnailPath?.let { path ->
                    val stillReferenced = memoryDocs.any { it.thumbnailPath == path }
                    if (!stillReferenced) {
                        val f = File(path)
                        if (f.exists()) f.delete()
                    }
                }
                doc.pagePaths.forEach { path ->
                    val stillReferenced = memoryDocs.any { other -> other.pagePaths.contains(path) }
                    if (!stillReferenced) {
                        val f = File(path)
                        if (f.exists()) f.delete()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            publishDocuments()
            return true
        }
        return false
    }

    @Synchronized
    fun isLocalTombstoned(driveFileIdOrDocId: String): Boolean {
        return localTombstones.contains(driveFileIdOrDocId)
    }

    @Synchronized
    fun addLocalTombstone(driveFileIdOrDocId: String) {
        localTombstones.add(driveFileIdOrDocId)
        persistTombstones()
    }

    @Synchronized
    fun clearLocalTombstone(driveFileIdOrDocId: String) {
        localTombstones.remove(driveFileIdOrDocId)
        persistTombstones()
    }

    @Synchronized
    fun getLocalTombstones(): Set<String> = HashSet(localTombstones)

    private fun persistTombstones() {
        try {
            prefs.edit().putStringSet("key_local_tombstones", HashSet(localTombstones)).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist local tombstones", e)
        }
    }

    @Synchronized
    fun renameDocument(docId: String, newTitle: String, userId: String? = null): Boolean {
        val index = memoryDocs.indexOfFirst { it.id == docId }
        if (index != -1) {
            val doc = memoryDocs[index]
            val hasAccess = if (userId == null) {
                doc.ownerId == null
            } else {
                doc.ownerId == null || doc.ownerId == userId
            }
            if (!hasAccess) {
                Log.w(TAG, "Cannot rename document $docId owned by ${doc.ownerId} by user $userId")
                return false
            }
            // V11b: Bumping contentRevision and resetting sync status so that remote Drive metadata gets updated
            val updated = doc.copy(
                title = newTitle,
                contentRevision = doc.contentRevision + 1L,
                syncStatus = SyncStatus.LOCAL_ONLY,
                isSynced = false
            )
            memoryDocs[index] = updated
            val saved = saveData()
            if (saved) {
                publishDocuments()
                if (com.tscanner.app.utils.AppAuthManager.isUserVip() && updated.pdfPath != null) {
                    dispatchCloudBackup(updated)
                }
                return true
            } else {
                memoryDocs[index] = doc
                return false
            }
        }
        return false
    }

    @Synchronized
    fun moveDocumentToFolder(docId: String, folderId: String?, userId: String? = null): Boolean {
        val index = memoryDocs.indexOfFirst { it.id == docId }
        if (index != -1) {
            val doc = memoryDocs[index]
            val hasAccess = if (userId == null) {
                doc.ownerId == null
            } else {
                doc.ownerId == null || doc.ownerId == userId
            }
            if (!hasAccess) {
                Log.w(TAG, "Cannot move document $docId owned by ${doc.ownerId} by user $userId")
                return false
            }
            val updated = doc.copy(folderId = folderId)
            memoryDocs[index] = updated
            val saved = saveData()
            if (saved) {
                publishDocuments()
                return true
            } else {
                memoryDocs[index] = doc
                return false
            }
        }
        return false
    }

    @Synchronized
    fun addFolder(name: String): FolderItem {
        val folder = FolderItem(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            createdAt = System.currentTimeMillis()
        )
        memoryFolders.add(folder)
        saveData()
        publishFolders()
        return folder
    }

    @Synchronized
    fun deleteFolder(folderId: String) {
        memoryFolders.removeAll { it.id == folderId }
        memoryDocs.forEachIndexed { i, d ->
            if (d.folderId == folderId) {
                memoryDocs[i] = d.copy(folderId = null)
            }
        }
        saveData()
        publishFolders()
        publishDocuments()
    }

    @Synchronized
    fun getDocument(docId: String): DocumentItem? {
        return memoryDocs.find { it.id == docId }
    }

    @Synchronized
    fun getDocumentByPdfPath(path: String): DocumentItem? {
        return memoryDocs.find { it.pdfPath == path }
    }

    /**
     * Retrieves a document checking that [userId] has access to it.
     * Accessible if document is owned by [userId], or if [allowGuest] is true and document has no owner.
     */
    @Synchronized
    fun getDocumentForUser(docId: String, userId: String?, allowGuest: Boolean = true): DocumentItem? {
        val doc = memoryDocs.find { it.id == docId } ?: return null
        val hasAccess = if (userId == null) {
            doc.ownerId == null
        } else {
            doc.ownerId == userId || (allowGuest && doc.ownerId == null)
        }
        return if (hasAccess) doc else null
    }

    /**
     * Returns documents scoped to [userId].
     * If [userId] is null, returns only guest (unowned) documents.
     * If [userId] is provided, returns documents owned by [userId] (plus guest docs if [includeGuest] is true).
     */
    @Synchronized
    fun getDocumentsForUser(userId: String?, includeGuest: Boolean = true): List<DocumentItem> {
        return memoryDocs.filter { doc ->
            if (userId == null) {
                doc.ownerId == null
            } else {
                doc.ownerId == userId || (includeGuest && doc.ownerId == null)
            }
        }
    }

    /**
     * Permanently assigns an owner to a document if it does not already have a different owner.
     * Returns true if ownership was assigned and saved.
     */
    @Synchronized
    fun setDocumentOwner(docId: String, newOwnerId: String): Boolean {
        if (newOwnerId.isBlank()) return false
        val index = memoryDocs.indexOfFirst { it.id == docId }
        if (index == -1) return false
        val doc = memoryDocs[index]
        if (doc.ownerId != null && doc.ownerId != newOwnerId) {
            Log.w(TAG, "Cannot reassign document ${doc.id} owned by ${doc.ownerId} to $newOwnerId")
            return false
        }
        if (doc.ownerId == newOwnerId) return true
        memoryDocs[index] = doc.copy(ownerId = newOwnerId)
        val saved = saveData()
        if (saved) {
            publishDocuments()
            return true
        } else {
            memoryDocs[index] = doc
            return false
        }
    }

    /**
     * Claims all unowned (guest) documents for [targetOwnerId].
     * Documents already owned by any user are strictly preserved and untouched.
     * Returns the number of documents claimed.
     */
    @Synchronized
    fun claimGuestDocuments(targetOwnerId: String): Int {
        if (targetOwnerId.isBlank()) return 0
        val originalDocs = ArrayList(memoryDocs)
        var claimedCount = 0
        for (i in memoryDocs.indices) {
            val doc = memoryDocs[i]
            if (doc.ownerId == null) {
                memoryDocs[i] = doc.copy(ownerId = targetOwnerId)
                claimedCount++
            }
        }
        if (claimedCount > 0) {
            val saved = saveData()
            if (saved) {
                publishDocuments()
                Log.i(TAG, "Claimed $claimedCount guest document(s) for user $targetOwnerId")
                return claimedCount
            } else {
                memoryDocs.clear()
                memoryDocs.addAll(originalDocs)
                return 0
            }
        }
        return 0
    }

    @Synchronized
    fun getRecentDocuments(limit: Int = 10, userId: String? = null): List<DocumentItem> {
        val baseList = getDocumentsForUser(userId, includeGuest = false)
        return baseList.take(limit)
    }

    @Synchronized
    fun getDocumentsInFolder(folderId: String?, userId: String? = null): List<DocumentItem> {
        val baseList = getDocumentsForUser(userId, includeGuest = false)
        return baseList.filter { it.folderId == folderId }
    }

    @Synchronized
    fun searchDocuments(query: String, userId: String? = null): List<DocumentItem> {
        val baseList = getDocumentsForUser(userId, includeGuest = false)
        if (query.isBlank()) return ArrayList(baseList)
        return baseList.filter { it.title.contains(query, ignoreCase = true) }
    }

    @Synchronized
    fun updateSyncStatus(
        docId: String,
        status: com.tscanner.app.data.model.SyncStatus,
        driveFileId: String? = null,
        syncedAt: Long? = null,
        clearDriveFileId: Boolean = false
    ) {
        val index = memoryDocs.indexOfFirst { it.id == docId }
        if (index != -1) {
            val doc = memoryDocs[index]
            val isSynced = (status == com.tscanner.app.data.model.SyncStatus.SYNCED)
            val newDriveId = if (clearDriveFileId) null else (driveFileId ?: doc.driveFileId)
            memoryDocs[index] = doc.copy(
                syncStatus = status,
                driveFileId = newDriveId,
                isSynced = isSynced,
                lastSyncedAt = if (isSynced) (syncedAt ?: System.currentTimeMillis()) else doc.lastSyncedAt
            )
            saveData()
            publishDocuments()
        }
    }

    /**
     * Atomically commits a sync status update using Compare-And-Swap (CAS).
     * The update will only succeed if:
     * 1. The document exists in memoryDocs.
     * 2. The document's ownerId matches [expectedOwnerId] (or both null).
     * 3. The document's contentRevision matches [expectedRevision].
     *
     * If the document was modified (contentRevision changed) while the upload was in-flight,
     * this call returns false and does NOT overwrite the newer dirty state.
     */
    @Synchronized
    fun updateSyncStatusCas(
        docId: String,
        expectedOwnerId: String?,
        expectedRevision: Long,
        status: com.tscanner.app.data.model.SyncStatus,
        driveFileId: String? = null,
        syncedAt: Long? = null,
        clearDriveFileId: Boolean = false
    ): Boolean {
        val index = memoryDocs.indexOfFirst { it.id == docId }
        if (index == -1) {
            Log.w(TAG, "updateSyncStatusCas: Document $docId not found")
            return false
        }
        val doc = memoryDocs[index]

        // 1. Verify owner matches
        if (doc.ownerId != expectedOwnerId) {
            Log.w(TAG, "updateSyncStatusCas: Owner mismatch for $docId. Expected '$expectedOwnerId' but was '${doc.ownerId}'")
            return false
        }

        // 2. Verify revision matches (CAS)
        if (expectedRevision != -1L && doc.contentRevision != expectedRevision) {
            Log.w(TAG, "updateSyncStatusCas: Revision mismatch for $docId. Expected rev $expectedRevision but was rev ${doc.contentRevision}. Newer local edits exist!")
            return false
        }

        val isSynced = (status == com.tscanner.app.data.model.SyncStatus.SYNCED)
        val newDriveId = if (clearDriveFileId) null else (driveFileId ?: doc.driveFileId)
        val remoteBaseline = doc.remoteModifiedTime ?: doc.lastSyncedAt
        val updated = doc.copy(
            syncStatus = status,
            driveFileId = newDriveId,
            isSynced = isSynced,
            lastSyncedAt = if (isSynced) (syncedAt ?: System.currentTimeMillis()) else doc.lastSyncedAt,
            remoteModifiedTime = remoteBaseline
        )
        memoryDocs[index] = updated
        val saved = saveData()
        if (saved) {
            publishDocuments()
            return true
        } else {
            memoryDocs[index] = doc
            return false
        }
    }

    @Synchronized
    fun markDocumentModified(docId: String, newSizeBytes: Long? = null, newThumbnailPath: String? = null, userId: String? = null): Boolean {
        val index = memoryDocs.indexOfFirst { it.id == docId }
        if (index == -1) return false
        val doc = memoryDocs[index]
        val hasAccess = if (userId == null) {
            doc.ownerId == null
        } else {
            doc.ownerId == null || doc.ownerId == userId
        }
        if (!hasAccess) {
            Log.w(TAG, "Cannot modify document $docId owned by ${doc.ownerId} by user $userId")
            return false
        }
        val updated = doc.copy(
            isSynced = false,
            syncStatus = com.tscanner.app.data.model.SyncStatus.LOCAL_ONLY,
            contentRevision = doc.contentRevision + 1L,
            sizeBytes = newSizeBytes ?: doc.sizeBytes,
            thumbnailPath = newThumbnailPath ?: doc.thumbnailPath
        )
        memoryDocs[index] = updated
        val saved = saveData()
        if (saved) {
            publishDocuments()
            if (com.tscanner.app.utils.AppAuthManager.isUserVip() && updated.pdfPath != null) {
                dispatchCloudBackup(updated)
            }
            return true
        } else {
            memoryDocs[index] = doc
            return false
        }
    }

    @Synchronized
    fun updateDocumentPdfPath(
        docId: String,
        newPath: String,
        fileSize: Long,
        thumbPath: String? = null,
        pageCount: Int? = null,
        expectedOwnerId: String? = null,
        expectedRevision: Long? = null
    ): Boolean {
        val index = memoryDocs.indexOfFirst { it.id == docId }
        if (index != -1) {
            val file = File(newPath)
            if (!file.exists() || file.length() == 0L) {
                return false
            }
            val doc = memoryDocs[index]
            if (expectedOwnerId != null && doc.ownerId != expectedOwnerId) {
                Log.w(TAG, "updateDocumentPdfPath: Owner mismatch for $docId (expected=$expectedOwnerId, current=${doc.ownerId})")
                return false
            }
            if (expectedRevision != null && expectedRevision != -1L && doc.contentRevision != expectedRevision) {
                Log.w(TAG, "updateDocumentPdfPath: Revision mismatch for $docId (expected=$expectedRevision, current=${doc.contentRevision})")
                return false
            }
            // V11a: Resolve real page count from PDF or provided count
            val resolvedPageCount = pageCount?.takeIf { it > 0 }
                ?: SafeFileWriter.getPdfPageCount(file).takeIf { it > 0 }
                ?: doc.pageCount.takeIf { it > 0 }
                ?: 1
            val updated = doc.copy(
                pdfPath = newPath,
                sizeBytes = fileSize,
                thumbnailPath = thumbPath ?: doc.thumbnailPath,
                pageCount = resolvedPageCount
            )
            memoryDocs[index] = updated
            val saved = saveData()
            if (saved) {
                publishDocuments()
                return true
            } else {
                memoryDocs[index] = doc
                return false
            }
        }
        return false
    }

data class UpsertResult(
    val document: DocumentItem,
    val isNew: Boolean
)

    /**
     * Atomically reconciles or inserts a remote document from Google Drive.
     * Prevents duplicate entries on concurrent catalog imports (V06).
     * Compares remote modifiedTime with local lastSyncedAt and detects dirty conflicts (V05a).
     * Checks local tombstones to prevent resurrecting deleted documents (V11c).
     * Returns the persisted UpsertResult, or null if saveData failed.
     */
    @Synchronized
    fun upsertFromDrive(
        ownerId: String,
        driveFileId: String,
        title: String,
        sizeBytes: Long,
        modifiedTime: Long
    ): UpsertResult? {
        // V11c: Check local tombstones - if user explicitly deleted this file locally,
        // do not resurrect it from Google Drive catalog!
        if (isLocalTombstoned(driveFileId)) {
            Log.d(TAG, "Skipping Drive file $driveFileId because it was deleted locally (tombstoned)")
            return null
        }

        val cleanTitle = if (title.endsWith(".pdf", ignoreCase = true)) title.removeSuffix(".pdf") else title
        val existingIndex = memoryDocs.indexOfFirst {
            val ownerMatch = (it.ownerId == null || it.ownerId == ownerId)
            ownerMatch && it.driveFileId == driveFileId
        }

        if (existingIndex != -1) {
            val existing = memoryDocs[existingIndex]
            val remoteBaseline = existing.remoteModifiedTime ?: existing.lastSyncedAt ?: 0L
            val isRemoteNewer = modifiedTime > remoteBaseline

            val updatedDoc = if (isRemoteNewer) {
                // Check if local document is dirty (V05a)
                val isLocalDirty = existing.syncStatus == SyncStatus.LOCAL_ONLY ||
                        existing.syncStatus == SyncStatus.FAILED ||
                        existing.syncStatus == SyncStatus.SYNCING
                if (isLocalDirty) {
                    Log.w(TAG, "Conflict detected for doc '${existing.title}' ($driveFileId): local edits exist while remote is newer.")
                    existing.copy(
                        isConflict = true,
                        ownerId = ownerId
                    )
                } else {
                    // Local is clean, remote has newer version: invalidate local catalog cache for lazy re-download,
                    // but DO NOT delete existing physical file so offline readers retain access and rollback is safe.
                    Log.i(TAG, "Remote is newer for doc '${existing.title}' ($driveFileId). Refreshing remote metadata.")
                    existing.copy(
                        title = cleanTitle,
                        pdfPath = null,
                        thumbnailPath = null,
                        sizeBytes = sizeBytes,
                        lastSyncedAt = modifiedTime,
                        remoteModifiedTime = modifiedTime,
                        isSynced = true,
                        syncStatus = SyncStatus.SYNCED,
                        ownerId = ownerId,
                        isConflict = false
                    )
                }
            } else {
                // Remote not newer, ensure ownerId is attached
                if (existing.ownerId == null) existing.copy(ownerId = ownerId) else existing
            }

            if (updatedDoc !== existing) {
                memoryDocs[existingIndex] = updatedDoc
                val saved = saveData()
                if (saved) {
                    publishDocuments()
                    return UpsertResult(updatedDoc, isNew = false)
                } else {
                    // Rollback
                    memoryDocs[existingIndex] = existing
                    return null
                }
            }
            return UpsertResult(existing, isNew = false)
        } else {
            // Document doesn't exist locally: create new lazy DocumentItem with unknown page count (V11a)
            val newDoc = DocumentItem(
                id = java.util.UUID.randomUUID().toString(),
                title = cleanTitle,
                pdfPath = null,
                thumbnailPath = null,
                pagePaths = emptyList(),
                pageCount = 0,
                sizeBytes = sizeBytes,
                createdAt = modifiedTime,
                isSynced = true,
                driveFileId = driveFileId,
                lastSyncedAt = modifiedTime,
                remoteModifiedTime = modifiedTime,
                syncStatus = SyncStatus.SYNCED,
                ownerId = ownerId,
                isConflict = false
            )
            memoryDocs.add(0, newDoc)
            val saved = saveData()
            if (saved) {
                publishDocuments()
                return UpsertResult(newDoc, isNew = true)
            } else {
                // Rollback
                memoryDocs.removeAt(0)
                return null
            }
        }
    }

    @Synchronized
    fun getUnsyncedDocuments(userId: String? = null): List<DocumentItem> {
        return memoryDocs.filter { doc ->
            val ownerMatches = if (userId == null) true else (doc.ownerId == userId || doc.ownerId == null)
            ownerMatches && !doc.isSynced && !doc.isConflict && doc.pdfPath != null && File(doc.pdfPath).exists()
        }
    }

    @Synchronized
    fun getStorageStats(userId: String? = null): StorageStats {
        val userDocs = getDocumentsForUser(userId, includeGuest = (userId == null))
        val docCount = userDocs.size
        val pdfCount = userDocs.count { it.pdfPath != null }
        val totalBytes = userDocs.sumOf { it.sizeBytes }

        return StorageStats(
            totalDocuments = docCount,
            totalPdfs = pdfCount,
            totalSizeBytes = totalBytes
        )
    }

    private fun getDirSize(dir: File): Long {
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) getDirSize(file) else file.length()
        }
        return size
    }

    data class StorageStats(
        val totalDocuments: Int,
        val totalPdfs: Int,
        val totalSizeBytes: Long
    )

    @Synchronized
    fun getAllManagedFiles(userId: String? = null): List<ManagedFileItem> {
        val fileMap = mutableMapOf<String, ManagedFileItem>()
        val otherUserDocPaths = if (userId != null) {
            memoryDocs.filter { it.ownerId != null && it.ownerId != userId }.mapNotNull { it.pdfPath }.toSet()
        } else {
            memoryDocs.filter { it.ownerId != null }.mapNotNull { it.pdfPath }.toSet()
        }

        fun addFileIfValid(f: File) {
            if (!f.exists() || f.isDirectory || f.length() == 0L) return
            if (f.name.startsWith("thumb_") || f.name.startsWith("page_") || f.name.startsWith("pdf_page_")) return
            val canonical = try { f.canonicalPath } catch (e: Exception) { f.absolutePath }
            if (otherUserDocPaths.contains(canonical) || otherUserDocPaths.contains(f.absolutePath)) return
            if (fileMap.containsKey(canonical)) return

            val ext = f.extension.lowercase()
            val type = when (ext) {
                "pdf" -> ManagedFileType.PDF
                "doc", "docx" -> ManagedFileType.WORD
                "csv", "xls", "xlsx" -> ManagedFileType.EXCEL
                "html", "ppt", "pptx" -> ManagedFileType.PPT
                "jpg", "jpeg", "png", "webp" -> ManagedFileType.IMAGE
                else -> ManagedFileType.OTHER
            }

            fileMap[canonical] = ManagedFileItem(
                file = f,
                name = f.name,
                path = f.absolutePath,
                sizeBytes = f.length(),
                lastModified = f.lastModified(),
                fileType = type
            )
        }

        // 1. Scan app's documents directory (Saved official PDFs)
        val docDir = FileUtils.getDocumentsDir(context)
        docDir.listFiles()?.forEach { addFileIfValid(it) }

        // 2. Scan app's exports directory (Word, Excel, PPT, Long Image exports)
        val exportDir = FileUtils.getExportsDir(context)
        exportDir.listFiles()?.forEach { addFileIfValid(it) }

        // 3. Also incorporate any files explicitly registered in memoryDocs for this user/guest
        val relevantDocs = getDocumentsForUser(userId, includeGuest = (userId == null))
        for (doc in relevantDocs) {
            doc.pdfPath?.let { addFileIfValid(File(it)) }
        }

        return fileMap.values.sortedByDescending { it.lastModified }
    }

    fun calculateManagementStats(files: List<ManagedFileItem>): DocumentManagementStats {
        val totalFiles = files.size
        val totalBytes = files.sumOf { it.sizeBytes }

        val typeCategories = listOf(
            ManagedFileType.PDF,
            ManagedFileType.WORD,
            ManagedFileType.EXCEL,
            ManagedFileType.PPT,
            ManagedFileType.IMAGE,
            ManagedFileType.OTHER
        )

        val typeStats = typeCategories.map { type ->
            val matching = files.filter { it.fileType == type }
            FileTypeStat(
                type = type,
                count = matching.size,
                totalSizeBytes = matching.sumOf { it.sizeBytes }
            )
        }

        return DocumentManagementStats(
            totalFiles = totalFiles,
            totalSizeBytes = totalBytes,
            typeStats = typeStats
        )
    }

    @Synchronized
    fun deleteManagedFile(file: File): Boolean {
        val path = file.absolutePath
        var fileDeleted = false
        try {
            fileDeleted = if (file.exists()) file.delete() else true
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (!fileDeleted) {
            return false
        }

        val originalDocs = ArrayList(memoryDocs)
        var repoModified = false
        val toRemove = mutableListOf<DocumentItem>()
        for (i in memoryDocs.indices) {
            val doc = memoryDocs[i]
            if (doc.pdfPath == path) {
                doc.thumbnailPath?.let { thumb ->
                    val stillReferenced = memoryDocs.any { other -> other.id != doc.id && other.thumbnailPath == thumb }
                    if (!stillReferenced) File(thumb).delete()
                }
                toRemove.add(doc)
                repoModified = true
            } else if (doc.pagePaths.contains(path)) {
                val newPages = doc.pagePaths.filter { it != path }
                if (newPages.isEmpty() && doc.pdfPath == null) {
                    doc.thumbnailPath?.let { thumb ->
                        val stillReferenced = memoryDocs.any { other -> other.id != doc.id && other.thumbnailPath == thumb }
                        if (!stillReferenced) File(thumb).delete()
                    }
                    toRemove.add(doc)
                } else {
                    memoryDocs[i] = doc.copy(pagePaths = newPages, pageCount = maxOf(1, newPages.size))
                }
                repoModified = true
            }
        }

        if (toRemove.isNotEmpty()) {
            memoryDocs.removeAll(toRemove)
            toRemove.forEach { doc ->
                doc.driveFileId?.let { driveId ->
                    localTombstones.add(driveId)
                }
                localTombstones.add(doc.id)
            }
            persistTombstones()
        }

        if (repoModified) {
            val saved = saveData()
            if (saved) {
                publishDocuments()
            } else {
                memoryDocs.clear()
                memoryDocs.addAll(originalDocs)
                return false
            }
        }

        return true
    }

    /**
     * Migrates ownership of all documents belonging to [legacyOwnerId] to [canonicalOwnerId].
     * Atomically persists changes to storage and notifies observers.
     * Returns the number of documents migrated.
     */
    @Synchronized
    fun migrateOwnerId(legacyOwnerId: String, canonicalOwnerId: String): Int {
        if (legacyOwnerId.isBlank() || canonicalOwnerId.isBlank() || legacyOwnerId == canonicalOwnerId) {
            return 0
        }
        val originalDocs = ArrayList(memoryDocs)
        var migratedCount = 0
        for (i in memoryDocs.indices) {
            val doc = memoryDocs[i]
            if (doc.ownerId == legacyOwnerId) {
                memoryDocs[i] = doc.copy(ownerId = canonicalOwnerId)
                migratedCount++
            }
        }
        if (migratedCount > 0) {
            val saved = saveData()
            if (saved) {
                publishDocuments()
            } else {
                memoryDocs.clear()
                memoryDocs.addAll(originalDocs)
                return 0
            }
        }
        return migratedCount
    }

    companion object {
        private const val TAG = "DocumentRepo"

        @Volatile
        private var INSTANCE: DocumentRepo? = null

        fun getInstance(context: Context): DocumentRepo {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DocumentRepo(context.applicationContext).also { INSTANCE = it }
            }
        }

        @androidx.annotation.VisibleForTesting
        fun resetInstanceForTesting() {
            INSTANCE = null
        }
    }
}
