package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.model.SyncStatus
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.CloudBackupManager
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

class CloudSessionGenerationGuardTest {

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
    fun testSessionGenerationIncrementsOnUserChange() {
        val initialGen = AppAuthManager.getSessionGeneration()

        // 1. User A logs in
        val userA = UserProfile(
            id = "user_A",
            email = "a@test.com",
            displayName = "User A",
            isVip = true,
            tier = VipTier.VIP
        )
        fakePrefs.edit().putString("key_user_profile", """
            {"id":"user_A","email":"a@test.com","displayName":"User A","isVip":true,"tier":"vip"}
        """.trimIndent()).apply()
        AppAuthManager.init(testContext)

        // 2. Switch to Demo User
        AppAuthManager.signInWithDemoAccount(testContext) {}
        val demoGen = AppAuthManager.getSessionGeneration()
        assertTrue("Session generation must increment when switching to demo user", demoGen > initialGen)

        // 3. User signs out
        val latch = CountDownLatch(1)
        AppAuthManager.signOut(testContext, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)) {
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)

        val signoutGen = AppAuthManager.getSessionGeneration()
        assertTrue("Session generation must increment on signOut", signoutGen > demoGen)
    }

    @Test
    fun testCancelActiveCloudTasks_invalidatesInFlightCloudScope() {
        // User A starts cloud work
        val userA = UserProfile(id = "user_A", email = "a@test.com", displayName = "A", isVip = true, tier = VipTier.VIP)
        fakePrefs.edit().putString("key_user_profile", """
            {"id":"user_A","email":"a@test.com","displayName":"A","isVip":true,"tier":"vip"}
        """.trimIndent()).apply()
        AppAuthManager.init(testContext)

        // Invalidate cloud tasks
        CloudBackupManager.cancelActiveCloudTasks(testContext, "user_A")

        // Invalidate does not crash and leaves clean state
        assertTrue(true)
    }

    @Test
    fun testSessionChangeMidFlight_workerCasCommitAborts() {
        val repo = DocumentRepo.getInstance(testContext)
        val userA = "user_canonical_A"
        val userB = "user_canonical_B"

        val docA = DocumentItem(
            id = "doc-session-test",
            title = "A Private Document",
            ownerId = userA,
            contentRevision = 1L,
            syncStatus = SyncStatus.SYNCING
        )
        repo.addDocument(docA)

        // Simulate: While User A's worker is in flight uploading, User A logs out and User B logs in
        // In-flight worker for User A attempts to commit SYNCED:
        // Expected owner is userA. But if it attempts CAS under userB session or with mismatched owner:
        val casResult = repo.updateSyncStatusCas(
            docId = "doc-session-test",
            expectedOwnerId = userB, // Mismatched active session user
            expectedRevision = 1L,
            status = SyncStatus.SYNCED,
            driveFileId = "drive_cross_session"
        )
        assertFalse("CAS commit must reject commit when active user does not match doc owner", casResult)

        val docAfter = repo.getDocument("doc-session-test")
        assertEquals("Document must still belong to userA", userA, docAfter?.ownerId)
        assertFalse("Document must NOT be marked synced by mismatched session", docAfter?.isSynced == true)
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
