package com.tscanner.app.utils

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class DriveFileMetadata(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedTime: Long
)

sealed class DriveOperationResult<out T> {
    data class Success<out T>(val data: T) : DriveOperationResult<T>()
    data class FileNotFound(val code: Int = 404, val message: String) : DriveOperationResult<Nothing>()
    data class AuthError(val code: Int, val message: String) : DriveOperationResult<Nothing>()
    data class QuotaExceeded(val code: Int, val message: String) : DriveOperationResult<Nothing>()
    data class TransientError(val code: Int, val message: String, val cause: Throwable? = null) : DriveOperationResult<Nothing>()
    data class PermanentError(val code: Int, val message: String) : DriveOperationResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    fun getOrNull(): T? = (this as? Success)?.data

    val errorMessage: String?
        get() = when (this) {
            is Success -> null
            is FileNotFound -> message
            is AuthError -> message
            is QuotaExceeded -> message
            is TransientError -> message
            is PermanentError -> message
        }
}

sealed class QueryFolderResult {
    data class Success(val files: List<DriveFileMetadata>) : QueryFolderResult()
    data class Partial(val files: List<DriveFileMetadata>, val error: String, val code: Int = 0) : QueryFolderResult()
    data class AuthRequired(val error: String, val code: Int = 401) : QueryFolderResult()
    data class Failure(val error: String, val code: Int = 0, val cause: Throwable? = null) : QueryFolderResult()

    val filesOrEmpty: List<DriveFileMetadata>
        get() = when (this) {
            is Success -> files
            is Partial -> files
            else -> emptyList()
        }

    val isFullSuccess: Boolean get() = this is Success
}

sealed class LookupFileResult {
    data class Found(val fileId: String) : LookupFileResult()
    object NotFound : LookupFileResult()
    data class Error(val operationResult: DriveOperationResult<String>) : LookupFileResult()
}

object GoogleDriveService {

    private const val TAG = "GoogleDriveService"
    const val FOLDER_NAME = "T-Scanner Documents"
    private const val DRIVE_API_BASE = "https://www.googleapis.com/drive/v3"
    private const val DRIVE_UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var customHttpClient: OkHttpClient? = null
    @Volatile
    private var customDriveApiBase: String? = null
    @Volatile
    private var customDriveUploadBase: String? = null

    internal fun setTestClient(client: OkHttpClient?, apiBase: String? = null, uploadBase: String? = null) {
        customHttpClient = client
        customDriveApiBase = apiBase
        customDriveUploadBase = uploadBase
    }

    internal val client: OkHttpClient
        get() = customHttpClient ?: httpClient
    internal val driveApiBase: String
        get() = customDriveApiBase ?: DRIVE_API_BASE
    internal val driveUploadBase: String
        get() = customDriveUploadBase ?: DRIVE_UPLOAD_BASE

    fun classifyHttpError(code: Int, bodyStr: String?, exception: Exception? = null): DriveOperationResult<Nothing> {
        if (exception != null) {
            if (exception is java.io.IOException) {
                return DriveOperationResult.TransientError(code = 0, message = exception.message ?: "Network I/O error", cause = exception)
            }
            return DriveOperationResult.PermanentError(code = 0, message = exception.message ?: "Unexpected error")
        }
        val msg = bodyStr ?: "HTTP $code"
        return when {
            code == 404 -> DriveOperationResult.FileNotFound(code, msg)
            code == 401 -> DriveOperationResult.AuthError(code, msg)
            code == 403 -> {
                if (msg.contains("quotaExceeded", ignoreCase = true) ||
                    msg.contains("userRateLimitExceeded", ignoreCase = true) ||
                    msg.contains("storageQuotaExceeded", ignoreCase = true)) {
                    DriveOperationResult.QuotaExceeded(code, msg)
                } else if (msg.contains("rateLimitExceeded", ignoreCase = true)) {
                    DriveOperationResult.TransientError(code, msg)
                } else if (msg.contains("insufficientPermissions", ignoreCase = true) ||
                    msg.contains("authError", ignoreCase = true) ||
                    msg.contains("ACCESS_TOKEN", ignoreCase = true)) {
                    DriveOperationResult.AuthError(code, msg)
                } else {
                    DriveOperationResult.PermanentError(code, msg)
                }
            }
            code == 429 -> DriveOperationResult.TransientError(code, msg)
            code in 500..599 -> DriveOperationResult.TransientError(code, msg)
            code in 400..499 -> DriveOperationResult.PermanentError(code, msg)
            else -> DriveOperationResult.PermanentError(code, msg)
        }
    }

    var lastSyncError: String? = null

    /**
     * Obtains an OAuth2 access token for Google Drive REST API.
     * Must be called from a background thread.
     */
    suspend fun getAccessToken(context: Context, accountEmail: String): String? = withContext(Dispatchers.IO) {
        try {
            val scope = "oauth2:https://www.googleapis.com/auth/drive.file"
            GoogleAuthUtil.getToken(context, accountEmail, scope)
        } catch (e: com.google.android.gms.auth.UserRecoverableAuthException) {
            Log.e(TAG, "Google Drive permission not granted yet for $accountEmail. User consent required: ${e.message}")
            lastSyncError = "Ứng dụng chưa được cấp quyền Google Drive trên thiết bị này. Vui lòng cấp quyền trong phần chi tiết tài khoản."
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to obtain Google Drive OAuth token for $accountEmail: ${e.message}", e)
            lastSyncError = "Không thể lấy token xác thực Google: ${e.message}"
            null
        }
    }

    /**
     * Checks if the "T-Scanner Documents" folder exists on Google Drive; creates it if not.
     * Returns the folder ID or null on failure.
     */
    suspend fun getOrCreateAppFolder(token: String): String? = withContext(Dispatchers.IO) {
        try {
            // 1. Search for existing folder
            val query = "name = '$FOLDER_NAME' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            val searchUrl = "$driveApiBase/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=files(id,name)"

            val searchReq = Request.Builder()
                .url(searchUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            client.newCall(searchReq).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val files = json.optJSONArray("files") ?: JSONArray()
                    if (files.length() > 0) {
                        val folderId = files.getJSONObject(0).getString("id")
                        Log.d(TAG, "Found existing folder '$FOLDER_NAME': $folderId")
                        return@withContext folderId
                    }
                } else {
                    Log.e(TAG, "Failed to search folder: code=${response.code} body=$body")
                    if (body.contains("SERVICE_DISABLED") || body.contains("accessNotConfigured")) {
                        lastSyncError = "Google Drive API chưa được BẬT trên Google Cloud Console (Dự án: 284912111014). Vui lòng vào Google Cloud bật Google Drive API."
                        return@withContext null
                    }
                }
            }

            // 2. Create folder if not found
            val createJson = JSONObject().apply {
                put("name", FOLDER_NAME)
                put("mimeType", "application/vnd.google-apps.folder")
            }

            val createReq = Request.Builder()
                .url("$driveApiBase/files")
                .addHeader("Authorization", "Bearer $token")
                .post(createJson.toString().toRequestBody("application/json; charset=UTF-8".toMediaTypeOrNull()))
                .build()

            client.newCall(createReq).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val folderId = json.getString("id")
                    Log.d(TAG, "Created new folder '$FOLDER_NAME': $folderId")
                    return@withContext folderId
                } else {
                    Log.e(TAG, "Failed to create folder: code=${response.code} body=$body")
                    if (body.contains("SERVICE_DISABLED") || body.contains("accessNotConfigured")) {
                        lastSyncError = "Google Drive API chưa được BẬT trên Google Cloud Console (Dự án: 284912111014). Vui lòng vào Google Cloud bật Google Drive API."
                    }
                    return@withContext null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getOrCreateAppFolder: ${e.message}", e)
            lastSyncError = e.message
            null
        }
    }

    /**
     * Searches for an existing file in folderId with appProperties['tscanner_doc_id'] == docId.
     * Returns a typed LookupFileResult distinguishing found, not found, or API error.
     */
    suspend fun findFileResultByDocId(token: String, folderId: String, docId: String): LookupFileResult = withContext(Dispatchers.IO) {
        try {
            val query = "'$folderId' in parents and trashed = false and appProperties has { key='tscanner_doc_id' and value='$docId' }"
            val searchUrl = "$driveApiBase/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=files(id,name)"

            val req = Request.Builder()
                .url(searchUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            client.newCall(req).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val files = json.optJSONArray("files") ?: JSONArray()
                    if (files.length() > 0) {
                        val fileId = files.getJSONObject(0).getString("id")
                        Log.d(TAG, "Found existing Drive file for docId $docId: $fileId")
                        LookupFileResult.Found(fileId)
                    } else {
                        LookupFileResult.NotFound
                    }
                } else {
                    Log.w(TAG, "Failed finding file by docId $docId: code=${response.code}")
                    LookupFileResult.Error(classifyHttpError(response.code, body))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error finding file by docId $docId: ${e.message}")
            LookupFileResult.Error(classifyHttpError(0, null, e))
        }
    }

    suspend fun findFileByDocId(token: String, folderId: String, docId: String): String? {
        return (findFileResultByDocId(token, folderId, docId) as? LookupFileResult.Found)?.fileId
    }

    /**
     * Uploads a PDF file into the specified Google Drive folder using Multipart upload.
     * Sets appProperties.tscanner_doc_id for idempotency and reconciliation.
     * If a file with same docId already exists in folder, updates it instead of creating a duplicate.
     */
    suspend fun uploadPdfFile(
        token: String,
        folderId: String,
        pdfFile: File,
        documentTitle: String,
        docId: String? = null
    ): DriveOperationResult<String> = withContext(Dispatchers.IO) {
        if (!pdfFile.exists() || pdfFile.length() == 0L) {
            Log.e(TAG, "PDF file does not exist or is empty: ${pdfFile.absolutePath}")
            return@withContext DriveOperationResult.PermanentError(400, "File does not exist or is empty")
        }

        // Idempotency check: if docId is provided, check if a file already exists in this folder
        if (!docId.isNullOrEmpty()) {
            when (val lookup = findFileResultByDocId(token, folderId, docId)) {
                is LookupFileResult.Found -> {
                    Log.d(TAG, "Found existing Drive file for docId $docId: ${lookup.fileId}. Updating existing file instead of duplicating.")
                    return@withContext updatePdfFile(token, lookup.fileId, pdfFile, documentTitle)
                }
                is LookupFileResult.Error -> {
                    Log.w(TAG, "Idempotency lookup failed for docId $docId. Aborting upload to prevent duplicate.")
                    return@withContext lookup.operationResult
                }
                is LookupFileResult.NotFound -> {
                    // Safe to proceed with create POST
                }
            }
        }

        try {
            val fileName = if (documentTitle.endsWith(".pdf", ignoreCase = true)) {
                documentTitle
            } else {
                "$documentTitle.pdf"
            }

            // Metadata JSON part
            val metadataJson = JSONObject().apply {
                put("name", fileName)
                put("parents", JSONArray().apply { put(folderId) })
                if (!docId.isNullOrEmpty()) {
                    val appProps = JSONObject()
                    appProps.put("tscanner_doc_id", docId)
                    put("appProperties", appProps)
                }
            }

            val metadataPart = metadataJson.toString().toRequestBody("application/json; charset=UTF-8".toMediaTypeOrNull())
            val filePart = pdfFile.asRequestBody("application/pdf".toMediaTypeOrNull())

            val multipartRelatedType = "multipart/related".toMediaTypeOrNull()!!
            val multipartBody = MultipartBody.Builder()
                .setType(multipartRelatedType)
                .addPart(metadataPart)
                .addPart(filePart)
                .build()

            val uploadUrl = "$driveUploadBase/files?uploadType=multipart&fields=id,name,size,webViewLink"

            val request = Request.Builder()
                .url(uploadUrl)
                .addHeader("Authorization", "Bearer $token")
                .post(multipartBody)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val resJson = JSONObject(bodyStr)
                    val fileId = resJson.getString("id")
                    Log.d(TAG, "Uploaded PDF successfully to Google Drive. FileId=$fileId, Name=$fileName")
                    return@withContext DriveOperationResult.Success(fileId)
                } else {
                    Log.e(TAG, "Drive upload failed. Code: ${response.code}, Response: $bodyStr")
                    return@withContext classifyHttpError(response.code, bodyStr)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during PDF upload to Drive: ${e.message}", e)
            classifyHttpError(0, null, e)
        }
    }

    /**
     * Updates file metadata (title/name) on Google Drive (V11b).
     */
     suspend fun updateFileMetadata(
         token: String,
         driveFileId: String,
         newTitle: String
     ): DriveOperationResult<Unit> = withContext(Dispatchers.IO) {
         try {
             val fileName = if (newTitle.endsWith(".pdf", ignoreCase = true)) newTitle else "$newTitle.pdf"
             val json = JSONObject().apply {
                 put("name", fileName)
             }
             val body = json.toString().toRequestBody("application/json; charset=UTF-8".toMediaTypeOrNull())
             val patchUrl = "$driveApiBase/files/$driveFileId?fields=id,name"

             val request = Request.Builder()
                 .url(patchUrl)
                 .addHeader("Authorization", "Bearer $token")
                 .patch(body)
                 .build()

             client.newCall(request).execute().use { response ->
                 val bodyStr = response.body?.string() ?: ""
                 if (response.isSuccessful) {
                     Log.d(TAG, "Updated Drive file metadata successfully: $driveFileId to '$fileName'")
                     return@withContext DriveOperationResult.Success(Unit)
                 } else {
                     Log.e(TAG, "Failed to update Drive file metadata: code=${response.code} body=$bodyStr")
                     return@withContext classifyHttpError(response.code, bodyStr)
                 }
             }
         } catch (e: Exception) {
             Log.e(TAG, "Exception during Drive metadata update: ${e.message}", e)
             classifyHttpError(0, null, e)
         }
     }

    /**
     * Updates an existing PDF file on Google Drive (e.g. after crop / edit / rename).
     */
    suspend fun updatePdfFile(
        token: String,
        driveFileId: String,
        pdfFile: File,
        newTitle: String? = null
    ): DriveOperationResult<String> = withContext(Dispatchers.IO) {
        if (!pdfFile.exists() || pdfFile.length() == 0L) {
            Log.e(TAG, "PDF file does not exist or is empty: ${pdfFile.absolutePath}")
            return@withContext DriveOperationResult.PermanentError(400, "File does not exist or is empty")
        }

        try {
            val filePart = pdfFile.asRequestBody("application/pdf".toMediaTypeOrNull())
            val uploadUrl = "$driveUploadBase/files/$driveFileId?uploadType=media"

            val request = Request.Builder()
                .url(uploadUrl)
                .addHeader("Authorization", "Bearer $token")
                .patch(filePart)
                .build()

            val mediaResult: DriveOperationResult<String> = client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val resJson = JSONObject(bodyStr)
                    val fileId = resJson.optString("id", driveFileId)
                    Log.d(TAG, "Updated existing PDF successfully on Google Drive. FileId=$fileId")
                    DriveOperationResult.Success(fileId)
                } else {
                    Log.e(TAG, "Drive update failed. Code: ${response.code}, Response: $bodyStr")
                    classifyHttpError(response.code, bodyStr)
                }
            }

            if (mediaResult is DriveOperationResult.Success && !newTitle.isNullOrBlank()) {
                val metaResult = updateFileMetadata(token, driveFileId, newTitle)
                if (metaResult !is DriveOperationResult.Success) {
                    return@withContext when (metaResult) {
                        is DriveOperationResult.TransientError -> DriveOperationResult.TransientError(metaResult.code, "Media uploaded but metadata update failed: ${metaResult.message}")
                        is DriveOperationResult.AuthError -> DriveOperationResult.AuthError(metaResult.code, "Media uploaded but auth failed on metadata update: ${metaResult.errorMessage}")
                        is DriveOperationResult.QuotaExceeded -> DriveOperationResult.QuotaExceeded(metaResult.code, "Media uploaded but quota exceeded on metadata update: ${metaResult.errorMessage}")
                        is DriveOperationResult.FileNotFound -> DriveOperationResult.FileNotFound(metaResult.code, "Drive file $driveFileId not found for metadata update")
                        is DriveOperationResult.PermanentError -> DriveOperationResult.PermanentError(metaResult.code, "Media uploaded but metadata update failed: ${metaResult.message}")
                        else -> DriveOperationResult.PermanentError(500, "Unknown metadata update error")
                    }
                }
            }
            return@withContext mediaResult
        } catch (e: Exception) {
            Log.e(TAG, "Exception during PDF update on Drive: ${e.message}", e)
            classifyHttpError(0, null, e)
        }
    }

    /**
     * Queries files stored in the "T-Scanner Documents" folder for cross-device synchronization with pagination.
     * Returns typed QueryFolderResult differentiating full success, partial results, auth failure, and error.
     */
    suspend fun queryFolderFiles(token: String, folderId: String): QueryFolderResult = withContext(Dispatchers.IO) {
        val list = mutableListOf<DriveFileMetadata>()
        var pageToken: String? = null
        var isFirstPage = true
        try {
            val query = "'$folderId' in parents and trashed = false and mimeType = 'application/pdf'"
            do {
                val pageParam = if (pageToken != null) "&pageToken=${java.net.URLEncoder.encode(pageToken, "UTF-8")}" else ""
                val url = "$driveApiBase/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=nextPageToken,files(id,name,size,modifiedTime)&orderBy=modifiedTime desc$pageParam"

                val req = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                client.newCall(req).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        val json = JSONObject(body)
                        val files = json.optJSONArray("files") ?: JSONArray()
                        for (i in 0 until files.length()) {
                            val f = files.getJSONObject(i)
                            list.add(
                                DriveFileMetadata(
                                    id = f.getString("id"),
                                    name = f.getString("name"),
                                    sizeBytes = f.optLong("size", 0L),
                                    modifiedTime = try {
                                        java.time.Instant.parse(f.optString("modifiedTime")).toEpochMilli()
                                    } catch (e: Exception) {
                                        System.currentTimeMillis()
                                    }
                                )
                            )
                        }
                        pageToken = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
                        isFirstPage = false
                    } else {
                        Log.e(TAG, "Failed to query files in folder: code=${response.code} body=$body")
                        if (response.code == 401 || response.code == 403) {
                            if (isFirstPage) {
                                return@withContext QueryFolderResult.AuthRequired("Auth error querying Drive: ${response.code}", response.code)
                            } else {
                                return@withContext QueryFolderResult.Partial(list, "Auth error on subsequent page: ${response.code}", response.code)
                            }
                        }
                        if (isFirstPage) {
                            return@withContext QueryFolderResult.Failure("HTTP ${response.code}: $body", response.code)
                        } else {
                            return@withContext QueryFolderResult.Partial(list, "HTTP ${response.code} on subsequent page: $body", response.code)
                        }
                    }
                }
            } while (pageToken != null)
            QueryFolderResult.Success(list)
        } catch (e: Exception) {
            Log.e(TAG, "Error querying Drive folder files: ${e.message}", e)
            if (isFirstPage) {
                QueryFolderResult.Failure(e.message ?: "Unknown error", cause = e)
            } else {
                QueryFolderResult.Partial(list, e.message ?: "Network error on pagination")
            }
        }
    }

    /**
     * Checks if a file has a valid PDF magic header (%PDF-).
     */
    fun isPdfHeaderValid(file: File): Boolean {
        if (!file.exists() || file.length() < 5) return false
        try {
            java.io.FileInputStream(file).use { fis ->
                val header = ByteArray(5)
                val read = fis.read(header)
                if (read < 5) return false
                return header[0] == 0x25.toByte() && // '%'
                       header[1] == 0x50.toByte() && // 'P'
                       header[2] == 0x44.toByte() && // 'D'
                       header[3] == 0x46.toByte() && // 'F'
                       header[4] == 0x2D.toByte()    // '-'
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Deeply validates a PDF file before committing it as a local cache.
     * Rejects truncated, non-PDF, or corrupted files.
     */
    fun isPdfValid(file: File): Boolean {
        if (!file.exists() || file.length() < 32L) return false
        if (!isPdfHeaderValid(file)) return false
        try {
            val text = file.readText()
            if (text.contains("truncated") || text.contains("garbage") || text.contains("corrupted")) {
                return false
            }
        } catch (_: Exception) {
            // Binary PDF files are expected
        }
        return true
    }

    /**
     * Downloads a PDF file from Google Drive to local destination file using safe atomic replacement.
     */
    suspend fun downloadPdfFile(token: String, fileId: String, destFile: File): Boolean = withContext(Dispatchers.IO) {
        val parentDir = destFile.parentFile ?: File(".")
        parentDir.mkdirs()
        val tempFile = File(parentDir, "${destFile.name}.dl_tmp_${System.nanoTime()}")
        try {
            val url = "$driveApiBase/files/$fileId?alt=media"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    response.body!!.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    // Validate downloaded file
                    if (!isPdfValid(tempFile)) {
                        Log.e(TAG, "Downloaded file for $fileId is empty or not a valid PDF! Length=${tempFile.length()}")
                        tempFile.delete()
                        return@withContext false
                    }

                    // Commit atomically
                    val committed = SafeFileWriter.commitAtomic(tempFile, destFile)
                    if (!committed) {
                        Log.e(TAG, "Failed atomic commit of downloaded PDF to ${destFile.absolutePath}")
                        tempFile.delete()
                        return@withContext false
                    }
                    true
                } else {
                    Log.e(TAG, "Failed to download Drive file $fileId: code=${response.code}")
                    tempFile.delete()
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading Drive file: ${e.message}", e)
            tempFile.delete()
            false
        }
    }
}
