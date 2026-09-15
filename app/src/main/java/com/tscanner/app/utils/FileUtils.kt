package com.tscanner.app.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileUtils {

    fun getDocumentsDir(context: Context): File {
        val dir = File(context.filesDir, "documents")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getImagesDir(context: Context): File {
        val dir = File(context.filesDir, "images")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getExportsDir(context: Context): File {
        val dir = File(context.filesDir, "exports")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getTempScanSessionDir(context: Context, sessionId: String): File {
        val baseTempDir = File(context.cacheDir, "temp_scan")
        val sessionDir = File(baseTempDir, sessionId)
        if (!sessionDir.exists()) sessionDir.mkdirs()
        return sessionDir
    }

    fun getPdfPreviewDir(context: Context): File {
        val dir = File(context.cacheDir, "pdf_preview")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getThumbnailsDir(context: Context): File {
        val dir = File(context.filesDir, ".thumbnails")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun copyUriToAppStorage(context: Context, uri: Uri, targetDir: File, fileNamePrefix: String): File? {
        return try {
            val extension = getFileExtension(context, uri) ?: "dat"
            val targetFile = File(targetDir, "${fileNamePrefix}_${System.currentTimeMillis()}.$extension")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            targetFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getFileExtension(context: Context, uri: Uri): String? {
        val mimeType = context.contentResolver.getType(uri)
        if (mimeType != null) {
            if (mimeType.contains("pdf")) return "pdf"
            if (mimeType.contains("jpeg") || mimeType.contains("jpg")) return "jpg"
            if (mimeType.contains("png")) return "png"
        }
        val name = getFileName(context, uri)
        val dotIndex = name.lastIndexOf('.')
        return if (dotIndex > 0) name.substring(dotIndex + 1) else null
    }

    fun getFileName(context: Context, uri: Uri): String {
        var result = "document_${System.currentTimeMillis()}"
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            val name = cursor.getString(nameIndex)
                            if (!name.isNullOrBlank()) result = name
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            uri.path?.let { path ->
                val cut = path.lastIndexOf('/')
                if (cut != -1) result = path.substring(cut + 1)
            }
        }
        return result
    }

    fun formatFileSize(sizeBytes: Long): String {
        if (sizeBytes <= 0) return "0 KB"
        val kb = sizeBytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(Locale.getDefault(), "%.1f MB", mb)
            else -> String.format(Locale.getDefault(), "%.0f KB", kb)
        }
    }

    fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun sanitizeFileName(name: String): String {
        var clean = name.replace('/', '-')
            .replace('\\', '-')
            .replace(':', '-')
            .replace(Regex("[*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.isBlank()) {
            clean = "PDF_${System.currentTimeMillis()}"
        }
        return clean
    }

    fun savePdfToDownloads(context: Context, file: File): Uri? {
        return saveFileToDownloads(context, file, "application/pdf")
    }

    fun saveFileToDownloads(
        context: Context,
        file: File,
        mimeType: String = "application/octet-stream",
        customName: String? = null
    ): Uri? {
        val fileName = customName ?: file.name
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                var uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    // Fallback to Files table
                    uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
                }
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                }
                uri
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val dest = File(downloadsDir, fileName)
                file.copyTo(dest, overwrite = true)
                Uri.fromFile(dest)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun saveFilesToDownloads(
        context: Context,
        files: List<File>,
        mimeType: String = "image/jpeg"
    ): List<Uri> {
        val savedUris = mutableListOf<Uri>()
        for (file in files) {
            val uri = saveFileToDownloads(context, file, mimeType)
            if (uri != null) {
                savedUris.add(uri)
            }
        }
        return savedUris
    }

    fun copyFileToUri(context: Context, sourceFile: File, targetUri: Uri): Boolean {
        return try {
            context.contentResolver.openOutputStream(targetUri)?.use { out ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun saveFileToTreeUri(
        context: Context,
        sourceFile: File,
        treeUri: Uri,
        targetFileName: String? = null,
        mimeType: String = "*/*"
    ): Uri? {
        return try {
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            val fileName = targetFileName ?: sourceFile.name
            val cleanMime = if (mimeType.isBlank()) "application/octet-stream" else mimeType
            val newFile = rootDoc.createFile(cleanMime, fileName) ?: return null
            context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }
            newFile.uri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun saveFilesToTreeUri(
        context: Context,
        sourceFiles: List<File>,
        treeUri: Uri,
        mimeType: String = "image/jpeg"
    ): List<Uri> {
        val savedUris = mutableListOf<Uri>()
        try {
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
            for (file in sourceFiles) {
                val newFile = rootDoc.createFile(mimeType, file.name)
                if (newFile != null) {
                    context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                        file.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    savedUris.add(newFile.uri)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return savedUris
    }

    fun getTreeDirectoryName(context: Context, treeUri: Uri): String {
        return try {
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            rootDoc?.name ?: "Thư mục đã chọn"
        } catch (e: Exception) {
            "Thư mục đã chọn"
        }
    }

    fun deleteTempSession(context: Context, sessionId: String): Long {
        return try {
            val baseTempDir = File(context.cacheDir, "temp_scan")
            val sessionDir = File(baseTempDir, sessionId)
            deleteDir(sessionDir)
        } catch (e: Exception) {
            e.printStackTrace()
            0L
        }
    }

    fun cleanOrphanedTempScans(context: Context, maxAgeHours: Int = 24) {
        try {
            val baseTempDir = File(context.cacheDir, "temp_scan")
            if (baseTempDir.exists() && baseTempDir.isDirectory) {
                val cutoffTime = System.currentTimeMillis() - (maxAgeHours * 60 * 60 * 1000L)
                baseTempDir.listFiles()?.forEach { sessionDir ->
                    if (sessionDir.isDirectory && sessionDir.lastModified() < cutoffTime) {
                        deleteDir(sessionDir)
                    }
                }
            }
            val previewDir = File(context.cacheDir, "pdf_preview")
            if (previewDir.exists() && previewDir.isDirectory) {
                deleteDirContents(previewDir)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearCache(context: Context): Long {
        var deletedBytes = 0L
        try {
            val cacheDir = context.cacheDir
            deletedBytes += deleteDirContents(cacheDir)

            // Also clean any legacy orphaned files in images directory if they are temporary
            val imgDir = File(context.filesDir, "images")
            if (imgDir.exists() && imgDir.isDirectory) {
                imgDir.listFiles()?.forEach { f ->
                    if (f.name.startsWith("page_") || f.name.startsWith("pdf_page_") || f.name.startsWith("thumb_")) {
                        deletedBytes += f.length()
                        f.delete()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return deletedBytes
    }

    fun deleteDirContents(dir: File?): Long {
        var bytes = 0L
        if (dir != null && dir.isDirectory) {
            dir.listFiles()?.forEach { child ->
                if (child.isDirectory) {
                    bytes += deleteDir(child)
                } else {
                    bytes += child.length()
                    child.delete()
                }
            }
        }
        return bytes
    }

    fun deleteDir(dir: File?): Long {
        var bytes = 0L
        if (dir != null && dir.exists()) {
            if (dir.isDirectory) {
                dir.listFiles()?.forEach { child ->
                    bytes += deleteDir(child)
                }
            }
            bytes += dir.length()
            dir.delete()
        }
        return bytes
    }
}
