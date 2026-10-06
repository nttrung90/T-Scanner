package com.tscanner.app

import com.tscanner.app.utils.ScanTarget
import com.tscanner.app.utils.ScanUiPolicy
import com.tscanner.app.utils.ScannerUserPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Kiểm thử hồi quy cho Gói L02:
 * 1. Xác thực tài nguyên ngôn ngữ giải thích giới hạn SDK (8 ngôn ngữ).
 * 2. Xác thực cấu trúc layout dialog_scan_options và dialog_id_card_options.
 * 3. Xác thực hợp đồng routing cho các cửa vào FAB, Home, Files.
 */
class ScanEntryLanguageRegressionTest {

    private val supportedLocales = listOf(
        "values",
        "values-vi",
        "values-es",
        "values-pt",
        "values-fr",
        "values-in",
        "values-de",
        "values-ja"
    )

    @Test
    fun testAllEightLocales_containScanLanguageNotes() {
        val resDir = File("src/main/res")
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()

        for (localeDirName in supportedLocales) {
            val stringsFile = File(resDir, "$localeDirName/strings.xml")
            assertTrue("File ${stringsFile.path} phải tồn tại", stringsFile.exists())

            val doc = builder.parse(stringsFile)
            val stringNodes = doc.getElementsByTagName("string")

            var hasGoogleAiNote = false
            var hasFastCameraNote = false
            var googleAiNoteContent = ""
            var fastCameraNoteContent = ""

            for (i in 0 until stringNodes.length) {
                val element = stringNodes.item(i) as Element
                val name = element.getAttribute("name")
                if (name == "scan_google_ai_language_note") {
                    hasGoogleAiNote = true
                    googleAiNoteContent = element.textContent.trim()
                } else if (name == "scan_fast_camera_language_note") {
                    hasFastCameraNote = true
                    fastCameraNoteContent = element.textContent.trim()
                }
            }

            assertTrue(
                "[$localeDirName] Phải chứa khóa scan_google_ai_language_note",
                hasGoogleAiNote
            )
            assertTrue(
                "[$localeDirName] Khóa scan_google_ai_language_note không được rỗng",
                googleAiNoteContent.isNotEmpty()
            )

            assertTrue(
                "[$localeDirName] Phải chứa khóa scan_fast_camera_language_note",
                hasFastCameraNote
            )
            assertTrue(
                "[$localeDirName] Khóa scan_fast_camera_language_note không được rỗng",
                fastCameraNoteContent.isNotEmpty()
            )
        }
    }

    @Test
    fun testDialogLayouts_containLanguageNoteViews() {
        val resDir = File("src/main/res/layout")

        // 1. dialog_scan_options.xml
        val scanOptionsFile = File(resDir, "dialog_scan_options.xml")
        assertTrue(scanOptionsFile.exists())
        val scanOptionsText = scanOptionsFile.readText()
        assertTrue(
            "dialog_scan_options.xml phải chứa tv_scan_option_ai_lang_note",
            scanOptionsText.contains("tv_scan_option_ai_lang_note")
        )
        assertTrue(
            "dialog_scan_options.xml phải chứa tv_scan_option_fast_lang_note",
            scanOptionsText.contains("tv_scan_option_fast_lang_note")
        )
        assertTrue(
            "dialog_scan_options.xml phải tham chiếu @string/scan_google_ai_language_note",
            scanOptionsText.contains("@string/scan_google_ai_language_note")
        )
        assertTrue(
            "dialog_scan_options.xml phải tham chiếu @string/scan_fast_camera_language_note",
            scanOptionsText.contains("@string/scan_fast_camera_language_note")
        )

        // 2. dialog_id_card_options.xml
        val idCardOptionsFile = File(resDir, "dialog_id_card_options.xml")
        assertTrue(idCardOptionsFile.exists())
        val idCardOptionsText = idCardOptionsFile.readText()
        assertTrue(
            "dialog_id_card_options.xml phải tham chiếu @string/scan_google_ai_language_note",
            idCardOptionsText.contains("@string/scan_google_ai_language_note")
        )
        assertTrue(
            "dialog_id_card_options.xml phải tham chiếu @string/scan_fast_camera_language_note",
            idCardOptionsText.contains("@string/scan_fast_camera_language_note")
        )
    }

    @Test
    fun testRoutingPolicy_underDirectionB_maintainsGoogleAiDefault() {
        // Mặc định luôn là Google AI theo Hướng B
        assertEquals(ScanTarget.GOOGLE_AI, ScanUiPolicy.resolveDocumentScanTarget(ScannerUserPreference.AUTOMATIC))
        assertEquals(ScanTarget.GOOGLE_AI, ScanUiPolicy.resolveIdCardScanTarget(ScannerUserPreference.AUTOMATIC))

        // Có đường mở Camera nội bộ tường minh khi người dùng yêu cầu
        assertEquals(
            ScanTarget.INTERNAL_CAMERA,
            ScanUiPolicy.resolveDocumentScanTarget(ScannerUserPreference.INTERNAL_CAMERA)
        )
        assertEquals(
            ScanTarget.INTERNAL_CAMERA,
            ScanUiPolicy.resolveIdCardScanTarget(ScannerUserPreference.INTERNAL_CAMERA)
        )
    }
}
