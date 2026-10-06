package com.tscanner.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

object TesseractOcrHelper {

    private const val TAG = "TesseractOcrHelper"
    private const val TESS_DIR = "tesseract"
    private const val TESSDATA_SUBDIR = "tessdata"
    private const val VIE_DATA = "vie.traineddata"
    private const val ENG_DATA = "eng.traineddata"

    // Expected exact file sizes from tessdata_fast
    private const val VIE_EXPECTED_SIZE = 531275L
    private const val ENG_EXPECTED_SIZE = 4113088L

    private val tessDataLock = Any()

    /**
     * Ensures traineddata files exist in internal storage: context.filesDir/tesseract/tessdata/
     * Returns the root tesseract directory path (required by TessBaseAPI.init).
     */
    suspend fun prepareTessData(context: Context): String = withContext(Dispatchers.IO) {
        synchronized(tessDataLock) {
            val rootDir = File(context.filesDir, TESS_DIR)
            val tessdataDir = File(rootDir, TESSDATA_SUBDIR)
            if (!tessdataDir.exists()) {
                tessdataDir.mkdirs()
            }

            copyAssetFileIfNeeded(context, "$TESSDATA_SUBDIR/$VIE_DATA", File(tessdataDir, VIE_DATA), VIE_EXPECTED_SIZE)
            copyAssetFileIfNeeded(context, "$TESSDATA_SUBDIR/$ENG_DATA", File(tessdataDir, ENG_DATA), ENG_EXPECTED_SIZE)

            rootDir.absolutePath
        }
    }

    private fun copyAssetFileIfNeeded(context: Context, assetPath: String, destFile: File, expectedSize: Long) {
        if (destFile.exists() && destFile.length() > 0L) {
            // Already present and non-empty
            if (destFile.length() == expectedSize || expectedSize <= 0L) {
                return
            }
        }

        val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (tempFile.exists() && tempFile.length() > 0L) {
                if (destFile.exists()) {
                    destFile.delete()
                }
                val renamed = tempFile.renameTo(destFile)
                if (!renamed) {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                }
                Log.d(TAG, "Successfully extracted $assetPath to ${destFile.absolutePath} (${destFile.length()} bytes)")
            }
        } catch (e: Exception) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            Log.e(TAG, "Error extracting asset: $assetPath: ${e.message}")
        }
    }

    /**
     * Interface to abstract TessBaseAPI operations for unit testing and JVM isolation.
     */
    interface TessApiDriver {
        fun init(dataPath: String, language: String): Boolean
        fun setPageSegMode(mode: Int)
        fun setImage(bitmap: Bitmap)
        fun getUTF8Text(): String?
        fun getPageDocument(bitmapWidth: Int, bitmapHeight: Int, language: String): com.tscanner.app.ocr.model.OcrPage? = null
        fun recycle()
    }

    class RealTessApiDriver(private val api: TessBaseAPI = TessBaseAPI()) : TessApiDriver {
        override fun init(dataPath: String, language: String): Boolean = api.init(dataPath, language)
        override fun setPageSegMode(mode: Int) = api.setPageSegMode(mode)
        override fun setImage(bitmap: Bitmap) = api.setImage(bitmap)
        override fun getUTF8Text(): String? = api.getUTF8Text()

        override fun getPageDocument(bitmapWidth: Int, bitmapHeight: Int, language: String): com.tscanner.app.ocr.model.OcrPage? {
            var iterator: com.googlecode.tesseract.android.ResultIterator? = null
            try {
                iterator = api.resultIterator ?: return null
                iterator.begin()

                val safeWidth = if (bitmapWidth <= 0) 1000f else bitmapWidth.toFloat()
                val safeHeight = if (bitmapHeight <= 0) 1000f else bitmapHeight.toFloat()

                val ocrLines = mutableListOf<com.tscanner.app.ocr.model.OcrLine>()
                var lineIndex = 0
                var tokenIndex = 0

                do {
                    val lineText = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)?.trim() ?: ""
                    val lineBox = iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                    val lineConfidence = (iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE) / 100f).coerceIn(0f, 1f)

                    if (lineText.isNotBlank() && lineBox != null && lineBox.size >= 4) {
                        val l = (lineBox[0].toFloat() / safeWidth).coerceIn(0f, 1f)
                        val t = (lineBox[1].toFloat() / safeHeight).coerceIn(0f, 1f)
                        val r = (lineBox[2].toFloat() / safeWidth).coerceIn(l, 1f)
                        val b = (lineBox[3].toFloat() / safeHeight).coerceIn(t, 1f)

                        val tokens = mutableListOf<com.tscanner.app.ocr.model.OcrToken>()
                        do {
                            val wordText = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim() ?: ""
                            val wordBox = iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                            val wordConfidence = (iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD) / 100f).coerceIn(0f, 1f)

                            if (wordText.isNotBlank() && wordBox != null && wordBox.size >= 4) {
                                val wl = (wordBox[0].toFloat() / safeWidth).coerceIn(0f, 1f)
                                val wt = (wordBox[1].toFloat() / safeHeight).coerceIn(0f, 1f)
                                val wr = (wordBox[2].toFloat() / safeWidth).coerceIn(wl, 1f)
                                val wb = (wordBox[3].toFloat() / safeHeight).coerceIn(wt, 1f)

                                tokens.add(
                                    com.tscanner.app.ocr.model.OcrToken(
                                        tokenId = "tok_1_$tokenIndex",
                                        text = wordText,
                                        polygon = com.tscanner.app.ocr.model.OcrPolygon(
                                            listOf(
                                                com.tscanner.app.ocr.model.OcrPoint(wl, wt),
                                                com.tscanner.app.ocr.model.OcrPoint(wr, wt),
                                                com.tscanner.app.ocr.model.OcrPoint(wr, wb),
                                                com.tscanner.app.ocr.model.OcrPoint(wl, wb)
                                            )
                                        ),
                                        confidence = wordConfidence
                                    )
                                )
                                tokenIndex++
                            }

                            if (iterator.isAtFinalElement(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE, TessBaseAPI.PageIteratorLevel.RIL_WORD)) {
                                break
                            }
                        } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))

                        ocrLines.add(
                            com.tscanner.app.ocr.model.OcrLine(
                                lineId = "line_1_$lineIndex",
                                text = lineText,
                                polygon = com.tscanner.app.ocr.model.OcrPolygon(
                                    listOf(
                                        com.tscanner.app.ocr.model.OcrPoint(l, t),
                                        com.tscanner.app.ocr.model.OcrPoint(r, t),
                                        com.tscanner.app.ocr.model.OcrPoint(r, b),
                                        com.tscanner.app.ocr.model.OcrPoint(l, b)
                                    )
                                ),
                                boundingBox = com.tscanner.app.ocr.model.OcrRect(l, t, r, b),
                                confidence = lineConfidence,
                                tokens = tokens
                            )
                        )
                        lineIndex++
                    }
                } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))

                if (ocrLines.isEmpty()) return null

                return com.tscanner.app.ocr.model.OcrPage(
                    pageId = "page_1_${java.util.UUID.randomUUID().toString().take(8)}",
                    pageIndex = 1,
                    status = com.tscanner.app.ocr.model.OcrPageStatus.SUCCESS,
                    imageInfo = com.tscanner.app.ocr.model.OcrImageInfo(
                        localUri = "",
                        widthPx = safeWidth.toInt(),
                        heightPx = safeHeight.toInt(),
                        rotationDegrees = 0
                    ),
                    engineId = "tesseract",
                    sourceLanguage = language,
                    sourceBlocks = listOf(
                        com.tscanner.app.ocr.model.OcrBlock(
                            blockId = "blk_1_0",
                            lines = ocrLines
                        )
                    )
                )
            } catch (t: Throwable) {
                Log.w("TessApiDriver", "Failed to extract geometry from Tesseract iterator: ${t.message}")
                return null
            } finally {
                try {
                    iterator?.delete()
                } catch (_: Throwable) {}
            }
        }

        override fun recycle() = api.recycle()
    }

    @Volatile
    internal var tessDriverFactory: () -> TessApiDriver = { RealTessApiDriver() }

    @Volatile
    internal var dataPathOverride: String? = null

    /**
     * Resolves exact Tesseract model name without silent language fallback (F02).
     * Request 'vi'/'vie' strictly maps to 'vie'.
     * Request 'en'/'eng' strictly maps to 'eng'.
     * Combined requests like 'vie+eng' strictly map to 'vie+eng' without silent single-model downgrade.
     */
    fun resolveTessModel(language: String): String {
        return when (language.trim().lowercase(Locale.ROOT)) {
            "vie", "vi" -> "vie"
            "eng", "en" -> "eng"
            "vi+en", "en+vi", "vie+eng", "eng+vie", "auto" -> "vie+eng"
            else -> language.trim()
        }
    }

    /**
     * Recognizes text from a Bitmap using Tesseract OCR returning structured result.
     */
    suspend fun recognizeTextFromBitmapStructured(
        context: Context,
        bitmap: Bitmap,
        language: String = "vie"
    ): EngineRunResult = withContext(Dispatchers.Default) {
        var safeBitmap: Bitmap? = null
        var tess: TessApiDriver? = null
        try {
            val dataPath = dataPathOverride ?: prepareTessData(context)

            // Ensure bitmap is in a software ARGB_8888 configuration accessible to native C++
            safeBitmap = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    bitmap.config == Bitmap.Config.HARDWARE) {
                    bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
                } else if (bitmap.config != Bitmap.Config.ARGB_8888) {
                    bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
                } else {
                    bitmap
                }
            } catch (_: Throwable) {
                bitmap
            }

            val targetModel = resolveTessModel(language)
            val driver = tessDriverFactory()
            val initOk = try {
                driver.init(dataPath, targetModel)
            } catch (c: kotlinx.coroutines.CancellationException) {
                try { driver.recycle() } catch (_: Throwable) {}
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "Exception initializing Tesseract model '$targetModel': ${t.message}", t)
                try { driver.recycle() } catch (_: Throwable) {}
                return@withContext EngineRunResult.ModelUnavailable(
                    "Tesseract model '$targetModel' failed to initialize: ${t.message}",
                    OcrModelUnavailableType.INIT_FAILED
                )
            }

            if (!initOk) {
                Log.e(TAG, "Tesseract failed to initialize with model '$targetModel'")
                try { driver.recycle() } catch (_: Throwable) {}
                return@withContext EngineRunResult.ModelUnavailable(
                    "Tesseract traineddata for '$targetModel' missing or failed to initialize",
                    OcrModelUnavailableType.MISSING
                )
            }

            tess = driver
            Log.d(TAG, "Tesseract initialized successfully with exact model '$targetModel'")

            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            tess.setImage(safeBitmap)
            val result = tess.getUTF8Text()?.trim() ?: ""
            if (result.isNotBlank()) {
                val pageDoc = tess.getPageDocument(safeBitmap.width, safeBitmap.height, targetModel)
                    ?: com.tscanner.app.ocr.model.OcrPage(
                        pageId = "page_1_${java.util.UUID.randomUUID().toString().take(8)}",
                        pageIndex = 1,
                        status = com.tscanner.app.ocr.model.OcrPageStatus.SUCCESS,
                        imageInfo = com.tscanner.app.ocr.model.OcrImageInfo(
                            localUri = "",
                            widthPx = safeBitmap.width.coerceAtLeast(1),
                            heightPx = safeBitmap.height.coerceAtLeast(1),
                            rotationDegrees = 0
                        ),
                        engineId = "tesseract",
                        sourceLanguage = targetModel,
                        sourceBlocks = listOf(
                            com.tscanner.app.ocr.model.OcrBlock(
                                blockId = "blk_1_0",
                                lines = result.lines().mapIndexed { idx, lineText ->
                                    com.tscanner.app.ocr.model.OcrLine(
                                        lineId = "line_1_$idx",
                                        text = lineText,
                                        polygon = null, // geometryUnavailable fallback
                                        tokens = emptyList()
                                    )
                                }
                            )
                        )
                    )
                EngineRunResult.Success(result, pageDoc)
            } else {
                EngineRunResult.NoText
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "Error in recognizeTextFromBitmap: ${t.message}", t)
            EngineRunResult.Failure(t.message ?: "Tesseract recognition error", t)
        } finally {
            try {
                tess?.recycle()
            } catch (_: Throwable) {}
            if (safeBitmap != null && safeBitmap != bitmap && !safeBitmap.isRecycled) {
                safeBitmap.recycle()
            }
        }
    }

    /**
     * Recognizes text from a Bitmap using Tesseract OCR with Vietnamese / English LSTM neural models.
     */
    suspend fun recognizeTextFromBitmap(
        context: Context,
        bitmap: Bitmap,
        language: String = "vie"
    ): String {
        return (recognizeTextFromBitmapStructured(context, bitmap, language) as? EngineRunResult.Success)?.text ?: ""
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, maxDim: Int = 2048): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > maxDim || width > maxDim) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= maxDim || (halfWidth / inSampleSize) >= maxDim) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * Loads and normalizes a Bitmap from Uri, applying EXIF rotation if necessary.
     */
    suspend fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOptions)
            }
            val sampleSize = calculateInSampleSize(boundsOptions, 2048)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return@withContext null

            rotateBitmapIfNeeded(context, uri, bitmap)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load bitmap from uri: $uri", t)
            null
        }
    }

    /**
     * Loads and normalizes a Bitmap from File path, applying EXIF rotation if necessary.
     */
    suspend fun loadBitmapFromFile(filePath: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, boundsOptions)
            val sampleSize = calculateInSampleSize(boundsOptions, 2048)

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeFile(filePath, decodeOptions) ?: return@withContext null
            val exif = ExifInterface(filePath)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rotationDegrees != 0f) {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                bitmap.recycle()
                rotatedBitmap
            } else {
                bitmap
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load bitmap from file: $filePath", t)
            null
        }
    }

    private fun rotateBitmapIfNeeded(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return bitmap
            val exif = ExifInterface(inputStream)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            inputStream.close()

            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rotationDegrees != 0f) {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                bitmap.recycle()
                rotatedBitmap
            } else {
                bitmap
            }
        } catch (e: Exception) {
            bitmap
        }
    }
}
