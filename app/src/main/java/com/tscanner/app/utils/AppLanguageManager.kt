package com.tscanner.app.utils

import android.content.Context
import android.content.res.Resources
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import java.util.Locale

enum class OcrType {
    TESSERACT_PRIMARY,          // vi, en (Mặc định Tiếng Việt & Tiếng Anh dùng Tesseract OCR v5 LSTM)
    PADDLE_OCR_V4,              // zh (PaddleOCR v4 Mobile Baidu ONNX cho Tiếng Trung)
    MLKIT_JAPANESE,             // ja (Google ML Kit Japanese Text Recognizer)
    MLKIT_KOREAN,               // ko (Google ML Kit Korean Text Recognizer)
    MLKIT_LATIN,                // 29 ngôn ngữ Latin khác (Google ML Kit Latin)
    PLAY_SERVICES_DEVANAGARI,   // hi (Google Play Services Devanagari)
    UNSUPPORTED_ON_DEVICE       // th, ar
}

data class SupportedLanguage(
    val code: String,
    val nativeName: String,
    val vietnameseName: String,
    val flag: String,
    val ocrType: OcrType,
    val isTesseractPrimary: Boolean = (code == "vi" || code == "en")
)

object AppLanguageManager {

    private const val TAG = "AppLanguageManager"
    private const val PREFS_NAME = "tscanner_language_prefs"
    private const val KEY_SELECTED_LANG = "key_selected_lang"
    const val CODE_SYSTEM = "system"

    val SUPPORTED_LANGUAGES: List<SupportedLanguage> = listOf(
        // 1. Tiếng Việt & Tiếng Anh mặc định sử dụng Tesseract OCR v5 LSTM chuyên sâu:
        SupportedLanguage("vi", "Tiếng Việt", "Tiếng Việt", "🇻🇳", OcrType.TESSERACT_PRIMARY),
        SupportedLanguage("en", "English", "Tiếng Anh", "🇺🇸", OcrType.TESSERACT_PRIMARY),

        // 2. Nhóm ngôn ngữ Châu Á với nhận diện chuyên biệt:
        SupportedLanguage("zh", "中文 (简体)", "Tiếng Trung (Giản thể)", "🇨🇳", OcrType.PADDLE_OCR_V4),
        SupportedLanguage("ja", "日本語", "Tiếng Nhật", "🇯🇵", OcrType.MLKIT_JAPANESE),
        SupportedLanguage("ko", "한국어", "Tiếng Hàn", "🇰🇷", OcrType.MLKIT_KOREAN),

        // 2. 29 ngôn ngữ giữ nguyên cơ chế OCR của ML Kit (Latin):
        SupportedLanguage("fr", "Français", "Tiếng Pháp", "🇫🇷", OcrType.MLKIT_LATIN),
        SupportedLanguage("es", "Español", "Tiếng Tây Ban Nha", "🇪🇸", OcrType.MLKIT_LATIN),
        SupportedLanguage("de", "Deutsch", "Tiếng Đức", "🇩🇪", OcrType.MLKIT_LATIN),
        SupportedLanguage("it", "Italiano", "Tiếng Ý", "🇮🇹", OcrType.MLKIT_LATIN),
        SupportedLanguage("pt", "Português", "Tiếng Bồ Đào Nha", "🇵🇹", OcrType.MLKIT_LATIN),
        SupportedLanguage("nl", "Nederlands", "Tiếng Hà Lan", "🇳🇱", OcrType.MLKIT_LATIN),
        SupportedLanguage("pl", "Polski", "Tiếng Ba Lan", "🇵🇱", OcrType.MLKIT_LATIN),
        SupportedLanguage("tr", "Türkçe", "Tiếng Thổ Nhĩ Kỳ", "🇹🇷", OcrType.MLKIT_LATIN),
        SupportedLanguage("id", "Bahasa Indonesia", "Tiếng Indonesia", "🇮🇩", OcrType.MLKIT_LATIN),
        SupportedLanguage("ms", "Bahasa Melayu", "Tiếng Mã Lai", "🇲🇾", OcrType.MLKIT_LATIN),
        SupportedLanguage("fil", "Filipino", "Tiếng Philippines", "🇵🇭", OcrType.MLKIT_LATIN),
        SupportedLanguage("cs", "Čeština", "Tiếng Séc", "🇨🇿", OcrType.MLKIT_LATIN),
        SupportedLanguage("da", "Dansk", "Tiếng Đan Mạch", "🇩🇰", OcrType.MLKIT_LATIN),
        SupportedLanguage("sv", "Svenska", "Tiếng Thụy Điển", "🇸🇪", OcrType.MLKIT_LATIN),
        SupportedLanguage("no", "Norsk", "Tiếng Na Uy", "🇳🇴", OcrType.MLKIT_LATIN),
        SupportedLanguage("fi", "Suomi", "Tiếng Phần Lan", "🇫🇮", OcrType.MLKIT_LATIN),
        SupportedLanguage("hu", "Magyar", "Tiếng Hungary", "🇭🇺", OcrType.MLKIT_LATIN),
        SupportedLanguage("ro", "Română", "Tiếng Romania", "🇷🇴", OcrType.MLKIT_LATIN),
        SupportedLanguage("hr", "Hrvatski", "Tiếng Croatia", "🇭🇷", OcrType.MLKIT_LATIN),
        SupportedLanguage("sk", "Slovenčina", "Tiếng Slovakia", "🇸🇰", OcrType.MLKIT_LATIN),
        SupportedLanguage("sl", "Slovenščina", "Tiếng Slovenia", "🇸🇮", OcrType.MLKIT_LATIN),
        SupportedLanguage("sq", "Shqip", "Tiếng Albania", "🇦🇱", OcrType.MLKIT_LATIN),
        SupportedLanguage("ca", "Català", "Tiếng Catalan", "🇪🇸", OcrType.MLKIT_LATIN),
        SupportedLanguage("et", "Eesti", "Tiếng Estonia", "🇪🇪", OcrType.MLKIT_LATIN),
        SupportedLanguage("lv", "Latviešu", "Tiếng Latvia", "🇱🇻", OcrType.MLKIT_LATIN),
        SupportedLanguage("lt", "Lietuvių", "Tiếng Litva", "🇱🇹", OcrType.MLKIT_LATIN),
        SupportedLanguage("is", "Íslenska", "Tiếng Iceland", "🇮🇸", OcrType.MLKIT_LATIN),
        SupportedLanguage("af", "Afrikaans", "Tiếng Nam Phi", "🇿🇦", OcrType.MLKIT_LATIN),
        SupportedLanguage("sr", "Srpski (Latin)", "Tiếng Serbia", "🇷🇸", OcrType.MLKIT_LATIN),

        // 3. Tiếng Hindi giữ nguyên ML Kit Devanagari:
        SupportedLanguage("hi", "हिन्दी", "Tiếng Hindi", "🇮🇳", OcrType.PLAY_SERVICES_DEVANAGARI),

        // 4. Các ngôn ngữ được hỗ trợ giao diện / hệ thống:
        SupportedLanguage("th", "ไทย", "Tiếng Thái", "🇹🇭", OcrType.UNSUPPORTED_ON_DEVICE),
        SupportedLanguage("ar", "العربية", "Tiếng Ả Rập", "🇸🇦", OcrType.UNSUPPORTED_ON_DEVICE)
    )

    private val SUPPORTED_CODES_SET: Set<String> = SUPPORTED_LANGUAGES.map { it.code }.toSet()

    /**
     * Checks whether a given language code is supported.
     */
    fun isSupported(code: String?): Boolean {
        if (code == null) return false
        val normalized = normalizeCode(code)
        return SUPPORTED_CODES_SET.contains(normalized)
    }

    /**
     * Normalizes language tags (e.g., 'tl' -> 'fil', 'zh-Hans' -> 'zh', 'in' -> 'id').
     */
    fun normalizeCode(code: String): String {
        val lower = code.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("zh") -> "zh"
            lower == "tl" -> "fil"
            lower == "in" -> "id"
            lower == "iw" -> "he"
            lower.startsWith("sr") -> "sr"
            lower.contains("-") -> lower.split("-")[0]
            lower.contains("_") -> lower.split("_")[0]
            else -> lower
        }
    }

    fun getLanguage(code: String?): SupportedLanguage? {
        if (code == null) return null
        val normalized = normalizeCode(code)
        return SUPPORTED_LANGUAGES.firstOrNull { it.code == normalized }
    }

    /**
     * Returns the raw system language code of the device.
     */
    fun getSystemLanguageCode(): String {
        val systemLocale = try {
            ConfigurationCompat.getLocales(Resources.getSystem().configuration)[0]
                ?: Locale.getDefault()
        } catch (e: Exception) {
            Locale.getDefault()
        }
        return normalizeCode(systemLocale.language)
    }

    /**
     * Initializes app language on application startup.
     * Rule:
     * - Uses device system language as display language if supported.
     * - If system language is outside supported languages, automatically falls back to English ('en').
     */
    fun initAppLanguage(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedLang = prefs.getString(KEY_SELECTED_LANG, null)

        if (savedLang == null || savedLang == CODE_SYSTEM) {
            val sysCode = getSystemLanguageCode()
            if (isSupported(sysCode)) {
                Log.d(TAG, "Device language ($sysCode) is supported.")
                val appLocales = AppCompatDelegate.getApplicationLocales()
                if (!appLocales.isEmpty && normalizeCode(appLocales[0]?.language ?: "") != sysCode) {
                    val tag = if (sysCode == "zh") "zh-Hans" else sysCode
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                }
            } else {
                Log.i(TAG, "Device language ($sysCode) is outside supported languages. Falling back to English (en).")
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
            }
        } else {
            Log.d(TAG, "Restoring user selected language: $savedLang")
            val tag = if (savedLang == "zh") "zh-Hans" else savedLang
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
    }

    /**
     * Gets the currently active language code for OCR and app operations.
     */
    fun getCurrentLanguageCode(context: Context): String {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        if (!appLocales.isEmpty) {
            val code = appLocales[0]?.language
            if (!code.isNullOrEmpty()) {
                return normalizeCode(code)
            }
        }

        // System default fallback logic
        val sysCode = getSystemLanguageCode()
        return if (isSupported(sysCode)) sysCode else "en"
    }

    /**
     * Gets the display name of the current language for UI badges.
     */
    fun getCurrentLanguageDisplayName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedLang = prefs.getString(KEY_SELECTED_LANG, null)

        val code = getCurrentLanguageCode(context)
        val lang = getLanguage(code)
        val name = lang?.nativeName ?: "English"

        return if (savedLang == null || savedLang == CODE_SYSTEM) {
            val sysCode = getSystemLanguageCode()
            if (isSupported(sysCode)) {
                name
            } else {
                "English (Mặc định máy: chưa hỗ trợ)"
            }
        } else {
            name
        }
    }

    fun isSystemDefaultSelected(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedLang = prefs.getString(KEY_SELECTED_LANG, null)
        return savedLang == null || savedLang == CODE_SYSTEM
    }

    /**
     * Applies a new language or sets to system default.
     * Pass null or "system" for system default.
     */
    fun applyLanguage(context: Context, languageCode: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (languageCode.isNullOrEmpty() || languageCode == CODE_SYSTEM) {
            prefs.edit().putString(KEY_SELECTED_LANG, CODE_SYSTEM).apply()
            val sysCode = getSystemLanguageCode()
            if (isSupported(sysCode)) {
                val tag = if (sysCode == "zh") "zh-Hans" else sysCode
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            } else {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
            }
        } else {
            val normalized = normalizeCode(languageCode)
            prefs.edit().putString(KEY_SELECTED_LANG, normalized).apply()
            val tag = if (normalized == "zh") "zh-Hans" else normalized
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
    }
}
