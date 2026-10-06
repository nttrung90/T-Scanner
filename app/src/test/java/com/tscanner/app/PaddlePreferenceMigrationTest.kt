package com.tscanner.app

import android.content.SharedPreferences
import com.tscanner.app.ocr.model.OcrBlock
import com.tscanner.app.ocr.model.OcrDocument
import com.tscanner.app.ocr.model.OcrLine
import com.tscanner.app.ocr.model.OcrPage
import com.tscanner.app.ocr.model.OcrPoint
import com.tscanner.app.utils.OcrLanguageMode
import com.tscanner.app.utils.OcrRequest
import com.tscanner.app.utils.OcrResult
import com.tscanner.app.utils.TextRecognitionHelper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PaddlePreferenceMigrationTest {

    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
    }

    // -------------------------------------------------------------------------
    // 1. Chuẩn hóa hàm thuần normalizeEngineMode
    // -------------------------------------------------------------------------

    @Test
    fun testNormalizeEngineMode_legacyPaddleBecomesAuto() {
        assertEquals(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.normalizeEngineMode("paddle"))
        assertEquals(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.normalizeEngineMode(TextRecognitionHelper.ENGINE_MODE_PADDLE))
    }

    @Test
    fun testNormalizeEngineMode_validEnginesRetained() {
        assertEquals(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.normalizeEngineMode(TextRecognitionHelper.ENGINE_MODE_AUTO))
        assertEquals(TextRecognitionHelper.ENGINE_MODE_TESSERACT, TextRecognitionHelper.normalizeEngineMode(TextRecognitionHelper.ENGINE_MODE_TESSERACT))
        assertEquals(TextRecognitionHelper.ENGINE_MODE_MLKIT, TextRecognitionHelper.normalizeEngineMode(TextRecognitionHelper.ENGINE_MODE_MLKIT))
    }

    @Test
    fun testNormalizeEngineMode_nullDefaultsToAuto() {
        assertEquals(TextRecognitionHelper.ENGINE_MODE_AUTO, TextRecognitionHelper.normalizeEngineMode(null))
    }

    @Test
    fun testNormalizeEngineMode_unknownEngineNotSilentlyConverted() {
        // Chính sách lỗi: engine lạ không được âm thầm chuyển thành auto
        assertEquals("unknown_engine_xyz", TextRecognitionHelper.normalizeEngineMode("unknown_engine_xyz"))
        assertEquals("custom_engine", TextRecognitionHelper.normalizeEngineMode("custom_engine"))
    }

    // -------------------------------------------------------------------------
    // 2. Getter SharedPreferences migration & idempotency
    // -------------------------------------------------------------------------

    @Test
    fun testGetPreferredEngine_migratesLegacyPaddleToAutoAndPersists() {
        fakePrefs.edit().putString("key_preferred_ocr_engine", "paddle").apply()
        assertEquals("paddle", fakePrefs.getString("key_preferred_ocr_engine", null))

        val engine = TextRecognitionHelper.getPreferredEngine(fakePrefs)
        assertEquals("Getter phải trả về auto ngay lập tức khi gặp paddle legacy", TextRecognitionHelper.ENGINE_MODE_AUTO, engine)

        // Phải được ghi lại giá trị auto trong persistence
        val persisted = fakePrefs.getString("key_preferred_ocr_engine", null)
        assertEquals("Persistence phải được cập nhật thành auto", TextRecognitionHelper.ENGINE_MODE_AUTO, persisted)
    }

    @Test
    fun testGetPreferredEngine_secondReadIsIdempotent() {
        fakePrefs.edit().putString("key_preferred_ocr_engine", "paddle").apply()

        // Lần đọc 1: chuyển đổi paddle -> auto
        val firstRead = TextRecognitionHelper.getPreferredEngine(fakePrefs)
        assertEquals(TextRecognitionHelper.ENGINE_MODE_AUTO, firstRead)

        // Lần đọc 2: giữ auto, không ghi đè bất thường
        val secondRead = TextRecognitionHelper.getPreferredEngine(fakePrefs)
        assertEquals(TextRecognitionHelper.ENGINE_MODE_AUTO, secondRead)
        assertEquals(TextRecognitionHelper.ENGINE_MODE_AUTO, fakePrefs.getString("key_preferred_ocr_engine", null))
    }

    @Test
    fun testGetPreferredEngine_nullOrEmptyDefaultsToAuto() {
        val defaultEngine = TextRecognitionHelper.getPreferredEngine(fakePrefs)
        assertEquals(TextRecognitionHelper.ENGINE_MODE_AUTO, defaultEngine)
    }

    // -------------------------------------------------------------------------
    // 3. Setter SharedPreferences migration
    // -------------------------------------------------------------------------

    @Test
    fun testSetPreferredEngine_legacyPaddleWritesAuto() {
        TextRecognitionHelper.setPreferredEngine(fakePrefs, "paddle")
        val persisted = fakePrefs.getString("key_preferred_ocr_engine", null)
        assertEquals("Setter không bao giờ ghi lại 'paddle', phải lưu 'auto'", TextRecognitionHelper.ENGINE_MODE_AUTO, persisted)
    }

    @Test
    fun testSetPreferredEngine_validEnginesPersistedCorrectly() {
        TextRecognitionHelper.setPreferredEngine(fakePrefs, TextRecognitionHelper.ENGINE_MODE_TESSERACT)
        assertEquals(TextRecognitionHelper.ENGINE_MODE_TESSERACT, fakePrefs.getString("key_preferred_ocr_engine", null))

        TextRecognitionHelper.setPreferredEngine(fakePrefs, TextRecognitionHelper.ENGINE_MODE_MLKIT)
        assertEquals(TextRecognitionHelper.ENGINE_MODE_MLKIT, fakePrefs.getString("key_preferred_ocr_engine", null))

        TextRecognitionHelper.setPreferredEngine(fakePrefs, TextRecognitionHelper.ENGINE_MODE_AUTO)
        assertEquals(TextRecognitionHelper.ENGINE_MODE_AUTO, fakePrefs.getString("key_preferred_ocr_engine", null))
    }

    // -------------------------------------------------------------------------
    // 4. Bảo toàn các preferences khác & không đụng ngôn ngữ
    // -------------------------------------------------------------------------

    @Test
    fun testMigrationPreservesOtherPreferences() {
        fakePrefs.edit()
            .putString("key_preferred_ocr_engine", "paddle")
            .putString("key_ocr_document_language", "zh-Hans")
            .putString(TextRecognitionHelper.KEY_OCR_LANGUAGE_MODE, OcrLanguageMode.MANUAL.name)
            .putString(TextRecognitionHelper.KEY_MANUAL_OCR_TAG, "zh-Hans")
            .putString("unrelated_app_setting", "preserve_this_value")
            .apply()

        // Thực hiện đọc kích hoạt migration
        val engine = TextRecognitionHelper.getPreferredEngine(fakePrefs)
        assertEquals(TextRecognitionHelper.ENGINE_MODE_AUTO, engine)

        // Kiểm tra các key khác không bị ảnh hưởng hay clear
        assertEquals("zh-Hans", fakePrefs.getString("key_ocr_document_language", null))
        assertEquals(OcrLanguageMode.MANUAL.name, fakePrefs.getString(TextRecognitionHelper.KEY_OCR_LANGUAGE_MODE, null))
        assertEquals("zh-Hans", fakePrefs.getString(TextRecognitionHelper.KEY_MANUAL_OCR_TAG, null))
        assertEquals("preserve_this_value", fakePrefs.getString("unrelated_app_setting", null))
    }

    // -------------------------------------------------------------------------
    // 5. Chuẩn hóa OcrRequest trực tiếp không báo Incompatible trước routing
    // -------------------------------------------------------------------------

    @Test
    fun testDirectLegacyRequest_normalizedBeforeCompatibilityCheck() {
        // Giả lập request trực tiếp do caller cũ tạo với engineMode = "paddle"
        val legacyRequest = OcrRequest(
            languageMode = OcrLanguageMode.AUTO,
            languageTag = "vi",
            engineMode = "paddle"
        )

        val effectiveEngineMode = TextRecognitionHelper.normalizeEngineMode(legacyRequest.engineMode)
        assertEquals(TextRecognitionHelper.ENGINE_MODE_AUTO, effectiveEngineMode)

        val effectiveRequest = legacyRequest.copy(engineMode = effectiveEngineMode)
        // Request gốc không bị thay đổi bằng side effect
        assertEquals("paddle", legacyRequest.engineMode)
        assertEquals(TextRecognitionHelper.ENGINE_MODE_AUTO, effectiveRequest.engineMode)
    }

    @Test
    fun testUnknownEngineRequest_isNotSilentlyNormalizedToAuto() {
        val invalidRequest = OcrRequest(
            languageMode = OcrLanguageMode.AUTO,
            languageTag = "vi",
            engineMode = "invalid_custom_engine"
        )

        val effectiveEngineMode = TextRecognitionHelper.normalizeEngineMode(invalidRequest.engineMode)
        assertEquals("invalid_custom_engine", effectiveEngineMode)
        // Khi chạy kiểm tra compatibility, engine lạ sẽ bị từ chối chứ không bị âm thầm chạy
        assertNotEquals(TextRecognitionHelper.ENGINE_MODE_AUTO, effectiveEngineMode)
    }

    // -------------------------------------------------------------------------
    // 6. Tài liệu và metadata lịch sử độc lập hoàn toàn với migration
    // -------------------------------------------------------------------------

    @Test
    fun testLegacyDocumentMetadata_engineIdPaddlePreservedInJson() {
        val originalJson = """
        {
            "pageId": "legacy_page_001",
            "pageIndex": 0,
            "status": "SUCCESS",
            "engineId": "paddle",
            "sourceLanguage": "zh",
            "sourceBlocks": [
                {
                    "blockId": "b1",
                    "lines": [
                        {
                            "lineId": "l1",
                            "text": "测试Paddle历史记录",
                            "boundingBox": [0, 0, 100, 20]
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val parsedPage = OcrPage.fromJson(JSONObject(originalJson))

        // engineId="paddle" KHÔNG ĐƯỢC biến thành "auto" hay "mlkit_chinese"
        assertEquals("paddle", parsedPage.engineId)
        assertEquals("zh", parsedPage.sourceLanguage)
        assertEquals("测试Paddle历史记录", parsedPage.resolvedText)

        // Khi export/serialize lại thành JSON, engineId vẫn là "paddle"
        val reSerializedJson = parsedPage.toJson()
        assertEquals("paddle", reSerializedJson.getString("engineId"))
    }

    // -------------------------------------------------------------------------
    // FakeSharedPreferences test double
    // -------------------------------------------------------------------------

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
