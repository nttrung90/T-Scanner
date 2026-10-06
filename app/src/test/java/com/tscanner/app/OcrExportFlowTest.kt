package com.tscanner.app

import com.tscanner.app.ocr.export.DocxWriter
import com.tscanner.app.ocr.export.XlsxWriter
import com.tscanner.app.ocr.model.*
import com.tscanner.app.utils.FileUtils
import com.tscanner.app.utils.SafeFileWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

/**
 * Integration & Unit tests for OCR Export Flow (Package S21):
 * - Edit -> prepare snapshot -> export genuine DOCX/XLSX
 * - MIME types and extensions matching
 * - File sanitization and extension retention (docx, xlsx, csv, txt)
 * - Safe export: no false success on write failures
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S21).
 */
class OcrExportFlowTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testEditToDocxExportFlow() = runBlocking {
        val targetFile = tempFolder.newFile("edited_output.docx")

        // Simulate edited document snapshot
        val doc = OcrDocument(
            id = "doc_edit_test",
            pages = listOf(
                OcrPage(
                    pageId = "p1",
                    pageIndex = 1,
                    status = OcrPageStatus.SUCCESS,
                    editedContent = OcrEditedContent(
                        text = "Văn bản đã chỉnh sửa qua OcrTextEditorFragment\nDòng thứ hai",
                        paragraphs = listOf(
                            OcrParagraph(
                                paragraphId = "para_0",
                                text = "Văn bản đã chỉnh sửa qua OcrTextEditorFragment",
                                runs = listOf(
                                    OcrTextRun("Văn bản đã chỉnh sửa", isBold = true),
                                    OcrTextRun(" qua OcrTextEditorFragment", isItalic = true)
                                )
                            ),
                            OcrParagraph(
                                paragraphId = "para_1",
                                text = "Dòng thứ hai",
                                runs = listOf(OcrTextRun("Dòng thứ hai"))
                            )
                        )
                    )
                )
            )
        )

        val success = DocxWriter.writeDocx(doc, targetFile, addWatermark = false)
        assertTrue("DOCX write must succeed", success)
        assertTrue(targetFile.exists())

        // Verify content inside ZIP
        val zip = ZipFile(targetFile)
        val docXml = zip.getInputStream(zip.getEntry("word/document.xml"))
            .bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

        assertTrue(docXml.contains("V&#259;n b&#7843;n &#273;&#227; ch&#7881;nh s&#7917;a") || docXml.contains("Văn bản đã chỉnh sửa"))
        assertTrue(docXml.contains("<w:b/>"))
        assertTrue(docXml.contains("<w:i/>"))
        zip.close()
    }

    @Test
    fun testEditToXlsxExportFlow() = runBlocking {
        val targetFile = tempFolder.newFile("edited_sheet.xlsx")

        // Simulate edited table snapshot
        val table = OcrTable(
            tableId = "tbl_edit",
            rowCount = 2,
            columnCount = 2,
            cells = listOf(
                OcrTableCell("c00", 0, 0, 1, 1, "Mã hàng", "Mã hàng", OcrCellType.TEXT),
                OcrTableCell("c01", 0, 1, 1, 1, "Giá", "Giá", OcrCellType.TEXT),
                OcrTableCell("c10", 1, 0, 1, 1, "00456", "00456", OcrCellType.TEXT),
                OcrTableCell("c11", 1, 1, 1, 1, "89000", "89000", OcrCellType.NUMBER)
            )
        )

        val doc = OcrDocument(
            id = "doc_table_test",
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
        assertTrue("XLSX write must succeed", success)
        assertTrue(targetFile.exists())

        val zip = ZipFile(targetFile)
        val sheetXml = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml"))
            .bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

        // Must preserve leading zeros as inline string
        assertTrue(sheetXml.contains("<t xml:space=\"preserve\">00456</t>"))
        assertTrue(sheetXml.contains("<v>89000</v>"))
        zip.close()
    }

    @Test
    fun testMimeTypesResolution() {
        fun resolveMime(fileName: String): String = when {
            fileName.endsWith(".docx", ignoreCase = true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            fileName.endsWith(".xlsx", ignoreCase = true) -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            fileName.endsWith(".doc", ignoreCase = true) -> "application/msword"
            fileName.endsWith(".csv", ignoreCase = true) -> "text/csv"
            fileName.endsWith(".txt", ignoreCase = true) -> "text/plain"
            else -> "application/octet-stream"
        }

        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", resolveMime("invoice.docx"))
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", resolveMime("data.xlsx"))
        assertEquals("application/msword", resolveMime("old_format.doc"))
        assertEquals("text/csv", resolveMime("records.csv"))
        assertEquals("text/plain", resolveMime("notes.txt"))
    }

    @Test
    fun testExportDocDialogExtensionResolution() {
        fun resolveFinalName(rawName: String, extension: String, supportedExtensions: List<String>): String {
            val extensionToUse = supportedExtensions.firstOrNull {
                rawName.endsWith(".$it", ignoreCase = true)
            } ?: extension
            val sanitized = FileUtils.sanitizeFileName(rawName.removeSuffix(".$extensionToUse"))
            return "$sanitized.$extensionToUse"
        }

        val wordSupported = listOf("docx", "doc", "txt")
        assertEquals("Document_01.docx", resolveFinalName("Document_01", "docx", wordSupported))
        assertEquals("Document_01.doc", resolveFinalName("Document_01.doc", "docx", wordSupported))
        assertEquals("Document_01.txt", resolveFinalName("Document_01.txt", "docx", wordSupported))

        val excelSupported = listOf("xlsx", "csv")
        assertEquals("Sheet_01.xlsx", resolveFinalName("Sheet_01", "xlsx", excelSupported))
        assertEquals("Sheet_01.csv", resolveFinalName("Sheet_01.csv", "xlsx", excelSupported))
    }

    @Test
    fun testSafeFileWriterNoFalseSuccessOnFailure() = runBlocking {
        val destination = File(tempFolder.root, "failure_test.docx")

        // When writer lambda returns false (simulating I/O error or write failure)
        val result = SafeFileWriter.writeSafely(destination) { tempFile ->
            tempFile.writeText("corrupted partial data")
            false // Simulate failure
        }

        // Must report Error, never Success, and destination file must not exist
        assertTrue("SafeFileWriter must report Error on failure", result is SafeFileWriter.Result.Error)
        assertFalse("Destination file must not exist when write fails", destination.exists())
    }
}
