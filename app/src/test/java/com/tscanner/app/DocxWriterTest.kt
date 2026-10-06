package com.tscanner.app

import com.tscanner.app.ocr.export.DocxWriter
import com.tscanner.app.ocr.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

/**
 * Unit tests for DocxWriter:
 * - Generates compliant OpenXML ZIP package
 * - Paragraphs with bold, italic, font size, and alignment
 * - Tables with row/col spans and borders
 * - Page breaks and watermark emission
 * - Strict XML character escaping
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S19).
 */
class DocxWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testGenerateValidDocxZipStructureAndOpenXmlParts() = runBlocking {
        val targetFile = tempFolder.newFile("test_output.docx")

        val run1 = OcrTextRun("Tiêu đề in đậm ", isBold = true, isItalic = false, fontSizePt = 16.0f)
        val run2 = OcrTextRun("và in nghiêng", isBold = false, isItalic = true, fontSizePt = 12.0f)
        val para1 = OcrParagraph(
            paragraphId = "p1",
            text = "Tiêu đề in đậm và in nghiêng",
            alignment = OcrTextAlignment.CENTER,
            runs = listOf(run1, run2)
        )

        val table = OcrTable(
            tableId = "tbl_1",
            rowCount = 2,
            columnCount = 2,
            cells = listOf(
                OcrTableCell("c00", 0, 0, 1, 2, "Hàng tiêu đề gộp 2 cột", "Hàng tiêu đề gộp 2 cột", OcrCellType.TEXT),
                OcrTableCell("c10", 1, 0, 1, 1, "Mã hàng", "Mã hàng", OcrCellType.TEXT),
                OcrTableCell("c11", 1, 1, 1, 1, "00123", "00123", OcrCellType.TEXT)
            )
        )

        val doc = OcrDocument(
            id = "doc_docx_test",
            pages = listOf(
                OcrPage(
                    pageId = "page_1",
                    pageIndex = 1,
                    status = OcrPageStatus.SUCCESS,
                    tables = listOf(table),
                    editedContent = OcrEditedContent(text = para1.text, paragraphs = listOf(para1))
                ),
                OcrPage(
                    pageId = "page_2",
                    pageIndex = 2,
                    status = OcrPageStatus.SUCCESS,
                    editedContent = OcrEditedContent(
                        text = "Nội dung trang số 2",
                        paragraphs = listOf(OcrParagraph("p2", text = "Nội dung trang số 2"))
                    )
                )
            )
        )

        val success = DocxWriter.writeDocx(doc, targetFile, addWatermark = true, watermarkText = "T-Scanner VIP")
        assertTrue("Write DOCX must succeed", success)
        assertTrue(targetFile.exists())
        assertTrue("File size must be reasonable OpenXML package (> 1 KB)", targetFile.length() > 1000L)

        // Inspect ZIP structure
        val zip = ZipFile(targetFile)
        val entries = zip.entries().toList().map { it.name }.toSet()

        assertTrue(entries.contains("[Content_Types].xml"))
        assertTrue(entries.contains("_rels/.rels"))
        assertTrue(entries.contains("word/_rels/document.xml.rels"))
        assertTrue(entries.contains("word/styles.xml"))
        assertTrue(entries.contains("word/document.xml"))

        // Read and verify word/document.xml
        val docXmlEntry = zip.getEntry("word/document.xml")
        val docXml = zip.getInputStream(docXmlEntry).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

        // 1. Bold & Italic verification
        assertTrue("Must contain bold tag <w:b/>", docXml.contains("<w:b/>"))
        assertTrue("Must contain italic tag <w:i/>", docXml.contains("<w:i/>"))

        // 2. Alignment verification
        assertTrue("Must contain center alignment", docXml.contains("<w:jc w:val=\"center\"/>"))

        // 3. Table verification
        assertTrue("Must contain table element <w:tbl>", docXml.contains("<w:tbl>"))
        assertTrue("Must contain horizontal grid span", docXml.contains("<w:gridSpan w:val=\"2\"/>"))
        assertTrue("Must contain cell text 'Hàng tiêu đề gộp 2 cột'", docXml.contains("Hàng tiêu đề gộp 2 cột"))
        assertTrue("Must preserve leading zero '00123'", docXml.contains("00123"))

        // 4. Page Break between Page 1 and Page 2
        assertTrue("Must contain page break between pages", docXml.contains("<w:br w:type=\"page\"/>"))
        assertTrue("Must contain Page 2 text", docXml.contains("Nội dung trang số 2"))

        // 5. Watermark verification
        assertTrue("Must contain watermark text", docXml.contains("T-Scanner VIP"))

        zip.close()
    }

    @Test
    fun testXmlEscapingSanitizesSpecialCharacters() {
        val raw = "Tom & Jerry <cartoon> \"Quote\" 'Apos' \u0000\u0007"
        val escaped = DocxWriter.escapeXml(raw)

        assertTrue(escaped.contains("&amp;"))
        assertTrue(escaped.contains("&lt;"))
        assertTrue(escaped.contains("&gt;"))
        assertTrue(escaped.contains("&quot;"))
        assertTrue(escaped.contains("&apos;"))

        // Non-printable control characters stripped
        assertFalse(escaped.contains("\u0000"))
        assertFalse(escaped.contains("\u0007"))
    }
}
