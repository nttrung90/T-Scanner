package com.tscanner.app

import com.tscanner.app.ocr.model.*
import com.tscanner.app.ui.ocr.reader.OcrSelectionController
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for OcrSelectionController:
 * - Token and line hit-testing
 * - Reading order preservation during copy
 * - Unicode case-insensitive search
 * - Line-only engine semantics (no fake word boxes)
 * - Safe behavior when text is edited
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S10).
 */
class OcrSelectionControllerTest {

    @Test
    fun testTokenLevelHitTestingAndToggle() {
        val token1 = OcrToken(
            tokenId = "tok_1",
            text = "HỢP",
            polygon = OcrPolygon(
                listOf(
                    OcrPoint(0.1f, 0.1f),
                    OcrPoint(0.25f, 0.1f),
                    OcrPoint(0.25f, 0.2f),
                    OcrPoint(0.1f, 0.2f)
                )
            )
        )
        val token2 = OcrToken(
            tokenId = "tok_2",
            text = "ĐỒNG",
            polygon = OcrPolygon(
                listOf(
                    OcrPoint(0.26f, 0.1f),
                    OcrPoint(0.45f, 0.1f),
                    OcrPoint(0.45f, 0.2f),
                    OcrPoint(0.26f, 0.2f)
                )
            )
        )
        val line = OcrLine(
            lineId = "line_1",
            text = "HỢP ĐỒNG",
            tokens = listOf(token1, token2)
        )
        val block = OcrBlock(blockId = "b1", lines = listOf(line))
        val page = OcrPage(pageId = "p1", pageIndex = 1, status = OcrPageStatus.SUCCESS, sourceBlocks = listOf(block))

        val controller = OcrSelectionController()
        controller.setPage(page)

        // 1. Tap on tok_1 (0.15, 0.15)
        val hit1 = controller.onSingleTap(0.15f, 0.15f)
        assertTrue("Must hit tok_1", hit1)
        assertTrue(controller.getSelectedTokenIds().contains("tok_1"))
        assertEquals("HỢP", controller.getSelectedText())

        // 2. Tap on tok_2 (0.35, 0.15)
        val hit2 = controller.onSingleTap(0.35f, 0.15f)
        assertTrue("Must hit tok_2", hit2)
        assertTrue(controller.getSelectedTokenIds().contains("tok_2"))
        assertEquals("HỢP ĐỒNG", controller.getSelectedText())

        // 3. Tap tok_1 again to toggle off
        controller.onSingleTap(0.15f, 0.15f)
        assertFalse(controller.getSelectedTokenIds().contains("tok_1"))
        assertEquals("ĐỒNG", controller.getSelectedText())
    }

    @Test
    fun testLineOnlyHitTestingWithoutWordBoxes() {
        // Line-only engine (e.g. PaddleOCR)
        val line = OcrLine(
            lineId = "line_paddle_1",
            text = "人工智能与深度学习",
            polygon = OcrPolygon(
                listOf(
                    OcrPoint(0.1f, 0.3f),
                    OcrPoint(0.9f, 0.3f),
                    OcrPoint(0.9f, 0.4f),
                    OcrPoint(0.1f, 0.4f)
                )
            ),
            tokens = emptyList() // No fake word boxes
        )
        val block = OcrBlock(blockId = "b1", lines = listOf(line))
        val page = OcrPage(pageId = "p1", pageIndex = 1, status = OcrPageStatus.SUCCESS, sourceBlocks = listOf(block))

        val controller = OcrSelectionController()
        controller.setPage(page)

        val hit = controller.onSingleTap(0.5f, 0.35f)
        assertTrue("Must hit line", hit)
        assertTrue(controller.getSelectedLineIds().contains("line_paddle_1"))
        assertEquals("人工智能与深度学习", controller.getSelectedText())
    }

    @Test
    fun testReadingOrderPreservedDuringCopy() {
        val col1Line1 = OcrLine("l_c1_1", "Cột 1 Dòng 1")
        val col1Line2 = OcrLine("l_c1_2", "Cột 1 Dòng 2")
        val col2Line1 = OcrLine("l_c2_1", "Cột 2 Dòng 1")
        val col2Line2 = OcrLine("l_c2_2", "Cột 2 Dòng 2")

        val block1 = OcrBlock("b1", boundingBox = OcrRect(0.05f, 0.1f, 0.45f, 0.5f), lines = listOf(col1Line1, col1Line2))
        val block2 = OcrBlock("b2", boundingBox = OcrRect(0.55f, 0.1f, 0.95f, 0.5f), lines = listOf(col2Line1, col2Line2))

        val page = OcrPage(pageId = "p1", pageIndex = 1, status = OcrPageStatus.SUCCESS, sourceBlocks = listOf(block1, block2))

        val controller = OcrSelectionController()
        controller.setPage(page)

        // Select entire page area
        controller.selectArea(OcrRect(0f, 0f, 1f, 1f))
        val text = controller.getSelectedText()

        val expected = "Cột 1 Dòng 1\nCột 1 Dòng 2\nCột 2 Dòng 1\nCột 2 Dòng 2"
        assertEquals("Reading order must read column 1 completely before column 2", expected, text)
    }

    @Test
    fun testUnicodeSearchWithAccentsAndCaseInsensitive() {
        val line1 = OcrLine(
            lineId = "l1",
            text = "CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM",
            boundingBox = OcrRect(0.1f, 0.1f, 0.9f, 0.15f)
        )
        val line2 = OcrLine(
            lineId = "l2",
            text = "Độc lập - Tự do - Hạnh phúc",
            boundingBox = OcrRect(0.2f, 0.16f, 0.8f, 0.20f)
        )
        val block = OcrBlock("b1", lines = listOf(line1, line2))
        val page = OcrPage(pageId = "p1", pageIndex = 1, status = OcrPageStatus.SUCCESS, sourceBlocks = listOf(block))

        val controller = OcrSelectionController()
        controller.setPage(page)

        // 1. Search for "việt" in lowercase
        val matchesVi = controller.search("việt")
        assertEquals(1, matchesVi.size)
        assertEquals("VIỆT", matchesVi[0].matchedText)
        assertEquals("l1", matchesVi[0].lineId)
        assertTrue(matchesVi[0].highlightBox.left >= 0.1f)
        assertTrue(matchesVi[0].highlightBox.right <= 0.9f)

        // 2. Search for "Tự Do"
        val matchesTuDo = controller.search("tự do")
        assertEquals(1, matchesTuDo.size)
        assertEquals("Tự do", matchesTuDo[0].matchedText)
        assertEquals("l2", matchesTuDo[0].lineId)

        // 3. Clear search
        controller.clearSearch()
        assertTrue(controller.getSearchMatches().isEmpty())
    }

    @Test
    fun testEditedContentDoesNotMisplaceOldWordOffsets() {
        // Original text was "Short", but edited text is much longer: "Edited Much Longer Text Content"
        val line = OcrLine(
            lineId = "l1",
            text = "Short",
            polygon = OcrPolygon(listOf(OcrPoint(0.1f, 0.1f), OcrPoint(0.3f, 0.1f), OcrPoint(0.3f, 0.15f), OcrPoint(0.1f, 0.15f))),
            tokens = listOf(OcrToken("tok_short", "Short"))
        )
        val block = OcrBlock("b1", lines = listOf(line))
        val page = OcrPage(
            pageId = "p1",
            pageIndex = 1,
            status = OcrPageStatus.SUCCESS,
            sourceBlocks = listOf(block),
            editedContent = OcrEditedContent(text = "Edited Much Longer Text Content")
        )

        val controller = OcrSelectionController()
        controller.setPage(page)

        // Tapping inside line region anchors to line, not reusing stale token offset
        val hit = controller.onSingleTap(0.2f, 0.12f)
        assertTrue(hit)
        // Must select line, not stale token
        assertTrue(controller.getSelectedLineIds().contains("l1"))
        assertEquals(0, controller.getSelectedTokenIds().size)
    }
}
