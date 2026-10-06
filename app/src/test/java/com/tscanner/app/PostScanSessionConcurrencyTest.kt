package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import com.tscanner.app.data.model.PostScanSessionDraft
import com.tscanner.app.data.repository.PostScanSessionRepository
import com.tscanner.app.ui.editor.model.PageEditState
import com.tscanner.app.utils.SafeFileWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PostScanSessionConcurrencyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testBaseDir: File
    private lateinit var testContext: TestContext
    private lateinit var repo: PostScanSessionRepository

    private class TestContext(private val baseDir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = File(baseDir, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(baseDir, "cache").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
    }

    @Before
    fun setUp() {
        testBaseDir = tempFolder.newFolder("session_test_root")
        testContext = TestContext(testBaseDir)
        repo = PostScanSessionRepository.createForTesting(testContext)
    }

    @After
    fun tearDown() {
        SafeFileWriter.imageValidator = SafeFileWriter.DefaultImageValidator
    }

    private fun createDummyDraft(sessionId: String, revision: Long = 1L): PostScanSessionDraft {
        return PostScanSessionDraft(
            sessionId = sessionId,
            documentTitle = "Test Draft $sessionId",
            pageStates = listOf(
                PageEditState(pageIndex = 0, inputImagePath = "/dummy/path.jpg")
            ),
            schemaVersion = 1,
            revision = revision,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }

    @Test
    fun testLateSaveDraftAfterDiscard_isRejectedAndDoesNotRecreateDir() = runBlocking {
        val sessionId = "session-discard-late-writer"
        val initialDraft = createDummyDraft(sessionId, revision = 1L)

        // 1. Initial save succeeds
        val saveOk = repo.saveDraft(initialDraft)
        assertTrue("Initial save should succeed", saveOk)
        assertTrue("Session dir should exist", repo.getSessionDir(sessionId).exists())

        // 2. Deterministic race barrier:
        // Barrier 1: signals when writer coroutine is spawned and waiting
        val writerReadyBarrier = CompletableDeferred<Unit>()
        // Barrier 2: holds writer until discard completes
        val discardCompleteBarrier = CompletableDeferred<Unit>()

        val lateWriterJob = async(Dispatchers.IO) {
            writerReadyBarrier.complete(Unit)
            discardCompleteBarrier.await()
            // Late save with revision 2 arrives strictly after discard has finished
            repo.saveDraft(createDummyDraft(sessionId, revision = 2L))
        }

        // Wait until writer is staged
        writerReadyBarrier.await()

        // Discard session
        val discardOk = repo.discardSession(sessionId)
        assertTrue("Discard should return true", discardOk)
        assertTrue("Session must be marked closed", repo.isSessionClosed(sessionId))

        // Session directory must be gone
        val sessionDir = File(testContext.filesDir, "draft_sessions/$sessionId")
        assertFalse("Session dir must not exist after discard", sessionDir.exists())

        // Release the late writer
        discardCompleteBarrier.complete(Unit)
        val lateSaveResult = lateWriterJob.await()

        // 3. Late writer must be rejected and must NOT resurrect the session directory
        assertFalse("Late saveDraft after discard must be rejected", lateSaveResult)
        assertFalse("Session dir must NOT be recreated by late writer", sessionDir.exists())
        assertTrue("Session must remain closed", repo.isSessionClosed(sessionId))
    }

    @Test
    fun testInitializeSessionAfterDiscard_isRejected() = runBlocking {
        val sessionId = "session-init-after-discard"
        val draft = createDummyDraft(sessionId)
        repo.saveDraft(draft)

        repo.discardSession(sessionId)
        assertTrue("Session must be closed", repo.isSessionClosed(sessionId))

        // Attempt to re-initialize the same discarded session ID
        val dummyImage = File(testContext.filesDir, "dummy_src.jpg").apply {
            writeBytes(ByteArray(100) { 1 })
        }
        val reinitResult = repo.initializeSession(sessionId, listOf(dummyImage.absolutePath))

        assertNull("initializeSession must refuse previously discarded sessionId", reinitResult)
        val sessionDir = File(testContext.filesDir, "draft_sessions/$sessionId")
        assertFalse("Session dir must not be created for discarded sessionId", sessionDir.exists())
    }

    @Test
    fun testInitializeSessionInterleavedWithDiscard_cleansUpAndReturnsNull() = runBlocking {
        val sessionId = "session-init-race-discard"
        val dummySrc1 = File(testContext.filesDir, "src1.jpg").apply {
            writeBytes(ByteArray(128) { 0xFF.toByte() })
        }
        val dummySrc2 = File(testContext.filesDir, "src2.jpg").apply {
            writeBytes(ByteArray(128) { 0xEE.toByte() })
        }

        val initCopyBarrier = CompletableDeferred<Unit>()
        val discardTriggeredBarrier = CompletableDeferred<Unit>()

        // Inject barrier in imageValidator during first page copy
        SafeFileWriter.imageValidator = SafeFileWriter.ImageValidator { file ->
            if (file.name.contains("tmp_copy_1")) {
                initCopyBarrier.complete(Unit)
                // Block until discard coroutine has started and added sessionId to closedSessions
                runBlocking { discardTriggeredBarrier.await() }
            }
            file.exists() && file.length() > 0
        }

        // 1. Launch initializeSession
        val initJob = async(Dispatchers.IO) {
            repo.initializeSession(sessionId, listOf(dummySrc1.absolutePath, dummySrc2.absolutePath))
        }

        // 2. Wait until initializeSession is inside mutex and copying page 1
        initCopyBarrier.await()

        // 3. Launch discardSession while initializeSession holds the lock
        val discardJob = async(Dispatchers.IO) {
            repo.discardSession(sessionId)
        }

        // Wait a tiny moment to ensure discardSession has executed closedSessions.add(sessionId)
        // and is waiting on getSessionLock(sessionId).mutex
        while (!repo.isSessionClosed(sessionId)) {
            Thread.sleep(10)
        }

        // 4. Release initializeSession's validator to proceed
        discardTriggeredBarrier.complete(Unit)

        val initResult = initJob.await()
        val discardResult = discardJob.await()

        // 5. Assert results
        assertNull("initializeSession must abort and return null when session is discarded", initResult)
        assertTrue("discardSession must succeed", discardResult)
        assertTrue("Session must be closed", repo.isSessionClosed(sessionId))

        val sessionDir = File(testContext.filesDir, "draft_sessions/$sessionId")
        assertFalse("Session dir must NOT exist on disk after concurrent discard", sessionDir.exists())
        val repoSessionDir = repo.getSessionDir(sessionId)
        assertFalse("repo.getSessionDir must NOT recreate dir for closed session", repoSessionDir.exists())
    }

    @Test
    fun testNewSessionWithNewId_worksIndependently() = runBlocking {
        val oldSessionId = "session-old"
        repo.saveDraft(createDummyDraft(oldSessionId))
        repo.discardSession(oldSessionId)
        assertTrue(repo.isSessionClosed(oldSessionId))

        // Completely new independent session
        val newSessionId = "session-new"
        assertFalse(repo.isSessionClosed(newSessionId))

        val newDraft = createDummyDraft(newSessionId, revision = 1L)
        val saveOk = repo.saveDraft(newDraft)
        assertTrue("New session save should succeed", saveOk)

        val loaded = repo.loadDraft(newSessionId)
        assertNotNull("New session draft should be readable", loaded)
        assertEquals(newSessionId, loaded?.sessionId)
    }

    @Test
    fun testCloseSession_stopsFutureSaves() = runBlocking {
        val sessionId = "session-close-test"
        repo.saveDraft(createDummyDraft(sessionId, revision = 1L))

        repo.closeSession(sessionId)
        assertTrue("Session must be marked closed", repo.isSessionClosed(sessionId))

        val saveAfterClose = repo.saveDraft(createDummyDraft(sessionId, revision = 2L))
        assertFalse("Save after close must be rejected", saveAfterClose)
    }
}
