package com.tscanner.app

import com.tscanner.app.utils.DriveOperationResult
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class GoogleDriveTransportErrorTest {

    private lateinit var tempFile: File

    @Before
    fun setUp() {
        tempFile = File.createTempFile("test_transport_", ".pdf")
        FileOutputStream(tempFile).use {
            // Write valid PDF magic bytes
            it.write("%PDF-1.4 test document content".toByteArray())
        }
    }

    @After
    fun tearDown() {
        GoogleDriveService.setTestClient(null, null, null)
        if (tempFile.exists()) tempFile.delete()
    }

    @Test
    fun testClassifyHttpError_allStatusCodes() {
        // 404 -> FileNotFound
        val r404 = GoogleDriveService.classifyHttpError(404, "File not found")
        assertTrue("Expected FileNotFound", r404 is DriveOperationResult.FileNotFound)

        // 401 -> AuthError
        val r401 = GoogleDriveService.classifyHttpError(401, "Unauthorized")
        assertTrue("Expected AuthError", r401 is DriveOperationResult.AuthError)

        // 403 quota -> QuotaExceeded
        val r403Quota = GoogleDriveService.classifyHttpError(403, "{\"error\": {\"errors\": [{\"reason\": \"userRateLimitExceeded\"}]}}")
        assertTrue("Expected QuotaExceeded", r403Quota is DriveOperationResult.QuotaExceeded)

        // 403 auth -> AuthError
        val r403Auth = GoogleDriveService.classifyHttpError(403, "{\"error\": {\"errors\": [{\"reason\": \"insufficientPermissions\"}]}}")
        assertTrue("Expected AuthError", r403Auth is DriveOperationResult.AuthError)

        // 429 -> TransientError
        val r429 = GoogleDriveService.classifyHttpError(429, "Too Many Requests")
        assertTrue("Expected TransientError for 429", r429 is DriveOperationResult.TransientError)

        // 500, 502, 503, 504 -> TransientError
        val r500 = GoogleDriveService.classifyHttpError(500, "Internal Server Error")
        assertTrue("Expected TransientError for 500", r500 is DriveOperationResult.TransientError)
        val r503 = GoogleDriveService.classifyHttpError(503, "Service Unavailable")
        assertTrue("Expected TransientError for 503", r503 is DriveOperationResult.TransientError)

        // Network IOException -> TransientError
        val rIo = GoogleDriveService.classifyHttpError(0, null, IOException("Socket connection reset"))
        assertTrue("Expected TransientError for IOException", rIo is DriveOperationResult.TransientError)

        // 400 Bad Request -> PermanentError
        val r400 = GoogleDriveService.classifyHttpError(400, "Bad Request")
        assertTrue("Expected PermanentError for 400", r400 is DriveOperationResult.PermanentError)
    }

    @Test
    fun testUpdatePdfFile_transient503DoesNotReturnNullOrSuccess() = runBlocking {
        val testClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Service Unavailable")
                    .body("{\"error\": \"backend error\"}".toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            })
            .build()

        GoogleDriveService.setTestClient(testClient)

        val result = GoogleDriveService.updatePdfFile("dummy_token", "drive_123", tempFile)
        assertTrue("Result must be TransientError on 503", result is DriveOperationResult.TransientError)
        val transient = result as DriveOperationResult.TransientError
        assertEquals(503, transient.code)
    }

    @Test
    fun testUpdatePdfFile_returnsFileNotFoundOn404() = runBlocking {
        val testClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body("{\"error\": \"File not found\"}".toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            })
            .build()

        GoogleDriveService.setTestClient(testClient)

        val result = GoogleDriveService.updatePdfFile("dummy_token", "drive_nonexistent", tempFile)
        assertTrue("Result must be FileNotFound on 404", result is DriveOperationResult.FileNotFound)
    }

    @Test
    fun testUploadPdfFile_idempotentReconciliationAvoidsDuplicate() = runBlocking {
        var searchCalled = false
        var patchCalled = false
        var postCalled = false

        val testClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                val method = req.method

                if (method == "GET" && url.contains("tscanner_doc_id")) {
                    searchCalled = true
                    // Simulate search finding an existing file with this docId
                    val json = """{"files": [{"id": "drive_existing_888", "name": "Document.pdf"}]}"""
                    return@Interceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                        .build()
                } else if (method == "PATCH" && url.contains("drive_existing_888")) {
                    patchCalled = true
                    val json = """{"id": "drive_existing_888"}"""
                    return@Interceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                        .build()
                } else if (method == "POST") {
                    postCalled = true
                    val json = """{"id": "drive_duplicate_created"}"""
                    return@Interceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                        .build()
                }

                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not found")
                    .body("".toResponseBody(null))
                    .build()
            })
            .build()

        GoogleDriveService.setTestClient(testClient)

        val result = GoogleDriveService.uploadPdfFile(
            token = "dummy_token",
            folderId = "folder_123",
            pdfFile = tempFile,
            documentTitle = "My Document",
            docId = "doc_unique_456"
        )

        assertTrue("Expected search to be called for idempotency check", searchCalled)
        assertTrue("Expected PATCH to be called to update existing file", patchCalled)
        assertTrue("POST must NOT be called when existing file is found", !postCalled)
        assertTrue("Result must be Success", result is DriveOperationResult.Success)
        assertEquals("drive_existing_888", (result as DriveOperationResult.Success).data)
    }

    @Test
    fun testUploadPdfFile_includesDocIdInAppProperties() = runBlocking {
        var capturedBody: String? = null

        val testClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                val method = req.method

                if (method == "GET" && url.contains("tscanner_doc_id")) {
                    // No existing file
                    val json = """{"files": []}"""
                    return@Interceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                        .build()
                } else if (method == "POST") {
                    val buffer = okio.Buffer()
                    req.body?.writeTo(buffer)
                    capturedBody = buffer.readUtf8()

                    val json = """{"id": "drive_new_created_777"}"""
                    return@Interceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(json.toResponseBody("application/json".toMediaTypeOrNull()))
                        .build()
                }

                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not found")
                    .body("".toResponseBody(null))
                    .build()
            })
            .build()

        GoogleDriveService.setTestClient(testClient)

        val result = GoogleDriveService.uploadPdfFile(
            token = "dummy_token",
            folderId = "folder_123",
            pdfFile = tempFile,
            documentTitle = "My Document",
            docId = "doc_unique_777"
        )

        assertTrue("Result must be Success", result is DriveOperationResult.Success)
        assertEquals("drive_new_created_777", (result as DriveOperationResult.Success).data)
        assertNotNull("Captured body should not be null", capturedBody)
        assertTrue("Multipart request must include tscanner_doc_id app property", capturedBody!!.contains("tscanner_doc_id"))
        assertTrue("Multipart request must include docId value", capturedBody!!.contains("doc_unique_777"))
    }
}
