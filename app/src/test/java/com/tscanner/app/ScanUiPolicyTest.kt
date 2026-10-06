package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.tscanner.app.utils.ScanTarget
import com.tscanner.app.utils.ScanUiPolicy
import com.tscanner.app.utils.ScannerUserPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ScanUiPolicyTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var testContext: TestContext

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        testContext = TestContext(fakePrefs)
    }

    @Test
    fun defaultScanTarget_underDirectionB_isGoogleAi() {
        // Hợp đồng Hướng B: Mặc định luồng quét tài liệu và quét thẻ là Google AI Scanner
        assertEquals(ScanTarget.GOOGLE_AI, ScanUiPolicy.DEFAULT_SCAN_TARGET)
        assertEquals(ScanTarget.GOOGLE_AI, ScanUiPolicy.resolveDocumentScanTarget(ScannerUserPreference.AUTOMATIC))
        assertEquals(ScanTarget.GOOGLE_AI, ScanUiPolicy.resolveIdCardScanTarget(ScannerUserPreference.AUTOMATIC))
    }

    @Test
    fun explicitUserPreference_overridesDefault() {
        // Khi người dùng chủ động chọn Camera nội bộ
        assertEquals(
            ScanTarget.INTERNAL_CAMERA,
            ScanUiPolicy.resolveDocumentScanTarget(ScannerUserPreference.INTERNAL_CAMERA)
        )
        assertEquals(
            ScanTarget.INTERNAL_CAMERA,
            ScanUiPolicy.resolveIdCardScanTarget(ScannerUserPreference.INTERNAL_CAMERA)
        )

        // Khi người dùng chủ động chọn Google AI
        assertEquals(
            ScanTarget.GOOGLE_AI,
            ScanUiPolicy.resolveDocumentScanTarget(ScannerUserPreference.GOOGLE_AI)
        )
        assertEquals(
            ScanTarget.GOOGLE_AI,
            ScanUiPolicy.resolveIdCardScanTarget(ScannerUserPreference.GOOGLE_AI)
        )
    }

    @Test
    fun alternativeScanTarget_togglesBetweenAvailableScanners() {
        // Nút chuyển đổi giữa hai trình quét hoạt động đối xứng
        assertEquals(ScanTarget.INTERNAL_CAMERA, ScanUiPolicy.getAlternativeTarget(ScanTarget.GOOGLE_AI))
        assertEquals(ScanTarget.GOOGLE_AI, ScanUiPolicy.getAlternativeTarget(ScanTarget.INTERNAL_CAMERA))
    }

    @Test
    fun scannerDescriptors_reflectApprovedContract() {
        val googleAiDesc = ScanUiPolicy.getScannerDescriptor(ScanTarget.GOOGLE_AI)
        assertEquals(ScanTarget.GOOGLE_AI, googleAiDesc.target)
        assertTrue("Google AI phải được đánh dấu là recommended theo Hướng B", googleAiDesc.isRecommended)
        assertFalse("Google AI không đảm bảo theo app locale do chạy ngoài process", googleAiDesc.isAppLocaleGuaranteed)
        assertTrue("Google AI UI thuộc quyền kiểm soát của Play services/hệ thống", googleAiDesc.isGoogleUiLocaleUncontrolled)
        assertEquals(R.string.scan_option_ai, googleAiDesc.titleResId)
        assertEquals(R.string.scan_option_ai_desc, googleAiDesc.descResId)
        assertEquals(R.string.scan_badge_recommended, googleAiDesc.badgeResId)

        val cameraDesc = ScanUiPolicy.getScannerDescriptor(ScanTarget.INTERNAL_CAMERA)
        assertEquals(ScanTarget.INTERNAL_CAMERA, cameraDesc.target)
        assertFalse("Camera nội bộ là tùy chọn bổ trợ theo Hướng B", cameraDesc.isRecommended)
        assertTrue("Camera nội bộ đảm bảo 100% ngôn ngữ theo app locale", cameraDesc.isAppLocaleGuaranteed)
        assertFalse("Camera nội bộ không bị chi phối bởi Play services", cameraDesc.isGoogleUiLocaleUncontrolled)
        assertEquals(R.string.scan_option_fast, cameraDesc.titleResId)
        assertEquals(R.string.scan_option_fast_desc, cameraDesc.descResId)
        assertEquals(R.string.scan_badge_optional, cameraDesc.badgeResId)
    }

    @Test
    fun localeMismatchRisk_detection() {
        // Phát hiện rủi ro lệch ngôn ngữ khi App Locale khác System Locale
        assertTrue(ScanUiPolicy.isLocaleMismatchRisk("en", "vi"))
        assertTrue(ScanUiPolicy.isLocaleMismatchRisk("ja", "vi"))
        assertTrue(ScanUiPolicy.isLocaleMismatchRisk("fr", "en"))
        assertTrue(ScanUiPolicy.isLocaleMismatchRisk("de", "vi-VN"))

        // Cùng ngôn ngữ cơ bản -> không có rủi ro lệch ngôn ngữ
        assertFalse(ScanUiPolicy.isLocaleMismatchRisk("en", "en"))
        assertFalse(ScanUiPolicy.isLocaleMismatchRisk("en-US", "en-GB"))
        assertFalse(ScanUiPolicy.isLocaleMismatchRisk("vi", "vi-VN"))
        assertFalse(ScanUiPolicy.isLocaleMismatchRisk("pt-BR", "pt"))

        // Null hoặc rỗng -> an toàn
        assertFalse(ScanUiPolicy.isLocaleMismatchRisk(null, "vi"))
        assertFalse(ScanUiPolicy.isLocaleMismatchRisk("en", null))
        assertFalse(ScanUiPolicy.isLocaleMismatchRisk("", ""))
    }

    @Test
    fun policyIndependence_acrossAllSupportedAppLocales() {
        // Kiểm tra độc lập tuyệt đối: chính sách không phụ thuộc vào ngôn ngữ UI của ứng dụng
        val supportedLocales = listOf("en", "vi", "es", "pt", "fr", "id", "de", "ja")
        for (locale in supportedLocales) {
            val target = ScanUiPolicy.resolveDocumentScanTarget(ScannerUserPreference.AUTOMATIC)
            assertEquals("Chính sách tại locale $locale phải nhất quán theo Hướng B", ScanTarget.GOOGLE_AI, target)
        }

        // Locale ngoài danh sách hỗ trợ (fallback)
        val fallbackLocales = listOf("zh", "ko", "ru", "ar")
        for (fallback in fallbackLocales) {
            val target = ScanUiPolicy.resolveDocumentScanTarget(ScannerUserPreference.AUTOMATIC)
            assertEquals("Chính sách tại fallback locale $fallback phải nhất quán", ScanTarget.GOOGLE_AI, target)
        }
    }

    @Test
    fun sharedPreferences_persistenceIntegration() {
        // Mặc định ban đầu chưa cấu hình gì
        assertEquals(ScannerUserPreference.AUTOMATIC, ScanUiPolicy.getPreferredScanner(testContext))
        assertEquals(ScanTarget.GOOGLE_AI, ScanUiPolicy.resolveDocumentScanTarget(testContext))
        assertEquals(ScanTarget.GOOGLE_AI, ScanUiPolicy.resolveIdCardScanTarget(testContext))

        // Lưu cấu hình chọn Camera nội bộ
        ScanUiPolicy.setPreferredScanner(testContext, ScannerUserPreference.INTERNAL_CAMERA)
        assertEquals(ScannerUserPreference.INTERNAL_CAMERA, ScanUiPolicy.getPreferredScanner(testContext))
        assertEquals(ScanTarget.INTERNAL_CAMERA, ScanUiPolicy.resolveDocumentScanTarget(testContext))
        assertEquals(ScanTarget.INTERNAL_CAMERA, ScanUiPolicy.resolveIdCardScanTarget(testContext))

        // Lưu cấu hình chọn Google AI tường minh
        ScanUiPolicy.setPreferredScanner(testContext, ScannerUserPreference.GOOGLE_AI)
        assertEquals(ScannerUserPreference.GOOGLE_AI, ScanUiPolicy.getPreferredScanner(testContext))
        assertEquals(ScanTarget.GOOGLE_AI, ScanUiPolicy.resolveDocumentScanTarget(testContext))
        assertEquals(ScanTarget.GOOGLE_AI, ScanUiPolicy.resolveIdCardScanTarget(testContext))

        // Quay lại AUTOMATIC
        ScanUiPolicy.setPreferredScanner(testContext, ScannerUserPreference.AUTOMATIC)
        assertEquals(ScannerUserPreference.AUTOMATIC, ScanUiPolicy.getPreferredScanner(testContext))
        assertEquals(ScanTarget.GOOGLE_AI, ScanUiPolicy.resolveDocumentScanTarget(testContext))
    }

    // --- Fake Test Context & SharedPreferences ---

    private class TestContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
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
                for (k in removes) {
                    prefs.map.remove(k)
                }
                removes.clear()
                for ((k, v) in pending) {
                    if (v != null) prefs.map[k] = v else prefs.map.remove(k)
                }
                pending.clear()
            }
        }
    }
}
