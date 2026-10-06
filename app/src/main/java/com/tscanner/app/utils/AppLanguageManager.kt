package com.tscanner.app.utils

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * Chế độ ngôn ngữ giao diện (UI Language Mode):
 * - SYSTEM: Theo ngôn ngữ chính của thiết bị (primary-only, fallback về "en" nếu ngoài 8 nhóm hỗ trợ).
 * - MANUAL: Người dùng chọn thủ công một trong 8 ngôn ngữ hỗ trợ cố định.
 */
enum class UiLanguageMode {
    SYSTEM,
    MANUAL
}

enum class OcrType {
    TESSERACT_PRIMARY,          // vi, en (Mặc định Tiếng Việt & Tiếng Anh dùng Tesseract OCR v5 LSTM)
    MLKIT_CHINESE,              // zh, zh-Hans, zh-Hant (Google ML Kit Chinese Text Recognizer)
    MLKIT_JAPANESE,             // ja (Google ML Kit Japanese Text Recognizer)
    MLKIT_KOREAN,               // ko (Google ML Kit Korean Text Recognizer)
    MLKIT_LATIN,                // 29 ngôn ngữ Latin khác (Google ML Kit Latin)
    PLAY_SERVICES_DEVANAGARI,   // hi (Google Play Services Devanagari)
    UNSUPPORTED_ON_DEVICE       // th, ar
}

/**
 * Metadata cho một ngôn ngữ giao diện (UI Language).
 * Tách biệt hoàn toàn metadata hiển thị UI khỏi routing OCR trong tương lai.
 * Cung cấp các thuộc tính tương thích ngược cho các callsite hiện có.
 */
data class UiLanguage(
    val tag: String,
    val nativeName: String,
    val vietnameseName: String,
    val englishName: String,
    val flag: String,
    val aliases: List<String> = emptyList(),
    val legacyOcrType: OcrType = OcrType.MLKIT_LATIN
) {
    // Thuộc tính tương thích ngược cho LanguageAdapter và các màn hình hiện hữu
    val code: String get() = tag
    val ocrType: OcrType get() = legacyOcrType
    val isTesseractPrimary: Boolean get() = (tag == "vi" || tag == "en")
}

// Typealias tương thích ngược với code cũ
typealias SupportedLanguage = UiLanguage

/**
 * Metadata cho một ngôn ngữ UI tự động theo hệ thống (Kế hoạch 8 ngôn ngữ tự động 2026-09-18).
 * Ngôn ngữ giao diện tự động theo ngôn ngữ chính của hệ thống.
 */
data class AutoUiLanguage(
    val code: String,
    val nativeName: String,
    val englishName: String,
    val vietnameseName: String,
    val resourceQualifier: String
)

object AppLanguageManager {

    /**
     * Danh mục 8 ngôn ngữ UI tự động theo máy (E01).
     * Bảng tài nguyên chuẩn theo hợp đồng kế hoạch:
     * - en: English (values làm nguồn chuẩn)
     * - vi: Tiếng Việt (values-vi)
     * - es: Español (values-es)
     * - pt: Português, Brazil (values-pt)
     * - fr: Français (values-fr)
     * - id: Bahasa Indonesia (values-in, canonical logic là id)
     * - de: Deutsch (values-de)
     * - ja: 日本語 (values-ja)
     */
    val SUPPORTED_AUTO_UI_LANGUAGES: List<AutoUiLanguage> = listOf(
        AutoUiLanguage("en", "English", "English", "Tiếng Anh", "values"),
        AutoUiLanguage("vi", "Tiếng Việt", "Vietnamese", "Tiếng Việt", "values-vi"),
        AutoUiLanguage("es", "Español", "Spanish", "Tiếng Tây Ban Nha", "values-es"),
        AutoUiLanguage("pt", "Português (Brasil)", "Portuguese", "Tiếng Bồ Đào Nha", "values-pt"),
        AutoUiLanguage("fr", "Français", "French", "Tiếng Pháp", "values-fr"),
        AutoUiLanguage("id", "Bahasa Indonesia", "Indonesian", "Tiếng Indonesia", "values-in"),
        AutoUiLanguage("de", "Deutsch", "German", "Tiếng Đức", "values-de"),
        AutoUiLanguage("ja", "日本語", "Japanese", "Tiếng Nhật", "values-ja")
    )

    val SUPPORTED_AUTO_UI_TAGS: Set<String> = setOf("en", "vi", "es", "pt", "fr", "id", "de", "ja")
    const val DEFAULT_AUTO_UI_LANGUAGE: String = "en"

    private const val TAG = "AppLanguageManager"
    private const val PREFS_NAME = "tscanner_language_prefs"
    private const val KEY_SELECTED_LANG = "key_selected_lang"
    const val KEY_MIGRATED = "key_locales_migrated_v2"
    const val KEY_AUTO_SYSTEM_ENFORCED = "key_auto_system_enforced_v3"
    const val KEY_UI_LANGUAGE_MODE = "key_ui_language_mode_v4"
    const val KEY_MANUAL_UI_TAG = "key_manual_ui_tag_v4"
    const val KEY_UI_MIGRATED_V4 = "key_ui_language_migrated_v4"
    const val CODE_SYSTEM = "system"

    val SUPPORTED_LANGUAGES: List<UiLanguage> = listOf(
        UiLanguage("en", "English", "Tiếng Anh", "English", "🇺🇸", legacyOcrType = OcrType.TESSERACT_PRIMARY),
        UiLanguage("vi", "Tiếng Việt", "Tiếng Việt", "Vietnamese", "🇻🇳", legacyOcrType = OcrType.TESSERACT_PRIMARY),
        UiLanguage("es", "Español", "Tiếng Tây Ban Nha", "Spanish", "🇪🇸"),
        UiLanguage("pt", "Português (Brasil)", "Tiếng Bồ Đào Nha", "Portuguese", "🇧🇷"),
        UiLanguage("fr", "Français", "Tiếng Pháp", "French", "🇫🇷"),
        UiLanguage("id", "Bahasa Indonesia", "Tiếng Indonesia", "Indonesian", "🇮🇩", aliases = listOf("in")),
        UiLanguage("de", "Deutsch", "Tiếng Đức", "German", "🇩🇪"),
        UiLanguage("ja", "日本語", "Tiếng Nhật", "Japanese", "🇯🇵", legacyOcrType = OcrType.MLKIT_JAPANESE)
    )

    private val SUPPORTED_TAGS_SET: Set<String> = SUPPORTED_LANGUAGES.map { it.tag }.toSet()

    /**
     * Chuẩn hóa mã/tag ngôn ngữ sang canonical tag được hỗ trợ (8 ngôn ngữ UI tự động).
     * Trả về null nếu ngôn ngữ không nằm trong 8 nhóm được hỗ trợ.
     */
    fun normalizeTag(rawTag: String?): String? {
        if (rawTag.isNullOrBlank()) return null
        val trimmed = rawTag.trim().replace('_', '-')
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower == "und" || lower.startsWith("und-")) return null

        val sanitized = when {
            lower == "in" || lower.startsWith("in-") -> "id" + trimmed.substring(2)
            else -> trimmed
        }

        val loc = Locale.forLanguageTag(sanitized)
        val lang = loc.language.lowercase(Locale.ROOT)
        if (lang.isEmpty() || loc.toLanguageTag().equals("und", ignoreCase = true)) {
            return null
        }

        val canonical = when (lang) {
            "in" -> "id"
            "iw" -> "he"
            "ji" -> "yi"
            else -> lang
        }

        return if (SUPPORTED_AUTO_UI_TAGS.contains(canonical)) canonical else null
    }

    /**
     * Kiểm tra xem một mã hoặc tag ngôn ngữ có được ứng dụng hỗ trợ hay không.
     */
    fun isSupported(code: String?): Boolean {
        return normalizeTag(code) != null
    }

    /**
     * Chuẩn hóa mã ngôn ngữ, nếu không hỗ trợ thì fallback về "en".
     */
    fun normalizeCode(code: String): String {
        return normalizeTag(code) ?: "en"
    }

    fun getLanguage(code: String?): UiLanguage? {
        val canonicalTag = normalizeTag(code) ?: return null
        return SUPPORTED_LANGUAGES.firstOrNull { it.tag == canonicalTag }
    }

    /**
     * Tìm ngôn ngữ đầu tiên được ứng dụng hỗ trợ từ danh sách Locale ưu tiên (theo thứ tự).
     * Trả về canonical tag nếu tìm thấy, hoặc null nếu không có ngôn ngữ nào trong danh sách được hỗ trợ.
     */
    fun findFirstSupportedLocale(localeList: List<Locale>): String? {
        for (loc in localeList) {
            val tag = normalizeTag(loc.toLanguageTag())
            if (tag != null) {
                return tag
            }
        }
        return null
    }

    /**
     * Thuật toán phân giải ngôn ngữ hỗ trợ đầu tiên từ danh sách Locale của hệ thống (theo thứ tự ưu tiên).
     * Nếu không có ngôn ngữ nào được hỗ trợ, fallback về "en".
     */
    fun resolveLocaleFromList(localeList: List<Locale>): String {
        return findFirstSupportedLocale(localeList) ?: "en"
    }

    /**
     * Kết quả phân giải trạng thái ngôn ngữ hệ thống độc lập với override ứng dụng.
     */
    data class SystemLocaleResolution(
        val primaryLocale: Locale,
        val matchedSupportedTag: String?,
        val fallbackTag: String = "en"
    ) {
        val isSupported: Boolean get() = matchedSupportedTag != null
        val effectiveUiTag: String get() = matchedSupportedTag ?: fallbackTag
        val rawCode: String get() {
            val tag = primaryLocale.toLanguageTag()
            return if (tag.isNotBlank() && !tag.equals("und", ignoreCase = true)) tag else primaryLocale.language.lowercase(Locale.ROOT)
        }
    }

    /**
     * Lấy danh sách Locale hệ thống thật sự từ Resources.getSystem().
     * Tuyệt đối không đọc từ Context của Activity hay Application vì có thể bị override bởi per-app language (R08).
     */
    fun getSystemLocales(): List<Locale> {
        return getTrueSystemLocales()
    }

    /**
     * Lấy danh sách Locale hệ thống thật sự, độc lập tuyệt đối với override của ứng dụng.
     * - Trên API 33+: Sử dụng LocaleManager.systemLocales nếu context được cung cấp.
     * - Fallback cho mọi API: Đọc độc lập từ Resources.getSystem().configuration.
     */
    fun getTrueSystemLocales(context: Context? = null): List<Locale> {
        val result = mutableListOf<Locale>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && context != null) {
            try {
                val lm = context.getSystemService(LocaleManager::class.java)
                val sysLocales = lm?.systemLocales
                if (sysLocales != null && !sysLocales.isEmpty) {
                    for (i in 0 until sysLocales.size()) {
                        sysLocales.get(i)?.let { result.add(it) }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Không thể đọc systemLocales từ LocaleManager: ${e.message}")
            }
        }
        if (result.isEmpty()) {
            try {
                val sysConfig = Resources.getSystem().configuration
                val configLocales = ConfigurationCompat.getLocales(sysConfig)
                for (i in 0 until configLocales.size()) {
                    configLocales[i]?.let { result.add(it) }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Không thể đọc locales từ Resources.getSystem(): ${e.message}")
            }
        }
        if (result.isEmpty()) {
            result.add(Locale.getDefault())
        }
        return result
    }

    /**
     * Lấy canonical tag của locale đang thực sự được áp dụng trong framework (API 33+ LocaleManager hoặc AppCompatDelegate).
     * Trả về null nếu chưa được gán rõ ràng (empty locale list).
     */
    fun getCurrentlyAppliedLocaleTag(context: Context? = null): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && context != null) {
            try {
                val lm = context.getSystemService(LocaleManager::class.java)
                val appLocales = lm?.applicationLocales
                if (appLocales != null && !appLocales.isEmpty) {
                    val loc = appLocales.get(0)
                    if (loc != null) {
                        val lang = loc.language.lowercase(Locale.ROOT)
                        val canonical = if (lang == "in") "id" else lang
                        if (SUPPORTED_AUTO_UI_TAGS.contains(canonical)) {
                            return canonical
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Lỗi khi đọc applicationLocales từ LocaleManager: ${e.message}")
            }
        }
        val compatLocales = AppCompatDelegate.getApplicationLocales()
        if (!compatLocales.isEmpty) {
            val loc = compatLocales.get(0)
            if (loc != null) {
                val lang = loc.language.lowercase(Locale.ROOT)
                val canonical = if (lang == "in") "id" else lang
                if (SUPPORTED_AUTO_UI_TAGS.contains(canonical)) {
                    return canonical
                }
            }
        }
        return null
    }

    // ========================================================================
    // KIẾN TRÚC TỰ ĐỘNG 8 NGÔN NGỮ THEO MÁY (E01 - PLAN 2026-09-18)
    // ========================================================================

    /**
     * Phân giải ngôn ngữ UI từ một Locale đầu vào theo chính sách primary-only (E01):
     * - Chỉ nhận ngôn ngữ chính nếu thuộc 8 nhóm hỗ trợ: en, vi, es, pt, fr, id, de, ja.
     * - Nếu ngoài 8 nhóm (ko, zh, ar, th, ru...): fallback về "en".
     * - Không đọc được locale hợp lệ / rỗng / und / null: fallback về "en".
     * - Chuẩn hóa alias cổ: "in", "in-ID", "in_ID" -> "id".
     * - Chuẩn hóa region: "pt-PT", "pt-BR" -> "pt"; "es-ES", "es-MX" -> "es"; "fr-FR", "fr-CA" -> "fr";
     *   "de-DE", "de-AT" -> "de"; "ja-JP" -> "ja"; "vi-VN" -> "vi"; "en-US", "en-GB" -> "en".
     * - Luôn đảm bảo kết quả trả về là một trong 8 canonical tags.
     */
    fun resolvePrimaryAutoUiLanguage(primaryLocale: Locale?): String {
        if (primaryLocale == null) return DEFAULT_AUTO_UI_LANGUAGE

        val langTag = primaryLocale.toLanguageTag()
        if (langTag.equals("und", ignoreCase = true) || langTag.isBlank()) {
            val rawLang = primaryLocale.language
            if (rawLang.isNullOrBlank() || rawLang.equals("und", ignoreCase = true)) {
                return DEFAULT_AUTO_UI_LANGUAGE
            }
        }

        // Chuẩn hóa mã ngôn ngữ gốc (language code)
        val rawLanguage = primaryLocale.language.lowercase(Locale.ROOT)
        val normalizedLang = when (rawLanguage) {
            "in" -> "id"
            "iw" -> "he"
            "ji" -> "yi"
            else -> rawLanguage
        }

        return if (SUPPORTED_AUTO_UI_TAGS.contains(normalizedLang)) {
            normalizedLang
        } else {
            DEFAULT_AUTO_UI_LANGUAGE
        }
    }

    /**
     * Phân giải ngôn ngữ UI tự động từ danh sách locale của hệ thống.
     * QUAN TRỌNG (Hợp đồng E01):
     * Chỉ đọc duy nhất locale chính đứng đầu danh sách (systemLocales.firstOrNull()).
     * Tuyệt đối không duyệt sang ngôn ngữ thứ hai trở đi.
     * Ví dụ:
     * - [ko-KR, vi-VN] -> trả về "en" (không duyệt sang "vi").
     * - [fr-CA, en-US] -> trả về "fr".
     * - [ru, fr] -> trả về "en" (không duyệt sang "fr").
     * - [] -> trả về "en".
     */
    fun resolvePrimaryAutoUiLanguage(systemLocales: List<Locale>): String {
        val primaryLocale = systemLocales.firstOrNull()
        return resolvePrimaryAutoUiLanguage(primaryLocale)
    }

    /**
     * Tiện ích chuẩn hóa tag dạng chuỗi sang một trong 8 ngôn ngữ UI tự động.
     * Xử lý cả chuỗi dạng "pt-PT", "in-ID", "es-MX", "ko-KR", "zh-CN", etc.
     */
    fun resolvePrimaryAutoUiLanguage(rawTag: String?): String {
        if (rawTag.isNullOrBlank()) return DEFAULT_AUTO_UI_LANGUAGE
        val trimmed = rawTag.trim().replace('_', '-')
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower == "und" || lower.startsWith("und-")) return DEFAULT_AUTO_UI_LANGUAGE

        val sanitized = when {
            lower == "in" || lower.startsWith("in-") -> "id" + trimmed.substring(2)
            else -> trimmed
        }

        val loc = Locale.forLanguageTag(sanitized)
        return resolvePrimaryAutoUiLanguage(loc)
    }

    fun isSupportedAutoUiLanguage(tag: String?): Boolean {
        if (tag.isNullOrBlank()) return false
        val lower = tag.trim().lowercase(Locale.ROOT)
        val canonical = if (lower == "in") "id" else lower
        return SUPPORTED_AUTO_UI_TAGS.contains(canonical)
    }

    fun getAutoUiLanguage(tag: String?): AutoUiLanguage? {
        if (tag.isNullOrBlank()) return null
        val lower = tag.trim().lowercase(Locale.ROOT)
        val canonical = if (lower == "in") "id" else lower
        return SUPPORTED_AUTO_UI_LANGUAGES.firstOrNull { it.code == canonical }
    }

    /**
     * Phân giải trạng thái ngôn ngữ hệ thống độc lập với override của ứng dụng.
     * Cho phép truyền danh sách locale cụ thể (phục vụ unit test) hoặc tự động lấy từ Resources.getSystem().
     */
    fun resolveSystemLanguage(systemLocales: List<Locale> = getSystemLocales()): SystemLocaleResolution {
        val primary = if (systemLocales.isNotEmpty()) systemLocales[0] else Locale.getDefault()
        val matched = findFirstSupportedLocale(systemLocales)
        return SystemLocaleResolution(
            primaryLocale = primary,
            matchedSupportedTag = matched
        )
    }

    /**
     * Lấy canonical tag của ngôn ngữ hệ thống phù hợp nhất trên thiết bị (dành cho UI / badge).
     * - Nếu có ngôn ngữ được hỗ trợ trong danh sách hệ thống: trả về canonical tag đó (ví dụ "fr" khi máy có [ru, fr]).
     * - Nếu không có ngôn ngữ nào được hỗ trợ: trả về raw code/tag của locale đầu tiên (ví dụ "ru" khi máy chỉ có [ru]).
     * Tuyệt đối không phụ thuộc vào context để tránh đọc nhầm configuration override của ứng dụng (R08).
     */
    fun getSystemLanguageCode(context: Context? = null): String {
        val resolution = resolveSystemLanguage()
        return if (resolution.isSupported) {
            resolution.matchedSupportedTag!!
        } else {
            resolution.rawCode
        }
    }

    /**
     * Phân giải ngôn ngữ UI hiện tại đang áp dụng:
     * - Nếu người dùng có lựa chọn rõ ràng (explicit override): trả về ngôn ngữ override.
     * - Nếu ở chế độ "Theo hệ thống": duyệt danh sách locale của hệ thống/cấu hình để tìm ngôn ngữ hỗ trợ đầu tiên,
     *   nếu không có thì fallback về "en".
     */
    fun resolveSupportedLocale(
        explicitAppLocales: List<Locale>?,
        systemOrContextLocales: List<Locale>
    ): String {
        if (!explicitAppLocales.isNullOrEmpty()) {
            for (loc in explicitAppLocales) {
                val tag = normalizeTag(loc.toLanguageTag().ifBlank { loc.language })
                if (tag != null) {
                    return tag
                }
            }
        }
        return resolveLocaleFromList(systemOrContextLocales)
    }

    /**
     * Phân giải ngôn ngữ UI hiện tại đang áp dụng cho ứng dụng.
     * - Nếu người dùng chọn ngôn ngữ cụ thể (override): đọc từ AppCompatDelegate.
     * - Nếu ở chế độ "Theo hệ thống" (empty locales): đọc danh sách locale từ configuration của context.
     */
    fun resolveSupportedLocale(context: Context): String {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        val explicitList = if (!appLocales.isEmpty) {
            val list = mutableListOf<Locale>()
            for (i in 0 until appLocales.size()) {
                appLocales[i]?.let { list.add(it) }
            }
            list
        } else {
            null
        }

        val configLocales = try {
            ConfigurationCompat.getLocales(context.resources.configuration)
        } catch (e: Exception) {
            ConfigurationCompat.getLocales(Resources.getSystem().configuration)
        }

        val contextList = mutableListOf<Locale>()
        for (i in 0 until configLocales.size()) {
            configLocales[i]?.let { contextList.add(it) }
        }

        return resolveSupportedLocale(explicitList, contextList)
    }

    /**
     * Các bước quyết định hành vi migration locale từ preference cũ.
     */
    enum class MigrationStep {
        ALREADY_COMPLETED,
        NO_OP_SYSTEM_DEFAULT,
        UNSUPPORTED_LEGACY,
        PRESERVE_EXISTING_FRAMEWORK,
        APPLY_VIA_LOCALE_MANAGER,
        APPLY_VIA_APPCOMPAT,
        DEFER_TO_ACTIVITY
    }

    /**
     * Xác định bước migration logic độc lập với framework state, phục vụ kiểm thử đơn vị.
     */
    fun determineMigrationStep(
        isMigrated: Boolean,
        legacySavedLang: String?,
        hasExistingFrameworkLocales: Boolean,
        isApi33Plus: Boolean,
        isActivityContext: Boolean
    ): Pair<MigrationStep, String?> {
        if (isMigrated) {
            return Pair(MigrationStep.ALREADY_COMPLETED, null)
        }
        if (legacySavedLang == null || legacySavedLang == CODE_SYSTEM) {
            return Pair(MigrationStep.NO_OP_SYSTEM_DEFAULT, null)
        }
        val canonical = normalizeTag(legacySavedLang)
        if (canonical == null) {
            return Pair(MigrationStep.UNSUPPORTED_LEGACY, null)
        }
        if (hasExistingFrameworkLocales) {
            return Pair(MigrationStep.PRESERVE_EXISTING_FRAMEWORK, canonical)
        }
        if (isApi33Plus) {
            return Pair(MigrationStep.APPLY_VIA_LOCALE_MANAGER, canonical)
        }
        return if (isActivityContext) {
            Pair(MigrationStep.APPLY_VIA_APPCOMPAT, canonical)
        } else {
            Pair(MigrationStep.DEFER_TO_ACTIVITY, canonical)
        }
    }

    /**
     * Kết quả đánh giá migration SharedPreferences cho UI language (Gói U01).
     */
    data class UiMigrationResult(
        val migrated: Boolean,
        val mode: UiLanguageMode,
        val manualTag: String?
    )

    /**
     * Đánh giá migration cấu hình ngôn ngữ UI độc lập với Android framework:
     * - Bản v3 trở về trước luôn ép auto, nên khi lên v4 mặc định chuyển sang SYSTEM.
     * - Tag manual không hợp lệ/đã bị loại phải quay về SYSTEM.
     */
    fun evaluateUiMigration(
        isMigratedV4: Boolean,
        savedMode: String?,
        savedManualTag: String?,
        legacySelectedLang: String? = null
    ): UiMigrationResult {
        if (isMigratedV4) {
            if (savedMode == UiLanguageMode.MANUAL.name) {
                val normalized = normalizeTag(savedManualTag)
                if (normalized != null) {
                    return UiMigrationResult(migrated = false, mode = UiLanguageMode.MANUAL, manualTag = normalized)
                }
            }
            return UiMigrationResult(migrated = false, mode = UiLanguageMode.SYSTEM, manualTag = null)
        }

        // Migration: mặc định chuyển sang SYSTEM, không hiểu locale framework đã lưu là manual
        return UiMigrationResult(migrated = true, mode = UiLanguageMode.SYSTEM, manualTag = null)
    }

    /**
     * Thực hiện migration SharedPreferences nếu chưa nâng cấp lên v4.
     */
    fun migrateUiPreferencesIfNeeded(prefs: SharedPreferences): UiMigrationResult {
        val isMigratedV4 = prefs.getBoolean(KEY_UI_MIGRATED_V4, false)
        val savedMode = prefs.getString(KEY_UI_LANGUAGE_MODE, null)
        val savedManualTag = prefs.getString(KEY_MANUAL_UI_TAG, null)
        val legacySelected = prefs.getString(KEY_SELECTED_LANG, null)

        val eval = evaluateUiMigration(isMigratedV4, savedMode, savedManualTag, legacySelected)
        if (eval.migrated) {
            prefs.edit()
                .putBoolean(KEY_UI_MIGRATED_V4, true)
                .putString(KEY_UI_LANGUAGE_MODE, eval.mode.name)
                .apply {
                    if (eval.manualTag != null) {
                        putString(KEY_MANUAL_UI_TAG, eval.manualTag)
                    } else {
                        remove(KEY_MANUAL_UI_TAG)
                    }
                    remove(KEY_SELECTED_LANG)
                }
                .apply()
        } else if (savedMode == UiLanguageMode.MANUAL.name && eval.mode == UiLanguageMode.SYSTEM) {
            prefs.edit()
                .putString(KEY_UI_LANGUAGE_MODE, UiLanguageMode.SYSTEM.name)
                .remove(KEY_MANUAL_UI_TAG)
                .apply()
        }
        return eval
    }

    /**
     * Lấy chế độ ngôn ngữ giao diện đang lưu (SYSTEM hoặc MANUAL).
     */
    fun getUiLanguageMode(context: Context): UiLanguageMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val eval = migrateUiPreferencesIfNeeded(prefs)
        return eval.mode
    }

    /**
     * Lấy tag ngôn ngữ thủ công (nếu ở chế độ MANUAL và tag hợp lệ). Trả về null nếu ở SYSTEM.
     */
    fun getManualUiTag(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val eval = migrateUiPreferencesIfNeeded(prefs)
        return eval.manualTag
    }

    /**
     * Quyết định target tag cho chính sách ngôn ngữ UI (Gói U01):
     * - MANUAL: Dùng manualTag nếu hợp lệ. Nếu không hợp lệ -> SYSTEM -> target theo máy.
     * - SYSTEM: Dùng ngôn ngữ chính của thiết bị (primary-only: 8 nhóm, ngoài 8 nhóm -> en).
     */
    fun determinePolicyTarget(
        mode: UiLanguageMode,
        manualTag: String?,
        systemLocales: List<Locale>
    ): Pair<UiLanguageMode, String> {
        if (mode == UiLanguageMode.MANUAL) {
            val canonical = normalizeTag(manualTag)
            if (canonical != null) {
                return Pair(UiLanguageMode.MANUAL, canonical)
            }
        }
        val autoTag = resolvePrimaryAutoUiLanguage(systemLocales)
        return Pair(UiLanguageMode.SYSTEM, autoTag)
    }

    /**
     * Các bước thực thi chính sách ngôn ngữ UI (Gói U01).
     */
    enum class PolicyEnforcementStep {
        ALREADY_IN_SYNC,
        APPLY_VIA_LOCALE_MANAGER,
        APPLY_VIA_APPCOMPAT,
        DEFER_TO_ACTIVITY
    }

    /**
     * Xác định hành vi thực thi chính sách:
     * - Nếu locale đang áp dụng đã khớp targetTag: ALREADY_IN_SYNC (chống recreate loop).
     * - Nếu khác: gán qua LocaleManager (API 33+) hoặc AppCompatDelegate.
     */
    fun determinePolicyEnforcementStep(
        targetTag: String,
        currentAppliedTag: String?,
        isApi33Plus: Boolean,
        isActivityContext: Boolean
    ): Pair<PolicyEnforcementStep, String> {
        val canonicalTarget = if (SUPPORTED_AUTO_UI_TAGS.contains(targetTag)) targetTag else DEFAULT_AUTO_UI_LANGUAGE
        if (currentAppliedTag == canonicalTarget) {
            return Pair(PolicyEnforcementStep.ALREADY_IN_SYNC, canonicalTarget)
        }

        if (isApi33Plus) {
            return Pair(PolicyEnforcementStep.APPLY_VIA_LOCALE_MANAGER, canonicalTarget)
        }

        return if (isActivityContext) {
            Pair(PolicyEnforcementStep.APPLY_VIA_APPCOMPAT, canonicalTarget)
        } else {
            Pair(PolicyEnforcementStep.DEFER_TO_ACTIVITY, canonicalTarget)
        }
    }

    /**
     * Áp dụng chính sách ngôn ngữ UI hiện hành (SYSTEM hoặc MANUAL):
     * - MANUAL: Giữ nguyên lựa chọn người dùng, không bị resume ghi đè.
     * - SYSTEM: Theo ngôn ngữ chính của máy, tự động cập nhật khi máy đổi ngôn ngữ.
     * - Trả về true nếu framework locales được cập nhật (gây recreate), false nếu đã đồng bộ.
     */
    fun applyPolicy(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        migrateUiPreferencesIfNeeded(prefs)

        val mode = getUiLanguageMode(context)
        val manualTag = getManualUiTag(context)
        val trueSystemLocales = getTrueSystemLocales(context)

        val (effectiveMode, targetTag) = determinePolicyTarget(mode, manualTag, trueSystemLocales)

        if (effectiveMode != mode) {
            prefs.edit()
                .putString(KEY_UI_LANGUAGE_MODE, UiLanguageMode.SYSTEM.name)
                .remove(KEY_MANUAL_UI_TAG)
                .apply()
        }

        val currentApplied = getCurrentlyAppliedLocaleTag(context)
        val isApi33Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val isActivity = context is Activity

        val (step, tagToApply) = determinePolicyEnforcementStep(
            targetTag = targetTag,
            currentAppliedTag = currentApplied,
            isApi33Plus = isApi33Plus,
            isActivityContext = isActivity
        )

        Log.d(TAG, "applyPolicy: mode=$effectiveMode, target=$targetTag, currentApplied=$currentApplied, step=$step, context=${context.javaClass.simpleName}")

        return when (step) {
            PolicyEnforcementStep.ALREADY_IN_SYNC -> false

            PolicyEnforcementStep.APPLY_VIA_LOCALE_MANAGER -> {
                if (isApi33Plus) {
                    try {
                        val lm = context.getSystemService(LocaleManager::class.java)
                        lm?.applicationLocales = LocaleList.forLanguageTags(tagToApply)
                    } catch (e: Throwable) {
                        Log.w(TAG, "Lỗi khi gán applicationLocales: ${e.message}")
                    }
                }
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tagToApply))
                true
            }

            PolicyEnforcementStep.APPLY_VIA_APPCOMPAT -> {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tagToApply))
                true
            }

            PolicyEnforcementStep.DEFER_TO_ACTIVITY -> {
                try {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tagToApply))
                } catch (e: Throwable) {
                    Log.w(TAG, "DEFER_TO_ACTIVITY: ${e.message}")
                }
                false
            }
        }
    }

    /**
     * Thiết lập ngôn ngữ giao diện theo lựa chọn của người dùng:
     * - mode == SYSTEM: theo máy (primary-only).
     * - mode == MANUAL: mã thủ công trong 8 ngôn ngữ hỗ trợ.
     */
    fun setUiLanguage(context: Context, mode: UiLanguageMode, manualTag: String? = null): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit().putBoolean(KEY_UI_MIGRATED_V4, true)

        if (mode == UiLanguageMode.MANUAL) {
            val canonical = normalizeTag(manualTag)
            if (canonical != null) {
                editor.putString(KEY_UI_LANGUAGE_MODE, UiLanguageMode.MANUAL.name)
                editor.putString(KEY_MANUAL_UI_TAG, canonical)
            } else {
                editor.putString(KEY_UI_LANGUAGE_MODE, UiLanguageMode.SYSTEM.name)
                editor.remove(KEY_MANUAL_UI_TAG)
            }
        } else {
            editor.putString(KEY_UI_LANGUAGE_MODE, UiLanguageMode.SYSTEM.name)
            editor.remove(KEY_MANUAL_UI_TAG)
        }
        editor.remove(KEY_SELECTED_LANG)
        editor.apply()

        return applyPolicy(context)
    }

    /**
     * Các bước quyết định thực thi chính sách tự động theo máy (E03 - duy trì tương thích).
     */
    enum class AutoSystemEnforcementStep {
        ALREADY_IN_SYNC,
        CLEANUP_LEGACY_PREFS_ONLY,
        APPLY_VIA_LOCALE_MANAGER,
        APPLY_VIA_APPCOMPAT,
        DEFER_TO_ACTIVITY
    }

    /**
     * Xác định hành vi thực thi chính sách tự động theo máy (E03), phục vụ kiểm thử đơn vị độc lập.
     */
    fun determineAutoSystemEnforcementStep(
        targetAutoTag: String,
        currentAppliedTag: String?,
        legacySavedLang: String?,
        isApi33Plus: Boolean,
        isActivityContext: Boolean
    ): Pair<AutoSystemEnforcementStep, String> {
        val canonicalTarget = if (SUPPORTED_AUTO_UI_TAGS.contains(targetAutoTag)) targetAutoTag else DEFAULT_AUTO_UI_LANGUAGE
        if (currentAppliedTag == canonicalTarget) {
            return if (legacySavedLang != null) {
                Pair(AutoSystemEnforcementStep.CLEANUP_LEGACY_PREFS_ONLY, canonicalTarget)
            } else {
                Pair(AutoSystemEnforcementStep.ALREADY_IN_SYNC, canonicalTarget)
            }
        }

        if (isApi33Plus) {
            return Pair(AutoSystemEnforcementStep.APPLY_VIA_LOCALE_MANAGER, canonicalTarget)
        }

        return if (isActivityContext) {
            Pair(AutoSystemEnforcementStep.APPLY_VIA_APPCOMPAT, canonicalTarget)
        } else {
            Pair(AutoSystemEnforcementStep.DEFER_TO_ACTIVITY, canonicalTarget)
        }
    }

    /**
     * Áp dụng chính sách ngôn ngữ UI (chuyển hướng sang applyPolicy để hỗ trợ cả SYSTEM và MANUAL).
     */
    fun enforceAutoSystemLanguage(context: Context): Boolean {
        return applyPolicy(context)
    }

    /**
     * Tương thích ngược: chuyển hướng sang applyPolicy.
     */
    fun migrateLegacyLocaleIfNeeded(context: Context): Boolean {
        return applyPolicy(context)
    }

    /**
     * Khởi tạo ngôn ngữ ứng dụng khi khởi động (Application.onCreate).
     */
    fun initAppLanguage(context: Context) {
        applyPolicy(context)
    }

    /**
     * Lấy mã canonical tag hiện tại đang kích hoạt cho UI (luôn thuộc 8 ngôn ngữ).
     */
    fun getCurrentLanguageCode(context: Context? = null): String {
        if (context != null) {
            val mode = getUiLanguageMode(context)
            if (mode == UiLanguageMode.MANUAL) {
                val manual = getManualUiTag(context)
                if (manual != null) return manual
            } else {
                return resolvePrimaryAutoUiLanguage(getTrueSystemLocales(context))
            }
        }
        val applied = getCurrentlyAppliedLocaleTag(context)
        if (applied != null && SUPPORTED_AUTO_UI_TAGS.contains(applied)) {
            return applied
        }
        val trueSystemLocales = getTrueSystemLocales(context)
        return resolvePrimaryAutoUiLanguage(trueSystemLocales)
    }

    /**
     * Lấy tên hiển thị của ngôn ngữ hiện tại cho UI badges (thuộc 8 ngôn ngữ).
     */
    fun getCurrentLanguageDisplayName(context: Context): String {
        val code = getCurrentLanguageCode(context)
        val autoLang = getAutoUiLanguage(code)
        if (autoLang != null) {
            return autoLang.nativeName
        }
        val lang = getLanguage(code)
        return lang?.nativeName ?: "English"
    }

    /**
     * Kiểm tra xem ứng dụng có đang ở chế độ "Theo hệ thống" hay không.
     */
    fun isSystemDefaultSelected(context: Context? = null): Boolean {
        if (context == null) return true
        return getUiLanguageMode(context) == UiLanguageMode.SYSTEM
    }

    /**
     * Áp dụng ngôn ngữ UI: null hoặc "system" -> SYSTEM; mã ngôn ngữ -> MANUAL.
     */
    fun applyLanguage(context: Context, languageCode: String?): Boolean {
        return if (languageCode == null || languageCode == CODE_SYSTEM) {
            setUiLanguage(context, UiLanguageMode.SYSTEM)
        } else {
            setUiLanguage(context, UiLanguageMode.MANUAL, languageCode)
        }
    }
}
