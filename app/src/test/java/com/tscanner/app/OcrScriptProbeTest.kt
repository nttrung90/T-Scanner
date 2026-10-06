package com.tscanner.app

import com.tscanner.app.utils.OcrScriptProbe
import com.tscanner.app.utils.ScriptFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrScriptProbeTest {

    @Test
    fun testJapaneseWithKana_detectedAsJapanese() {
        // Văn bản có cả Kanji và Hiragana/Katakana
        val japanese = "東京都新宿区のオフィスで働いています。テストデータです。"
        val result = OcrScriptProbe.analyzeTextScript(japanese)

        assertEquals(ScriptFamily.JAPANESE_KANA, result.primaryScript)
        assertEquals("ja", result.recommendedTag)
        assertFalse("Có Kana thì không còn mơ hồ CJK", result.isAmbiguousCjk)
        assertTrue(result.scriptCounts[ScriptFamily.JAPANESE_KANA] ?: 0 > 0)
    }

    @Test
    fun testPureCjkIdeographs_detectedAsAmbiguousCjk() {
        // Chỉ chứa chữ Hán (không có Kana): Có thể là Trung giản thể, phồn thể hoặc biển hiệu Kanji
        val pureHan = "中华人民共和国国家版权局计算机软件著作权登记证书"
        val result = OcrScriptProbe.analyzeTextScript(pureHan)

        assertEquals(ScriptFamily.CJK_IDEOGRAPH, result.primaryScript)
        assertTrue("Thuần Hán tự phải đánh dấu là mơ hồ để người dùng tự chọn", result.isAmbiguousCjk)
        assertNull("Không được tự tiện đoán giản thể hay phồn thể khi chỉ có Hán tự chung", result.recommendedTag)
    }

    @Test
    fun testKoreanHangul_detectedAsKorean() {
        val korean = "대한민국 서울특별시 중구 세종대로"
        val result = OcrScriptProbe.analyzeTextScript(korean)

        assertEquals(ScriptFamily.HANGUL, result.primaryScript)
        assertEquals("ko", result.recommendedTag)
        assertFalse(result.isAmbiguousCjk)
    }

    @Test
    fun testDevanagari_detectedAsHindi() {
        val hindi = "नमस्ते भारत। यह एक परीक्षण दस्तावेज़ है।"
        val result = OcrScriptProbe.analyzeTextScript(hindi)

        assertEquals(ScriptFamily.DEVANAGARI, result.primaryScript)
        assertEquals("hi", result.recommendedTag)
        assertFalse(result.isAmbiguousCjk)
    }

    @Test
    fun testLatinText_detectedAsLatin() {
        val english = "Hello World! This is an optical character recognition test."
        val result = OcrScriptProbe.analyzeTextScript(english)

        assertEquals(ScriptFamily.LATIN, result.primaryScript)
        assertFalse(result.isAmbiguousCjk)
    }

    @Test
    fun testNumbersAndSymbols_detectedAsUnknown() {
        val numbers = "12345 67890 + - = % $ # @ !"
        val result = OcrScriptProbe.analyzeTextScript(numbers)

        assertEquals(ScriptFamily.UNKNOWN, result.primaryScript)
        assertEquals(0, result.totalScriptCharacters)
    }
}
