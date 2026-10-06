package com.tscanner.app.utils

import androidx.annotation.StringRes
import com.tscanner.app.R
import com.tscanner.app.ocr.model.*
import java.util.Locale
import java.util.UUID

/**
 * Chế độ ngôn ngữ tài liệu OCR (Gói O01):
 * - AUTO: Tự nhận diện (mặc định, ưu tiên Tiếng Việt & Tiếng Anh).
 * - VI_EN: Cố định song ngữ Tiếng Việt + English.
 * - MANUAL: Chọn thủ công một ngôn ngữ cụ thể từ catalog OCR 36 ngôn ngữ.
 */
enum class OcrLanguageMode {
    AUTO,
    VI_EN,
    MANUAL
}

/**
 * Trạng thái nhận diện ngôn ngữ của trang tài liệu (Gói O03).
 */
enum class OcrDetectionStatus {
    CONFIDENT,
    MIXED_BILINGUAL,
    UNCERTAIN
}

/**
 * Thông tin phát hiện ngôn ngữ trên từng trang tài liệu (Gói O03/O04).
 */
data class OcrPageDetectionResult(
    val status: OcrDetectionStatus = OcrDetectionStatus.UNCERTAIN,
    val detectedLanguages: List<String> = emptyList(),
    val candidateLanguages: List<String> = emptyList(),
    val modelLanguages: String = "",
    val confidence: Float = 0f
)

/**
 * Snapshot request for an OCR job/session to ensure consistent routing across all pages.
 */
data class OcrRequest(
    val languageMode: OcrLanguageMode = OcrLanguageMode.AUTO,
    val languageTag: String = "vi",
    val engineMode: String = TextRecognitionHelper.ENGINE_MODE_AUTO
) {
    constructor(languageTag: String, engineMode: String) : this(
        languageMode = when (languageTag) {
            "vi+en" -> OcrLanguageMode.VI_EN
            "auto" -> OcrLanguageMode.AUTO
            else -> OcrLanguageMode.MANUAL
        },
        languageTag = if (languageTag == "auto" || languageTag == "vi+en") "vi" else languageTag,
        engineMode = engineMode
    )
}

enum class OcrModelUnavailableType {
    DOWNLOADING,
    MISSING,
    INIT_FAILED,
    NOT_ENOUGH_SPACE,
    NETWORK_ERROR
}

data class OcrEngineUsage(
    val engineId: String,
    val fallbackUsed: Boolean
)

enum class OcrFailureCode {
    IMAGE_LOAD_FAILED,
    EXECUTION_FAILED,
    UNKNOWN
}

fun resolveModelUnavailableType(reason: String): OcrModelUnavailableType {
    val lower = reason.lowercase(Locale.ROOT)
    return when {
        lower.contains("space") || lower.contains("storage") || lower.contains("not enough") || lower.contains("disk") ->
            OcrModelUnavailableType.NOT_ENOUGH_SPACE
        lower.contains("network") || lower.contains("connection") || lower.contains("offline") || lower.contains("internet") ->
            OcrModelUnavailableType.NETWORK_ERROR
        lower.contains("download") || lower.contains("waiting for") || lower.contains("not yet downloaded") ->
            OcrModelUnavailableType.DOWNLOADING
        lower.contains("missing") || lower.contains("not found") || lower.contains("traineddata") ->
            OcrModelUnavailableType.MISSING
        else ->
            OcrModelUnavailableType.INIT_FAILED
    }
}

fun resolveFailureCode(message: String, throwable: Throwable? = null): OcrFailureCode {
    val combined = (message + " " + (throwable?.message ?: "")).lowercase(Locale.ROOT)
    return when {
        combined.contains("bitmap") || combined.contains("image") || combined.contains("file") ||
        combined.contains("not exist") || combined.contains("load") || combined.contains("decode") ->
            OcrFailureCode.IMAGE_LOAD_FAILED
        else ->
            OcrFailureCode.EXECUTION_FAILED
    }
}

/**
 * Structured result for an OCR operation.
 */
sealed class OcrResult {
    data class Success(
        val text: String,
        val engineId: String,
        val documentLanguage: String,
        val fallbackUsed: Boolean = false,
        val detectionResult: OcrPageDetectionResult = OcrPageDetectionResult(),
        val pageDocument: OcrPage? = null
    ) : OcrResult()

    object NoText : OcrResult()
    data class UnsupportedLanguage(val languageTag: String) : OcrResult()
    data class IncompatibleEngine(val engineMode: String, val languageTag: String) : OcrResult()
    data class ModelUnavailable(
        val reason: String,
        val languageTag: String? = null,
        val type: OcrModelUnavailableType = resolveModelUnavailableType(reason)
    ) : OcrResult()
    data class Failure(
        val error: String,
        val throwable: Throwable? = null,
        val code: OcrFailureCode = resolveFailureCode(error, throwable)
    ) : OcrResult()

    val textOrNull: String?
        get() = (this as? Success)?.text
}

/**
 * Kết quả thực thi từ một engine OCR cấp thấp (Tesseract, Paddle, ML Kit).
 */
sealed class EngineRunResult {
    data class Success(
        val text: String,
        val pageDocument: OcrPage? = null
    ) : EngineRunResult()
    object NoText : EngineRunResult()
    data class ModelUnavailable(
        val reason: String,
        val type: OcrModelUnavailableType = resolveModelUnavailableType(reason)
    ) : EngineRunResult()
    data class Failure(
        val message: String,
        val cause: Throwable? = null,
        val code: OcrFailureCode = resolveFailureCode(message, cause)
    ) : EngineRunResult()
}

/**
 * Metadata cho một ngôn ngữ tài liệu OCR được hỗ trợ on-device.
 * Độc lập hoàn toàn với ngôn ngữ giao diện (UI language catalog).
 */
data class OcrDocumentLanguage(
    val tag: String,
    val nativeName: String,
    val vietnameseName: String,
    val englishName: String,
    val flag: String,
    val defaultEngineMode: String = TextRecognitionHelper.ENGINE_MODE_AUTO,
    val supportedEngineModes: List<String> = emptyList(),
    @get:StringRes val nameResId: Int = 0
)

/**
 * Pure routing resolver for unit testing engine selection and language compatibility.
 */
object OcrRoutingResolver {

    /**
     * Tập hợp 29 ngôn ngữ dùng chữ cái Latin được hỗ trợ on-device qua Google ML Kit Latin.
     */
    val SUPPORTED_LATIN_OCR_TAGS: Set<String> = setOf(
        "fr", "de", "es", "it", "pt", "nl", "pl", "tr", "id", "ms",
        "fil", "cs", "da", "sv", "nb", "fi", "hu", "ro", "hr", "sk",
        "sl", "sq", "ca", "et", "lv", "lt", "is", "af", "sr-Latn"
    )

    /**
     * Danh sách 36 ngôn ngữ tài liệu OCR được hỗ trợ on-device với metadata, cờ và resource name.
     */
    val SUPPORTED_OCR_DOCUMENT_LANGUAGES: List<OcrDocumentLanguage> = listOf(
        OcrDocumentLanguage("vi", "Tiếng Việt", "Tiếng Việt", "Vietnamese", "🇻🇳",
            nameResId = R.string.ocr_doc_lang_vi,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_TESSERACT, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("en", "English", "Tiếng Anh", "English", "🇺🇸",
            nameResId = R.string.ocr_doc_lang_en,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_TESSERACT, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("zh-Hans", "中文 (简体)", "Tiếng Trung (Giản thể)", "Chinese (Simplified)", "🇨🇳",
            nameResId = R.string.ocr_doc_lang_zh_hans,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("zh-Hant", "中文 (繁體)", "Tiếng Trung (Phồn thể)", "Chinese (Traditional)", "🇹🇼",
            nameResId = R.string.ocr_doc_lang_zh_hant,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("ja", "日本語", "Tiếng Nhật", "Japanese", "🇯🇵",
            nameResId = R.string.ocr_doc_lang_ja,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("ko", "한국어", "Tiếng Hàn", "Korean", "🇰🇷",
            nameResId = R.string.ocr_doc_lang_ko,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("hi", "हिन्दी", "Tiếng Hindi", "Hindi", "🇮🇳",
            nameResId = R.string.ocr_doc_lang_hi,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("fr", "Français", "Tiếng Pháp", "French", "🇫🇷",
            nameResId = R.string.ocr_doc_lang_fr,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("de", "Deutsch", "Tiếng Đức", "German", "🇩🇪",
            nameResId = R.string.ocr_doc_lang_de,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("es", "Español", "Tiếng Tây Ban Nha", "Spanish", "🇪🇸",
            nameResId = R.string.ocr_doc_lang_es,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("it", "Italiano", "Tiếng Ý", "Italian", "🇮🇹",
            nameResId = R.string.ocr_doc_lang_it,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("pt", "Português", "Tiếng Bồ Đào Nha", "Portuguese", "🇵🇹",
            nameResId = R.string.ocr_doc_lang_pt,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("nl", "Nederlands", "Tiếng Hà Lan", "Dutch", "🇳🇱",
            nameResId = R.string.ocr_doc_lang_nl,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("pl", "Polski", "Tiếng Ba Lan", "Polish", "🇵🇱",
            nameResId = R.string.ocr_doc_lang_pl,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("tr", "Türkçe", "Tiếng Thổ Nhĩ Kỳ", "Turkish", "🇹🇷",
            nameResId = R.string.ocr_doc_lang_tr,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("id", "Bahasa Indonesia", "Tiếng Indonesia", "Indonesian", "🇮🇩",
            nameResId = R.string.ocr_doc_lang_id,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("ms", "Bahasa Melayu", "Tiếng Mã Lai", "Malay", "🇲🇾",
            nameResId = R.string.ocr_doc_lang_ms,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("fil", "Filipino", "Tiếng Filipino", "Filipino", "🇵🇭",
            nameResId = R.string.ocr_doc_lang_fil,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("cs", "Čeština", "Tiếng Séc", "Czech", "🇨🇿",
            nameResId = R.string.ocr_doc_lang_cs,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("da", "Dansk", "Tiếng Đan Mạch", "Danish", "🇩🇰",
            nameResId = R.string.ocr_doc_lang_da,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("sv", "Svenska", "Tiếng Thụy Điển", "Swedish", "🇸🇪",
            nameResId = R.string.ocr_doc_lang_sv,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("nb", "Norsk Bokmål", "Tiếng Na Uy", "Norwegian Bokmål", "🇳🇴",
            nameResId = R.string.ocr_doc_lang_nb,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("fi", "Suomi", "Tiếng Phần Lan", "Finnish", "🇫🇮",
            nameResId = R.string.ocr_doc_lang_fi,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("hu", "Magyar", "Tiếng Hungary", "Hungarian", "🇭🇺",
            nameResId = R.string.ocr_doc_lang_hu,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("ro", "Română", "Tiếng Romania", "Romanian", "🇷🇴",
            nameResId = R.string.ocr_doc_lang_ro,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("hr", "Hrvatski", "Tiếng Croatia", "Croatian", "🇭🇷",
            nameResId = R.string.ocr_doc_lang_hr,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("sk", "Slovenčina", "Tiếng Slovakia", "Slovak", "🇸🇰",
            nameResId = R.string.ocr_doc_lang_sk,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("sl", "Slovenščina", "Tiếng Slovenia", "Slovenian", "🇸🇮",
            nameResId = R.string.ocr_doc_lang_sl,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("sq", "Shqip", "Tiếng Albania", "Albanian", "🇦🇱",
            nameResId = R.string.ocr_doc_lang_sq,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("ca", "Català", "Tiếng Catalan", "Catalan", "🇪🇸",
            nameResId = R.string.ocr_doc_lang_ca,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("et", "Eesti", "Tiếng Estonia", "Estonian", "🇪🇪",
            nameResId = R.string.ocr_doc_lang_et,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("lv", "Latviešu", "Tiếng Latvia", "Latvian", "🇱🇻",
            nameResId = R.string.ocr_doc_lang_lv,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("lt", "Lietuvių", "Tiếng Litva", "Lithuanian", "🇱🇹",
            nameResId = R.string.ocr_doc_lang_lt,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("is", "Íslenska", "Tiếng Iceland", "Icelandic", "🇮🇸",
            nameResId = R.string.ocr_doc_lang_is,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("af", "Afrikaans", "Tiếng Nam Phi", "Afrikaans", "🇿🇦",
            nameResId = R.string.ocr_doc_lang_af,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT)),
        OcrDocumentLanguage("sr-Latn", "Srpski (Latin)", "Tiếng Serbia", "Serbian (Latin)", "🇷🇸",
            nameResId = R.string.ocr_doc_lang_sr_latn,
            supportedEngineModes = listOf(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.ENGINE_MODE_MLKIT))
    )

    fun getDocumentLanguage(tag: String?): OcrDocumentLanguage? {
        val norm = normalizeLanguageTag(tag)
        return SUPPORTED_OCR_DOCUMENT_LANGUAGES.firstOrNull { it.tag.equals(norm, ignoreCase = true) }
    }

    fun getLocalizedDocumentLanguageName(context: android.content.Context, tag: String?): String {
        val docLang = getDocumentLanguage(tag) ?: return tag.orEmpty()
        if (docLang.nameResId != 0) {
            try {
                val resName = context.getString(docLang.nameResId)
                if (resName.isNotBlank()) return resName
            } catch (_: Exception) {}
        }
        val configLocale = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                context.resources?.configuration?.locales?.get(0) ?: java.util.Locale.getDefault()
            } else {
                @Suppress("DEPRECATION")
                context.resources?.configuration?.locale ?: java.util.Locale.getDefault()
            }
        } catch (_: Throwable) {
            java.util.Locale.getDefault()
        }
        if (configLocale.language == "vi") return docLang.vietnameseName
        if (configLocale.language == "en") return docLang.englishName
        return docLang.nativeName.ifBlank { docLang.englishName }
    }

    /**
     * Chuẩn hóa mã/tag ngôn ngữ tài liệu OCR kỹ thuật sử dụng Locale.ROOT.
     */
    fun normalizeLanguageTag(tag: String?): String {
        if (tag.isNullOrBlank()) return ""
        val trimmed = tag.trim().replace('_', '-')
        val lower = trimmed.lowercase(Locale.ROOT)
        return when {
            lower == "in" || lower.startsWith("in-") -> "id"
            lower == "tl" || lower.startsWith("tl-") -> "fil"
            lower == "no" || lower.startsWith("no-") -> "nb"
            lower == "zh" || lower.startsWith("zh-hans") || lower == "zh-cn" || lower == "zh-sg" -> "zh-Hans"
            lower.startsWith("zh-hant") || lower == "zh-tw" || lower == "zh-hk" || lower == "zh-mo" -> "zh-Hant"
            lower == "sr" || lower.startsWith("sr-latn") -> "sr-Latn"
            lower.startsWith("sr-cyrl") -> "sr-Cyrl"
            else -> {
                val idx = lower.indexOf('-')
                if (idx > 0) lower.substring(0, idx) else lower
            }
        }
    }

    /**
     * Lấy loại OCR model tương ứng với ngôn ngữ tài liệu.
     * Trả về OcrType.UNSUPPORTED_ON_DEVICE nếu không có model nhận diện tương thích trên thiết bị.
     */
    fun getOcrTypeForLanguage(languageTag: String?): OcrType {
        val norm = normalizeLanguageTag(languageTag)
        if (norm.isBlank()) return OcrType.UNSUPPORTED_ON_DEVICE

        return when (norm) {
            "vi" -> OcrType.TESSERACT_PRIMARY
            "en" -> OcrType.TESSERACT_PRIMARY
            "zh-Hans", "zh-Hant", "zh" -> OcrType.MLKIT_CHINESE
            "ja" -> OcrType.MLKIT_JAPANESE
            "ko" -> OcrType.MLKIT_KOREAN
            "hi" -> OcrType.PLAY_SERVICES_DEVANAGARI
            "ar", "th", "ru", "sr-Cyrl" -> OcrType.UNSUPPORTED_ON_DEVICE
            in SUPPORTED_LATIN_OCR_TAGS -> OcrType.MLKIT_LATIN
            else -> OcrType.UNSUPPORTED_ON_DEVICE
        }
    }

    /**
     * Kiểm tra xem ngôn ngữ tài liệu có được nhận diện on-device hay không.
     */
    fun isLanguageSupported(languageTag: String?): Boolean {
        return getOcrTypeForLanguage(languageTag) != OcrType.UNSUPPORTED_ON_DEVICE
    }

    /**
     * Kiểm tra tính tương thích giữa Engine Mode và OcrType.
     * - Tesseract chỉ hỗ trợ vi và en (TESSERACT_PRIMARY). Tuyệt đối không nhận các ngôn ngữ khác (R03).
     * - PaddleOCR là cấu hình cũ (legacy), không còn hỗ trợ thực thi trên máy.
     * - ML Kit hỗ trợ: vi/en, 29 ngôn ngữ Latin, Nhật, Hàn, Hindi, và Trung (qua module tương ứng).
     * - AUTO tương thích với mọi ngôn ngữ có thể nhận dạng on-device.
     */
    fun isEngineCompatible(engineMode: String, ocrType: OcrType): Boolean {
        if (ocrType == OcrType.UNSUPPORTED_ON_DEVICE) return false
        return when (engineMode) {
            TextRecognitionHelper.ENGINE_MODE_AUTO -> true
            TextRecognitionHelper.ENGINE_MODE_TESSERACT -> ocrType == OcrType.TESSERACT_PRIMARY
            TextRecognitionHelper.ENGINE_MODE_PADDLE -> false
            TextRecognitionHelper.ENGINE_MODE_MLKIT -> {
                ocrType == OcrType.TESSERACT_PRIMARY ||
                ocrType == OcrType.MLKIT_LATIN ||
                ocrType == OcrType.MLKIT_JAPANESE ||
                ocrType == OcrType.MLKIT_KOREAN ||
                ocrType == OcrType.PLAY_SERVICES_DEVANAGARI ||
                ocrType == OcrType.MLKIT_CHINESE
            }
            else -> false
        }
    }

    /**
     * Lấy mã model của Tesseract.
     * Chỉ trả về "vie" cho vi và "eng" cho en.
     * Mọi ngôn ngữ khác trả về null để ngăn chặn việc chạy sai model vie gây rác chữ (R03).
     */
    fun getTessLanguage(languageTag: String?): String? {
        val norm = normalizeLanguageTag(languageTag)
        return when (norm) {
            "en" -> "eng"
            "vi" -> "vie"
            else -> null
        }
    }

    /**
     * Danh sách các chế độ engine được hỗ trợ cho từng ngôn ngữ tài liệu cụ thể.
     */
    fun getSupportedEngineModesForLanguage(languageTag: String): List<String> {
        val ocrType = getOcrTypeForLanguage(languageTag)
        if (ocrType == OcrType.UNSUPPORTED_ON_DEVICE) return emptyList()

        val list = mutableListOf(TextRecognitionHelper.ENGINE_MODE_AUTO)
        if (isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_TESSERACT, ocrType)) {
            list.add(TextRecognitionHelper.ENGINE_MODE_TESSERACT)
        }
        if (isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_PADDLE, ocrType)) {
            list.add(TextRecognitionHelper.ENGINE_MODE_PADDLE)
        }
        if (isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_MLKIT, ocrType)) {
            list.add(TextRecognitionHelper.ENGINE_MODE_MLKIT)
        }
        return list
    }
}

/**
 * Kết quả tổng hợp có cấu trúc cho tác vụ OCR nhiều trang (R05).
 * Áp dụng nghiêm ngặt chính sách:
 * - Chỉ thêm header khi trang có text OCR thật; không chèn status/error vào văn bản xuất.
 * - Giữ nguyên số thứ tự trang gốc (1-based index).
 * - Nếu có bất kỳ trang nào bị lỗi (Failure, ModelUnavailable, IncompatibleEngine, UnsupportedLanguage):
 *   Dừng xuất tự động (PageError) với số trang lỗi đầu tiên để tránh xuất tài liệu thiếu trang im lặng.
 * - Nếu toàn bộ các trang đều NoText: trả về AllNoText để chặn mở kết quả giả / tạo file Word trống.
 * - Nếu có trang Success và trang NoText: trả về Success với thống kê pagesWithText, blankPages, totalPages.
 */
sealed class MultiPageOcrResult {
    data class Success(
        val fullText: String,
        val pagesWithText: Int,
        val blankPages: Int,
        val totalPages: Int,
        val enginesUsed: Set<String>,
        val engineIds: Set<String> = emptySet(),
        val engineUsages: List<OcrEngineUsage> = emptyList(),
        val documentLanguage: String? = null,
        val pageDetections: List<OcrPageDetectionResult> = emptyList(),
        val detectedLanguages: Set<String> = emptySet(),
        val document: OcrDocument? = null
    ) : MultiPageOcrResult()

    data class AllNoText(
        val totalPages: Int
    ) : MultiPageOcrResult()

    data class PageError(
        val failedPageNumber: Int,
        val errorResult: OcrResult,
        val totalPages: Int
    ) : MultiPageOcrResult()
}

/**
 * Pure multi-page OCR aggregator for deterministic testing and consistent UI behavior across callers.
 */
object MultiPageOcrAggregator {
    /**
     * Aggregates page-by-page OCR results into a structured [MultiPageOcrResult].
     *
     * @param pages An ordered list of page OCR results (0-indexed list representing 1-indexed document pages).
     * @param formatHeader A function to format the header string for a given 1-based page number (e.g. "--- PAGE 1 ---\n\n").
     * @param formatEngineMetadata A function to format engine metadata label from an [OcrResult.Success].
     */
    fun aggregate(
        pages: List<OcrResult>,
        formatHeader: (pageNumber: Int) -> String = { "--- PAGE $it ---\n\n" },
        formatEngineMetadata: (result: OcrResult.Success) -> String = { it.engineId }
    ): MultiPageOcrResult {
        if (pages.isEmpty()) {
            return MultiPageOcrResult.AllNoText(0)
        }

        val totalPages = pages.size

        // 1. Kiểm tra xem có trang nào bị lỗi không (dừng xuất tự động nếu thiếu trang do lỗi)
        for (i in pages.indices) {
            val pageResult = pages[i]
            if (isBlockingError(pageResult)) {
                return MultiPageOcrResult.PageError(
                    failedPageNumber = i + 1,
                    errorResult = pageResult,
                    totalPages = totalPages
                )
            }
        }

        // 2. Gom văn bản từ các trang Success
        val fullText = StringBuilder()
        val enginesUsed = mutableSetOf<String>()
        val engineIds = mutableSetOf<String>()
        val engineUsages = mutableListOf<OcrEngineUsage>()
        val pageDetections = mutableListOf<OcrPageDetectionResult>()
        val detectedLanguages = mutableSetOf<String>()
        var docLang: String? = null
        var pagesWithText = 0
        var blankPages = 0

        for (i in pages.indices) {
            val pageResult = pages[i]
            if (pageResult is OcrResult.Success) {
                pageDetections.add(pageResult.detectionResult)
                detectedLanguages.addAll(pageResult.detectionResult.detectedLanguages)
                if (pageResult.text.isNotBlank()) {
                    pagesWithText++
                    if (totalPages > 1) {
                        fullText.append(formatHeader(i + 1))
                    }
                    fullText.append(pageResult.text.trim()).append("\n\n")
                    enginesUsed.add(formatEngineMetadata(pageResult))
                    engineIds.add(pageResult.engineId)
                    val usage = OcrEngineUsage(pageResult.engineId, pageResult.fallbackUsed)
                    if (!engineUsages.contains(usage)) {
                        engineUsages.add(usage)
                    }
                    if (docLang == null && pageResult.documentLanguage.isNotBlank()) {
                        docLang = pageResult.documentLanguage
                    }
                } else {
                    blankPages++
                }
            } else {
                blankPages++
            }
        }

        val resultText = fullText.toString().trim()
        val ocrPages = mutableListOf<OcrPage>()
        for (i in pages.indices) {
            val pNum = i + 1
            when (val p = pages[i]) {
                is OcrResult.Success -> {
                    val rawPageDoc = p.pageDocument ?: OcrPage(
                        pageId = "page_${pNum}_${UUID.randomUUID().toString().take(8)}",
                        pageIndex = pNum,
                        status = if (p.text.isBlank()) OcrPageStatus.NO_TEXT else OcrPageStatus.SUCCESS,
                        engineId = p.engineId,
                        sourceLanguage = p.documentLanguage,
                        sourceBlocks = if (p.text.isBlank()) emptyList() else listOf(
                            OcrBlock(
                                blockId = "blk_${pNum}_0",
                                lines = p.text.lines().mapIndexed { lIdx, lText ->
                                    OcrLine(
                                        lineId = "line_${pNum}_$lIdx",
                                        text = lText
                                    )
                                }
                            )
                        )
                    )
                    val pageDoc = if (rawPageDoc.pageIndex != pNum) rawPageDoc.copy(pageIndex = pNum) else rawPageDoc
                    ocrPages.add(enrichPageWithLayoutAndTables(pageDoc))
                }
                is OcrResult.NoText -> {
                    ocrPages.add(
                        OcrPage(
                            pageId = "page_${pNum}_${UUID.randomUUID().toString().take(8)}",
                            pageIndex = pNum,
                            status = OcrPageStatus.NO_TEXT
                        )
                    )
                }
                is OcrResult.Failure -> {
                    ocrPages.add(
                        OcrPage(
                            pageId = "page_${pNum}_${UUID.randomUUID().toString().take(8)}",
                            pageIndex = pNum,
                            status = OcrPageStatus.ERROR,
                            errorMessage = p.error
                        )
                    )
                }
                is OcrResult.ModelUnavailable -> {
                    ocrPages.add(
                        OcrPage(
                            pageId = "page_${pNum}_${UUID.randomUUID().toString().take(8)}",
                            pageIndex = pNum,
                            status = OcrPageStatus.ERROR,
                            errorMessage = p.reason
                        )
                    )
                }
                is OcrResult.UnsupportedLanguage -> {
                    ocrPages.add(
                        OcrPage(
                            pageId = "page_${pNum}_${UUID.randomUUID().toString().take(8)}",
                            pageIndex = pNum,
                            status = OcrPageStatus.UNSUPPORTED_LANGUAGE,
                            errorMessage = "Unsupported language: ${p.languageTag}"
                        )
                    )
                }
                is OcrResult.IncompatibleEngine -> {
                    ocrPages.add(
                        OcrPage(
                            pageId = "page_${pNum}_${UUID.randomUUID().toString().take(8)}",
                            pageIndex = pNum,
                            status = OcrPageStatus.ERROR,
                            errorMessage = "Incompatible engine: ${p.engineMode}"
                        )
                    )
                }
            }
        }
        val document = OcrDocument(
            title = "Scanned Document",
            sourceLanguage = docLang,
            pages = ocrPages
        )

        return if (resultText.isEmpty()) {
            MultiPageOcrResult.AllNoText(totalPages)
        } else {
            MultiPageOcrResult.Success(
                fullText = resultText,
                pagesWithText = pagesWithText,
                blankPages = blankPages,
                totalPages = totalPages,
                enginesUsed = enginesUsed,
                engineIds = engineIds,
                engineUsages = engineUsages,
                documentLanguage = docLang,
                pageDetections = pageDetections,
                detectedLanguages = detectedLanguages,
                document = document
            )
        }
    }

    fun enrichPageWithLayoutAndTables(page: OcrPage): OcrPage {
        if (page.status != OcrPageStatus.SUCCESS || page.sourceBlocks.isEmpty()) return page

        // 1. Detect Tables if not already present
        val detectedTables = if (page.tables.isEmpty()) {
            try {
                com.tscanner.app.ocr.table.TableStructureAnalyzer.detectTables(page).tables
            } catch (_: Throwable) {
                emptyList()
            }
        } else {
            page.tables
        }

        // 2. Perform layout analysis to discover structured paragraphs and reading order
        val updatedEdited = if (page.editedContent == null) {
            try {
                val analysis = com.tscanner.app.ocr.layout.DocumentLayoutAnalyzer.analyzePage(page)
                if (analysis.columns.isNotEmpty()) {
                    val ocrParas = analysis.columns.flatMap { col ->
                        col.paragraphs.map { aPara ->
                            OcrParagraph(
                                paragraphId = aPara.paragraphId,
                                text = aPara.text,
                                alignment = if (aPara.isHeading) OcrTextAlignment.CENTER else OcrTextAlignment.LEFT,
                                runs = aPara.lines.mapIndexed { lIdx, l ->
                                    val sep = if (lIdx < aPara.lines.size - 1) "\n" else ""
                                    OcrTextRun(text = l.text + sep, isBold = aPara.isHeading)
                                }
                            )
                        }
                    }
                    OcrEditedContent(text = analysis.orderedText.ifBlank { page.resolvedText }, paragraphs = ocrParas)
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
        } else {
            page.editedContent
        }

        return page.copy(
            tables = detectedTables,
            editedContent = updatedEdited
        )
    }

    /**
     * Checks if an [OcrResult] represents a blocking engine/system failure rather than an empty page.
     */
    fun isBlockingError(result: OcrResult): Boolean {
        return when (result) {
            is OcrResult.Failure,
            is OcrResult.ModelUnavailable,
            is OcrResult.IncompatibleEngine,
            is OcrResult.UnsupportedLanguage -> true
            is OcrResult.Success,
            is OcrResult.NoText -> false
        }
    }
}

