package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import com.google.common.util.concurrent.ListenableFuture
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BackupSnapshotStore
import com.tscanner.app.utils.CloudBackupManager
import com.tscanner.app.utils.SyncCatalogResult
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CloudBackupDispatchTest {

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
        DocumentRepo.getInstance(testContext).autoBackupEnabled = false
        CloudBackupManager.resetForTesting()
        CloudBackupManager.workEnqueuer = { _, _, _, _ -> }
        BackupSnapshotStore.resetForTesting()
        BackupSnapshotStore.baseDirOverride = snapshotsDir
    }

    @After
    fun tearDown() {
        AppAuthManager.resetForTesting()
        DocumentRepo.resetInstanceForTesting()
        CloudBackupManager.resetForTesting()
        BackupSnapshotStore.resetForTesting()
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
    // 1. Non-blocking UI Caller Behavior (Latch test)
    // -------------------------------------------------------------------------

    @Test(timeout = 5000)
    fun testRunPostAuthorizationSync_doesNotBlockCallerThread_whenSnapshotCopySlow() {
        setupVipUser("alice@test.com", "user_alice")
        val repo = DocumentRepo.getInstance(testContext)
        val pdf = File(testContext.filesDir, "slow_doc.pdf").apply { writeText("SLOW_CONTENT") }
        repo.addDocument(DocumentItem(id = "doc_slow", title = "Slow Doc", ownerId = "user_alice", pdfPath = pdf.absolutePath, isSynced = false))

        val copyEnteredLatch = CountDownLatch(1)
        val unblockCopyLatch = CountDownLatch(1)

        BackupSnapshotStore.snapshotCopyHook = {
            copyEnteredLatch.countDown()
            unblockCopyLatch.await(3, TimeUnit.SECONDS)
        }

        CloudBackupManager.syncCatalogOverrideForTesting = { _, callback ->
            callback(SyncCatalogResult.Success(0, 0))
        }

        val startTime = System.currentTimeMillis()

        // Calling runPostAuthorizationSync with default Dispatchers.IO
        AppAuthManager.runPostAuthorizationSync(
            context = testContext,
            hasDrivePermissionProvider = { true }
        )

        val returnDuration = System.currentTimeMillis() - startTime

        // UI calling thread MUST return almost immediately without waiting for copy
        assertTrue("Caller thread should not block on snapshot copy (took ${returnDuration}ms)", returnDuration < 1000L)

        // Wait until background worker actually enters the copy
        assertTrue("Background worker should have reached copy step", copyEnteredLatch.await(2, TimeUnit.SECONDS))

        // Now unblock the copy and wait for completion
        unblockCopyLatch.countDown()
    }

    @Test(timeout = 5000)
    fun testEnqueueBackupAsync_doesNotBlockCallerThread_whenSnapshotCopySlow() {
        setupVipUser("alice@test.com", "user_alice")
        val repo = DocumentRepo.getInstance(testContext)
        val pdf = File(testContext.filesDir, "slow_async.pdf").apply { writeText("ASYNC_CONTENT") }
        val doc = DocumentItem(id = "doc_async", title = "Async Doc", ownerId = "user_alice", pdfPath = pdf.absolutePath, isSynced = false)
        repo.addDocument(doc)

        val copyEnteredLatch = CountDownLatch(1)
        val unblockCopyLatch = CountDownLatch(1)

        BackupSnapshotStore.snapshotCopyHook = {
            copyEnteredLatch.countDown()
            unblockCopyLatch.await(3, TimeUnit.SECONDS)
        }

        val startTime = System.currentTimeMillis()

        val job = CloudBackupManager.enqueueBackupAsync(testContext, doc)

        val returnDuration = System.currentTimeMillis() - startTime
        assertTrue("enqueueBackupAsync must return immediately (< 1000ms, took ${returnDuration}ms)", returnDuration < 1000L)
        assertTrue("Job must be active", job.isActive)

        assertTrue("Background worker should enter copy step", copyEnteredLatch.await(2, TimeUnit.SECONDS))

        unblockCopyLatch.countDown()
        runBlocking {
            job.join()
        }
        assertFalse("Job must be completed", job.isActive)
    }

    // -------------------------------------------------------------------------
    // 2. Batch Enqueue: Clean Orphan Snapshots Exactly Once
    // -------------------------------------------------------------------------

    @Test
    fun testEnqueueBatchBackup_performsOrphanCleanupOnlyOnceForMultipleFiles() {
        setupVipUser("alice@test.com", "user_alice")
        val repo = DocumentRepo.getInstance(testContext)

        val docs = mutableListOf<DocumentItem>()
        for (i in 1..5) {
            val pdf = File(testContext.filesDir, "batch_doc_$i.pdf").apply { writeText("CONTENT_$i") }
            val doc = DocumentItem(id = "doc_batch_$i", title = "Batch Doc $i", ownerId = "user_alice", pdfPath = pdf.absolutePath, isSynced = false)
            repo.addDocument(doc)
            docs.add(doc)
        }

        val cleanupCount = AtomicInteger(0)
        BackupSnapshotStore.orphanCleanupInterceptor = { _, _ ->
            cleanupCount.incrementAndGet()
            0
        }

        val capturedWorkCount = AtomicInteger(0)
        CloudBackupManager.workEnqueuer = { _, _, _, _ ->
            capturedWorkCount.incrementAndGet()
        }

        CloudBackupManager.enqueueBatchBackup(testContext, docs)

        assertEquals("Orphan snapshot cleanup must be executed EXACTLY ONCE for entire batch", 1, cleanupCount.get())
        assertEquals("All 5 documents must be enqueued for backup", 5, capturedWorkCount.get())
    }

    // -------------------------------------------------------------------------
    // 3. User Switch / Session Invalidation During Copy
    // -------------------------------------------------------------------------

    @Test
    fun testEnqueueBackup_abortsAndDeletesSnapshot_whenUserSwitchesDuringCopy() {
        setupVipUser("alice@test.com", "user_alice")
        val repo = DocumentRepo.getInstance(testContext)
        val pdf = File(testContext.filesDir, "alice_secret.pdf").apply { writeText("ALICE_SECRET_DATA") }
        val doc = DocumentItem(id = "doc_alice_1", title = "Alice Secret", ownerId = "user_alice", pdfPath = pdf.absolutePath, isSynced = false)
        repo.addDocument(doc)

        // Clear any snapshot created by repo.addDocument auto-backup so we test this specific enqueue call in isolation
        snapshotsDir.listFiles()?.forEach { it.delete() }
        assertEquals(0, snapshotsDir.listFiles()?.size ?: 0)

        val capturedRequest = AtomicReference<OneTimeWorkRequest?>()
        CloudBackupManager.workEnqueuer = { _, _, _, request ->
            capturedRequest.set(request)
        }

        // Hook into snapshot copy: switch user to Bob while Alice's PDF is being copied!
        BackupSnapshotStore.snapshotCopyHook = {
            setupVipUser("bob@test.com", "user_bob")
        }

        CloudBackupManager.enqueueBackup(
            context = testContext,
            docItem = doc,
            skipCleanup = true,
            expectedUserId = "user_alice"
        )

        // Work request MUST NOT be enqueued because user switched!
        assertNull("WorkManager must NOT be enqueued when user switches during copy", capturedRequest.get())

        // Snapshot directory must have NO lingering PDF files (must have been deleted)
        val snapshotFiles = snapshotsDir.listFiles { _, name -> name.endsWith(".pdf") } ?: emptyArray()
        assertEquals("Snapshot file must be deleted upon account switch to prevent data leakage", 0, snapshotFiles.size)
    }

    // -------------------------------------------------------------------------
    // 4. WorkManager Query Timeout & Fail-Safe Retention
    // -------------------------------------------------------------------------

    @Test
    fun testCheckWorkStatus_returnsQueryErrorAndRetainsSnapshot_whenFutureTimesOut() {
        val dummySnapshot = File(snapshotsDir, "snapshot_test_doc_rev1_abcd1234.pdf").apply {
            writeText("SNAPSHOT_BYTES")
        }
        assertTrue("Snapshot file must exist", dummySnapshot.exists())

        val cancelCalled = AtomicBoolean(false)

        // Mock a future that throws TimeoutException on get(timeout, unit)
        val timingOutFuture = object : ListenableFuture<List<WorkInfo>> {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
                cancelCalled.set(true)
                return true
            }

            override fun isCancelled(): Boolean = cancelCalled.get()
            override fun isDone(): Boolean = false

            override fun get(): List<WorkInfo> = throw TimeoutException("Simulated timeout")
            override fun get(timeout: Long, unit: TimeUnit): List<WorkInfo> {
                throw TimeoutException("Simulated WorkManager query timeout")
            }

            override fun addListener(listener: Runnable, executor: Executor) {
                // No-op
            }
        }

        BackupSnapshotStore.workQueryFutureProvider = { _ ->
            timingOutFuture
        }

        val status = BackupSnapshotStore.checkWorkStatus(testContext, dummySnapshot)
        assertEquals("Timeout must resolve to QUERY_ERROR", BackupSnapshotStore.WorkSnapshotStatus.QUERY_ERROR, status)
        assertTrue("Future.cancel(true) must be called upon timeout", cancelCalled.get())

        // Now run cleanOrphanSnapshots: fail-safe retention must preserve the file!
        val cleaned = BackupSnapshotStore.cleanOrphanSnapshots(testContext, maxAgeMs = 0L)
        assertEquals("Must clean 0 files when query errored", 0, cleaned)
        assertTrue("Snapshot file must be RETAINED despite age when WorkManager query errors/timeouts", dummySnapshot.exists())
    }

    // -------------------------------------------------------------------------
    // 5. Session Cancellation on Sign Out
    // -------------------------------------------------------------------------

    @Test
    fun testRunPostAuthorizationSync_abortsWhenSessionInvalidated() {
        setupVipUser("alice@test.com", "user_alice")
        val repo = DocumentRepo.getInstance(testContext)
        val pdf = File(testContext.filesDir, "cancel_doc.pdf").apply { writeText("CANCEL_DOC") }
        repo.addDocument(DocumentItem(id = "doc_cancel", title = "Cancel Doc", ownerId = "user_alice", pdfPath = pdf.absolutePath, isSynced = false))

        val capturedWork = AtomicReference<OneTimeWorkRequest?>()
        CloudBackupManager.workEnqueuer = { _, _, _, req ->
            capturedWork.set(req)
        }

        // Invalidate session scope before dispatch
        CloudBackupManager.cancelActiveCloudTasks(testContext, "user_alice")

        // Post-auth sync should exit early
        AppAuthManager.runPostAuthorizationSync(
            context = testContext,
            hasDrivePermissionProvider = { true }
        )

        assertNull("No work should be enqueued after session cancellation", capturedWork.get())
    }

    // -------------------------------------------------------------------------
    // Helper Test Classes
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
