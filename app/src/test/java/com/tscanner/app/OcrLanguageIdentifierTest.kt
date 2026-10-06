package com.tscanner.app

import com.tscanner.app.utils.OcrDetectionStatus
import com.tscanner.app.utils.OcrLanguageIdentifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrLanguageIdentifierTest {

    @Test
    fun testHasVietnameseDiacritics() {
        assertTrue(OcrLanguageIdentifier.hasVietnameseDiacritics("Cộng hòa Xã hội Chủ nghĩa Việt Nam"))
        assertTrue(OcrLanguageIdentifier.hasVietnameseDiacritics("Độc lập Tự do Hạnh phúc"))
        assertTrue(OcrLanguageIdentifier.hasVietnameseDiacritics("tiếng việt có dấu"))
        assertTrue(OcrLanguageIdentifier.hasVietnameseDiacritics("giấy phép lái xe"))

        assertFalse(OcrLanguageIdentifier.hasVietnameseDiacritics("Hello World"))
        assertFalse(OcrLanguageIdentifier.hasVietnameseDiacritics("The quick brown fox jumps"))
        assertFalse(OcrLanguageIdentifier.hasVietnameseDiacritics("tieng viet khong dau"))
        assertFalse(OcrLanguageIdentifier.hasVietnameseDiacritics("12345 67890"))
    }

    @Test
    fun testSynthesizeDetectionResult_confidentVietnamese() {
        val viText = "Cộng hòa Xã hội Chủ nghĩa Việt Nam\nĐộc lập - Tự do - Hạnh phúc\nĐơn xin xác nhận cư trú"
        val result = OcrLanguageIdentifier.synthesizeDetectionResult(
            text = viText,
            paragraphLanguages = listOf("vi")
        )
        assertEquals(OcrDetectionStatus.CONFIDENT, result.status)
        assertEquals(listOf("vi"), result.detectedLanguages)
    }

    @Test
    fun testSynthesizeDetectionResult_confidentEnglish() {
        val enText = "The quick brown fox jumps over the lazy dog. In the morning they have to think about work and time with people."
        val result = OcrLanguageIdentifier.synthesizeDetectionResult(
            text = enText,
            paragraphLanguages = listOf("en")
        )
        assertEquals(OcrDetectionStatus.CONFIDENT, result.status)
        assertEquals(listOf("en"), result.detectedLanguages)
    }

    @Test
    fun testSynthesizeDetectionResult_mixedBilingualVietnameseEnglish() {
        val bilingualText = """
            Cộng hòa Xã hội Chủ nghĩa Việt Nam
            Độc lập - Tự do - Hạnh phúc

            This agreement is made and entered into by and between the parties.
            All terms and conditions will remain valid for two years.
        """.trimIndent()

        val result = OcrLanguageIdentifier.synthesizeDetectionResult(
            text = bilingualText,
            paragraphLanguages = listOf("vi", "en")
        )
        assertEquals("Tài liệu song ngữ phải phát hiện MIXED_BILINGUAL", OcrDetectionStatus.MIXED_BILINGUAL, result.status)
        assertEquals(listOf("vi", "en"), result.detectedLanguages)
    }

    @Test
    fun testSynthesizeDetectionResult_shortString_uncertain() {
        // Chuỗi quá ngắn (< 15 ký tự) -> UNCERTAIN
        val short1 = "ABC 123"
        val short2 = "Xin chào"
        val res1 = OcrLanguageIdentifier.synthesizeDetectionResult(short1)
        val res2 = OcrLanguageIdentifier.synthesizeDetectionResult(short2)

        assertEquals(OcrDetectionStatus.UNCERTAIN, res1.status)
        assertEquals(OcrDetectionStatus.UNCERTAIN, res2.status)
        assertTrue(res1.detectedLanguages.isEmpty())
        assertTrue(res2.detectedLanguages.isEmpty())
    }

    @Test
    fun testSynthesizeDetectionResult_mostlyNumbersAndSymbols_uncertain() {
        // Hoá đơn / số liệu / bảng biểu không có đủ chữ cái
        val tableNumbers = "12345 67890 998822 09/2026 $5,432.10 #4432 @"
        val res = OcrLanguageIdentifier.synthesizeDetectionResult(tableNumbers)

        assertEquals(OcrDetectionStatus.UNCERTAIN, res.status)
        assertTrue(res.detectedLanguages.isEmpty())
    }

    @Test
    fun testSynthesizeDetectionResult_unaccentedVietnamese_notAssignedToEnglish() {
        // Tiếng Việt không dấu: quy tắc R06 cấm tự động gán nhãn Anh chỉ vì không có dấu Việt
        val unaccentedVi = "nguyen van an tran thi mai ha noi ho chi minh viet nam"
        val res = OcrLanguageIdentifier.synthesizeDetectionResult(unaccentedVi)

        assertEquals("Tiếng Việt không dấu không được tự gán nhãn English", OcrDetectionStatus.UNCERTAIN, res.status)
        assertTrue(res.detectedLanguages.isEmpty())
    }

    @Test
    fun testSynthesizeDetectionResult_otherLanguages() {
        val jaText = "これは日本語のテキストです。文字認識のテストを行います。"
        val resJa = OcrLanguageIdentifier.synthesizeDetectionResult(
            text = jaText,
            paragraphLanguages = listOf("ja")
        )
        assertEquals(OcrDetectionStatus.CONFIDENT, resJa.status)
        assertEquals(listOf("ja"), resJa.detectedLanguages)
    }

    @Test
    fun testSynthesizeDetectionResult_frenchAccents_notMisclassifiedAsVietnamese() {
        // F02: Dấu tiếng Pháp (café, déjà, fermé, journée) không được tự gán thành tiếng Việt
        val frText = "Le café est déjà fermé pour la journée. Veuillez revenir demain matin."
        val res = OcrLanguageIdentifier.synthesizeDetectionResult(
            text = frText,
            paragraphLanguages = listOf("fr")
        )
        assertEquals("Tiếng Pháp có dấu không được gán thành vi", OcrDetectionStatus.CONFIDENT, res.status)
        assertEquals(listOf("fr"), res.detectedLanguages)
        assertTrue("Confidence phải là giá trị đo động > 0.65", res.confidence >= 0.65f)
    }

    @Test
    fun testSynthesizeDetectionResult_spanishAccents_notMisclassifiedAsVietnamese() {
        // F02: Dấu tiếng Tây Ban Nha (información, educación, atención) không được gán vi
        val esText = "Información sobre educación y atención al cliente en la oficina central."
        val res = OcrLanguageIdentifier.synthesizeDetectionResult(
            text = esText,
            paragraphLanguages = listOf("es")
        )
        assertEquals("Tiếng Tây Ban Nha có dấu không được gán thành vi", OcrDetectionStatus.CONFIDENT, res.status)
        assertEquals(listOf("es"), res.detectedLanguages)
    }

    @Test
    fun testSynthesizeDetectionResult_portugueseAccents_notMisclassifiedAsVietnamese() {
        // F02: Dấu tiếng Bồ Đào Nha (atenção, reunião, produção) không được gán vi
        val ptText = "Atenção às informações da reunião de amanhã sobre a nova produção."
        val res = OcrLanguageIdentifier.synthesizeDetectionResult(
            text = ptText,
            paragraphLanguages = listOf("pt")
        )
        assertEquals("Tiếng Bồ Đào Nha có dấu không được gán thành vi", OcrDetectionStatus.CONFIDENT, res.status)
        assertEquals(listOf("pt"), res.detectedLanguages)
    }

    @Test
    fun testSplitIntoParagraphs_handlesStandardNewlinesAndMultipleEmptyLines() {
        // F03: Kiểm tra tách đoạn thuần với \n, \r\n và nhiều dòng trống
        val multilineText = "Đoạn văn đầu tiên có độ dài đầy đủ hơn hai mươi ký tự.\n\nĐoạn văn thứ hai cũng có độ dài đầy đủ hơn hai mươi ký tự.\r\n\r\nĐoạn văn thứ ba dài đủ để thỏa mãn điều kiện lọc."
        val paragraphs = OcrLanguageIdentifier.splitIntoParagraphs(multilineText)
        assertEquals(3, paragraphs.size)
        assertTrue(paragraphs[0].startsWith("Đoạn văn đầu tiên"))
        assertTrue(paragraphs[1].startsWith("Đoạn văn thứ hai"))
        assertTrue(paragraphs[2].startsWith("Đoạn văn thứ ba"))
    }
}
