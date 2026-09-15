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
            val searchUrl = "$DRIVE_API_BASE/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=files(id,name)"

            val searchReq = Request.Builder()
                .url(searchUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(searchReq).execute().use { response ->
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
                .url("$DRIVE_API_BASE/files")
                .addHeader("Authorization", "Bearer $token")
                .post(createJson.toString().toRequestBody("application/json; charset=UTF-8".toMediaTypeOrNull()))
                .build()

            httpClient.newCall(createReq).execute().use { response ->
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
     * Uploads a PDF file into the specified Google Drive folder using Multipart upload.
     * Returns the created file ID on Drive or null on failure.
     */
    suspend fun uploadPdfFile(
        token: String,
        folderId: String,
        pdfFile: File,
        documentTitle: String
    ): String? = withContext(Dispatchers.IO) {
        if (!pdfFile.exists() || pdfFile.length() == 0L) {
            Log.e(TAG, "PDF file does not exist or is empty: ${pdfFile.absolutePath}")
            return@withContext null
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
            }

            val metadataPart = metadataJson.toString().toRequestBody("application/json; charset=UTF-8".toMediaTypeOrNull())
            val filePart = pdfFile.asRequestBody("application/pdf".toMediaTypeOrNull())

            val multipartRelatedType = "multipart/related".toMediaTypeOrNull()!!
            val multipartBody = MultipartBody.Builder()
                .setType(multipartRelatedType)
                .addPart(metadataPart)
                .addPart(filePart)
                .build()

            val uploadUrl = "$DRIVE_UPLOAD_BASE/files?uploadType=multipart&fields=id,name,size,webViewLink"

            val request = Request.Builder()
                .url(uploadUrl)
                .addHeader("Authorization", "Bearer $token")
                .post(multipartBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val resJson = JSONObject(bodyStr)
                    val fileId = resJson.getString("id")
                    Log.d(TAG, "Uploaded PDF successfully to Google Drive. FileId=$fileId, Name=$fileName")
                    return@withContext fileId
                } else {
                    Log.e(TAG, "Drive upload failed. Code: ${response.code}, Response: $bodyStr")
                    return@withContext null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during PDF upload to Drive: ${e.message}", e)
            null
        }
    }

    /**
     * Updates an existing PDF file on Google Drive (e.g. after crop / edit).
     */
    suspend fun updatePdfFile(
        token: String,
        driveFileId: String,
        pdfFile: File
    ): String? = withContext(Dispatchers.IO) {
        if (!pdfFile.exists() || pdfFile.length() == 0L) {
            Log.e(TAG, "PDF file does not exist or is empty: ${pdfFile.absolutePath}")
            return@withContext null
        }

        try {
            val filePart = pdfFile.asRequestBody("application/pdf".toMediaTypeOrNull())
            val uploadUrl = "$DRIVE_UPLOAD_BASE/files/$driveFileId?uploadType=media"

            val request = Request.Builder()
                .url(uploadUrl)
                .addHeader("Authorization", "Bearer $token")
                .patch(filePart)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val resJson = JSONObject(bodyStr)
                    val fileId = resJson.optString("id", driveFileId)
                    Log.d(TAG, "Updated existing PDF successfully on Google Drive. FileId=$fileId")
                    return@withContext fileId
                } else {
                    Log.e(TAG, "Drive update failed. Code: ${response.code}, Response: $bodyStr")
                    return@withContext null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during PDF update on Drive: ${e.message}", e)
            null
        }
    }

    /**
     * Queries files stored in the "T-Scanner Documents" folder for cross-device synchronization with pagination.
     */
    suspend fun queryFolderFiles(token: String, folderId: String): List<DriveFileMetadata> = withContext(Dispatchers.IO) {
        val list = mutableListOf<DriveFileMetadata>()
        var pageToken: String? = null
        try {
            val query = "'$folderId' in parents and trashed = false and mimeType = 'application/pdf'"
            do {
                val pageParam = if (pageToken != null) "&pageToken=${java.net.URLEncoder.encode(pageToken, "UTF-8")}" else ""
                val url = "$DRIVE_API_BASE/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=nextPageToken,files(id,name,size,modifiedTime)&orderBy=modifiedTime desc$pageParam"

                val req = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                httpClient.newCall(req).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
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
                    } else {
                        Log.e(TAG, "Failed to query files in folder: code=${response.code}")
                        pageToken = null
                    }
                }
            } while (pageToken != null)
        } catch (e: Exception) {
            Log.e(TAG, "Error querying Drive folder files: ${e.message}", e)
        }
        list
    }

    /**
     * Downloads a PDF file from Google Drive to local destination file.
     */
    suspend fun downloadPdfFile(token: String, fileId: String, destFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$DRIVE_API_BASE/files/$fileId?alt=media"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    destFile.parentFile?.mkdirs()
                    response.body!!.byteStream().use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    true
                } else {
                    Log.e(TAG, "Failed to download Drive file $fileId: code=${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading Drive file: ${e.message}", e)
            false
        }
    }
}
