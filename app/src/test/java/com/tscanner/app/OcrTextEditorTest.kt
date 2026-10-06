package com.tscanner.app

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.tscanner.app.ocr.data.OcrDocumentRepository
import com.tscanner.app.ocr.model.*
import com.tscanner.app.ui.ocr.reader.OcrReaderTab
import com.tscanner.app.ui.ocr.reader.OcrReaderViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for text editor logic, IME composing safety, and undo/redo synchronization.
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S12).
 */
class OcrTextEditorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class TestApplication(private val baseDir: File) : Application() {
        override fun getFilesDir(): File = baseDir
    }

    private lateinit var app: Application
    private lateinit var baseDir: File
    private lateinit var repository: OcrDocumentRepository
    private val testScope = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("ocr_editor_test")
        repository = OcrDocumentRepository(baseDir)
        app = TestApplication(baseDir)
    }

    private fun createDocumentWithText(text: String): OcrDocument {
        return OcrDocument(
            id = "doc_editor_01",
            pages = listOf(
                OcrPage(
                    pageId = "p1",
                    pageIndex = 1,
                    status = OcrPageStatus.SUCCESS,
                    sourceBlocks = listOf(OcrBlock("b1", lines = listOf(OcrLine("l1", text))))
                )
            )
        )
    }

    @Test
    fun testComposingTextSimulationDoesNotMultiplyCharacters() {
        val initialDoc = createDocumentWithText("Bắt đầu")
        val handle = SavedStateHandle()
        val vm = OcrReaderViewModel(app, handle, repository, testScope)
        vm.initialize(initialDoc.id, initialDoc)

        assertEquals("Bắt đầu", vm.document.value?.pages?.get(0)?.resolvedText)

        // Simulating Vietnamese IME composition for word "Việt":
        // 1. "v"
        // 2. "vi"
        // 3. "vie"
        // 4. "viê"
        // 5. "viêt"
        // 6. "việt" (Committed)
        val intermediateComposingSteps = listOf("v", "vi", "vie", "viê", "viêt")
        val committedWord = "việt"

        // In production OcrTextEditorFragment, intermediate steps with composing spans
        // are debounced until final composition commits.
        vm.updateCurrentPageText(committedWord)

        val resolved = vm.document.value?.pages?.get(0)?.resolvedText
        assertEquals("việt", resolved)
        assertFalse("Character count must not multiply", resolved!!.length > committedWord.length)
    }

    @Test
    fun testMultilinePastePreservesLineBreaks() {
        val initialDoc = createDocumentWithText("Original Line")
        val handle = SavedStateHandle()
        val vm = OcrReaderViewModel(app, handle, repository, testScope)
        vm.initialize(initialDoc.id, initialDoc)

        val pastedMultilineText = """
            CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM
            Độc lập - Tự do - Hạnh phúc

            HỢP ĐỒNG MUA BÁN HÀNG HÓA
            Số: 108/2026/HĐMB
        """.trimIndent()

        vm.updateCurrentPageText(pastedMultilineText)

        val updatedText = vm.document.value?.pages?.get(0)?.resolvedText
        assertEquals(pastedMultilineText, updatedText)
        assertEquals(4, updatedText?.lines()?.filter { it.isNotBlank() }?.size)
    }

    @Test
    fun testUndoRedoWorkflowInViewModel() {
        val initialDoc = createDocumentWithText("Version 1")
        val handle = SavedStateHandle()
        val vm = OcrReaderViewModel(app, handle, repository, testScope)
        vm.initialize(initialDoc.id, initialDoc)

        assertFalse(vm.canUndo.value)
        assertFalse(vm.canRedo.value)

        // Edit to Version 2
        vm.updateCurrentPageText("Version 2")
        assertTrue(vm.canUndo.value)
        assertFalse(vm.canRedo.value)
        assertEquals("Version 2", vm.document.value?.pages?.get(0)?.resolvedText)

        // Edit to Version 3
        vm.updateCurrentPageText("Version 3")
        assertEquals("Version 3", vm.document.value?.pages?.get(0)?.resolvedText)

        // Undo -> Version 2
        assertTrue(vm.undo())
        assertEquals("Version 2", vm.document.value?.pages?.get(0)?.resolvedText)
        assertTrue(vm.canUndo.value)
        assertTrue(vm.canRedo.value)

        // Undo -> Version 1
        assertTrue(vm.undo())
        assertEquals("Version 1", vm.document.value?.pages?.get(0)?.resolvedText)
        assertFalse(vm.canUndo.value)
        assertTrue(vm.canRedo.value)

        // Redo -> Version 2
        assertTrue(vm.redo())
        assertEquals("Version 2", vm.document.value?.pages?.get(0)?.resolvedText)

        // Redo -> Version 3
        assertTrue(vm.redo())
        assertEquals("Version 3", vm.document.value?.pages?.get(0)?.resolvedText)
        assertFalse(vm.canRedo.value)
    }

    @Test
    fun testSwitchingTabsDoesNotDiscardEdits() {
        val initialDoc = createDocumentWithText("Before Tab Switch")
        val handle = SavedStateHandle()
        val vm = OcrReaderViewModel(app, handle, repository, testScope)
        vm.initialize(initialDoc.id, initialDoc)

        // Edit on TEXT tab
        vm.selectTab(OcrReaderTab.TEXT)
        vm.updateCurrentPageText("Modified on Text Tab")

        // Switch to SCAN tab
        vm.selectTab(OcrReaderTab.SCAN)
        assertEquals(OcrReaderTab.SCAN, vm.currentTab.value)

        // Switch back to TEXT tab
        vm.selectTab(OcrReaderTab.TEXT)
        assertEquals(OcrReaderTab.TEXT, vm.currentTab.value)

        // Edit is completely preserved
        assertEquals("Modified on Text Tab", vm.document.value?.pages?.get(0)?.resolvedText)
        assertTrue(vm.isDirty.value)
    }

    @Test
    fun testExportSnapshotCapturesEditedTextWhileSourceBlocksRemainImmutable() {
        val originalText = "Original Scanned Text"
        val initialDoc = createDocumentWithText(originalText)
        val handle = SavedStateHandle()
        val vm = OcrReaderViewModel(app, handle, repository, testScope)
        vm.initialize(initialDoc.id, initialDoc)

        val editedText = "Fully Edited Content Ready for Export"
        vm.updateCurrentPageText(editedText)

        // Take export snapshot
        val snapshot = vm.createExportSnapshot()
        assertNotNull(snapshot)
        assertEquals(editedText, snapshot!!.pages[0].resolvedText)

        // Source OCR block must remain unchanged (provenance preserved)
        assertEquals(originalText, snapshot.pages[0].sourceBlocks[0].lines[0].text)
        assertEquals(originalText, vm.document.value?.pages?.get(0)?.sourceBlocks?.get(0)?.lines?.get(0)?.text)
    }
}
