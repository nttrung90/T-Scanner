package com.tscanner.app

import com.tscanner.app.ocr.data.OcrDocumentRepository
import com.tscanner.app.ocr.data.RepositoryResult
import com.tscanner.app.ocr.model.OcrPageStatus
import com.tscanner.app.utils.MultiPageOcrAggregator
import com.tscanner.app.utils.MultiPageOcrResult
import com.tscanner.app.utils.OcrResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for multi-page aggregation, persistence before handoff, and revision guard.
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S08).
 */
class OcrMultiPageIntegrationAndHandoffTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var repository: OcrDocumentRepository

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("ocr_handoff_test")
        repository = OcrDocumentRepository(baseDir)
    }

    @Test
    fun testThreePagesWithMiddlePageBlankPreservesPageOrder() {
        val pages = listOf(
            OcrResult.Success(
                text = "Trang bìa tài liệu",
                engineId = "tesseract",
                documentLanguage = "vi"
            ),
            OcrResult.NoText, // Middle page blank
            OcrResult.Success(
                text = "Trang ký tên và đóng dấu",
                engineId = "tesseract",
                documentLanguage = "vi"
            )
        )

        val aggregated = MultiPageOcrAggregator.aggregate(pages)
        assertTrue(aggregated is MultiPageOcrResult.Success)

        val success = aggregated as MultiPageOcrResult.Success
        assertEquals(3, success.totalPages)
        assertEquals(2, success.pagesWithText)
        assertEquals(1, success.blankPages)

        val doc = success.document
        assertNotNull(doc)
        assertEquals(3, doc!!.totalPages)

        // Strict 1-based order
        assertEquals(1, doc.pages[0].pageIndex)
        assertEquals(OcrPageStatus.SUCCESS, doc.pages[0].status)
        assertEquals("Trang bìa tài liệu", doc.pages[0].resolvedText)

        assertEquals(2, doc.pages[1].pageIndex)
        assertEquals(OcrPageStatus.NO_TEXT, doc.pages[1].status)
        assertEquals("", doc.pages[1].resolvedText)

        assertEquals(3, doc.pages[2].pageIndex)
        assertEquals(OcrPageStatus.SUCCESS, doc.pages[2].status)
        assertEquals("Trang ký tên và đóng dấu", doc.pages[2].resolvedText)

        val validation = doc.validate()
        assertTrue("Document must pass validation: ${validation.errors}", validation.isValid)
    }

    @Test
    fun testCommitBeforeHandoffPersistsDocumentToDisk() = runBlocking {
        val pages = listOf(
            OcrResult.Success(
                text = "Hợp đồng thử nghiệm",
                engineId = "mlkit_latin",
                documentLanguage = "vi"
            )
        )

        val aggregated = MultiPageOcrAggregator.aggregate(pages) as MultiPageOcrResult.Success
        val doc = aggregated.document!!

        // Save to repository before screen handoff
        val saveResult = repository.saveDocument(doc)
        assertTrue(saveResult is RepositoryResult.Success)

        val savedDoc = (saveResult as RepositoryResult.Success).value
        assertNotNull(savedDoc.id)

        // Verify document is readable by ID
        val loadResult = repository.loadDocument(savedDoc.id)
        assertTrue(loadResult is RepositoryResult.Success)
        val loadedDoc = (loadResult as RepositoryResult.Success).value

        assertEquals(savedDoc.id, loadedDoc.id)
        assertEquals("Hợp đồng thử nghiệm", loadedDoc.fullText)
    }

    @Test
    fun testStaleWorkerDoesNotOverwriteNewerUserEdit() = runBlocking {
        val initialDoc = (MultiPageOcrAggregator.aggregate(
            listOf(OcrResult.Success(text = "Original OCR Text", engineId = "paddle", documentLanguage = "zh"))
        ) as MultiPageOcrResult.Success).document!!

        // 1. Initial save produces revision 2L
        val firstSave = repository.saveDocument(initialDoc)
        assertTrue(firstSave is RepositoryResult.Success)
        val r2 = (firstSave as RepositoryResult.Success).value
        assertEquals(2L, r2.revision)

        // 2. User makes an edit and saves with expectedRevision = 2L -> produces revision 3L
        val editedDoc = r2.copy(
            pages = listOf(
                r2.pages[0].copy(
                    editedContent = com.tscanner.app.ocr.model.OcrEditedContent(text = "User Edited Text")
                )
            )
        )
        val userSave = repository.saveDocument(editedDoc, expectedRevision = 2L)
        assertTrue(userSave is RepositoryResult.Success)
        val r3 = (userSave as RepositoryResult.Success).value
        assertEquals(3L, r3.revision)
        assertEquals("User Edited Text", r3.fullText)

        // 3. Stale background worker (started earlier with expectedRevision = 2L) finishes late
        // and attempts to overwrite with its old OCR result
        val staleWorkerDoc = r2.copy(title = "Stale Worker Title")
        val staleSaveResult = repository.saveDocument(staleWorkerDoc, expectedRevision = 2L)

        // Must reject stale overwrite with Conflict!
        assertTrue("CAS must reject stale save", staleSaveResult is RepositoryResult.Conflict)
        val conflict = staleSaveResult as RepositoryResult.Conflict
        assertEquals(3L, conflict.currentRevision)

        // 4. Verify disk still contains the user's edited revision 3L
        val diskDoc = (repository.loadDocument(initialDoc.id) as RepositoryResult.Success).value
        assertEquals(3L, diskDoc.revision)
        assertEquals("User Edited Text", diskDoc.fullText)
    }
}
