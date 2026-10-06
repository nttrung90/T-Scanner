package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.model.SyncStatus
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.SafeFileWriter
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

class DocumentMetadataSyncAndTombstoneTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: TestContext
    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var repo: DocumentRepo

    @Before
    fun setUp() {
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
        fakePrefs = FakeSharedPreferences()
        testContext = TestContext(tempFolder.root, fakePrefs)
        DocumentRepo.resetInstanceForTesting()
        repo = DocumentRepo.getInstance(testContext)
    }

    @After
    fun tearDown() {
        DocumentRepo.resetInstanceForTesting()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    @Test
    fun testV11a_upsertLazyDocHasZeroPages_andDownloadUpdatesPageCount() {
        // SafeFileWriter page count seam
        val originalExtractor = SafeFileWriter.pdfPageCountExtractor
        try {
            SafeFileWriter.pdfPageCountExtractor = { file ->
                if (file.name.contains("multipage")) 5 else 1
            }

            // 1. Remote file catalog item is discovered
            val upsertRes = repo.upsertFromDrive(
                ownerId = "user_1",
                driveFileId = "drive_file_99",
                title = "Contract.pdf",
                sizeBytes = 2048L,
                modifiedTime = 1000L
            )
            assertNotNull("Upsert must succeed", upsertRes)
            assertTrue("Should be a newly discovered remote file", upsertRes!!.isNew)
            assertEquals("Un-downloaded remote doc must have pageCount 0 (unknown)", 0, upsertRes.document.pageCount)

            // 2. Simulating download completion of a 5-page PDF
            val downloadedPdf = File(testContext.filesDir, "multipage.pdf").apply {
                writeText("%PDF-1.4 mock content with 5 pages")
            }

            val updated = repo.updateDocumentPdfPath(
                docId = upsertRes.document.id,
                newPath = downloadedPdf.absolutePath,
                fileSize = downloadedPdf.length()
            )
            assertTrue("PDF path update must succeed", updated)

            val docAfter = repo.getDocument(upsertRes.document.id)
            assertNotNull(docAfter)
            assertEquals("Page count must be accurately extracted from the PDF", 5, docAfter!!.pageCount)
            assertEquals(downloadedPdf.absolutePath, docAfter.pdfPath)
        } finally {
            SafeFileWriter.pdfPageCountExtractor = originalExtractor
        }
    }

    @Test
    fun testV11b_renameDocumentIncrementsRevisionAndResetsSyncStatus() {
        val pdfFile = File(testContext.filesDir, "sample.pdf").apply { writeText("%PDF-1.4 test") }
        val doc = DocumentItem(
            id = "doc_ren",
            title = "Original Title",
            pdfPath = pdfFile.absolutePath,
            ownerId = "user_1",
            contentRevision = 3L,
            isSynced = true,
            syncStatus = SyncStatus.SYNCED,
            driveFileId = "drive_abc"
        )
        repo.addDocument(doc)

        val renamed = repo.renameDocument("doc_ren", "New Renamed Title", "user_1")
        assertTrue("Rename should succeed", renamed)

        val updatedDoc = repo.getDocument("doc_ren")
        assertNotNull(updatedDoc)
        assertEquals("New Renamed Title", updatedDoc!!.title)
        assertEquals("contentRevision must be bumped to propagate changes", 4L, updatedDoc.contentRevision)
        assertEquals("syncStatus must be reset to LOCAL_ONLY", SyncStatus.LOCAL_ONLY, updatedDoc.syncStatus)
        assertFalse("isSynced must be false", updatedDoc.isSynced)
    }

    @Test
    fun testV11c_localDeletePreventsDriveResurrection() {
        val pdfFile = File(testContext.filesDir, "del.pdf").apply { writeText("%PDF-1.4 test") }
        val doc = DocumentItem(
            id = "doc_del",
            title = "Deleted Doc",
            pdfPath = pdfFile.absolutePath,
            ownerId = "user_1",
            isSynced = true,
            syncStatus = SyncStatus.SYNCED,
            driveFileId = "drive_tombstone_123"
        )
        repo.addDocument(doc)

        // Delete document locally
        val deleted = repo.deleteDocument("doc_del", "user_1")
        assertTrue("Delete must succeed", deleted)
        assertNull("Document must be removed from memory", repo.getDocument("doc_del"))
        assertTrue("Drive file ID must be tombstoned", repo.isLocalTombstoned("drive_tombstone_123"))
        assertTrue("Doc ID must be tombstoned", repo.isLocalTombstoned("doc_del"))

        // Subsequent Drive catalog sync discovers the file on Drive
        val resurrectAttempt = repo.upsertFromDrive(
            ownerId = "user_1",
            driveFileId = "drive_tombstone_123",
            title = "Deleted Doc.pdf",
            sizeBytes = 1024L,
            modifiedTime = 2000L
        )
        assertNull("Upsert must reject resurrecting tombstoned Drive file", resurrectAttempt)
        assertEquals("memoryDocs must remain empty of the deleted doc", 0, repo.documents.value?.size ?: 0)
    }

    @Test
    fun testV11c_tombstonesPersistAcrossAppRestarts() {
        val pdfFile = File(testContext.filesDir, "del2.pdf").apply { writeText("%PDF-1.4 test") }
        val doc = DocumentItem(
            id = "doc_del2",
            title = "Deleted Doc 2",
            pdfPath = pdfFile.absolutePath,
            ownerId = "user_1",
            isSynced = true,
            syncStatus = SyncStatus.SYNCED,
            driveFileId = "drive_persisted_tombstone"
        )
        repo.addDocument(doc)
        repo.deleteDocument("doc_del2", "user_1")

        // Simulate app kill and repo re-initialization
        DocumentRepo.resetInstanceForTesting()
        val restartedRepo = DocumentRepo.getInstance(testContext)

        assertTrue("Tombstone must persist across restart", restartedRepo.isLocalTombstoned("drive_persisted_tombstone"))

        val resurrectAttempt = restartedRepo.upsertFromDrive(
            ownerId = "user_1",
            driveFileId = "drive_persisted_tombstone",
            title = "Deleted Doc 2.pdf",
            sizeBytes = 1024L,
            modifiedTime = 2000L
        )
        assertNull("Upsert must still reject resurrected file after restart", resurrectAttempt)
    }

    // -------------------------------------------------------------------------
    // Test Helpers
    // -------------------------------------------------------------------------

    private class TestContext(
        private val baseDir: File,
        private val prefs: SharedPreferences
    ) : ContextWrapper(null) {
        override fun getFilesDir(): File = File(baseDir, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(baseDir, "cache").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? Set<*>)?.mapNotNull { it?.toString() }?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(this)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removes = mutableSetOf<String>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) {
                    if (value != null) pending[key] = value else removes.add(key)
                }
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) {
                    if (values != null) pending[key] = values else removes.add(key)
                }
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) removes.add(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearFlag = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearFlag) {
                    prefs.map.clear()
                }
                removes.forEach { prefs.map.remove(it) }
                pending.forEach { (k, v) ->
                    if (v != null) prefs.map[k] = v else prefs.map.remove(k)
                }
            }
        }
    }
}
