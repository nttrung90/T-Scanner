package com.tscanner.app.data.repository

import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import com.tscanner.app.data.model.PostScanSessionDraft
import com.tscanner.app.ui.editor.model.PageEditState
import com.tscanner.app.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Quản lý vòng đời phiên chỉnh sửa sau quét và lưu trữ an toàn trong bộ nhớ nội bộ.
 * Phân chia rõ ràng giữa dữ liệu bản nháp bền vững (filesDir) và ảnh xem trước tạm thời (cacheDir).
 */
class PostScanSessionRepository private constructor(private val context: Context) {

    private val baseDraftsDir = File(context.filesDir, "draft_sessions")
    private val basePreviewsDir = File(context.cacheDir, "editor_previews")

    init {
        if (!baseDraftsDir.exists()) baseDraftsDir.mkdirs()
        if (!basePreviewsDir.exists()) basePreviewsDir.mkdirs()
    }

    fun getSessionDir(sessionId: String): File {
        val dir = File(baseDraftsDir, sessionId)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getRawPagesDir(sessionId: String): File {
        val dir = File(getSessionDir(sessionId), "raw_pages")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getProcessedPagesDir(sessionId: String): File {
        val dir = File(getSessionDir(sessionId), "processed_pages")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getPreviewDir(sessionId: String): File {
        val dir = File(basePreviewsDir, sessionId)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getMetadataFile(sessionId: String): File {
        return File(getSessionDir(sessionId), "session_metadata.json")
    }

    private val sessionLocks = ConcurrentHashMap<String, Mutex>()
    private val latestPersistedRevision = ConcurrentHashMap<String, Long>()
    private val closedSessions = ConcurrentHashMap.newKeySet<String>()

    private fun getSessionLock(sessionId: String): Mutex {
        return sessionLocks.computeIfAbsent(sessionId) { Mutex() }
    }

    /**
     * Khởi tạo phiên mới: Sao chép ảnh đầu vào từ ML Kit sang thư mục raw_pages nội bộ an toàn
     * và ghi lại bản nháp ban đầu.
     */
    suspend fun initializeSession(
        sessionId: String,
        sourceImagePaths: List<String>,
        documentTitle: String? = null
    ): PostScanSessionDraft = withContext(Dispatchers.IO) {
        closedSessions.remove(sessionId)
        val rawDir = getRawPagesDir(sessionId)
        val defaultTitle = documentTitle?.trim()?.ifEmpty { null }
            ?: ("Tài liệu " + FileUtils.formatDate(System.currentTimeMillis()).replace('/', '-').replace(':', '-'))

        val pageStates = mutableListOf<PageEditState>()
        sourceImagePaths.forEachIndexed { index, sourcePath ->
            val sourceFile = File(sourcePath)
            val destFile = File(rawDir, "raw_page_${index + 1}.jpg")
            if (sourceFile.exists() && sourceFile.length() > 0L) {
                try {
                    val tempCopy = File(rawDir, "tmp_copy_${index + 1}_${System.currentTimeMillis()}.tmp")
                    sourceFile.copyTo(tempCopy, overwrite = true)
                    if (tempCopy.exists() && tempCopy.length() > 0L) {
                        tempCopy.renameTo(destFile)
                    } else {
                        tempCopy.delete()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Lỗi sao chép ảnh trang ${index + 1}: ${e.message}")
                }
            }
            val validPath = if (destFile.exists()) destFile.absolutePath else sourcePath
            pageStates.add(
                PageEditState(
                    pageIndex = index,
                    inputImagePath = validPath
                )
            )
        }

        val draft = PostScanSessionDraft(
            sessionId = sessionId,
            documentTitle = defaultTitle,
            pageStates = pageStates,
            schemaVersion = 1,
            revision = 1L,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        saveDraft(draft)
        draft
    }

    /**
     * Đọc lại bản nháp đã lưu của phiên (phục hồi khi Activity recreate hoặc process death)
     */
    suspend fun loadDraft(sessionId: String): PostScanSessionDraft? = withContext(Dispatchers.IO) {
        val mutex = getSessionLock(sessionId)
        mutex.withLock {
            val file = getMetadataFile(sessionId)
            if (!file.exists()) return@withContext null
            try {
                val atomicFile = AtomicFile(file)
                val jsonStr = atomicFile.openRead().use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                }
                val draft = PostScanSessionDraft.fromJson(JSONObject(jsonStr))
                latestPersistedRevision[sessionId] = draft.revision
                draft
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi đọc session metadata: ${e.message}")
                null
            }
        }
    }

    /**
     * Ghi cập nhật bản nháp an toàn bằng AtomicFile tuần tự qua Mutex theo session.
     * Bỏ qua các revision cũ hơn bản đã lưu gần nhất.
     */
    suspend fun saveDraft(draft: PostScanSessionDraft): Boolean = withContext(Dispatchers.IO) {
        val sessionId = draft.sessionId
        if (closedSessions.contains(sessionId)) {
            Log.w(TAG, "Bỏ qua saveDraft cho phiên đã đóng: $sessionId")
            return@withContext false
        }

        val mutex = getSessionLock(sessionId)
        mutex.withLock {
            if (closedSessions.contains(sessionId)) {
                return@withContext false
            }

            val lastRev = latestPersistedRevision[sessionId] ?: 0L
            if (draft.revision < lastRev) {
                // Đã có revision mới hơn được lưu, bỏ qua snapshot lỗi thời
                return@withContext true
            }

            val file = getMetadataFile(sessionId)
            try {
                val atomicFile = AtomicFile(file)
                val jsonBytes = draft.copy(updatedAt = System.currentTimeMillis()).toJson().toString(2).toByteArray(Charsets.UTF_8)
                var fos: FileOutputStream? = null
                try {
                    fos = atomicFile.startWrite()
                    fos.write(jsonBytes)
                    atomicFile.finishWrite(fos)
                    latestPersistedRevision[sessionId] = draft.revision
                    true
                } catch (e: Exception) {
                    if (fos != null) atomicFile.failWrite(fos)
                    Log.e(TAG, "Lỗi ghi session metadata: ${e.message}")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi AtomicFile session metadata: ${e.message}")
                false
            }
        }
    }

    /**
     * Chờ cho đến khi revision tối thiểu [minRevision] được ghi bền vững lên đĩa.
     */
    suspend fun flush(sessionId: String, minRevision: Long): Boolean = withContext(Dispatchers.IO) {
        val mutex = getSessionLock(sessionId)
        mutex.withLock {
            val currentRev = latestPersistedRevision[sessionId] ?: 0L
            currentRev >= minRevision
        }
    }

    /**
     * Đóng phiên: Ngăn nhận thêm yêu cầu ghi và chờ tác vụ ghi hiện thời hoàn tất.
     */
    suspend fun closeSession(sessionId: String) = withContext(Dispatchers.IO) {
        closedSessions.add(sessionId)
        val mutex = getSessionLock(sessionId)
        mutex.withLock {
            // Đã giữ lock, mọi writer trước đó đã hoàn tất
        }
    }

    /**
     * Hủy phiên khi người dùng xác nhận Hủy tài liệu: Đóng phiên trước, sau đó xóa toàn bộ file nháp và preview
     */
    suspend fun discardSession(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        closeSession(sessionId)
        try {
            FileUtils.deleteDir(getSessionDir(sessionId))
            FileUtils.deleteDir(getPreviewDir(sessionId))
            sessionLocks.remove(sessionId)
            latestPersistedRevision.remove(sessionId)
            closedSessions.remove(sessionId)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi hủy phiên $sessionId: ${e.message}")
            false
        }
    }

    /**
     * Hoàn tất phiên sau khi PDF đã được tạo và lưu thành công
     */
    suspend fun completeSession(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        discardSession(sessionId)
    }

    /**
     * Dọn dẹp các bản nháp bị bỏ rơi quá hạn (mặc định 7 ngày)
     */
    fun cleanOrphanedDrafts(maxAgeDays: Int = 7) {
        try {
            val cutoff = System.currentTimeMillis() - (maxAgeDays * 24 * 60 * 60 * 1000L)
            baseDraftsDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.lastModified() < cutoff) {
                    FileUtils.deleteDir(dir)
                }
            }
            basePreviewsDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.lastModified() < cutoff) {
                    FileUtils.deleteDir(dir)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi dọn dẹp bản nháp mồ côi: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "PostScanSessionRepo"

        @Volatile
        private var instance: PostScanSessionRepository? = null

        fun getInstance(context: Context): PostScanSessionRepository {
            return instance ?: synchronized(this) {
                instance ?: PostScanSessionRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
