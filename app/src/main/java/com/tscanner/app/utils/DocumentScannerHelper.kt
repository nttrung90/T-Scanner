package com.tscanner.app.utils

import android.app.Activity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
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
    val tempPagePaths: List<String>
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
                    onError(e.localizedMessage ?: "Failed to start scanner")
                }
            }
            .addOnFailureListener { e ->
                onError(e.localizedMessage ?: "Scanner error")
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
                    onError(e.localizedMessage ?: "Failed to start scanner")
                }
            }
            .addOnFailureListener { e ->
                onError(e.localizedMessage ?: "Scanner error")
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
            onError("Scan failed")
            return
        }

        val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        if (scanResult == null) {
            onError("Could not parse scan result")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val sessionId = UUID.randomUUID().toString()
            val tempDir = FileUtils.getTempScanSessionDir(activity, sessionId)

            // 1. Copy page images in parallel with 64KB high-speed I/O buffer
            val pages = scanResult.pages ?: emptyList()
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
                            if (pageFile.exists() && pageFile.length() > 0) pageFile.absolutePath else null
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                }
            }
            val pagePaths = deferredPages.awaitAll().filterNotNull()

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
                tempPagePaths = pagePaths
            )

            withContext(Dispatchers.Main) {
                onSuccess(sessionResult)
            }
        }
    }
}
