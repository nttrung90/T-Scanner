package com.tscanner.app

import com.tscanner.app.utils.AppLanguageManager
import com.tscanner.app.utils.OcrType
import com.tscanner.app.utils.UiLanguageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class AppLanguageManagerTest {

    @Test
    fun testSupportedLanguages_hasExactly8LanguagesWithUniqueTags() {
        val languages = AppLanguageManager.SUPPORTED_LANGUAGES
        assertEquals("Phải có chính xác 8 ngôn ngữ được hỗ trợ", 8, languages.size)

        val tags = languages.map { it.tag }
        val uniqueTags = tags.toSet()
        assertEquals("Tất cả các canonical tags phải duy nhất", 8, uniqueTags.size)

        val expectedTags = setOf("en", "vi", "es", "pt", "fr", "id", "de", "ja")
        assertEquals(expectedTags, uniqueTags)

        // Đảm bảo không có tag rỗng
        assertTrue(tags.none { it.isBlank() })
    }

    @Test
    fun testLocalesConfigXml_matchesSupportedLanguagesExactly() {
        val configFile = File("src/main/res/xml/locales_config.xml")
        val altConfigFile = File("app/src/main/res/xml/locales_config.xml")
        val fileToRead = if (configFile.exists()) configFile else altConfigFile

        assertTrue("Tệp locales_config.xml phải tồn tại", fileToRead.exists())

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(fileToRead)
        val localeNodes = doc.getElementsByTagName("locale")

        assertEquals("locales_config.xml phải khai báo chính xác 8 locale", 8, localeNodes.length)

        val xmlLocales = mutableSetOf<String>()
        for (i in 0 until localeNodes.length) {
            val node = localeNodes.item(i)
            val name = node.attributes.getNamedItem("android:name")?.nodeValue
            if (name != null) {
                xmlLocales.add(name)
            }
        }

        val supportedTags = AppLanguageManager.SUPPORTED_LANGUAGES.map { it.tag }.toSet()
        assertEquals("Tất cả canonical tags trong AppLanguageManager phải khớp 1:1 với locales_config.xml",
            supportedTags, xmlLocales)
    }

    @Test
    fun testNormalizeTag_legacyAliasesAndRegionalVariants() {
        // Indonesian (in, id) -> canonical id
        assertEquals("id", AppLanguageManager.normalizeTag("in"))
        assertEquals("id", AppLanguageManager.normalizeTag("in-ID"))
        assertEquals("id", AppLanguageManager.normalizeTag("in_ID"))
        assertEquals("id", AppLanguageManager.normalizeTag("id"))
        assertEquals("id", AppLanguageManager.normalizeTag("id-ID"))

        // Các ngôn ngữ không nằm trong 8 nhóm -> trả về null
        assertNull(AppLanguageManager.normalizeTag("tl"))
        assertNull(AppLanguageManager.normalizeTag("tl-PH"))
        assertNull(AppLanguageManager.normalizeTag("fil"))
        assertNull(AppLanguageManager.normalizeTag("no"))
        assertNull(AppLanguageManager.normalizeTag("nb"))
        assertNull(AppLanguageManager.normalizeTag("sr"))
        assertNull(AppLanguageManager.normalizeTag("sr-Latn"))
        assertNull(AppLanguageManager.normalizeTag("zh"))
        assertNull(AppLanguageManager.normalizeTag("zh-CN"))
        assertNull(AppLanguageManager.normalizeTag("zh-SG"))
        assertNull(AppLanguageManager.normalizeTag("zh-Hans"))
        assertNull(AppLanguageManager.normalizeTag("ko"))
        assertNull(AppLanguageManager.normalizeTag("ar"))
        assertNull(AppLanguageManager.normalizeTag("th"))
        assertNull(AppLanguageManager.normalizeTag("ru"))
        assertNull(AppLanguageManager.normalizeTag("it"))

        // 8 ngôn ngữ hỗ trợ với biến thể vùng miền
        assertEquals("vi", AppLanguageManager.normalizeTag("vi"))
        assertEquals("vi", AppLanguageManager.normalizeTag("vi-VN"))
        assertEquals("en", AppLanguageManager.normalizeTag("en"))
        assertEquals("en", AppLanguageManager.normalizeTag("en-US"))
        assertEquals("en", AppLanguageManager.normalizeTag("en-GB"))
        assertEquals("es", AppLanguageManager.normalizeTag("es"))
        assertEquals("es", AppLanguageManager.normalizeTag("es-ES"))
        assertEquals("es", AppLanguageManager.normalizeTag("es-MX"))
        assertEquals("pt", AppLanguageManager.normalizeTag("pt"))
        assertEquals("pt", AppLanguageManager.normalizeTag("pt-BR"))
        assertEquals("pt", AppLanguageManager.normalizeTag("pt-PT"))
        assertEquals("fr", AppLanguageManager.normalizeTag("fr"))
        assertEquals("fr", AppLanguageManager.normalizeTag("fr-FR"))
        assertEquals("fr", AppLanguageManager.normalizeTag("fr-CA"))
        assertEquals("de", AppLanguageManager.normalizeTag("de"))
        assertEquals("de", AppLanguageManager.normalizeTag("de-DE"))
        assertEquals("de", AppLanguageManager.normalizeTag("de-AT"))
        assertEquals("ja", AppLanguageManager.normalizeTag("ja"))
        assertEquals("ja", AppLanguageManager.normalizeTag("ja-JP"))
    }

    @Test
    fun testNormalizeTag_scriptAwareRejection() {
        // Traditional Chinese không được tự ép sang Simplified Chinese
        assertNull("zh-Hant không được map sang zh-Hans", AppLanguageManager.normalizeTag("zh-Hant"))
        assertNull("zh-Hant-TW không được map sang zh-Hans", AppLanguageManager.normalizeTag("zh-Hant-TW"))
        assertNull("zh-TW không được map sang zh-Hans", AppLanguageManager.normalizeTag("zh-TW"))
        assertNull("zh-HK không được map sang zh-Hans", AppLanguageManager.normalizeTag("zh-HK"))
        assertNull("zh-MO không được map sang zh-Hans", AppLanguageManager.normalizeTag("zh-MO"))

        // Serbian Cyrillic không được tự ép sang Latin
        assertNull("sr-Cyrl không được map sang sr-Latn", AppLanguageManager.normalizeTag("sr-Cyrl"))
        assertNull("sr-Cyrl-RS không được map sang sr-Latn", AppLanguageManager.normalizeTag("sr-Cyrl-RS"))

        // Nynorsk không được tự nhận diện là Bokmål
        assertNull("nn không được map sang nb", AppLanguageManager.normalizeTag("nn"))
        assertNull("nn-NO không được map sang nb", AppLanguageManager.normalizeTag("nn-NO"))

        // Tag sai hoặc rỗng
        assertNull(AppLanguageManager.normalizeTag(null))
        assertNull(AppLanguageManager.normalizeTag(""))
        assertNull(AppLanguageManager.normalizeTag("   "))
        assertNull(AppLanguageManager.normalizeTag("und"))
        assertNull(AppLanguageManager.normalizeTag("invalid-tag-xyz"))
        assertNull(AppLanguageManager.normalizeTag("12345"))
    }

    @Test
    fun testIsSupported() {
        assertTrue(AppLanguageManager.isSupported("vi"))
        assertTrue(AppLanguageManager.isSupported("en"))
        assertTrue(AppLanguageManager.isSupported("es"))
        assertTrue(AppLanguageManager.isSupported("pt"))
        assertTrue(AppLanguageManager.isSupported("fr"))
        assertTrue(AppLanguageManager.isSupported("id"))
        assertTrue(AppLanguageManager.isSupported("de"))
        assertTrue(AppLanguageManager.isSupported("ja"))
        assertTrue(AppLanguageManager.isSupported("in")) // Alias cho id

        // Các ngôn ngữ ngoài 8 nhóm
        assertFalse(AppLanguageManager.isSupported("zh-Hans"))
        assertFalse(AppLanguageManager.isSupported("zh-CN"))
        assertFalse(AppLanguageManager.isSupported("sr-Latn"))
        assertFalse(AppLanguageManager.isSupported("sr"))
        assertFalse(AppLanguageManager.isSupported("nb"))
        assertFalse(AppLanguageManager.isSupported("no"))
        assertFalse(AppLanguageManager.isSupported("tl"))
        assertFalse(AppLanguageManager.isSupported("fil"))
        assertFalse(AppLanguageManager.isSupported("ko"))
        assertFalse(AppLanguageManager.isSupported("ar"))
        assertFalse(AppLanguageManager.isSupported("th"))
        assertFalse(AppLanguageManager.isSupported("ru"))
        assertFalse(AppLanguageManager.isSupported("it"))
        assertFalse(AppLanguageManager.isSupported("zh-Hant"))
        assertFalse(AppLanguageManager.isSupported("zh-TW"))
        assertFalse(AppLanguageManager.isSupported("sr-Cyrl"))
        assertFalse(AppLanguageManager.isSupported("nn"))
        assertFalse(AppLanguageManager.isSupported(null))
        assertFalse(AppLanguageManager.isSupported(""))
        assertFalse(AppLanguageManager.isSupported("klingon"))
    }

    @Test
    fun testNormalizeCode_fallback() {
        assertEquals("vi", AppLanguageManager.normalizeCode("vi-VN"))
        assertEquals("es", AppLanguageManager.normalizeCode("es-ES"))
        assertEquals("pt", AppLanguageManager.normalizeCode("pt-BR"))
        assertEquals("fr", AppLanguageManager.normalizeCode("fr-FR"))
        assertEquals("id", AppLanguageManager.normalizeCode("in-ID"))
        assertEquals("de", AppLanguageManager.normalizeCode("de-DE"))
        assertEquals("ja", AppLanguageManager.normalizeCode("ja-JP"))
        // Fallback en cho các ngôn ngữ ngoài 8 nhóm
        assertEquals("en", AppLanguageManager.normalizeCode("zh-CN"))
        assertEquals("en", AppLanguageManager.normalizeCode("sr-Latn-RS"))
        assertEquals("en", AppLanguageManager.normalizeCode("zh-Hant"))
        assertEquals("en", AppLanguageManager.normalizeCode("sr-Cyrl"))
        assertEquals("en", AppLanguageManager.normalizeCode("ko-KR"))
        assertEquals("en", AppLanguageManager.normalizeCode("unknown-language"))
        assertEquals("en", AppLanguageManager.normalizeCode(""))
    }

    @Test
    fun testGetLanguage_aliasLookup() {
        val id = AppLanguageManager.getLanguage("in")
        assertNotNull(id)
        assertEquals("id", id?.tag)

        val idDirect = AppLanguageManager.getLanguage("id")
        assertNotNull(idDirect)
        assertEquals("id", idDirect?.tag)

        // Các ngôn ngữ ngoài 8 nhóm không còn tồn tại trong UI catalog
        assertNull(AppLanguageManager.getLanguage("zh"))
        assertNull(AppLanguageManager.getLanguage("sr"))
        assertNull(AppLanguageManager.getLanguage("no"))
        assertNull(AppLanguageManager.getLanguage("fil"))
        assertNull(AppLanguageManager.getLanguage("tl"))
        assertNull(AppLanguageManager.getLanguage("ko"))
        assertNull(AppLanguageManager.getLanguage("ar"))
        assertNull(AppLanguageManager.getLanguage("zh-TW"))
        assertNull(AppLanguageManager.getLanguage("sr-Cyrl"))
    }

    @Test
    fun testBackwardCompatibilityProperties() {
        for (lang in AppLanguageManager.SUPPORTED_LANGUAGES) {
            assertEquals("code phải bằng tag", lang.tag, lang.code)
            assertEquals("ocrType phải bằng legacyOcrType", lang.legacyOcrType, lang.ocrType)
            if (lang.tag == "vi" || lang.tag == "en") {
                assertTrue("vi và en là Tesseract primary", lang.isTesseractPrimary)
                assertEquals(OcrType.TESSERACT_PRIMARY, lang.ocrType)
            } else {
                assertFalse("${lang.tag} không phải Tesseract primary", lang.isTesseractPrimary)
            }
        }
    }

    @Test
    fun testResolveLocaleFromList_priorityAndFallback() {
        // Kịch bản F03: Máy có danh sách [ru, fr] -> ru không hỗ trợ nhưng fr đứng thứ hai phải được chọn (của legacy multi-locale lookup)
        val listRuFr = listOf(Locale("ru"), Locale("fr"))
        assertEquals("fr", AppLanguageManager.resolveLocaleFromList(listRuFr))

        // Kịch bản máy chỉ có [ru] -> Không hỗ trợ -> Fallback về English "en"
        val listRu = listOf(Locale("ru"))
        assertEquals("en", AppLanguageManager.resolveLocaleFromList(listRu))

        // Kịch bản máy có danh sách rỗng -> Fallback "en"
        assertEquals("en", AppLanguageManager.resolveLocaleFromList(emptyList()))

        // Kịch bản máy có [vi-VN, en-US] -> Chọn "vi"
        val listViEn = listOf(Locale("vi", "VN"), Locale("en", "US"))
        assertEquals("vi", AppLanguageManager.resolveLocaleFromList(listViEn))

        // Kịch bản máy có [sr-Cyrl, de] -> sr-Cyrl bị từ chối, de được chọn
        val listSrCyrlDe = listOf(Locale.forLanguageTag("sr-Cyrl-RS"), Locale("de"))
        assertEquals("de", AppLanguageManager.resolveLocaleFromList(listSrCyrlDe))

        // Kịch bản máy có [zh-Hant-TW, ja] -> zh-Hant bị từ chối, ja được chọn
        val listZhHantJa = listOf(Locale.forLanguageTag("zh-Hant-TW"), Locale("ja"))
        assertEquals("ja", AppLanguageManager.resolveLocaleFromList(listZhHantJa))

        // Kịch bản máy có [zh-CN] -> Ngoài 8 nhóm -> Fallback "en"
        val listZhCn = listOf(Locale("zh", "CN"))
        assertEquals("en", AppLanguageManager.resolveLocaleFromList(listZhCn))
    }

    @Test
    fun testRemoveDiacritics_handlesVietnameseSpecialD() {
        assertEquals("Tieng Duc", com.tscanner.app.ui.dialogs.LanguageAdapter.removeDiacritics("Tiếng Đức"))
        assertEquals("duc", com.tscanner.app.ui.dialogs.LanguageAdapter.removeDiacritics("đức"))
        assertEquals("DUC", com.tscanner.app.ui.dialogs.LanguageAdapter.removeDiacritics("ĐỨC"))
        assertEquals("Tieng Viet", com.tscanner.app.ui.dialogs.LanguageAdapter.removeDiacritics("Tiếng Việt"))
    }

    @Test
    fun testFindFirstSupportedLocale() {
        assertNull(AppLanguageManager.findFirstSupportedLocale(emptyList()))
        assertNull(AppLanguageManager.findFirstSupportedLocale(listOf(Locale("ru"), Locale("uk"))))
        assertEquals("fr", AppLanguageManager.findFirstSupportedLocale(listOf(Locale("ru"), Locale("fr"))))
        assertEquals("vi", AppLanguageManager.findFirstSupportedLocale(listOf(Locale("vi", "VN"))))
    }

    @Test
    fun testResolveSystemLanguage_andEffectiveUiSeparation() {
        // Kịch bản 1: Hệ thống tiếng Anh [en-US]
        val resEn = AppLanguageManager.resolveSystemLanguage(listOf(Locale("en", "US")))
        assertTrue("Hệ thống en-US phải được nhận là supported", resEn.isSupported)
        assertEquals("en", resEn.matchedSupportedTag)
        assertEquals("en", resEn.effectiveUiTag)

        // Kịch bản 2: Hệ thống [ru, fr] (ưu tiên locale thứ 2 khi locale đầu không hỗ trợ)
        val resRuFr = AppLanguageManager.resolveSystemLanguage(listOf(Locale("ru"), Locale("fr")))
        assertTrue("Hệ thống [ru, fr] phải được nhận là supported nhờ fr", resRuFr.isSupported)
        assertEquals("fr", resRuFr.matchedSupportedTag)
        assertEquals("fr", resRuFr.effectiveUiTag)
        assertEquals("ru", resRuFr.rawCode)

        // Kịch bản 3: Hệ thống chỉ có [ru] (không hỗ trợ)
        val resRu = AppLanguageManager.resolveSystemLanguage(listOf(Locale("ru")))
        assertFalse("Hệ thống chỉ có ru phải là unsupported", resRu.isSupported)
        assertNull("matchedSupportedTag phải null khi không có ngôn ngữ hỗ trợ", resRu.matchedSupportedTag)
        assertEquals("effectiveUiTag phải fallback về en khi hệ thống không hỗ trợ", "en", resRu.effectiveUiTag)
        assertEquals("rawCode phải giữ mã ngôn ngữ máy ru để UI cảnh báo", "ru", resRu.rawCode)

        // Kịch bản 4: Hệ thống [zh-Hant-TW] (Traditional Chinese không tự ép về Simplified)
        val resZhHant = AppLanguageManager.resolveSystemLanguage(listOf(Locale.forLanguageTag("zh-Hant-TW")))
        assertFalse("zh-Hant không được tự nhận diện hỗ trợ", resZhHant.isSupported)
        assertNull(resZhHant.matchedSupportedTag)
        assertEquals("en", resZhHant.effectiveUiTag)

        // Kịch bản 5: Hệ thống [zh-CN] (Simplified Chinese - ngoài 8 ngôn ngữ UI tự động)
        val resZhHans = AppLanguageManager.resolveSystemLanguage(listOf(Locale("zh", "CN")))
        assertFalse(resZhHans.isSupported)
        assertNull(resZhHans.matchedSupportedTag)
        assertEquals("en", resZhHans.effectiveUiTag)

        // Kịch bản 6: Hệ thống [es-ES] (Tây Ban Nha - trong 8 ngôn ngữ UI tự động)
        val resEs = AppLanguageManager.resolveSystemLanguage(listOf(Locale("es", "ES")))
        assertTrue(resEs.isSupported)
        assertEquals("es", resEs.matchedSupportedTag)
        assertEquals("es", resEs.effectiveUiTag)
    }

    @Test
    fun testResolveSupportedLocale_overrideTakesPrecedenceOverSystem() {
        val systemEn = listOf(Locale("en", "US"))
        val systemRuFr = listOf(Locale("ru"), Locale("fr"))
        val systemRu = listOf(Locale("ru"))

        val appOverrideJa = listOf(Locale("ja", "JP"))
        val appOverrideVi = listOf(Locale("vi", "VN"))

        // R08 test: Máy tiếng Anh (en), app chọn Japanese (ja) -> UI phải ra ja, không bị ảnh hưởng bởi system
        val uiLocale1 = AppLanguageManager.resolveSupportedLocale(appOverrideJa, systemEn)
        assertEquals("ja", uiLocale1)

        // Máy [ru, fr], app chọn Vietnamese (vi) -> UI ra vi
        val uiLocale2 = AppLanguageManager.resolveSupportedLocale(appOverrideVi, systemRuFr)
        assertEquals("vi", uiLocale2)

        // Không có override (chế độ Theo hệ thống) + máy [ru, fr] -> UI ra fr
        val uiLocale3 = AppLanguageManager.resolveSupportedLocale(null, systemRuFr)
        assertEquals("fr", uiLocale3)

        // Không có override (chế độ Theo hệ thống) + máy [ru] -> UI fallback en
        val uiLocale4 = AppLanguageManager.resolveSupportedLocale(null, systemRu)
        assertEquals("en", uiLocale4)

        // Không có override + máy [en] -> UI ra en
        val uiLocale5 = AppLanguageManager.resolveSupportedLocale(emptyList(), systemEn)
        assertEquals("en", uiLocale5)
    }

    @Test
    fun testDetermineMigrationStep_lifecycleAndSafetyRules() {
        // 1. Đã migrate từ trước -> ALREADY_COMPLETED
        val (step1, tag1) = AppLanguageManager.determineMigrationStep(
            isMigrated = true,
            legacySavedLang = "vi",
            hasExistingFrameworkLocales = false,
            isApi33Plus = true,
            isActivityContext = true
        )
        assertEquals(AppLanguageManager.MigrationStep.ALREADY_COMPLETED, step1)
        assertNull(tag1)

        // 2. Legacy null hoặc "system" -> NO_OP_SYSTEM_DEFAULT
        val (step2a, _) = AppLanguageManager.determineMigrationStep(
            isMigrated = false,
            legacySavedLang = null,
            hasExistingFrameworkLocales = false,
            isApi33Plus = false,
            isActivityContext = false
        )
        assertEquals(AppLanguageManager.MigrationStep.NO_OP_SYSTEM_DEFAULT, step2a)

        val (step2b, _) = AppLanguageManager.determineMigrationStep(
            isMigrated = false,
            legacySavedLang = "system",
            hasExistingFrameworkLocales = false,
            isApi33Plus = true,
            isActivityContext = false
        )
        assertEquals(AppLanguageManager.MigrationStep.NO_OP_SYSTEM_DEFAULT, step2b)

        // 3. Legacy không hỗ trợ -> UNSUPPORTED_LEGACY
        val (step3, _) = AppLanguageManager.determineMigrationStep(
            isMigrated = false,
            legacySavedLang = "klingon",
            hasExistingFrameworkLocales = false,
            isApi33Plus = false,
            isActivityContext = false
        )
        assertEquals(AppLanguageManager.MigrationStep.UNSUPPORTED_LEGACY, step3)

        // 4. Framework/AppCompat đã có thiết lập riêng -> PRESERVE_EXISTING_FRAMEWORK (không ghi đè)
        val (step4, tag4) = AppLanguageManager.determineMigrationStep(
            isMigrated = false,
            legacySavedLang = "vi",
            hasExistingFrameworkLocales = true,
            isApi33Plus = true,
            isActivityContext = false
        )
        assertEquals(AppLanguageManager.MigrationStep.PRESERVE_EXISTING_FRAMEWORK, step4)
        assertEquals("vi", tag4)

        // 5. API 33+ nâng cấp chỉ có legacy explicit -> APPLY_VIA_LOCALE_MANAGER trực tiếp
        val (step5, tag5) = AppLanguageManager.determineMigrationStep(
            isMigrated = false,
            legacySavedLang = "ja",
            hasExistingFrameworkLocales = false,
            isApi33Plus = true,
            isActivityContext = false
        )
        assertEquals(AppLanguageManager.MigrationStep.APPLY_VIA_LOCALE_MANAGER, step5)
        assertEquals("ja", tag5)

        // 6. API 33+ với alias cũ (in -> id)
        val (step6, tag6) = AppLanguageManager.determineMigrationStep(
            isMigrated = false,
            legacySavedLang = "in-ID",
            hasExistingFrameworkLocales = false,
            isApi33Plus = true,
            isActivityContext = false
        )
        assertEquals(AppLanguageManager.MigrationStep.APPLY_VIA_LOCALE_MANAGER, step6)
        assertEquals("id", tag6)

        // 7. API < 33 khi chạy ở Application.onCreate (chưa có Activity delegate) -> DEFER_TO_ACTIVITY (không xoá preference!)
        val (step7, tag7) = AppLanguageManager.determineMigrationStep(
            isMigrated = false,
            legacySavedLang = "vi",
            hasExistingFrameworkLocales = false,
            isApi33Plus = false,
            isActivityContext = false
        )
        assertEquals(AppLanguageManager.MigrationStep.DEFER_TO_ACTIVITY, step7)
        assertEquals("vi", tag7)

        // 8. API < 33 khi Activity đã được tạo -> APPLY_VIA_APPCOMPAT
        val (step8, tag8) = AppLanguageManager.determineMigrationStep(
            isMigrated = false,
            legacySavedLang = "vi",
            hasExistingFrameworkLocales = false,
            isApi33Plus = false,
            isActivityContext = true
        )
        assertEquals(AppLanguageManager.MigrationStep.APPLY_VIA_APPCOMPAT, step8)
        assertEquals("vi", tag8)
    }

    // ========================================================================
    // KIỂM THỬ HỢP ĐỒNG 8 NGÔN NGỮ TỰ ĐỘNG THEO MÁY (E01)
    // ========================================================================

    @Test
    fun testSupportedAutoUiLanguages_catalogContract() {
        val languages = AppLanguageManager.SUPPORTED_AUTO_UI_LANGUAGES
        assertEquals("Phải có chính xác 8 ngôn ngữ UI tự động", 8, languages.size)

        val codes = languages.map { it.code }
        val expectedCodes = setOf("en", "vi", "es", "pt", "fr", "id", "de", "ja")
        assertEquals(expectedCodes, codes.toSet())
        assertEquals("Tất cả canonical tags phải duy nhất", 8, codes.toSet().size)

        val expectedQualifiers = mapOf(
            "en" to "values",
            "vi" to "values-vi",
            "es" to "values-es",
            "pt" to "values-pt",
            "fr" to "values-fr",
            "id" to "values-in",
            "de" to "values-de",
            "ja" to "values-ja"
        )
        for (lang in languages) {
            assertEquals(
                "Resource qualifier cho ${lang.code} phải khớp bảng kế hoạch",
                expectedQualifiers[lang.code],
                lang.resourceQualifier
            )
            assertTrue("Tên hiển thị không được rỗng", lang.nativeName.isNotBlank())
            assertTrue("Tên tiếng Anh không được rỗng", lang.englishName.isNotBlank())
            assertTrue("Tên tiếng Việt không được rỗng", lang.vietnameseName.isNotBlank())
        }
    }

    @Test
    fun testResolvePrimaryAutoUiLanguage_tableSection1FullContract() {
        // Hàng 1: en, en-US, en-GB… -> English ("en", values)
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("en")))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("en", "US")))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("en", "GB")))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage("en"))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage("en-US"))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage("en-GB"))

        // Hàng 2: vi, vi-VN… -> Tiếng Việt ("vi", values-vi)
        assertEquals("vi", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("vi")))
        assertEquals("vi", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("vi", "VN")))
        assertEquals("vi", AppLanguageManager.resolvePrimaryAutoUiLanguage("vi"))
        assertEquals("vi", AppLanguageManager.resolvePrimaryAutoUiLanguage("vi-VN"))

        // Hàng 3: es, es-ES, es-MX… -> Español ("es", values-es)
        assertEquals("es", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("es")))
        assertEquals("es", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("es", "ES")))
        assertEquals("es", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("es", "MX")))
        assertEquals("es", AppLanguageManager.resolvePrimaryAutoUiLanguage("es"))
        assertEquals("es", AppLanguageManager.resolvePrimaryAutoUiLanguage("es-ES"))
        assertEquals("es", AppLanguageManager.resolvePrimaryAutoUiLanguage("es-MX"))

        // Hàng 4: pt, pt-BR, pt-PT… -> Português ("pt", values-pt với nội dung Brazil)
        // Lưu ý: Mọi máy tiếng Bồ Đào Nha (kể cả Bồ Đào Nha hay Brazil) đều nhận cùng bản dịch "pt"
        assertEquals("pt", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("pt")))
        assertEquals("pt", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("pt", "BR")))
        assertEquals("pt", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("pt", "PT")))
        assertEquals("pt", AppLanguageManager.resolvePrimaryAutoUiLanguage("pt"))
        assertEquals("pt", AppLanguageManager.resolvePrimaryAutoUiLanguage("pt-BR"))
        assertEquals("pt", AppLanguageManager.resolvePrimaryAutoUiLanguage("pt-PT"))

        // Hàng 5: fr, fr-FR, fr-CA… -> Français ("fr", values-fr)
        assertEquals("fr", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("fr")))
        assertEquals("fr", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("fr", "FR")))
        assertEquals("fr", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("fr", "CA")))
        assertEquals("fr", AppLanguageManager.resolvePrimaryAutoUiLanguage("fr"))
        assertEquals("fr", AppLanguageManager.resolvePrimaryAutoUiLanguage("fr-FR"))
        assertEquals("fr", AppLanguageManager.resolvePrimaryAutoUiLanguage("fr-CA"))

        // Hàng 6: id, id-ID; alias Android in/in-ID -> Bahasa Indonesia ("id", values-in)
        assertEquals("id", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("id")))
        assertEquals("id", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("id", "ID")))
        assertEquals("id", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("in")))
        assertEquals("id", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("in", "ID")))
        assertEquals("id", AppLanguageManager.resolvePrimaryAutoUiLanguage("id"))
        assertEquals("id", AppLanguageManager.resolvePrimaryAutoUiLanguage("id-ID"))
        assertEquals("id", AppLanguageManager.resolvePrimaryAutoUiLanguage("in"))
        assertEquals("id", AppLanguageManager.resolvePrimaryAutoUiLanguage("in-ID"))
        assertEquals("id", AppLanguageManager.resolvePrimaryAutoUiLanguage("in_ID"))

        // Hàng 7: de, de-DE, de-AT… -> Deutsch ("de", values-de)
        assertEquals("de", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("de")))
        assertEquals("de", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("de", "DE")))
        assertEquals("de", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("de", "AT")))
        assertEquals("de", AppLanguageManager.resolvePrimaryAutoUiLanguage("de"))
        assertEquals("de", AppLanguageManager.resolvePrimaryAutoUiLanguage("de-DE"))
        assertEquals("de", AppLanguageManager.resolvePrimaryAutoUiLanguage("de-AT"))

        // Hàng 8: ja, ja-JP… -> 日本語 ("ja", values-ja)
        assertEquals("ja", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("ja")))
        assertEquals("ja", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("ja", "JP")))
        assertEquals("ja", AppLanguageManager.resolvePrimaryAutoUiLanguage("ja"))
        assertEquals("ja", AppLanguageManager.resolvePrimaryAutoUiLanguage("ja-JP"))

        // Hàng 9: ko, zh, ar, th, ru và mọi ngôn ngữ khác -> English ("en", values)
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("ko")))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("ko", "KR")))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("zh")))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("zh", "CN")))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("zh", "TW")))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale.forLanguageTag("zh-Hans")))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale.forLanguageTag("zh-Hant")))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("ar")))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("ar", "SA")))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("th")))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("th", "TH")))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("ru")))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("ru", "RU")))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("it")))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("nl")))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale("hi")))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage("ko-KR"))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage("zh-CN"))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage("zh-Hans"))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage("ar-SA"))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage("th-TH"))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage("ru-RU"))

        // Hàng 10: Không đọc được locale hợp lệ / rỗng / null / und -> English ("en", values)
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(null as Locale?))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(null as String?))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(""))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage("   "))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage("und"))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage("invalid_tag_123"))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(Locale.forLanguageTag("und")))
    }

    @Test
    fun testResolvePrimaryAutoUiLanguage_primaryOnlyNeverChecksSecondary() {
        // 1. [ko-KR, vi-VN]:
        // Máy có ngôn ngữ chính là ko (ngoài 8 nhóm), ngôn ngữ thứ hai là vi (thuộc 8 nhóm).
        // Nếu duyệt ngôn ngữ thứ hai: sẽ chọn "vi".
        // Hợp đồng bắt buộc E01: Chỉ xét ngôn ngữ chính (ko -> ngoài 8 nhóm) -> fallback về "en", KHÔNG tìm "vi"!
        val listKoVi = listOf(Locale("ko", "KR"), Locale("vi", "VN"))
        val secondaryCandidate = listKoVi.firstOrNull { AppLanguageManager.SUPPORTED_AUTO_UI_TAGS.contains(it.language) }
        assertEquals("vi", secondaryCandidate?.language) // Chứng minh ngôn ngữ thứ hai có trong 8 nhóm
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(listKoVi)) // Nhưng resolver bắt buộc trả về "en"

        // 2. [ru, fr]:
        // Code cũ (37 ngôn ngữ) duyệt tìm ngôn ngữ hỗ trợ đầu tiên: ru không hỗ trợ nhưng fr có hỗ trợ -> ra "fr".
        // Hợp đồng mới E01: ru không thuộc 8 nhóm -> fallback về "en", KHÔNG tìm "fr"!
        val listRuFr = listOf(Locale("ru"), Locale("fr"))
        assertEquals("fr", AppLanguageManager.findFirstSupportedLocale(listRuFr)) // Chứng minh code cũ duyệt ngôn ngữ thứ 2
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(listRuFr)) // Hợp đồng mới E01 bắt buộc trả về "en"

        // 3. [fr-CA, en-US]:
        // Ngôn ngữ chính là fr-CA (thuộc nhóm fr) -> "fr"
        val listFrEn = listOf(Locale("fr", "CA"), Locale("en", "US"))
        assertEquals("fr", AppLanguageManager.resolvePrimaryAutoUiLanguage(listFrEn))

        // 4. [zh-CN, vi]:
        // zh-CN không thuộc 8 nhóm UI -> fallback "en", không tìm "vi" (dù vi thuộc 8 nhóm)
        val listZhVi = listOf(Locale("zh", "CN"), Locale("vi"))
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(listZhVi))

        // 5. [pt-PT, en]:
        // pt-PT thuộc nhóm pt -> "pt"
        val listPtEn = listOf(Locale("pt", "PT"), Locale("en"))
        assertEquals("pt", AppLanguageManager.resolvePrimaryAutoUiLanguage(listPtEn))

        // 6. [in-ID, vi]:
        // in-ID chuẩn hóa sang id -> "id"
        val listInVi = listOf(Locale("in", "ID"), Locale("vi"))
        assertEquals("id", AppLanguageManager.resolvePrimaryAutoUiLanguage(listInVi))

        // 7. [es-MX, ja]:
        // es-MX thuộc nhóm es -> "es"
        val listEsJa = listOf(Locale("es", "MX"), Locale("ja"))
        assertEquals("es", AppLanguageManager.resolvePrimaryAutoUiLanguage(listEsJa))

        // 8. [de-AT, fr]:
        // de-AT thuộc nhóm de -> "de"
        val listDeFr = listOf(Locale("de", "AT"), Locale("fr"))
        assertEquals("de", AppLanguageManager.resolvePrimaryAutoUiLanguage(listDeFr))

        // 9. [ja-JP, en]:
        // ja-JP thuộc nhóm ja -> "ja"
        val listJaEn = listOf(Locale("ja", "JP"), Locale("en"))
        assertEquals("ja", AppLanguageManager.resolvePrimaryAutoUiLanguage(listJaEn))

        // 10. Danh sách rỗng: fallback "en"
        assertEquals("en", AppLanguageManager.resolvePrimaryAutoUiLanguage(emptyList()))
    }

    @Test
    fun testResolvePrimaryAutoUiLanguage_alwaysReturnsOneOfEightTags() {
        val testLocales = listOf(
            Locale("en"), Locale("vi"), Locale("es"), Locale("pt"),
            Locale("fr"), Locale("id"), Locale("de"), Locale("ja"),
            Locale("ko"), Locale("zh"), Locale("ar"), Locale("th"),
            Locale("ru"), Locale("hi"), Locale("it"), Locale("nl"),
            Locale("pl"), Locale("tr"), Locale("ms"), Locale("fil"),
            Locale("cs"), Locale("da"), Locale("sv"), Locale("nb"),
            Locale("fi"), Locale("hu"), Locale("ro"), Locale("hr"),
            Locale("sk"), Locale("sl"), Locale("sq"), Locale("ca"),
            Locale("et"), Locale("lv"), Locale("lt"), Locale("is"),
            Locale("af"), Locale("sr"), Locale("he"), Locale("fa"),
            Locale.forLanguageTag("und"), Locale.forLanguageTag("")
        )

        for (loc in testLocales) {
            val result = AppLanguageManager.resolvePrimaryAutoUiLanguage(loc)
            assertTrue(
                "Kết quả phân giải cho ${loc.toLanguageTag()} ($result) phải nằm trong 8 tag chuẩn",
                AppLanguageManager.SUPPORTED_AUTO_UI_TAGS.contains(result)
            )
        }
    }

    @Test
    fun testIsSupportedAutoUiLanguage_and_getAutoUiLanguage() {
        for (tag in listOf("en", "vi", "es", "pt", "fr", "id", "de", "ja")) {
            assertTrue(AppLanguageManager.isSupportedAutoUiLanguage(tag))
            assertNotNull(AppLanguageManager.getAutoUiLanguage(tag))
            assertEquals(tag, AppLanguageManager.getAutoUiLanguage(tag)?.code)
        }

        // Alias "in" cho "id"
        assertTrue(AppLanguageManager.isSupportedAutoUiLanguage("in"))
        assertEquals("id", AppLanguageManager.getAutoUiLanguage("in")?.code)

        // Các ngôn ngữ ngoài 8 nhóm
        assertFalse(AppLanguageManager.isSupportedAutoUiLanguage("ko"))
        assertFalse(AppLanguageManager.isSupportedAutoUiLanguage("zh"))
        assertFalse(AppLanguageManager.isSupportedAutoUiLanguage("ar"))
        assertFalse(AppLanguageManager.isSupportedAutoUiLanguage("ru"))
        assertFalse(AppLanguageManager.isSupportedAutoUiLanguage("it"))
        assertFalse(AppLanguageManager.isSupportedAutoUiLanguage(null))
        assertFalse(AppLanguageManager.isSupportedAutoUiLanguage(""))

        assertNull(AppLanguageManager.getAutoUiLanguage("ko"))
        assertNull(AppLanguageManager.getAutoUiLanguage(null))
    }

    // ========================================================================
    // KIỂM THỬ GÓI E03 — TỰ ĐỘNG THEO MÁY, CHUYỂN ĐỔI NGƯỜI DÙNG CŨ
    // ========================================================================

    @Test
    fun testDetermineAutoSystemEnforcementStep_cleanInstall() {
        // 1. Cài mới trên API 33+ (chưa có framework locale, chưa có preference cũ):
        val (stepApi33, tagApi33) = AppLanguageManager.determineAutoSystemEnforcementStep(
            targetAutoTag = "vi",
            currentAppliedTag = null,
            legacySavedLang = null,
            isApi33Plus = true,
            isActivityContext = false
        )
        assertEquals(AppLanguageManager.AutoSystemEnforcementStep.APPLY_VIA_LOCALE_MANAGER, stepApi33)
        assertEquals("vi", tagApi33)

        // 2. Cài mới trên API < 33 từ Application context (chưa có Activity):
        val (stepApp, tagApp) = AppLanguageManager.determineAutoSystemEnforcementStep(
            targetAutoTag = "ja",
            currentAppliedTag = null,
            legacySavedLang = null,
            isApi33Plus = false,
            isActivityContext = false
        )
        assertEquals(AppLanguageManager.AutoSystemEnforcementStep.DEFER_TO_ACTIVITY, stepApp)
        assertEquals("ja", tagApp)

        // 3. Cài mới trên API < 33 từ Activity context:
        val (stepAct, tagAct) = AppLanguageManager.determineAutoSystemEnforcementStep(
            targetAutoTag = "ja",
            currentAppliedTag = null,
            legacySavedLang = null,
            isApi33Plus = false,
            isActivityContext = true
        )
        assertEquals(AppLanguageManager.AutoSystemEnforcementStep.APPLY_VIA_APPCOMPAT, stepAct)
        assertEquals("ja", tagAct)
    }

    @Test
    fun testDetermineAutoSystemEnforcementStep_upgradeFromLegacyManualSelections() {
        // Hợp đồng E03: Bỏ hoàn toàn mọi lựa chọn UI thủ công cũ (kể cả ngôn ngữ vẫn còn hỗ trợ).
        // Ví dụ: Người dùng cũ từng chọn "vi", nhưng máy đặt "ja" -> UI phải chuyển sang "ja"!
        val legacySelections = listOf("vi", "en", "es", "fr", "de", "pt", "id", "ja", "ko", "zh-Hans", "ru", "ar", "th", "system")

        for (legacy in legacySelections) {
            // Trường hợp A: Máy là tiếng Nhật ("ja") -> Target "ja"
            val (stepJa, tagJa) = AppLanguageManager.determineAutoSystemEnforcementStep(
                targetAutoTag = "ja",
                currentAppliedTag = if (legacy == "system") null else legacy,
                legacySavedLang = legacy,
                isApi33Plus = true,
                isActivityContext = false
            )
            if (legacy == "ja") {
                // Nếu framework đã là "ja" sẵn -> chỉ cần dọn dẹp preference
                assertEquals(AppLanguageManager.AutoSystemEnforcementStep.CLEANUP_LEGACY_PREFS_ONLY, stepJa)
            } else {
                // Nếu khác "ja" -> phải ghi đè sang "ja"
                assertEquals(AppLanguageManager.AutoSystemEnforcementStep.APPLY_VIA_LOCALE_MANAGER, stepJa)
            }
            assertEquals("ja", tagJa)

            // Trường hợp B: Máy là tiếng Việt ("vi") -> Target "vi"
            val (stepVi, tagVi) = AppLanguageManager.determineAutoSystemEnforcementStep(
                targetAutoTag = "vi",
                currentAppliedTag = if (legacy == "system") null else legacy,
                legacySavedLang = legacy,
                isApi33Plus = false,
                isActivityContext = true
            )
            if (legacy == "vi") {
                assertEquals(AppLanguageManager.AutoSystemEnforcementStep.CLEANUP_LEGACY_PREFS_ONLY, stepVi)
            } else {
                assertEquals(AppLanguageManager.AutoSystemEnforcementStep.APPLY_VIA_APPCOMPAT, stepVi)
            }
            assertEquals("vi", tagVi)
        }
    }

    @Test
    fun testDetermineAutoSystemEnforcementStep_cleanupLegacyPrefsWhenAlreadyInSync() {
        // Framework đã có đúng "vi", nhưng SharedPreferences vẫn còn lưu "vi" từ phiên bản cũ:
        val (step, tag) = AppLanguageManager.determineAutoSystemEnforcementStep(
            targetAutoTag = "vi",
            currentAppliedTag = "vi",
            legacySavedLang = "vi",
            isApi33Plus = true,
            isActivityContext = false
        )
        assertEquals(AppLanguageManager.AutoSystemEnforcementStep.CLEANUP_LEGACY_PREFS_ONLY, step)
        assertEquals("vi", tag)
    }

    @Test
    fun testDetermineAutoSystemEnforcementStep_noRecreateLoopWhenInSync() {
        // Hợp đồng E03: Không gọi setter mỗi lần resume nếu locale đã khớp; chống vòng lặp recreate.
        for (supportedTag in AppLanguageManager.SUPPORTED_AUTO_UI_TAGS) {
            val (stepApi33, tagApi33) = AppLanguageManager.determineAutoSystemEnforcementStep(
                targetAutoTag = supportedTag,
                currentAppliedTag = supportedTag,
                legacySavedLang = null,
                isApi33Plus = true,
                isActivityContext = true
            )
            assertEquals("Khi đã đồng bộ trên API 33+, phải trả về ALREADY_IN_SYNC cho $supportedTag",
                AppLanguageManager.AutoSystemEnforcementStep.ALREADY_IN_SYNC, stepApi33)
            assertEquals(supportedTag, tagApi33)

            val (stepApiBelow, tagApiBelow) = AppLanguageManager.determineAutoSystemEnforcementStep(
                targetAutoTag = supportedTag,
                currentAppliedTag = supportedTag,
                legacySavedLang = null,
                isApi33Plus = false,
                isActivityContext = true
            )
            assertEquals("Khi đã đồng bộ trên API < 33, phải trả về ALREADY_IN_SYNC cho $supportedTag",
                AppLanguageManager.AutoSystemEnforcementStep.ALREADY_IN_SYNC, stepApiBelow)
            assertEquals(supportedTag, tagApiBelow)
        }
    }

    @Test
    fun testDetermineAutoSystemEnforcementStep_deviceLanguageChangedInBackground() {
        // Người dùng đổi ngôn ngữ máy từ Cài đặt Android lúc app đang ở nền:
        // 1. Máy đổi từ Đức ("de") sang Pháp ("fr"):
        val (stepFr, tagFr) = AppLanguageManager.determineAutoSystemEnforcementStep(
            targetAutoTag = "fr",
            currentAppliedTag = "de",
            legacySavedLang = null,
            isApi33Plus = true,
            isActivityContext = true
        )
        assertEquals(AppLanguageManager.AutoSystemEnforcementStep.APPLY_VIA_LOCALE_MANAGER, stepFr)
        assertEquals("fr", tagFr)

        // 2. Máy đổi từ Tây Ban Nha ("es") sang Nhật ("ja") trên API < 33:
        val (stepJa, tagJa) = AppLanguageManager.determineAutoSystemEnforcementStep(
            targetAutoTag = "ja",
            currentAppliedTag = "es",
            legacySavedLang = null,
            isApi33Plus = false,
            isActivityContext = true
        )
        assertEquals(AppLanguageManager.AutoSystemEnforcementStep.APPLY_VIA_APPCOMPAT, stepJa)
        assertEquals("ja", tagJa)
    }

    @Test
    fun testDetermineAutoSystemEnforcementStep_unsupportedSystemPrimaryFallbackToEnglish() {
        // Thiết bị có ngôn ngữ chính ngoài 8 nhóm (ko, zh, ru, th, ar):
        val unsupportedPrimaries = listOf("ko", "zh-CN", "ru", "th", "ar", "he", "fa")

        for (primary in unsupportedPrimaries) {
            val resolvedTarget = AppLanguageManager.resolvePrimaryAutoUiLanguage(primary)
            assertEquals("Ngôn ngữ ngoài 8 nhóm ($primary) phải phân giải về en", "en", resolvedTarget)

            val (step, tag) = AppLanguageManager.determineAutoSystemEnforcementStep(
                targetAutoTag = resolvedTarget,
                currentAppliedTag = "vi", // Đang áp dụng tiếng Việt cũ
                legacySavedLang = "vi",
                isApi33Plus = true,
                isActivityContext = true
            )
            assertEquals(AppLanguageManager.AutoSystemEnforcementStep.APPLY_VIA_LOCALE_MANAGER, step)
            assertEquals("en", tag)
        }
    }

    @Test
    fun testAutoSystemConstantsAndEnforcedFlags() {
        assertEquals("key_auto_system_enforced_v3", AppLanguageManager.KEY_AUTO_SYSTEM_ENFORCED)
        assertEquals("en", AppLanguageManager.DEFAULT_AUTO_UI_LANGUAGE)
    }

    // ========================================================================
    // KIỂM THỬ GÓI U01 — SYSTEM / MANUAL ĐỘC LẬP & MIGRATION V4
    // ========================================================================

    @Test
    fun testEvaluateUiMigration_unmigratedDefaultsToSystem() {
        // Cài mới hoặc nâng cấp từ phiên bản cũ (chưa migrate v4)
        val legacySelections = listOf(null, "system", "vi", "en", "es", "pt", "fr", "id", "de", "ja", "ko", "zh-CN", "ru")
        for (legacy in legacySelections) {
            val eval = AppLanguageManager.evaluateUiMigration(
                isMigratedV4 = false,
                savedMode = null,
                savedManualTag = null,
                legacySelectedLang = legacy
            )
            assertTrue("Chưa migrate v4 phải kích hoạt migration", eval.migrated)
            assertEquals("Bản v3 trở về trước luôn ép auto -> lên v4 phải mặc định chuyển sang SYSTEM",
                UiLanguageMode.SYSTEM, eval.mode)
            assertNull("Chế độ SYSTEM không có manualTag", eval.manualTag)
        }
    }

    @Test
    fun testEvaluateUiMigration_migratedV4PreservesValidManualAndSanitizesInvalid() {
        // Đã migrate v4: giữ nguyên manual hợp lệ
        val validTags = listOf("en", "vi", "es", "pt", "fr", "id", "de", "ja")
        for (tag in validTags) {
            val eval = AppLanguageManager.evaluateUiMigration(
                isMigratedV4 = true,
                savedMode = UiLanguageMode.MANUAL.name,
                savedManualTag = tag
            )
            assertFalse(eval.migrated)
            assertEquals(UiLanguageMode.MANUAL, eval.mode)
            assertEquals(tag, eval.manualTag)
        }

        // Đã migrate v4: alias cổ "in-ID" được chuẩn hóa sang "id"
        val evalAlias = AppLanguageManager.evaluateUiMigration(
            isMigratedV4 = true,
            savedMode = UiLanguageMode.MANUAL.name,
            savedManualTag = "in-ID"
        )
        assertFalse(evalAlias.migrated)
        assertEquals(UiLanguageMode.MANUAL, evalAlias.mode)
        assertEquals("id", evalAlias.manualTag)

        // Đã migrate v4: manual tag không hợp lệ/đã bị loại bỏ phải quay về SYSTEM
        val invalidTags = listOf("ko", "zh", "ru", "ar", "th", "", "   ", "und", null)
        for (inv in invalidTags) {
            val evalInv = AppLanguageManager.evaluateUiMigration(
                isMigratedV4 = true,
                savedMode = UiLanguageMode.MANUAL.name,
                savedManualTag = inv
            )
            assertFalse(evalInv.migrated)
            assertEquals("Tag manual không hợp lệ phải fallback về SYSTEM", UiLanguageMode.SYSTEM, evalInv.mode)
            assertNull(evalInv.manualTag)
        }

        // Đã migrate v4: chế độ SYSTEM giữ nguyên SYSTEM
        val evalSystem = AppLanguageManager.evaluateUiMigration(
            isMigratedV4 = true,
            savedMode = UiLanguageMode.SYSTEM.name,
            savedManualTag = null
        )
        assertFalse(evalSystem.migrated)
        assertEquals(UiLanguageMode.SYSTEM, evalSystem.mode)
        assertNull(evalSystem.manualTag)
    }

    @Test
    fun testDeterminePolicyTarget_systemMode_primaryOnlyRule() {
        // Quy tắc primary-only: máy [ko, vi] ở chế độ SYSTEM vẫn dùng Anh
        val koVi = listOf(Locale.forLanguageTag("ko-KR"), Locale.forLanguageTag("vi-VN"))
        val (mode1, target1) = AppLanguageManager.determinePolicyTarget(UiLanguageMode.SYSTEM, null, koVi)
        assertEquals(UiLanguageMode.SYSTEM, mode1)
        assertEquals("Primary ko không thuộc 8 nhóm -> fallback về en (không nhảy sang vi)", "en", target1)

        // Máy [ru, fr] -> fallback về en
        val ruFr = listOf(Locale.forLanguageTag("ru-RU"), Locale.forLanguageTag("fr-FR"))
        val (mode2, target2) = AppLanguageManager.determinePolicyTarget(UiLanguageMode.SYSTEM, null, ruFr)
        assertEquals(UiLanguageMode.SYSTEM, mode2)
        assertEquals("en", target2)

        // Máy có primary thuộc 8 nhóm
        val viEn = listOf(Locale.forLanguageTag("vi-VN"), Locale.forLanguageTag("en-US"))
        assertEquals("vi", AppLanguageManager.determinePolicyTarget(UiLanguageMode.SYSTEM, null, viEn).second)

        val frCa = listOf(Locale.forLanguageTag("fr-CA"), Locale.forLanguageTag("en-US"))
        assertEquals("fr", AppLanguageManager.determinePolicyTarget(UiLanguageMode.SYSTEM, null, frCa).second)

        val jaJp = listOf(Locale.forLanguageTag("ja-JP"))
        assertEquals("ja", AppLanguageManager.determinePolicyTarget(UiLanguageMode.SYSTEM, null, jaJp).second)

        val deDe = listOf(Locale.forLanguageTag("de-DE"))
        assertEquals("de", AppLanguageManager.determinePolicyTarget(UiLanguageMode.SYSTEM, null, deDe).second)

        val ptBr = listOf(Locale.forLanguageTag("pt-BR"))
        assertEquals("pt", AppLanguageManager.determinePolicyTarget(UiLanguageMode.SYSTEM, null, ptBr).second)

        val esMx = listOf(Locale.forLanguageTag("es-MX"))
        assertEquals("es", AppLanguageManager.determinePolicyTarget(UiLanguageMode.SYSTEM, null, esMx).second)

        val idId = listOf(Locale.forLanguageTag("id-ID"))
        assertEquals("id", AppLanguageManager.determinePolicyTarget(UiLanguageMode.SYSTEM, null, idId).second)

        // Danh sách rỗng
        assertEquals("en", AppLanguageManager.determinePolicyTarget(UiLanguageMode.SYSTEM, null, emptyList()).second)
    }

    @Test
    fun testDeterminePolicyTarget_manualMode_independentFromDevice() {
        val koDevice = listOf(Locale.forLanguageTag("ko-KR"))
        val viDevice = listOf(Locale.forLanguageTag("vi-VN"))
        val enDevice = listOf(Locale.forLanguageTag("en-US"))

        // Chọn thủ công "ja": Bất kể máy là ko, vi hay en, target luôn là "ja"
        val (m1, t1) = AppLanguageManager.determinePolicyTarget(UiLanguageMode.MANUAL, "ja", koDevice)
        assertEquals(UiLanguageMode.MANUAL, m1)
        assertEquals("ja", t1)

        val (m2, t2) = AppLanguageManager.determinePolicyTarget(UiLanguageMode.MANUAL, "ja", viDevice)
        assertEquals(UiLanguageMode.MANUAL, m2)
        assertEquals("ja", t2)

        val (m3, t3) = AppLanguageManager.determinePolicyTarget(UiLanguageMode.MANUAL, "ja", enDevice)
        assertEquals(UiLanguageMode.MANUAL, m3)
        assertEquals("ja", t3)

        // Manual tag không hợp lệ ("ko") -> fallback về SYSTEM -> tính theo máy
        val (mInv, tInv) = AppLanguageManager.determinePolicyTarget(UiLanguageMode.MANUAL, "ko", viDevice)
        assertEquals("Manual tag không hợp lệ phải fallback về SYSTEM", UiLanguageMode.SYSTEM, mInv)
        assertEquals("vi", tInv)
    }

    @Test
    fun testDeterminePolicyEnforcementStep_lifecycleAndRecreatePrevention() {
        // Đã khớp ngôn ngữ -> ALREADY_IN_SYNC (chống recreate loop khi resume)
        val (syncStep, _) = AppLanguageManager.determinePolicyEnforcementStep(
            targetTag = "vi",
            currentAppliedTag = "vi",
            isApi33Plus = true,
            isActivityContext = false
        )
        assertEquals(AppLanguageManager.PolicyEnforcementStep.ALREADY_IN_SYNC, syncStep)

        // Khác ngôn ngữ trên API 33+ -> APPLY_VIA_LOCALE_MANAGER
        val (step33, tag33) = AppLanguageManager.determinePolicyEnforcementStep(
            targetTag = "ja",
            currentAppliedTag = "vi",
            isApi33Plus = true,
            isActivityContext = false
        )
        assertEquals(AppLanguageManager.PolicyEnforcementStep.APPLY_VIA_LOCALE_MANAGER, step33)
        assertEquals("ja", tag33)

        // Khác ngôn ngữ trên API < 33 từ Activity context -> APPLY_VIA_APPCOMPAT
        val (stepCompat, tagCompat) = AppLanguageManager.determinePolicyEnforcementStep(
            targetTag = "de",
            currentAppliedTag = "en",
            isApi33Plus = false,
            isActivityContext = true
        )
        assertEquals(AppLanguageManager.PolicyEnforcementStep.APPLY_VIA_APPCOMPAT, stepCompat)
        assertEquals("de", tagCompat)

        // Khác ngôn ngữ trên API < 33 từ Application context -> DEFER_TO_ACTIVITY
        val (stepDefer, tagDefer) = AppLanguageManager.determinePolicyEnforcementStep(
            targetTag = "fr",
            currentAppliedTag = null,
            isApi33Plus = false,
            isActivityContext = false
        )
        assertEquals(AppLanguageManager.PolicyEnforcementStep.DEFER_TO_ACTIVITY, stepDefer)
        assertEquals("fr", tagDefer)
    }

    @Test
    fun testMandatoryScenario_koreanDeviceToManualVietnameseToJapaneseDeviceToSystem() {
        // Kịch bản bắt buộc theo thiết kế:
        // Máy Hàn + UI SYSTEM -> Anh; chọn Việt thủ công -> Việt; đổi máy Nhật vẫn Việt; quay SYSTEM -> Nhật.
        // Kiểm tra trên cả API 33+ và API 26-32.

        for (isApi33 in listOf(true, false)) {
            // Bước 1: Máy Hàn [ko-KR], UI chế độ SYSTEM (Theo thiết bị)
            val koreanLocales = listOf(Locale.forLanguageTag("ko-KR"))
            val (mode1, target1) = AppLanguageManager.determinePolicyTarget(UiLanguageMode.SYSTEM, null, koreanLocales)
            assertEquals("Máy Hàn ở chế độ SYSTEM phải phân giải về en", "en", target1)
            assertEquals(UiLanguageMode.SYSTEM, mode1)

            // Áp dụng lần đầu (chưa có applied tag)
            val (step1, applyTag1) = AppLanguageManager.determinePolicyEnforcementStep(
                targetTag = target1,
                currentAppliedTag = null,
                isApi33Plus = isApi33,
                isActivityContext = true
            )
            val expectedStep1 = if (isApi33) {
                AppLanguageManager.PolicyEnforcementStep.APPLY_VIA_LOCALE_MANAGER
            } else {
                AppLanguageManager.PolicyEnforcementStep.APPLY_VIA_APPCOMPAT
            }
            assertEquals(expectedStep1, step1)
            assertEquals("en", applyTag1)

            // Resume với máy Hàn: framework đã là "en", không bị ghi đè hay recreate
            val (stepResume1, _) = AppLanguageManager.determinePolicyEnforcementStep(
                targetTag = target1,
                currentAppliedTag = "en",
                isApi33Plus = isApi33,
                isActivityContext = true
            )
            assertEquals(AppLanguageManager.PolicyEnforcementStep.ALREADY_IN_SYNC, stepResume1)

            // Bước 2: Người dùng chọn thủ công Tiếng Việt ("vi")
            val (mode2, target2) = AppLanguageManager.determinePolicyTarget(UiLanguageMode.MANUAL, "vi", koreanLocales)
            assertEquals(UiLanguageMode.MANUAL, mode2)
            assertEquals("vi", target2)

            val (step2, applyTag2) = AppLanguageManager.determinePolicyEnforcementStep(
                targetTag = target2,
                currentAppliedTag = "en",
                isApi33Plus = isApi33,
                isActivityContext = true
            )
            assertEquals(expectedStep1, step2)
            assertEquals("vi", applyTag2)

            // Bước 3: Đổi máy sang Tiếng Nhật [ja-JP], Activity resume ở chế độ MANUAL
            val japaneseLocales = listOf(Locale.forLanguageTag("ja-JP"))
            val (mode3, target3) = AppLanguageManager.determinePolicyTarget(UiLanguageMode.MANUAL, "vi", japaneseLocales)
            assertEquals("Chế độ MANUAL phải giữ nguyên Tiếng Việt dù máy đổi sang Nhật", UiLanguageMode.MANUAL, mode3)
            assertEquals("vi", target3)

            // Framework đã là "vi", resume không ghi đè thành "ja"
            val (stepResume3, _) = AppLanguageManager.determinePolicyEnforcementStep(
                targetTag = target3,
                currentAppliedTag = "vi",
                isApi33Plus = isApi33,
                isActivityContext = true
            )
            assertEquals("Khi resume ở chế độ MANUAL, lựa chọn người dùng không bị ghi đè",
                AppLanguageManager.PolicyEnforcementStep.ALREADY_IN_SYNC, stepResume3)

            // Bước 4: Người dùng chuyển từ thủ công về Theo thiết bị (SYSTEM)
            val (mode4, target4) = AppLanguageManager.determinePolicyTarget(UiLanguageMode.SYSTEM, null, japaneseLocales)
            assertEquals(UiLanguageMode.SYSTEM, mode4)
            assertEquals("Quay về SYSTEM phải tính lại theo ngôn ngữ chính của máy (Nhật -> ja)", "ja", target4)

            val (step4, applyTag4) = AppLanguageManager.determinePolicyEnforcementStep(
                targetTag = target4,
                currentAppliedTag = "vi",
                isApi33Plus = isApi33,
                isActivityContext = true
            )
            assertEquals(expectedStep1, step4)
            assertEquals("ja", applyTag4)
        }
    }
}

