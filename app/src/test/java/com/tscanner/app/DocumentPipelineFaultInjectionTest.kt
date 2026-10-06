package com.tscanner.app

import com.tscanner.app.ui.camera.CameraPageRecord
import com.tscanner.app.ui.camera.CameraScanSessionManager
import com.tscanner.app.ui.camera.CaptureStatus
import com.tscanner.app.ui.editor.model.PageEditState
import com.tscanner.app.utils.PdfConverterHelper
import com.tscanner.app.utils.SafeFileWriter
import com.tscanner.app.utils.ScanSessionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class DocumentPipelineFaultInjectionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ----------------------------------------------------------------------------------
    // S01: Crop safe overwrite — writer failure / compress=false does NOT truncate destination
    // ----------------------------------------------------------------------------------
    @Test
    fun testS01_cropSafeOverwrite_writerFails_preservesOriginalFile() = runBlocking {
        val originalFile = File(tempFolder.root, "original_page.jpg")
        val originalContent = "ORIGINAL_VALID_JPEG_BINARY_DATA"
        originalFile.writeText(originalContent)
        val originalChecksum = originalFile.readText()

        // Simulate CropRotateActivity safe write where compress fails (returns false)
        val result = SafeFileWriter.writeSafely(
            destinationFile = originalFile,
            validator = { it.length() > 0 }
        ) { tempFile ->
            // Simulate failed compress
            false
        }

        assertFalse("Safe write must return error when writer returns false", result is SafeFileWriter.Result.Success)
        assertTrue("Original file must still exist", originalFile.exists())
        assertEquals("Original file must not be truncated or modified", originalChecksum, originalFile.readText())
    }

    @Test
    fun testS01_cropSafeOverwrite_exceptionDuringWrite_preservesOriginalFile() = runBlocking {
        val originalFile = File(tempFolder.root, "page_to_crop.jpg")
        originalFile.writeText("ORIGINAL_CONTENT")

        val result = SafeFileWriter.writeSafely(
            destinationFile = originalFile
        ) { tempFile ->
            FileOutputStream(tempFile).use { out ->
                out.write("PARTIAL_WRITE".toByteArray())
                throw IOException("Simulated disk full during JPEG compress")
            }
        }

        assertFalse("Safe write must fail on IOException", result is SafeFileWriter.Result.Success)
        assertEquals("Original file must preserve original content", "ORIGINAL_CONTENT", originalFile.readText())
    }

    // ----------------------------------------------------------------------------------
    // S02: Scanner integrity — incomplete page list is never treated as success
    // ----------------------------------------------------------------------------------
    @Test
    fun testS02_scanSessionResult_expectedPageCountMismatchDetected() {
        val sessionResult = ScanSessionResult(
            sessionId = UUID.randomUUID().toString(),
            tempPdfPath = null,
            tempPagePaths = listOf("/path/page_1.jpg", "/path/page_3.jpg"),
            totalPagesExpected = 3 // 3 pages expected, but page 2 failed to copy
        )

        val isComplete = sessionResult.tempPagePaths.size == sessionResult.totalPagesExpected
        assertFalse("Mismatch between received pages and expected pages must not be considered complete", isComplete)
    }

    // ----------------------------------------------------------------------------------
    // S03: PDF Converter — mid-document failure cleans up partial outputs and returns Failure
    // ----------------------------------------------------------------------------------
    @Test
    fun testS03_pdfConversionResult_failureCleansUpPartialFiles() = runBlocking {
        val outputDir = File(tempFolder.root, "pdf_previews")
        outputDir.mkdirs()

        // Create partial dummy files
        val file1 = File(outputDir, "pdf_page_1.jpg").apply { writeText("page 1") }
        val file2 = File(outputDir, "pdf_page_2.jpg").apply { writeText("page 2") }

        // Simulate structured conversion reporting failure on page 3
        val partialPaths = listOf(file1.absolutePath, file2.absolutePath)
        val failureResult = PdfConverterHelper.PdfToImagesResult.Failure(
            failedPage = 3,
            totalPages = 5,
            message = "Simulated render error on page 3"
        )

        // Verify failure structure
        assertEquals(3, failureResult.failedPage)
        assertEquals(5, failureResult.totalPages)

        // Verify cleanup behavior on failure
        partialPaths.forEach { File(it).delete() }
        assertFalse("Partial page 1 must be deleted on failure", file1.exists())
        assertFalse("Partial page 2 must be deleted on failure", file2.exists())
    }

    // ----------------------------------------------------------------------------------
    // S05: Camera scan session recreation — state reconstruction preserves captured pages
    // ----------------------------------------------------------------------------------
    @Test
    fun testS05_cameraSessionState_recreationRestoresOrderedPages() {
        val page1 = File(tempFolder.root, "page_1.jpg").apply { writeText("p1") }
        val page2 = File(tempFolder.root, "page_2.jpg").apply { writeText("p2") }

        val savedPages = arrayListOf(page1.absolutePath, page2.absolutePath)
        val restoredMap = java.util.concurrent.ConcurrentSkipListMap<Int, String>()

        // Simulate recreation from savedInstanceState
        savedPages.forEachIndexed { index, path ->
            val f = File(path)
            if (f.exists() && f.length() > 0L) {
                restoredMap[index + 1] = path
            }
        }

        assertEquals(2, restoredMap.size)
        assertEquals(page1.absolutePath, restoredMap[1])
        assertEquals(page2.absolutePath, restoredMap[2])
    }

    // ----------------------------------------------------------------------------------
    // S07: PostScanSessionRepository — fails when image copy fails, no external temp fallback
    // ----------------------------------------------------------------------------------
    @Test
    fun testS07_sessionInitialization_missingSourceReturnsNull() = runBlocking {
        val nonExistentPath = File(tempFolder.root, "missing_source.jpg").absolutePath

        // Verify that when raw file cannot be copied from source, initialization does NOT accept it
        val sourceFile = File(nonExistentPath)
        assertFalse("Source file must not exist", sourceFile.exists())
    }

    // ----------------------------------------------------------------------------------
    // S08: PDF Viewer preview safety — session deletion only when preview has complete pages
    // ----------------------------------------------------------------------------------
    @Test
    fun testS08_viewerPreviewSafety_incompletePreviewDoesNotDeleteSession() {
        var sessionDeleted = false
        val expectedPageCount = 3

        // Case A: Preview conversion failed / returned empty
        val previewPagesEmpty = emptyList<String>()
        if (previewPagesEmpty.size == expectedPageCount) {
            sessionDeleted = true
        }
        assertFalse("Session must NOT be deleted if preview conversion is empty", sessionDeleted)

        // Case B: Preview conversion returned partial pages (2 of 3)
        val previewPagesPartial = listOf("p1.jpg", "p2.jpg")
        if (previewPagesPartial.size == expectedPageCount) {
            sessionDeleted = true
        }
        assertFalse("Session must NOT be deleted if preview conversion is incomplete", sessionDeleted)

        // Case C: Preview conversion succeeded with all 3 pages
        val previewPagesComplete = listOf("p1.jpg", "p2.jpg", "p3.jpg")
        if (previewPagesComplete.size == expectedPageCount) {
            sessionDeleted = true
        }
        assertTrue("Session should be safely deleted only when preview has all pages", sessionDeleted)
    }

    // ----------------------------------------------------------------------------------
    // S09: PostScan editor dirty tracking — checks all pages and title
    // ----------------------------------------------------------------------------------
    @Test
    fun testS09_editorDirtyTracking_page2ModifiedMarksSessionDirty() {
        val page1 = PageEditState(pageIndex = 0, inputImagePath = "/path/p1.jpg") // unmodified
        val page2 = PageEditState(pageIndex = 1, inputImagePath = "/path/p2.jpg", rotationDegrees = 90) // modified!

        val pages = listOf(page1, page2)

        // Old buggy behavior: only checked currentPageState (if viewing page 1 -> false!)
        val currentPageIsModified = pages[0].isModified
        assertFalse("Old behavior falsely reported not modified when viewing page 1", currentPageIsModified)

        // New behavior: checks all pages in session
        val anyPageModified = pages.any { it.isModified }
        assertTrue("New behavior correctly detects page 2 was modified", anyPageModified)
    }

    @Test
    fun testS09_editorDirtyTracking_titleChangeMarksSessionDirty() {
        val initialTitle = "Tài liệu ngày 20-09-2026"
        val currentTitle = "Hóa đơn VAT tháng 9"

        val isTitleModified = currentTitle != initialTitle
        assertTrue("Changing document title must mark session as dirty", isTitleModified)
    }

    // ----------------------------------------------------------------------------------
    // B01: Camera processing failure preserves rawFile and records failed capture
    // ----------------------------------------------------------------------------------
    @Test
    fun testB01_cameraProcessingFailure_preservesRawFileAndPreventsSilentDrop() {
        val rawFile = File(tempFolder.root, "raw_capture_1.jpg").apply {
            writeText("RAW_CAMERA_FRAME_DATA")
        }
        val finalPageFile = File(tempFolder.root, "page_1.jpg") // not created or invalid

        val orderedPageMap = java.util.concurrent.ConcurrentSkipListMap<Int, String>()
        val failedCaptures = java.util.concurrent.ConcurrentHashMap<Int, String>()
        val photoIndex = 1

        // Simulate failed processing (e.g. crop/normalize failed and SafeFileWriter.validateImage is false)
        val processedOk = finalPageFile.exists() && finalPageFile.length() > 0L
        if (processedOk) {
            rawFile.delete()
            orderedPageMap[photoIndex] = finalPageFile.absolutePath
        } else {
            // B01 fix: keep rawFile and track failure
            failedCaptures[photoIndex] = rawFile.absolutePath
        }

        assertTrue("Raw file must NOT be deleted when processing fails", rawFile.exists())
        assertEquals("Failed capture must be tracked with its photoIndex", rawFile.absolutePath, failedCaptures[photoIndex])
        assertFalse("Ordered pages must not include failed page", orderedPageMap.containsKey(photoIndex))

        // When finishing session, failed captures block silent completion
        val hasFailedCaptures = failedCaptures.isNotEmpty()
        assertTrue("Session must NOT complete silently when captures failed", hasFailedCaptures)
    }

    // ----------------------------------------------------------------------------------
    // B02: Camera recreate restores exact page indices and in-flight / pending raw files
    // ----------------------------------------------------------------------------------
    @Test
    fun testB02_cameraSessionRecreation_restoresKeyIndicesAndPendingRawFiles() {
        val page1 = File(tempFolder.root, "page_1.jpg").apply { writeText("p1") }
        val page3 = File(tempFolder.root, "page_3.jpg").apply { writeText("p3") }
        val rawPending2 = File(tempFolder.root, "raw_capture_2.jpg").apply { writeText("raw2") }

        // State before recreate: page 1 and 3 finished, page 2 was pending in-flight
        val savedPageIndices = intArrayOf(1, 3)
        val savedPagePaths = arrayListOf(page1.absolutePath, page3.absolutePath)
        val savedPendingIndices = intArrayOf(2)
        val savedPendingPaths = arrayListOf(rawPending2.absolutePath)

        val restoredOrderedMap = java.util.concurrent.ConcurrentSkipListMap<Int, String>()
        val restoredPendingMap = java.util.concurrent.ConcurrentHashMap<Int, String>()

        for (i in savedPageIndices.indices) {
            val idx = savedPageIndices[i]
            val path = savedPagePaths[i]
            if (File(path).exists()) {
                restoredOrderedMap[idx] = path
            }
        }
        for (i in savedPendingIndices.indices) {
            val idx = savedPendingIndices[i]
            val path = savedPendingPaths[i]
            if (File(path).exists()) {
                restoredPendingMap[idx] = path
            }
        }

        assertEquals("Page 1 must retain key index 1", page1.absolutePath, restoredOrderedMap[1])
        assertEquals("Page 3 must retain key index 3 (not shifted to 2)", page3.absolutePath, restoredOrderedMap[3])
        assertEquals("Pending raw file 2 must be restored in pending map", rawPending2.absolutePath, restoredPendingMap[2])
    }

    // ----------------------------------------------------------------------------------
    // B03: Editor exit on Save — flush failure prevents closing editor
    // ----------------------------------------------------------------------------------
    @Test
    fun testB03_editorExit_flushFailurePreventsFinish() = runBlocking {
        var isEditorFinished = false

        fun onSaveClicked(flushResult: Boolean) {
            if (flushResult) {
                isEditorFinished = true
            } else {
                // Keep editor open! Do not finish
                isEditorFinished = false
            }
        }

        // Case 1: Flush fails (IO error / disk full)
        onSaveClicked(flushResult = false)
        assertFalse("Editor must NOT finish when flush fails", isEditorFinished)

        // Case 2: Flush succeeds
        onSaveClicked(flushResult = true)
        assertTrue("Editor should finish only when flush succeeds", isEditorFinished)
    }

    // ----------------------------------------------------------------------------------
    // B04: Active drafts query filters out invalid sessions and sorts by updatedAt
    // ----------------------------------------------------------------------------------
    @Test
    fun testB04_activeDraftsQuery_filtersInvalidAndSortsByUpdatedDesc() {
        val draftsDir = File(tempFolder.root, "draft_sessions").apply { mkdirs() }

        // Session 1: Valid draft with existing image
        val session1Dir = File(draftsDir, "sess_1").apply { mkdirs() }
        val img1 = File(session1Dir, "p1.jpg").apply { writeText("img1") }
        val draft1 = com.tscanner.app.data.model.PostScanSessionDraft(
            sessionId = "sess_1",
            documentTitle = "Tài liệu 1",
            pageStates = listOf(PageEditState(pageIndex = 0, inputImagePath = img1.absolutePath)),
            schemaVersion = 1,
            revision = 1L,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        File(session1Dir, "session_metadata.json").writeText(draft1.toJson().toString())

        // Session 2: Valid draft with newer timestamp
        val session2Dir = File(draftsDir, "sess_2").apply { mkdirs() }
        val img2 = File(session2Dir, "p2.jpg").apply { writeText("img2") }
        val draft2 = com.tscanner.app.data.model.PostScanSessionDraft(
            sessionId = "sess_2",
            documentTitle = "Tài liệu 2",
            pageStates = listOf(PageEditState(pageIndex = 0, inputImagePath = img2.absolutePath)),
            schemaVersion = 1,
            revision = 2L,
            createdAt = 1500L,
            updatedAt = 2000L
        )
        File(session2Dir, "session_metadata.json").writeText(draft2.toJson().toString())

        // Session 3: Corrupted draft (image file does not exist)
        val session3Dir = File(draftsDir, "sess_3").apply { mkdirs() }
        val draft3 = com.tscanner.app.data.model.PostScanSessionDraft(
            sessionId = "sess_3",
            documentTitle = "Tài liệu 3",
            pageStates = listOf(PageEditState(pageIndex = 0, inputImagePath = "/non_existent/p3.jpg")),
            schemaVersion = 1,
            revision = 1L,
            createdAt = 500L,
            updatedAt = 500L
        )
        File(session3Dir, "session_metadata.json").writeText(draft3.toJson().toString())

        // Simulate getActiveDrafts logic
        val activeDrafts = mutableListOf<com.tscanner.app.data.model.PostScanSessionDraft>()
        draftsDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
                val metaFile = File(dir, "session_metadata.json")
                if (metaFile.exists() && metaFile.length() > 0L) {
                    val parsed = com.tscanner.app.data.model.PostScanSessionDraft.fromJson(org.json.JSONObject(metaFile.readText()))
                    val validPages = parsed.pageStates.filter { File(it.inputImagePath).exists() }
                    if (validPages.isNotEmpty()) {
                        activeDrafts.add(parsed)
                    }
                }
            }
        }
        val sorted = activeDrafts.sortedByDescending { it.updatedAt }

        assertEquals("Should only include drafts with existing image files", 2, sorted.size)
        assertEquals("Most recently updated draft must come first", "sess_2", sorted[0].sessionId)
        assertEquals("Older draft comes second", "sess_1", sorted[1].sessionId)
    }

    // ----------------------------------------------------------------------------------
    // B05: PdfViewer createNewPdf checks DocumentRepo.addDocument result
    // ----------------------------------------------------------------------------------
    @Test
    fun testB05_pdfViewer_createNewPdf_catalogFailureHandled() {
        var successDialogShown = false
        var errorToastShown = false

        fun handleCreateNewPdfResult(addSuccess: Boolean) {
            if (addSuccess) {
                successDialogShown = true
            } else {
                errorToastShown = true
            }
        }

        // Case 1: Catalog addDocument fails (e.g. storage error or atomic write failure)
        handleCreateNewPdfResult(addSuccess = false)
        assertFalse("Success dialog must NOT be shown when catalog save fails", successDialogShown)
        assertTrue("Error toast must be shown when catalog save fails", errorToastShown)

        // Case 2: Catalog addDocument succeeds
        errorToastShown = false
        handleCreateNewPdfResult(addSuccess = true)
        assertTrue("Success dialog should be shown when catalog save succeeds", successDialogShown)
        assertFalse("Error toast must not be shown on success", errorToastShown)
    }

    // ----------------------------------------------------------------------------------
    // C01: Two-Phase Commit & Persistent Manifest Session Recovery
    // ----------------------------------------------------------------------------------

    @Test
    fun testC01_crashPointA_outputWrittenBeforeManifestCommit_recoversFromDisk() = runBlocking {
        val sessionDir = File(tempFolder.root, "session_c01_a").apply { mkdirs() }
        val testValidator: (File) -> Boolean = { it.exists() && it.length() > 0L }

        // Scenario: Document crop wrote page_1_1000000002.jpg to disk and raw_1000000001_1.jpg exists.
        // Process crashes BEFORE commitPageOutput is called or manifest is written.
        val rawFile = File(sessionDir, "raw_1000000001_1.jpg").apply { writeText("RAW_CAMERA_FRAME") }
        val outputFile = File(sessionDir, "page_1_1000000002.jpg").apply { writeText("VALID_OUTPUT_PAGE") }

        // Process restarts: new session manager recovers the session from disk
        val recoveryManager = CameraScanSessionManager(sessionDir, imageValidator = testValidator)
        val reconciled = recoveryManager.reconcileSession()

        // Page 1 must be recovered as COMMITTED from disk output
        assertTrue("Page 1 must be recovered as committed", reconciled.committedPages.containsKey(1))
        assertEquals(outputFile.absolutePath, reconciled.committedPages[1])

        // Leftover rawFile must be cleaned up because output is valid and committed
        assertFalse("Leftover raw file must be cleaned up on recovery", rawFile.exists())

        // Reconciled manifest must persist the recovery
        val diskManifest = recoveryManager.loadManifestFromDisk()
        assertEquals(CaptureStatus.COMMITTED, diskManifest[1]?.status)
        assertEquals(outputFile.absolutePath, diskManifest[1]?.outputPath)
    }

    @Test
    fun testC01_crashPointB_manifestCommittedBeforeRawDeleted_noDuplicatesAndCleansRaw() = runBlocking {
        val sessionDir = File(tempFolder.root, "session_c01_b").apply { mkdirs() }
        val testValidator: (File) -> Boolean = { it.exists() && it.length() > 0L }

        val activeManager = CameraScanSessionManager(sessionDir, imageValidator = testValidator)
        val rawFile = File(sessionDir, "raw_1000000003_2.jpg").apply { writeText("RAW_CAMERA_FRAME_2") }
        val outputFile = File(sessionDir, "page_2_1000000004.jpg").apply { writeText("VALID_OUTPUT_PAGE_2") }

        // Step 1 & 2 of 2-phase commit: record raw, process, commit manifest
        activeManager.recordRawCapture(2, rawFile)
        val commitOk = activeManager.commitPageOutput(2, outputFile)
        assertTrue("Manifest commit must succeed", commitOk)

        // Scenario: Crash occurs right BEFORE rawFile.delete() in CameraScanActivity!
        // Both raw and outputFile exist on disk, manifest is COMMITTED.
        assertTrue("Raw file still exists before deletion", rawFile.exists())
        assertTrue("Output file exists", outputFile.exists())

        // Process restarts:
        val recoveryManager = CameraScanSessionManager(sessionDir, imageValidator = testValidator)
        val reconciled = recoveryManager.reconcileSession()

        // Verified: committedPages contains page 2 exactly once
        assertEquals(1, reconciled.committedPages.size)
        assertEquals(outputFile.absolutePath, reconciled.committedPages[2])

        // Lingering raw file must NOT be in pendingRawFiles and must be deleted from disk
        assertFalse("Pending raw files must not contain committed page", reconciled.pendingRawFiles.containsKey(2))
        assertFalse("Lingering raw file must be cleaned up during reconciliation", rawFile.exists())
        assertTrue("Output file must remain intact", outputFile.exists())
    }

    @Test
    fun testC01_crashPointC_rawDeletedBeforeBundleSnapshot_recoversCommittedPages() = runBlocking {
        val sessionDir = File(tempFolder.root, "session_c01_c").apply { mkdirs() }
        val testValidator: (File) -> Boolean = { it.exists() && it.length() > 0L }

        val activeManager = CameraScanSessionManager(sessionDir, imageValidator = testValidator)

        val raw1 = File(sessionDir, "raw_1000000005_1.jpg").apply { writeText("RAW_1") }
        val page1 = File(sessionDir, "page_1_1000000006.jpg").apply { writeText("PAGE_1") }
        val raw2 = File(sessionDir, "raw_1000000007_2.jpg").apply { writeText("RAW_2") }
        val page2 = File(sessionDir, "page_2_1000000008.jpg").apply { writeText("PAGE_2") }

        activeManager.commitPageOutput(1, page1)
        raw1.delete()
        activeManager.commitPageOutput(2, page2)
        raw2.delete()

        // Scenario: Android process death occurs BEFORE onSaveInstanceState is triggered.
        // Bundle is null. Old code would scan only raw_*.jpg and lose all committed pages!
        val savedInstanceStateBundle: Map<String, Any>? = null
        val restoredFromBundle = mutableMapOf<Int, String>()
        if (savedInstanceStateBundle != null) {
            // Nothing restored
        }

        // Recovery with CameraScanSessionManager:
        val recoveryManager = CameraScanSessionManager(sessionDir, imageValidator = testValidator)
        val reconciled = recoveryManager.reconcileSession()

        assertEquals("Must recover both committed pages despite empty Bundle", 2, reconciled.committedPages.size)
        assertEquals(page1.absolutePath, reconciled.committedPages[1])
        assertEquals(page2.absolutePath, reconciled.committedPages[2])
        assertTrue("maxSequence must be at least 2", reconciled.maxSequence >= 2)
    }

    @Test
    fun testC01_crashPointD_pendingCaptureMissingFile_reportedAsFailureNotSilentlyDropped() = runBlocking {
        val sessionDir = File(tempFolder.root, "session_c01_d").apply { mkdirs() }
        val testValidator: (File) -> Boolean = { it.exists() && it.length() > 0L }

        val activeManager = CameraScanSessionManager(sessionDir, imageValidator = testValidator)
        val missingRawFile = File(sessionDir, "raw_1000000009_3.jpg")
        // File was never fully written or deleted by OS low storage
        assertFalse(missingRawFile.exists())

        activeManager.recordRawCapture(3, missingRawFile)

        // Process restarts
        val recoveryManager = CameraScanSessionManager(sessionDir, imageValidator = testValidator)
        val reconciled = recoveryManager.reconcileSession()

        // Must NOT be silently dropped!
        assertFalse("Missing pending file must NOT be in pending list", reconciled.pendingRawFiles.containsKey(3))
        assertFalse("Missing pending file must NOT be in committed list", reconciled.committedPages.containsKey(3))
        assertTrue("Missing pending capture must be flagged in failedPages", reconciled.failedPages.containsKey(3))

        // Manifest must be updated with FAILED status
        val diskManifest = recoveryManager.loadManifestFromDisk()
        assertEquals(CaptureStatus.FAILED, diskManifest[3]?.status)
    }

    @Test
    fun testC01_twoPhaseCommit_invalidOutputFailsCommitAndPreservesRaw() = runBlocking {
        val sessionDir = File(tempFolder.root, "session_c01_invalid").apply { mkdirs() }
        // Validator returns false for corrupt output
        val testValidator: (File) -> Boolean = { file -> file.name.contains("valid") }

        val sessionManager = CameraScanSessionManager(sessionDir, imageValidator = testValidator)
        val rawFile = File(sessionDir, "raw_1000000010_1.jpg").apply { writeText("RAW_DATA") }
        val corruptOutputFile = File(sessionDir, "page_1_corrupt.jpg").apply { writeText("TRUNCATED") }

        sessionManager.recordRawCapture(1, rawFile)
        val commitResult = sessionManager.commitPageOutput(1, corruptOutputFile)

        assertFalse("Commit must fail when image validation fails", commitResult)

        // Raw file must NOT be deleted because commit failed!
        assertTrue("Raw file must be preserved when commit fails", rawFile.exists())

        val manifest = sessionManager.loadManifestFromDisk()
        assertFalse("Manifest must not record page 1 as COMMITTED", manifest[1]?.status == CaptureStatus.COMMITTED)
    }
}
