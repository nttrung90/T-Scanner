package com.tscanner.app

import android.content.ContextWrapper
import com.tscanner.app.ocr.data.OcrDocumentRepository
import com.tscanner.app.ocr.data.RepositoryResult
import com.tscanner.app.ocr.edit.OcrEditCommand
import com.tscanner.app.ocr.edit.OcrEditHistory
import com.tscanner.app.ocr.export.DocxWriter
import com.tscanner.app.ocr.export.XlsxWriter
import com.tscanner.app.ocr.model.*
import com.tscanner.app.utils.OcrResult
import com.tscanner.app.utils.TextRecognitionHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

/**
 * F03: Comprehensive integration tests for historical/legacy documents with metadata `engineId = "paddle"`.
 *
 * Verifies the complete lifecycle pipeline for legacy documents:
 * 1. Persistence roundtrip with real OcrDocumentRepository (filesystem JSON persistence).
 * 2. Complete layout and geometry preservation (blocks, lines, tokens, bounding boxes, polygons).
 * 3. In-memory editing via real OcrEditHistory and OcrEditCommand (provenance preservation, CAS revision).
 * 4. Production export to DOCX via real DocxWriter (OpenXML packaging, paragraph/table rendering).
 * 5. Production export to XLSX via real XlsxWriter (SpreadsheetML packaging, table structure).
 * 6. UI Metadata formatting for legacy PaddleOCR engine identifiers.
 */
class PaddleLegacyPersistenceExportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var repoDir: File
    private lateinit var repository: OcrDocumentRepository
    private lateinit var dummyContext: ContextWrapper

    @Before
    fun setUp() {
        repoDir = tempFolder.newFolder("ocr_legacy_repo")
        repository = OcrDocumentRepository(repoDir)
        dummyContext = object : ContextWrapper(null) {}
    }

    private fun createLegacyPaddleDocument(): OcrDocument {
        val token1 = OcrToken(
            tokenId = "tok_1",
            text = "CỘNG",
            confidence = 0.98f,
            polygon = OcrPolygon(
                listOf(
                    OcrPoint(0.1f, 0.1f),
                    OcrPoint(0.2f, 0.1f),
                    OcrPoint(0.2f, 0.15f),
                    OcrPoint(0.1f, 0.15f)
                )
            )
        )
        val token2 = OcrToken(
            tokenId = "tok_2",
            text = "HÒA",
            confidence = 0.99f,
            polygon = OcrPolygon(
                listOf(
                    OcrPoint(0.22f, 0.1f),
                    OcrPoint(0.35f, 0.1f),
                    OcrPoint(0.35f, 0.15f),
                    OcrPoint(0.22f, 0.15f)
                )
            )
        )

        val line1 = OcrLine(
            lineId = "line_1",
            text = "CỘNG HÒA",
            confidence = 0.985f,
            polygon = OcrPolygon(
                listOf(
                    OcrPoint(0.1f, 0.1f),
                    OcrPoint(0.35f, 0.1f),
                    OcrPoint(0.35f, 0.15f),
                    OcrPoint(0.1f, 0.15f)
                )
            ),
            boundingBox = OcrRect(100f, 100f, 350f, 150f),
            tokens = listOf(token1, token2)
        )

        val block1 = OcrBlock(
            blockId = "blk_1",
            boundingBox = OcrRect(100f, 100f, 350f, 150f),
            lines = listOf(line1)
        )

        val table1 = OcrTable(
            tableId = "tbl_legacy_1",
            rowCount = 2,
            columnCount = 2,
            cells = listOf(
                OcrTableCell("c00", 0, 0, 1, 1, "Mục", "Mục", OcrCellType.TEXT),
                OcrTableCell("c01", 0, 1, 1, 1, "Giá trị", "Giá trị", OcrCellType.TEXT),
                OcrTableCell("c10", 1, 0, 1, 1, "Hợp đồng số", "Hợp đồng số", OcrCellType.TEXT),
                OcrTableCell("c11", 1, 1, 1, 1, "HD-2024-001", "HD-2024-001", OcrCellType.TEXT)
            )
        )

        val legacyPage = OcrPage(
            pageId = "page_legacy_1",
            pageIndex = 1,
            status = OcrPageStatus.SUCCESS,
            engineId = "paddle",
            sourceLanguage = "vi",
            sourceBlocks = listOf(block1),
            tables = listOf(table1)
        )

        return OcrDocument(
            id = "doc_legacy_paddle_001",
            revision = 1L,
            title = "Tài liệu lịch sử PaddleOCR",
            sourceLanguage = "vi",
            pages = listOf(legacyPage)
        )
    }

    @Test
    fun testLegacyPaddleDocument_persistenceRoundtrip_preservesAllMetadataAndHierarchy() = runBlocking {
        val originalDoc = createLegacyPaddleDocument()

        // 1. Save to real repository on disk
        val saveResult = repository.saveDocument(originalDoc)
        assertTrue("Save must succeed, got: $saveResult", saveResult is RepositoryResult.Success)

        // 2. Load from repository
        val loadResult = repository.loadDocument(originalDoc.id)
        assertTrue("Load must succeed, got: $loadResult", loadResult is RepositoryResult.Success)

        val loadedDoc = (loadResult as RepositoryResult.Success).value
        assertEquals("doc_legacy_paddle_001", loadedDoc.id)
        assertEquals(2L, loadedDoc.revision)
        assertEquals("Tài liệu lịch sử PaddleOCR", loadedDoc.title)
        assertEquals("vi", loadedDoc.sourceLanguage)
        assertEquals(1, loadedDoc.pages.size)

        val page = loadedDoc.pages[0]
        assertEquals("page_legacy_1", page.pageId)
        assertEquals(1, page.pageIndex)
        assertEquals(OcrPageStatus.SUCCESS, page.status)
        assertEquals("paddle", page.engineId)
        assertEquals("vi", page.sourceLanguage)

        // Verify hierarchy: Block -> Line -> Token
        assertEquals(1, page.sourceBlocks.size)
        val block = page.sourceBlocks[0]
        assertEquals("blk_1", block.blockId)
        assertEquals(1, block.lines.size)

        val line = block.lines[0]
        assertEquals("line_1", line.lineId)
        assertEquals("CỘNG HÒA", line.text)
        assertEquals(2, line.tokens.size)

        val tok1 = line.tokens[0]
        assertEquals("tok_1", tok1.tokenId)
        assertEquals("CỘNG", tok1.text)
        assertNotNull("Polygon must be preserved", tok1.polygon)
        assertEquals(4, tok1.polygon?.points?.size)
        assertEquals(0.1f, tok1.polygon!!.points[0].x, 0.001f)
        assertEquals(0.1f, tok1.polygon!!.points[0].y, 0.001f)

        // Verify Table
        assertEquals(1, page.tables.size)
        val tbl = page.tables[0]
        assertEquals(2, tbl.rowCount)
        assertEquals(2, tbl.columnCount)
        assertEquals(4, tbl.cells.size)
        assertEquals("HD-2024-001", tbl.cells[3].rawText)
    }

    @Test
    fun testLegacyPaddleDocument_realEditingCommands_updatesDocumentAndIncrementsRevision() = runBlocking {
        val originalDoc = createLegacyPaddleDocument()
        repository.saveDocument(originalDoc)

        // Edit via OcrEditHistory
        val history = OcrEditHistory(originalDoc)
        val originalText = originalDoc.pages[0].resolvedText
        val editedText = "CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM (ĐÃ CẬP NHẬT 2026)"

        val command = OcrEditCommand.ReplacePageText(
            pageIndex = 1,
            oldText = originalText,
            newText = editedText
        )
        val editedDoc = history.execute(command)

        assertEquals(editedText, editedDoc.pages[0].resolvedText)
        // Provenance source blocks must remain untouched
        assertEquals(1, editedDoc.pages[0].sourceBlocks.size)
        assertEquals("CỘNG HÒA", editedDoc.pages[0].sourceBlocks[0].lines[0].text)
        assertEquals("paddle", editedDoc.pages[0].engineId)

        // Save updated revision
        val saveEditedResult = repository.saveDocument(editedDoc)
        assertTrue("Save edited doc must succeed", saveEditedResult is RepositoryResult.Success)

        // Reload and verify
        val reloadedResult = repository.loadDocument(originalDoc.id)
        assertTrue("Reload must succeed", reloadedResult is RepositoryResult.Success)
        val reloadedDoc = (reloadedResult as RepositoryResult.Success).value
        assertEquals(2L, reloadedDoc.revision)
        assertEquals(editedText, reloadedDoc.pages[0].resolvedText)
        assertEquals("paddle", reloadedDoc.pages[0].engineId)
    }

    @Test
    fun testLegacyPaddleDocument_exportToDocx_createsValidDocxPackage() = runBlocking {
        val doc = createLegacyPaddleDocument()
        val docxFile = tempFolder.newFile("legacy_paddle_export.docx")

        val writeSuccess = DocxWriter.writeDocx(doc, docxFile)
        assertTrue("DocxWriter must report success", writeSuccess)
        assertTrue("Exported DOCX file must exist", docxFile.exists())
        assertTrue("Exported DOCX file must have non-zero size", docxFile.length() > 0)

        // Verify valid OpenXML ZIP package structure
        ZipFile(docxFile).use { zip ->
            assertNotNull("Must contain [Content_Types].xml", zip.getEntry("[Content_Types].xml"))
            assertNotNull("Must contain word/document.xml", zip.getEntry("word/document.xml"))
            assertNotNull("Must contain word/_rels/document.xml.rels", zip.getEntry("word/_rels/document.xml.rels"))

            val docXmlContent = zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader(StandardCharsets.UTF_8).readText()
            assertTrue("Document XML must contain page text", docXmlContent.contains("CỘNG HÒA"))
            assertTrue("Document XML must contain table cell content", docXmlContent.contains("HD-2024-001"))
        }
    }

    @Test
    fun testLegacyPaddleDocument_exportToXlsx_createsValidXlsxPackage() = runBlocking {
        val doc = createLegacyPaddleDocument()
        val xlsxFile = tempFolder.newFile("legacy_paddle_export.xlsx")

        val writeSuccess = XlsxWriter.writeXlsx(doc, xlsxFile)
        assertTrue("XlsxWriter must report success", writeSuccess)
        assertTrue("Exported XLSX file must exist", xlsxFile.exists())
        assertTrue("Exported XLSX file must have non-zero size", xlsxFile.length() > 0)

        // Verify valid OpenXML SpreadsheetML ZIP package structure
        ZipFile(xlsxFile).use { zip ->
            assertNotNull("Must contain [Content_Types].xml", zip.getEntry("[Content_Types].xml"))
            assertNotNull("Must contain xl/workbook.xml", zip.getEntry("xl/workbook.xml"))
            assertNotNull("Must contain xl/worksheets/sheet1.xml", zip.getEntry("xl/worksheets/sheet1.xml"))

            val sheetXmlContent = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).bufferedReader(StandardCharsets.UTF_8).readText()
            assertTrue("Sheet XML must contain table cell content", sheetXmlContent.contains("HD-2024-001"))
        }
    }

    @Test
    fun testLegacyPaddleMetadataFormatter_displaysAppropriateLegacyLabelWithoutCrash() {
        val displayName = TextRecognitionHelper.getEngineDisplayName(dummyContext, "paddle")
        assertEquals("PaddleOCR (Legacy)", displayName)

        val formattedMetadata = TextRecognitionHelper.formatEngineMetadata(
            context = dummyContext,
            engineId = "paddle",
            documentLanguage = "vi"
        )
        assertTrue("Formatted metadata must contain Paddle identifier", formattedMetadata.contains("Paddle"))
        assertTrue("Formatted metadata must not be empty", formattedMetadata.isNotBlank())

        val successResult = OcrResult.Success(
            text = "Văn bản mẫu",
            engineId = "paddle",
            documentLanguage = "vi",
            fallbackUsed = false
        )
        val fromResult = TextRecognitionHelper.formatEngineMetadata(dummyContext, successResult)
        assertEquals(formattedMetadata, fromResult)
    }
}
