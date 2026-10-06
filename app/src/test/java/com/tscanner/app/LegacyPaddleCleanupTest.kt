package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import com.tscanner.app.utils.LegacyPaddleCleanup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/**
 * P05: Kiểm thử dọn dẹp model PaddleOCR cũ an toàn tuyệt đối.
 * Thao tác filesystem thật trên TemporaryFolder, kiểm tra allowlist,
 * non-recursive, link-safety, fault injection qua seam, sentinel và idempotency.
 */
class LegacyPaddleCleanupTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("filesDir")
        // Reset seams
        LegacyPaddleCleanup.fileDeleter = { it.delete() }
        LegacyPaddleCleanup.directoryDeleter = { it.delete() }
        LegacyPaddleCleanup.checkActive = {}
    }

    @After
    fun tearDown() {
        // Reset seams
        LegacyPaddleCleanup.fileDeleter = { it.delete() }
        LegacyPaddleCleanup.directoryDeleter = { it.delete() }
        LegacyPaddleCleanup.checkActive = {}
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }

    // -------------------------------------------------------------------------
    // 1. Root không tồn tại
    // -------------------------------------------------------------------------
    @Test
    fun testRootMissing_returnsSafelyWithoutActions() {
        val paddleDir = File(baseDir, "paddleocr")
        assertFalse("paddleocr chưa tồn tại", paddleDir.exists())

        val summary = LegacyPaddleCleanup.cleanupDirectory(baseDir)
        assertFalse("rootExisted phải là false", summary.rootExisted)
        assertTrue("Không có file nào bị xóa", summary.deletedFiles.isEmpty())
        assertFalse("Thư mục root không bị xóa", summary.rootDirectoryDeleted)
    }

    // -------------------------------------------------------------------------
    // 2. Đầy đủ 4 model allowlist
    // -------------------------------------------------------------------------
    @Test
    fun testFullModelsPresent_deletesAll4AndRemovesEmptyDirectory() {
        val paddleDir = File(baseDir, "paddleocr")
        paddleDir.mkdirs()

        for (name in LegacyPaddleCleanup.ALLOWLISTED_FILES) {
            File(paddleDir, name).writeText("Dummy model content for $name")
        }

        val summary = LegacyPaddleCleanup.cleanupDirectory(baseDir)

        assertTrue(summary.rootExisted)
        assertEquals("Phải xóa đúng 4 file trong allowlist", 4, summary.deletedFiles.size)
        assertTrue("Các file bị xóa phải khớp allowlist", summary.deletedFiles.containsAll(LegacyPaddleCleanup.ALLOWLISTED_FILES))
        assertTrue("Không có file nào bị bỏ qua", summary.skippedFiles.isEmpty())
        assertTrue("Thư mục paddleocr rỗng phải bị xóa", summary.rootDirectoryDeleted)
        assertFalse("Thư mục paddleocr không còn tồn tại trên đĩa", paddleDir.exists())
    }

    // -------------------------------------------------------------------------
    // 3. Thiếu một phần (chỉ có 2 file)
    // -------------------------------------------------------------------------
    @Test
    fun testPartialModelsPresent_deletesOnlyPresentFilesAndRemovesDirectory() {
        val paddleDir = File(baseDir, "paddleocr")
        paddleDir.mkdirs()

        File(paddleDir, "ch_PP-OCRv4_det.onnx").writeText("Detector model")
        File(paddleDir, "ppocr_keys_v1.txt").writeText("Keys dict")

        val summary = LegacyPaddleCleanup.cleanupDirectory(baseDir)

        assertTrue(summary.rootExisted)
        assertEquals(2, summary.deletedFiles.size)
        assertTrue(summary.deletedFiles.contains("ch_PP-OCRv4_det.onnx"))
        assertTrue(summary.deletedFiles.contains("ppocr_keys_v1.txt"))
        assertTrue(summary.rootDirectoryDeleted)
        assertFalse(paddleDir.exists())
    }

    // -------------------------------------------------------------------------
    // 4. Idempotency: chạy lần 2 sau khi đã xóa sạch
    // -------------------------------------------------------------------------
    @Test
    fun testIdempotency_secondRunDoesNothingGracefully() {
        val paddleDir = File(baseDir, "paddleocr")
        paddleDir.mkdirs()
        File(paddleDir, "vi_dict.txt").writeText("vietnamese dictionary")

        // Lần 1: xóa sạch
        val run1 = LegacyPaddleCleanup.cleanupDirectory(baseDir)
        assertTrue(run1.rootExisted)
        assertTrue(run1.rootDirectoryDeleted)

        // Lần 2: chạy lại
        val run2 = LegacyPaddleCleanup.cleanupDirectory(baseDir)
        assertFalse("Lần 2 root không còn tồn tại", run2.rootExisted)
        assertTrue(run2.deletedFiles.isEmpty())
        assertFalse(run2.rootDirectoryDeleted)
    }

    // -------------------------------------------------------------------------
    // 5. File lạ và thư mục lạ bên trong paddleocr -> giữ nguyên, không xóa đệ quy
    // -------------------------------------------------------------------------
    @Test
    fun testUnknownFileInsideRoot_retainsUnknownFileAndDoesNotDeleteRoot() {
        val paddleDir = File(baseDir, "paddleocr")
        paddleDir.mkdirs()

        // 1 file allowlist + 1 file lạ của người dùng
        File(paddleDir, "ch_PP-OCRv4_rec.onnx").writeText("Recognizer")
        val userFile = File(paddleDir, "user_custom_notes.txt")
        userFile.writeText("Critical user notes that must never be deleted")

        val summary = LegacyPaddleCleanup.cleanupDirectory(baseDir)

        assertTrue(summary.rootExisted)
        assertEquals(listOf("ch_PP-OCRv4_rec.onnx"), summary.deletedFiles)
        assertFalse("Thư mục root KHÔNG được xóa vì còn chứa file lạ", summary.rootDirectoryDeleted)
        assertTrue("Thư mục paddleocr vẫn tồn tại", paddleDir.exists())
        assertTrue("File lạ của người dùng phải được bảo toàn nguyên vẹn", userFile.exists())
        assertEquals("Critical user notes that must never be deleted", userFile.readText())
    }

    @Test
    fun testSubdirectoryInsideRoot_retainsSubdirAndDoesNotDeleteRecursively() {
        val paddleDir = File(baseDir, "paddleocr")
        paddleDir.mkdirs()

        File(paddleDir, "vi_dict.txt").writeText("dict")
        val subDir = File(paddleDir, "custom_backup")
        subDir.mkdirs()
        val nestedFile = File(subDir, "important.pdf")
        nestedFile.writeText("Important nested PDF content")

        val summary = LegacyPaddleCleanup.cleanupDirectory(baseDir)

        assertTrue(summary.rootExisted)
        assertEquals(listOf("vi_dict.txt"), summary.deletedFiles)
        assertFalse("Root không được xóa vì còn subfolder", summary.rootDirectoryDeleted)
        assertTrue("Thư mục con phải được giữ nguyên", subDir.exists())
        assertTrue("File lồng trong thư mục con phải được giữ nguyên", nestedFile.exists())
    }

    // -------------------------------------------------------------------------
    // 6. Tên allowlist nhưng thực tế là thư mục (Directory with allowlist name)
    // -------------------------------------------------------------------------
    @Test
    fun testAllowlistNameIsDirectory_skipsDirectoryAndDoesNotDelete() {
        val paddleDir = File(baseDir, "paddleocr")
        paddleDir.mkdirs()

        // Tạo thư mục có tên trùng với tên file allowlist
        val fakeModelDir = File(paddleDir, "ch_PP-OCRv4_det.onnx")
        fakeModelDir.mkdirs()
        val nested = File(fakeModelDir, "inside.txt")
        nested.writeText("inside fake dir")

        val summary = LegacyPaddleCleanup.cleanupDirectory(baseDir)

        assertTrue(summary.rootExisted)
        assertTrue("Không được xóa thư mục giả danh file", summary.deletedFiles.isEmpty())
        assertTrue("Phải đánh dấu skipped", summary.skippedFiles.contains("ch_PP-OCRv4_det.onnx"))
        assertFalse(summary.rootDirectoryDeleted)
        assertTrue("Thư mục vẫn phải tồn tại", fakeModelDir.exists())
        assertTrue("File lồng bên trong vẫn phải tồn tại", nested.exists())
    }

    // -------------------------------------------------------------------------
    // 7. Symlink avoidance (nếu môi trường OS hỗ trợ)
    // -------------------------------------------------------------------------
    @Test
    fun testSymlinkTarget_isNeverFollowedOrDeleted() {
        val externalSecretFile = tempFolder.newFile("secret_external.txt")
        externalSecretFile.writeText("External sensitive content")
        val externalHashBefore = sha256(externalSecretFile)

        val paddleDir = File(baseDir, "paddleocr")
        paddleDir.mkdirs()

        val symlinkCandidate = File(paddleDir, "vi_dict.txt").toPath()
        val canCreateSymlink = try {
            Files.createSymbolicLink(symlinkCandidate, externalSecretFile.toPath())
            true
        } catch (_: Throwable) {
            false
        }

        if (!canCreateSymlink) {
            println("Bỏ qua testSymlinkTarget trên Windows do tài khoản không có quyền SE_CREATE_SYMBOLIC_LINK_NAME.")
            return
        }

        val summary = LegacyPaddleCleanup.cleanupDirectory(baseDir)
        assertTrue("File symlink trong allowlist phải bị skipped", summary.skippedFiles.contains("vi_dict.txt"))
        assertFalse("File symlink không được tính vào deleted", summary.deletedFiles.contains("vi_dict.txt"))
        assertTrue("File target bên ngoài phải nguyên vẹn", externalSecretFile.exists())
        assertEquals("Hash của external file không được đổi", externalHashBefore, sha256(externalSecretFile))
    }

    @Test
    fun testRootIsSymlink_abortsImmediatelyWithoutTouchingTarget() {
        val externalFolder = tempFolder.newFolder("external_folder")
        val externalFile = File(externalFolder, "ch_PP-OCRv4_det.onnx")
        externalFile.writeText("Do not delete me")

        val paddleDirLink = File(baseDir, "paddleocr").toPath()
        val canCreateSymlink = try {
            Files.createSymbolicLink(paddleDirLink, externalFolder.toPath())
            true
        } catch (_: Throwable) {
            false
        }

        if (!canCreateSymlink) {
            println("Bỏ qua testRootIsSymlink trên Windows do thiếu quyền symlink OS.")
            return
        }

        val summary = LegacyPaddleCleanup.cleanupDirectory(baseDir)
        assertTrue("Phải abort do root là symlink", summary.abortedDueToSecurityOrLink)
        assertTrue("Không file nào bị xóa", summary.deletedFiles.isEmpty())
        assertTrue("Thư mục target bên ngoài vẫn còn nguyên", externalFile.exists())
    }

    // -------------------------------------------------------------------------
    // 8. Seam Fault Injection: fileDeleter trả false hoặc ném exception
    // -------------------------------------------------------------------------
    @Test
    fun testFileDeleterFails_skipsFileWithoutCrashing() {
        val paddleDir = File(baseDir, "paddleocr")
        paddleDir.mkdirs()
        val file1 = File(paddleDir, "ch_PP-OCRv4_det.onnx").apply { writeText("model1") }
        val file2 = File(paddleDir, "ch_PP-OCRv4_rec.onnx").apply { writeText("model2") }

        // Seam: từ chối xóa file1, cho phép xóa file2
        LegacyPaddleCleanup.fileDeleter = { file ->
            if (file.name == "ch_PP-OCRv4_det.onnx") false else file.delete()
        }

        val summary = LegacyPaddleCleanup.cleanupDirectory(baseDir)

        assertTrue(summary.deletedFiles.contains("ch_PP-OCRv4_rec.onnx"))
        assertTrue(summary.skippedFiles.contains("ch_PP-OCRv4_det.onnx"))
        assertFalse("Root không được xóa khi còn file", summary.rootDirectoryDeleted)
        assertTrue("File1 chưa xóa được phải còn nguyên", file1.exists())
        assertFalse("File2 đã xóa thành công", file2.exists())
    }

    @Test
    fun testFileDeleterThrowsException_handledGracefullyWithoutCrashing() {
        val paddleDir = File(baseDir, "paddleocr")
        paddleDir.mkdirs()
        File(paddleDir, "vi_dict.txt").writeText("dict")

        // Seam: ném IOException
        LegacyPaddleCleanup.fileDeleter = {
            throw IOException("Simulated disk I/O lock")
        }

        val summary = LegacyPaddleCleanup.cleanupDirectory(baseDir)

        assertTrue("Không file nào bị xóa khi lỗi ném ra", summary.deletedFiles.isEmpty())
        assertTrue(summary.skippedFiles.contains("vi_dict.txt"))
        assertFalse(summary.rootDirectoryDeleted)
    }

    // -------------------------------------------------------------------------
    // 9. Interrupted Run & Resume (xóa dở lần 1, chạy lại lần 2 dọn sạch)
    // -------------------------------------------------------------------------
    @Test
    fun testInterruptedRun_resumesAndCompletesOnSecondRun() {
        val paddleDir = File(baseDir, "paddleocr")
        paddleDir.mkdirs()
        File(paddleDir, "ch_PP-OCRv4_det.onnx").writeText("det")
        File(paddleDir, "ch_PP-OCRv4_rec.onnx").writeText("rec")
        File(paddleDir, "ppocr_keys_v1.txt").writeText("keys")
        File(paddleDir, "vi_dict.txt").writeText("dict")

        // Lần 1: Chỉ cho phép xóa det và rec, rồi fail
        LegacyPaddleCleanup.fileDeleter = { file ->
            if (file.name == "ppocr_keys_v1.txt" || file.name == "vi_dict.txt") {
                false
            } else {
                file.delete()
            }
        }

        val run1 = LegacyPaddleCleanup.cleanupDirectory(baseDir)
        assertEquals(2, run1.deletedFiles.size)
        assertEquals(2, run1.skippedFiles.size)
        assertFalse(run1.rootDirectoryDeleted)
        assertTrue(paddleDir.exists())

        // Lần 2: Phục hồi xóa bình thường
        LegacyPaddleCleanup.fileDeleter = { it.delete() }
        val run2 = LegacyPaddleCleanup.cleanupDirectory(baseDir)
        assertEquals(2, run2.deletedFiles.size)
        assertTrue(run2.deletedFiles.contains("ppocr_keys_v1.txt"))
        assertTrue(run2.deletedFiles.contains("vi_dict.txt"))
        assertTrue(run2.rootDirectoryDeleted)
        assertFalse(paddleDir.exists())
    }

    // -------------------------------------------------------------------------
    // 10. Sentinel Check: Tesseract, OCR Documents, Sibling Files bảo toàn Hash
    // -------------------------------------------------------------------------
    @Test
    fun testSentinelsPreserved_tesseractOcrDocsAndSiblingsUntouched() {
        // 1. Sentinel Tesseract
        val tessDir = File(baseDir, "tessdata").apply { mkdirs() }
        val tessModel = File(tessDir, "vie.traineddata").apply { writeText("Tesseract Vietnamese Trained Data Binary Content") }
        val tessHashBefore = sha256(tessModel)

        // 2. Sentinel OCR Docs
        val docsDir = File(baseDir, "ocr_docs").apply { mkdirs() }
        val docJson = File(docsDir, "scan_001.json").apply { writeText("{\"pageId\":\"p1\", \"text\":\"Nội dung quét\"}") }
        val docHashBefore = sha256(docJson)

        // 3. Sentinel Sibling File
        val siblingFile = File(baseDir, "app_prefs.xml").apply { writeText("<map><string name=\"theme\">dark</string></map>") }
        val siblingHashBefore = sha256(siblingFile)

        // 4. Model Paddle cần xóa
        val paddleDir = File(baseDir, "paddleocr").apply { mkdirs() }
        File(paddleDir, "ch_PP-OCRv4_det.onnx").writeText("Paddle Det Model")
        File(paddleDir, "ch_PP-OCRv4_rec.onnx").writeText("Paddle Rec Model")

        // Thực hiện cleanup
        val summary = LegacyPaddleCleanup.cleanupDirectory(baseDir)

        assertTrue(summary.rootDirectoryDeleted)
        assertFalse(paddleDir.exists())

        // Kiểm tra Hash tất cả Sentinels
        assertTrue("Tesseract model phải tồn tại", tessModel.exists())
        assertEquals("Hash Tesseract không đổi", tessHashBefore, sha256(tessModel))

        assertTrue("OCR doc JSON phải tồn tại", docJson.exists())
        assertEquals("Hash OCR doc JSON không đổi", docHashBefore, sha256(docJson))

        assertTrue("Sibling file phải tồn tại", siblingFile.exists())
        assertEquals("Hash Sibling file không đổi", siblingHashBefore, sha256(siblingFile))
    }

    // -------------------------------------------------------------------------
    // 11. Suspend function & CancellationException propagation
    // -------------------------------------------------------------------------
    @Test
    fun testCleanupLegacyPaddleFiles_propagatesCancellationException() = runBlocking {
        val dummyContext = object : ContextWrapper(null) {
            override fun getFilesDir(): File {
                throw CancellationException("Coroutine was cancelled")
            }
        }

        try {
            LegacyPaddleCleanup.cleanupLegacyPaddleFiles(dummyContext)
            fail("CancellationException phải được propagate ra ngoài")
        } catch (c: CancellationException) {
            assertEquals("Coroutine was cancelled", c.message)
        }
    }

    @Test
    fun testCleanupLegacyPaddleFiles_normalExecutionSucceeds() = runBlocking {
        val paddleDir = File(baseDir, "paddleocr").apply { mkdirs() }
        File(paddleDir, "vi_dict.txt").writeText("dict")

        val dummyContext = object : ContextWrapper(null) {
            override fun getFilesDir(): File = baseDir
        }

        val summary = LegacyPaddleCleanup.cleanupLegacyPaddleFiles(dummyContext)
        assertTrue(summary.rootExisted)
        assertEquals(listOf("vi_dict.txt"), summary.deletedFiles)
        assertTrue(summary.rootDirectoryDeleted)
        assertFalse(paddleDir.exists())
    }

    // -------------------------------------------------------------------------
    // 12. Probe tests: File & Directory deletion and checkpoint cancellation propagation
    // -------------------------------------------------------------------------
    @Test
    fun testFileDeletionCancellationMustPropagate() {
        val paddleDir = File(baseDir, "paddleocr").apply { mkdirs() }
        File(paddleDir, "vi_dict.txt").writeText("legacy")
        LegacyPaddleCleanup.fileDeleter = { throw CancellationException("file cancelled") }
        try {
            LegacyPaddleCleanup.cleanupDirectory(baseDir)
            fail("Cancellation during file deletion was swallowed")
        } catch (expected: CancellationException) {
            assertEquals("file cancelled", expected.message)
        }
    }

    @Test
    fun testDirectoryDeletionCancellationMustPropagate() {
        val paddleDir = File(baseDir, "paddleocr").apply { mkdirs() }
        LegacyPaddleCleanup.directoryDeleter = { throw CancellationException("directory cancelled") }
        try {
            LegacyPaddleCleanup.cleanupDirectory(baseDir)
            fail("Cancellation during directory deletion was swallowed")
        } catch (expected: CancellationException) {
            assertEquals("directory cancelled", expected.message)
        }
    }

    @Test
    fun testCheckActiveCancellationBeforeScanningPropagates() {
        val paddleDir = File(baseDir, "paddleocr").apply { mkdirs() }
        File(paddleDir, "vi_dict.txt").writeText("legacy")
        var checkCount = 0
        LegacyPaddleCleanup.checkActive = {
            checkCount++
            throw CancellationException("Cancelled before scan")
        }
        try {
            LegacyPaddleCleanup.cleanupDirectory(baseDir)
            fail("Cancellation from checkActive was swallowed")
        } catch (expected: CancellationException) {
            assertEquals("Cancelled before scan", expected.message)
            assertEquals(1, checkCount)
        }
    }

    @Test
    fun testCheckActiveCancellationDuringFileIterationPropagates() {
        val paddleDir = File(baseDir, "paddleocr").apply { mkdirs() }
        File(paddleDir, "vi_dict.txt").writeText("legacy")
        File(paddleDir, "ppocr_keys_v1.txt").writeText("keys")
        var checkCount = 0
        LegacyPaddleCleanup.checkActive = {
            checkCount++
            if (checkCount > 1) {
                throw CancellationException("Cancelled during iteration")
            }
        }
        try {
            LegacyPaddleCleanup.cleanupDirectory(baseDir)
            fail("Cancellation from checkActive was swallowed")
        } catch (expected: CancellationException) {
            assertEquals("Cancelled during iteration", expected.message)
        }
    }
}
