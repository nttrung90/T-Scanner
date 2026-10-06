package com.tscanner.app

import com.tscanner.app.ocr.model.*
import com.tscanner.app.ocr.edit.*
import com.tscanner.app.ocr.export.DocxWriter
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import com.tscanner.app.ocr.data.OcrDocumentRepository
import com.tscanner.app.ui.ocr.reader.OcrReaderViewModel

class OcrIndependentAuditTest {
    @get:Rule val temp = TemporaryFolder()
    private fun original() = OcrDocument(pages = listOf(OcrPage(
        pageId = "p1", pageIndex = 1, status = OcrPageStatus.SUCCESS,
        sourceBlocks = listOf(OcrBlock("b1", lines = listOf(OcrLine("l1", "original")))))))

    private fun xml(doc: OcrDocument): String {
        val bytes = ByteArrayOutputStream()
        DocxWriter.generateDocxStream(doc, bytes, false, "")
        ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: error("document.xml missing")
                if (entry.name == "word/document.xml") return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
    }

    @Test fun secondTextEditMustReachDocx() {
        val history = OcrEditHistory(original())
        history.execute(OcrEditCommand.ReplacePageText(1, "original", "first edit"))
        history.execute(OcrEditCommand.ReplacePageText(1, "first edit", "latest edit"))
        assertEquals("latest edit", history.currentDocument.pages[0].resolvedText)
        assertTrue("DOCX must contain latest edit", xml(history.createExportSnapshot()).contains("latest edit"))
    }

    @Test fun deletingAllTextMustRemainEmpty() {
        val history = OcrEditHistory(original())
        history.execute(OcrEditCommand.ReplacePageText(1, "original", ""))
        assertEquals("User deletion must not resurrect OCR text", "", history.currentDocument.pages[0].resolvedText)
    }

    @Test fun verticalMergeMustBeRepresentedInDocx() {
        val table = OcrTable("tbl", 2, 2, cells = listOf(
            OcrTableCell("a", 0, 0, 2, 1, "merged", "merged", OcrCellType.TEXT),
            OcrTableCell("b", 0, 1, 1, 1, "top", "top", OcrCellType.TEXT),
            OcrTableCell("c", 1, 1, 1, 1, "bottom", "bottom", OcrCellType.TEXT)))
        val doc = original().copy(pages = listOf(original().pages[0].copy(tables = listOf(table))))
        assertTrue("rowSpan must be emitted as Word vertical merge", xml(doc).contains("w:vMerge"))
    }

    @Test fun readerViewModelMustExposeDefaultFactoryConstructor() {
        val constructors = com.tscanner.app.ui.ocr.reader.OcrReaderViewModel::class.java.constructors
        val supported = constructors.any { c ->
            c.parameterTypes.toList() == listOf(android.app.Application::class.java, androidx.lifecycle.SavedStateHandle::class.java)
        }
        assertTrue("Default SavedState factory requires public (Application, SavedStateHandle): " + constructors.joinToString(), supported)
    }

    @Test fun manualTableMustReachExportSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repo = OcrDocumentRepository(temp.newFolder())
            val vm = OcrReaderViewModel(android.app.Application(), androidx.lifecycle.SavedStateHandle(), repo, scope)
            vm.autosaveDebounceMs = 60000L
            vm.initialize("audit", original())
            vm.createManualTable()
            assertEquals(1, vm.document.value!!.pages[0].tables.size)
            assertEquals("Visible manual table must also be exported", 1, vm.createExportSnapshot()!!.pages[0].tables.size)
        } finally { scope.cancel() }
    }

    @Test fun savingOlderSnapshotMustNotEraseNewEdit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repo = OcrDocumentRepository(temp.newFolder())
            val vm = OcrReaderViewModel(android.app.Application(), androidx.lifecycle.SavedStateHandle(), repo, scope)
            vm.autosaveDebounceMs = 60000L
            vm.initialize("audit", original())
            vm.updateCurrentPageText("saving version")
            repo.preCommitFaultHook = { vm.updateCurrentPageText("new edit during save") }
            assertTrue(vm.flushPendingSaves())
            assertEquals("A completed old save must not replace newer text", "new edit during save", vm.document.value!!.pages[0].resolvedText)
            assertTrue("Newer edit remains dirty", vm.isDirty.value)
        } finally { scope.cancel() }
    }
}
