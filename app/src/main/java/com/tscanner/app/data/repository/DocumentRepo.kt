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
import com.tscanner.app.utils.FileUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class DocumentRepo private constructor(private val context: Context) {

    private val dataFile = File(context.filesDir, "tscanner_data.json")
    private val atomicDataFile = AtomicFile(dataFile)

    private val memoryDocs = mutableListOf<DocumentItem>()
    private val memoryFolders = mutableListOf<FolderItem>()

    private val _documents = MutableLiveData<List<DocumentItem>>(emptyList())
    val documents: LiveData<List<DocumentItem>> = _documents

    private val _folders = MutableLiveData<List<FolderItem>>(emptyList())
    val folders: LiveData<List<FolderItem>> = _folders

    init {
        loadData()
    }

    @Synchronized
    private fun loadData() {
        var needsCleanup = false
        val backupFile = File(dataFile.parentFile, "${dataFile.name}.bak")
        val exists = dataFile.exists() || backupFile.exists()

        if (exists) {
            try {
                val jsonStr = atomicDataFile.openRead().use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                }
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
                                mimeType = mimeType
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
    private fun saveData() {
        var fos: FileOutputStream? = null
        try {
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
                    val pagesArr = JSONArray()
                    d.pagePaths.forEach { pagesArr.put(it) }
                    put("pagePaths", pagesArr)
                }
                docsJson.put(obj)
            }
            root.put("documents", docsJson)

            fos = atomicDataFile.startWrite()
            fos.write(root.toString().toByteArray(Charsets.UTF_8))
            atomicDataFile.finishWrite(fos)
        } catch (e: Exception) {
            if (fos != null) {
                atomicDataFile.failWrite(fos)
            }
            Log.e(TAG, "Failed to atomically save document repository", e)
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
    fun addDocument(doc: DocumentItem) {
        val existingIndex = memoryDocs.indexOfFirst { it.id == doc.id }
        if (existingIndex != -1) {
            memoryDocs[existingIndex] = doc
        } else {
            memoryDocs.add(0, doc)
        }
        saveData()
        publishDocuments()

        // Auto-backup to Google Drive if user is VIP
        if (doc.syncStatus != com.tscanner.app.data.model.SyncStatus.SYNCED &&
            com.tscanner.app.utils.AppAuthManager.isUserVip() &&
            doc.pdfPath != null
        ) {
            com.tscanner.app.utils.CloudBackupManager.enqueueBackup(context, doc)
        }
    }

    @Synchronized
    fun deleteDocument(docId: String): Boolean {
        val index = memoryDocs.indexOfFirst { it.id == docId }
        if (index != -1) {
            val doc = memoryDocs.removeAt(index)

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

            // Immediately persist changes to disk
            saveData()
            publishDocuments()
            return true
        }
        return false
    }

    @Synchronized
    fun renameDocument(docId: String, newTitle: String) {
        val index = memoryDocs.indexOfFirst { it.id == docId }
        if (index != -1) {
            val doc = memoryDocs[index]
            memoryDocs[index] = doc.copy(title = newTitle)
            saveData()
            publishDocuments()
        }
    }

    @Synchronized
    fun moveDocumentToFolder(docId: String, folderId: String?) {
        val index = memoryDocs.indexOfFirst { it.id == docId }
        if (index != -1) {
            val doc = memoryDocs[index]
            memoryDocs[index] = doc.copy(folderId = folderId)
            saveData()
            publishDocuments()
        }
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
    fun getRecentDocuments(limit: Int = 10): List<DocumentItem> {
        return memoryDocs.take(limit)
    }

    @Synchronized
    fun getDocumentsInFolder(folderId: String?): List<DocumentItem> {
        return memoryDocs.filter { it.folderId == folderId }
    }

    @Synchronized
    fun searchDocuments(query: String): List<DocumentItem> {
        if (query.isBlank()) return ArrayList(memoryDocs)
        return memoryDocs.filter { it.title.contains(query, ignoreCase = true) }
    }

    @Synchronized
    fun updateSyncStatus(
        docId: String,
        status: com.tscanner.app.data.model.SyncStatus,
        driveFileId: String? = null,
        syncedAt: Long? = null
    ) {
        val index = memoryDocs.indexOfFirst { it.id == docId }
        if (index != -1) {
            val doc = memoryDocs[index]
            val isSynced = (status == com.tscanner.app.data.model.SyncStatus.SYNCED)
            memoryDocs[index] = doc.copy(
                syncStatus = status,
                driveFileId = driveFileId ?: doc.driveFileId,
                isSynced = isSynced || doc.isSynced,
                lastSyncedAt = if (isSynced) (syncedAt ?: System.currentTimeMillis()) else doc.lastSyncedAt
            )
            saveData()
            publishDocuments()
        }
    }

    @Synchronized
    fun markDocumentModified(docId: String, newSizeBytes: Long? = null, newThumbnailPath: String? = null) {
        val index = memoryDocs.indexOfFirst { it.id == docId }
        if (index != -1) {
            val doc = memoryDocs[index]
            val updated = doc.copy(
                isSynced = false,
                syncStatus = com.tscanner.app.data.model.SyncStatus.LOCAL_ONLY,
                contentRevision = doc.contentRevision + 1L,
                sizeBytes = newSizeBytes ?: doc.sizeBytes,
                thumbnailPath = newThumbnailPath ?: doc.thumbnailPath
            )
            memoryDocs[index] = updated
            saveData()
            publishDocuments()

            if (com.tscanner.app.utils.AppAuthManager.isUserVip() && updated.pdfPath != null) {
                com.tscanner.app.utils.CloudBackupManager.enqueueBackup(context, updated)
            }
        }
    }

    @Synchronized
    fun updateDocumentPdfPath(docId: String, newPath: String, fileSize: Long, thumbPath: String? = null) {
        val index = memoryDocs.indexOfFirst { it.id == docId }
        if (index != -1) {
            val doc = memoryDocs[index]
            memoryDocs[index] = doc.copy(
                pdfPath = newPath,
                sizeBytes = fileSize,
                thumbnailPath = thumbPath ?: doc.thumbnailPath
            )
            saveData()
            publishDocuments()
        }
    }

    @Synchronized
    fun getUnsyncedDocuments(): List<DocumentItem> {
        return memoryDocs.filter { !it.isSynced && it.pdfPath != null && File(it.pdfPath).exists() }
    }

    @Synchronized
    fun getStorageStats(): StorageStats {
        val docCount = memoryDocs.size
        val pdfCount = memoryDocs.count { it.pdfPath != null }
        var totalBytes = memoryDocs.sumOf { it.sizeBytes }

        // calculate actual directory sizes
        val docDir = FileUtils.getDocumentsDir(context)
        val imgDir = FileUtils.getImagesDir(context)
        val exportDir = FileUtils.getExportsDir(context)

        totalBytes = maxOf(totalBytes, getDirSize(docDir) + getDirSize(imgDir) + getDirSize(exportDir))

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
    fun getAllManagedFiles(): List<ManagedFileItem> {
        val fileMap = mutableMapOf<String, ManagedFileItem>()

        fun addFileIfValid(f: File) {
            if (!f.exists() || f.isDirectory || f.length() == 0L) return
            if (f.name.startsWith("thumb_") || f.name.startsWith("page_") || f.name.startsWith("pdf_page_")) return
            val canonical = try { f.canonicalPath } catch (e: Exception) { f.absolutePath }
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

        // 3. Also incorporate any files explicitly registered in memoryDocs
        for (doc in memoryDocs) {
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
        }

        if (repoModified) {
            saveData()
            publishDocuments()
        }

        return true
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
    }
}
