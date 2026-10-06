package com.tscanner.app.ui.camera

import android.util.Log
import com.tscanner.app.utils.SafeFileWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class CaptureStatus {
    PENDING,
    PROCESSING,
    COMMITTED,
    FAILED
}

data class CameraPageRecord(
    val captureIndex: Int,
    val rawPath: String?,
    val outputPath: String?,
    val status: CaptureStatus,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("captureIndex", captureIndex)
            put("rawPath", rawPath ?: "")
            put("outputPath", outputPath ?: "")
            put("status", status.name)
            put("updatedAt", updatedAt)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): CameraPageRecord {
            return CameraPageRecord(
                captureIndex = json.optInt("captureIndex", 0),
                rawPath = json.optString("rawPath").ifEmpty { null },
                outputPath = json.optString("outputPath").ifEmpty { null },
                status = try {
                    CaptureStatus.valueOf(json.optString("status", CaptureStatus.PENDING.name))
                } catch (_: Exception) {
                    CaptureStatus.PENDING
                },
                updatedAt = json.optLong("updatedAt", 0L)
            )
        }
    }
}

data class ReconciledSession(
    val committedPages: Map<Int, String>,    // index -> outputPath
    val pendingRawFiles: Map<Int, String>,   // index -> rawPath
    val failedPages: Map<Int, String?>,      // index -> rawPath or null
    val maxSequence: Int
)

/**
 * C01 (P1): Quản lý manifest phiên Camera bền vững và an toàn (Two-Phase Commit Protocol).
 * Đảm bảo:
 * 1. File thô `rawFile` chỉ bị xóa sau khi file kết quả `finalPageFile` đã validate VÀ manifest đã commit thành công xuống đĩa.
 * 2. Khi khôi phục phiên (recreate / process death), đối chiếu 2 chiều giữa Manifest và các file vật lý trên đĩa:
 *    - Nhận diện các file output đã ghi trên đĩa kể cả khi process chết trước khi manifest commit (Crash Point A).
 *    - Khử trùng lặp và xóa raw dư thừa nếu manifest đã commit mà raw chưa kịp xóa (Crash Point B).
 *    - Khôi phục đầy đủ danh sách trang mà không phụ thuộc vào độ trễ snapshot Bundle (Crash Point C).
 *    - Phát hiện các capture pending bị mất file và đánh dấu thất bại rõ ràng, không nuốt âm thầm (Crash Point D).
 */
class CameraScanSessionManager(
    val sessionDir: File,
    private val imageValidator: (File) -> Boolean = { SafeFileWriter.validateImage(it) }
) {

    private val manifestFile = File(sessionDir, "session_manifest.json")
    private val records = ConcurrentHashMap<Int, CameraPageRecord>()
    private val manifestMutex = Mutex()

    init {
        if (!sessionDir.exists()) {
            sessionDir.mkdirs()
        }
    }

    suspend fun recordRawCapture(captureIndex: Int, rawFile: File): Boolean = withContext(Dispatchers.IO) {
        val record = CameraPageRecord(
            captureIndex = captureIndex,
            rawPath = rawFile.absolutePath,
            outputPath = null,
            status = CaptureStatus.PENDING,
            updatedAt = System.currentTimeMillis()
        )
        records[captureIndex] = record
        persistManifestLocked()
    }

    suspend fun recordProcessing(captureIndex: Int, rawFile: File, outputFile: File): Boolean = withContext(Dispatchers.IO) {
        val existing = records[captureIndex]
        val record = CameraPageRecord(
            captureIndex = captureIndex,
            rawPath = rawFile.absolutePath,
            outputPath = outputFile.absolutePath,
            status = CaptureStatus.PROCESSING,
            updatedAt = System.currentTimeMillis()
        )
        records[captureIndex] = record
        persistManifestLocked()
    }

    /**
     * Bước commit an toàn: Xác thực ảnh kết quả [outputFile], ghi bền vững vào manifest
     * với trạng thái COMMITTED.
     * Callers CHỈ ĐƯỢC xóa rawFile sau khi hàm này trả về true!
     */
    suspend fun commitPageOutput(captureIndex: Int, outputFile: File): Boolean = withContext(Dispatchers.IO) {
        if (!outputFile.exists() || outputFile.length() == 0L || !imageValidator(outputFile)) {
            Log.e(TAG, "Output file không hợp lệ, không thể commit: ${outputFile.absolutePath}")
            return@withContext false
        }

        val existing = records[captureIndex]
        val updated = CameraPageRecord(
            captureIndex = captureIndex,
            rawPath = existing?.rawPath,
            outputPath = outputFile.absolutePath,
            status = CaptureStatus.COMMITTED,
            updatedAt = System.currentTimeMillis()
        )
        records[captureIndex] = updated
        val persisted = persistManifestLocked()
        if (!persisted) {
            Log.e(TAG, "Ghi manifest thất bại khi commit trang $captureIndex")
            false
        } else {
            true
        }
    }

    suspend fun recordFailure(captureIndex: Int, rawFile: File?): Boolean = withContext(Dispatchers.IO) {
        val existing = records[captureIndex]
        val record = CameraPageRecord(
            captureIndex = captureIndex,
            rawPath = rawFile?.absolutePath ?: existing?.rawPath,
            outputPath = null,
            status = CaptureStatus.FAILED,
            updatedAt = System.currentTimeMillis()
        )
        records[captureIndex] = record
        persistManifestLocked()
    }

    suspend fun removePage(captureIndex: Int): Boolean = withContext(Dispatchers.IO) {
        records.remove(captureIndex)
        persistManifestLocked()
    }

    private suspend fun persistManifestLocked(): Boolean {
        return manifestMutex.withLock {
            try {
                val json = JSONObject().apply {
                    put("schemaVersion", 1)
                    put("updatedAt", System.currentTimeMillis())
                    val recordsArray = JSONArray()
                    records.values.sortedBy { it.captureIndex }.forEach { rec ->
                        recordsArray.put(rec.toJson())
                    }
                    put("records", recordsArray)
                }
                val result = SafeFileWriter.writeSafely(destinationFile = manifestFile) { tempFile ->
                    tempFile.writeText(json.toString(2), Charsets.UTF_8)
                    true
                }
                result is SafeFileWriter.Result.Success
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi ghi session manifest: ${e.message}")
                false
            }
        }
    }

    fun loadManifestFromDisk(): Map<Int, CameraPageRecord> {
        if (!manifestFile.exists() || manifestFile.length() == 0L) return emptyMap()
        return try {
            val content = manifestFile.readText(Charsets.UTF_8)
            val json = JSONObject(content)
            val array = json.optJSONArray("records") ?: JSONArray()
            val map = mutableMapOf<Int, CameraPageRecord>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val rec = CameraPageRecord.fromJson(obj)
                map[rec.captureIndex] = rec
            }
            map
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi đọc session manifest: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Đối chiếu 2 chiều giữa Manifest và các tệp thực tế trên đĩa để khôi phục phiên toàn diện.
     */
    suspend fun reconcileSession(): ReconciledSession = withContext(Dispatchers.IO) {
        manifestMutex.withLock {
            val manifestRecords = loadManifestFromDisk()
            records.clear()
            records.putAll(manifestRecords)

            val committedPages = mutableMapOf<Int, String>()
            val pendingRawFiles = mutableMapOf<Int, String>()
            val failedPages = mutableMapOf<Int, String?>()

            // 1. Quét đĩa tìm các file trang hoàn tất (page_<idx>_*.jpg)
            val pageFilesOnDisk = mutableMapOf<Int, File>()
            // 2. Quét đĩa tìm các file thô (raw_*.jpg)
            val rawFilesOnDisk = mutableMapOf<Int, File>()

            sessionDir.listFiles()?.forEach { file ->
                if (file.isFile && file.length() > 0L) {
                    val pageIdx = extractPageIndex(file.name)
                    if (pageIdx != null && imageValidator(file)) {
                        val existing = pageFilesOnDisk[pageIdx]
                        if (existing == null || file.lastModified() > existing.lastModified()) {
                            pageFilesOnDisk[pageIdx] = file
                        }
                    }
                    val rawIdx = extractRawIndex(file.name)
                    if (rawIdx != null) {
                        val existing = rawFilesOnDisk[rawIdx]
                        if (existing == null || file.lastModified() > existing.lastModified()) {
                            rawFilesOnDisk[rawIdx] = file
                        }
                    }
                }
            }

            // 3. Đối chiếu từng bản ghi trong manifest
            for ((idx, rec) in manifestRecords) {
                val diskOutput = pageFilesOnDisk[idx]?.absolutePath
                    ?: rec.outputPath?.let { if (File(it).exists() && imageValidator(File(it))) it else null }

                val diskRaw = rawFilesOnDisk[idx]?.absolutePath
                    ?: rec.rawPath?.let { if (File(it).exists() && File(it).length() > 0L) it else null }

                when {
                    diskOutput != null -> {
                        committedPages[idx] = diskOutput
                        records[idx] = rec.copy(outputPath = diskOutput, status = CaptureStatus.COMMITTED)
                        // Nếu output đã hợp lệ và commit, dọn dẹp file raw dư thừa nếu còn
                        if (diskRaw != null) {
                            try { File(diskRaw).delete() } catch (_: Exception) {}
                            rawFilesOnDisk.remove(idx)
                        }
                    }
                    diskRaw != null -> {
                        pendingRawFiles[idx] = diskRaw
                        records[idx] = rec.copy(rawPath = diskRaw, status = CaptureStatus.PENDING)
                    }
                    else -> {
                        // Crash Point D: Capture dở dang nhưng cả raw và output đều không còn
                        failedPages[idx] = null
                        records[idx] = rec.copy(status = CaptureStatus.FAILED)
                    }
                }
            }

            // 4. Phát hiện các file có trên đĩa nhưng chưa nằm trong manifest (Crash Point A)
            for ((idx, pageFile) in pageFilesOnDisk) {
                if (!committedPages.containsKey(idx)) {
                    committedPages[idx] = pageFile.absolutePath
                    records[idx] = CameraPageRecord(
                        captureIndex = idx,
                        rawPath = rawFilesOnDisk[idx]?.absolutePath,
                        outputPath = pageFile.absolutePath,
                        status = CaptureStatus.COMMITTED,
                        updatedAt = pageFile.lastModified()
                    )
                    // Dọn raw dư nếu đã có output
                    rawFilesOnDisk[idx]?.let { raw ->
                        try { raw.delete() } catch (_: Exception) {}
                        rawFilesOnDisk.remove(idx)
                    }
                }
            }

            for ((idx, rawFile) in rawFilesOnDisk) {
                if (!committedPages.containsKey(idx) && !pendingRawFiles.containsKey(idx)) {
                    pendingRawFiles[idx] = rawFile.absolutePath
                    records[idx] = CameraPageRecord(
                        captureIndex = idx,
                        rawPath = rawFile.absolutePath,
                        outputPath = null,
                        status = CaptureStatus.PENDING,
                        updatedAt = rawFile.lastModified()
                    )
                }
            }

            // 5. Ghi lại manifest sau khi đã hoàn tất đối chiếu
            val allIndices = committedPages.keys + pendingRawFiles.keys + failedPages.keys + records.keys
            val maxSeq = if (allIndices.isNotEmpty()) allIndices.maxOrNull() ?: 0 else 0

            try {
                val json = JSONObject().apply {
                    put("schemaVersion", 1)
                    put("updatedAt", System.currentTimeMillis())
                    val recordsArray = JSONArray()
                    records.values.sortedBy { it.captureIndex }.forEach { rec ->
                        recordsArray.put(rec.toJson())
                    }
                    put("records", recordsArray)
                }
                SafeFileWriter.writeSafely(destinationFile = manifestFile) { tempFile ->
                    tempFile.writeText(json.toString(2), Charsets.UTF_8)
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi ghi manifest sau đối chiếu: ${e.message}")
            }

            ReconciledSession(
                committedPages = committedPages.toSortedMap(),
                pendingRawFiles = pendingRawFiles.toSortedMap(),
                failedPages = failedPages.toSortedMap(),
                maxSequence = maxSeq
            )
        }
    }

    companion object {
        private const val TAG = "CameraScanSessionMgr"

        fun extractPageIndex(fileName: String): Int? {
            val name = fileName.substringBeforeLast(".")
            val parts = name.split("_")
            if (parts.size >= 2 && parts[0].equals("page", ignoreCase = true)) {
                return parts[1].toIntOrNull()
            }
            return null
        }

        fun extractRawIndex(fileName: String): Int? {
            val name = fileName.substringBeforeLast(".")
            val parts = name.split("_")
            if (parts.isEmpty()) return null

            if (parts[0].equals("raw", ignoreCase = true)) {
                if (parts.size == 2) {
                    return parts[1].toIntOrNull()
                } else if (parts.size >= 3) {
                    val p1 = parts[1].toLongOrNull()
                    val p2 = parts[2].toLongOrNull()
                    if (p1 != null && p2 != null) {
                        return if (p1 > 1000000000L && p2 < 100000L) p2.toInt()
                        else if (p2 > 1000000000L && p1 < 100000L) p1.toInt()
                        else parts.last().toIntOrNull()
                    }
                    return parts.last().toIntOrNull()
                }
            }
            return null
        }
    }
}
