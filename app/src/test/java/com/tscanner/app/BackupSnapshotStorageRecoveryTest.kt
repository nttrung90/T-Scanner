package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import com.google.common.util.concurrent.ListenableFuture
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.model.SyncStatus
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BackupSnapshotStore
import com.tscanner.app.utils.CloudBackupManager
import com.tscanner.app.utils.DriveOperationResult
import com.tscanner.app.utils.GoogleDriveBackupWorker
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
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class BackupSnapshotStorageRecoveryTest {

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
        CloudBackupManager.resetForTesting()
        CloudBackupManager.workEnqueuer = { _, _, _, _ -> }
        BackupSnapshotStore.resetForTesting()
        GoogleDriveBackupWorker.resetForTesting()
    }

    @After
    fun tearDown() {
        AppAuthManager.resetForTesting()
        DocumentRepo.resetInstanceForTesting()
        CloudBackupManager.resetForTesting()
        BackupSnapshotStore.resetForTesting()
        GoogleDriveBackupWorker.resetForTesting()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    private fun setupVipUser(email: String, userId: String = "uid_$email"): UserProfile {
        val expires = System.currentTimeMillis() + 86400000L
        val user = UserProfile(
            id = userId,
            email = email,
            displayName = "User $email",
            isVip = true,
            tier = VipTier.VIP,
            vipExpiresAt = expires
        )
        fakePrefs.edit()
            .putString(
                "key_user_profile",
                """{"id":"$userId","email":"$email","displayName":"User $email","isVip":true,"tier":"vip","vipExpiresAt":$expires}"""
            )
            .putString("vip_account_${userId}_tier", "vip")
            .putLong("vip_account_${userId}_expires_at", expires)
            .putLong("vip_account_${userId}_purchased_at", System.currentTimeMillis())
            .apply()
        AppAuthManager.setCurrentUserForTesting(user)
        AppAuthManager.setUserVipTier(testContext, VipTier.VIP, durationDays = 365)
        return user
    }

    // -------------------------------------------------------------------------
    // 1. New Snapshot in Persistent Storage & Survival Across Cache Purge
    // -------------------------------------------------------------------------

    @Test
    fun testNewSnapshot_createdInPersistentStorage_survivesCacheEviction() = runBlocking {
        setupVipUser("alice@test.com", "user_alice")
        val repo = DocumentRepo.getInstance(testContext)
        val livePdf = File(testContext.filesDir, "contract.pdf").apply { writeText("PERSISTENT_SNAPSHOT_CONTENT") }
        val doc = DocumentItem(
            id = "doc_persist_1",
            title = "Contract",
            ownerId = "user_alice",
            pdfPath = livePdf.absolutePath,
            contentRevision = 1L
        )
        repo.addDocument(doc)

        val capturedRequest = AtomicReference<OneTimeWorkRequest?>()
        CloudBackupManager.workEnqueuer = { _, _, _, req ->
            capturedRequest.set(req)
        }

        CloudBackupManager.enqueueBackup(testContext, doc)

        val req = capturedRequest.get()
        assertNotNull("Work request must be enqueued", req)
        val snapshotPath = req?.workSpec?.input?.getString(GoogleDriveBackupWorker.KEY_SNAPSHOT_PATH)
        assertNotNull("Snapshot path must be populated", snapshotPath)

        val snapshotFile = File(snapshotPath!!)
        val noBackupDir = testContext.noBackupFilesDir ?: testContext.filesDir
        assertEquals("Snapshot must be located in no-backup persistent dir", noBackupDir.canonicalPath, snapshotFile.parentFile?.parentFile?.canonicalPath)
        assertEquals("PERSISTENT_SNAPSHOT_CONTENT", snapshotFile.readText())

        // Simulate aggressive OS cache eviction: wipe cache directory completely!
        testContext.cacheDir.listFiles()?.forEach { it.deleteRecursively() }

        // Persistent snapshot file MUST still be 100% intact!
        assertTrue("Snapshot must survive cache eviction", snapshotFile.exists())
        assertEquals("PERSISTENT_SNAPSHOT_CONTENT", snapshotFile.readText())

        // Run worker: must upload successfully from persistent snapshot
        val uploadedContent = AtomicReference<String?>()
        GoogleDriveBackupWorker.tokenProviderForTesting = { _, _ -> "test_token" }
        GoogleDriveBackupWorker.driveUploaderForTesting = { _, _, file, _, _ ->
            uploadedContent.set(file.readText())
            DriveOperationResult.Success("drive_file_id_persist")
        }

        val result = GoogleDriveBackupWorker.performBackup(testContext, req.workSpec.input, 0)
        assertTrue("Worker must succeed", result is ListenableWorker.Result.Success)
        assertEquals("PERSISTENT_SNAPSHOT_CONTENT", uploadedContent.get())

        val updatedDoc = repo.getDocument("doc_persist_1")
        assertEquals(SyncStatus.SYNCED, updatedDoc?.syncStatus)
        assertEquals("drive_file_id_persist", updatedDoc?.driveFileId)

        // After success, terminal cleanup removes snapshot file
        assertFalse("Snapshot file should be deleted upon terminal completion", snapshotFile.exists())
    }

    // -------------------------------------------------------------------------
    // 2. Legacy Work Request with Cache Path: Reads Legacy Snapshot if Exists
    // -------------------------------------------------------------------------

    @Test
    fun testLegacyWorkRequest_withCachePath_readsLegacySnapshotIfExists() = runBlocking {
        setupVipUser("alice@test.com", "user_alice")
        val repo = DocumentRepo.getInstance(testContext)
        val livePdf = File(testContext.filesDir, "legacy_doc.pdf").apply { writeText("LIVE_CONTENT") }
        val doc = DocumentItem(
            id = "doc_legacy_1",
            title = "Legacy Doc",
            ownerId = "user_alice",
            pdfPath = livePdf.absolutePath,
            contentRevision = 1L
        )
        repo.addDocument(doc)

        // Create legacy snapshot in cache directory
        val legacyCacheDir = File(testContext.cacheDir, BackupSnapshotStore.SNAPSHOT_DIR_NAME).apply { mkdirs() }
        val legacySnapshotFile = File(legacyCacheDir, "snapshot_doc_legacy_1_rev1_abcd1234.pdf").apply {
            writeText("LEGACY_SNAPSHOT_BYTES")
        }

        val inputData = Data.Builder()
            .putString(GoogleDriveBackupWorker.KEY_DOC_ID, "doc_legacy_1")
            .putString(GoogleDriveBackupWorker.KEY_PDF_PATH, livePdf.absolutePath)
            .putString(GoogleDriveBackupWorker.KEY_SNAPSHOT_PATH, legacySnapshotFile.absolutePath)
            .putString(GoogleDriveBackupWorker.KEY_DOC_TITLE, "Legacy Doc")
            .putString(GoogleDriveBackupWorker.KEY_OWNER_ID, "user_alice")
            .putLong(GoogleDriveBackupWorker.KEY_REVISION, 1L)
            .build()

        val uploadedBytes = AtomicReference<String?>()
        GoogleDriveBackupWorker.tokenProviderForTesting = { _, _ -> "test_token" }
        GoogleDriveBackupWorker.driveUploaderForTesting = { _, _, file, _, _ ->
            uploadedBytes.set(file.readText())
            DriveOperationResult.Success("drive_id_legacy")
        }

        val result = GoogleDriveBackupWorker.performBackup(testContext, inputData, 0)
        assertTrue("Worker must succeed with legacy cache snapshot", result is ListenableWorker.Result.Success)
        assertEquals("LEGACY_SNAPSHOT_BYTES", uploadedBytes.get())

        val updatedDoc = repo.getDocument("doc_legacy_1")
        assertEquals(SyncStatus.SYNCED, updatedDoc?.syncStatus)
        assertFalse("Legacy cache snapshot file should be cleaned up", legacySnapshotFile.exists())
    }

    // -------------------------------------------------------------------------
    // 3. Legacy Work Request When Cache Evicted: Fails Cleanly Without Live Fallback
    // -------------------------------------------------------------------------

    @Test
    fun testLegacyWorkRequest_whenCacheEvicted_failsCleanlyWithoutLiveFileFallback() = runBlocking {
        setupVipUser("alice@test.com", "user_alice")
        val repo = DocumentRepo.getInstance(testContext)
        val livePdf = File(testContext.filesDir, "current_contract.pdf").apply { writeText("LIVE_CURRENT_BYTES") }
        val doc = DocumentItem(
            id = "doc_evicted_1",
            title = "Evicted Doc",
            ownerId = "user_alice",
            pdfPath = livePdf.absolutePath,
            contentRevision = 1L
        )
        repo.addDocument(doc)

        // Point to a cache file that DOES NOT EXIST (purged by OS)
        val nonExistentCachePath = File(testContext.cacheDir, "backup_snapshots/snapshot_doc_evicted_1_rev1_missing.pdf").absolutePath

        val inputData = Data.Builder()
            .putString(GoogleDriveBackupWorker.KEY_DOC_ID, "doc_evicted_1")
            .putString(GoogleDriveBackupWorker.KEY_PDF_PATH, livePdf.absolutePath)
            .putString(GoogleDriveBackupWorker.KEY_SNAPSHOT_PATH, nonExistentCachePath)
            .putString(GoogleDriveBackupWorker.KEY_DOC_TITLE, "Evicted Doc")
            .putString(GoogleDriveBackupWorker.KEY_OWNER_ID, "user_alice")
            .putLong(GoogleDriveBackupWorker.KEY_REVISION, 1L)
            .build()

        var uploaderInvoked = false
        GoogleDriveBackupWorker.tokenProviderForTesting = { _, _ -> "test_token" }
        GoogleDriveBackupWorker.driveUploaderForTesting = { _, _, _, _, _ ->
            uploaderInvoked = true
            DriveOperationResult.Success("drive_id_wrong")
        }

        val result = GoogleDriveBackupWorker.performBackup(testContext, inputData, 0)

        assertTrue("Worker must fail when snapshot was evicted", result is ListenableWorker.Result.Failure)
        assertFalse("Live file fallback is strictly forbidden by policy", uploaderInvoked)

        val updatedDoc = repo.getDocument("doc_evicted_1")
        assertEquals("Document must be marked FAILED for retry via new snapshot", SyncStatus.FAILED, updatedDoc?.syncStatus)
    }

    // -------------------------------------------------------------------------
    // 4. Source Changed After Enqueue: Aborts Obsolete Upload
    // -------------------------------------------------------------------------

    @Test
    fun testWorker_abortsObsoleteUpload_whenDocumentRevisionMovedPastEnqueuedRevision() = runBlocking {
        setupVipUser("alice@test.com", "user_alice")
        val repo = DocumentRepo.getInstance(testContext)
        val livePdf = File(testContext.filesDir, "editable.pdf").apply { writeText("REV_1_DATA") }
        val doc = DocumentItem(
            id = "doc_edit_1",
            title = "Editable",
            ownerId = "user_alice",
            pdfPath = livePdf.absolutePath,
            contentRevision = 1L
        )
        repo.addDocument(doc)

        val snapshotFile = BackupSnapshotStore.createSnapshot(testContext, doc.id, 1L, livePdf)!!
        assertTrue(snapshotFile.exists())

        // User modifies document locally: revision advances to 2
        livePdf.writeText("REV_2_NEW_DATA")
        repo.markDocumentModified(doc.id, userId = "user_alice")
        val modifiedDoc = repo.getDocument(doc.id)
        assertEquals(2L, modifiedDoc?.contentRevision)

        val inputData = Data.Builder()
            .putString(GoogleDriveBackupWorker.KEY_DOC_ID, doc.id)
            .putString(GoogleDriveBackupWorker.KEY_PDF_PATH, livePdf.absolutePath)
            .putString(GoogleDriveBackupWorker.KEY_SNAPSHOT_PATH, snapshotFile.absolutePath)
            .putString(GoogleDriveBackupWorker.KEY_DOC_TITLE, doc.title)
            .putString(GoogleDriveBackupWorker.KEY_OWNER_ID, "user_alice")
            .putLong(GoogleDriveBackupWorker.KEY_REVISION, 1L) // Enqueued with revision 1
            .build()

        var uploaderInvoked = false
        GoogleDriveBackupWorker.tokenProviderForTesting = { _, _ -> "test_token" }
        GoogleDriveBackupWorker.driveUploaderForTesting = { _, _, _, _, _ ->
            uploaderInvoked = true
            DriveOperationResult.Success("drive_id_obsolete")
        }

        val result = GoogleDriveBackupWorker.performBackup(testContext, inputData, 0)

        // Obsolete work must succeed without uploading to prevent overwriting newer local edits
        assertTrue("Obsolete upload must return success to drain queue", result is ListenableWorker.Result.Success)
        assertFalse("Uploader must not be invoked for obsolete revision", uploaderInvoked)
        assertNull("Obsolete drive ID must not be set on newer revision", repo.getDocument(doc.id)?.driveFileId)
    }

    // -------------------------------------------------------------------------
    // 5. Handling Missing/Empty Source File: Enqueues Nothing Partial
    // -------------------------------------------------------------------------

    @Test
    fun testCreateSnapshot_failsGracefullyAndEnqueuesNothing_whenSourceEmptyOrMissing() {
        setupVipUser("alice@test.com", "user_alice")
        val repo = DocumentRepo.getInstance(testContext)
        val emptyPdf = File(testContext.filesDir, "empty.pdf").apply { createNewFile() }
        val doc = DocumentItem(
            id = "doc_empty_1",
            title = "Empty",
            ownerId = "user_alice",
            pdfPath = emptyPdf.absolutePath,
            contentRevision = 1L
        )
        repo.addDocument(doc)

        val capturedWork = AtomicReference<OneTimeWorkRequest?>()
        CloudBackupManager.workEnqueuer = { _, _, _, req ->
            capturedWork.set(req)
        }

        CloudBackupManager.enqueueBackup(testContext, doc)

        assertNull("Work must NOT be enqueued when source PDF is empty", capturedWork.get())
        val persistentSnapshots = BackupSnapshotStore.getSnapshotDir(testContext).listFiles() ?: emptyArray()
        assertEquals("No partial or empty snapshot files should remain", 0, persistentSnapshots.size)
    }

    // -------------------------------------------------------------------------
    // 6. Terminal Cleanup: Success/Failure Cleans Up, Retry Retains
    // -------------------------------------------------------------------------

    @Test
    fun testTerminalCleanup_removesSnapshotOnSuccessOrFail_butRetainsOnRetry() = runBlocking {
        setupVipUser("alice@test.com", "user_alice")
        val repo = DocumentRepo.getInstance(testContext)
        val livePdf = File(testContext.filesDir, "test_retry.pdf").apply { writeText("RETRY_CONTENT") }
        val doc = DocumentItem(id = "doc_retry_1", title = "Retry Doc", ownerId = "user_alice", pdfPath = livePdf.absolutePath, contentRevision = 1L)
        repo.addDocument(doc)

        val snapshotFile = BackupSnapshotStore.createSnapshot(testContext, doc.id, 1L, livePdf)!!
        assertTrue("Snapshot file must exist", snapshotFile.exists())

        val inputData = Data.Builder()
            .putString(GoogleDriveBackupWorker.KEY_DOC_ID, doc.id)
            .putString(GoogleDriveBackupWorker.KEY_PDF_PATH, livePdf.absolutePath)
            .putString(GoogleDriveBackupWorker.KEY_SNAPSHOT_PATH, snapshotFile.absolutePath)
            .putString(GoogleDriveBackupWorker.KEY_DOC_TITLE, doc.title)
            .putString(GoogleDriveBackupWorker.KEY_OWNER_ID, "user_alice")
            .putLong(GoogleDriveBackupWorker.KEY_REVISION, 1L)
            .build()

        GoogleDriveBackupWorker.tokenProviderForTesting = { _, _ -> "test_token" }

        // Attempt 0: Transient error -> Retry -> MUST RETAIN snapshot!
        GoogleDriveBackupWorker.driveUploaderForTesting = { _, _, _, _, _ ->
            DriveOperationResult.TransientError(500, "Server Error")
        }
        val retryResult = GoogleDriveBackupWorker.performBackup(testContext, inputData, 0)
        assertTrue("Must return Retry", retryResult is ListenableWorker.Result.Retry)
        assertTrue("Snapshot file MUST be retained for subsequent retry attempts", snapshotFile.exists())

        // Attempt 1: Success -> MUST DELETE snapshot!
        GoogleDriveBackupWorker.driveUploaderForTesting = { _, _, _, _, _ ->
            DriveOperationResult.Success("drive_success")
        }
        val successResult = GoogleDriveBackupWorker.performBackup(testContext, inputData, 1)
        assertTrue("Must return Success", successResult is ListenableWorker.Result.Success)
        assertFalse("Snapshot file MUST be cleaned up on terminal success", snapshotFile.exists())
    }

    // -------------------------------------------------------------------------
    // 7. Draining Legacy Cache Without Affecting Active Work
    // -------------------------------------------------------------------------

    @Test
    fun testCleanOrphanSnapshots_safelyDrainsLegacyCacheWithoutImpactingActiveWork() {
        val legacyCacheDir = File(testContext.cacheDir, BackupSnapshotStore.SNAPSHOT_DIR_NAME).apply { mkdirs() }
        val activeLegacyFile = File(legacyCacheDir, "snapshot_active_rev1_aaaa1111.pdf").apply { writeText("ACTIVE") }
        val terminalLegacyFile = File(legacyCacheDir, "snapshot_terminal_rev1_bbbb2222.pdf").apply { writeText("TERMINAL") }

        val persistentDir = BackupSnapshotStore.getSnapshotDir(testContext)
        val terminalPersistentFile = File(persistentDir, "snapshot_persist_rev1_cccc3333.pdf").apply { writeText("PERSIST_TERM") }

        BackupSnapshotStore.workStateChecker = { _, file ->
            when (file.name) {
                activeLegacyFile.name -> BackupSnapshotStore.WorkSnapshotStatus.ACTIVE
                terminalLegacyFile.name -> BackupSnapshotStore.WorkSnapshotStatus.TERMINAL
                terminalPersistentFile.name -> BackupSnapshotStore.WorkSnapshotStatus.TERMINAL
                else -> BackupSnapshotStore.WorkSnapshotStatus.UNKNOWN
            }
        }

        val cleaned = BackupSnapshotStore.cleanOrphanSnapshots(testContext)
        assertEquals("Must clean 2 terminal snapshots across legacy and persistent dirs", 2, cleaned)

        assertTrue("Active snapshot in legacy cache MUST be retained", activeLegacyFile.exists())
        assertFalse("Terminal snapshot in legacy cache MUST be cleaned", terminalLegacyFile.exists())
        assertFalse("Terminal snapshot in persistent dir MUST be cleaned", terminalPersistentFile.exists())
    }

    // -------------------------------------------------------------------------
    // 8. Lookup Timeout Retains Snapshot (Fail-safe)
    // -------------------------------------------------------------------------

    @Test
    fun testLookupTimeout_retainsSnapshotInPersistentStorage() {
        val persistentDir = BackupSnapshotStore.getSnapshotDir(testContext)
        val snapshotFile = File(persistentDir, "snapshot_timeout_rev1_dddd4444.pdf").apply { writeText("TIMEOUT_CONTENT") }

        // Future times out
        BackupSnapshotStore.workQueryFutureProvider = { _ ->
            object : ListenableFuture<List<WorkInfo>> {
                override fun cancel(mayInterruptIfRunning: Boolean): Boolean = true
                override fun isCancelled(): Boolean = true
                override fun isDone(): Boolean = false
                override fun get(): List<WorkInfo> = throw TimeoutException("timeout")
                override fun get(timeout: Long, unit: TimeUnit): List<WorkInfo> = throw TimeoutException("timeout")
                override fun addListener(listener: Runnable, executor: Executor) {}
            }
        }

        val status = BackupSnapshotStore.checkWorkStatus(testContext, snapshotFile)
        assertEquals(BackupSnapshotStore.WorkSnapshotStatus.QUERY_ERROR, status)

        val cleaned = BackupSnapshotStore.cleanOrphanSnapshots(testContext, maxAgeMs = 0L)
        assertEquals("QUERY_ERROR snapshots must never be deleted", 0, cleaned)
        assertTrue("Snapshot file must be preserved on lookup error", snapshotFile.exists())
    }

    // -------------------------------------------------------------------------
    // Helper Test Classes
    // -------------------------------------------------------------------------

    private class TestContext(
        private val baseDir: File,
        private val prefs: SharedPreferences
    ) : ContextWrapper(null) {
        override fun getFilesDir(): File = File(baseDir, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(baseDir, "no_backup").apply { mkdirs() }
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
