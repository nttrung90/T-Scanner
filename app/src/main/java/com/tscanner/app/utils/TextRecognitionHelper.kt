package com.tscanner.app.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.text.TextRecognizerOptionsInterface
import com.tscanner.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object TextRecognitionHelper {

    private const val TAG = "TextRecognitionHelper"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private const val PREFS_NAME = "tscanner_ocr_prefs"
    private const val KEY_PREFERRED_ENGINE = "key_preferred_ocr_engine"
    private const val KEY_OCR_DOCUMENT_LANGUAGE = "key_ocr_document_language"
    const val KEY_OCR_LANGUAGE_MODE = "key_ocr_language_mode_v2"
    const val KEY_MANUAL_OCR_TAG = "key_manual_ocr_tag_v2"
    const val KEY_OCR_MIGRATED_V2 = "key_ocr_language_migrated_v2"

    const val ENGINE_MODE_AUTO = "auto"
    /**
     * Legacy engine mode constant kept only for migration and historical metadata mapping.
     * Not supported for active engine selection or execution.
     */
    const val ENGINE_MODE_PADDLE = "paddle"
    const val ENGINE_MODE_TESSERACT = "tesseract"
    const val ENGINE_MODE_MLKIT = "mlkit"

    @Deprecated("Dùng formatEngineMetadata từ OcrResult.Success thay cho biến toàn cục này.")
    @Volatile
    var lastEngineUsed: String = "Auto"
        private set

    fun formatEngineMetadata(
        context: Context,
        engineId: String,
        documentLanguage: String,
        fallbackUsed: Boolean = false
    ): String {
        val engineName = getEngineDisplayName(context, engineId)
        val langLabel = getOcrDocumentLanguageDisplayName(context, documentLanguage)
        val formatted = if (fallbackUsed) {
            val fallbackBadge = try { context.getString(R.string.ocr_fallback_badge) } catch (_: Throwable) { null } ?: "Dự phòng"
            "$engineName ($fallbackBadge)"
        } else if (langLabel.isNotBlank()) {
            "$engineName ($langLabel)"
        } else {
            engineName
        }
        @Suppress("DEPRECATION")
        lastEngineUsed = formatted
        return formatted
    }

    fun formatEngineMetadata(
        context: Context,
        result: OcrResult.Success
    ): String = formatEngineMetadata(context, result.engineId, result.documentLanguage, result.fallbackUsed)

    fun getLocalizedOcrErrorMessage(context: Context, result: OcrResult): String {
        return when (result) {
            is OcrResult.Success -> ""
            is OcrResult.NoText -> context.getString(R.string.no_text_found)
            is OcrResult.UnsupportedLanguage -> context.getString(R.string.ocr_lang_unsupported, result.languageTag)
            is OcrResult.IncompatibleEngine -> context.getString(R.string.ocr_engine_incompatible, result.engineMode, result.languageTag)
            is OcrResult.ModelUnavailable -> when (result.type) {
                OcrModelUnavailableType.DOWNLOADING -> context.getString(R.string.ocr_model_downloading)
                OcrModelUnavailableType.NOT_ENOUGH_SPACE -> context.getString(R.string.ocr_model_not_enough_space)
                OcrModelUnavailableType.NETWORK_ERROR -> context.getString(R.string.ocr_model_network_error)
                OcrModelUnavailableType.MISSING -> context.getString(R.string.ocr_model_missing)
                OcrModelUnavailableType.INIT_FAILED -> context.getString(R.string.ocr_model_init_failed)
            }
            is OcrResult.Failure -> {
                Log.w(TAG, "OCR technical failure: ${result.error}", result.throwable)
                when (result.code) {
                    OcrFailureCode.IMAGE_LOAD_FAILED -> context.getString(R.string.cannot_load_image)
                    OcrFailureCode.EXECUTION_FAILED, OcrFailureCode.UNKNOWN -> context.getString(R.string.ocr_error_generic)
                }
            }
        }
    }

    fun showOcrErrorToast(context: Context, result: OcrResult) {
        val message = getLocalizedOcrErrorMessage(context, result)
        if (message.isNotBlank()) {
            val duration = if (result is OcrResult.ModelUnavailable &&
                (result.type == OcrModelUnavailableType.DOWNLOADING ||
                 result.type == OcrModelUnavailableType.NOT_ENOUGH_SPACE ||
                 result.type == OcrModelUnavailableType.NETWORK_ERROR)
            ) {
                Toast.LENGTH_LONG
            } else {
                Toast.LENGTH_SHORT
            }
            Toast.makeText(context, message, duration).show()
        }
    }

    fun resolveMlKitModelUnavailableType(t: Throwable): OcrModelUnavailableType {
        if (t is MlKitException) {
            when (t.errorCode) {
                MlKitException.NOT_ENOUGH_SPACE -> return OcrModelUnavailableType.NOT_ENOUGH_SPACE
                MlKitException.NETWORK_ISSUE -> return OcrModelUnavailableType.NETWORK_ERROR
                MlKitException.UNAVAILABLE -> return OcrModelUnavailableType.DOWNLOADING
            }
        }
        val msg = t.message?.lowercase(Locale.ROOT) ?: ""
        return when {
            msg.contains("space") || msg.contains("storage") || msg.contains("not enough") || msg.contains("disk") ->
                OcrModelUnavailableType.NOT_ENOUGH_SPACE
            msg.contains("network") || msg.contains("connection") || msg.contains("offline") || msg.contains("internet") ->
                OcrModelUnavailableType.NETWORK_ERROR
            msg.contains("download") || msg.contains("waiting for") || msg.contains("not yet downloaded") ->
                OcrModelUnavailableType.DOWNLOADING
            msg.contains("missing") || msg.contains("not found") || msg.contains("traineddata") ->
                OcrModelUnavailableType.MISSING
            else ->
                OcrModelUnavailableType.INIT_FAILED
        }
    }

    fun isModelUnavailableException(t: Throwable): Boolean {
        if (t is MlKitException) {
            return t.errorCode == MlKitException.UNAVAILABLE ||
                   t.errorCode == MlKitException.NETWORK_ISSUE ||
                   t.errorCode == MlKitException.NOT_ENOUGH_SPACE
        }
        val msg = t.message?.lowercase(Locale.ROOT) ?: ""
        return msg.contains("download") ||
               msg.contains("waiting for") ||
               msg.contains("not yet downloaded") ||
               msg.contains("not available") ||
               msg.contains("model_unavailable") ||
               msg.contains("module_not_found") ||
               msg.contains("not enough space") ||
               msg.contains("network")
    }

    fun normalizeEngineMode(engineMode: String?): String {
        return when (engineMode) {
            ENGINE_MODE_PADDLE -> ENGINE_MODE_AUTO
            ENGINE_MODE_AUTO, ENGINE_MODE_TESSERACT, ENGINE_MODE_MLKIT -> engineMode
            null -> ENGINE_MODE_AUTO
            else -> engineMode
        }
    }

    fun getPreferredEngine(prefs: SharedPreferences): String {
        val raw = prefs.getString(KEY_PREFERRED_ENGINE, null)
        if (raw == ENGINE_MODE_PADDLE) {
            prefs.edit().putString(KEY_PREFERRED_ENGINE, ENGINE_MODE_AUTO).apply()
            return ENGINE_MODE_AUTO
        }
        return normalizeEngineMode(raw)
    }

    fun getPreferredEngine(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return getPreferredEngine(prefs)
    }

    fun setPreferredEngine(prefs: SharedPreferences, engineMode: String) {
        val effective = normalizeEngineMode(engineMode)
        prefs.edit().putString(KEY_PREFERRED_ENGINE, effective).apply()
    }

    fun setPreferredEngine(context: Context, engineMode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        setPreferredEngine(prefs, engineMode)
    }

    fun getPreferredEngineDisplayName(context: Context): String {
        return when (getPreferredEngine(context)) {
            ENGINE_MODE_AUTO -> try { context.getString(R.string.ocr_engine_auto) } catch (_: Throwable) { null } ?: "Tự động (Khuyên dùng)"
            ENGINE_MODE_TESSERACT -> try { context.getString(R.string.ocr_engine_tesseract) } catch (_: Throwable) { null } ?: "Tesseract OCR v5"
            ENGINE_MODE_PADDLE -> try { context.getString(R.string.ocr_engine_legacy_paddle) } catch (_: Throwable) { null } ?: "PaddleOCR (Legacy)"
            ENGINE_MODE_MLKIT -> try { context.getString(R.string.ocr_engine_mlkit) } catch (_: Throwable) { null } ?: "Google ML Kit"
            else -> try { context.getString(R.string.ocr_engine_auto) } catch (_: Throwable) { null } ?: "Tự động (Khuyên dùng)"
        }
    }

    /**
     * Phân giải logic xác định ngôn ngữ tài liệu OCR độc lập hoàn toàn với Android SharedPreferences.
     * Dùng cho unit test và kiểm tra quy tắc cấu hình (R02).
     */
    fun resolveOcrDocumentLanguage(savedLanguage: String?, currentUiLang: String?): String? {
        if (!savedLanguage.isNullOrBlank()) {
            val normalized = OcrRoutingResolver.normalizeLanguageTag(savedLanguage)
            if (OcrRoutingResolver.isLanguageSupported(normalized)) {
                return normalized
            }
        }
        if (!currentUiLang.isNullOrBlank() && OcrRoutingResolver.isLanguageSupported(currentUiLang)) {
            return OcrRoutingResolver.normalizeLanguageTag(currentUiLang)
        }
        return null
    }

    /**
     * Xác định xem có cần tự động lưu lại ngôn ngữ tài liệu lần đầu hay không.
     * Chỉ lưu khi chưa từng lưu lựa chọn và ngôn ngữ giao diện được hỗ trợ OCR.
     */
    fun shouldAutoPersistInitialLanguage(savedLanguage: String?, currentUiLang: String?): Boolean {
        if (!savedLanguage.isNullOrBlank()) return false
        return !currentUiLang.isNullOrBlank() && OcrRoutingResolver.isLanguageSupported(currentUiLang)
    }

    /**
     * Kết quả đánh giá migration SharedPreferences cho OCR language (Gói O01).
     */
    data class OcrPreferenceMigrationResult(
        val migrated: Boolean,
        val mode: OcrLanguageMode,
        val manualTag: String?
    )

    /**
     * Đánh giá migration cấu hình ngôn ngữ tài liệu OCR độc lập với Android framework:
     * - Preferences cũ không phân biệt được người dùng chọn hay tự lưu từ UI.
     * - Chuyển mặc định sang AUTO, giữ giá trị cũ dưới dạng lựa chọn gần nhất (manualTag) để người dùng chọn lại.
     */
    fun evaluateOcrPreferenceMigration(
        isMigratedV2: Boolean,
        savedMode: String?,
        savedManualTag: String?,
        legacyDocLang: String?
    ): OcrPreferenceMigrationResult {
        if (isMigratedV2) {
            val mode = when (savedMode) {
                OcrLanguageMode.MANUAL.name -> {
                    if (OcrRoutingResolver.isLanguageSupported(savedManualTag)) OcrLanguageMode.MANUAL else OcrLanguageMode.AUTO
                }
                OcrLanguageMode.VI_EN.name -> OcrLanguageMode.VI_EN
                else -> OcrLanguageMode.AUTO
            }
            val tag = if (mode == OcrLanguageMode.MANUAL) OcrRoutingResolver.normalizeLanguageTag(savedManualTag) else null
            return OcrPreferenceMigrationResult(migrated = false, mode = mode, manualTag = tag)
        }

        // Migration sang AUTO mặc định, lưu giữ lựa chọn cũ để chọn lại nếu cần
        val candidateTag = if (!legacyDocLang.isNullOrBlank() && OcrRoutingResolver.isLanguageSupported(legacyDocLang)) {
            OcrRoutingResolver.normalizeLanguageTag(legacyDocLang)
        } else {
            null
        }
        return OcrPreferenceMigrationResult(migrated = true, mode = OcrLanguageMode.AUTO, manualTag = candidateTag)
    }

    /**
     * Thực hiện migration SharedPreferences nếu chưa nâng cấp lên v2 cho OCR.
     */
    fun migrateOcrPreferencesIfNeeded(prefs: SharedPreferences): OcrPreferenceMigrationResult {
        val isMigratedV2 = prefs.getBoolean(KEY_OCR_MIGRATED_V2, false)
        val savedMode = prefs.getString(KEY_OCR_LANGUAGE_MODE, null)
        val savedManualTag = prefs.getString(KEY_MANUAL_OCR_TAG, null)
        val legacyDocLang = prefs.getString(KEY_OCR_DOCUMENT_LANGUAGE, null)

        val eval = evaluateOcrPreferenceMigration(isMigratedV2, savedMode, savedManualTag, legacyDocLang)
        if (eval.migrated) {
            val editor = prefs.edit()
                .putBoolean(KEY_OCR_MIGRATED_V2, true)
                .putString(KEY_OCR_LANGUAGE_MODE, eval.mode.name)
            if (eval.manualTag != null) {
                editor.putString(KEY_MANUAL_OCR_TAG, eval.manualTag)
            }
            editor.apply()
        }
        return eval
    }

    /**
     * Lấy chế độ ngôn ngữ OCR hiện tại (AUTO, VI_EN, MANUAL). Mặc định là AUTO.
     */
    fun getOcrLanguageMode(context: Context): OcrLanguageMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val eval = migrateOcrPreferencesIfNeeded(prefs)
        return eval.mode
    }

    /**
     * Lấy mã ngôn ngữ thủ công đã lưu (cho chế độ MANUAL).
     */
    fun getManualOcrTag(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val eval = migrateOcrPreferencesIfNeeded(prefs)
        return eval.manualTag ?: prefs.getString(KEY_OCR_DOCUMENT_LANGUAGE, null)
    }

    /**
     * Thiết lập chế độ ngôn ngữ OCR (Gói O01).
     */
    fun setOcrLanguageMode(context: Context, mode: OcrLanguageMode, manualTag: String? = null) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putBoolean(KEY_OCR_MIGRATED_V2, true)
            .putString(KEY_OCR_LANGUAGE_MODE, mode.name)

        if (mode == OcrLanguageMode.MANUAL && !manualTag.isNullOrBlank()) {
            val norm = OcrRoutingResolver.normalizeLanguageTag(manualTag)
            editor.putString(KEY_MANUAL_OCR_TAG, norm)
            editor.putString(KEY_OCR_DOCUMENT_LANGUAGE, norm)
        }
        editor.apply()
    }

    /**
     * Trạng thái cấu hình ngôn ngữ: AUTO là cấu hình hợp lệ và mặc định, không chặn người dùng hay ép mở dialog.
     */
    fun isOcrDocumentLanguageConfigured(context: Context): Boolean {
        return true
    }

    /**
     * Lấy mã ngôn ngữ tài liệu OCR hiệu lực (không còn phụ thuộc hay suy luận từ UI language).
     */
    fun getOcrDocumentLanguage(context: Context): String? {
        val mode = getOcrLanguageMode(context)
        return when (mode) {
            OcrLanguageMode.MANUAL -> getManualOcrTag(context) ?: "vi"
            OcrLanguageMode.VI_EN -> "vi"
            OcrLanguageMode.AUTO -> "vi"
        }
    }

    fun setOcrDocumentLanguage(context: Context, languageTag: String) {
        setOcrLanguageMode(context, OcrLanguageMode.MANUAL, languageTag)
    }

    fun getOcrDocumentLanguageDisplayName(context: Context, tag: String?): String {
        return when (tag) {
            "vi+en" -> try { context.getString(R.string.ocr_lang_mode_vi_en) } catch (_: Throwable) { null } ?: "Tiếng Việt + English"
            "auto" -> try { context.getString(R.string.ocr_lang_mode_auto) } catch (_: Throwable) { null } ?: "Tự động nhận diện"
            else -> if (!tag.isNullOrBlank()) {
                OcrRoutingResolver.getLocalizedDocumentLanguageName(context, tag)
            } else {
                try { context.getString(R.string.ocr_lang_mode_auto) } catch (_: Throwable) { null } ?: "Tự động nhận diện"
            }
        }
    }

    fun getOcrDocumentLanguageDisplayName(context: Context): String {
        return when (getOcrLanguageMode(context)) {
            OcrLanguageMode.AUTO -> try { context.getString(R.string.ocr_lang_mode_auto) } catch (_: Throwable) { null } ?: "Tự động nhận diện"
            OcrLanguageMode.VI_EN -> try { context.getString(R.string.ocr_lang_mode_vi_en) } catch (_: Throwable) { null } ?: "Tiếng Việt + English"
            OcrLanguageMode.MANUAL -> {
                val tag = getManualOcrTag(context) ?: "vi"
                OcrRoutingResolver.getLocalizedDocumentLanguageName(context, tag)
            }
        }
    }

    fun getDefaultOcrRequest(context: Context): OcrRequest {
        val mode = getOcrLanguageMode(context)
        val manual = getManualOcrTag(context) ?: "vi"
        return OcrRequest(
            languageMode = mode,
            languageTag = manual,
            engineMode = getPreferredEngine(context)
        )
    }

    /**
     * Synchronous suspend function to recognize text from a file path returning structured result.
     */
    suspend fun recognizeTextFromFileStructured(
        context: Context,
        filePath: String,
        request: OcrRequest = getDefaultOcrRequest(context)
    ): OcrResult = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists() || file.length() == 0L) {
                Log.w(TAG, "File does not exist or is empty: $filePath")
                return@withContext OcrResult.Failure("File does not exist or is empty", code = OcrFailureCode.IMAGE_LOAD_FAILED)
            }

            val bitmap = TesseractOcrHelper.loadBitmapFromFile(filePath)
            if (bitmap == null) {
                Log.e(TAG, "Failed to load bitmap from file: $filePath")
                return@withContext OcrResult.Failure("Failed to load bitmap from file", code = OcrFailureCode.IMAGE_LOAD_FAILED)
            }

            try {
                val res = recognizeInternalStructured(context, bitmap, request)
                if (res is OcrResult.Success && res.pageDocument != null) {
                    val curImg = res.pageDocument.imageInfo
                    val updatedImg = (curImg ?: com.tscanner.app.ocr.model.OcrImageInfo(widthPx = bitmap.width, heightPx = bitmap.height)).copy(
                        localUri = filePath,
                        widthPx = if ((curImg?.widthPx ?: 0) > 0) curImg!!.widthPx else bitmap.width,
                        heightPx = if ((curImg?.heightPx ?: 0) > 0) curImg!!.heightPx else bitmap.height
                    )
                    res.copy(pageDocument = res.pageDocument.copy(imageInfo = updatedImg))
                } else {
                    res
                }
            } finally {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "Error in recognizeTextFromFileStructured: ${t.message}", t)
            OcrResult.Failure(t.message ?: "OCR error", t)
        }
    }

    /**
     * Synchronous suspend function to recognize text from a Uri returning structured result.
     */
    suspend fun recognizeTextFromUriStructured(
        context: Context,
        uri: Uri,
        request: OcrRequest = getDefaultOcrRequest(context)
    ): OcrResult = withContext(Dispatchers.IO) {
        try {
            val bitmap = TesseractOcrHelper.loadBitmapFromUri(context, uri)
            if (bitmap == null) {
                Log.e(TAG, "Failed to load bitmap from Uri: $uri")
                return@withContext OcrResult.Failure("Failed to load bitmap from Uri", code = OcrFailureCode.IMAGE_LOAD_FAILED)
            }

            try {
                val res = recognizeInternalStructured(context, bitmap, request)
                if (res is OcrResult.Success && res.pageDocument != null) {
                    val curImg = res.pageDocument.imageInfo
                    val updatedImg = (curImg ?: com.tscanner.app.ocr.model.OcrImageInfo(widthPx = bitmap.width, heightPx = bitmap.height)).copy(
                        localUri = uri.toString(),
                        widthPx = if ((curImg?.widthPx ?: 0) > 0) curImg!!.widthPx else bitmap.width,
                        heightPx = if ((curImg?.heightPx ?: 0) > 0) curImg!!.heightPx else bitmap.height
                    )
                    res.copy(pageDocument = res.pageDocument.copy(imageInfo = updatedImg))
                } else {
                    res
                }
            } finally {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "Error in recognizeTextFromUriStructured: ${t.message}", t)
            OcrResult.Failure(t.message ?: "OCR error", t)
        }
    }

    /**
     * Synchronous suspend function to recognize text from a file path.
     */
    suspend fun recognizeTextFromFileSync(
        context: Context,
        filePath: String,
        request: OcrRequest = getDefaultOcrRequest(context)
    ): String {
        return recognizeTextFromFileStructured(context, filePath, request).textOrNull ?: ""
    }

    /**
     * Synchronous suspend function to recognize text from a Uri.
     */
    suspend fun recognizeTextFromUriSync(
        context: Context,
        uri: Uri,
        request: OcrRequest = getDefaultOcrRequest(context)
    ): String {
        return recognizeTextFromUriStructured(context, uri, request).textOrNull ?: ""
    }

    /**
     * Core recognition pipeline with intelligent routing and multi-tier fallbacks.
     */
    suspend fun recognizeInternalStructured(
        context: Context,
        bitmap: Bitmap,
        request: OcrRequest = getDefaultOcrRequest(context)
    ): OcrResult {
        val effectiveEngineMode = normalizeEngineMode(request.engineMode)
        val effectiveRequest = if (effectiveEngineMode != request.engineMode) {
            request.copy(engineMode = effectiveEngineMode)
        } else {
            request
        }

        val isAutoOrViEn = effectiveRequest.languageMode == OcrLanguageMode.AUTO || effectiveRequest.languageMode == OcrLanguageMode.VI_EN

        if (!isAutoOrViEn && !OcrRoutingResolver.isLanguageSupported(effectiveRequest.languageTag)) {
            return OcrResult.UnsupportedLanguage(effectiveRequest.languageTag)
        }

        val ocrType = if (isAutoOrViEn) OcrType.TESSERACT_PRIMARY else OcrRoutingResolver.getOcrTypeForLanguage(effectiveRequest.languageTag)
        if (!OcrRoutingResolver.isEngineCompatible(effectiveRequest.engineMode, ocrType)) {
            return OcrResult.IncompatibleEngine(effectiveRequest.engineMode, effectiveRequest.languageTag)
        }

        val tessLang = if (isAutoOrViEn) "vie+eng" else OcrRoutingResolver.getTessLanguage(effectiveRequest.languageTag)

        suspend fun success(
            text: String,
            engineId: String,
            fallbackUsed: Boolean = false,
            pageDoc: com.tscanner.app.ocr.model.OcrPage? = null
        ): OcrResult.Success {
            val modelLangs = when (engineId) {
                "tesseract" -> tessLang ?: "vie+eng"
                "mlkit_latin" -> "latin"
                "mlkit_chinese" -> "chinese"
                "mlkit_japanese" -> "japanese"
                "mlkit_korean" -> "korean"
                "mlkit_devanagari" -> "devanagari"
                "paddle" -> "ch_PP-OCRv4"
                else -> engineId
            }

            val detection: OcrPageDetectionResult
            val resolvedDocLang: String

            if (isAutoOrViEn) {
                detection = OcrLanguageIdentifier.identify(text, listOf("vi", "en"), modelLangs)
                resolvedDocLang = when (detection.status) {
                    OcrDetectionStatus.CONFIDENT -> detection.detectedLanguages.firstOrNull() ?: "vi"
                    OcrDetectionStatus.MIXED_BILINGUAL -> "vi+en"
                    OcrDetectionStatus.UNCERTAIN -> if (effectiveRequest.languageMode == OcrLanguageMode.VI_EN) "vi+en" else "vi"
                }
            } else {
                resolvedDocLang = effectiveRequest.languageTag
                detection = OcrPageDetectionResult(
                    status = OcrDetectionStatus.UNCERTAIN,
                    detectedLanguages = emptyList(),
                    candidateLanguages = listOf(effectiveRequest.languageTag),
                    modelLanguages = modelLangs,
                    confidence = 0f
                )
            }

            formatEngineMetadata(context, engineId, resolvedDocLang, fallbackUsed)
            return OcrResult.Success(text, engineId, resolvedDocLang, fallbackUsed, detection, pageDoc)
        }

        // Nhánh AUTO / VI_EN: Pipeline song ngữ Việt - Anh (vie+eng)
        if (isAutoOrViEn) {
            if (effectiveRequest.engineMode == ENGINE_MODE_AUTO) {
                val tessResult = runTesseractStructured(context, bitmap, "vie+eng")
                if (tessResult is EngineRunResult.Success) {
                    return success(tessResult.text, "tesseract", fallbackUsed = false, pageDoc = tessResult.pageDocument)
                }
                val mlResult = runMlKitLatinStructured(bitmap)
                if (mlResult is EngineRunResult.Success) {
                    return success(mlResult.text, "mlkit_latin", fallbackUsed = true, pageDoc = mlResult.pageDocument)
                }
                return resolveFallbackOutcome(tessResult, mlResult, "vi")
            } else if (effectiveRequest.engineMode == ENGINE_MODE_TESSERACT) {
                // F07: Strict manual Tesseract mode - NO fallback to ML Kit
                val tessResult = runTesseractStructured(context, bitmap, "vie+eng")
                return mapSingleEngineResultWithPage(tessResult, "tesseract", "vi") { t, id, doc -> success(t, id, pageDoc = doc) }
            } else if (effectiveRequest.engineMode == ENGINE_MODE_MLKIT) {
                // F07: Strict manual ML Kit mode - NO fallback to Tesseract
                val mlResult = runMlKitLatinStructured(bitmap)
                return mapSingleEngineResultWithPage(mlResult, "mlkit_latin", "vi") { t, id, doc -> success(t, id, pageDoc = doc) }
            } else {
                return OcrResult.IncompatibleEngine(effectiveRequest.engineMode, "vi")
            }
        }

        // 1. User explicitly set Tesseract OCR v5 (F07: Strict manual, NO fallback)
        if (effectiveRequest.engineMode == ENGINE_MODE_TESSERACT) {
            if (tessLang == null) {
                return OcrResult.IncompatibleEngine(effectiveRequest.engineMode, effectiveRequest.languageTag)
            }
            val tessResult = runTesseractStructured(context, bitmap, tessLang)
            return mapSingleEngineResultWithPage(tessResult, "tesseract", effectiveRequest.languageTag) { t, id, doc -> success(t, id, pageDoc = doc) }
        }

        // 2. User explicitly set Google ML Kit (F07: Strict manual, NO fallback)
        if (effectiveRequest.engineMode == ENGINE_MODE_MLKIT) {
            return when (ocrType) {
                OcrType.MLKIT_JAPANESE -> {
                    mapSingleEngineResultWithPage(runMlKitJapaneseStructured(bitmap), "mlkit_japanese", effectiveRequest.languageTag) { t, id, doc -> success(t, id, pageDoc = doc) }
                }
                OcrType.MLKIT_KOREAN -> {
                    mapSingleEngineResultWithPage(runMlKitKoreanStructured(bitmap), "mlkit_korean", effectiveRequest.languageTag) { t, id, doc -> success(t, id, pageDoc = doc) }
                }
                OcrType.PLAY_SERVICES_DEVANAGARI -> {
                    mapSingleEngineResultWithPage(runMlKitDevanagariStructured(bitmap), "mlkit_devanagari", effectiveRequest.languageTag) { t, id, doc -> success(t, id, pageDoc = doc) }
                }
                OcrType.MLKIT_CHINESE -> {
                    mapSingleEngineResultWithPage(runMlKitChineseStructured(bitmap), "mlkit_chinese", effectiveRequest.languageTag) { t, id, doc -> success(t, id, pageDoc = doc) }
                }
                OcrType.TESSERACT_PRIMARY -> {
                    mapSingleEngineResultWithPage(runMlKitLatinStructured(bitmap), "mlkit_latin", effectiveRequest.languageTag) { t, id, doc -> success(t, id, pageDoc = doc) }
                }
                OcrType.MLKIT_LATIN -> {
                    mapSingleEngineResultWithPage(runMlKitLatinStructured(bitmap), "mlkit_latin", effectiveRequest.languageTag) { t, id, doc -> success(t, id, pageDoc = doc) }
                }
                OcrType.UNSUPPORTED_ON_DEVICE -> {
                    OcrResult.UnsupportedLanguage(effectiveRequest.languageTag)
                }
            }
        }

        // 3. AUTO mode: Smart Routing based on language (Permits automatic fallback)
        return when (ocrType) {
            OcrType.TESSERACT_PRIMARY -> {
                val resolvedTessLang = tessLang ?: "vie"
                val tessResult = runTesseractStructured(context, bitmap, resolvedTessLang)
                if (tessResult is EngineRunResult.Success) {
                    return success(tessResult.text, "tesseract", fallbackUsed = false, pageDoc = tessResult.pageDocument)
                }
                val mlText = runMlKitLatinStructured(bitmap)
                if (mlText is EngineRunResult.Success) {
                    return success(mlText.text, "mlkit_latin", fallbackUsed = true, pageDoc = mlText.pageDocument)
                }
                resolveFallbackOutcome(tessResult, mlText, effectiveRequest.languageTag)
            }

            OcrType.MLKIT_CHINESE -> {
                mapSingleEngineResultWithPage(runMlKitChineseStructured(bitmap), "mlkit_chinese", effectiveRequest.languageTag) { t, id, doc -> success(t, id, fallbackUsed = false, pageDoc = doc) }
            }

            OcrType.MLKIT_JAPANESE -> {
                mapSingleEngineResultWithPage(runMlKitJapaneseStructured(bitmap), "mlkit_japanese", effectiveRequest.languageTag) { t, id, doc -> success(t, id, pageDoc = doc) }
            }

            OcrType.MLKIT_KOREAN -> {
                mapSingleEngineResultWithPage(runMlKitKoreanStructured(bitmap), "mlkit_korean", effectiveRequest.languageTag) { t, id, doc -> success(t, id, pageDoc = doc) }
            }

            OcrType.PLAY_SERVICES_DEVANAGARI -> {
                mapSingleEngineResultWithPage(runMlKitDevanagariStructured(bitmap), "mlkit_devanagari", effectiveRequest.languageTag) { t, id, doc -> success(t, id, pageDoc = doc) }
            }

            OcrType.MLKIT_LATIN -> {
                mapSingleEngineResultWithPage(runMlKitLatinStructured(bitmap), "mlkit_latin", effectiveRequest.languageTag) { t, id, doc -> success(t, id, pageDoc = doc) }
            }

            OcrType.UNSUPPORTED_ON_DEVICE -> {
                OcrResult.UnsupportedLanguage(effectiveRequest.languageTag)
            }
        }
    }

    fun resolveFallbackOutcome(
        primary: EngineRunResult,
        fallback: EngineRunResult,
        languageTag: String
    ): OcrResult {
        return when {
            primary is EngineRunResult.ModelUnavailable && fallback is EngineRunResult.ModelUnavailable ->
                OcrResult.ModelUnavailable(primary.reason, languageTag, primary.type)
            primary is EngineRunResult.ModelUnavailable ->
                OcrResult.ModelUnavailable(primary.reason, languageTag, primary.type)
            fallback is EngineRunResult.ModelUnavailable && primary !is EngineRunResult.NoText ->
                OcrResult.ModelUnavailable(fallback.reason, languageTag, fallback.type)
            primary is EngineRunResult.Failure ->
                OcrResult.Failure(primary.message, primary.cause, primary.code)
            fallback is EngineRunResult.Failure ->
                OcrResult.Failure(fallback.message, fallback.cause, fallback.code)
            else -> OcrResult.NoText
        }
    }

    inline fun mapSingleEngineResult(
        result: EngineRunResult,
        engineId: String,
        languageTag: String,
        onSuccess: (String, String) -> OcrResult.Success
    ): OcrResult {
        return when (result) {
            is EngineRunResult.Success -> onSuccess(result.text, engineId)
            is EngineRunResult.NoText -> OcrResult.NoText
            is EngineRunResult.ModelUnavailable -> OcrResult.ModelUnavailable(result.reason, languageTag, result.type)
            is EngineRunResult.Failure -> OcrResult.Failure(result.message, result.cause, result.code)
        }
    }

    inline fun mapSingleEngineResultWithPage(
        result: EngineRunResult,
        engineId: String,
        languageTag: String,
        onSuccess: (String, String, com.tscanner.app.ocr.model.OcrPage?) -> OcrResult.Success
    ): OcrResult {
        return when (result) {
            is EngineRunResult.Success -> onSuccess(result.text, engineId, result.pageDocument)
            is EngineRunResult.NoText -> OcrResult.NoText
            is EngineRunResult.ModelUnavailable -> OcrResult.ModelUnavailable(result.reason, languageTag, result.type)
            is EngineRunResult.Failure -> OcrResult.Failure(result.message, result.cause, result.code)
        }
    }

    fun getEngineDisplayName(context: Context, engineId: String): String {
        return when (engineId) {
            "tesseract" -> try { context.getString(R.string.ocr_engine_tesseract) } catch (_: Throwable) { null } ?: "Tesseract OCR"
            "paddle" -> try { context.getString(R.string.ocr_engine_legacy_paddle) } catch (_: Throwable) { null } ?: "PaddleOCR (Legacy)"
            "mlkit", "mlkit_latin", "mlkit_japanese", "mlkit_korean", "mlkit_chinese", "mlkit_devanagari" -> try { context.getString(R.string.ocr_engine_mlkit) } catch (_: Throwable) { null } ?: "Google ML Kit"
            else -> engineId
        }
    }



    private suspend fun runTesseractStructured(context: Context, bitmap: Bitmap, language: String): EngineRunResult {
        return try {
            TesseractOcrHelper.recognizeTextFromBitmapStructured(context, bitmap, language)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Tesseract execution failed: ${t.message}", t)
            if (isModelUnavailableException(t)) {
                EngineRunResult.ModelUnavailable(t.message ?: "Tesseract model unavailable", OcrModelUnavailableType.INIT_FAILED)
            } else {
                EngineRunResult.Failure(t.message ?: "Tesseract error", t, OcrFailureCode.EXECUTION_FAILED)
            }
        }
    }

    internal var textRecognizerFactory: (TextRecognizerOptionsInterface) -> TextRecognizer = {
        TextRecognition.getClient(it)
    }

    internal var inputImageFactory: (Bitmap?) -> InputImage = {
        InputImage.fromBitmap(it!!, 0)
    }

    internal suspend fun processMlKitRecognition(
        options: TextRecognizerOptionsInterface,
        bitmap: Bitmap? = null,
        engineId: String = "mlkit_latin",
        documentLanguage: String = "en"
    ): EngineRunResult = suspendCancellableCoroutine { cont ->
        var recognizer: TextRecognizer? = null
        val closedGuard = java.util.concurrent.atomic.AtomicBoolean(false)

        fun safeCloseRecognizer() {
            if (closedGuard.compareAndSet(false, true)) {
                try {
                    recognizer?.close()
                } catch (t: Throwable) {
                    Log.w(TAG, "Error closing TextRecognizer: ${t.message}", t)
                }
            }
        }

        cont.invokeOnCancellation {
            safeCloseRecognizer()
        }

        try {
            val client = textRecognizerFactory(options)
            recognizer = client
            val image = inputImageFactory(bitmap)
            client.process(image)
                .addOnSuccessListener { visionText ->
                    try {
                        if (!cont.isActive) return@addOnSuccessListener
                        val res = visionText.text.trim()
                        val engineResult = if (res.isNotBlank()) {
                            val pageDoc = com.tscanner.app.ocr.engine.MlKitLayoutMapper.mapVisionTextToOcrPage(
                                visionText = visionText,
                                pageIndex = 1,
                                engineId = engineId,
                                documentLanguage = documentLanguage,
                                bitmapWidthPx = bitmap?.width ?: 1,
                                bitmapHeightPx = bitmap?.height ?: 1
                            )
                            EngineRunResult.Success(res, pageDoc)
                        } else {
                            EngineRunResult.NoText
                        }
                        if (cont.isActive) {
                            cont.resumeWith(Result.success(engineResult))
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Error mapping ML Kit vision text: ${t.message}", t)
                        if (cont.isActive) {
                            cont.resumeWith(Result.success(EngineRunResult.Failure(t.message ?: "ML Kit mapping error", t, OcrFailureCode.EXECUTION_FAILED)))
                        }
                    } finally {
                        safeCloseRecognizer()
                    }
                }
                .addOnFailureListener { e ->
                    try {
                        if (!cont.isActive) return@addOnFailureListener
                        if (e is CancellationException) {
                            cont.cancel(e)
                            return@addOnFailureListener
                        }
                        Log.w(TAG, "ML Kit recognition failed: ${e.message}", e)
                        val failureResult = if (isModelUnavailableException(e)) {
                            val unavailableType = resolveMlKitModelUnavailableType(e)
                            EngineRunResult.ModelUnavailable(e.message ?: "ML Kit model unavailable", unavailableType)
                        } else {
                            EngineRunResult.Failure(e.message ?: "ML Kit recognition failed", e, OcrFailureCode.EXECUTION_FAILED)
                        }
                        if (cont.isActive) {
                            cont.resumeWith(Result.success(failureResult))
                        }
                    } finally {
                        safeCloseRecognizer()
                    }
                }
        } catch (c: CancellationException) {
            safeCloseRecognizer()
            if (cont.isActive) cont.cancel(c)
        } catch (t: Throwable) {
            safeCloseRecognizer()
            if (!cont.isActive) return@suspendCancellableCoroutine
            Log.e(TAG, "Error initiating ML Kit recognition: ${t.message}", t)
            if (isModelUnavailableException(t)) {
                val unavailableType = resolveMlKitModelUnavailableType(t)
                cont.resumeWith(Result.success(EngineRunResult.ModelUnavailable(t.message ?: "ML Kit model unavailable", unavailableType)))
            } else {
                cont.resumeWith(Result.success(EngineRunResult.Failure(t.message ?: "ML Kit error", t, OcrFailureCode.EXECUTION_FAILED)))
            }
        }
    }

    private suspend fun runMlKitJapaneseStructured(bitmap: Bitmap): EngineRunResult =
        processMlKitRecognition(JapaneseTextRecognizerOptions.Builder().build(), bitmap, engineId = "mlkit_japanese", documentLanguage = "ja")

    private suspend fun runMlKitKoreanStructured(bitmap: Bitmap): EngineRunResult =
        processMlKitRecognition(KoreanTextRecognizerOptions.Builder().build(), bitmap, engineId = "mlkit_korean", documentLanguage = "ko")

    internal var mlKitChineseRunner: (suspend (Bitmap) -> EngineRunResult)? = null

    private suspend fun runMlKitChineseStructured(bitmap: Bitmap): EngineRunResult {
        mlKitChineseRunner?.let { return it.invoke(bitmap) }
        return processMlKitRecognition(ChineseTextRecognizerOptions.Builder().build(), bitmap, engineId = "mlkit_chinese", documentLanguage = "zh")
    }

    private suspend fun runMlKitLatinStructured(bitmap: Bitmap): EngineRunResult =
        processMlKitRecognition(TextRecognizerOptions.DEFAULT_OPTIONS, bitmap, engineId = "mlkit_latin", documentLanguage = "en")

    private suspend fun runMlKitDevanagariStructured(bitmap: Bitmap): EngineRunResult =
        processMlKitRecognition(DevanagariTextRecognizerOptions.Builder().build(), bitmap, engineId = "mlkit_devanagari", documentLanguage = "hi")

    /**
     * Recognizes text from a Uri with structured callbacks.
     */
    fun recognizeTextFromUriStructuredCallback(
        context: Context,
        uri: Uri,
        request: OcrRequest = getDefaultOcrRequest(context),
        onResult: (OcrResult) -> Unit
    ) {
        scope.launch {
            try {
                val result = recognizeTextFromUriStructured(context, uri, request)
                onResult(result)
            } catch (c: CancellationException) {
                // Job was cancelled, do not notify callback
            } catch (t: Throwable) {
                onResult(OcrResult.Failure(t.message ?: "OCR error", t))
            }
        }
    }

    /**
     * Recognizes text from a Uri with callbacks.
     */
    fun recognizeTextFromUri(
        context: Context,
        uri: Uri,
        request: OcrRequest = getDefaultOcrRequest(context),
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        recognizeTextFromUriStructuredCallback(context, uri, request) { result ->
            when (result) {
                is OcrResult.Success -> onSuccess(result.text)
                else -> onError(Exception(getLocalizedOcrErrorMessage(context, result)))
            }
        }
    }

    /**
     * Recognizes text from a file path with callbacks.
     */
    fun recognizeTextFromFile(
        context: Context,
        filePath: String,
        request: OcrRequest = getDefaultOcrRequest(context),
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        scope.launch {
            try {
                val result = recognizeTextFromFileStructured(context, filePath, request)
                when (result) {
                    is OcrResult.Success -> onSuccess(result.text)
                    else -> onError(Exception(getLocalizedOcrErrorMessage(context, result)))
                }
            } catch (c: CancellationException) {
                // Job was cancelled
            } catch (t: Throwable) {
                onError(Exception(t.message ?: "OCR error", t))
            }
        }
    }

    /**
     * Aggregates multi-page results using the context's localized page header format and engine metadata.
     */
    fun aggregateMultiPageResults(
        context: Context,
        pages: List<OcrResult>
    ): MultiPageOcrResult {
        return MultiPageOcrAggregator.aggregate(
            pages = pages,
            formatHeader = { pageNum -> context.getString(R.string.ocr_page_header_format, pageNum) },
            formatEngineMetadata = { result -> formatEngineMetadata(context, result) }
        )
    }

    /**
     * Formats a localized error message for a [MultiPageOcrResult.PageError], specifying the 1-based page number if multi-page.
     */
    fun formatPageErrorMessage(context: Context, pageError: MultiPageOcrResult.PageError): String {
        val errorDesc = getLocalizedOcrErrorMessage(context, pageError.errorResult)
        return if (pageError.totalPages > 1) {
            context.getString(R.string.ocr_page_error_format, pageError.failedPageNumber, errorDesc)
        } else {
            errorDesc
        }
    }

    /**
     * Formats a notice toast message if some pages in a multi-page document had no text.
     * Returns null if all pages had text (blankPages == 0).
     */
    fun formatPartialSuccessNotice(context: Context, success: MultiPageOcrResult.Success): String? {
        if (success.blankPages <= 0) return null
        return context.getString(
            R.string.ocr_partial_blank_pages_notice,
            success.pagesWithText,
            success.blankPages
        )
    }
}

