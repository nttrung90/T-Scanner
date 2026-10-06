package com.tscanner.app.utils

import android.app.Activity
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.tscanner.app.R
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScanSessionResult(
    val sessionId: String,
    val tempPdfPath: String?,
    val tempPagePaths: List<String>,
    val totalPagesExpected: Int = tempPagePaths.size
)

class DocumentScannerHelper(private val activity: Activity) {

    // Pre-configured options for highest quality (ML-enabled finger removal, stain removal, shadow removal, and auto-filters)
    private val defaultOptions = GmsDocumentScannerOptions.Builder()
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
        .setGalleryImportAllowed(true)
        .build()

    private val idCardOptions = GmsDocumentScannerOptions.Builder()
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .setPageLimit(2)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
        .setGalleryImportAllowed(true)
        .build()

    // Pre-warmed clients to eliminate IPC binder setup latency
    private val defaultClient by lazy { GmsDocumentScanning.getClient(defaultOptions) }
    private val idCardClient by lazy { GmsDocumentScanning.getClient(idCardOptions) }

    fun startScan(
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        scannerMode: Int = GmsDocumentScannerOptions.SCANNER_MODE_FULL,
        onError: (String) -> Unit
    ) {
        val client = if (scannerMode == GmsDocumentScannerOptions.SCANNER_MODE_FULL) {
            defaultClient
        } else {
            val customOptions = GmsDocumentScannerOptions.Builder()
                .setScannerMode(scannerMode)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setGalleryImportAllowed(true)
                .build()
            GmsDocumentScanning.getClient(customOptions)
        }

        client.getStartScanIntent(activity)
            .addOnSuccessListener { intentSender ->
                try {
                    launcher.launch(IntentSenderRequest.Builder(intentSender).build())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start scanner launcher: ${e.message}", e)
                    onError(getFailedToStartErrorMessage(activity))
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Google Document Scanner client error: ${e.message}", e)
                onError(getScannerGeneralErrorMessage(activity))
            }
    }

    fun startIdCardScan(
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        onError: (String) -> Unit
    ) {
        idCardClient.getStartScanIntent(activity)
            .addOnSuccessListener { intentSender ->
                try {
                    launcher.launch(IntentSenderRequest.Builder(intentSender).build())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start ID card scanner launcher: ${e.message}", e)
                    onError(getFailedToStartErrorMessage(activity))
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Google ID Card Scanner client error: ${e.message}", e)
                onError(getScannerGeneralErrorMessage(activity))
            }
    }

    fun handleScanResult(
        result: ActivityResult,
        onSuccess: (ScanSessionResult) -> Unit,
        onCancelled: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (result.resultCode == Activity.RESULT_CANCELED) {
            onCancelled()
            return
        }

        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            onError(getScanFailedErrorMessage(activity))
            return
        }

        val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        if (scanResult == null) {
            onError(getParseFailedErrorMessage(activity))
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val sessionId = UUID.randomUUID().toString()
            val tempDir = FileUtils.getTempScanSessionDir(activity, sessionId)

            // 1. Copy page images in parallel with 64KB high-speed I/O buffer
            val pages = scanResult.pages ?: emptyList()
            if (pages.isEmpty()) {
                withContext(Dispatchers.Main) {
                    onError(getNoPagesErrorMessage(activity))
                }
                return@launch
            }

            val deferredPages = pages.mapIndexed { index, page ->
                async(Dispatchers.IO) {
                    try {
                        val pageFile = File(tempDir, "page_${index + 1}.jpg")
                        val stream = activity.contentResolver.openInputStream(page.imageUri)
                        if (stream != null) {
                            stream.buffered(65536).use { input ->
                                FileOutputStream(pageFile).buffered(65536).use { output ->
                                    input.copyTo(output, bufferSize = 65536)
                                }
                            }
                            if (pageFile.exists() && pageFile.length() > 0 && SafeFileWriter.validateImage(pageFile)) {
                                pageFile.absolutePath
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                }
            }
            val pagePathResults = deferredPages.awaitAll()
            val failedIndices = pagePathResults.mapIndexedNotNull { idx, path -> if (path == null) idx + 1 else null }
            if (failedIndices.isNotEmpty()) {
                FileUtils.deleteDir(tempDir)
                withContext(Dispatchers.Main) {
                    val formattedIndices = failedIndices.joinToString(", ")
                    onError(getImportPagesFailedErrorMessage(activity, formattedIndices, pages.size))
                }
                return@launch
            }
            val pagePaths = pagePathResults.filterNotNull()

            // 2. Copy generated PDF to temporary buffer if present
            var savedPdfPath: String? = null
            scanResult.pdf?.let { pdf ->
                try {
                    val pdfFile = File(tempDir, "scan_preview.pdf")
                    val stream = activity.contentResolver.openInputStream(pdf.uri)
                    if (stream != null) {
                        stream.buffered(65536).use { input ->
                            FileOutputStream(pdfFile).buffered(65536).use { output ->
                                input.copyTo(output, bufferSize = 65536)
                            }
                        }
                        if (pdfFile.exists() && pdfFile.length() > 0) {
                            savedPdfPath = pdfFile.absolutePath
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val sessionResult = ScanSessionResult(
                sessionId = sessionId,
                tempPdfPath = savedPdfPath,
                tempPagePaths = pagePaths,
                totalPagesExpected = pages.size
            )

            withContext(Dispatchers.Main) {
                onSuccess(sessionResult)
            }
        }
    }

    companion object {
        private const val TAG = "DocumentScannerHelper"

        fun getFailedToStartErrorMessage(context: android.content.Context): String =
            context.getString(R.string.scanner_err_failed_to_start)

        fun getScannerGeneralErrorMessage(context: android.content.Context): String =
            context.getString(R.string.scanner_err_general)

        fun getScanFailedErrorMessage(context: android.content.Context): String =
            context.getString(R.string.scanner_err_scan_failed)

        fun getParseFailedErrorMessage(context: android.content.Context): String =
            context.getString(R.string.scanner_err_parse_failed)

        fun getNoPagesErrorMessage(context: android.content.Context): String =
            context.getString(R.string.scanner_err_no_pages)

        fun getImportPagesFailedErrorMessage(context: android.content.Context, failedIndices: String, total: Int): String =
            context.getString(R.string.scanner_err_import_pages_failed, failedIndices, total)
    }
}
