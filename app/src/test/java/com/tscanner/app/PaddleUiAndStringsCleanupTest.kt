package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.tscanner.app.ui.dialogs.OcrLanguageAdapter
import com.tscanner.app.utils.OcrDocumentLanguage
import com.tscanner.app.utils.OcrRoutingResolver
import com.tscanner.app.utils.OcrType
import com.tscanner.app.utils.TextRecognitionHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Unit tests verifying UI and String Resources cleanup for PaddleOCR removal (Package P03).
 */
class PaddleUiAndStringsCleanupTest {

    private lateinit var dummyContext: DummyContext
    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        dummyContext = DummyContext(fakePrefs)
    }

    private fun readXmlStrings(filePath: String): Map<String, String> {
        val root = File(System.getProperty("user.dir") ?: ".")
        val target = if (File(root, filePath).exists()) File(root, filePath) else File(root, "app/$filePath")
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

    // -------------------------------------------------------------------------
    // 1. Kiểm tra 8 locale không còn resource lựa chọn Paddle và About/Desc sạch
    // -------------------------------------------------------------------------

    @Test
    fun testAll8Locales_noActivePaddleSelectionStrings() {
        val locales = listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-vi/strings.xml",
            "src/main/res/values-de/strings.xml",
            "src/main/res/values-es/strings.xml",
            "src/main/res/values-fr/strings.xml",
            "src/main/res/values-in/strings.xml",
            "src/main/res/values-ja/strings.xml",
            "src/main/res/values-pt/strings.xml"
        )

        for (localePath in locales) {
            val map = readXmlStrings(localePath)

            // 1. Không còn resource lựa chọn Paddle cũ
            assertFalse("File $localePath không được chứa key ocr_engine_paddle", map.containsKey("ocr_engine_paddle"))
            assertFalse("File $localePath không được chứa key ocr_engine_paddle_desc", map.containsKey("ocr_engine_paddle_desc"))

            // 2. Resource legacy paddle phải tồn tại để phục vụ metadata lịch sử
            assertTrue("File $localePath phải chứa ocr_engine_legacy_paddle", map.containsKey("ocr_engine_legacy_paddle"))
            val legacyName = map["ocr_engine_legacy_paddle"]
            assertNotNull(legacyName)
            assertTrue("Tên legacy paddle không được rỗng ($localePath)", legacyName!!.isNotBlank())

            // 3. about_engine không được quảng bá Paddle, phải chứa ML Kit
            val aboutEngine = map["about_engine"]
            assertNotNull("about_engine phải tồn tại ($localePath)", aboutEngine)
            assertFalse("about_engine không được chứa Paddle ($localePath): $aboutEngine", aboutEngine!!.contains("Paddle"))
            assertTrue("about_engine phải chứa Google ML Kit hoặc ML Kit ($localePath)", aboutEngine.contains("ML Kit"))

            // 4. ocr_engine_auto_desc không được chứa Paddle
            val autoDesc = map["ocr_engine_auto_desc"]
            assertNotNull("ocr_engine_auto_desc phải tồn tại ($localePath)", autoDesc)
            assertFalse("ocr_engine_auto_desc không được chứa Paddle ($localePath): $autoDesc", autoDesc!!.contains("Paddle"))
            assertTrue("ocr_engine_auto_desc phải chứa ML Kit ($localePath)", autoDesc.contains("ML Kit"))
        }
    }

    // -------------------------------------------------------------------------
    // 2. Layout XML dialog không còn Paddle view/id nào
    // -------------------------------------------------------------------------

    @Test
    fun testDialogLayout_doesNotContainPaddleViews() {
        val root = File(System.getProperty("user.dir") ?: ".")
        val path = "src/main/res/layout/dialog_ocr_engine_selection.xml"
        val layoutFile = if (File(root, path).exists()) File(root, path) else File(root, "app/$path")
        assertTrue("Tệp layout phải tồn tại", layoutFile.exists())
        val content = layoutFile.readText()

        // Không còn view Paddle
        assertFalse("Layout XML không được chứa layout_engine_paddle", content.contains("layout_engine_paddle"))
        assertFalse("Layout XML không được chứa iv_check_paddle", content.contains("iv_check_paddle"))
        assertFalse("Layout XML không được chứa ocr_engine_paddle", content.contains("@string/ocr_engine_paddle"))

        // Vẫn giữ đầy đủ 3 option: Auto, Tesseract, ML Kit
        assertTrue("Layout XML phải chứa layout_engine_auto", content.contains("layout_engine_auto"))
        assertTrue("Layout XML phải chứa layout_engine_tesseract", content.contains("layout_engine_tesseract"))
        assertTrue("Layout XML phải chứa layout_engine_mlkit", content.contains("layout_engine_mlkit"))
        assertTrue("Layout XML phải chứa iv_check_auto", content.contains("iv_check_auto"))
        assertTrue("Layout XML phải chứa iv_check_tesseract", content.contains("iv_check_tesseract"))
        assertTrue("Layout XML phải chứa iv_check_mlkit", content.contains("iv_check_mlkit"))
    }

    // -------------------------------------------------------------------------
    // 3. OcrLanguageAdapter badge cho tiếng Trung là ML Kit
    // -------------------------------------------------------------------------

    @Test
    fun testOcrLanguageAdapter_engineBadges() {
        val viLang = OcrDocumentLanguage(tag = "vi", nativeName = "Tiếng Việt", vietnameseName = "Tiếng Việt", englishName = "Vietnamese", flag = "🇻🇳")
        val enLang = OcrDocumentLanguage(tag = "en", nativeName = "English", vietnameseName = "Tiếng Anh", englishName = "English", flag = "🇺🇸")
        val zhHans = OcrDocumentLanguage(tag = "zh-Hans", nativeName = "简体中文", vietnameseName = "Tiếng Trung (Giản thể)", englishName = "Chinese (Simplified)", flag = "🇨🇳")
        val zhHant = OcrDocumentLanguage(tag = "zh-Hant", nativeName = "繁體中文", vietnameseName = "Tiếng Trung (Phồn thể)", englishName = "Chinese (Traditional)", flag = "🇹🇼")
        val jaLang = OcrDocumentLanguage(tag = "ja", nativeName = "日本語", vietnameseName = "Tiếng Nhật", englishName = "Japanese", flag = "🇯🇵")
        val frLang = OcrDocumentLanguage(tag = "fr", nativeName = "Français", vietnameseName = "Tiếng Pháp", englishName = "French", flag = "🇫🇷")

        assertEquals("Tesseract / ML Kit", OcrLanguageAdapter.getEngineBadge(viLang))
        assertEquals("Tesseract / ML Kit", OcrLanguageAdapter.getEngineBadge(enLang))

        // Tiếng Trung trước đây hiển thị 'Paddle / ML Kit', nay phải hiển thị 'ML Kit'
        assertEquals("ML Kit", OcrLanguageAdapter.getEngineBadge(zhHans))
        assertEquals("ML Kit", OcrLanguageAdapter.getEngineBadge(zhHant))

        assertEquals("ML Kit", OcrLanguageAdapter.getEngineBadge(jaLang))
        assertEquals("ML Kit", OcrLanguageAdapter.getEngineBadge(frLang))
    }

    // -------------------------------------------------------------------------
    // 4. Engine lịch sử 'paddle' vẫn hiển thị nhãn đẹp cho tài liệu cũ
    // -------------------------------------------------------------------------

    @Test
    fun testLegacyPaddleEngineDisplayName_resolvedProperly() {
        val displayName = TextRecognitionHelper.getEngineDisplayName(dummyContext, "paddle")
        assertTrue("DisplayName cho engine paddle lịch sử phải chứa Paddle", displayName.contains("Paddle"))

        val formattedMetadata = TextRecognitionHelper.formatEngineMetadata(dummyContext, "paddle", "zh-Hans", false)
        assertTrue("Metadata lịch sử cho paddle phải được định dạng thành công", formattedMetadata.isNotBlank())
        assertTrue("Metadata lịch sử phải chứa Paddle", formattedMetadata.contains("Paddle"))
    }

    // -------------------------------------------------------------------------
    // 5. Quy tắc tương thích engine cho tiếng Trung và các ngôn ngữ khác
    // -------------------------------------------------------------------------

    @Test
    fun testEngineCompatibility_chineseOnlyAllowsAutoAndMlKit() {
        val zhType = OcrType.MLKIT_CHINESE
        assertTrue(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_AUTO, zhType))
        assertTrue(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_MLKIT, zhType))
        assertFalse(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_TESSERACT, zhType))
        assertFalse(OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_PADDLE, zhType))

        val zhEngines = OcrRoutingResolver.getSupportedEngineModesForLanguage("zh-Hans")
        assertEquals(listOf("auto", "mlkit"), zhEngines)
        assertFalse(zhEngines.contains("paddle"))
    }

    // -------------------------------------------------------------------------
    // 6. Dialog mở sau preference cũ 'paddle' tự động chuyển sang Auto
    // -------------------------------------------------------------------------

    @Test
    fun testDialogSelectionAfterLegacyPaddlePreference() {
        // Cài đặt giả lập trước đó người dùng lưu 'paddle'
        fakePrefs.edit().putString("key_preferred_ocr_engine", "paddle").apply()

        // Khi truy vấn preferred engine, P01 migration đảm bảo trả về AUTO
        val engine = TextRecognitionHelper.getPreferredEngine(fakePrefs)
        assertEquals(TextRecognitionHelper.ENGINE_MODE_AUTO, engine)

        // Khẳng định ivCheckAuto sẽ được hiển thị và không còn checkmark nào cho paddle
        val isAutoSelected = (engine == TextRecognitionHelper.ENGINE_MODE_AUTO)
        val isTessSelected = (engine == TextRecognitionHelper.ENGINE_MODE_TESSERACT)
        val isMlkitSelected = (engine == TextRecognitionHelper.ENGINE_MODE_MLKIT)

        assertTrue(isAutoSelected)
        assertFalse(isTessSelected)
        assertFalse(isMlkitSelected)
    }

    // -------------------------------------------------------------------------
    // Test double: DummyContext
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
