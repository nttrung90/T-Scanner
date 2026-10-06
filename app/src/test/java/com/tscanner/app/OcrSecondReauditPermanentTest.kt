package com.tscanner.app

import com.tscanner.app.ocr.model.*
import com.tscanner.app.ocr.edit.*
import com.tscanner.app.ocr.data.OcrDocumentRepository
import com.tscanner.app.ui.ocr.reader.OcrReaderViewModel
import com.tscanner.app.utils.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Permanent regression tests for OCR Reader/Editor reaudit round 2 (N01 to N06).
 * Complies with RECHECK_OCR_READER_EDITOR_ROUND2_2026-09-23.md.
 */
class OcrSecondReauditPermanentTest {
    @get:Rule val temp = TemporaryFolder()
    private fun page(id: String = "p1", text: String = "original") = OcrPage(
        pageId = id, pageIndex = 1, status = OcrPageStatus.SUCCESS,
        sourceBlocks = listOf(OcrBlock("b_$id", lines = listOf(OcrLine("l_$id", text)))))
    private fun doc() = OcrDocument(pages = listOf(page()))
    private fun aggregate() = (MultiPageOcrAggregator.aggregate(listOf(
        OcrResult.Success("first", "tesseract", "vi", pageDocument = page("p1", "first")),
        OcrResult.Success("second", "tesseract", "vi", pageDocument = page("p2", "second"))
    )) as MultiPageOcrResult.Success).document!!

    @Test fun enginePagesMustBeRenumberedBeforeEditing() {
        assertEquals(listOf(1, 2), aggregate().pages.map { it.pageIndex })
    }

    @Test fun editFirstPageMustNotOverwriteSecondPage() {
        val history = OcrEditHistory(aggregate())
        history.execute(OcrEditCommand.ReplacePageText(1, "first", "edited first"))
        assertEquals("second", history.currentDocument.pages[1].resolvedText)
    }

    @Test fun undoDuringSaveMustRemainVisibleAndDirty() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repo = OcrDocumentRepository(temp.newFolder())
            val vm = OcrReaderViewModel(android.app.Application(), androidx.lifecycle.SavedStateHandle(), repo, scope)
            vm.autosaveDebounceMs = 60000L
            vm.initialize("audit", doc())
            vm.updateCurrentPageText("edit")
            repo.preCommitFaultHook = { assertTrue(vm.undo()) }
            assertTrue(vm.flushPendingSaves())
            assertEquals("original", vm.document.value!!.pages[0].resolvedText)
            assertTrue(vm.isDirty.value)
        } finally { scope.cancel() }
    }

    @Test fun undoTextEditMustRestorePriorFormatting() {
        val styled = page().copy(editedContent = OcrEditedContent("original", listOf(
            OcrParagraph("para", text = "original", runs = listOf(OcrTextRun("original", isBold = true, fontSizePt = 18f)))
        )))
        val history = OcrEditHistory(OcrDocument(pages = listOf(styled)))
        history.execute(OcrEditCommand.ReplacePageText(1, "original", "original!"))
        history.undo()
        assertEquals(styled.editedContent, history.currentDocument.pages[0].editedContent)
    }

    @Test fun layoutParagraphRunsMustPreserveWordBoundaries() {
        val p = page().copy(sourceBlocks = listOf(OcrBlock("b", lines = listOf(
            OcrLine("a", "Hello world", boundingBox = OcrRect(0.1f, 0.1f, 0.8f, 0.14f)),
            OcrLine("b", "Next line", boundingBox = OcrRect(0.1f, 0.15f, 0.8f, 0.19f))
        ))))
        val enriched = MultiPageOcrAggregator.enrichPageWithLayoutAndTables(p)
        val paras = enriched.editedContent!!.paragraphs
        assertTrue(paras.isNotEmpty())
        paras.forEach { assertEquals("Rendered runs must match paragraph text", it.text, it.runs.joinToString("") { run -> run.text }) }
    }

    @Test fun deletingRowThroughMergedCellMustKeepValidGrid() {
        val table = OcrTable("tbl", 2, 2, cells = listOf(
            OcrTableCell("a", 0, 0, 2, 1, "merged", "merged", OcrCellType.TEXT),
            OcrTableCell("b", 0, 1, 1, 1, "top", "top", OcrCellType.TEXT),
            OcrTableCell("c", 1, 1, 1, 1, "bottom", "bottom", OcrCellType.TEXT)))
        val document = OcrDocument(pages = listOf(page().copy(tables = listOf(table))))
        val result = OcrEditReducer.apply(document, OcrEditCommand.DeleteTableRow(1, "tbl", 1, listOf(table.cells[2])))
        val validation = result.pages[0].tables[0].validateGrid()
        assertTrue(validation.errors.toString(), validation.isValid)
    }

    @Test fun canceledAutosaveAfterCommitMustRecoverRevision() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = OcrDocumentRepository(temp.newFolder())
            val initial = repo.saveDocument(doc()).getOrNull()!!
            val vm = OcrReaderViewModel(android.app.Application(), androidx.lifecycle.SavedStateHandle(), repo, scope)
            vm.initialize(initial.id, initial)
            val committed = CompletableDeferred<Unit>()
            repo.postCommitFaultHook = { committed.complete(Unit); awaitCancellation() }
            vm.autosaveDebounceMs = 10L
            vm.updateCurrentPageText("first saved edit")
            withTimeout(5000) { committed.await() }
            vm.autosaveDebounceMs = 60000L
            vm.updateCurrentPageText("latest edit")
            repo.postCommitFaultHook = null
            assertTrue("Autosave cancellation must not leave future saves permanently conflicting", vm.flushPendingSaves())
            assertEquals("latest edit", repo.loadDocument(initial.id).getOrNull()!!.fullText)
        } finally { scope.cancel() }
    }
}
