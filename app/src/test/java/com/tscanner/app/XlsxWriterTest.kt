package com.tscanner.app

import com.tscanner.app.ocr.export.XlsxWriter
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
 * Unit tests for XlsxWriter:
 * - Generates compliant OpenXML SpreadsheetML ZIP package
 * - Leading zero preservation ("00123" as inlineStr)
 * - Number formatting ('n')
 * - Formula injection neutralization ('=', '+', '-', '@')
 * - Merged cells ('mergeCells')
 * - Cell reference addressing (A1..AA1)
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S20).
 */
class XlsxWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testGenerateValidXlsxZipStructureAndSpreadsheetParts() = runBlocking {
        val targetFile = tempFolder.newFile("test_sheet.xlsx")

        val table = OcrTable(
            tableId = "tbl_1",
            rowCount = 3,
            columnCount = 3,
            cells = listOf(
                OcrTableCell("c00", 0, 0, 1, 2, "BẢNG KINH DOANH", "BẢNG KINH DOANH", OcrCellType.TEXT),
                OcrTableCell("c02", 0, 2, 1, 1, "Tháng 9", "Tháng 9", OcrCellType.TEXT),
                OcrTableCell("c10", 1, 0, 1, 1, "STT", "STT", OcrCellType.TEXT),
                OcrTableCell("c11", 1, 1, 1, 1, "Mã hàng", "Mã hàng", OcrCellType.TEXT),
                OcrTableCell("c12", 1, 2, 1, 1, "Doanh thu", "Doanh thu", OcrCellType.TEXT),
                OcrTableCell("c20", 2, 0, 1, 1, "1", "1", OcrCellType.NUMBER),
                OcrTableCell("c21", 2, 1, 1, 1, "00123", "00123", OcrCellType.TEXT),
                OcrTableCell("c22", 2, 2, 1, 1, "1500000", "1500000", OcrCellType.NUMBER)
            )
        )

        val doc = OcrDocument(
            id = "doc_xlsx_test",
            pages = listOf(
                OcrPage(
                    pageId = "p1",
                    pageIndex = 1,
                    status = OcrPageStatus.SUCCESS,
                    tables = listOf(table)
                )
            )
        )

        val success = XlsxWriter.writeXlsx(doc, targetFile)
        assertTrue("Write XLSX must succeed", success)
        assertTrue(targetFile.exists())
        assertTrue(targetFile.length() > 1000L)

        // Inspect ZIP structure
        val zip = ZipFile(targetFile)
        val entries = zip.entries().toList().map { it.name }.toSet()

        assertTrue(entries.contains("[Content_Types].xml"))
        assertTrue(entries.contains("_rels/.rels"))
        assertTrue(entries.contains("xl/_rels/workbook.xml.rels"))
        assertTrue(entries.contains("xl/styles.xml"))
        assertTrue(entries.contains("xl/workbook.xml"))
        assertTrue(entries.contains("xl/worksheets/sheet1.xml"))

        // Inspect sheet1.xml
        val sheetEntry = zip.getEntry("xl/worksheets/sheet1.xml")
        val sheetXml = zip.getInputStream(sheetEntry).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

        // 1. Leading zero preservation ("00123" must be inlineStr, never numeric 123)
        assertTrue("00123 must be preserved as inline string", sheetXml.contains("<t xml:space=\"preserve\">00123</t>"))
        assertFalse("00123 must not be converted to number <v>123</v>", sheetXml.contains("<v>123</v>"))

        // 2. Number formatting
        assertTrue("Numeric 1500000 must be formatted as number", sheetXml.contains("<v>1500000</v>"))

        // 3. Merged cells (A1:B1)
        assertTrue("Must contain mergeCells tag", sheetXml.contains("<mergeCells"))
        assertTrue("Must contain mergeCell ref A1:B1", sheetXml.contains("<mergeCell ref=\"A1:B1\"/>"))

        zip.close()
    }

    @Test
    fun testFormulaInjectionNeutralizedWithQuoteEscape() = runBlocking {
        val targetFile = tempFolder.newFile("test_formula_defense.xlsx")

        val table = OcrTable(
            tableId = "tbl_formula",
            rowCount = 4,
            columnCount = 1,
            cells = listOf(
                OcrTableCell("c0", 0, 0, 1, 1, "=SUM(A1:A10)", "=SUM(A1:A10)", OcrCellType.TEXT),
                OcrTableCell("c1", 1, 0, 1, 1, "+12345", "+12345", OcrCellType.TEXT),
                OcrTableCell("c2", 2, 0, 1, 1, "-500", "-500", OcrCellType.TEXT),
                OcrTableCell("c3", 3, 0, 1, 1, "@cmd|'calc'!A0", "@cmd|'calc'!A0", OcrCellType.TEXT)
            )
        )

        val doc = OcrDocument(
            id = "doc_formula_test",
            pages = listOf(OcrPage("p1", 1, OcrPageStatus.SUCCESS, tables = listOf(table)))
        )

        XlsxWriter.writeXlsx(doc, targetFile)

        val zip = ZipFile(targetFile)
        val sheetEntry = zip.getEntry("xl/worksheets/sheet1.xml")
        val sheetXml = zip.getInputStream(sheetEntry).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

        // Must prepend single quote to neutralize formula injection (escaped as &apos; in XML)
        assertTrue(sheetXml.contains("&apos;=SUM(A1:A10)"))
        assertTrue(sheetXml.contains("&apos;+12345"))
        assertTrue(sheetXml.contains("&apos;-500"))
        assertTrue(sheetXml.contains("&apos;@cmd|"))

        // Must never emit formula tag <f>
        assertFalse("Must never emit executable formula tag <f>", sheetXml.contains("<f>"))

        zip.close()
    }

    @Test
    fun testCellReferenceAddressing() {
        assertEquals("A1", XlsxWriter.getCellRef(0, 0))
        assertEquals("B1", XlsxWriter.getCellRef(1, 0))
        assertEquals("C5", XlsxWriter.getCellRef(2, 4))
        assertEquals("Z1", XlsxWriter.getCellRef(25, 0))
        assertEquals("AA1", XlsxWriter.getCellRef(26, 0))
        assertEquals("AB1", XlsxWriter.getCellRef(27, 0))
    }
}
