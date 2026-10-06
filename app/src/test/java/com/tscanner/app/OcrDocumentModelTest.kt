package com.tscanner.app

import com.tscanner.app.ocr.model.*
import com.tscanner.app.utils.MultiPageOcrAggregator
import com.tscanner.app.utils.OcrResult
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for OcrDocument schema, geometry, table structures, and compatibility adapters.
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S02).
 */
class OcrDocumentModelTest {

    @Test
    fun testUnicodeRoundtripAndJsonSerialization() {
        val originalText = "Tiếng Việt có dấu: Ắ, Ằ, Ẳ, Ẵ, Ặ. Ứ, Ừ, Ử, Ữ, Ự. Hán tự: 漢字, 仮名. Emojis: 📄✨"
        val doc = OcrDocument(
            id = "doc_test_unicode_01",
            title = "Tài liệu thử nghiệm Unicode",
            sourceLanguage = "vi",
            pages = listOf(
                OcrPage(
                    pageId = "page_1_u",
                    pageIndex = 1,
                    status = OcrPageStatus.SUCCESS,
                    imageInfo = OcrImageInfo("file:///sdcard/scan.jpg", 1200, 1600, 0),
                    engineId = "mlkit_latin",
                    sourceBlocks = listOf(
                        OcrBlock(
                            blockId = "blk_1_0",
                            boundingBox = OcrRect(0.1f, 0.1f, 0.9f, 0.2f),
                            lines = listOf(
                                OcrLine(
                                    lineId = "line_1_0",
                                    text = originalText,
                                    polygon = OcrPolygon(
                                        listOf(
                                            OcrPoint(0.1f, 0.1f),
                                            OcrPoint(0.9f, 0.1f),
                                            OcrPoint(0.9f, 0.2f),
                                            OcrPoint(0.1f, 0.2f)
                                        )
                                    ),
                                    tokens = listOf(
                                        OcrToken("tok_1_0", "Tiếng", confidence = 0.99f),
                                        OcrToken("tok_1_1", "Việt", confidence = 0.98f)
                                    ),
                                    confidence = 0.985f
                                )
                            ),
                            confidence = 0.985f
                        )
                    ),
                    tables = listOf(
                        OcrTable(
                            tableId = "tbl_1_0",
                            rowCount = 2,
                            columnCount = 2,
                            cells = listOf(
                                OcrTableCell("c0", 0, 0, 1, 1, "Mã", "Mã", OcrCellType.TEXT),
                                OcrTableCell("c1", 0, 1, 1, 1, "00123", "00123", OcrCellType.TEXT),
                                OcrTableCell("c2", 1, 0, 1, 1, "Tiền tệ", "Tiền tệ", OcrCellType.TEXT),
                                OcrTableCell("c3", 1, 1, 1, 1, "500000", "500000", OcrCellType.NUMBER)
                            )
                        )
                    ),
                    editedContent = OcrEditedContent(
                        text = originalText,
                        paragraphs = listOf(
                            OcrParagraph(
                                paragraphId = "p_1_0",
                                sourceAnchorLineId = "line_1_0",
                                text = originalText,
                                alignment = OcrTextAlignment.CENTER,
                                runs = listOf(
                                    OcrTextRun(originalText, isBold = true, isItalic = false, fontSizePt = 12.0f)
                                )
                            )
                        )
                    )
                )
            )
        )

        val jsonString = doc.toJsonString()
        assertNotNull(jsonString)

        val restoredDoc = OcrDocument.fromJsonString(jsonString)
        assertEquals(doc.id, restoredDoc.id)
        assertEquals(doc.title, restoredDoc.title)
        assertEquals(doc.pages.size, restoredDoc.pages.size)

        val page = restoredDoc.pages[0]
        assertEquals(1, page.pageIndex)
        assertEquals(OcrPageStatus.SUCCESS, page.status)
        assertEquals(originalText, page.resolvedText)
        assertEquals(1, page.sourceBlocks.size)
        assertEquals(originalText, page.sourceBlocks[0].lines[0].text)
        assertEquals(4, page.sourceBlocks[0].lines[0].polygon?.points?.size)
        assertEquals(1, page.tables.size)
        assertEquals("00123", page.tables[0].cells[1].rawText)
        assertEquals(OcrCellType.TEXT, page.tables[0].cells[1].cellType)

        val edited = page.editedContent
        assertNotNull(edited)
        assertEquals(originalText, edited!!.text)
        assertEquals(1, edited.paragraphs.size)
        assertEquals(OcrTextAlignment.CENTER, edited.paragraphs[0].alignment)
        assertTrue(edited.paragraphs[0].runs[0].isBold)
    }

    @Test
    fun testTableGridValidationOverlapAndBounds() {
        // 1. Valid Table
        val validTable = OcrTable(
            tableId = "tbl_valid",
            rowCount = 2,
            columnCount = 2,
            cells = listOf(
                OcrTableCell("c00", 0, 0, 1, 1, "A"),
                OcrTableCell("c01", 0, 1, 1, 1, "B"),
                OcrTableCell("c10", 1, 0, 1, 1, "C"),
                OcrTableCell("c11", 1, 1, 1, 1, "D")
            )
        )
        val validResult = validTable.validateGrid()
        assertTrue("Valid grid should pass validation", validResult.isValid)
        assertTrue(validResult.errors.isEmpty())

        // 2. Table with overlapping cells
        val overlappingTable = OcrTable(
            tableId = "tbl_overlap",
            rowCount = 2,
            columnCount = 2,
            cells = listOf(
                OcrTableCell("c_span", 0, 0, 2, 2, "Span All"),
                OcrTableCell("c_conflict", 1, 1, 1, 1, "Conflict")
            )
        )
        val overlapResult = overlappingTable.validateGrid()
        assertFalse("Overlapping cells must fail validation", overlapResult.isValid)
        assertTrue("Error message should mention overlap", overlapResult.errors.any { it.contains("Overlapping") })

        // 3. Table with out-of-bounds cell
        val outOfBoundsTable = OcrTable(
            tableId = "tbl_oob",
            rowCount = 2,
            columnCount = 2,
            cells = listOf(
                OcrTableCell("c_oob", 1, 1, 2, 1, "OutOfBounds")
            )
        )
        val oobResult = outOfBoundsTable.validateGrid()
        assertFalse("Out of bounds cells must fail validation", oobResult.isValid)
        assertTrue("Error message should mention out of bounds", oobResult.errors.any { it.contains("out of bounds") })
    }

    @Test
    fun testDocumentPageIndexAndDuplicateValidation() {
        // Page index out of order
        val invalidIndexDoc = OcrDocument(
            pages = listOf(
                OcrPage("p1", 1, OcrPageStatus.SUCCESS),
                OcrPage("p2", 3, OcrPageStatus.SUCCESS) // expected 2
            )
        )
        val res1 = invalidIndexDoc.validate()
        assertFalse("Doc with skipped pageIndex must fail validation", res1.isValid)

        // Duplicate pageId
        val dupIdDoc = OcrDocument(
            pages = listOf(
                OcrPage("dup_id", 1, OcrPageStatus.SUCCESS),
                OcrPage("dup_id", 2, OcrPageStatus.SUCCESS)
            )
        )
        val res2 = dupIdDoc.validate()
        assertFalse("Doc with duplicate pageId must fail validation", res2.isValid)
        assertTrue(res2.errors.any { it.contains("Duplicate pageId") })
    }

    @Test
    fun testMultiPageOcrAggregatorCreatesOcrDocument() {
        val pages = listOf(
            OcrResult.Success(
                text = "First page content",
                engineId = "paddle",
                documentLanguage = "vi"
            ),
            OcrResult.NoText,
            OcrResult.Success(
                text = "Third page content",
                engineId = "tesseract",
                documentLanguage = "vi"
            )
        )

        val aggregated = MultiPageOcrAggregator.aggregate(pages)
        assertTrue(aggregated is com.tscanner.app.utils.MultiPageOcrResult.Success)

        val success = aggregated as com.tscanner.app.utils.MultiPageOcrResult.Success
        val doc = success.document
        assertNotNull("MultiPageOcrResult must produce an OcrDocument", doc)
        assertEquals(3, doc!!.totalPages)
        assertEquals(2, doc.pagesWithText)
        assertEquals(1, doc.blankPages)

        // Verify page 2 is NO_TEXT and not dropped
        assertEquals(OcrPageStatus.NO_TEXT, doc.pages[1].status)
        assertEquals(2, doc.pages[1].pageIndex)

        // Verify page 3 is preserved at index 3
        assertEquals(OcrPageStatus.SUCCESS, doc.pages[2].status)
        assertEquals(3, doc.pages[2].pageIndex)
        assertEquals("Third page content", doc.pages[2].resolvedText)

        val docVal = doc.validate()
        assertTrue("Aggregated document must pass validation: ${docVal.errors}", docVal.isValid)
    }
}
