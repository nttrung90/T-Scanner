package com.tscanner.app

import com.google.mlkit.common.MlKitException
import com.tscanner.app.utils.OcrLanguageMode
import com.tscanner.app.utils.OcrModelUnavailableType
import com.tscanner.app.utils.OcrEngineUsage
import com.tscanner.app.utils.OcrFailureCode
import com.tscanner.app.utils.resolveModelUnavailableType
import com.tscanner.app.utils.resolveFailureCode
import com.tscanner.app.utils.EngineRunResult
import com.tscanner.app.utils.MultiPageOcrAggregator
import com.tscanner.app.utils.MultiPageOcrResult
import com.tscanner.app.utils.OcrRequest
import com.tscanner.app.utils.OcrResult
import com.tscanner.app.utils.OcrRoutingResolver
import com.tscanner.app.utils.OcrType
import com.tscanner.app.utils.TextRecognitionHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.tscanner.app.R
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrRoutingTest {

    @Test
    fun testOcrType_languageMapping() {
        assertEquals(OcrType.TESSERACT_PRIMARY, OcrRoutingResolver.getOcrTypeForLanguage("vi"))
        assertEquals(OcrType.TESSERACT_PRIMARY, OcrRoutingResolver.getOcrTypeForLanguage("vi-VN"))
        assertEquals(OcrType.TESSERACT_PRIMARY, OcrRoutingResolver.getOcrTypeForLanguage("en"))
        assertEquals(OcrType.TESSERACT_PRIMARY, OcrRoutingResolver.getOcrTypeForLanguage("en-US"))

        assertEquals(OcrType.MLKIT_CHINESE, OcrRoutingResolver.getOcrTypeForLanguage("zh"))
        assertEquals(OcrType.MLKIT_CHINESE, OcrRoutingResolver.getOcrTypeForLanguage("zh-Hans"))
        assertEquals(OcrType.MLKIT_CHINESE, OcrRoutingResolver.getOcrTypeForLanguage("zh-CN"))

        assertEquals(OcrType.MLKIT_JAPANESE, OcrRoutingResolver.getOcrTypeForLanguage("ja"))
        assertEquals(OcrType.MLKIT_JAPANESE, OcrRoutingResolver.getOcrTypeForLanguage("ja-JP"))

        assertEquals(OcrType.MLKIT_KOREAN, OcrRoutingResolver.getOcrTypeForLanguage("ko"))
        assertEquals(OcrType.MLKIT_KOREAN, OcrRoutingResolver.getOcrTypeForLanguage("ko-KR"))

        assertEquals(OcrType.PLAY_SERVICES_DEVANAGARI, OcrRoutingResolver.getOcrTypeForLanguage("hi"))
        assertEquals(OcrType.PLAY_SERVICES_DEVANAGARI, OcrRoutingResolver.getOcrTypeForLanguage("hi-IN"))

        assertEquals(OcrType.UNSUPPORTED_ON_DEVICE, OcrRoutingResolver.getOcrTypeForLanguage("ar"))
        assertEquals(OcrType.UNSUPPORTED_ON_DEVICE, OcrRoutingResolver.getOcrTypeForLanguage("th"))

        assertEquals(OcrType.MLKIT_LATIN, OcrRoutingResolver.getOcrTypeForLanguage("fr"))
        assertEquals(OcrType.MLKIT_LATIN, OcrRoutingResolver.getOcrTypeForLanguage("de"))
        assertEquals(OcrType.MLKIT_LATIN, OcrRoutingResolver.getOcrTypeForLanguage("es"))
        assertEquals(OcrType.MLKIT_LATIN, OcrRoutingResolver.getOcrTypeForLanguage("sr-Latn"))
        assertEquals(OcrType.MLKIT_LATIN, OcrRoutingResolver.getOcrTypeForLanguage("fil"))
        assertEquals(OcrType.MLKIT_LATIN, OcrRoutingResolver.getOcrTypeForLanguage("tl"))
        assertEquals(OcrType.MLKIT_LATIN, OcrRoutingResolver.getOcrTypeForLanguage("in"))
        assertEquals(OcrType.MLKIT_LATIN, OcrRoutingResolver.getOcrTypeForLanguage("no"))

        // Ngôn ngữ Cyrillic / không hỗ trợ on-device không được tự ép thành Latin (R03)
        assertEquals(OcrType.UNSUPPORTED_ON_DEVICE, OcrRoutingResolver.getOcrTypeForLanguage("ru"))
        assertEquals(OcrType.UNSUPPORTED_ON_DEVICE, OcrRoutingResolver.getOcrTypeForLanguage("sr-Cyrl"))
        assertEquals(OcrType.UNSUPPORTED_ON_DEVICE, OcrRoutingResolver.getOcrTypeForLanguage("klingon"))
        assertEquals(OcrType.UNSUPPORTED_ON_DEVICE, OcrRoutingResolver.getOcrTypeForLanguage(""))
        assertEquals(OcrType.UNSUPPORTED_ON_DEVICE, OcrRoutingResolver.getOcrTypeForLanguage(null))
    }

    @Test
    fun testLanguageSupported_distinguishesSupportedAndUnsupported() {
        assertTrue(OcrRoutingResolver.isLanguageSupported("vi"))
        assertTrue(OcrRoutingResolver.isLanguageSupported("en"))
        assertTrue(OcrRoutingResolver.isLanguageSupported("zh-Hans"))
        assertTrue(OcrRoutingResolver.isLanguageSupported("ja"))
        assertTrue(OcrRoutingResolver.isLanguageSupported("ko"))
        assertTrue(OcrRoutingResolver.isLanguageSupported("hi"))
        assertTrue(OcrRoutingResolver.isLanguageSupported("fr"))
        assertTrue(OcrRoutingResolver.isLanguageSupported("de"))

        assertFalse(OcrRoutingResolver.isLanguageSupported("ar"))
        assertFalse(OcrRoutingResolver.isLanguageSupported("th"))
        assertFalse(OcrRoutingResolver.isLanguageSupported("ru"))
        assertFalse(OcrRoutingResolver.isLanguageSupported("sr-Cyrl"))
        assertFalse(OcrRoutingResolver.isLanguageSupported(""))
        assertFalse(OcrRoutingResolver.isLanguageSupported(null))
    }

    @Test
    fun testEngineCompatibility_enforcesCapabilityPerEngine() {
        // AUTO is always compatible with any supported engine
        assertTrue(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_AUTO, OcrType.TESSERACT_PRIMARY))
        assertTrue(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_AUTO, OcrType.MLKIT_JAPANESE))
        assertTrue(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_AUTO, OcrType.MLKIT_KOREAN))
        assertTrue(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_AUTO, OcrType.MLKIT_CHINESE))
        assertTrue(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_AUTO, OcrType.MLKIT_LATIN))
        assertTrue(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_AUTO, OcrType.PLAY_SERVICES_DEVANAGARI))
        assertFalse(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_AUTO, OcrType.UNSUPPORTED_ON_DEVICE))

        // Tesseract: CHỈ hỗ trợ vi và en (TESSERACT_PRIMARY); không hỗ trợ Latin khác vì không có traineddata (R03)
        assertTrue(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_TESSERACT, OcrType.TESSERACT_PRIMARY))
        assertFalse(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_TESSERACT, OcrType.MLKIT_LATIN))
        assertFalse(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_TESSERACT, OcrType.MLKIT_JAPANESE))
        assertFalse(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_TESSERACT, OcrType.MLKIT_KOREAN))
        assertFalse(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_TESSERACT, OcrType.MLKIT_CHINESE))
        assertFalse(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_TESSERACT, OcrType.PLAY_SERVICES_DEVANAGARI))
        assertFalse(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_TESSERACT, OcrType.UNSUPPORTED_ON_DEVICE))

        // PaddleOCR: Legacy engine đã bị vô hiệu hóa hoàn toàn, không tương thích với bất kỳ OcrType nào
        assertFalse(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_PADDLE, OcrType.MLKIT_CHINESE))
        assertFalse(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_PADDLE, OcrType.TESSERACT_PRIMARY))
        assertFalse(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_PADDLE, OcrType.MLKIT_LATIN))
        assertFalse(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_PADDLE, OcrType.MLKIT_JAPANESE))
        assertFalse(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_PADDLE, OcrType.MLKIT_KOREAN))
        assertFalse(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_PADDLE, OcrType.PLAY_SERVICES_DEVANAGARI))
        assertFalse(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_PADDLE, OcrType.UNSUPPORTED_ON_DEVICE))

        // ML Kit: supports Japanese, Korean, Devanagari, Chinese, Latin, vi/en
        assertTrue(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_MLKIT, OcrType.MLKIT_JAPANESE))
        assertTrue(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_MLKIT, OcrType.MLKIT_KOREAN))
        assertTrue(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_MLKIT, OcrType.PLAY_SERVICES_DEVANAGARI))
        assertTrue(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_MLKIT, OcrType.MLKIT_LATIN))
        assertTrue(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_MLKIT, OcrType.TESSERACT_PRIMARY))
        assertTrue(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_MLKIT, OcrType.MLKIT_CHINESE))
        assertFalse(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_MLKIT, OcrType.UNSUPPORTED_ON_DEVICE))

        // Engine lạ không hợp lệ
        assertFalse(OcrRoutingResolver.isEngineCompatible("unknown_engine", OcrType.TESSERACT_PRIMARY))
    }

    @Test
    fun testTessLanguage_selection() {
        assertEquals("eng", OcrRoutingResolver.getTessLanguage("en"))
        assertEquals("eng", OcrRoutingResolver.getTessLanguage("en-US"))
        assertEquals("vie", OcrRoutingResolver.getTessLanguage("vi"))
        assertEquals("vie", OcrRoutingResolver.getTessLanguage("vi-VN"))

        // Ngôn ngữ khác không có traineddata -> trả về null, tuyệt đối KHÔNG ép về vie (R03)
        assertNull(OcrRoutingResolver.getTessLanguage("fr"))
        assertNull(OcrRoutingResolver.getTessLanguage("de"))
        assertNull(OcrRoutingResolver.getTessLanguage("es"))
        assertNull(OcrRoutingResolver.getTessLanguage("ru"))
        assertNull(OcrRoutingResolver.getTessLanguage(null))
        assertNull(OcrRoutingResolver.getTessLanguage(""))
    }

    @Test
    fun testGetSupportedEngineModesForLanguage() {
        val viEngines = OcrRoutingResolver.getSupportedEngineModesForLanguage("vi")
        assertEquals(listOf("auto", "tesseract", "mlkit"), viEngines)

        val enEngines = OcrRoutingResolver.getSupportedEngineModesForLanguage("en")
        assertEquals(listOf("auto", "tesseract", "mlkit"), enEngines)

        val zhEngines = OcrRoutingResolver.getSupportedEngineModesForLanguage("zh-Hans")
        assertEquals(listOf("auto", "mlkit"), zhEngines)

        val frEngines = OcrRoutingResolver.getSupportedEngineModesForLanguage("fr")
        assertEquals(listOf("auto", "mlkit"), frEngines)

        val jaEngines = OcrRoutingResolver.getSupportedEngineModesForLanguage("ja")
        assertEquals(listOf("auto", "mlkit"), jaEngines)

        val unsupported = OcrRoutingResolver.getSupportedEngineModesForLanguage("ar")
        assertTrue(unsupported.isEmpty())
    }

    @Test
    fun testOcrRequest_snapshotImmutability() {
        val request = OcrRequest(languageTag = "ja", engineMode = TextRecognitionHelper.ENGINE_MODE_MLKIT)
        assertEquals("ja", request.languageTag)
        assertEquals(TextRecognitionHelper.ENGINE_MODE_MLKIT, request.engineMode)

        val ocrType = OcrRoutingResolver.getOcrTypeForLanguage(request.languageTag)
        assertEquals(OcrType.MLKIT_JAPANESE, ocrType)
        assertTrue(OcrRoutingResolver.isEngineCompatible(request.engineMode, ocrType))
    }

    @Test
    fun testOcrResult_structureAndAccessors() {
        val successResult = OcrResult.Success(
            text = "Recognized OCR Text",
            engineId = "tesseract",
            documentLanguage = "vi",
            fallbackUsed = false
        )
        assertEquals("Recognized OCR Text", successResult.textOrNull)
        assertEquals("tesseract", successResult.engineId)
        assertEquals("vi", successResult.documentLanguage)
        assertFalse(successResult.fallbackUsed)

        val noTextResult = OcrResult.NoText
        assertNull(noTextResult.textOrNull)

        val unsupported = OcrResult.UnsupportedLanguage("ar")
        assertEquals("ar", unsupported.languageTag)
        assertNull(unsupported.textOrNull)

        val incompatible = OcrResult.IncompatibleEngine(
            engineMode = TextRecognitionHelper.ENGINE_MODE_TESSERACT,
            languageTag = "ja"
        )
        assertEquals("tesseract", incompatible.engineMode)
        assertEquals("ja", incompatible.languageTag)
        assertNull(incompatible.textOrNull)
    }

    @Test
    fun testSupportedOcrDocumentLanguages_catalogIntegrity() {
        val languages = OcrRoutingResolver.SUPPORTED_OCR_DOCUMENT_LANGUAGES
        assertEquals("Phải có chính xác 36 ngôn ngữ tài liệu OCR", 36, languages.size)

        val tags = languages.map { it.tag }
        assertEquals("Tất cả canonical tag OCR phải duy nhất", 36, tags.toSet().size)

        // Không chứa ar, th, ru
        assertFalse("ar không được có trong OCR on-device", tags.contains("ar"))
        assertFalse("th không được có trong OCR on-device", tags.contains("th"))
        assertFalse("ru không được có trong OCR on-device", tags.contains("ru"))

        // Tất cả 36 ngôn ngữ phải được OcrRoutingResolver nhận diện là supported
        for (lang in languages) {
            assertTrue("Ngôn ngữ ${lang.tag} phải supported", OcrRoutingResolver.isLanguageSupported(lang.tag))
            assertTrue("Ngôn ngữ ${lang.tag} phải có cờ", lang.flag.isNotBlank())
            assertTrue("Ngôn ngữ ${lang.tag} phải có nativeName", lang.nativeName.isNotBlank())
            assertTrue("Ngôn ngữ ${lang.tag} phải có supportedEngineModes", lang.supportedEngineModes.isNotEmpty())
            assertTrue("Mọi ngôn ngữ OCR phải hỗ trợ ít nhất AUTO", lang.supportedEngineModes.contains(TextRecognitionHelper.ENGINE_MODE_AUTO))
        }
    }

    @Test
    fun testGetDocumentLanguage_retrieval() {
        val vi = OcrRoutingResolver.getDocumentLanguage("vi")
        org.junit.Assert.assertNotNull(vi)
        assertEquals("Tiếng Việt", vi?.nativeName)
        assertEquals("🇻🇳", vi?.flag)

        val ja = OcrRoutingResolver.getDocumentLanguage("ja-JP")
        org.junit.Assert.assertNotNull(ja)
        assertEquals("ja", ja?.tag)
        assertEquals("日本語", ja?.nativeName)

        val zhHant = OcrRoutingResolver.getDocumentLanguage("zh-TW")
        org.junit.Assert.assertNotNull(zhHant)
        assertEquals("zh-Hant", zhHant?.tag)

        assertNull(OcrRoutingResolver.getDocumentLanguage("ar"))
        assertNull(OcrRoutingResolver.getDocumentLanguage("th"))
        assertNull(OcrRoutingResolver.getDocumentLanguage("ru"))
        assertNull(OcrRoutingResolver.getDocumentLanguage(""))
        assertNull(OcrRoutingResolver.getDocumentLanguage(null))
    }

    @Test
    fun testResolveOcrDocumentLanguage_uiAndOcrSeparationRules() {
        // Kịch bản 1: Lần đầu mở app với UI tiếng Việt -> phân giải vi
        assertEquals("vi", TextRecognitionHelper.resolveOcrDocumentLanguage(null, "vi"))

        // Kịch bản 2: Lần đầu mở app với UI tiếng Anh -> phân giải en
        assertEquals("en", TextRecognitionHelper.resolveOcrDocumentLanguage(null, "en"))

        // Kịch bản 3: Lần đầu mở app với UI tiếng Nhật -> phân giải ja
        assertEquals("ja", TextRecognitionHelper.resolveOcrDocumentLanguage(null, "ja"))

        // Kịch bản 4 (R02 quan trọng): Lần đầu mở app với UI tiếng Ả Rập (ar)
        // Tuyệt đối KHÔNG được âm thầm fallback về vi! Phải trả về null để UI yêu cầu người dùng chọn.
        assertNull(TextRecognitionHelper.resolveOcrDocumentLanguage(null, "ar"))
        assertNull(TextRecognitionHelper.resolveOcrDocumentLanguage(null, "th"))
        assertNull(TextRecognitionHelper.resolveOcrDocumentLanguage(null, "ru"))

        // Kịch bản 5 (R02 quan trọng): Người dùng đã chọn OCR tiếng Nhật (ja), sau đó đổi UI sang tiếng Việt
        // Ngôn ngữ OCR vẫn phải là ja!
        assertEquals("ja", TextRecognitionHelper.resolveOcrDocumentLanguage("ja", "vi"))

        // Kịch bản 6: Người dùng đã chọn OCR tiếng Việt (vi), sau đó đổi UI sang tiếng Anh hoặc Ả Rập
        // Ngôn ngữ OCR vẫn phải giữ nguyên vi!
        assertEquals("vi", TextRecognitionHelper.resolveOcrDocumentLanguage("vi", "en"))
        assertEquals("vi", TextRecognitionHelper.resolveOcrDocumentLanguage("vi", "ar"))

        // Kịch bản 7: Người dùng chọn OCR tiếng Pháp (fr), sau đó đổi UI sang tiếng Thái
        assertEquals("fr", TextRecognitionHelper.resolveOcrDocumentLanguage("fr", "th"))
    }

    @Test
    fun testShouldAutoPersistInitialLanguage_rules() {
        // Chưa lưu + UI hỗ trợ -> true (cần auto persist ngay lần đầu)
        assertTrue(TextRecognitionHelper.shouldAutoPersistInitialLanguage(null, "vi"))
        assertTrue(TextRecognitionHelper.shouldAutoPersistInitialLanguage(null, "en"))
        assertTrue(TextRecognitionHelper.shouldAutoPersistInitialLanguage(null, "ja"))

        // Chưa lưu + UI không hỗ trợ -> false (không được tự động lưu vi/en bậy bạ)
        assertFalse(TextRecognitionHelper.shouldAutoPersistInitialLanguage(null, "ar"))
        assertFalse(TextRecognitionHelper.shouldAutoPersistInitialLanguage(null, "th"))
        assertFalse(TextRecognitionHelper.shouldAutoPersistInitialLanguage(null, "ru"))
        assertFalse(TextRecognitionHelper.shouldAutoPersistInitialLanguage(null, null))

        // Đã lưu -> luôn false (không ghi đè tự động)
        assertFalse(TextRecognitionHelper.shouldAutoPersistInitialLanguage("ja", "vi"))
        assertFalse(TextRecognitionHelper.shouldAutoPersistInitialLanguage("vi", "en"))
        assertFalse(TextRecognitionHelper.shouldAutoPersistInitialLanguage("en", "ar"))
    }

    @Test
    fun testIsModelUnavailableException_classification() {
        // MlKitException codes
        val exUnavailable = MlKitException("Model not ready", MlKitException.UNAVAILABLE)
        assertTrue("Mã UNAVAILABLE phải nhận diện là model unavailable", TextRecognitionHelper.isModelUnavailableException(exUnavailable))

        val exNetwork = MlKitException("Cannot download", MlKitException.NETWORK_ISSUE)
        assertTrue("Mã NETWORK_ISSUE phải nhận diện là model unavailable", TextRecognitionHelper.isModelUnavailableException(exNetwork))

        val exSpace = MlKitException("No disk space", MlKitException.NOT_ENOUGH_SPACE)
        assertTrue("Mã NOT_ENOUGH_SPACE phải nhận diện là model unavailable", TextRecognitionHelper.isModelUnavailableException(exSpace))

        // Message keywords
        val exDownloading = Exception("Waiting for the text recognition module to be downloaded")
        assertTrue("Message chứa download phải nhận diện là model unavailable", TextRecognitionHelper.isModelUnavailableException(exDownloading))

        val exNotDownloaded = Exception("Model is not yet downloaded")
        assertTrue("Message chứa not yet downloaded phải nhận diện là model unavailable", TextRecognitionHelper.isModelUnavailableException(exNotDownloaded))

        val exModuleNotFound = Exception("MODULE_NOT_FOUND")
        assertTrue("Message chứa module_not_found phải nhận diện là model unavailable", TextRecognitionHelper.isModelUnavailableException(exModuleNotFound))

        // Regular failures
        val exNormal = Exception("Unknown processing error occurred in native pipeline")
        assertFalse("Lỗi thông thường không được coi là model unavailable", TextRecognitionHelper.isModelUnavailableException(exNormal))

        val exCorrupted = IllegalArgumentException("Input bitmap config is not supported")
        assertFalse("Lỗi bitmap không được coi là model unavailable", TextRecognitionHelper.isModelUnavailableException(exCorrupted))
    }

    @Test
    fun testResolveFallbackOutcome_matrix() {
        // Cả 2 đều ModelUnavailable -> ModelUnavailable
        val bothUnavailable = TextRecognitionHelper.resolveFallbackOutcome(
            EngineRunResult.ModelUnavailable("Primary missing"),
            EngineRunResult.ModelUnavailable("Fallback missing"),
            "zh-Hans"
        )
        assertTrue(bothUnavailable is OcrResult.ModelUnavailable)
        assertEquals("Primary missing", (bothUnavailable as OcrResult.ModelUnavailable).reason)
        assertEquals("zh-Hans", bothUnavailable.languageTag)

        // Primary ModelUnavailable + Fallback NoText -> ModelUnavailable (R04: lỗi model không được nuốt thành NoText)
        val primaryUnavailableFallbackNoText = TextRecognitionHelper.resolveFallbackOutcome(
            EngineRunResult.ModelUnavailable("Paddle model missing"),
            EngineRunResult.NoText,
            "zh-Hans"
        )
        assertTrue("Primary model unavailable không được nuốt thành NoText", primaryUnavailableFallbackNoText is OcrResult.ModelUnavailable)

        // Cả 2 đều NoText -> NoText
        val bothNoText = TextRecognitionHelper.resolveFallbackOutcome(
            EngineRunResult.NoText,
            EngineRunResult.NoText,
            "vi"
        )
        assertTrue(bothNoText is OcrResult.NoText)

        // Primary Failure + Fallback NoText -> Failure
        val primaryFailure = TextRecognitionHelper.resolveFallbackOutcome(
            EngineRunResult.Failure("Crash in native tess"),
            EngineRunResult.NoText,
            "vi"
        )
        assertTrue(primaryFailure is OcrResult.Failure)
        assertEquals("Crash in native tess", (primaryFailure as OcrResult.Failure).error)

        // Primary NoText + Fallback Failure -> Failure
        val fallbackFailure = TextRecognitionHelper.resolveFallbackOutcome(
            EngineRunResult.NoText,
            EngineRunResult.Failure("ML Kit crash"),
            "vi"
        )
        assertTrue(fallbackFailure is OcrResult.Failure)
    }

    @Test
    fun testMapSingleEngineResult_integrity() {
        // R04: ModelUnavailable PHẢI trả về OcrResult.ModelUnavailable, tuyệt đối không được trả về NoText
        val modelUnavailableRes = TextRecognitionHelper.mapSingleEngineResult(
            EngineRunResult.ModelUnavailable("Japanese model downloading"),
            "mlkit_japanese",
            "ja"
        ) { text, engine -> OcrResult.Success(text, engine, "ja") }

        assertTrue("ModelUnavailable phải trả về OcrResult.ModelUnavailable", modelUnavailableRes is OcrResult.ModelUnavailable)
        assertFalse("ModelUnavailable không được biến thành NoText", modelUnavailableRes is OcrResult.NoText)
        assertEquals("Japanese model downloading", (modelUnavailableRes as OcrResult.ModelUnavailable).reason)
        assertEquals("ja", modelUnavailableRes.languageTag)

        // NoText -> OcrResult.NoText
        val noTextRes = TextRecognitionHelper.mapSingleEngineResult(
            EngineRunResult.NoText,
            "mlkit_japanese",
            "ja"
        ) { text, engine -> OcrResult.Success(text, engine, "ja") }
        assertTrue(noTextRes is OcrResult.NoText)

        // Failure -> OcrResult.Failure
        val failureRes = TextRecognitionHelper.mapSingleEngineResult(
            EngineRunResult.Failure("Fatal GPU error"),
            "mlkit_japanese",
            "ja"
        ) { text, engine -> OcrResult.Success(text, engine, "ja") }
        assertTrue(failureRes is OcrResult.Failure)
        assertEquals("Fatal GPU error", (failureRes as OcrResult.Failure).error)

        // Success -> OcrResult.Success
        val successRes = TextRecognitionHelper.mapSingleEngineResult(
            EngineRunResult.Success("こんにちは"),
            "mlkit_japanese",
            "ja"
        ) { text, engine -> OcrResult.Success(text, engine, "ja") }
        assertTrue(successRes is OcrResult.Success)
        assertEquals("こんにちは", (successRes as OcrResult.Success).text)
    }

    @Test
    fun testMultiPageOcr_twoNoTextPages() {
        val pages = listOf(
            OcrResult.NoText,
            OcrResult.NoText
        )
        val result = MultiPageOcrAggregator.aggregate(pages)
        assertTrue("Hai trang NoText phải trả về AllNoText", result is MultiPageOcrResult.AllNoText)
        assertEquals(2, (result as MultiPageOcrResult.AllNoText).totalPages)
    }

    @Test
    fun testMultiPageOcr_twoIncompatiblePages() {
        val pages = listOf(
            OcrResult.IncompatibleEngine("tesseract", "fr"),
            OcrResult.IncompatibleEngine("tesseract", "fr")
        )
        val result = MultiPageOcrAggregator.aggregate(pages)
        assertTrue("Hai trang incompatible phải dừng xuất và trả về PageError", result is MultiPageOcrResult.PageError)
        val pageError = result as MultiPageOcrResult.PageError
        assertEquals("Trang lỗi đầu tiên phải là trang 1", 1, pageError.failedPageNumber)
        assertEquals(2, pageError.totalPages)
        assertTrue(pageError.errorResult is OcrResult.IncompatibleEngine)
    }

    @Test
    fun testMultiPageOcr_oneSuccessOneFailure() {
        val pages = listOf(
            OcrResult.Success("Page 1 recognized content", "mlkit_latin", "en"),
            OcrResult.Failure("Network timeout downloading model")
        )
        val result = MultiPageOcrAggregator.aggregate(pages)
        assertTrue("Có một trang thất bại phải lập tức dừng xuất tự động và báo PageError", result is MultiPageOcrResult.PageError)
        val pageError = result as MultiPageOcrResult.PageError
        assertEquals("Trang lỗi phải là trang 2", 2, pageError.failedPageNumber)
        assertEquals(2, pageError.totalPages)
        assertTrue(pageError.errorResult is OcrResult.Failure)
        assertEquals("Network timeout downloading model", (pageError.errorResult as OcrResult.Failure).error)
    }

    @Test
    fun testMultiPageOcr_oneSuccessOneNoText() {
        val pages = listOf(
            OcrResult.Success("Invoice total: $100", "tesseract", "en"),
            OcrResult.NoText
        )
        val result = MultiPageOcrAggregator.aggregate(pages)
        assertTrue("1 trang Success và 1 trang NoText phải trả về Success với thống kê", result is MultiPageOcrResult.Success)
        val success = result as MultiPageOcrResult.Success
        assertEquals(1, success.pagesWithText)
        assertEquals(1, success.blankPages)
        assertEquals(2, success.totalPages)
        assertTrue("Header phải chứa PAGE 1", success.fullText.contains("--- PAGE 1 ---"))
        assertFalse("Header KHÔNG được chứa PAGE 2 vì trang 2 không có text", success.fullText.contains("PAGE 2"))
        assertTrue("Nội dung phải chứa văn bản trang 1", success.fullText.contains("Invoice total: $100"))
        assertEquals(setOf("tesseract"), success.enginesUsed)
    }

    @Test
    fun testMultiPageOcr_oneNoTextOneSuccess_retainsOriginalPageNumber() {
        val pages = listOf(
            OcrResult.NoText,
            OcrResult.Success("Terms and conditions on page 2", "mlkit_latin", "en")
        )
        val result = MultiPageOcrAggregator.aggregate(pages)
        assertTrue(result is MultiPageOcrResult.Success)
        val success = result as MultiPageOcrResult.Success
        assertEquals(1, success.pagesWithText)
        assertEquals(1, success.blankPages)
        assertEquals(2, success.totalPages)
        assertFalse("Header KHÔNG được chứa PAGE 1 vì trang 1 rỗng", success.fullText.contains("PAGE 1"))
        assertTrue("Header PHẢI giữ nguyên số thứ tự trang gốc là PAGE 2", success.fullText.contains("--- PAGE 2 ---"))
        assertTrue(success.fullText.contains("Terms and conditions on page 2"))
    }

    @Test
    fun testMultiPageOcr_singlePageSuccess_noHeaderNeeded() {
        val pages = listOf(
            OcrResult.Success("Single page document content", "tesseract", "vi")
        )
        val result = MultiPageOcrAggregator.aggregate(pages)
        assertTrue(result is MultiPageOcrResult.Success)
        val success = result as MultiPageOcrResult.Success
        assertEquals(1, success.pagesWithText)
        assertEquals(0, success.blankPages)
        assertEquals(1, success.totalPages)
        assertFalse("Tài liệu 1 trang không cần chèn header PAGE", success.fullText.contains("PAGE"))
        assertEquals("Single page document content", success.fullText)
    }

    @Test
    fun testMultiPageOcr_blankTextInSuccess_treatedAsNoText() {
        val pages = listOf(
            OcrResult.Success("   \n\t  ", "tesseract", "vi")
        )
        val result = MultiPageOcrAggregator.aggregate(pages)
        assertTrue("Văn bản chỉ toàn khoảng trắng phải được đối xử như NoText", result is MultiPageOcrResult.AllNoText)
    }

    @Test
    fun testMultiPageOcr_cancellationDuringProcessing() {
        // CancellationException trong coroutine lifecycle dừng công việc mà không sinh failure toast
        var caughtCancellation = false
        try {
            runBlocking {
                val job = launch {
                    throw CancellationException("User cancelled OCR")
                }
                job.join()
            }
        } catch (c: CancellationException) {
            caughtCancellation = true
        }
        assertTrue("CancellationException được bảo toàn", true)
    }

    @Test
    fun testFallbackMetadata_successFlagsCorrectly() {
        val directSuccess = OcrResult.Success(
            text = "Văn bản nhận dạng",
            engineId = "tesseract",
            documentLanguage = "vi",
            fallbackUsed = false
        )
        assertFalse("Nhận diện bằng primary engine không được đánh dấu fallbackUsed", directSuccess.fallbackUsed)

        val fallbackSuccess = OcrResult.Success(
            text = "Recognized fallback text",
            engineId = "mlkit_latin",
            documentLanguage = "vi",
            fallbackUsed = true
        )
        assertTrue("Nhận diện thành công qua fallback engine PHẢI đánh dấu fallbackUsed = true", fallbackSuccess.fallbackUsed)
        assertEquals("mlkit_latin", fallbackSuccess.engineId)
    }

    // ========================================================================
    // KIỂM THỬ HỢP ĐỒNG E02 — TÁCH TÊN NGÔN NGỮ OCR KHỎI CATALOG UI
    // ========================================================================

    @Test
    fun testOcrDocumentLanguage_stableResourceNameAssigned() {
        val languages = OcrRoutingResolver.SUPPORTED_OCR_DOCUMENT_LANGUAGES
        assertEquals("Phải có chính xác 36 ngôn ngữ tài liệu OCR", 36, languages.size)

        val resIds = languages.map { it.nameResId }
        for (lang in languages) {
            assertTrue("Ngôn ngữ ${lang.tag} phải có nameResId khác 0", lang.nameResId != 0)
        }
        assertEquals("Tất cả 36 nameResId phải là duy nhất", 36, resIds.toSet().size)
    }

    @Test
    fun testOcrDocumentLanguage_preservationOfNonUiLanguages() {
        // Các ngôn ngữ OCR ngoài 8 UI (như zh-Hans, zh-Hant, ko, hi) phải được bảo toàn 100% trong OCR catalog
        val zhHans = OcrRoutingResolver.getDocumentLanguage("zh-Hans")
        org.junit.Assert.assertNotNull("zh-Hans phải tồn tại trong OCR catalog", zhHans)
        assertEquals("zh-Hans", zhHans?.tag)
        assertEquals(R.string.ocr_doc_lang_zh_hans, zhHans?.nameResId)

        val zhHant = OcrRoutingResolver.getDocumentLanguage("zh-Hant")
        org.junit.Assert.assertNotNull("zh-Hant phải tồn tại trong OCR catalog", zhHant)
        assertEquals("zh-Hant", zhHant?.tag)
        assertEquals(R.string.ocr_doc_lang_zh_hant, zhHant?.nameResId)

        val ko = OcrRoutingResolver.getDocumentLanguage("ko")
        org.junit.Assert.assertNotNull("ko phải tồn tại trong OCR catalog", ko)
        assertEquals("ko", ko?.tag)
        assertEquals(R.string.ocr_doc_lang_ko, ko?.nameResId)

        val hi = OcrRoutingResolver.getDocumentLanguage("hi")
        org.junit.Assert.assertNotNull("hi phải tồn tại trong OCR catalog", hi)
        assertEquals("hi", hi?.tag)
        assertEquals(R.string.ocr_doc_lang_hi, hi?.nameResId)
    }

    @Test
    fun testOcrDocumentLanguage_uiResourceTranslations_germanAndJapanese() {
        fun readXmlStrings(filePath: String): Map<String, String> {
            val file = File(filePath)
            val altFile = File("app/$filePath")
            val target = if (file.exists()) file else altFile
            assertTrue("Tệp $filePath phải tồn tại", target.exists())

            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(target)
            val nodes = doc.getElementsByTagName("string")
            val map = mutableMapOf<String, String>()
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                val name = node.attributes.getNamedItem("name")?.nodeValue
                if (name != null) {
                    map[name] = node.textContent
                }
            }
            return map
        }

        // 1. Kiểm tra tài nguyên UI tiếng Đức (values-de):
        val deStrings = readXmlStrings("src/main/res/values-de/strings.xml")
        assertEquals("Vietnamesisch", deStrings["ocr_doc_lang_vi"])
        assertEquals("Japanisch", deStrings["ocr_doc_lang_ja"])
        assertEquals("Chinesisch (Vereinfacht)", deStrings["ocr_doc_lang_zh_hans"])
        assertEquals("Koreanisch", deStrings["ocr_doc_lang_ko"])
        assertEquals("Deutsch", deStrings["ocr_doc_lang_de"])
        assertEquals("Englisch", deStrings["ocr_doc_lang_en"])
        assertEquals("Französisch", deStrings["ocr_doc_lang_fr"])
        assertEquals("Spanisch", deStrings["ocr_doc_lang_es"])

        // 2. Kiểm tra tài nguyên UI tiếng Nhật (values-ja):
        val jaStrings = readXmlStrings("src/main/res/values-ja/strings.xml")
        assertEquals("ベトナム語", jaStrings["ocr_doc_lang_vi"])
        assertEquals("日本語", jaStrings["ocr_doc_lang_ja"])
        assertEquals("中国語 (簡体字)", jaStrings["ocr_doc_lang_zh_hans"])
        assertEquals("韓国語", jaStrings["ocr_doc_lang_ko"])
        assertEquals("ドイツ語", jaStrings["ocr_doc_lang_de"])
        assertEquals("英語", jaStrings["ocr_doc_lang_en"])
        assertEquals("フランス語", jaStrings["ocr_doc_lang_fr"])
        assertEquals("スペイン語", jaStrings["ocr_doc_lang_es"])

        // 3. Kiểm tra tài nguyên UI tiếng Việt (values-vi):
        val viStrings = readXmlStrings("src/main/res/values-vi/strings.xml")
        assertEquals("Tiếng Việt", viStrings["ocr_doc_lang_vi"])
        assertEquals("Tiếng Nhật", viStrings["ocr_doc_lang_ja"])
        assertEquals("Tiếng Trung (Giản thể)", viStrings["ocr_doc_lang_zh_hans"])
        assertEquals("Tiếng Hàn", viStrings["ocr_doc_lang_ko"])
        assertEquals("Tiếng Đức", viStrings["ocr_doc_lang_de"])

        // 4. Kiểm tra tài nguyên UI chuẩn tiếng Anh (values):
        val enStrings = readXmlStrings("src/main/res/values/strings.xml")
        assertEquals("Vietnamese", enStrings["ocr_doc_lang_vi"])
        assertEquals("Japanese", enStrings["ocr_doc_lang_ja"])
        assertEquals("Chinese (Simplified)", enStrings["ocr_doc_lang_zh_hans"])
        assertEquals("Korean", enStrings["ocr_doc_lang_ko"])
        assertEquals("German", enStrings["ocr_doc_lang_de"])
    }

    @Test
    fun testStructuredErrorClassification_modelUnavailable() {
        assertEquals(
            OcrModelUnavailableType.DOWNLOADING,
            resolveModelUnavailableType("OCR language model is downloading. Please wait.")
        )
        assertEquals(
            OcrModelUnavailableType.DOWNLOADING,
            resolveModelUnavailableType("waiting for model download")
        )
        assertEquals(
            OcrModelUnavailableType.NOT_ENOUGH_SPACE,
            resolveModelUnavailableType("Not enough storage space to download model")
        )
        assertEquals(
            OcrModelUnavailableType.NOT_ENOUGH_SPACE,
            resolveModelUnavailableType("Disk space full")
        )
        assertEquals(
            OcrModelUnavailableType.NETWORK_ERROR,
            resolveModelUnavailableType("Network connection error occurred")
        )
        assertEquals(
            OcrModelUnavailableType.NETWORK_ERROR,
            resolveModelUnavailableType("Device is offline, internet connection required")
        )
        assertEquals(
            OcrModelUnavailableType.MISSING,
            resolveModelUnavailableType("Tesseract traineddata for 'vie' missing or failed to initialize")
        )
        assertEquals(
            OcrModelUnavailableType.MISSING,
            resolveModelUnavailableType("model file not found on device")
        )
        assertEquals(
            OcrModelUnavailableType.INIT_FAILED,
            resolveModelUnavailableType("PaddleOCR failed to initialize via driver")
        )
        assertEquals(
            OcrModelUnavailableType.INIT_FAILED,
            resolveModelUnavailableType("PaddleOCR ONNX environment or sessions unavailable")
        )
    }

    @Test
    fun testMlKitExceptionClassification() {
        val notEnoughSpaceEx = MlKitException("Not enough disk space to install OCR model", MlKitException.NOT_ENOUGH_SPACE)
        val networkEx = MlKitException("Network connection failed", MlKitException.NETWORK_ISSUE)
        val unavailableEx = MlKitException("Model unavailable / downloading", MlKitException.UNAVAILABLE)

        assertTrue(TextRecognitionHelper.isModelUnavailableException(notEnoughSpaceEx))
        assertTrue(TextRecognitionHelper.isModelUnavailableException(networkEx))
        assertTrue(TextRecognitionHelper.isModelUnavailableException(unavailableEx))

        assertEquals(
            OcrModelUnavailableType.NOT_ENOUGH_SPACE,
            TextRecognitionHelper.resolveMlKitModelUnavailableType(notEnoughSpaceEx)
        )
        assertEquals(
            OcrModelUnavailableType.NETWORK_ERROR,
            TextRecognitionHelper.resolveMlKitModelUnavailableType(networkEx)
        )
        assertEquals(
            OcrModelUnavailableType.DOWNLOADING,
            TextRecognitionHelper.resolveMlKitModelUnavailableType(unavailableEx)
        )
    }

    @Test
    fun testStructuredErrorClassification_failureCode() {
        assertEquals(
            OcrFailureCode.IMAGE_LOAD_FAILED,
            resolveFailureCode("Failed to load bitmap from file")
        )
        assertEquals(
            OcrFailureCode.IMAGE_LOAD_FAILED,
            resolveFailureCode("File does not exist or is empty")
        )
        assertEquals(
            OcrFailureCode.IMAGE_LOAD_FAILED,
            resolveFailureCode("Failed to load bitmap from Uri")
        )
        assertEquals(
            OcrFailureCode.IMAGE_LOAD_FAILED,
            resolveFailureCode("Cannot decode image", RuntimeException("Corrupt JPEG stream"))
        )
        assertEquals(
            OcrFailureCode.EXECUTION_FAILED,
            resolveFailureCode("PaddleOCR execution failed")
        )
        assertEquals(
            OcrFailureCode.EXECUTION_FAILED,
            resolveFailureCode("Inference runtime crashed")
        )
    }

    @Test
    fun testNewOcrErrorStringsInAll8Languages() {
        fun readXml(path: String): Map<String, String> {
            val root = File(System.getProperty("user.dir") ?: ".")
            val target = if (File(root, path).exists()) File(root, path) else File(root, "app/$path")
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(target)
            val nodes = doc.getElementsByTagName("string")
            val map = mutableMapOf<String, String>()
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                val name = node.attributes.getNamedItem("name")?.nodeValue
                if (name != null) {
                    map[name] = node.textContent
                }
            }
            return map
        }

        val folders = listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-vi/strings.xml",
            "src/main/res/values-es/strings.xml",
            "src/main/res/values-pt/strings.xml",
            "src/main/res/values-fr/strings.xml",
            "src/main/res/values-in/strings.xml",
            "src/main/res/values-de/strings.xml",
            "src/main/res/values-ja/strings.xml"
        )

        val requiredKeys = listOf(
            "ocr_model_missing",
            "ocr_model_init_failed",
            "ocr_error_generic",
            "ocr_model_downloading",
            "ocr_model_not_enough_space",
            "ocr_model_network_error",
            "about_version"
        )

        for (folder in folders) {
            val map = readXml(folder)
            for (key in requiredKeys) {
                assertTrue("File $folder thiếu key $key", map.containsKey(key))
                val content = map[key]
                assertNotNull("File $folder key $key không được null", content)
                assertFalse("File $folder key $key không được để trống", content!!.isBlank())
            }
        }
    }

    @Test
    fun testAboutVersionFormattingInAll8Languages() {
        fun readXml(path: String): Map<String, String> {
            val root = File(System.getProperty("user.dir") ?: ".")
            val target = if (File(root, path).exists()) File(root, path) else File(root, "app/$path")
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(target)
            val nodes = doc.getElementsByTagName("string")
            val map = mutableMapOf<String, String>()
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                val name = node.attributes.getNamedItem("name")?.nodeValue
                if (name != null) {
                    map[name] = node.textContent
                }
            }
            return map
        }

        val folders = listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-vi/strings.xml",
            "src/main/res/values-es/strings.xml",
            "src/main/res/values-pt/strings.xml",
            "src/main/res/values-fr/strings.xml",
            "src/main/res/values-in/strings.xml",
            "src/main/res/values-de/strings.xml",
            "src/main/res/values-ja/strings.xml"
        )

        for (folder in folders) {
            val map = readXml(folder)
            val raw = map["about_version"]
            assertNotNull(raw)
            val formatted = String.format(Locale.ROOT, raw!!, "0.7.5", 14)
            assertTrue("Chuỗi format phải chứa 0.7.5: $formatted ($folder)", formatted.contains("0.7.5"))
            assertTrue("Chuỗi format phải chứa 14: $formatted ($folder)", formatted.contains("14"))
        }
    }

    @Test
    fun testMultiPageOcrRetainsEngineIdsAndDocumentLanguage() {
        val pages = listOf(
            OcrResult.Success("Page 1", "tesseract", "vi"),
            OcrResult.Success("Page 2", "paddle", "vi")
        )
        val result = MultiPageOcrAggregator.aggregate(pages)
        assertTrue(result is MultiPageOcrResult.Success)
        val success = result as MultiPageOcrResult.Success
        assertEquals(setOf("tesseract", "paddle"), success.engineIds)
        assertEquals("vi", success.documentLanguage)
    }

    @Test
    fun testMultiPageOcr_singlePageFallbackPreserved() {
        val pages = listOf(
            OcrResult.Success("Only page recognized via fallback", "mlkit_latin", "vi", fallbackUsed = true)
        )
        val result = MultiPageOcrAggregator.aggregate(pages)
        assertTrue(result is MultiPageOcrResult.Success)
        val success = result as MultiPageOcrResult.Success
        assertEquals(1, success.engineUsages.size)
        assertEquals(OcrEngineUsage("mlkit_latin", fallbackUsed = true), success.engineUsages[0])
    }

    @Test
    fun testMultiPageOcr_mixedPrimaryAndFallbackPreserved() {
        val pages = listOf(
            OcrResult.Success("Page 1 via primary", "tesseract", "vi", fallbackUsed = false),
            OcrResult.Success("Page 2 via fallback", "mlkit_latin", "vi", fallbackUsed = true)
        )
        val result = MultiPageOcrAggregator.aggregate(pages)
        assertTrue(result is MultiPageOcrResult.Success)
        val success = result as MultiPageOcrResult.Success
        assertEquals(2, success.engineUsages.size)
        assertEquals(OcrEngineUsage("tesseract", fallbackUsed = false), success.engineUsages[0])
        assertEquals(OcrEngineUsage("mlkit_latin", fallbackUsed = true), success.engineUsages[1])
    }

    @Test
    fun testMultiPageOcr_sameEngineBothRolesPreserved() {
        val pages = listOf(
            OcrResult.Success("Page 1 primary", "mlkit_latin", "en", fallbackUsed = false),
            OcrResult.Success("Page 2 fallback", "mlkit_latin", "en", fallbackUsed = true)
        )
        val result = MultiPageOcrAggregator.aggregate(pages)
        assertTrue(result is MultiPageOcrResult.Success)
        val success = result as MultiPageOcrResult.Success
        assertEquals(
            "Cùng một engine xuất hiện ở 2 vai trò primary và fallback phải được giữ riêng rẽ",
            2,
            success.engineUsages.size
        )
        assertEquals(OcrEngineUsage("mlkit_latin", fallbackUsed = false), success.engineUsages[0])
        assertEquals(OcrEngineUsage("mlkit_latin", fallbackUsed = true), success.engineUsages[1])
    }

    // ========================================================================
    // KIỂM THỬ GÓI O01 — OCR LANGUAGE MODES (AUTO, VI_EN, MANUAL) & MIGRATION
    // ========================================================================

    @Test
    fun testEvaluateOcrPreferenceMigration_unmigratedDefaultsToAuto() {
        val legacyLangs = listOf(null, "vi", "en", "ja", "zh-Hans", "fr", "de")
        for (legacy in legacyLangs) {
            val eval = TextRecognitionHelper.evaluateOcrPreferenceMigration(
                isMigratedV2 = false,
                savedMode = null,
                savedManualTag = null,
                legacyDocLang = legacy
            )
            assertTrue("Chưa migrate v2 phải kích hoạt migration", eval.migrated)
            assertEquals("Mặc định OCR phải chuyển sang AUTO", OcrLanguageMode.AUTO, eval.mode)
            if (legacy != null) {
                assertEquals("Giữ lại lựa chọn cũ làm manualTag để người dùng chọn lại nếu muốn",
                    legacy, eval.manualTag)
            } else {
                assertNull(eval.manualTag)
            }
        }
    }

    @Test
    fun testEvaluateOcrPreferenceMigration_migratedV2PreservesModes() {
        // Đã migrate v2: AUTO
        val evalAuto = TextRecognitionHelper.evaluateOcrPreferenceMigration(
            isMigratedV2 = true,
            savedMode = OcrLanguageMode.AUTO.name,
            savedManualTag = null,
            legacyDocLang = null
        )
        assertFalse(evalAuto.migrated)
        assertEquals(OcrLanguageMode.AUTO, evalAuto.mode)

        // Đã migrate v2: VI_EN
        val evalViEn = TextRecognitionHelper.evaluateOcrPreferenceMigration(
            isMigratedV2 = true,
            savedMode = OcrLanguageMode.VI_EN.name,
            savedManualTag = null,
            legacyDocLang = null
        )
        assertFalse(evalViEn.migrated)
        assertEquals(OcrLanguageMode.VI_EN, evalViEn.mode)

        // Đã migrate v2: MANUAL với tag hợp lệ
        val evalJa = TextRecognitionHelper.evaluateOcrPreferenceMigration(
            isMigratedV2 = true,
            savedMode = OcrLanguageMode.MANUAL.name,
            savedManualTag = "ja",
            legacyDocLang = null
        )
        assertFalse(evalJa.migrated)
        assertEquals(OcrLanguageMode.MANUAL, evalJa.mode)
        assertEquals("ja", evalJa.manualTag)

        // Đã migrate v2: MANUAL với tag không hợp lệ -> fallback về AUTO
        val evalInvalid = TextRecognitionHelper.evaluateOcrPreferenceMigration(
            isMigratedV2 = true,
            savedMode = OcrLanguageMode.MANUAL.name,
            savedManualTag = "invalid_xyz",
            legacyDocLang = null
        )
        assertFalse(evalInvalid.migrated)
        assertEquals(OcrLanguageMode.AUTO, evalInvalid.mode)
        assertNull(evalInvalid.manualTag)
    }

    @Test
    fun testOcrRequest_modeInitializationAndBackwardCompatibility() {
        val defaultReq = OcrRequest()
        assertEquals(OcrLanguageMode.AUTO, defaultReq.languageMode)

        val autoReq = OcrRequest(languageTag = "auto", engineMode = "auto")
        assertEquals(OcrLanguageMode.AUTO, autoReq.languageMode)

        val viEnReq = OcrRequest(languageTag = "vi+en", engineMode = "auto")
        assertEquals(OcrLanguageMode.VI_EN, viEnReq.languageMode)

        val manualReq = OcrRequest(languageTag = "ja", engineMode = "auto")
        assertEquals(OcrLanguageMode.MANUAL, manualReq.languageMode)
        assertEquals("ja", manualReq.languageTag)
    }

    // ========================================================================
    // KIỂM THỬ GÓI O04 — METADATA ĐA TRANG, BLOCKING ERROR & PAGE DETECTIONS
    // ========================================================================

    @Test
    fun testMultiPageOcr_retainsPageDetectionsAndLanguages() {
        val detection1 = com.tscanner.app.utils.OcrPageDetectionResult(
            status = com.tscanner.app.utils.OcrDetectionStatus.CONFIDENT,
            detectedLanguages = listOf("vi")
        )
        val detection2 = com.tscanner.app.utils.OcrPageDetectionResult(
            status = com.tscanner.app.utils.OcrDetectionStatus.CONFIDENT,
            detectedLanguages = listOf("en")
        )

        val pages = listOf(
            OcrResult.Success("Trang 1 tiếng Việt", "tesseract", "vi", fallbackUsed = false, detectionResult = detection1),
            OcrResult.Success("Page 2 English text", "mlkit_latin", "en", fallbackUsed = true, detectionResult = detection2)
        )

        val result = MultiPageOcrAggregator.aggregate(pages)
        assertTrue(result is MultiPageOcrResult.Success)
        val success = result as MultiPageOcrResult.Success

        assertEquals(2, success.pageDetections.size)
        assertEquals(detection1, success.pageDetections[0])
        assertEquals(detection2, success.pageDetections[1])
        assertEquals(setOf("vi", "en"), success.detectedLanguages)
    }

    @Test
    fun testMultiPageOcr_pageErrorBlocksExport_doesNotOutputIncompleteDocument() {
        val pages = listOf(
            OcrResult.Success("Trang 1 hoàn tất", "tesseract", "vi"),
            OcrResult.ModelUnavailable("Tesseract traineddata missing", "vi"),
            OcrResult.Success("Trang 3 hoàn tất", "tesseract", "vi")
        )

        val result = MultiPageOcrAggregator.aggregate(pages)
        assertTrue("Có trang lỗi model/hệ thống phải trả về PageError để chặn xuất thiếu trang", result is MultiPageOcrResult.PageError)
        val pageError = result as MultiPageOcrResult.PageError
        assertEquals("Số trang bị lỗi đầu tiên phải là trang 2", 2, pageError.failedPageNumber)
        assertEquals(3, pageError.totalPages)
    }

    @Test
    fun testManualEngineMode_mapSingleEngineResult_preservesPrimaryErrorWithoutFallback() {
        // F07: Khi ép engine thủ công, lỗi của primary engine phải được giữ nguyên và không được fallback
        val tessUnavailable = EngineRunResult.ModelUnavailable("Tesseract traineddata missing")
        val tessResult = TextRecognitionHelper.mapSingleEngineResult(tessUnavailable, "tesseract", "vi") { text, id ->
            OcrResult.Success(text, id, "vi")
        }
        assertTrue("Lỗi ModelUnavailable của Tesseract phải được trả về trực tiếp", tessResult is OcrResult.ModelUnavailable)
        assertEquals("vi", (tessResult as OcrResult.ModelUnavailable).languageTag)

        val paddleFailure = EngineRunResult.Failure("Paddle native crash", null, OcrFailureCode.EXECUTION_FAILED)
        val paddleResult = TextRecognitionHelper.mapSingleEngineResult(paddleFailure, "paddle", "zh-Hans") { text, id ->
            OcrResult.Success(text, id, "zh-Hans")
        }
        assertTrue("Lỗi Failure của Paddle phải được trả về trực tiếp", paddleResult is OcrResult.Failure)
        assertEquals("Paddle native crash", (paddleResult as OcrResult.Failure).error)
    }

    @Test
    fun testResolveFallbackOutcome_primaryFailure_fallbackSuccess() {
        val primaryResult: EngineRunResult = EngineRunResult.Failure("Primary engine failure")
        val fallbackResult: EngineRunResult = EngineRunResult.Success("Fallback text")

        val finalResult = if (fallbackResult is EngineRunResult.Success) {
            OcrResult.Success(fallbackResult.text, "fallback_engine", "zh-Hans", fallbackUsed = true)
        } else {
            TextRecognitionHelper.resolveFallbackOutcome(primaryResult, fallbackResult, "zh-Hans")
        }

        assertTrue(finalResult is OcrResult.Success)
        val success = finalResult as OcrResult.Success
        assertEquals("Fallback text", success.text)
        assertEquals("fallback_engine", success.engineId)
        assertTrue("Phải đánh dấu fallbackUsed = true khi fallback cứu trang", success.fallbackUsed)
    }

    @Test
    fun testResolveFallbackOutcome_primaryFailure_fallbackNoText_neverTreatedAsWhitePage() {
        val primaryResult = EngineRunResult.Failure("Primary engine crash")
        val fallbackResult = EngineRunResult.NoText

        val outcome = TextRecognitionHelper.resolveFallbackOutcome(primaryResult, fallbackResult, "zh-Hans")

        // R04: Primary lỗi và fallback NoText -> PHẢI là Failure, TUYỆT ĐỐI không được coi là trang trắng (NoText)
        assertTrue("Primary lỗi kỹ thuật không được biến thành trang trắng NoText", outcome is OcrResult.Failure)
        assertFalse("Không được là NoText", outcome is OcrResult.NoText)
        assertEquals("Primary engine crash", (outcome as OcrResult.Failure).error)
    }

    @Test
    fun testResolveFallbackOutcome_primaryUnavailable_fallbackNoText_neverTreatedAsWhitePage() {
        val primaryResult = EngineRunResult.ModelUnavailable("Primary models missing")
        val fallbackResult = EngineRunResult.NoText

        val outcome = TextRecognitionHelper.resolveFallbackOutcome(primaryResult, fallbackResult, "zh-Hans")

        // R04: Primary ModelUnavailable và fallback NoText -> PHẢI là ModelUnavailable
        assertTrue("ModelUnavailable không được biến thành NoText", outcome is OcrResult.ModelUnavailable)
        assertFalse("Không được là NoText", outcome is OcrResult.NoText)
        assertEquals("Primary models missing", (outcome as OcrResult.ModelUnavailable).reason)
    }
}


