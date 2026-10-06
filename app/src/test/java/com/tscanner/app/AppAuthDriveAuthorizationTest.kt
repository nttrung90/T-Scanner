package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.AppAuthManager
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

class AppAuthDriveAuthorizationTest {

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
    fun testHasDrivePermission_returnsFalseWhenNoUserLoggedIn() {
        assertFalse("Must return false when no user is logged in", AppAuthManager.hasDrivePermission(testContext))
    }

    @Test
    fun testRunPostAuthorizationSync_onlyProcessesDocsOwnedByCurrentUser() {
        val userAlice = UserProfile(
            id = "user_alice",
            email = "alice@gmail.com",
            displayName = "Alice",
            isVip = true,
            tier = VipTier.VIP,
            vipExpiresAt = System.currentTimeMillis() + 86400000L
        )
        fakePrefs.edit().putString(
            "key_user_profile",
            """{"id":"user_alice","email":"alice@gmail.com","displayName":"Alice","isVip":true,"tier":"vip","vipExpiresAt":${userAlice.vipExpiresAt}}"""
        ).apply()
        AppAuthManager.init(testContext)

        val repo = DocumentRepo.getInstance(testContext)
        val alicePdf = File(testContext.filesDir, "alice.pdf").apply { writeText("alice content") }
        val bobPdf = File(testContext.filesDir, "bob.pdf").apply { writeText("bob content") }

        val aliceDoc = DocumentItem(id = "doc_a", title = "Alice Doc", ownerId = "user_alice", pdfPath = alicePdf.absolutePath, isSynced = false)
        val bobDoc = DocumentItem(id = "doc_b", title = "Bob Doc", ownerId = "user_bob", pdfPath = bobPdf.absolutePath, isSynced = false)
        repo.addDocument(aliceDoc)
        repo.addDocument(bobDoc)

        // Only Alice's documents must be returned for unsynced upload
        val unsyncedForAlice = repo.getUnsyncedDocuments("user_alice")
        assertEquals(1, unsyncedForAlice.size)
        assertEquals("doc_a", unsyncedForAlice[0].id)

        // Calling runPostAuthorizationSync should not crash or mutate Bob's doc
        AppAuthManager.runPostAuthorizationSync(testContext)
        val bobDocAfter = repo.getDocument("doc_b")
        assertEquals("user_bob", bobDocAfter?.ownerId)
        assertFalse("Bob doc must remain unsynced and untouched by Alice", bobDocAfter?.isSynced ?: true)
    }

    @Test
    fun testBuildGoogleDriveSignInOptions_bindsScope() {
        val user = UserProfile(
            id = "user_123",
            email = "test@example.com",
            displayName = "Tester",
            isVip = false,
            tier = VipTier.FREE
        )
        val options = AppAuthManager.buildGoogleDriveSignInOptions(user)
        assertNotNull("GoogleSignInOptions must be generated", options)
        assertTrue("Must request DRIVE_FILE_SCOPE", options.scopes.any { it.scopeUri == AppAuthManager.DRIVE_FILE_SCOPE })

        val optionsNull = AppAuthManager.buildGoogleDriveSignInOptions(null)
        assertNotNull("GoogleSignInOptions must be generated even if user is null", optionsNull)
        assertTrue("Must request DRIVE_FILE_SCOPE even when user is null", optionsNull.scopes.any { it.scopeUri == AppAuthManager.DRIVE_FILE_SCOPE })
    }

    @Test
    fun testHandleDrivePermissionResult_whenNoUserLoggedIn_callsOnError() {
        var errorMessage: String? = null
        AppAuthManager.handleDrivePermissionResult(
            context = testContext,
            data = null,
            onSuccess = { org.junit.Assert.fail("Should not succeed when no user is logged in") },
            onError = { msg -> errorMessage = msg }
        )
        assertEquals("Chưa đăng nhập tài khoản", errorMessage)
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
