package com.tscanner.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tscanner.app.paddleocr.PaddleOcrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File

object TextRecognitionHelper {

    private const val TAG = "TextRecognitionHelper"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private const val PREFS_NAME = "tscanner_ocr_prefs"
    private const val KEY_PREFERRED_ENGINE = "key_preferred_ocr_engine"

    const val ENGINE_MODE_AUTO = "auto"
    const val ENGINE_MODE_PADDLE = "paddle"
    const val ENGINE_MODE_TESSERACT = "tesseract"
    const val ENGINE_MODE_MLKIT = "mlkit"

    @Volatile
    var lastEngineUsed: String = "Tự động"
        private set

    fun getPreferredEngine(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PREFERRED_ENGINE, ENGINE_MODE_AUTO) ?: ENGINE_MODE_AUTO
    }

    fun setPreferredEngine(context: Context, engineMode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PREFERRED_ENGINE, engineMode).apply()
    }

    fun getPreferredEngineDisplayName(context: Context): String {
        return when (getPreferredEngine(context)) {
            ENGINE_MODE_AUTO -> "Tự động"
            ENGINE_MODE_TESSERACT -> "Tesseract OCR v5"
            ENGINE_MODE_PADDLE -> "PaddleOCR v4 Mobile"
            ENGINE_MODE_MLKIT -> "Google ML Kit"
            else -> "Tự động"
        }
    }

    /**
     * Synchronous suspend function to recognize text from a file path.
     * Sequentially processes bitmap with orientation correction and falls back safely.
     */
    suspend fun recognizeTextFromFileSync(context: Context, filePath: String): String = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists() || file.length() == 0L) {
                Log.w(TAG, "File does not exist or is empty: $filePath")
                return@withContext ""
            }

            val bitmap = TesseractOcrHelper.loadBitmapFromFile(filePath)
            if (bitmap == null) {
                Log.e(TAG, "Failed to load bitmap from file: $filePath")
                return@withContext ""
            }

            try {
                recognizeInternal(context, bitmap)
            } finally {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error in recognizeTextFromFileSync: ${t.message}", t)
            ""
        }
    }

    /**
     * Synchronous suspend function to recognize text from a Uri.
     */
    suspend fun recognizeTextFromUriSync(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            val bitmap = TesseractOcrHelper.loadBitmapFromUri(context, uri)
            if (bitmap == null) {
                Log.e(TAG, "Failed to load bitmap from Uri: $uri")
                return@withContext ""
            }

            try {
                recognizeInternal(context, bitmap)
            } finally {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error in recognizeTextFromUriSync: ${t.message}", t)
            ""
        }
    }

    /**
     * Core recognition pipeline with intelligent routing and multi-tier fallbacks.
     */
    private suspend fun recognizeInternal(
        context: Context,
        bitmap: Bitmap
    ): String {
        val preferred = getPreferredEngine(context)
        val currentLangCode = AppLanguageManager.getCurrentLanguageCode(context)
        val langInfo = AppLanguageManager.getLanguage(currentLangCode)
        val ocrType = langInfo?.ocrType ?: OcrType.TESSERACT_PRIMARY

        if (ocrType == OcrType.UNSUPPORTED_ON_DEVICE && preferred == ENGINE_MODE_AUTO) {
            return ""
        }

        val tessLang = if (currentLangCode == "en") "eng" else "vie"
        val langLabel = if (currentLangCode == "en") "Tiếng Anh" else (langInfo?.nativeName ?: "Tiếng Việt")

        // 1. User explicitly set Tesseract OCR v5 (Default)
        if (preferred == ENGINE_MODE_TESSERACT) {
            val tessText = runTesseract(context, bitmap, tessLang)
            if (tessText.isNotBlank()) {
                lastEngineUsed = "Tesseract OCR v5 ($langLabel)"
                return tessText
            }
            // Fallback 1: ML Kit Latin
            val mlText = runMlKitLatin(bitmap, "Google ML Kit (Dự phòng)")
            if (mlText.isNotBlank()) {
                return mlText
            }
            // Fallback 2: PaddleOCR v4 Mobile
            val paddleText = runPaddleOcr(context, bitmap)
            if (paddleText.isNotBlank()) {
                lastEngineUsed = "PaddleOCR v4 Mobile (Dự phòng)"
                return paddleText
            }
            return ""
        }

        // 2. User explicitly set PaddleOCR v4 Mobile
        if (preferred == ENGINE_MODE_PADDLE) {
            val paddleText = runPaddleOcr(context, bitmap)
            if (paddleText.isNotBlank()) {
                lastEngineUsed = "PaddleOCR v4 Mobile ($langLabel)"
                return paddleText
            }
            // Fallback 1: Tesseract OCR v5
            val tessText = runTesseract(context, bitmap, tessLang)
            if (tessText.isNotBlank()) {
                lastEngineUsed = "Tesseract OCR v5 (Dự phòng)"
                return tessText
            }
            // Fallback 2: ML Kit
            return runMlKitLatin(bitmap, "Google ML Kit (Dự phòng)")
        }

        // 3. User explicitly set Google ML Kit
        if (preferred == ENGINE_MODE_MLKIT) {
            val mlKitText = runMlKitLatin(bitmap, "Google ML Kit ($langLabel)")
            if (mlKitText.isNotBlank()) {
                return mlKitText
            }
            // Fallback 1: Tesseract OCR v5
            val tessText = runTesseract(context, bitmap, tessLang)
            if (tessText.isNotBlank()) {
                lastEngineUsed = "Tesseract OCR v5 (Dự phòng)"
                return tessText
            }
            // Fallback 2: PaddleOCR v4 Mobile
            val paddleText = runPaddleOcr(context, bitmap)
            if (paddleText.isNotBlank()) {
                lastEngineUsed = "PaddleOCR v4 Mobile (Dự phòng)"
                return paddleText
            }
            return ""
        }

        // 4. AUTO mode: Smart Routing based on language
        return when (ocrType) {
            OcrType.TESSERACT_PRIMARY -> {
                // Tiếng Việt & Tiếng Anh: Primary is Tesseract OCR v5 LSTM
                val tessText = runTesseract(context, bitmap, tessLang)
                if (tessText.isNotBlank()) {
                    lastEngineUsed = "Tesseract OCR v5 ($langLabel)"
                    return tessText
                }
                // Fallback 1: ML Kit Latin
                val mlText = runMlKitLatin(bitmap, "Google ML Kit (Dự phòng)")
                if (mlText.isNotBlank()) {
                    return mlText
                }
                // Fallback 2: PaddleOCR v4 Mobile
                val paddleText = runPaddleOcr(context, bitmap)
                if (paddleText.isNotBlank()) {
                    lastEngineUsed = "PaddleOCR v4 Mobile (Dự phòng)"
                    return paddleText
                }
                ""
            }

            OcrType.PADDLE_OCR_V4 -> {
                // zh, ja, ko: Primary is PaddleOCR v4 Mobile (Baidu ONNX)
                val paddleText = runPaddleOcr(context, bitmap)
                if (paddleText.isNotBlank()) {
                    lastEngineUsed = "PaddleOCR v4 Mobile ($langLabel)"
                    return paddleText
                }
                // Fallback 1: Tesseract
                val tessText = runTesseract(context, bitmap, "eng")
                if (tessText.isNotBlank()) {
                    lastEngineUsed = "Tesseract OCR v5 (Dự phòng)"
                    return tessText
                }
                // Fallback 2: ML Kit Latin
                runMlKitLatin(bitmap, "Google ML Kit (Dự phòng)")
            }

            OcrType.MLKIT_LATIN -> {
                // 29 Latin languages
                val mlText = runMlKitLatin(bitmap, "Google ML Kit ($langLabel)")
                if (mlText.isNotBlank()) {
                    return mlText
                }
                // Fallback: Tesseract
                val tessText = runTesseract(context, bitmap, "eng")
                if (tessText.isNotBlank()) {
                    lastEngineUsed = "Tesseract OCR v5 (Dự phòng)"
                    return tessText
                }
                ""
            }

            OcrType.PLAY_SERVICES_DEVANAGARI -> {
                runMlKitDevanagari(bitmap, "Google Play Services OCR (Tiếng Hindi)")
            }

            OcrType.UNSUPPORTED_ON_DEVICE -> {
                ""
            }
        }
    }

    private suspend fun runPaddleOcr(context: Context, bitmap: Bitmap): String {
        return try {
            PaddleOcrEngine.recognizeText(context, bitmap).trim()
        } catch (t: Throwable) {
            Log.w(TAG, "PaddleOCR execution failed: ${t.message}")
            ""
        }
    }

    private suspend fun runTesseract(context: Context, bitmap: Bitmap, language: String): String {
        return try {
            TesseractOcrHelper.recognizeTextFromBitmap(context, bitmap, language).trim()
        } catch (t: Throwable) {
            Log.w(TAG, "Tesseract execution failed: ${t.message}")
            ""
        }
    }

    private suspend fun runMlKitLatin(bitmap: Bitmap, engineLabel: String): String {
        return suspendCancellableCoroutine { cont ->
            try {
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        val res = visionText.text.trim()
                        if (res.isNotBlank()) {
                            lastEngineUsed = engineLabel
                        }
                        if (cont.isActive) {
                            cont.resumeWith(Result.success(res))
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "ML Kit Latin failed: ${e.message}")
                        if (cont.isActive) {
                            cont.resumeWith(Result.success(""))
                        }
                    }
            } catch (t: Throwable) {
                Log.e(TAG, "Error in ML Kit Latin: ${t.message}", t)
                if (cont.isActive) {
                    cont.resumeWith(Result.success(""))
                }
            }
        }
    }

    private suspend fun runMlKitDevanagari(bitmap: Bitmap, engineLabel: String): String {
        return suspendCancellableCoroutine { cont ->
            try {
                val recognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        val res = visionText.text.trim()
                        if (res.isNotBlank()) {
                            lastEngineUsed = engineLabel
                        }
                        if (cont.isActive) {
                            cont.resumeWith(Result.success(res))
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "ML Kit Devanagari failed: ${e.message}")
                        if (cont.isActive) {
                            cont.resumeWith(Result.success(""))
                        }
                    }
            } catch (t: Throwable) {
                Log.e(TAG, "Error in ML Kit Devanagari: ${t.message}", t)
                if (cont.isActive) {
                    cont.resumeWith(Result.success(""))
                }
            }
        }
    }

    /**
     * Recognizes text from a Uri with callbacks.
     */
    fun recognizeTextFromUri(
        context: Context,
        uri: Uri,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        scope.launch {
            try {
                val text = recognizeTextFromUriSync(context, uri)
                if (text.isNotBlank()) {
                    onSuccess(text)
                } else {
                    onError(Exception("No text recognized"))
                }
            } catch (t: Throwable) {
                onError(Exception(t.message ?: "OCR error", t))
            }
        }
    }

    /**
     * Recognizes text from a file path with callbacks.
     */
    fun recognizeTextFromFile(
        context: Context,
        filePath: String,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        scope.launch {
            try {
                val text = recognizeTextFromFileSync(context, filePath)
                if (text.isNotBlank()) {
                    onSuccess(text)
                } else {
                    onError(Exception("No text recognized"))
                }
            } catch (t: Throwable) {
                onError(Exception(t.message ?: "OCR error", t))
            }
        }
    }
}
