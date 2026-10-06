package com.tscanner.app

import android.app.Application
import com.tscanner.app.ocr.data.OcrDocumentRepository
import com.tscanner.app.ocr.export.DocxWriter
import com.tscanner.app.ocr.edit.OcrEditCommand
import com.tscanner.app.ocr.edit.OcrEditHistory
import com.tscanner.app.ocr.model.*
import com.tscanner.app.ui.ocr.reader.BoundedMemoryCache
import com.tscanner.app.ui.ocr.reader.OcrReaderViewModel
import com.tscanner.app.ui.ocr.reader.publishThenCache
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Permanent regressions promoted from the OCR reader/editor third reaudit probes. */
class OcrThirdReauditPermanentTest {
    @get:Rule val temp = TemporaryFolder()

    private fun doc() = OcrDocument(
        id = "stable",
        pages = listOf(OcrPage(
            "p1", 1, OcrPageStatus.SUCCESS,
            sourceBlocks = listOf(OcrBlock("b", lines = listOf(OcrLine("l", "original"))))
        ))
    )

    private fun viewModel(repo: OcrDocumentRepository, scope: CoroutineScope) =
        OcrReaderViewModel(Application(), androidx.lifecycle.SavedStateHandle(), repo, scope).apply {
            autosaveDebounceMs = 60_000L
        }

    @Test fun canceledCommitMustNotOverwriteAnotherCommittedWriter() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = OcrDocumentRepository(temp.newFolder())
            val initial = repo.saveDocument(doc()).getOrNull()!!
            val vm = viewModel(repo, scope)
            vm.initialize(initial.id, initial)
            val afterCommit = CompletableDeferred<Unit>()
            repo.postCommitFaultHook = { afterCommit.complete(Unit); awaitCancellation() }
            vm.autosaveDebounceMs = 10L
            vm.updateCurrentPageText("unacknowledged save")
            withTimeout(5_000) { afterCommit.await() }

            vm.autosaveDebounceMs = 60_000L
            vm.updateCurrentPageText("local draft")
            repo.postCommitFaultHook = null
            val committed = repo.loadDocument(initial.id).getOrNull()!!
            val other = committed.copy(pages = listOf(
                committed.pages[0].copy(editedContent = OcrEditedContent("other committed writer"))
            ))
            assertNotNull(repo.saveDocument(other, expectedRevision = committed.revision).getOrNull())

            assertFalse(vm.flushPendingSaves())
            assertEquals("other committed writer", repo.loadDocument(initial.id).getOrNull()!!.fullText)
        } finally {
            scope.cancel()
        }
    }

    @Test fun canceledOwnCommitCanBeAcknowledgedAndFollowedByLocalDraft() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = OcrDocumentRepository(temp.newFolder())
            val initial = repo.saveDocument(doc()).getOrNull()!!
            val vm = viewModel(repo, scope)
            vm.initialize(initial.id, initial)
            val afterCommit = CompletableDeferred<Unit>()
            repo.postCommitFaultHook = { afterCommit.complete(Unit); awaitCancellation() }
            vm.autosaveDebounceMs = 10L
            vm.updateCurrentPageText("my committed save")
            withTimeout(5_000) { afterCommit.await() }

            vm.autosaveDebounceMs = 60_000L
            vm.updateCurrentPageText("newer local draft")
            repo.postCommitFaultHook = null
            assertTrue(vm.flushPendingSaves())
            assertEquals("newer local draft", repo.loadDocument(initial.id).getOrNull()!!.fullText)
        } finally {
            scope.cancel()
        }
    }

    @Test fun canceledCommitMustNotBeMistakenForIdenticalContentFromAnotherWriter() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = OcrDocumentRepository(temp.newFolder())
            val initial = repo.saveDocument(doc()).getOrNull()!!
            val vm = viewModel(repo, scope)
            vm.initialize(initial.id, initial)
            val afterCommit = CompletableDeferred<Unit>()
            repo.postCommitFaultHook = { afterCommit.complete(Unit); awaitCancellation() }
            vm.autosaveDebounceMs = 10L
            vm.updateCurrentPageText("same visible content")
            withTimeout(5_000) { afterCommit.await() }

            vm.autosaveDebounceMs = 60_000L
            vm.updateCurrentPageText("local draft")
            repo.postCommitFaultHook = null
            val sameContent = repo.loadDocument(initial.id).getOrNull()!!
            val competingCommit = repo.saveDocument(
                sameContent.copy(), expectedRevision = sameContent.revision
            ).getOrNull()!!
            assertEquals(sameContent.fullText, competingCommit.fullText)
            assertNotEquals(sameContent.lastCommitToken, competingCommit.lastCommitToken)

            assertFalse(vm.flushPendingSaves())
            assertEquals("same visible content", repo.loadDocument(initial.id).getOrNull()!!.fullText)
        } finally {
            scope.cancel()
        }
    }

    @Test fun committedRecognitionReplacesSameDocumentRevisionInReaderAndExport() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = viewModel(OcrDocumentRepository(temp.newFolder()), scope)
            val original = doc().copy(revision = 2)
            vm.initialize(original.id, original)
            val fresh = original.copy(
                revision = 3,
                pages = listOf(original.pages[0].copy(editedContent = OcrEditedContent("new OCR")))
            )

            assertTrue(vm.applyCommittedRecognitionResult(fresh, expectedCurrentRevision = 2))
            assertEquals("new OCR", vm.document.value!!.fullText)
            assertEquals("new OCR", vm.createExportSnapshot()!!.fullText)
            assertFalse(vm.isDirty.value)
            assertFalse(vm.canUndo.value)
            assertFalse(vm.applyCommittedRecognitionResult(original, expectedCurrentRevision = 3))

            val staleVm = viewModel(OcrDocumentRepository(temp.newFolder()), scope)
            staleVm.initialize(original.id, original)
            staleVm.updateCurrentPageText("late local edit")
            assertFalse(staleVm.applyCommittedRecognitionResult(fresh, 2, original))
            assertEquals("late local edit", staleVm.document.value!!.fullText)
        } finally {
            scope.cancel()
        }
    }

    @Test fun persistedUserEditProvenanceSurvivesReopen() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repo = OcrDocumentRepository(temp.newFolder())
            val first = viewModel(repo, scope)
            first.initialize("stable", doc())
            first.updateCurrentPageText("user correction")
            assertTrue(first.flushPendingSaves())

            val saved = repo.loadDocument("stable").getOrNull()!!
            assertTrue(saved.hasUserEdits)
            val second = viewModel(repo, scope)
            second.initialize(saved.id, saved)
            assertTrue(second.hasUserEdits.value)
            assertEquals("user correction", second.createExportSnapshot()!!.fullText)
        } finally {
            scope.cancel()
        }
    }

    @Test fun imageDecodeSubsamplingHonorsByteBudgetAndDisplayedOversizeIsOwned() {
        val sample = OcrReaderViewModel.calculateSampleSize(4_000, 3_000, OcrReaderViewModel.MAX_CACHE_BYTES)
        val estimatedBytes = ((4_000L + sample - 1) / sample) *
            ((3_000L + sample - 1) / sample) * 4L
        assertTrue("Decoded allocation estimate must fit the cache budget", estimatedBytes <= OcrReaderViewModel.MAX_CACHE_BYTES)

        data class Image(val bytes: Long, var recycled: Boolean = false)
        var displayed: Image? = null
        val cache = BoundedMemoryCache<String, Image>(3, 24L * 1024L * 1024L, { it.bytes }) { _, image ->
            if (image !== displayed) image.recycled = true
        }
        val decoded = Image(4000L * 3000 * 4)
        publishThenCache(cache, "page", decoded) { displayed = it }
        assertFalse("Cache eviction must not recycle the displayed image", displayed!!.recycled)
    }

    @Test fun mixedFormattingSurvivesAppendInsertAndDelete() {
        val rich = OcrEditedContent("Bold plain", listOf(OcrParagraph(
            "para", text = "Bold plain", runs = listOf(OcrTextRun("Bold", isBold = true), OcrTextRun(" plain"))
        )))
        val history = OcrEditHistory(doc().copy(pages = listOf(doc().pages[0].copy(editedContent = rich))))

        history.execute(OcrEditCommand.ReplacePageText(1, "Bold plain", "Bold plain!"))
        var runs = history.currentDocument.pages[0].editedContent!!.paragraphs[0].runs
        assertEquals(listOf("Bold", " plain!"), runs.map { it.text })
        assertTrue(runs[0].isBold)
        assertFalse(runs[1].isBold)
        assertNotNull(history.undo())
        assertEquals(listOf("Bold", " plain"), history.currentDocument.pages[0].editedContent!!.paragraphs[0].runs.map { it.text })
        assertTrue(history.currentDocument.pages[0].editedContent!!.paragraphs[0].runs[0].isBold)
        assertNotNull(history.redo())

        history.execute(OcrEditCommand.ReplacePageText(1, "Bold plain!", "Bold! plain!"))
        runs = history.currentDocument.pages[0].editedContent!!.paragraphs[0].runs
        assertEquals(listOf("Bold!", " plain!"), runs.map { it.text })
        assertTrue(runs[0].isBold)
        assertFalse(runs[1].isBold)

        val output = ByteArrayOutputStream()
        DocxWriter.generateDocxStream(history.createExportSnapshot(), output, false, "")
        val documentXml = ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == "word/document.xml" }
            zip.readBytes().toString(Charsets.UTF_8)
        }
        assertTrue("DOCX must emit the retained bold run", documentXml.contains("<w:r><w:rPr><w:b/>"))
        assertTrue("DOCX must retain the unaffected plain run text", documentXml.contains(" plain!"))

        history.execute(OcrEditCommand.ReplacePageText(1, "Bold! plain!", "Bol! plain!"))
        runs = history.currentDocument.pages[0].editedContent!!.paragraphs[0].runs
        assertEquals(listOf("Bol!", " plain!"), runs.map { it.text })
        assertTrue(runs[0].isBold)
        assertFalse(runs[1].isBold)
    }

    @Test fun undoRedoRowAndColumnDeletionRestoresMergedSpansExactly() {
        val rowTable = OcrTable("tbl", 2, 2, cells = listOf(
            OcrTableCell("a", 0, 0, 2, 1, "merged rows", "merged rows", OcrCellType.TEXT),
            OcrTableCell("b", 0, 1, 1, 1, "top", "top", OcrCellType.TEXT),
            OcrTableCell("c", 1, 1, 1, 1, "bottom", "bottom", OcrCellType.TEXT)
        ))
        val history = OcrEditHistory(doc().copy(pages = listOf(doc().pages[0].copy(tables = listOf(rowTable)))))

        history.execute(OcrEditCommand.DeleteTableRow(1, "tbl", 1, emptyList()))
        val afterRowDelete = history.currentDocument.pages[0].tables.single()
        assertEquals(1, afterRowDelete.cells.first { it.cellId == "a" }.rowSpan)
        assertNotNull(history.undo())
        assertEquals(rowTable, history.currentDocument.pages[0].tables.single())
        assertNotNull(history.redo())
        assertEquals(afterRowDelete, history.currentDocument.pages[0].tables.single())

        val columnTable = OcrTable("tbl", 2, 2, cells = listOf(
            OcrTableCell("x", 0, 0, 1, 2, "merged columns", "merged columns", OcrCellType.TEXT),
            OcrTableCell("y", 1, 0, 1, 1, "left", "left", OcrCellType.TEXT),
            OcrTableCell("z", 1, 1, 1, 1, "right", "right", OcrCellType.TEXT)
        ))
        val columnHistory = OcrEditHistory(doc().copy(pages = listOf(doc().pages[0].copy(tables = listOf(columnTable)))))
        columnHistory.execute(OcrEditCommand.DeleteTableColumn(1, "tbl", 1, emptyList()))
        val afterColumnDelete = columnHistory.currentDocument.pages[0].tables.single()
        assertEquals(1, afterColumnDelete.cells.first { it.cellId == "x" }.colSpan)
        assertNotNull(columnHistory.undo())
        assertEquals(columnTable, columnHistory.currentDocument.pages[0].tables.single())
        assertNotNull(columnHistory.redo())
        assertEquals(afterColumnDelete, columnHistory.currentDocument.pages[0].tables.single())

        val originRowTable = OcrTable("tbl", 3, 1, cells = listOf(
            OcrTableCell("origin-row", 0, 0, 2, 1, "merged", "merged", OcrCellType.TEXT),
            OcrTableCell("last-row", 2, 0, 1, 1, "last", "last", OcrCellType.TEXT)
        ))
        val originRowHistory = OcrEditHistory(doc().copy(pages = listOf(
            doc().pages[0].copy(tables = listOf(originRowTable))
        )))
        originRowHistory.execute(OcrEditCommand.DeleteTableRow(1, "tbl", 0, emptyList()))
        assertEquals(1, originRowHistory.currentDocument.pages[0].tables.single().cells.first { it.cellId == "origin-row" }.rowSpan)
        assertNotNull(originRowHistory.undo())
        assertEquals(originRowTable, originRowHistory.currentDocument.pages[0].tables.single())

        val originColumnHistory = OcrEditHistory(doc().copy(pages = listOf(
            doc().pages[0].copy(tables = listOf(columnTable))
        )))
        originColumnHistory.execute(OcrEditCommand.DeleteTableColumn(1, "tbl", 0, emptyList()))
        assertEquals(1, originColumnHistory.currentDocument.pages[0].tables.single().cells.first { it.cellId == "x" }.colSpan)
        assertNotNull(originColumnHistory.undo())
        assertEquals(columnTable, originColumnHistory.currentDocument.pages[0].tables.single())
    }
}
