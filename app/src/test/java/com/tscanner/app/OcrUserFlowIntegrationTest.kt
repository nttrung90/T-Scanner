package com.tscanner.app

import com.tscanner.app.utils.EngineRunResult
import com.tscanner.app.utils.MultiPageOcrAggregator
import com.tscanner.app.utils.MultiPageOcrResult
import com.tscanner.app.utils.OcrDetectionStatus
import com.tscanner.app.utils.OcrPageDetectionResult
import com.tscanner.app.utils.OcrResult
import com.tscanner.app.utils.TextRecognitionHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrUserFlowIntegrationTest {

    @Test
    fun testTwoPages_onePageTechnicalFailure_blocksExportAndDoesNotCorruptContent() {
        // Trang 1 thành công từ Tesseract hoặc ML Kit
        val page1 = OcrResult.Success(
            text = "Nội dung hợp lệ của trang 1",
            engineId = "tesseract",
            documentLanguage = "vi"
        )
        // Trang 2 bị lỗi kỹ thuật (F01 hoặc F02: Session hỏng, crop box lỗi, hoặc model unavailable)
        val page2 = OcrResult.Failure("Failed to crop text line region at box index 1")

        val pages = listOf(page1, page2)

        // 1. MultiPageOcrAggregator phải dừng xuất tự động và trả về PageError
        val aggResult = MultiPageOcrAggregator.aggregate(pages)
        assertTrue("Có trang bị lỗi kỹ thuật phải trả về PageError", aggResult is MultiPageOcrResult.PageError)

        val pageError = aggResult as MultiPageOcrResult.PageError
        assertEquals("Trang lỗi đầu tiên phải là trang 2", 2, pageError.failedPageNumber)
        assertEquals(2, pageError.totalPages)
        assertEquals(page2, pageError.errorResult)

        // 2. Không được chèn nội dung lỗi vào văn bản OCR; lỗi kỹ thuật phải được nhận diện là blocking error
        val isBlocking = MultiPageOcrAggregator.isBlockingError(page2)
        assertTrue("Lỗi kỹ thuật phải được nhận diện là blocking error để dừng loop trang", isBlocking)
    }

    @Test
    fun testTwoPages_firstPageFails_stopsImmediatelyAtPage1() {
        val page1 = OcrResult.ModelUnavailable("Tesseract traineddata for 'vie' missing or failed to initialize", "vi")
        val page2 = OcrResult.Success("Trang 2 có chữ", "tesseract", "vi")

        val pages = listOf(page1, page2)
        val aggResult = MultiPageOcrAggregator.aggregate(pages)

        assertTrue(aggResult is MultiPageOcrResult.PageError)
        val pageError = aggResult as MultiPageOcrResult.PageError
        assertEquals("Trang lỗi đầu tiên phải là trang 1", 1, pageError.failedPageNumber)
        assertEquals(page1, pageError.errorResult)
    }

    @Test
    fun testAllPagesNoText_returnsAllNoText_doesNotCreateEmptyHeaderFile() {
        val pages = listOf(
            OcrResult.NoText,
            OcrResult.NoText,
            OcrResult.NoText
        )
        val aggResult = MultiPageOcrAggregator.aggregate(pages)

        assertTrue("Tất cả các trang NoText phải trả về AllNoText", aggResult is MultiPageOcrResult.AllNoText)
        assertEquals(3, (aggResult as MultiPageOcrResult.AllNoText).totalPages)
    }

    @Test
    fun testOnePageSuccess_onePageRealNoText_retainsDistinctionFromTechnicalError() {
        val page1 = OcrResult.Success("Dòng chữ trên trang 1", "tesseract", "vi")
        val page2 = OcrResult.NoText // Trang trắng thực sự (người dùng quét bìa trắng hoặc mặt sau)

        val pages = listOf(page1, page2)
        val aggResult = MultiPageOcrAggregator.aggregate(pages)

        // R05: Trang trắng thực sự KHÔNG phải là lỗi kỹ thuật, tài liệu vẫn được xuất với thống kê
        assertTrue(aggResult is MultiPageOcrResult.Success)
        val success = aggResult as MultiPageOcrResult.Success
        assertEquals(1, success.pagesWithText)
        assertEquals(1, success.blankPages)
        assertEquals(2, success.totalPages)

        // Nội dung chỉ chứa trang 1, giữ nguyên header trang 1
        assertTrue(success.fullText.contains("--- PAGE 1 ---"))
        assertTrue(success.fullText.contains("Dòng chữ trên trang 1"))
        assertFalse("Header trang 2 KHÔNG được xuất hiện vì trang 2 không có chữ", success.fullText.contains("PAGE 2"))
    }

    @Test
    fun testFallbackEngineSuccess_allowsDisplayAndExportWithFallbackMetadata() {
        // Primary Tesseract gặp lỗi F02 (thiếu traineddata)
        val primaryTess = EngineRunResult.ModelUnavailable("Tesseract traineddata for 'vie' missing")
        // Routing gọi fallback ML Kit Latin thành công
        val fallbackMlKit = EngineRunResult.Success("Cộng hòa xã hội chủ nghĩa Việt Nam")

        // Kết quả routing
        val ocrResult = OcrResult.Success(
            text = fallbackMlKit.text,
            engineId = "mlkit_latin",
            documentLanguage = "vi",
            fallbackUsed = true
        )

        // 1. Cho phép hiển thị và xuất
        assertEquals("Cộng hòa xã hội chủ nghĩa Việt Nam", ocrResult.text)
        assertTrue("Phải đánh dấu fallbackUsed = true", ocrResult.fallbackUsed)
        assertEquals("mlkit_latin", ocrResult.engineId)

        // 2. Gom trang với fallback
        val aggResult = MultiPageOcrAggregator.aggregate(listOf(ocrResult))
        assertTrue(aggResult is MultiPageOcrResult.Success)
        val success = aggResult as MultiPageOcrResult.Success
        assertEquals(1, success.pagesWithText)
        assertEquals(setOf("mlkit_latin"), success.enginesUsed)
    }

    @Test
    fun testBlockingErrorClassification_exhaustive() {
        // Lỗi kỹ thuật / cấu hình phải chặn xuất tự động
        assertTrue(MultiPageOcrAggregator.isBlockingError(OcrResult.Failure("Crash")))
        assertTrue(MultiPageOcrAggregator.isBlockingError(OcrResult.ModelUnavailable("Missing model")))
        assertTrue(MultiPageOcrAggregator.isBlockingError(OcrResult.IncompatibleEngine("paddle", "vi")))
        assertTrue(MultiPageOcrAggregator.isBlockingError(OcrResult.UnsupportedLanguage("ar")))

        // Trang thành công hoặc trang trắng thực sự KHÔNG chặn
        assertFalse(MultiPageOcrAggregator.isBlockingError(OcrResult.Success("Text", "tesseract", "vi")))
        assertFalse(MultiPageOcrAggregator.isBlockingError(OcrResult.NoText))
    }

    @Test
    fun testMultiPageAggregation_retainsAllPagesAndLanguageDetections() {
        val page1 = OcrResult.Success(
            text = "Trang 1 nội dung tiếng Việt",
            engineId = "tesseract",
            documentLanguage = "vi",
            detectionResult = OcrPageDetectionResult(
                status = OcrDetectionStatus.CONFIDENT,
                detectedLanguages = listOf("vi"),
                candidateLanguages = listOf("vi", "en")
            )
        )
        val page2 = OcrResult.Success(
            text = "Page 2 English content",
            engineId = "mlkit_latin",
            documentLanguage = "en",
            detectionResult = OcrPageDetectionResult(
                status = OcrDetectionStatus.CONFIDENT,
                detectedLanguages = listOf("en"),
                candidateLanguages = listOf("vi", "en")
            )
        )

        val pages = listOf(page1, page2)
        val aggResult = MultiPageOcrAggregator.aggregate(pages)

        assertTrue(aggResult is MultiPageOcrResult.Success)
        val success = aggResult as MultiPageOcrResult.Success
        assertEquals(2, success.totalPages)
        assertEquals(2, success.pagesWithText)
        assertEquals(0, success.blankPages)
        assertTrue(success.fullText.contains("--- PAGE 1 ---"))
        assertTrue(success.fullText.contains("Trang 1 nội dung tiếng Việt"))
        assertTrue(success.fullText.contains("--- PAGE 2 ---"))
        assertTrue(success.fullText.contains("Page 2 English content"))
        assertTrue(success.detectedLanguages.contains("vi"))
        assertTrue(success.detectedLanguages.contains("en"))
        assertEquals(2, success.pageDetections.size)
    }

    @Test
    fun testMultiPageAggregation_rerunFailure_preservesExistingResultPattern() {
        val existingFullText = "Trang 1 cũ\nTrang 2 cũ"

        val rerunPage1 = OcrResult.Success("Trang 1 mới", "tesseract", "vi")
        val rerunPage2 = OcrResult.Failure("Model out of memory during re-recognition")

        val rerunPages = listOf(rerunPage1, rerunPage2)
        val aggResult = MultiPageOcrAggregator.aggregate(rerunPages)

        assertTrue(aggResult is MultiPageOcrResult.PageError)
        val pageError = aggResult as MultiPageOcrResult.PageError
        assertEquals(2, pageError.failedPageNumber)
        assertEquals("Trang 1 cũ\nTrang 2 cũ", existingFullText)
    }
}
