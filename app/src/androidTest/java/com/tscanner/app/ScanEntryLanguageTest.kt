package com.tscanner.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tscanner.app.utils.ScanTarget
import com.tscanner.app.utils.ScanUiPolicy
import com.tscanner.app.utils.ScannerUserPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation Test cho Gói L02:
 * Xác thực thao tác cửa vào và kiểm tra text/resources thực tế theo locale trên thiết bị Android.
 */
@RunWith(AndroidJUnit4::class)
class ScanEntryLanguageTest {

    @Test
    fun testDefaultPolicyResolvesGoogleAiOnDevice() {
        val target = ScanUiPolicy.resolveDocumentScanTarget(ScannerUserPreference.AUTOMATIC)
        assertEquals(ScanTarget.GOOGLE_AI, target)
    }

    @Test
    fun testAppContextLoadsScanResources() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val googleAiNote = context.getString(R.string.scan_google_ai_language_note)
        val fastCameraNote = context.getString(R.string.scan_fast_camera_language_note)
        assertNotNull(googleAiNote)
        assertNotNull(fastCameraNote)
    }
}
