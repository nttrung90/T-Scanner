package com.tscanner.app

import com.tscanner.app.utils.GoogleDriveService
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class GoogleDriveDownloadValidationTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "dl_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        GoogleDriveService.setTestClient(null, null, null)
        tempDir.deleteRecursively()
    }

    @Test
    fun testIsPdfHeaderValid() {
        // 1. Valid PDF file
        val validFile = File(tempDir, "valid.pdf")
        validFile.writeBytes("%PDF-1.4 test binary data".toByteArray())
        assertTrue("Valid PDF header should pass", GoogleDriveService.isPdfHeaderValid(validFile))

        // 2. HTML content (e.g. captive portal or error page)
        val htmlFile = File(tempDir, "error.html")
        htmlFile.writeBytes("<!DOCTYPE html><html><body>Error</body></html>".toByteArray())
        assertFalse("HTML header must fail PDF check", GoogleDriveService.isPdfHeaderValid(htmlFile))

        // 3. Truncated / empty file
        val emptyFile = File(tempDir, "empty.pdf")
        emptyFile.writeBytes(ByteArray(0))
        assertFalse("Empty file must fail PDF check", GoogleDriveService.isPdfHeaderValid(emptyFile))

        // 4. File shorter than 5 bytes
        val shortFile = File(tempDir, "short.pdf")
        shortFile.writeBytes("%PDF".toByteArray())
        assertFalse("File shorter than 5 bytes must fail PDF check", GoogleDriveService.isPdfHeaderValid(shortFile))

        // 5. Non-existent file
        val missingFile = File(tempDir, "missing.pdf")
        assertFalse("Missing file must fail PDF check", GoogleDriveService.isPdfHeaderValid(missingFile))
    }

    @Test
    fun testDownloadPdfFile_validPdfCommitsAtomically() = runBlocking {
        val destFile = File(tempDir, "output_doc.pdf")

        val testClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("%PDF-1.7 complete document bytes".toByteArray().toResponseBody("application/pdf".toMediaTypeOrNull()))
                    .build()
            })
            .build()

        GoogleDriveService.setTestClient(testClient)

        val success = GoogleDriveService.downloadPdfFile("dummy_token", "drive_file_123", destFile)
        assertTrue("Download must succeed for valid PDF", success)
        assertTrue("Destination file must exist", destFile.exists())
        assertTrue("Destination file must have valid PDF header", GoogleDriveService.isPdfHeaderValid(destFile))
        assertEquals("%PDF-1.7 complete document bytes", destFile.readText())

        // Ensure no temporary files (.dl_tmp_) are left behind in directory
        val tempFiles = tempDir.listFiles { _, name -> name.contains(".dl_tmp_") }
        assertTrue("Temporary download files must be cleaned up", tempFiles.isNullOrEmpty())
    }

    @Test
    fun testDownloadPdfFile_invalidPdfBodyRejectedAndCleaned() = runBlocking {
        val destFile = File(tempDir, "corrupted_doc.pdf")

        val testClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("<html><body>Service Error</body></html>".toByteArray().toResponseBody("text/html".toMediaTypeOrNull()))
                    .build()
            })
            .build()

        GoogleDriveService.setTestClient(testClient)

        val success = GoogleDriveService.downloadPdfFile("dummy_token", "drive_file_456", destFile)
        assertFalse("Download must fail for non-PDF payload", success)
        assertFalse("Destination file must NOT be created when payload is invalid", destFile.exists())

        // Ensure no temporary files (.dl_tmp_) are left behind
        val tempFiles = tempDir.listFiles { _, name -> name.contains(".dl_tmp_") }
        assertTrue("Temporary download files must be cleaned up after validation failure", tempFiles.isNullOrEmpty())
    }

    @Test
    fun testDownloadPdfFile_emptyBodyRejected() = runBlocking {
        val destFile = File(tempDir, "empty_doc.pdf")

        val testClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ByteArray(0).toResponseBody("application/pdf".toMediaTypeOrNull()))
                    .build()
            })
            .build()

        GoogleDriveService.setTestClient(testClient)

        val success = GoogleDriveService.downloadPdfFile("dummy_token", "drive_file_789", destFile)
        assertFalse("Download must fail for 0-byte payload", success)
        assertFalse("Destination file must NOT be created when 0 bytes downloaded", destFile.exists())
    }
}
