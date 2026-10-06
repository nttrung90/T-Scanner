package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import com.tscanner.app.ocr.model.OcrBlock
import com.tscanner.app.ocr.model.OcrDocument
import com.tscanner.app.ocr.model.OcrLine
import com.tscanner.app.ocr.model.OcrPage
import com.tscanner.app.ocr.model.OcrPageStatus
import com.tscanner.app.ocr.model.OcrRect
import com.tscanner.app.utils.EngineRunResult
import com.tscanner.app.utils.OcrFailureCode
import com.tscanner.app.utils.OcrLanguageMode
import com.tscanner.app.utils.OcrModelUnavailableType
import com.tscanner.app.utils.OcrRequest
import com.tscanner.app.utils.OcrResult
import com.tscanner.app.utils.OcrRoutingResolver
import com.tscanner.app.utils.OcrType
import com.tscanner.app.utils.TextRecognitionHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ChineseMlKitRoutingTest {

    private lateinit var dummyContext: DummyContext
    private lateinit var stubBitmap: Bitmap

    @Before
    fun setUp() {
        dummyContext = DummyContext()
        stubBitmap = createStubBitmap()
        TextRecognitionHelper.mlKitChineseRunner = null
    }

    @After
    fun tearDown() {
        TextRecognitionHelper.mlKitChineseRunner = null
    }

    private fun createStubBitmap(): Bitmap {
        return try {
            val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null) as sun.misc.Unsafe
            unsafe.allocateInstance(Bitmap::class.java) as Bitmap
        } catch (_: Throwable) {
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
    }

    // -------------------------------------------------------------------------
    // 1. Alias matrix: Chinese tags routing to MLKIT_CHINESE
    // -------------------------------------------------------------------------

    @Test
    fun testChineseAliasMatrix_allMapToMlKitChinese() {
        val chineseAliases = listOf(
            "zh",
            "zh-Hans",
            "zh-Hant",
            "zh-CN",
            "zh-SG",
            "zh-TW",
            "zh-HK",
            "zh-MO"
        )

        for (tag in chineseAliases) {
            assertEquals(
                "Tag $tag phải ánh xạ tới OcrType.MLKIT_CHINESE",
                OcrType.MLKIT_CHINESE,
                OcrRoutingResolver.getOcrTypeForLanguage(tag)
            )
            assertTrue("Tag $tag phải được xem là ngôn ngữ được hỗ trợ", OcrRoutingResolver.isLanguageSupported(tag))

            val supportedEngines = OcrRoutingResolver.getSupportedEngineModesForLanguage(tag)
            assertEquals("Tag $tag chỉ hỗ trợ auto và mlkit (không có paddle)", listOf("auto", "mlkit"), supportedEngines)
            assertFalse("Tag $tag tuyệt đối không chứa engine paddle", supportedEngines.contains("paddle"))
        }
    }

    // -------------------------------------------------------------------------
    // 2. Engine compatibility for MLKIT_CHINESE
    // -------------------------------------------------------------------------

    @Test
    fun testEngineCompatibility_mlKitChineseCompatibilityRules() {
        assertTrue(
            "AUTO mode phải tương thích với MLKIT_CHINESE",
            OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_AUTO, OcrType.MLKIT_CHINESE)
        )
        assertTrue(
            "MLKIT mode phải tương thích với MLKIT_CHINESE",
            OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_MLKIT, OcrType.MLKIT_CHINESE)
        )
        assertFalse(
            "TESSERACT mode KHÔNG tương thích với MLKIT_CHINESE",
            OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_TESSERACT, OcrType.MLKIT_CHINESE)
        )
        assertFalse(
            "PADDLE mode KHÔNG tương thích với MLKIT_CHINESE (đã bị vô hiệu hóa)",
            OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_PADDLE, OcrType.MLKIT_CHINESE)
        )
    }

    // -------------------------------------------------------------------------
    // 3. Production Dispatcher: recognizeInternalStructured with ML Kit Chinese
    // -------------------------------------------------------------------------

    @Test
    fun testRecognizeInternalStructured_autoMode_invokesMlKitChinese() = runBlocking {
        var chineseCalls = 0
        TextRecognitionHelper.mlKitChineseRunner = {
            chineseCalls++
            EngineRunResult.Success("简体中文测试内容")
        }

        val request = OcrRequest(
            languageMode = OcrLanguageMode.MANUAL,
            languageTag = "zh-Hans",
            engineMode = TextRecognitionHelper.ENGINE_MODE_AUTO
        )

        val result = TextRecognitionHelper.recognizeInternalStructured(dummyContext, stubBitmap, request)

        assertEquals("mlKitChineseRunner phải được gọi chính xác 1 lần", 1, chineseCalls)
        assertTrue("Kết quả phải là OcrResult.Success", result is OcrResult.Success)
        val success = result as OcrResult.Success
        assertEquals("简体中文测试内容", success.text)
        assertEquals("mlkit_chinese", success.engineId)
        assertEquals("zh-Hans", success.documentLanguage)
        assertFalse("fallbackUsed phải là false vì đây là primary engine cho tiếng Trung", success.fallbackUsed)
    }

    @Test
    fun testRecognizeInternalStructured_manualMlKitMode_invokesMlKitChinese() = runBlocking {
        var chineseCalls = 0
        TextRecognitionHelper.mlKitChineseRunner = {
            chineseCalls++
            EngineRunResult.Success("繁體中文測試內容")
        }

        val request = OcrRequest(
            languageMode = OcrLanguageMode.MANUAL,
            languageTag = "zh-Hant",
            engineMode = TextRecognitionHelper.ENGINE_MODE_MLKIT
        )

        val result = TextRecognitionHelper.recognizeInternalStructured(dummyContext, stubBitmap, request)

        assertEquals(1, chineseCalls)
        assertTrue(result is OcrResult.Success)
        val success = result as OcrResult.Success
        assertEquals("繁體中文測試內容", success.text)
        assertEquals("mlkit_chinese", success.engineId)
        assertEquals("zh-Hant", success.documentLanguage)
        assertFalse(success.fallbackUsed)
    }

    @Test
    fun testRecognizeInternalStructured_legacyPaddleRequest_normalizedToAutoAndRunsChinese() = runBlocking {
        var chineseCalls = 0
        TextRecognitionHelper.mlKitChineseRunner = {
            chineseCalls++
            EngineRunResult.Success("旧版Paddle请求已平滑迁移")
        }

        // Request cũ với engineMode = "paddle"
        val request = OcrRequest(
            languageMode = OcrLanguageMode.MANUAL,
            languageTag = "zh-Hans",
            engineMode = "paddle"
        )

        val result = TextRecognitionHelper.recognizeInternalStructured(dummyContext, stubBitmap, request)

        assertEquals("Phải gọi mlKitChineseRunner 1 lần sau khi normalize sang AUTO", 1, chineseCalls)
        assertTrue(result is OcrResult.Success)
        val success = result as OcrResult.Success
        assertEquals("旧版Paddle请求已平滑迁移", success.text)
        assertEquals("mlkit_chinese", success.engineId)
        assertFalse(success.fallbackUsed)
    }

    @Test
    fun testRecognizeInternalStructured_successWithLayout_preservesOcrPageDocument() = runBlocking {
        val mockPage = OcrPage(
            pageId = "p_zh_01",
            pageIndex = 1,
            status = OcrPageStatus.SUCCESS,
            engineId = "mlkit_chinese",
            sourceLanguage = "zh-Hans",
            sourceBlocks = listOf(
                OcrBlock(
                    blockId = "b1",
                    lines = listOf(
                        OcrLine(
                            lineId = "l1",
                            text = "文字识别排版结构",
                            boundingBox = OcrRect(10f, 10f, 100f, 30f)
                        )
                    )
                )
            )
        )

        TextRecognitionHelper.mlKitChineseRunner = {
            EngineRunResult.Success("文字识别排版结构", mockPage)
        }

        val request = OcrRequest(
            languageMode = OcrLanguageMode.MANUAL,
            languageTag = "zh-Hans",
            engineMode = TextRecognitionHelper.ENGINE_MODE_AUTO
        )

        val result = TextRecognitionHelper.recognizeInternalStructured(dummyContext, stubBitmap, request)

        assertTrue(result is OcrResult.Success)
        val success = result as OcrResult.Success
        assertNotNull("pageDocument phải được bảo toàn nguyên vẹn", success.pageDocument)
        assertEquals("p_zh_01", success.pageDocument?.pageId)
        assertEquals(1, success.pageDocument?.sourceBlocks?.size)
        assertEquals("文字识别排版结构", success.pageDocument?.sourceBlocks?.first()?.lines?.first()?.text)
    }

    @Test
    fun testRecognizeInternalStructured_noText_returnsNoTextResult() = runBlocking {
        TextRecognitionHelper.mlKitChineseRunner = {
            EngineRunResult.NoText
        }

        val request = OcrRequest(
            languageMode = OcrLanguageMode.MANUAL,
            languageTag = "zh-Hans",
            engineMode = TextRecognitionHelper.ENGINE_MODE_AUTO
        )

        val result = TextRecognitionHelper.recognizeInternalStructured(dummyContext, stubBitmap, request)

        assertTrue("Kết quả phải là OcrResult.NoText", result is OcrResult.NoText)
        assertNull("textOrNull của NoText phải là null", result.textOrNull)
    }

    @Test
    fun testRecognizeInternalStructured_modelUnavailable_doesNotFallbackToLatinOrTess() = runBlocking {
        var chineseCalls = 0
        TextRecognitionHelper.mlKitChineseRunner = {
            chineseCalls++
            EngineRunResult.ModelUnavailable(
                reason = "Waiting for the text recognition module to be downloaded",
                type = OcrModelUnavailableType.DOWNLOADING
            )
        }

        val request = OcrRequest(
            languageMode = OcrLanguageMode.MANUAL,
            languageTag = "zh-Hans",
            engineMode = TextRecognitionHelper.ENGINE_MODE_AUTO
        )

        val result = TextRecognitionHelper.recognizeInternalStructured(dummyContext, stubBitmap, request)

        assertEquals(1, chineseCalls)
        assertTrue("Lỗi model unavailable phải được bảo toàn, không được fallback sang Latin", result is OcrResult.ModelUnavailable)
        val unavailable = result as OcrResult.ModelUnavailable
        assertEquals("Waiting for the text recognition module to be downloaded", unavailable.reason)
        assertEquals("zh-Hans", unavailable.languageTag)
        assertEquals(OcrModelUnavailableType.DOWNLOADING, unavailable.type)
    }

    @Test
    fun testRecognizeInternalStructured_failure_returnsFailureResult() = runBlocking {
        TextRecognitionHelper.mlKitChineseRunner = {
            EngineRunResult.Failure(
                message = "ML Kit internal inference error",
                cause = RuntimeException("Crash"),
                code = OcrFailureCode.EXECUTION_FAILED
            )
        }

        val request = OcrRequest(
            languageMode = OcrLanguageMode.MANUAL,
            languageTag = "zh-Hans",
            engineMode = TextRecognitionHelper.ENGINE_MODE_AUTO
        )

        val result = TextRecognitionHelper.recognizeInternalStructured(dummyContext, stubBitmap, request)

        assertTrue("Kết quả phải là OcrResult.Failure", result is OcrResult.Failure)
        val failure = result as OcrResult.Failure
        assertEquals("ML Kit internal inference error", failure.error)
        assertEquals(OcrFailureCode.EXECUTION_FAILED, failure.code)
    }

    @Test
    fun testRecognizeInternalStructured_cancellationExceptionPropagated() = runBlocking {
        TextRecognitionHelper.mlKitChineseRunner = {
            throw CancellationException("OCR cancelled by user")
        }

        val request = OcrRequest(
            languageMode = OcrLanguageMode.MANUAL,
            languageTag = "zh-Hans",
            engineMode = TextRecognitionHelper.ENGINE_MODE_AUTO
        )

        try {
            TextRecognitionHelper.recognizeInternalStructured(dummyContext, stubBitmap, request)
            fail("CancellationException phải được throw ra ngoài, không được nuốt")
        } catch (c: CancellationException) {
            assertEquals("OCR cancelled by user", c.message)
        }
    }

    // -------------------------------------------------------------------------
    // 4. Non-regression: Other languages do NOT invoke mlKitChineseRunner
    // -------------------------------------------------------------------------

    @Test
    fun testNonRegression_otherLanguagesDoNotInvokeChineseRunner() = runBlocking {
        var chineseCalls = 0
        TextRecognitionHelper.mlKitChineseRunner = {
            chineseCalls++
            EngineRunResult.Success("Unexpected Chinese call")
        }

        // 1. Tiếng Ả Rập: Unsupported
        val arRequest = OcrRequest(languageMode = OcrLanguageMode.MANUAL, languageTag = "ar", engineMode = "auto")
        val arResult = TextRecognitionHelper.recognizeInternalStructured(dummyContext, stubBitmap, arRequest)
        assertTrue(arResult is OcrResult.UnsupportedLanguage)
        assertEquals("ar", (arResult as OcrResult.UnsupportedLanguage).languageTag)
        assertEquals("Chinese runner không được gọi cho ar", 0, chineseCalls)

        // 2. Incompatible engine: Tesseract cho zh-Hans bị từ chối trước khi gọi runner
        val tessZhRequest = OcrRequest(languageMode = OcrLanguageMode.MANUAL, languageTag = "zh-Hans", engineMode = TextRecognitionHelper.ENGINE_MODE_TESSERACT)
        val tessZhResult = TextRecognitionHelper.recognizeInternalStructured(dummyContext, stubBitmap, tessZhRequest)
        assertTrue(tessZhResult is OcrResult.IncompatibleEngine)
        assertEquals("tesseract", (tessZhResult as OcrResult.IncompatibleEngine).engineMode)
        assertEquals(0, chineseCalls)
    }

    // -------------------------------------------------------------------------
    private class DummyContext(private val prefs: SharedPreferences = FakeSharedPreferences()) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? Set<*>)?.mapNotNull { it?.toString() }?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removes = mutableSetOf<String>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) {
                    if (value != null) pending[key] = value else removes.add(key)
                }
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) {
                    if (values != null) pending[key] = values else removes.add(key)
                }
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) removes.add(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearFlag = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearFlag) {
                    prefs.map.clear()
                    clearFlag = false
                }
                removes.forEach { prefs.map.remove(it) }
                removes.clear()
                pending.forEach { (k, v) ->
                    if (v != null) prefs.map[k] = v else prefs.map.remove(k)
                }
                pending.clear()
            }
        }
    }
}
