package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BackupSnapshotStore
import com.tscanner.app.utils.CloudBackupManager
import com.tscanner.app.utils.GoogleDriveBackupWorker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

class DocumentRepoBackupDispatchTest {

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

    private fun createDummyPdfFile(name: String, content: String = "Dummy PDF Content"): File {
        val file = File(testContext.filesDir, name)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    @Test
    fun regressionRenameDocument_doesNotBlockCallerOrHoldMonitorDuringBackup() {
        val user = setupVipUser("alice@gmail.com", "uid_alice")
        val repo = DocumentRepo.getInstance(testContext)

        val pdfFile = createDummyPdfFile("test_doc.pdf", "Original PDF Content")
        val doc = DocumentItem(
            id = "doc_rename_1",
            title = "Original Title",
            ownerId = user.id,
            pdfPath = pdfFile.absolutePath,
            sizeBytes = pdfFile.length(),
            contentRevision = 1L
        )
        repo.autoBackupEnabled = false
        repo.addDocument(doc)
        repo.autoBackupEnabled = true

        // Latches to freeze background IO dispatch
        val ioStartedLatch = CountDownLatch(1)
        val allowIoCompletionLatch = CountDownLatch(1)

        val blockingDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                Thread {
                    ioStartedLatch.countDown()
                    // Block the background IO execution until main test thread verifies unblocked monitor
                    allowIoCompletionLatch.await(5, TimeUnit.SECONDS)
                    block.run()
                }.start()
            }
        }

        repo.backupDispatcher = blockingDispatcher

        // Call renameDocument on caller thread
        val callerReturned = AtomicBoolean(false)
        val renameResult = repo.renameDocument("doc_rename_1", "New Nonblocking Title", user.id)
        callerReturned.set(true)

        // Caller must return true immediately without waiting for IO to finish!
        assertTrue("renameDocument must return true immediately", renameResult)
        assertTrue("Caller must return without blocking on background backup", callerReturned.get())

        // Verify background IO is indeed running/paused
        assertTrue("Background backup IO dispatch must have started", ioStartedLatch.await(2, TimeUnit.SECONDS))

        // Concurrently, another thread must be able to acquire DocumentRepo monitor without blocking
        val monitorAcquiredConcurrently = AtomicBoolean(false)
        val monitorThread = Thread {
            val retrieved = repo.getDocument("doc_rename_1")
            assertEquals("New Nonblocking Title", retrieved?.title)
            monitorAcquiredConcurrently.set(true)
        }
        monitorThread.start()
        monitorThread.join(1000)

        assertTrue("Another thread must acquire DocumentRepo monitor without blocking on background backup", monitorAcquiredConcurrently.get())

        // Release the background IO task
        allowIoCompletionLatch.countDown()
    }

    @Test
    fun regressionSessionSwitchWhileBackupQueued_doesNotEnqueueOldUserWork() {
        val userA = setupVipUser("userA@gmail.com", "uid_userA")
        val repo = DocumentRepo.getInstance(testContext)

        val pdfFile = createDummyPdfFile("userA_doc.pdf", "User A PDF")
        val doc = DocumentItem(
            id = "doc_switch_1",
            title = "Title A",
            ownerId = userA.id,
            pdfPath = pdfFile.absolutePath,
            sizeBytes = pdfFile.length(),
            contentRevision = 1L
        )
        repo.autoBackupEnabled = false
        repo.addDocument(doc)
        repo.autoBackupEnabled = true

        val ioStartedLatch = CountDownLatch(1)
        val allowIoCompletionLatch = CountDownLatch(1)
        val enqueuedWork = AtomicReference<String?>(null)

        CloudBackupManager.workEnqueuer = { _, workName, _, _ ->
            enqueuedWork.set(workName)
        }

        val pauseDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                Thread {
                    ioStartedLatch.countDown()
                    allowIoCompletionLatch.await(5, TimeUnit.SECONDS)
                    block.run()
                }.start()
            }
        }

        repo.backupDispatcher = pauseDispatcher

        // Rename document by User A
        assertTrue(repo.renameDocument("doc_switch_1", "Renamed Title A", userA.id))

        // Wait until backup is paused in flight on background dispatcher
        assertTrue(ioStartedLatch.await(2, TimeUnit.SECONDS))

        // Now user A signs out or session generation increments
        AppAuthManager.signOut(testContext, CoroutineScope(Dispatchers.Unconfined)) {}

        // Release background task
        allowIoCompletionLatch.countDown()

        Thread.sleep(200)

        // Work must NOT be enqueued because session changed during / after snapshot copy!
        assertNull("Backup work must not be enqueued after session switch / logout", enqueuedWork.get())
    }

    @Test
    fun regressionSaveFailure_doesNotDispatchBackup() {
        val user = setupVipUser("bob@gmail.com", "uid_bob")
        val repo = DocumentRepo.getInstance(testContext)
        repo.backupDispatcher = Dispatchers.Unconfined

        val pdfFile = createDummyPdfFile("fail_doc.pdf", "PDF")
        val doc = DocumentItem(
            id = "doc_fail_1",
            title = "Initial Title",
            ownerId = user.id,
            pdfPath = pdfFile.absolutePath,
            sizeBytes = pdfFile.length()
        )
        repo.addDocument(doc)

        val backupDispatched = AtomicBoolean(false)
        CloudBackupManager.workEnqueuer = { _, _, _, _ ->
            backupDispatched.set(true)
        }

        // Lock data file to force saveData() failure
        val dataFile = File(testContext.filesDir, "tscanner_data.json")
        val backupFile = File(testContext.filesDir, "tscanner_data.json.bak")
        dataFile.delete()
        dataFile.mkdir() // Make dataFile a directory so writing to it as a file fails
        backupFile.delete()
        backupFile.mkdir()

        try {
            val result = repo.renameDocument("doc_fail_1", "Should Fail", user.id)
            assertFalse("renameDocument must return false when saveData fails", result)
            assertFalse("Backup must NOT be dispatched when save fails", backupDispatched.get())
        } finally {
            dataFile.delete()
            backupFile.delete()
        }
    }

    @Test
    fun regressionMarkDocumentModified_enqueuesCorrectRevisionAndBytes() {
        val user = setupVipUser("carol@gmail.com", "uid_carol")
        val repo = DocumentRepo.getInstance(testContext)
        repo.backupDispatcher = Dispatchers.Unconfined

        val pdfFile = createDummyPdfFile("carol_doc.pdf", "Carol PDF Content")
        val doc = DocumentItem(
            id = "doc_modified_1",
            title = "Carol Doc",
            ownerId = user.id,
            pdfPath = pdfFile.absolutePath,
            sizeBytes = 1000L,
            contentRevision = 1L
        )
        repo.addDocument(doc)

        var enqueuedRevision = -1L
        var enqueuedOwner = ""
        CloudBackupManager.workEnqueuer = { _, _, _, request ->
            enqueuedRevision = request.workSpec.input.getLong(GoogleDriveBackupWorker.KEY_REVISION, -1L)
            enqueuedOwner = request.workSpec.input.getString(GoogleDriveBackupWorker.KEY_OWNER_ID) ?: ""
        }

        val modifiedResult = repo.markDocumentModified("doc_modified_1", newSizeBytes = 2500L, userId = user.id)
        assertTrue("markDocumentModified must return true", modifiedResult)

        val updatedDoc = repo.getDocument("doc_modified_1")
        assertNotNull(updatedDoc)
        assertEquals(2500L, updatedDoc!!.sizeBytes)
        assertEquals(2L, updatedDoc.contentRevision)
        assertEquals("uid_carol", updatedDoc.ownerId)

        assertEquals("Enqueued work must carry incremented revision", 2L, enqueuedRevision)
        assertEquals("Enqueued work must carry correct owner ID", "uid_carol", enqueuedOwner)
    }

    // -------------------------------------------------------------------------
    // Test Harness Classes
    // -------------------------------------------------------------------------

    private class TestContext(
        private val baseDir: File,
        private val prefs: SharedPreferences
    ) : ContextWrapper(null) {
        override fun getFilesDir(): File = File(baseDir, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(baseDir, "cache").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getPackageName(): String = "com.tscanner.app"
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

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) { if (value != null) pending[key] = value else removes.add(key) }
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) { if (values != null) pending[key] = values else removes.add(key) }
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) removes.add(key)
            }
            override fun clear(): SharedPreferences.Editor = apply { clearFlag = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearFlag) prefs.map.clear()
                removes.forEach { prefs.map.remove(it) }
                pending.forEach { (k, v) -> if (v != null) prefs.map[k] = v else prefs.map.remove(k) }
            }
        }
    }
}
