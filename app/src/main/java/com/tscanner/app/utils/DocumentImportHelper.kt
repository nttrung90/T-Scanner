package com.tscanner.app.utils

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.tscanner.app.R
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.ui.viewer.PdfViewerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

sealed class ImportResult {
    data class Success(val document: DocumentItem) : ImportResult()
    object NoImagesSelected : ImportResult()
    sealed class Failure : ImportResult() {
        object ConversionFailed : Failure()
        data class PersistFailed(val document: DocumentItem, val cause: Throwable? = null) : Failure()
        data class ImageReadFailed(val uri: Uri, val index: Int, val cause: Throwable? = null) : Failure()
    }
}

interface DocumentImportDependencies {
    fun openInputStream(uri: Uri): InputStream?
    fun validateImage(file: File): Boolean
    fun persist(doc: DocumentItem): Boolean
    suspend fun convertImagesToPdf(imagePaths: List<String>, outputFile: File, addWatermark: Boolean): Boolean
    fun renderFirstPageThumbnail(pdfFile: File, thumbFile: File): Boolean
    fun showToast(messageRes: Int, vararg formatArgs: Any)
    fun openViewer(pdfPath: String, title: String)
    fun createDocumentTitle(): String
}

open class DefaultDocumentImportDependencies(
    private val repo: DocumentRepo,
    private val context: Context
) : DocumentImportDependencies {
    override fun openInputStream(uri: Uri): InputStream? =
        context.contentResolver.openInputStream(uri)

    override fun validateImage(file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.outWidth > 0 && options.outHeight > 0
        } catch (e: Exception) {
            false
        }
    }

    override fun persist(doc: DocumentItem): Boolean =
        repo.addDocument(doc)

    override suspend fun convertImagesToPdf(imagePaths: List<String>, outputFile: File, addWatermark: Boolean): Boolean =
        PdfConverterHelper.createPdfFromImages(imagePaths, outputFile, addWatermark)

    override fun renderFirstPageThumbnail(pdfFile: File, thumbFile: File): Boolean =
        PdfConverterHelper.renderPdfFirstPage(pdfFile, thumbFile)

    override fun showToast(messageRes: Int, vararg formatArgs: Any) {
        val text = if (formatArgs.isNotEmpty()) {
            context.getString(messageRes, *formatArgs)
        } else {
            context.getString(messageRes)
        }
        val mainLooper = try { Looper.getMainLooper() } catch (_: Exception) { null }
        if (mainLooper != null && Looper.myLooper() == mainLooper) {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        } else if (mainLooper != null) {
            Handler(mainLooper).post {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.i("DefaultDocImportDeps", "Toast: $text")
        }
    }

    override fun openViewer(pdfPath: String, title: String) {
        val mainLooper = try { Looper.getMainLooper() } catch (_: Exception) { null }
        if (mainLooper != null && Looper.myLooper() == mainLooper) {
            PdfViewerActivity.start(context, pdfPath, title)
        } else if (mainLooper != null) {
            Handler(mainLooper).post {
                PdfViewerActivity.start(context, pdfPath, title)
            }
        } else {
            PdfViewerActivity.start(context, pdfPath, title)
        }
    }

    override fun createDocumentTitle(): String =
        context.getString(R.string.imported_images_title) + " " + FileUtils.formatDate(System.currentTimeMillis())
}

object DocumentImportHelper {

    private const val TAG = "DocumentImportHelper"

    /**
     * Imports multiple image URIs, converts them to a single PDF with watermark rules,
     * generates a thumbnail, saves the document to DocumentRepo, displays a success toast,
     * and launches PdfViewerActivity.
     *
     * Returns an [ImportResult] distinguishing success, cancellation, conversion failure,
     * and persist failure.
     */
    suspend fun importImagesToPdf(
        context: Context,
        uris: List<Uri>,
        repo: DocumentRepo
    ): ImportResult {
        return importImagesToPdf(
            context = context,
            uris = uris,
            deps = DefaultDocumentImportDependencies(repo, context)
        )
    }

    suspend fun importImagesToPdf(
        context: Context,
        uris: List<Uri>,
        deps: DocumentImportDependencies = DefaultDocumentImportDependencies(DocumentRepo.getInstance(context), context)
    ): ImportResult {
        if (uris.isEmpty()) return ImportResult.NoImagesSelected

        val result = withContext(Dispatchers.IO) {
            val docId = UUID.randomUUID().toString()
            val tempDir = FileUtils.getTempScanSessionDir(context, docId)
            val docDir = FileUtils.getDocumentsDir(context)
            val pdfFile = File(docDir, "imported_${docId}.pdf")
            val thumbsDir = FileUtils.getThumbnailsDir(context)
            val thumbFile = File(thumbsDir, "thumb_${docId}.jpg")
            var isPersisted = false

            try {
                val tempPagePaths = mutableListOf<String>()

                for ((index, uri) in uris.withIndex()) {
                    val tempFile = File(tempDir, "imported_${index + 1}.tmp")
                    val targetFile = File(tempDir, "imported_${index + 1}.jpg")
                    try {
                        val inputStream = deps.openInputStream(uri)
                            ?: throw java.io.IOException("Cannot open input stream for URI: $uri")
                        var bytesCopied = 0L
                        inputStream.use { input ->
                            FileOutputStream(tempFile).use { output ->
                                bytesCopied = input.copyTo(output)
                            }
                        }
                        if (bytesCopied <= 0L || !tempFile.exists() || tempFile.length() <= 0L) {
                            throw java.io.IOException("Empty or incomplete image stream for URI: $uri")
                        }
                        if (!deps.validateImage(tempFile)) {
                            throw java.io.IOException("Invalid image content or corrupted image for URI: $uri")
                        }
                        if (!tempFile.renameTo(targetFile)) {
                            tempFile.copyTo(targetFile, overwrite = true)
                            tempFile.delete()
                        }
                        tempPagePaths.add(targetFile.absolutePath)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Failed reading image at index $index: $uri", e)
                        return@withContext ImportResult.Failure.ImageReadFailed(uri, index, e)
                    }
                }

                if (tempPagePaths.size != uris.size || tempPagePaths.isEmpty()) {
                    return@withContext ImportResult.Failure.ConversionFailed
                }

                // Convert images to PDF
                val success = deps.convertImagesToPdf(
                    tempPagePaths,
                    pdfFile,
                    WatermarkHelper.shouldApplyWatermark(context)
                )

                if (!success || !pdfFile.exists()) {
                    return@withContext ImportResult.Failure.ConversionFailed
                }

                val thumbPath = if (deps.renderFirstPageThumbnail(pdfFile, thumbFile)) {
                    thumbFile.absolutePath
                } else null

                val item = DocumentItem(
                    id = docId,
                    title = deps.createDocumentTitle(),
                    pdfPath = pdfFile.absolutePath,
                    thumbnailPath = thumbPath,
                    pagePaths = emptyList(),
                    pageCount = tempPagePaths.size,
                    sizeBytes = pdfFile.length(),
                    createdAt = System.currentTimeMillis()
                )

                try {
                    val persistOk = deps.persist(item)
                    if (persistOk) {
                        isPersisted = true
                        ImportResult.Success(item)
                    } else {
                        Log.e(TAG, "Failed to persist document to repository: $docId")
                        ImportResult.Failure.PersistFailed(item)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Exception persisting document $docId", e)
                    ImportResult.Failure.PersistFailed(item, e)
                }
            } finally {
                FileUtils.deleteTempSession(context, docId)
                if (!isPersisted) {
                    if (pdfFile.exists() && !pdfFile.delete()) {
                        Log.w(TAG, "Failed to delete unpersisted PDF file: ${pdfFile.absolutePath}")
                    }
                    if (thumbFile.exists() && !thumbFile.delete()) {
                        Log.w(TAG, "Failed to delete unpersisted thumbnail file: ${thumbFile.absolutePath}")
                    }
                }
            }
        }

        // Dispatch UI side-effects on caller thread (outside Dispatchers.IO)
        when (result) {
            is ImportResult.Success -> {
                deps.showToast(R.string.imported_images_success_format, result.document.pageCount)
                result.document.pdfPath?.let { path ->
                    deps.openViewer(path, result.document.title)
                }
            }
            is ImportResult.NoImagesSelected -> {}
            is ImportResult.Failure.ConversionFailed -> {
                deps.showToast(R.string.pdf_create_failed)
            }
            is ImportResult.Failure.ImageReadFailed -> {
                deps.showToast(R.string.crop_image_read_error)
            }
            is ImportResult.Failure.PersistFailed -> {
                deps.showToast(R.string.pdf_save_error)
            }
        }

        return result
    }
}
