package com.tscanner.app.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * P05: Helper an toàn dọn dẹp các model PaddleOCR cũ còn lưu trong bộ nhớ riêng của ứng dụng (filesDir/paddleocr).
 *
 * Tiêu chí an toàn bắt buộc:
 * 1. Root duy nhất là File(context.filesDir, "paddleocr"). Tuyệt đối không nhận đường dẫn động.
 * 2. Xác minh canonical path là con trực tiếp của filesDir; từ chối xử lý nếu root hoặc target là symbolic link.
 * 3. Chỉ xóa file thường trực tiếp trong allowlist 4 file: ch_PP-OCRv4_det.onnx, ch_PP-OCRv4_rec.onnx,
 *    ppocr_keys_v1.txt, vi_dict.txt. Tuyệt đối KHÔNG xóa đệ quy.
 * 4. Xóa thư mục paddleocr bằng thao tác xóa thư mục rỗng sau khi xử lý. Nếu có file/subfolder lạ thì giữ nguyên.
 * 5. Chạy trên Dispatchers.IO, không chặn Main thread, không nuốt CancellationException.
 * 6. Idempotent: có thể chạy lại nhiều lần an toàn, không cần cờ ghi nhận trước khi xóa thực.
 */
object LegacyPaddleCleanup {

    private const val TAG = "LegacyPaddleCleanup"
    const val PADDLE_DIR_NAME = "paddleocr"

    val ALLOWLISTED_FILES = setOf(
        "ch_PP-OCRv4_det.onnx",
        "ch_PP-OCRv4_rec.onnx",
        "ppocr_keys_v1.txt",
        "vi_dict.txt"
    )

    data class CleanupSummary(
        val rootExisted: Boolean,
        val deletedFiles: List<String> = emptyList(),
        val skippedFiles: List<String> = emptyList(),
        val rootDirectoryDeleted: Boolean = false,
        val abortedDueToSecurityOrLink: Boolean = false
    )

    // Seam phục vụ kiểm thử đơn vị mô phỏng lỗi xóa filesystem mà không cần chmod OS
    internal var fileDeleter: (File) -> Boolean = { it.delete() }
    internal var directoryDeleter: (File) -> Boolean = { it.delete() }
    internal var checkActive: () -> Unit = {}

    /**
     * Dọn dẹp model PaddleOCR cũ trong context.filesDir/paddleocr.
     * Chạy bất đồng bộ trên Dispatchers.IO.
     */
    suspend fun cleanupLegacyPaddleFiles(context: Context): CleanupSummary {
        return withContext(Dispatchers.IO) {
            val ensureActiveAction = {
                coroutineContext.ensureActive()
            }
            try {
                val filesDir = context.filesDir ?: return@withContext CleanupSummary(rootExisted = false)
                cleanupDirectory(filesDir, ensureActiveAction)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "Lỗi bất ngờ trong quá trình dọn dẹp model Paddle cũ: ${t.message}")
                CleanupSummary(rootExisted = false)
            }
        }
    }

    /**
     * Logic dọn dẹp cốt lõi với baseDir được chỉ định.
     * Internal để phục vụ kiểm thử với thư mục tạm (TemporaryFolder).
     */
    internal fun cleanupDirectory(
        baseDir: File,
        activeChecker: (() -> Unit)? = null
    ): CleanupSummary {
        val check = activeChecker ?: checkActive
        check()

        val rootDir = File(baseDir, PADDLE_DIR_NAME)
        if (!rootDir.exists()) {
            return CleanupSummary(rootExisted = false)
        }

        val canonicalBase = try {
            baseDir.canonicalFile
        } catch (e: IOException) {
            Log.w(TAG, "Không thể xác định canonical base directory: ${e.message}")
            return CleanupSummary(rootExisted = true, abortedDueToSecurityOrLink = true)
        }

        val canonicalRoot = try {
            rootDir.canonicalFile
        } catch (e: IOException) {
            Log.w(TAG, "Không thể xác định canonical root directory: ${e.message}")
            return CleanupSummary(rootExisted = true, abortedDueToSecurityOrLink = true)
        }

        // 1. Kiểm tra bảo mật: rootDir phải là con trực tiếp dự kiến của baseDir
        if (canonicalRoot.parentFile != canonicalBase || canonicalRoot.name != PADDLE_DIR_NAME) {
            Log.w(TAG, "Phát hiện vị trí root directory bất thường hoặc path escape. Bỏ qua.")
            return CleanupSummary(rootExisted = true, abortedDueToSecurityOrLink = true)
        }

        // 2. Kiểm tra bảo mật: rootDir không được là symbolic link
        if (Files.isSymbolicLink(rootDir.toPath())) {
            Log.w(TAG, "Root directory là symbolic link. Bỏ qua để tránh duyệt theo link.")
            return CleanupSummary(rootExisted = true, abortedDueToSecurityOrLink = true)
        }

        val deleted = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        // 3. Chỉ duyệt và xóa các file nằm trong allowlist, tuyệt đối không duyệt đệ quy
        for (fileName in ALLOWLISTED_FILES) {
            check()
            val targetFile = File(rootDir, fileName)
            if (!targetFile.exists()) {
                continue
            }

            try {
                val targetPath = targetFile.toPath()

                // Không xử lý symbolic link
                if (Files.isSymbolicLink(targetPath)) {
                    Log.w(TAG, "File trong allowlist là symbolic link ($fileName). Bỏ qua.")
                    skipped.add(fileName)
                    continue
                }

                // Phải là file thường (regular file), không được là thư mục trùng tên
                if (!Files.isRegularFile(targetPath, LinkOption.NOFOLLOW_LINKS)) {
                    Log.w(TAG, "Mục trong allowlist không phải regular file ($fileName). Bỏ qua.")
                    skipped.add(fileName)
                    continue
                }

                // Canonical parent phải đúng là canonical root
                val canonicalTarget = targetFile.canonicalFile
                if (canonicalTarget.parentFile != canonicalRoot) {
                    Log.w(TAG, "File canonical parent không khớp root ($fileName). Bỏ qua.")
                    skipped.add(fileName)
                    continue
                }

                check()
                val success = fileDeleter(targetFile)
                if (success) {
                    deleted.add(fileName)
                } else {
                    Log.w(TAG, "Không thể xóa file: $fileName")
                    skipped.add(fileName)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "Lỗi khi xử lý file $fileName: ${t.message}")
                skipped.add(fileName)
            }
        }

        check()
        // 4. Xóa thư mục root bằng thao tác xóa thư mục rỗng
        val remainingEntries = rootDir.list()
        val dirDeleted = if (remainingEntries != null && remainingEntries.isEmpty()) {
            try {
                check()
                directoryDeleter(rootDir)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "Không thể xóa thư mục paddleocr đã rỗng: ${t.message}")
                false
            }
        } else {
            if (remainingEntries != null && remainingEntries.isNotEmpty()) {
                Log.i(TAG, "Thư mục paddleocr còn ${remainingEntries.size} mục lạ hoặc chưa xóa hết. Giữ nguyên thư mục.")
            }
            false
        }

        return CleanupSummary(
            rootExisted = true,
            deletedFiles = deleted,
            skippedFiles = skipped,
            rootDirectoryDeleted = dirDeleted,
            abortedDueToSecurityOrLink = false
        )
    }
}
