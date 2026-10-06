package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class BackupSnapshotLifecycleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: TestContext
    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var snapshotsDir: File

    @Before
    fun setUp() {
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
        fakePrefs = FakeSharedPreferences()
        testContext = TestContext(tempFolder.root, fakePrefs)
        snapshotsDir = File(tempFolder.root, "backup_snapshots").apply { mkdirs() }
        BackupSnapshotStore.baseDirOverride = snapshotsDir

        AppAuthManager.resetForTesting()
        DocumentRepo.resetInstanceForTesting()
        CloudBackupManager.resetForTesting()
        CloudBackupManager.workEnqueuer = { _, _, _, _ -> }
        GoogleDriveBackupWorker.resetForTesting()
        BackupSnapshotStore.resetForTesting()
        BackupSnapshotStore.baseDirOverride = snapshotsDir
    }

    @After
    fun tearDown() {
        AppAuthManager.resetForTesting()
        DocumentRepo.resetInstanceForTesting()
        CloudBackupManager.resetForTesting()
        GoogleDriveBackupWorker.resetForTesting()
        BackupSnapshotStore.resetForTesting()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    // -------------------------------------------------------------------------
    // 1. Mandatory Snapshot & Enqueue Fault Injection
    // -------------------------------------------------------------------------

    @Test
    fun testEnqueueBackup_whenSourcePdfMissing_abortsWithoutEnqueuingWork() {
        setupVipUser("alice@test.com", "user_alice")
        val repo = DocumentRepo.getInstance(testContext)
        val nonExistentPath = File(testContext.filesDir, "missing.pdf").absolutePath
        val doc = DocumentItem(id = "doc_missing", title = "Missing", ownerId = "user_alice", pdfPath = nonExistentPath)
        repo.addDocument(doc)

        var workEnqueued = false
        CloudBackupManager.workEnqueuer = { _, _, _, _ ->
            workEnqueued = true
        }

        CloudBackupManager.enqueueBackup(testContext, doc)

        assertFalse("WorkManager must NOT be called when source PDF is missing", workEnqueued)
        assertEquals(0, snapshotsDir.listFiles()?.size ?: 0)
    }

    @Test
    fun testEnqueueBackup_whenSourcePdfZeroBytes_abortsWithoutEnqueuingWork() {
        setupVipUser("alice@test.com", "user_alice")
        val repo = DocumentRepo.getInstance(testContext)
        val emptyPdf = File(testContext.filesDir, "empty.pdf").apply { writeBytes(ByteArray(0)) }
        val doc = DocumentItem(id = "doc_empty", title = "Empty", ownerId = "user_alice", pdfPath = emptyPdf.absolutePath)
        repo.addDocument(doc)

        var workEnqueued = false
        CloudBackupManager.workEnqueuer = { _, _, _, _ ->
            workEnqueued = true
        }

        CloudBackupManager.enqueueBackup(testContext, doc)

        assertFalse("WorkManager must NOT be called when source PDF is 0 bytes", workEnqueued)
        assertEquals(0, snapshotsDir.listFiles()?.size ?: 0)
    }

    @Test
    fun testEnqueueBackup_whenValid_createsSnapshotAndAttachesWorkTagAndSnapshotPath() {
        setupVipUser("alice@test.com", "user_alice")
        val repo = DocumentRepo.getInstance(testContext)
        val validPdf = File(testContext.filesDir, "valid.pdf").apply { writeText("PDF_ORIGINAL_BYTES") }
        val doc = DocumentItem(id = "doc_valid", title = "Valid", ownerId = "user_alice", pdfPath = validPdf.absolutePath, contentRevision = 3L)
        repo.addDocument(doc)

        val capturedRequest = AtomicReference<OneTimeWorkRequest?>()
        val capturedWorkName = AtomicReference<String?>()

        CloudBackupManager.workEnqueuer = { _, uniqueName, _, request ->
            capturedWorkName.set(uniqueName)
            capturedRequest.set(request)
        }

        CloudBackupManager.enqueueBackup(testContext, doc)

        assertEquals("backup_doc_valid", capturedWorkName.get())
        val req = capturedRequest.get()
        assertNotNull("Work request must be generated", req)

        val snapshotPath = req?.workSpec?.input?.getString(GoogleDriveBackupWorker.KEY_SNAPSHOT_PATH)
        assertNotNull("Snapshot path must be populated in input data", snapshotPath)
        val snapshotFile = File(snapshotPath!!)
        assertTrue("Snapshot file must exist on disk", snapshotFile.exists())
        assertEquals("PDF_ORIGINAL_BYTES", snapshotFile.readText())

        // Verify tags
        val tags = req?.tags ?: emptySet()
        assertTrue("Must have cloud_backup tag", tags.contains("cloud_backup"))
        assertTrue("Must have owner tag", tags.contains("owner_user_alice"))
        assertTrue("Must have doc tag", tags.contains("doc_doc_valid"))
        val expectedSnapTag = BackupSnapshotStore.getSnapshotTag(snapshotFile)
        assertTrue("Must contain specific snapshot file tag: $expectedSnapTag", tags.contains(expectedSnapTag))
    }

    // -------------------------------------------------------------------------
    // 2. Immutability & Live File Isolation During Upload
    // -------------------------------------------------------------------------

    @Test
    fun testWorker_whenSourceFileModifiedAfterEnqueue_uploadsOriginalSnapshotBytes() = runBlocking {
        setupVipUser("alice@test.com", "user_alice")
        val repo = DocumentRepo.getInstance(testContext)
        val livePdf = File(testContext.filesDir, "contract.pdf").apply { writeText("ORIGINAL_REVISION_1_CONTENT") }
        val doc = DocumentItem(id = "doc_contract", title = "Contract", ownerId = "user_alice", pdfPath = livePdf.absolutePath, contentRevision = 1L)
        repo.addDocument(doc)

        val capturedSnapshotPath = AtomicReference<String?>()
        CloudBackupManager.workEnqueuer = { _, _, _, req ->
            capturedSnapshotPath.set(req.workSpec.input.getString(GoogleDriveBackupWorker.KEY_SNAPSHOT_PATH))
        }
        CloudBackupManager.enqueueBackup(testContext, doc)

        val snapPath = capturedSnapshotPath.get()
        assertNotNull(snapPath)
        val snapshotFile = File(snapPath!!)

        // Concurrently mutate the live document on disk
        livePdf.writeText("CONCURRENT_MUTATION_REVISION_2_CONTENT")

        // Mock token and drive uploader
        GoogleDriveBackupWorker.tokenProviderForTesting = { _, _ -> "test_token" }
        val uploadedBytes = AtomicReference<String?>()
        GoogleDriveBackupWorker.driveUploaderForTesting = { _, _, file, _, _ ->
            uploadedBytes.set(file.readText())
            DriveOperationResult.Success("drive_file_contract_id")
        }

        val inputData = Data.Builder()
            .putString(GoogleDriveBackupWorker.KEY_DOC_ID, "doc_contract")
            .putString(GoogleDriveBackupWorker.KEY_PDF_PATH, livePdf.absolutePath)
            .putString(GoogleDriveBackupWorker.KEY_SNAPSHOT_PATH, snapPath)
            .putString(GoogleDriveBackupWorker.KEY_DOC_TITLE, "Contract")
            .putString(GoogleDriveBackupWorker.KEY_OWNER_ID, "user_alice")
            .putLong(GoogleDriveBackupWorker.KEY_REVISION, 1L)
            .build()

        val result = GoogleDriveBackupWorker.performBackup(testContext, inputData, 0)

        assertTrue("Upload must succeed", result is ListenableWorker.Result.Success)
        assertEquals("Uploaded bytes must come strictly from immutable snapshot", "ORIGINAL_REVISION_1_CONTENT", uploadedBytes.get())
        // Terminal success: snapshot must be cleaned up
        assertFalse("Snapshot file should be deleted on terminal success", snapshotFile.exists())
    }

    @Test
    fun testWorker_whenSnapshotPathMissing_abortsImmediatelyWithoutReadingLiveFile() = runBlocking {
        setupVipUser("alice@test.com", "user_alice")
        val repo = DocumentRepo.getInstance(testContext)
        val livePdf = File(testContext.filesDir, "contract.pdf").apply { writeText("LIVE_CONTENT") }
        val doc = DocumentItem(id = "doc_nosnap", title = "No Snap", ownerId = "user_alice", pdfPath = livePdf.absolutePath, contentRevision = 1L)
        repo.addDocument(doc)

        var uploaderCalled = false
        GoogleDriveBackupWorker.tokenProviderForTesting = { _, _ -> "test_token" }
        GoogleDriveBackupWorker.driveUploaderForTesting = { _, _, _, _, _ ->
            uploaderCalled = true
            DriveOperationResult.Success("drive_id")
        }

        // inputData with NO snapshot path
        val inputData = Data.Builder()
            .putString(GoogleDriveBackupWorker.KEY_DOC_ID, "doc_nosnap")
            .putString(GoogleDriveBackupWorker.KEY_PDF_PATH, livePdf.absolutePath)
            .putString(GoogleDriveBackupWorker.KEY_DOC_TITLE, "No Snap")
            .putString(GoogleDriveBackupWorker.KEY_OWNER_ID, "user_alice")
            .putLong(GoogleDriveBackupWorker.KEY_REVISION, 1L)
            .build()

        val result = GoogleDriveBackupWorker.performBackup(testContext, inputData, 0)

        assertTrue("Worker must fail when snapshot path is missing", result is ListenableWorker.Result.Failure)
        assertFalse("Drive uploader must NOT be called", uploaderCalled)
        assertEquals(SyncStatus.FAILED, repo.getDocument("doc_nosnap")?.syncStatus)
    }

    // -------------------------------------------------------------------------
    // 3. Retry Retention & Terminal Cleanup
    // -------------------------------------------------------------------------

    @Test
    fun testWorker_onTransientError_retriesAndPreservesSnapshot() = runBlocking {
        setupVipUser("alice@test.com", "user_alice")
        val repo = DocumentRepo.getInstance(testContext)
        val pdf = File(testContext.filesDir, "doc.pdf").apply { writeText("CONTENT") }
        val doc = DocumentItem(id = "doc_transient", title = "Doc", ownerId = "user_alice", pdfPath = pdf.absolutePath, contentRevision = 1L)
        repo.addDocument(doc)

        val snapFile = BackupSnapshotStore.createSnapshot(testContext, doc.id, doc.contentRevision, pdf)!!
        assertTrue(snapFile.exists())

        GoogleDriveBackupWorker.tokenProviderForTesting = { _, _ -> "token" }
        GoogleDriveBackupWorker.driveUploaderForTesting = { _, _, _, _, _ ->
            DriveOperationResult.TransientError(503, "Service Unavailable")
        }

        val inputData = Data.Builder()
            .putString(GoogleDriveBackupWorker.KEY_DOC_ID, doc.id)
            .putString(GoogleDriveBackupWorker.KEY_PDF_PATH, pdf.absolutePath)
            .putString(GoogleDriveBackupWorker.KEY_SNAPSHOT_PATH, snapFile.absolutePath)
            .putString(GoogleDriveBackupWorker.KEY_OWNER_ID, "user_alice")
            .putLong(GoogleDriveBackupWorker.KEY_REVISION, 1L)
            .build()

        val result = GoogleDriveBackupWorker.performBackup(testContext, inputData, 0)

        assertTrue("Worker must return Retry on transient 503", result is ListenableWorker.Result.Retry)
        assertTrue("Snapshot file MUST be preserved for subsequent retry attempt", snapFile.exists())
    }

    @Test
    fun testWorker_onPermanentError_failsAndDeletesSnapshot() = runBlocking {
        setupVipUser("alice@test.com", "user_alice")
        val repo = DocumentRepo.getInstance(testContext)
        val pdf = File(testContext.filesDir, "doc.pdf").apply { writeText("CONTENT") }
        val doc = DocumentItem(id = "doc_perm", title = "Doc", ownerId = "user_alice", pdfPath = pdf.absolutePath, contentRevision = 1L)
        repo.addDocument(doc)

        val snapFile = BackupSnapshotStore.createSnapshot(testContext, doc.id, doc.contentRevision, pdf)!!
        assertTrue(snapFile.exists())

        GoogleDriveBackupWorker.tokenProviderForTesting = { _, _ -> "token" }
        GoogleDriveBackupWorker.driveUploaderForTesting = { _, _, _, _, _ ->
            DriveOperationResult.PermanentError(400, "Bad Request")
        }

        val inputData = Data.Builder()
            .putString(GoogleDriveBackupWorker.KEY_DOC_ID, doc.id)
            .putString(GoogleDriveBackupWorker.KEY_PDF_PATH, pdf.absolutePath)
            .putString(GoogleDriveBackupWorker.KEY_SNAPSHOT_PATH, snapFile.absolutePath)
            .putString(GoogleDriveBackupWorker.KEY_OWNER_ID, "user_alice")
            .putLong(GoogleDriveBackupWorker.KEY_REVISION, 1L)
            .build()

        val result = GoogleDriveBackupWorker.performBackup(testContext, inputData, 0)

        assertTrue("Worker must return Failure on permanent error", result is ListenableWorker.Result.Failure)
        assertFalse("Snapshot file MUST be cleaned up on permanent terminal failure", snapFile.exists())
    }

    // -------------------------------------------------------------------------
    // 4. Orphan Cleanup Policy (>24h active retention, error preservation, terminal deletion)
    // -------------------------------------------------------------------------

    @Test
    fun testCleanOrphanSnapshots_whenWorkIsActive_preservesSnapshotEvenOlderThan24Hours() {
        val oldActiveFile = File(snapshotsDir, "snapshot_doc_retry_rev1_abcd.pdf").apply {
            writeText("RETRY_CONTENT")
            setLastModified(System.currentTimeMillis() - 100000000L) // > 27 hours ago
        }
        assertTrue(oldActiveFile.exists())

        // Mock WorkManager lookup: work is still ACTIVE (e.g. pending/retrying in backoff)
        BackupSnapshotStore.workStateChecker = { _, file ->
            if (file.name == oldActiveFile.name) BackupSnapshotStore.WorkSnapshotStatus.ACTIVE
            else BackupSnapshotStore.WorkSnapshotStatus.UNKNOWN
        }

        val cleaned = BackupSnapshotStore.cleanOrphanSnapshots(testContext)

        assertEquals(0, cleaned)
        assertTrue("Active snapshot must NOT be deleted even when older than 24h", oldActiveFile.exists())
    }

    @Test
    fun testCleanOrphanSnapshots_whenWorkManagerQueryFails_preservesSnapshot() {
        val oldFile = File(snapshotsDir, "snapshot_doc_query_err_rev1_efgh.pdf").apply {
            writeText("ERROR_CONTENT")
            setLastModified(System.currentTimeMillis() - 100000000L) // > 27 hours ago
        }

        // Mock WorkManager query throwing error (e.g. SQLite database locked)
        BackupSnapshotStore.workStateChecker = { _, _ ->
            BackupSnapshotStore.WorkSnapshotStatus.QUERY_ERROR
        }

        val cleaned = BackupSnapshotStore.cleanOrphanSnapshots(testContext)

        assertEquals(0, cleaned)
        assertTrue("Snapshot must be preserved when WorkManager lookup fails (fail-safe)", oldFile.exists())
    }

    @Test
    fun testCleanOrphanSnapshots_whenWorkIsTerminal_cleansUpImmediately() {
        val finishedFile = File(snapshotsDir, "snapshot_doc_done_rev1_ijkl.pdf").apply {
            writeText("DONE_CONTENT")
            setLastModified(System.currentTimeMillis() - 1000L) // Just finished (recent)
        }

        BackupSnapshotStore.workStateChecker = { _, _ ->
            BackupSnapshotStore.WorkSnapshotStatus.TERMINAL
        }

        val cleaned = BackupSnapshotStore.cleanOrphanSnapshots(testContext)

        assertEquals(1, cleaned)
        assertFalse("Terminal work snapshot must be deleted immediately", finishedFile.exists())
    }

    @Test
    fun testCleanOrphanSnapshots_whenUnknownAndYoungerThan24h_preservesFile() {
        val brandNewFile = File(snapshotsDir, "snapshot_doc_new_rev1_mnop.pdf").apply {
            writeText("NEW_CONTENT")
            setLastModified(System.currentTimeMillis() - 300000L) // 5 minutes ago
        }

        BackupSnapshotStore.workStateChecker = { _, _ ->
            BackupSnapshotStore.WorkSnapshotStatus.UNKNOWN
        }

        val cleaned = BackupSnapshotStore.cleanOrphanSnapshots(testContext)

        assertEquals(0, cleaned)
        assertTrue("Brand new snapshot without immediate work record must be preserved", brandNewFile.exists())
    }

    @Test
    fun testCleanOrphanSnapshots_whenUnknownAndOlderThan24h_cleansUpFile() {
        val trueOrphan = File(snapshotsDir, "snapshot_doc_orphan_rev1_qrst.pdf").apply {
            writeText("ORPHAN_CONTENT")
            setLastModified(System.currentTimeMillis() - 95000000L) // > 26 hours ago
        }

        BackupSnapshotStore.workStateChecker = { _, _ ->
            BackupSnapshotStore.WorkSnapshotStatus.UNKNOWN
        }

        val cleaned = BackupSnapshotStore.cleanOrphanSnapshots(testContext)

        assertEquals(1, cleaned)
        assertFalse("True orphan older than 24h must be purged", trueOrphan.exists())
    }

    // -------------------------------------------------------------------------
    // 5. Cancel / Replace & Process Recreation
    // -------------------------------------------------------------------------

    @Test
    fun testCancelAndReplace_cleansUpReplacedSnapshotAndRetainsNewOne() {
        val snapOld = File(snapshotsDir, "snapshot_doc1_rev1_old.pdf").apply { writeText("OLD") }
        val snapNew = File(snapshotsDir, "snapshot_doc1_rev2_new.pdf").apply { writeText("NEW") }

        BackupSnapshotStore.workStateChecker = { _, file ->
            if (file.name == snapOld.name) BackupSnapshotStore.WorkSnapshotStatus.TERMINAL // Old work CANCELLED
            else BackupSnapshotStore.WorkSnapshotStatus.ACTIVE // New work ENQUEUED
        }

        val cleaned = BackupSnapshotStore.cleanOrphanSnapshots(testContext)

        assertEquals(1, cleaned)
        assertFalse("Replaced/cancelled snapshot must be deleted", snapOld.exists())
        assertTrue("Active replacement snapshot must be preserved", snapNew.exists())
    }

    @Test
    fun testProcessRecreation_retainsDiskSnapshotAndCompletesUpload() = runBlocking {
        setupVipUser("alice@test.com", "user_alice")
        val repo = DocumentRepo.getInstance(testContext)
        val pdf = File(testContext.filesDir, "doc_proc.pdf").apply { writeText("PROCESS_CONTENT") }
        val doc = DocumentItem(id = "doc_proc", title = "Process", ownerId = "user_alice", pdfPath = pdf.absolutePath, contentRevision = 1L)
        repo.addDocument(doc)

        val snapFile = BackupSnapshotStore.createSnapshot(testContext, doc.id, doc.contentRevision, pdf)!!
        assertTrue(snapFile.exists())

        // 1. Simulate process kill by clearing all test seams and re-instantiating instances
        CloudBackupManager.resetForTesting()
        BackupSnapshotStore.resetForTesting()
        GoogleDriveBackupWorker.resetForTesting()
        BackupSnapshotStore.baseDirOverride = snapshotsDir

        // 2. Set up new worker after process recreation
        GoogleDriveBackupWorker.tokenProviderForTesting = { _, _ -> "recovered_token" }
        var uploadedText: String? = null
        GoogleDriveBackupWorker.driveUploaderForTesting = { _, _, file, _, _ ->
            uploadedText = file.readText()
            DriveOperationResult.Success("drive_file_proc_id")
        }

        val inputData = Data.Builder()
            .putString(GoogleDriveBackupWorker.KEY_DOC_ID, "doc_proc")
            .putString(GoogleDriveBackupWorker.KEY_PDF_PATH, pdf.absolutePath)
            .putString(GoogleDriveBackupWorker.KEY_SNAPSHOT_PATH, snapFile.absolutePath)
            .putString(GoogleDriveBackupWorker.KEY_DOC_TITLE, "Process")
            .putString(GoogleDriveBackupWorker.KEY_OWNER_ID, "user_alice")
            .putLong(GoogleDriveBackupWorker.KEY_REVISION, 1L)
            .build()

        val result = GoogleDriveBackupWorker.performBackup(testContext, inputData, 0)

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("PROCESS_CONTENT", uploadedText)
        assertFalse("Snapshot must be cleaned up upon successful completion", snapFile.exists())
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun setupVipUser(email: String, id: String = "user_canonical_vip") {
        val expires = System.currentTimeMillis() + 86400000L
        fakePrefs.edit()
            .putString("key_user_profile", """{"id":"$id","email":"$email","displayName":"VIP Tester","isVip":true,"tier":"vip","vipExpiresAt":$expires}""")
            .putString("vip_account_${id}_tier", "vip")
            .putLong("vip_account_${id}_expires_at", expires)
            .putLong("vip_account_${id}_purchased_at", System.currentTimeMillis())
            .apply()
        AppAuthManager.init(testContext)
        AppAuthManager.setUserVipTier(testContext, VipTier.VIP, durationDays = 365)
    }

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
