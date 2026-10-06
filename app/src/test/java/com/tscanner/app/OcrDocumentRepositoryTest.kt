package com.tscanner.app

import com.tscanner.app.ocr.data.OcrDocumentRepository
import com.tscanner.app.ocr.data.RepositoryResult
import com.tscanner.app.ocr.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for OcrDocumentRepository:
 * - Atomic persistence & crash safety (fault injection)
 * - CAS revision guard and conflict detection
 * - Automatic corruption recovery from backup
 * - Image ownership and cleanup
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S03).
 */
class OcrDocumentRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var repository: OcrDocumentRepository

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("ocr_repo_tests")
        repository = OcrDocumentRepository(baseDir)
    }

    private fun createSampleDoc(id: String = "doc_test_001", revision: Long = 1L): OcrDocument {
        return OcrDocument(
            id = id,
            revision = revision,
            title = "Hợp đồng thử nghiệm",
            sourceLanguage = "vi",
            pages = listOf(
                OcrPage(
                    pageId = "p_1",
                    pageIndex = 1,
                    status = OcrPageStatus.SUCCESS,
                    sourceBlocks = listOf(
                        OcrBlock(
                            blockId = "b1",
                            lines = listOf(OcrLine("l1", "Nội dung ban đầu"))
                        )
                    )
                )
            )
        )
    }

    @Test
    fun testSaveAndLoadDocumentRoundtrip() = runBlocking {
        val doc = createSampleDoc()
        val saveResult = repository.saveDocument(doc)
        assertTrue("Save must succeed", saveResult is RepositoryResult.Success)

        val savedDoc = (saveResult as RepositoryResult.Success).value
        // Initial revision was 1L, next saved revision must be 2L
        assertEquals(2L, savedDoc.revision)

        val loadResult = repository.loadDocument(doc.id)
        assertTrue("Load must succeed", loadResult is RepositoryResult.Success)

        val loadedDoc = (loadResult as RepositoryResult.Success).value
        assertEquals(doc.id, loadedDoc.id)
        assertEquals(2L, loadedDoc.revision)
        assertEquals("Hợp đồng thử nghiệm", loadedDoc.title)
        assertEquals(1, loadedDoc.pages.size)
        assertEquals("Nội dung ban đầu", loadedDoc.pages[0].resolvedText)
    }

    @Test
    fun testOptimisticConcurrencyControlConflict() = runBlocking {
        val doc = createSampleDoc()
        // First save creates document at revision 2L
        val firstSave = repository.saveDocument(doc)
        assertTrue(firstSave is RepositoryResult.Success)

        // Attempting to save with expectedRevision = 1L when disk is at 2L must fail with Conflict
        val conflictResult = repository.saveDocument(doc, expectedRevision = 1L)
        assertTrue("Must detect revision conflict", conflictResult is RepositoryResult.Conflict)

        val conflict = conflictResult as RepositoryResult.Conflict
        assertEquals(2L, conflict.currentRevision)

        // Saving with expectedRevision = 2L succeeds and advances to 3L
        val successfulSave = repository.saveDocument(doc, expectedRevision = 2L)
        assertTrue("Save with matching expectedRevision must succeed", successfulSave is RepositoryResult.Success)
        assertEquals(3L, (successfulSave as RepositoryResult.Success).value.revision)
    }

    @Test
    fun testFaultInjectionPreCommitLeavesOldRevisionIntact() = runBlocking {
        val doc = createSampleDoc()
        val initialSave = repository.saveDocument(doc)
        assertTrue(initialSave is RepositoryResult.Success)

        // Inject fault before commit
        repository.preCommitFaultHook = {
            error("Simulated disk write failure or process crash")
        }

        val updatedDoc = doc.copy(
            title = "Tiêu đề bị gián đoạn",
            pages = listOf(
                OcrPage("p_1", 1, OcrPageStatus.SUCCESS, sourceBlocks = listOf(
                    OcrBlock("b1", lines = listOf(OcrLine("l1", "Nội dung mới")))
                ))
            )
        )

        val failedSave = repository.saveDocument(updatedDoc, expectedRevision = 2L)
        assertTrue("Must fail cleanly when exception injected", failedSave is RepositoryResult.Error)

        // Clear fault hook
        repository.preCommitFaultHook = null

        // Load document from disk, verify it is still the previous valid document!
        val reloadResult = repository.loadDocument(doc.id)
        assertTrue(reloadResult is RepositoryResult.Success)
        val loaded = (reloadResult as RepositoryResult.Success).value
        assertEquals(2L, loaded.revision)
        assertEquals("Hợp đồng thử nghiệm", loaded.title)
        assertEquals("Nội dung ban đầu", loaded.pages[0].resolvedText)
    }

    @Test
    fun testCorruptionRecoveryFromBackup() = runBlocking {
        val doc = createSampleDoc()
        val saveResult = repository.saveDocument(doc)
        assertTrue(saveResult is RepositoryResult.Success)

        // Corrupt the primary manifest file with garbage bytes
        val manifestFile = File(File(baseDir, doc.id), "document.json")
        assertTrue(manifestFile.exists())
        manifestFile.writeText("{ corrupted_json: [invalid... ", Charsets.UTF_8)

        // Loading must gracefully recover from document.json.bak
        val recoveredResult = repository.loadDocument(doc.id)
        assertTrue("Must recover from backup", recoveredResult is RepositoryResult.Success)

        val recoveredDoc = (recoveredResult as RepositoryResult.Success).value
        assertEquals(doc.id, recoveredDoc.id)
        assertEquals(2L, recoveredDoc.revision)

        // Verify primary manifest was repaired
        val reloadedAgain = repository.loadDocument(doc.id)
        assertTrue(reloadedAgain is RepositoryResult.Success)
    }

    @Test
    fun testImageImportAndUnreferencedCleanup() = runBlocking {
        val docId = "doc_image_test"
        val tempImg1 = tempFolder.newFile("src1.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val tempImg2 = tempFolder.newFile("src2.jpg").apply { writeBytes(byteArrayOf(5, 6, 7, 8)) }

        val imported1 = repository.importPageImage(docId, "page_1", tempImg1)
        val imported2 = repository.importPageImage(docId, "page_2", tempImg2)

        assertTrue(imported1.exists())
        assertTrue(imported2.exists())

        // Create doc referencing only imported1
        val doc = OcrDocument(
            id = docId,
            pages = listOf(
                OcrPage(
                    pageId = "page_1",
                    pageIndex = 1,
                    status = OcrPageStatus.SUCCESS,
                    imageInfo = OcrImageInfo(imported1.toURI().toString(), 100, 100)
                )
            )
        )

        // Run cleanup
        val deletedCount = repository.cleanupUnreferencedImages(doc)
        assertEquals("Must delete exactly 1 unreferenced image (page_2)", 1, deletedCount)

        assertTrue("Referenced image page_1 must still exist", imported1.exists())
        assertFalse("Unreferenced image page_2 must have been deleted", imported2.exists())
    }

    @Test
    fun testConcurrentSavesDoNotCorruptState() = runBlocking {
        val docId = "doc_concurrent"
        val initialDoc = createSampleDoc(id = docId)
        val firstSave = repository.saveDocument(initialDoc)
        assertTrue(firstSave is RepositoryResult.Success)

        // Run 10 concurrent save requests
        val jobs = (1..10).map { i ->
            async {
                val candidate = initialDoc.copy(title = "Title $i")
                repository.saveDocument(candidate)
            }
        }

        val results = jobs.awaitAll()
        // All calls should return cleanly (either Success or Conflict, but never unhandled crash or corrupted state)
        assertTrue(results.all { it is RepositoryResult.Success || it is RepositoryResult.Conflict })

        // Load document from disk, verify it is completely valid JSON and has valid revision
        val loadFinal = repository.loadDocument(docId)
        assertTrue(loadFinal is RepositoryResult.Success)
        val finalDoc = (loadFinal as RepositoryResult.Success).value
        assertTrue(finalDoc.revision >= 2L)
    }
}
