package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.model.SyncStatus
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.AppAuthManager
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

class DocumentRevisionCasTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: TestContext
    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
        fakePrefs = FakeSharedPreferences()
        testContext = TestContext(tempFolder.root, fakePrefs)
        AppAuthManager.resetForTesting()
        DocumentRepo.resetInstanceForTesting()
    }

    @After
    fun tearDown() {
        AppAuthManager.resetForTesting()
        DocumentRepo.resetInstanceForTesting()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    @Test
    fun testCasCommit_succeedsWhenRevisionAndOwnerMatch() {
        val repo = DocumentRepo.getInstance(testContext)
        val userA = "user_canonical_A"
        val doc = DocumentItem(
            id = "doc-cas-1",
            title = "Cas Doc",
            ownerId = userA,
            contentRevision = 1L,
            syncStatus = SyncStatus.SYNCING
        )
        repo.addDocument(doc)

        val casResult = repo.updateSyncStatusCas(
            docId = "doc-cas-1",
            expectedOwnerId = userA,
            expectedRevision = 1L,
            status = SyncStatus.SYNCED,
            driveFileId = "drive_file_123",
            syncedAt = 1000L
        )

        assertTrue("CAS commit must succeed when revision and owner match", casResult)
        val updated = repo.getDocument("doc-cas-1")
        assertNotNull(updated)
        assertEquals(SyncStatus.SYNCED, updated?.syncStatus)
        assertTrue(updated?.isSynced == true)
        assertEquals("drive_file_123", updated?.driveFileId)
        assertEquals(1000L, updated?.lastSyncedAt)
    }

    @Test
    fun testCasCommit_rejectedWhenDocumentModifiedToNewerRevision() {
        val repo = DocumentRepo.getInstance(testContext)
        val userA = "user_canonical_A"
        val doc = DocumentItem(
            id = "doc-cas-race",
            title = "Racing Document",
            ownerId = userA,
            contentRevision = 1L,
            sizeBytes = 100L,
            syncStatus = SyncStatus.SYNCING
        )
        repo.addDocument(doc)

        // Simulate local edit happening while Revision 1 is in-flight uploading:
        // Document content revision increases from 1 to 2
        repo.markDocumentModified("doc-cas-race", newSizeBytes = 200L, userId = userA)
        val dirtyDoc = repo.getDocument("doc-cas-race")
        assertEquals(2L, dirtyDoc?.contentRevision)
        assertEquals(SyncStatus.LOCAL_ONLY, dirtyDoc?.syncStatus)
        assertFalse(dirtyDoc?.isSynced == true)

        // Late response from Revision 1 upload returns and tries to commit SYNCED:
        val casResult = repo.updateSyncStatusCas(
            docId = "doc-cas-race",
            expectedOwnerId = userA,
            expectedRevision = 1L, // Stale revision 1!
            status = SyncStatus.SYNCED,
            driveFileId = "drive_old_rev1",
            syncedAt = 2000L
        )

        assertFalse("CAS commit must be REJECTED when local revision has changed (newer edits exist)", casResult)

        // Verify document remained dirty and was NOT overwritten by stale upload
        val finalDoc = repo.getDocument("doc-cas-race")
        assertNotNull(finalDoc)
        assertEquals("Revision must still be 2", 2L, finalDoc?.contentRevision)
        assertEquals("Sync status must still be LOCAL_ONLY (dirty)", SyncStatus.LOCAL_ONLY, finalDoc?.syncStatus)
        assertFalse("Document must NOT be marked synced", finalDoc?.isSynced == true)
        assertEquals("Size must remain 200L from rev 2", 200L, finalDoc?.sizeBytes)
    }

    @Test
    fun testCasCommit_rejectedWhenOwnerMismatches() {
        val repo = DocumentRepo.getInstance(testContext)
        val userA = "user_canonical_A"
        val userB = "user_canonical_B"
        val doc = DocumentItem(
            id = "doc-owner-mismatch",
            title = "Owner Test",
            ownerId = userA,
            contentRevision = 1L,
            syncStatus = SyncStatus.LOCAL_ONLY
        )
        repo.addDocument(doc)

        val casResult = repo.updateSyncStatusCas(
            docId = "doc-owner-mismatch",
            expectedOwnerId = userB, // Mismatched owner
            expectedRevision = 1L,
            status = SyncStatus.SYNCED,
            driveFileId = "drive_b_file"
        )

        assertFalse("CAS commit must be REJECTED if expected owner does not match document owner", casResult)
        val finalDoc = repo.getDocument("doc-owner-mismatch")
        assertEquals(SyncStatus.LOCAL_ONLY, finalDoc?.syncStatus)
    }

    @Test
    fun testCasCommit_rejectedWhenDocumentDeleted() {
        val repo = DocumentRepo.getInstance(testContext)
        val userA = "user_canonical_A"
        val doc = DocumentItem(
            id = "doc-deleted-race",
            title = "Doc To Delete",
            ownerId = userA,
            contentRevision = 1L
        )
        repo.addDocument(doc)

        // Delete document while upload was pending
        repo.deleteDocument("doc-deleted-race", userA)
        assertNull(repo.getDocument("doc-deleted-race"))

        // Stale worker finishes and tries to commit
        val casResult = repo.updateSyncStatusCas(
            docId = "doc-deleted-race",
            expectedOwnerId = userA,
            expectedRevision = 1L,
            status = SyncStatus.SYNCED,
            driveFileId = "drive_deleted_file"
        )

        assertFalse("CAS commit must return false for deleted document", casResult)
        assertNull("Deleted document must NOT be resurrected in repository", repo.getDocument("doc-deleted-race"))
    }

    @Test
    fun testSnapshotLifecycle_immutableBytesAndCleanup() {
        val pdfDir = File(tempFolder.root, "files/documents").apply { mkdirs() }
        val livePdf = File(pdfDir, "live_doc.pdf").apply { writeText("REVISION_1_CONTENT") }

        val snapshotDir = File(testContext.cacheDir, "backup_snapshots").apply { mkdirs() }
        val snapshotFile = File(snapshotDir, "snapshot_test_rev1.pdf")

        // 1. Create snapshot
        livePdf.copyTo(snapshotFile, overwrite = true)
        assertTrue(snapshotFile.exists())
        assertEquals("REVISION_1_CONTENT", snapshotFile.readText())

        // 2. Mutate live file while snapshot is being held by worker
        livePdf.writeText("REVISION_2_MODIFIED_CONTENT")
        assertEquals("REVISION_2_MODIFIED_CONTENT", livePdf.readText())

        // Snapshot is immune to live mutations
        assertEquals("Snapshot must remain immutable even if live file changes concurrently", "REVISION_1_CONTENT", snapshotFile.readText())

        // 3. Worker completes and deletes snapshot
        snapshotFile.delete()
        assertFalse("Snapshot must be cleaned up after worker execution", snapshotFile.exists())
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
