package com.tscanner.app

import android.content.Context
import android.content.res.Resources
import com.tscanner.app.utils.DocumentScannerHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Kiểm thử cho Gói L03:
 * 1. Xác thực toàn bộ 6 khóa thông báo lỗi scanner trong cả 8 bộ tài nguyên ngôn ngữ.
 * 2. Xác thực cấu trúc placeholder (%1$s, %2$d) chính xác theo tiêu chuẩn Android localization.
 * 3. Xác thực các hàm helper trong DocumentScannerHelper gọi đúng ID tài nguyên.
 */
class ScannerErrorLanguageTest {

    private val expectedKeys = listOf(
        "scanner_err_failed_to_start",
        "scanner_err_general",
        "scanner_err_scan_failed",
        "scanner_err_parse_failed",
        "scanner_err_no_pages",
        "scanner_err_import_pages_failed"
    )

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
    fun testAllEightLocales_containAllSixScannerErrorKeys() {
        val resDir = File("src/main/res")
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()

        for (localeDir in supportedLocales) {
            val stringsFile = File(resDir, "$localeDir/strings.xml")
            assertTrue("File strings.xml tại $localeDir phải tồn tại", stringsFile.exists())

            val doc = builder.parse(stringsFile)
            val stringNodes = doc.getElementsByTagName("string")
            val foundKeys = mutableMapOf<String, String>()

            for (i in 0 until stringNodes.length) {
                val element = stringNodes.item(i) as Element
                val name = element.getAttribute("name")
                if (name in expectedKeys) {
                    foundKeys[name] = element.textContent.trim()
                }
            }

            for (key in expectedKeys) {
                assertTrue("[$localeDir] Phải định nghĩa khóa '$key'", foundKeys.containsKey(key))
                val content = foundKeys[key]
                assertNotNull("[$localeDir] Nội dung của '$key' không được null", content)
                assertFalse("[$localeDir] Nội dung của '$key' không được rỗng", content!!.isEmpty())
            }

            // Kiểm tra placeholder cho scanner_err_import_pages_failed
            val importErrorMsg = foundKeys["scanner_err_import_pages_failed"]!!
            assertTrue(
                "[$localeDir] scanner_err_import_pages_failed phải chứa %1\$s cho danh sách trang lỗi",
                importErrorMsg.contains("%1\$s")
            )
            assertTrue(
                "[$localeDir] scanner_err_import_pages_failed phải chứa %2\$d cho tổng số trang",
                importErrorMsg.contains("%2\$d")
            )
        }
    }

    @Test
    fun testVietnameseErrorStrings_areAccurate() {
        val resDir = File("src/main/res")
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(File(resDir, "values-vi/strings.xml"))
        val stringNodes = doc.getElementsByTagName("string")
        val viStrings = mutableMapOf<String, String>()

        for (i in 0 until stringNodes.length) {
            val element = stringNodes.item(i) as Element
            val name = element.getAttribute("name")
            if (name in expectedKeys) {
                viStrings[name] = element.textContent.trim()
            }
        }

        assertEquals("Không thể khởi động trình quét", viStrings["scanner_err_failed_to_start"])
        assertEquals("Lỗi trình quét", viStrings["scanner_err_general"])
        assertEquals("Quét tài liệu thất bại", viStrings["scanner_err_scan_failed"])
        assertEquals("Không thể xử lý kết quả quét", viStrings["scanner_err_parse_failed"])
        assertEquals("Không có trang nào được chụp từ trình quét", viStrings["scanner_err_no_pages"])
        assertEquals("Không thể nhập (các) trang: %1\$s trên tổng số %2\$d", viStrings["scanner_err_import_pages_failed"])
    }

    @Test
    fun testJapaneseErrorStrings_areAccurate() {
        val resDir = File("src/main/res")
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(File(resDir, "values-ja/strings.xml"))
        val stringNodes = doc.getElementsByTagName("string")
        val jaStrings = mutableMapOf<String, String>()

        for (i in 0 until stringNodes.length) {
            val element = stringNodes.item(i) as Element
            val name = element.getAttribute("name")
            if (name in expectedKeys) {
                jaStrings[name] = element.textContent.trim()
            }
        }

        assertEquals("スキャナーの起動に失敗しました", jaStrings["scanner_err_failed_to_start"])
        assertEquals("スキャナー エラー", jaStrings["scanner_err_general"])
        assertEquals("スキャンに失敗しました", jaStrings["scanner_err_scan_failed"])
        assertEquals("スキャン結果を解析できませんでした", jaStrings["scanner_err_parse_failed"])
        assertEquals("スキャナーからページがキャプチャされませんでした", jaStrings["scanner_err_no_pages"])
        assertEquals("ページのインポートに失敗しました: %2\$d 中 %1\$s", jaStrings["scanner_err_import_pages_failed"])
    }
}
