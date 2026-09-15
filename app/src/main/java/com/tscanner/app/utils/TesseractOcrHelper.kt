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
     * Recognizes text from a Bitmap using Tesseract OCR with Vietnamese / English LSTM neural models.
     */
    suspend fun recognizeTextFromBitmap(
        context: Context,
        bitmap: Bitmap,
        language: String = "vie"
    ): String = withContext(Dispatchers.Default) {
        var safeBitmap: Bitmap? = null
        var tess: TessBaseAPI? = null
        try {
            val dataPath = prepareTessData(context)

            // Ensure bitmap is in a software ARGB_8888 configuration accessible to native C++
            safeBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else if (bitmap.config != Bitmap.Config.ARGB_8888) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }

            // Determine candidate language codes to try
            val candidates = when (language) {
                "vie", "vi" -> listOf("vie", "eng")
                "eng", "en" -> listOf("eng", "vie")
                "vie+eng" -> listOf("vie+eng", "vie", "eng")
                else -> listOf(language, "vie", "eng")
            }

            for (lang in candidates) {
                val candidateTess = TessBaseAPI()
                val ok = candidateTess.init(dataPath, lang)
                if (ok) {
                    tess = candidateTess
                    Log.d(TAG, "Tesseract initialized successfully with language '$lang'")
                    break
                } else {
                    Log.w(TAG, "Tesseract candidate '$lang' failed to initialize, recycling instance")
                    try { candidateTess.recycle() } catch (_: Throwable) {}
                }
            }

            val activeTess = tess ?: run {
                Log.e(TAG, "All Tesseract language candidates failed to initialize")
                return@withContext ""
            }

            activeTess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            activeTess.setImage(safeBitmap)
            val result = activeTess.getUTF8Text() ?: ""
            result.trim()
        } catch (t: Throwable) {
            Log.e(TAG, "Error in recognizeTextFromBitmap: ${t.message}", t)
            ""
        } finally {
            try {
                tess?.recycle()
            } catch (_: Throwable) {}
            if (safeBitmap != null && safeBitmap != bitmap && !safeBitmap.isRecycled) {
                safeBitmap.recycle()
            }
        }
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
