package com.tscanner.app

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.tscanner.app.ocr.data.OcrDocumentRepository
import com.tscanner.app.ocr.edit.*
import com.tscanner.app.ocr.export.DocxWriter
import com.tscanner.app.ocr.model.*
import com.tscanner.app.ui.ocr.reader.OcrReaderViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Regression coverage for completed round-four packages. */
class OcrFourthReauditPermanentTest {
    @get:Rule val temp = TemporaryFolder()

    private fun doc() = OcrDocument(
        id = "round4-r01",
        pages = listOf(OcrPage("p1", 1, OcrPageStatus.SUCCESS, editedContent = OcrEditedContent("original")))
    )

    private fun viewModel(repo: OcrDocumentRepository, scope: CoroutineScope) =
        OcrReaderViewModel(Application(), SavedStateHandle(), repo, scope).apply {
            autosaveDebounceMs = 60_000L
        }

    private fun assertCompleteGrid(table: OcrTable) {
        assertTrue(table.validateGrid().errors.joinToString(), table.validateGrid().isValid)
        for (row in 0 until table.rowCount) {
            for (column in 0 until table.columnCount) {
                assertEquals(
                    "Grid position ($row,$column) must have exactly one owner",
                    1,
                    table.cells.count {
                        row in it.rowIndex until it.rowIndex + it.rowSpan &&
                            column in it.colIndex until it.colIndex + it.colSpan
                    }
                )
            }
        }
    }

    @Test fun editingAfterSaveMustKeepLiveAndExportRevisionAndToken() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repo = OcrDocumentRepository(temp.newFolder())
            val initial = repo.saveDocument(doc()).getOrNull()!!
            val reader = viewModel(repo, scope)
            reader.initialize(initial.id, initial)

            reader.updateCurrentPageText("one")
            assertTrue(reader.flushPendingSaves())
            val firstCommit = repo.loadDocument(initial.id).getOrNull()!!
            assertEquals(firstCommit.revision, reader.document.value!!.revision)
            assertEquals(firstCommit.revision, reader.createExportSnapshot()!!.revision)
            assertEquals(firstCommit.lastCommitToken, reader.createExportSnapshot()!!.lastCommitToken)

            reader.updateCurrentPageText("two")
            assertEquals("A later edit must keep the acknowledged revision", firstCommit.revision, reader.document.value!!.revision)
            assertEquals(firstCommit.revision, reader.createExportSnapshot()!!.revision)
            assertTrue(reader.flushPendingSaves())
            val secondCommit = repo.loadDocument(initial.id).getOrNull()!!
            assertEquals(secondCommit.revision, reader.createExportSnapshot()!!.revision)
            assertEquals(secondCommit.lastCommitToken, reader.createExportSnapshot()!!.lastCommitToken)
            assertTrue(reader.canUndo.value)

            assertTrue(reader.undo())
            assertEquals("one", reader.document.value!!.fullText)
            assertTrue(reader.flushPendingSaves())
            val undoCommit = repo.loadDocument(initial.id).getOrNull()!!
            assertEquals(undoCommit.revision, reader.createExportSnapshot()!!.revision)
            assertTrue(reader.canRedo.value)

            assertTrue(reader.redo())
            assertEquals("two", reader.document.value!!.fullText)
            assertTrue(reader.flushPendingSaves())
            val redoCommit = repo.loadDocument(initial.id).getOrNull()!!
            assertEquals(redoCommit.revision, reader.createExportSnapshot()!!.revision)
            assertEquals(redoCommit.lastCommitToken, reader.createExportSnapshot()!!.lastCommitToken)
        } finally {
            scope.cancel()
        }
    }

    @Test fun editDuringSaveKeepsNewContentDirtyWithAcknowledgedMetadata() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = OcrDocumentRepository(temp.newFolder())
        try {
            val initial = repo.saveDocument(doc()).getOrNull()!!
            val reader = viewModel(repo, scope)
            reader.initialize(initial.id, initial)
            reader.updateCurrentPageText("saved snapshot")
            repo.postCommitFaultHook = { reader.updateCurrentPageText("newer draft") }

            assertTrue(reader.flushPendingSaves())
            val acknowledged = repo.loadDocument(initial.id).getOrNull()!!
            val live = reader.document.value!!
            val export = reader.createExportSnapshot()!!
            assertEquals("saved snapshot", acknowledged.fullText)
            assertEquals("newer draft", live.fullText)
            assertEquals("newer draft", export.fullText)
            assertEquals(acknowledged.revision, live.revision)
            assertEquals(acknowledged.revision, export.revision)
            assertEquals(acknowledged.lastCommitToken, export.lastCommitToken)
            assertTrue("The draft created during save must remain dirty", reader.isDirty.value)

            repo.postCommitFaultHook = null
            assertTrue(reader.flushPendingSaves())
            val finalCommit = repo.loadDocument(initial.id).getOrNull()!!
            assertEquals("newer draft", finalCommit.fullText)
            assertEquals(finalCommit.revision, reader.createExportSnapshot()!!.revision)
            assertFalse(reader.isDirty.value)
        } finally {
            repo.postCommitFaultHook = null
            scope.cancel()
        }
    }

    @Test fun successfulReconcileRetryAcknowledgesHistoryMetadata() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repo = OcrDocumentRepository(temp.newFolder())
            val initial = repo.saveDocument(doc()).getOrNull()!!
            val reader = viewModel(repo, scope)
            reader.initialize(initial.id, initial)
            reader.updateCurrentPageText("first snapshot")

            val afterCommit = CompletableDeferred<Unit>()
            repo.postCommitFaultHook = { afterCommit.complete(Unit); awaitCancellation() }
            val firstSave = scope.launch { reader.flushPendingSaves() }
            withTimeout(5_000) { afterCommit.await() }
            firstSave.cancelAndJoin()

            reader.updateCurrentPageText("retry draft")
            repo.postCommitFaultHook = null
            assertTrue(reader.flushPendingSaves())
            val committed = repo.loadDocument(initial.id).getOrNull()!!
            assertEquals("retry draft", committed.fullText)
            assertEquals(committed.revision, reader.document.value!!.revision)
            assertEquals(committed.revision, reader.createExportSnapshot()!!.revision)
            assertEquals(committed.lastCommitToken, reader.createExportSnapshot()!!.lastCommitToken)
            assertTrue(reader.canUndo.value)
        } finally {
            scope.cancel()
        }
    }

    @Test fun cancellationAfterReconcileRetryMustRemainRecoverable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repo = OcrDocumentRepository(temp.newFolder())
            val initial = repo.saveDocument(doc()).getOrNull()!!
            val reader = viewModel(repo, scope)
            reader.initialize(initial.id, initial)

            reader.updateCurrentPageText("first snapshot")
            val firstCommitted = CompletableDeferred<Unit>()
            repo.postCommitFaultHook = { firstCommitted.complete(Unit); awaitCancellation() }
            val firstSave = launch { reader.flushPendingSaves() }
            withTimeout(5_000) { firstCommitted.await() }
            firstSave.cancelAndJoin()

            reader.updateCurrentPageText("retry snapshot")
            val retryCommitted = CompletableDeferred<Unit>()
            repo.postCommitFaultHook = { retryCommitted.complete(Unit); awaitCancellation() }
            val retrySave = launch { reader.flushPendingSaves() }
            withTimeout(5_000) { retryCommitted.await() }
            retrySave.cancelAndJoin()

            repo.postCommitFaultHook = null
            reader.updateCurrentPageText("latest draft")
            assertTrue("The retry commit must be recognized after cancellation", reader.flushPendingSaves())
            val disk = repo.loadDocument(initial.id).getOrNull()!!
            assertEquals("latest draft", disk.fullText)
            assertEquals(disk.revision, reader.createExportSnapshot()!!.revision)
        } finally {
            scope.cancel()
        }
    }

    @Test fun cancelledRetryMustNotOverwriteAnotherCommittedWriter() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = OcrDocumentRepository(temp.newFolder())
        try {
            val initial = repo.saveDocument(doc()).getOrNull()!!
            val reader = viewModel(repo, scope)
            reader.initialize(initial.id, initial)

            reader.updateCurrentPageText("first snapshot")
            val firstCommitted = CompletableDeferred<Unit>()
            repo.postCommitFaultHook = { firstCommitted.complete(Unit); awaitCancellation() }
            val firstSave = launch { reader.flushPendingSaves() }
            withTimeout(5_000) { firstCommitted.await() }
            firstSave.cancelAndJoin()

            reader.updateCurrentPageText("retry snapshot")
            val retryCommitted = CompletableDeferred<Unit>()
            repo.postCommitFaultHook = { retryCommitted.complete(Unit); awaitCancellation() }
            val retrySave = launch { reader.flushPendingSaves() }
            withTimeout(5_000) { retryCommitted.await() }
            retrySave.cancelAndJoin()
            repo.postCommitFaultHook = null

            val beforeExternalWrite = repo.loadDocument(initial.id).getOrNull()!!
            val external = repo.saveDocument(
                beforeExternalWrite.copy(pages = beforeExternalWrite.pages.map {
                    it.copy(editedContent = OcrEditedContent("other writer"))
                }),
                expectedRevision = beforeExternalWrite.revision
            ).getOrNull()!!
            reader.updateCurrentPageText("stale local draft")

            assertFalse("A cancelled retry token cannot authorize overwriting another writer", reader.flushPendingSaves())
            assertEquals("other writer", repo.loadDocument(initial.id).getOrNull()!!.fullText)
            assertEquals(external.revision, repo.loadDocument(initial.id).getOrNull()!!.revision)
        } finally {
            repo.postCommitFaultHook = null
            scope.cancel()
        }
    }

    @Test fun cancellationBeforeCommitMustNotPoisonLaterSave() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = OcrDocumentRepository(temp.newFolder())
        try {
            val initial = repo.saveDocument(doc()).getOrNull()!!
            val reader = viewModel(repo, scope)
            reader.initialize(initial.id, initial)
            reader.updateCurrentPageText("precommit draft")

            val beforeCommit = CompletableDeferred<Unit>()
            repo.preCommitFaultHook = { beforeCommit.complete(Unit); awaitCancellation() }
            val save = launch { reader.flushPendingSaves() }
            withTimeout(5_000) { beforeCommit.await() }
            save.cancelAndJoin()

            repo.preCommitFaultHook = null
            assertTrue("A normal save after pre-commit cancellation should succeed", reader.flushPendingSaves())
            assertEquals("precommit draft", repo.loadDocument(initial.id).getOrNull()!!.fullText)
        } finally {
            repo.preCommitFaultHook = null
            scope.cancel()
        }
    }

    @Test fun recognitionCommitFlushesAndReplacesReaderExportAndHistoryTogether() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repo = OcrDocumentRepository(temp.newFolder())
            val initial = repo.saveDocument(doc()).getOrNull()!!
            val reader = viewModel(repo, scope)
            reader.initialize(initial.id, initial)
            reader.updateCurrentPageText("editor text before OCR")

            val base = reader.captureRecognitionBase()!!
            assertEquals("editor text before OCR", repo.loadDocument(initial.id).getOrNull()!!.fullText)
            assertFalse(reader.isDirty.value)

            val recognition = base.document.copy(pages = base.document.pages.map {
                it.copy(editedContent = OcrEditedContent("new OCR result"))
            })
            val result = reader.commitRecognitionResult(base, recognition)
            assertTrue("Recognition should commit from its flushed base", result is OcrReaderViewModel.RecognitionCommitResult.Success)
            val disk = repo.loadDocument(initial.id).getOrNull()!!
            val live = reader.document.value!!
            val export = reader.createExportSnapshot()!!
            assertEquals("new OCR result", disk.fullText)
            assertEquals(disk, live)
            assertEquals(disk, export)
            assertFalse(reader.canUndo.value)
            assertFalse(reader.isDirty.value)
        } finally {
            scope.cancel()
        }
    }

    @Test fun editAfterRecognitionSnapshotMustRejectBeforeDiskCommit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repo = OcrDocumentRepository(temp.newFolder())
            val initial = repo.saveDocument(doc()).getOrNull()!!
            val reader = viewModel(repo, scope)
            reader.initialize(initial.id, initial)
            val base = reader.captureRecognitionBase()!!
            reader.updateCurrentPageText("new user draft")
            assertTrue(reader.flushPendingSaves())

            val recognition = base.document.copy(pages = base.document.pages.map {
                it.copy(editedContent = OcrEditedContent("stale OCR result"))
            })
            assertEquals(
                OcrReaderViewModel.RecognitionCommitResult.Stale,
                reader.commitRecognitionResult(base, recognition)
            )
            assertEquals("new user draft", repo.loadDocument(initial.id).getOrNull()!!.fullText)
            assertEquals("new user draft", reader.document.value!!.fullText)
        } finally {
            scope.cancel()
        }
    }

    @Test fun recognitionCommitConflictMustPreserveExternalWriter() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repo = OcrDocumentRepository(temp.newFolder())
            val initial = repo.saveDocument(doc()).getOrNull()!!
            val reader = viewModel(repo, scope)
            reader.initialize(initial.id, initial)
            val base = reader.captureRecognitionBase()!!
            val external = repo.saveDocument(
                initial.copy(pages = initial.pages.map { it.copy(editedContent = OcrEditedContent("external writer")) }),
                expectedRevision = base.document.revision
            ).getOrNull()!!

            val recognition = base.document.copy(pages = base.document.pages.map {
                it.copy(editedContent = OcrEditedContent("stale OCR result"))
            })
            assertTrue(
                reader.commitRecognitionResult(base, recognition) is OcrReaderViewModel.RecognitionCommitResult.Conflict
            )
            assertEquals("external writer", repo.loadDocument(initial.id).getOrNull()!!.fullText)
            assertEquals(external.revision, repo.loadDocument(initial.id).getOrNull()!!.revision)
            assertEquals("original", reader.document.value!!.fullText)
        } finally {
            scope.cancel()
        }
    }

    @Test fun editDuringRecognitionCommitMustBeRestoredWithCas() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = OcrDocumentRepository(temp.newFolder())
        try {
            val initial = repo.saveDocument(doc()).getOrNull()!!
            val reader = viewModel(repo, scope)
            reader.initialize(initial.id, initial)
            val base = reader.captureRecognitionBase()!!
            val recognition = base.document.copy(pages = base.document.pages.map {
                it.copy(editedContent = OcrEditedContent("new OCR result"))
            })
            repo.postCommitFaultHook = { reader.updateCurrentPageText("edit during commit") }

            assertEquals(
                OcrReaderViewModel.RecognitionCommitResult.Stale,
                reader.commitRecognitionResult(base, recognition)
            )
            val disk = repo.loadDocument(initial.id).getOrNull()!!
            assertEquals("edit during commit", disk.fullText)
            assertEquals("edit during commit", reader.document.value!!.fullText)
            assertEquals(disk.revision, reader.document.value!!.revision)
        } finally {
            repo.postCommitFaultHook = null
            scope.cancel()
        }
    }

    @Test fun cancellationAfterRecognitionDiskCommitMustStillPublishViewModelState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = OcrDocumentRepository(temp.newFolder())
        try {
            val initial = repo.saveDocument(doc()).getOrNull()!!
            val reader = viewModel(repo, scope)
            reader.initialize(initial.id, initial)
            val base = reader.captureRecognitionBase()!!
            val recognition = base.document.copy(pages = base.document.pages.map {
                it.copy(editedContent = OcrEditedContent("committed OCR"))
            })
            val committedOnDisk = CompletableDeferred<Unit>()
            val allowRepositoryReturn = CompletableDeferred<Unit>()
            repo.postCommitFaultHook = {
                committedOnDisk.complete(Unit)
                allowRepositoryReturn.await()
            }

            val commitJob = launch { reader.commitRecognitionResult(base, recognition) }
            withTimeout(5_000) { committedOnDisk.await() }
            commitJob.cancel()
            allowRepositoryReturn.complete(Unit)
            commitJob.join()

            assertEquals("committed OCR", repo.loadDocument(initial.id).getOrNull()!!.fullText)
            assertEquals("committed OCR", reader.document.value!!.fullText)
            assertEquals(repo.loadDocument(initial.id).getOrNull()!!.revision, reader.document.value!!.revision)
        } finally {
            repo.postCommitFaultHook = null
            scope.cancel()
        }
    }

    @Test fun insertingBlankLineBeforeStyledParagraphPreservesMovedParagraphMetadata() {
        val rich = OcrEditedContent("head\nBold", listOf(
            OcrParagraph("head-id", sourceAnchorLineId = "line-head", text = "head", runs = listOf(OcrTextRun("head"))),
            OcrParagraph(
                "bold-id", sourceAnchorLineId = "line-bold", text = "Bold", alignment = OcrTextAlignment.CENTER,
                runs = listOf(OcrTextRun("Bold", isBold = true, fontSizePt = 16f))
            )
        ))
        val source = doc().copy(pages = listOf(doc().pages[0].copy(editedContent = rich)))
        val history = OcrEditHistory(source)
        history.execute(OcrEditCommand.ReplacePageText(1, "head\nBold", "head\n\nBold", timestamp = 1234L))

        val paragraphs = history.currentDocument.pages.single().editedContent!!.paragraphs
        assertEquals(listOf("head", "", "Bold"), paragraphs.map { it.text })
        assertEquals("bold-id", paragraphs[2].paragraphId)
        assertEquals("line-bold", paragraphs[2].sourceAnchorLineId)
        assertEquals(OcrTextAlignment.CENTER, paragraphs[2].alignment)
        assertTrue(paragraphs[2].runs.single().isBold)
        assertEquals("edit_1_1234_1", paragraphs[1].paragraphId)

        assertEquals(source.pages.single().editedContent, history.undo()!!.pages.single().editedContent)
        assertEquals("bold-id", history.redo()!!.pages.single().editedContent!!.paragraphs[2].paragraphId)
    }

    @Test fun splittingAndRejoiningAcrossMixedRunsPreservesFormattingAndDocx() {
        val rich = OcrEditedContent("Bold plain", listOf(OcrParagraph(
            "mixed", text = "Bold plain", alignment = OcrTextAlignment.RIGHT,
            runs = listOf(OcrTextRun("Bold", isBold = true), OcrTextRun(" plain"))
        )))
        val history = OcrEditHistory(doc().copy(pages = listOf(doc().pages[0].copy(editedContent = rich))))
        history.execute(OcrEditCommand.ReplacePageText(1, "Bold plain", "Bold\n plain", timestamp = 5678L))

        var paragraphs = history.currentDocument.pages.single().editedContent!!.paragraphs
        assertEquals("Bold", paragraphs[0].text)
        assertTrue(paragraphs[0].runs.single().isBold)
        assertEquals(" plain", paragraphs[1].text)
        assertFalse(paragraphs[1].runs.single().isBold)
        assertEquals(OcrTextAlignment.RIGHT, paragraphs[0].alignment)
        assertEquals(OcrTextAlignment.RIGHT, paragraphs[1].alignment)

        assertEquals(rich, history.undo()!!.pages.single().editedContent)
        history.redo()
        paragraphs = history.currentDocument.pages.single().editedContent!!.paragraphs
        assertTrue(paragraphs[0].runs.single().isBold)
        assertFalse(paragraphs[1].runs.single().isBold)

        val output = ByteArrayOutputStream()
        DocxWriter.generateDocxStream(history.createExportSnapshot(), output, false, "")
        val documentXml = ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == "word/document.xml" }
            zip.readBytes().toString(Charsets.UTF_8)
        }
        assertTrue("DOCX must retain bold run after paragraph split", documentXml.contains("<w:r><w:rPr><w:b/>"))
        assertTrue("DOCX must retain the plain moved run", documentXml.contains(" plain"))
    }

    @Test fun deletingParagraphBoundaryKeepsRunsFromBothOriginalParagraphs() {
        val rich = OcrEditedContent("Bold\nplain", listOf(
            OcrParagraph("bold-id", text = "Bold", runs = listOf(OcrTextRun("Bold", isBold = true))),
            OcrParagraph("plain-id", text = "plain", runs = listOf(OcrTextRun("plain")))
        ))
        val history = OcrEditHistory(doc().copy(pages = listOf(doc().pages[0].copy(editedContent = rich))))
        history.execute(OcrEditCommand.ReplacePageText(1, "Bold\nplain", "Boldplain", timestamp = 9876L))
        val merged = history.currentDocument.pages.single().editedContent!!.paragraphs.single()
        assertEquals(listOf("Bold", "plain"), merged.runs.map { it.text })
        assertTrue(merged.runs.first().isBold)
        assertFalse(merged.runs.last().isBold)

        assertEquals(rich, history.undo()!!.pages.single().editedContent)
        history.redo()
        val redone = history.currentDocument.pages.single().editedContent!!.paragraphs.single()
        assertEquals(listOf("Bold", "plain"), redone.runs.map { it.text })
        assertTrue(redone.runs.first().isBold)
        assertFalse(redone.runs.last().isBold)
    }

    @Test fun insertingRowInsideMergedCellExpandsSpanAndUndoRedoRestoresGrid() {
        val original = OcrTable("row-merge", 2, 2, cells = listOf(
            OcrTableCell("vertical", 0, 0, rowSpan = 2, rawText = "merged"),
            OcrTableCell("right-top", 0, 1, rawText = "top"),
            OcrTableCell("right-bottom", 1, 1, rawText = "bottom")
        ))
        val history = OcrEditHistory(doc().copy(pages = listOf(doc().pages[0].copy(tables = listOf(original)))))
        history.execute(OcrEditCommand.AddTableRow(1, "row-merge", 1, listOf(
            OcrTableCell("right-inserted", 1, 1, rawText = "new")
        )))

        val inserted = history.currentDocument.pages.single().tables.single()
        assertEquals(3, inserted.rowCount)
        assertEquals(3, inserted.cells.single { it.cellId == "vertical" }.rowSpan)
        assertCompleteGrid(inserted)
        assertEquals(original, history.undo()!!.pages.single().tables.single())
        assertEquals(inserted, history.redo()!!.pages.single().tables.single())
    }

    @Test fun insertingColumnInsideMergedCellExpandsSpanAndUndoRedoRestoresGrid() {
        val original = OcrTable("column-merge", 2, 2, cells = listOf(
            OcrTableCell("horizontal", 0, 0, colSpan = 2, rawText = "merged"),
            OcrTableCell("bottom-left", 1, 0, rawText = "left"),
            OcrTableCell("bottom-right", 1, 1, rawText = "right")
        ))
        val history = OcrEditHistory(doc().copy(pages = listOf(doc().pages[0].copy(tables = listOf(original)))))
        history.execute(OcrEditCommand.AddTableColumn(1, "column-merge", 1, listOf(
            OcrTableCell("bottom-inserted", 1, 1, rawText = "new")
        )))

        val inserted = history.currentDocument.pages.single().tables.single()
        assertEquals(3, inserted.columnCount)
        assertEquals(3, inserted.cells.single { it.cellId == "horizontal" }.colSpan)
        assertCompleteGrid(inserted)
        assertEquals(original, history.undo()!!.pages.single().tables.single())
        assertEquals(inserted, history.redo()!!.pages.single().tables.single())
    }

    @Test fun overlappingNewCellsInsideMergeAreRejectedWithoutChangingHistory() {
        val rowTable = OcrTable("row-overlap", 2, 1, cells = listOf(
            OcrTableCell("vertical", 0, 0, rowSpan = 2, rawText = "merged")
        ))
        val rowHistory = OcrEditHistory(doc().copy(pages = listOf(doc().pages[0].copy(tables = listOf(rowTable)))))
        try {
            rowHistory.execute(OcrEditCommand.AddTableRow(1, "row-overlap", 1, listOf(
                OcrTableCell("overlap", 1, 0, rawText = "invalid")
            )))
            fail("Inserted row cell must not overlap a merged cell expanded across that row")
        } catch (_: IllegalArgumentException) {
            assertEquals(rowTable, rowHistory.currentDocument.pages.single().tables.single())
            assertFalse(rowHistory.canUndo)
        }

        val columnTable = OcrTable("column-overlap", 1, 2, cells = listOf(
            OcrTableCell("horizontal", 0, 0, colSpan = 2, rawText = "merged")
        ))
        val columnHistory = OcrEditHistory(doc().copy(pages = listOf(doc().pages[0].copy(tables = listOf(columnTable)))))
        try {
            columnHistory.execute(OcrEditCommand.AddTableColumn(1, "column-overlap", 1, listOf(
                OcrTableCell("overlap", 0, 1, rawText = "invalid")
            )))
            fail("Inserted column cell must not overlap a merged cell expanded across that column")
        } catch (_: IllegalArgumentException) {
            assertEquals(columnTable, columnHistory.currentDocument.pages.single().tables.single())
            assertFalse(columnHistory.canUndo)
        }
    }

    @Test fun legacyDocumentWithoutEditProvenanceMustRequireReplacementConfirmation() {
        val legacy = OcrDocument.fromJson(JSONObject().apply {
            put("id", "legacy-with-unknown-provenance")
            put("pages", org.json.JSONArray())
        })
        assertTrue("Missing provenance must not be treated as verified untouched OCR", legacy.hasUserEdits)
        assertTrue("After migration, explicit provenance must survive another reload", OcrDocument.fromJson(legacy.toJson()).hasUserEdits)

        val explicitUntouched = OcrDocument.fromJson(JSONObject().apply {
            put("id", "known-untouched")
            put("hasUserEdits", false)
            put("pages", org.json.JSONArray())
        })
        assertFalse("New/current documents with explicit false should not prompt", explicitUntouched.hasUserEdits)
        assertFalse(OcrDocument(id = "new-document").hasUserEdits)
    }
}
