package com.tscanner.app

import com.tscanner.app.ocr.export.DocxWriter
import com.tscanner.app.ocr.export.XlsxWriter
import com.tscanner.app.ocr.model.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.zip.ZipFile

/**
 * Multilingual & Complex Table Acceptance Tests (Package S22):
 * - 4 Language Families: Latin/Vietnamese, CJK, RTL (Arabic/Hebrew), Indic (Hindi/Devanagari)
 * - Complex Tables: Multipage, merged cells (colspan/rowspan), large numbers, leading zeros ("000123"), formula injection defense
 * - 100% JVM runnable without physical device requirement
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S22).
 */
class OcrMultilingualAndTableAcceptanceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun loadFixture(resourcePath: String): OcrDocument {
        val stream = javaClass.classLoader?.getResourceAsStream(resourcePath)
            ?: error("Resource not found: $resourcePath")
        val jsonStr = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return OcrDocument.fromJson(JSONObject(jsonStr))
    }

    @Test
    fun testLanguageFamily1_LatinAndVietnamese() = runBlocking {
        val stream = javaClass.classLoader?.getResourceAsStream("ocr_reader/single_col_vi_01.json")
            ?: error("Resource not found")
        val jsonStr = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val json = JSONObject(jsonStr)

        val lang = json.getString("language")
        assertEquals("vi", lang)

        val textVi = json.getString("groundTruthText")
        assertTrue("Must contain Vietnamese diacritics", textVi.contains("CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM"))

        // Unicode NFC normalization check
        val nfcText = Normalizer.normalize(textVi, Normalizer.Form.NFC)
        assertEquals("Text must be canonically NFC", nfcText, textVi)

        val docVi = OcrDocument(
            id = json.getString("id"),
            sourceLanguage = lang,
            pages = listOf(
                OcrPage(
                    pageId = "p1",
                    pageIndex = 1,
                    status = OcrPageStatus.SUCCESS,
                    sourceLanguage = lang,
                    editedContent = OcrEditedContent(
                        text = textVi,
                        paragraphs = textVi.split("\n\n").mapIndexed { idx, pText ->
                            OcrParagraph("para_$idx", text = pText, runs = listOf(OcrTextRun(text = pText)))
                        }
                    )
                )
            )
        )

        // Export to DOCX
        val docxFile = tempFolder.newFile("test_vi.docx")
        assertTrue(DocxWriter.writeDocx(docVi, docxFile))
        assertTrue(docxFile.length() > 500L)
    }

    @Test
    fun testLanguageFamily2_CJK() = runBlocking {
        val docCjk = loadFixture("ocr_reader/cjk_doc_01.json")
        assertEquals("ja", docCjk.sourceLanguage)
        val textCjk = docCjk.fullText

        assertTrue("Must contain Japanese Kanji and Kana", textCjk.contains("東京都千代田区丸の内1丁目"))
        assertTrue("Must contain Korean Hangul", textCjk.contains("안녕하세요 세계 여러분"))

        // Export to DOCX and inspect ZIP
        val docxFile = tempFolder.newFile("test_cjk.docx")
        assertTrue(DocxWriter.writeDocx(docCjk, docxFile))

        val zip = ZipFile(docxFile)
        val docXml = zip.getInputStream(zip.getEntry("word/document.xml"))
            .bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        assertTrue("XML must contain Japanese characters or entity encoding",
            docXml.contains("東京都千代田区") || docXml.contains("&#26481;&#20140;"))
        assertTrue("XML must contain Korean characters or entity encoding",
            docXml.contains("안녕하세요") || docXml.contains("&#50504;&#45397;"))
        zip.close()
    }

    @Test
    fun testLanguageFamily3_RTL() = runBlocking {
        val docRtl = loadFixture("ocr_reader/rtl_doc_01.json")
        assertEquals("ar", docRtl.sourceLanguage)
        val textRtl = docRtl.fullText

        assertTrue("Must contain Arabic characters", textRtl.contains("مرحبا بك"))
        assertTrue("Must contain Hebrew characters", textRtl.contains("שלום עולם"))

        val docxFile = tempFolder.newFile("test_rtl.docx")
        assertTrue(DocxWriter.writeDocx(docRtl, docxFile))
        assertTrue(docxFile.length() > 500L)
    }

    @Test
    fun testLanguageFamily4_Indic() = runBlocking {
        val docIndic = loadFixture("ocr_reader/indic_doc_01.json")
        assertEquals("hi", docIndic.sourceLanguage)
        val textIndic = docIndic.fullText

        assertTrue("Must contain Hindi Devanagari characters", textIndic.contains("नमस्ते भारत"))

        val docxFile = tempFolder.newFile("test_indic.docx")
        assertTrue(DocxWriter.writeDocx(docIndic, docxFile))
        assertTrue(docxFile.length() > 500L)
    }

    @Test
    fun testComplexMultipageTableWithMergedCellsAndZeroes() = runBlocking {
        val doc = loadFixture("ocr_reader/complex_table_multipage_01.json")
        assertEquals("Multipage table document must have 2 pages", 2, doc.totalPages)

        // 1. Validate Table structure on Page 1
        val p1Table = doc.pages[0].tables[0]
        assertEquals(3, p1Table.rowCount)
        assertEquals(4, p1Table.columnCount)

        // Header banner colspan 4
        val bannerCell = p1Table.cells.first { it.rowIndex == 0 && it.colIndex == 0 }
        assertEquals(1, bannerCell.rowSpan)
        assertEquals(4, bannerCell.colSpan)
        assertEquals("BÁO CÁO TÀI CHÍNH TỔNG HỢP NĂM 2026", bannerCell.editedText)

        // Leading zeroes preservation
        val codeCellP1 = p1Table.cells.first { it.rowIndex == 2 && it.colIndex == 1 }
        assertEquals("000123", codeCellP1.editedText)
        assertEquals(OcrCellType.TEXT, codeCellP1.cellType)

        // Large number cell
        val numCellP1 = p1Table.cells.first { it.rowIndex == 2 && it.colIndex == 3 }
        assertEquals("98765432.10", numCellP1.editedText)
        assertEquals(OcrCellType.NUMBER, numCellP1.cellType)

        // 2. Validate Table structure on Page 2
        val p2Table = doc.pages[1].tables[0]
        assertEquals(2, p2Table.rowCount)
        assertEquals(4, p2Table.columnCount)

        val codeCellP2 = p2Table.cells.first { it.rowIndex == 0 && it.colIndex == 1 }
        assertEquals("00789", codeCellP2.editedText)
        assertEquals(OcrCellType.TEXT, codeCellP2.cellType)

        // Formula injection cell in Page 2
        val formulaCell = p2Table.cells.first { it.rowIndex == 0 && it.colIndex == 3 }
        assertEquals("=SUM(D2:D10)", formulaCell.editedText)

        // 3. Export to XLSX and verify SpreadsheetML XML
        val xlsxFile = tempFolder.newFile("complex_table_out.xlsx")
        assertTrue("XLSX export must succeed", XlsxWriter.writeXlsx(doc, xlsxFile))

        val zip = ZipFile(xlsxFile)
        val sheetXml = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml"))
            .bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

        // Verify merge cells: A1:D1 for header, A6:C6 for summary banner (row 6 after gap)
        assertTrue("Must contain mergeCell ref A1:D1", sheetXml.contains("<mergeCell ref=\"A1:D1\"/>"))
        assertTrue("Must contain mergeCell ref A6:C6", sheetXml.contains("<mergeCell ref=\"A6:C6\"/>"))

        // Verify leading zeroes preservation in XLSX
        assertTrue("000123 must be inline string", sheetXml.contains("<t xml:space=\"preserve\">000123</t>"))
        assertTrue("00789 must be inline string", sheetXml.contains("<t xml:space=\"preserve\">00789</t>"))

        // Verify large number as numeric cell
        assertTrue("98765432.10 must be numeric", sheetXml.contains("<v>98765432.10</v>"))
        assertTrue("125000000 must be numeric", sheetXml.contains("<v>125000000</v>"))

        // Verify formula defense: neutralized with quote escape &apos;
        assertTrue("Formula must be quoted", sheetXml.contains("&apos;=SUM(D2:D10)"))
        assertFalse("Must never contain executable formula tag <f>", sheetXml.contains("<f>"))

        zip.close()
    }
}
