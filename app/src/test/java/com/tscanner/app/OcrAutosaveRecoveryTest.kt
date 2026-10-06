package com.tscanner.app

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.tscanner.app.ocr.data.OcrDocumentRepository
import com.tscanner.app.ocr.data.RepositoryResult
import com.tscanner.app.ocr.model.*
import com.tscanner.app.ui.ocr.reader.DocumentSaveState
import com.tscanner.app.ui.ocr.reader.OcrReaderViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for Autosave debounce, flush on exit, crash recovery, and CAS concurrency protection.
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S14).
 */
class OcrAutosaveRecoveryTest {

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
        baseDir = tempFolder.newFolder("ocr_autosave_test")
        repository = OcrDocumentRepository(baseDir)
        app = TestApplication(baseDir)
    }

    private fun createSampleDoc(text: String = "Văn bản ban đầu"): OcrDocument {
        return OcrDocument(
            id = "doc_autosave_01",
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
    fun testFlushPendingSavesPersistsImmediatelyToDisk() = runBlocking {
        val initialDoc = createSampleDoc()
        val handle = SavedStateHandle()
        val vm = OcrReaderViewModel(app, handle, repository, testScope)
        vm.autosaveDebounceMs = 5000L // Long debounce
        vm.initialize(initialDoc.id, initialDoc)

        // Make an edit
        vm.updateCurrentPageText("Nội dung đã sửa khẩn cấp")
        assertTrue(vm.isDirty.value)
        assertEquals(DocumentSaveState.DIRTY, vm.saveState.value)

        // Flush immediately without waiting for 5s debounce
        val flushed = vm.flushPendingSaves()
        assertTrue("Flush must succeed", flushed)
        assertFalse(vm.isDirty.value)
        assertEquals(DocumentSaveState.SAVED, vm.saveState.value)

        // Verify disk contains the flushed edit
        val loadResult = repository.loadDocument(initialDoc.id)
        assertTrue(loadResult is RepositoryResult.Success)
        val loaded = (loadResult as RepositoryResult.Success).value
        assertEquals("Nội dung đã sửa khẩn cấp", loaded.fullText)
    }

    @Test
    fun testProcessDeathAfterCommitRecoversFullyFromDisk() = runBlocking {
        val initialDoc = createSampleDoc()
        val handle = SavedStateHandle()
        val vm = OcrReaderViewModel(app, handle, repository, testScope)
        vm.initialize(initialDoc.id, initialDoc)

        vm.updateCurrentPageText("Phiên bản đã lưu thành công trước khi crash")
        vm.flushPendingSaves()

        // Simulate complete process death: instantiate new ViewModel and load directly from repository
        val newProcessRepo = OcrDocumentRepository(baseDir)
        val loadResult = newProcessRepo.loadDocument(initialDoc.id)
        assertTrue(loadResult is RepositoryResult.Success)

        val restored = (loadResult as RepositoryResult.Success).value
        assertEquals("Phiên bản đã lưu thành công trước khi crash", restored.fullText)
        assertTrue(restored.revision >= 2L)
    }

    @Test
    fun testProcessDeathBeforeCommitLeavesLastSavedRevisionIntact() = runBlocking {
        val initialDoc = createSampleDoc("Bản lưu chắc chắn số 1")
        val saveInit = repository.saveDocument(initialDoc)
        assertTrue(saveInit is RepositoryResult.Success)
        val savedDoc = (saveInit as RepositoryResult.Success).value

        val handle = SavedStateHandle()
        val vm = OcrReaderViewModel(app, handle, repository, testScope)
        vm.initialize(savedDoc.id, savedDoc)

        // User makes an uncommitted edit in memory
        vm.updateCurrentPageText("Bản nháp đang gõ dở dang chưa kịp lưu")
        assertTrue(vm.isDirty.value)

        // Process killed before flush/commit: reload from disk
        val reloadedDoc = (repository.loadDocument(initialDoc.id) as RepositoryResult.Success).value
        // Must still be the last safely committed version!
        assertEquals("Bản lưu chắc chắn số 1", reloadedDoc.fullText)
    }

    @Test
    fun testDiskFailureNeverReportsFakeSavedSuccess() = runBlocking {
        val initialDoc = createSampleDoc()
        val handle = SavedStateHandle()
        val vm = OcrReaderViewModel(app, handle, repository, testScope)
        vm.initialize(initialDoc.id, initialDoc)

        // Inject disk failure (e.g. disk full / read-only)
        repository.preCommitFaultHook = {
            error("ENOSPC: No space left on device")
        }

        vm.updateCurrentPageText("Nội dung khi ổ đĩa bị đầy")
        val saveSuccess = vm.flushPendingSaves()

        assertFalse("Must return false when disk write fails", saveSuccess)
        // Must indicate ERROR state, NEVER report fake SAVED success!
        assertEquals(DocumentSaveState.ERROR, vm.saveState.value)

        repository.preCommitFaultHook = null
    }

    @Test
    fun testExportNeverReadsStaleDraftWhenDirty() = runBlocking {
        val initialDoc = createSampleDoc("Old Text")
        val handle = SavedStateHandle()
        val vm = OcrReaderViewModel(app, handle, repository, testScope)
        vm.initialize(initialDoc.id, initialDoc)
        vm.flushPendingSaves()

        // User makes a change, leaving it dirty
        vm.updateCurrentPageText("Newest Unsaved Edit")
        assertTrue(vm.isDirty.value)

        // prepareDocumentForExport flushes pending saves before creating snapshot
        val exportDoc = vm.prepareDocumentForExport()
        assertNotNull(exportDoc)
        assertEquals("Newest Unsaved Edit", exportDoc!!.fullText)
        assertFalse("Dirty state must be cleared after export preparation", vm.isDirty.value)
        assertEquals(DocumentSaveState.SAVED, vm.saveState.value)
    }

    @Test
    fun testCasRevisionGuardRejectsStaleAutosave() = runBlocking {
        val initialDoc = createSampleDoc()
        val saveInit = repository.saveDocument(initialDoc)
        assertTrue(saveInit is RepositoryResult.Success)
        val savedDoc = (saveInit as RepositoryResult.Success).value

        val handle = SavedStateHandle()
        val vm = OcrReaderViewModel(app, handle, repository, testScope)
        vm.initialize(savedDoc.id, savedDoc)

        val currentDiskDoc = (repository.loadDocument(initialDoc.id) as RepositoryResult.Success).value

        // Simulate concurrent process advancing disk revision behind the scenes
        val externalBumpDoc = currentDiskDoc.copy(title = "Externally Modified Title")
        repository.saveDocument(externalBumpDoc, expectedRevision = currentDiskDoc.revision)

        // Now local VM tries to autosave with old expectedRevision
        vm.updateCurrentPageText("Conflicting Local Text")
        val flushed = vm.flushPendingSaves()

        // Must reject overwrite with conflict error
        assertFalse(flushed)
        assertEquals(DocumentSaveState.ERROR, vm.saveState.value)
    }
}
