package com.tscanner.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.GoogleSignInAccountData
import com.tscanner.app.utils.GoogleSignInAccountParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GoogleIdentityOptionsTest {

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

    // -------------------------------------------------------------------------
    // Options Tests
    // -------------------------------------------------------------------------

    @Test
    fun testBuildGoogleSignInOptions_doesNotContainDriveScope() {
        val options = AppAuthManager.buildGoogleSignInOptions()
        assertNotNull("GoogleSignInOptions must not be null", options)

        // Must request ID token, email, and profile
        assertTrue("Must request idToken", options.isIdTokenRequested)
        assertEquals("Server client ID must match webClientId", AppAuthManager.webClientId, options.serverClientId)

        // Must NOT request Drive scope
        val hasDriveScope = options.scopes.any { scope ->
            scope.scopeUri.contains("drive", ignoreCase = true)
        }
        assertFalse("Identity sign-in options must NEVER request drive.file scope", hasDriveScope)
    }

    @Test
    fun testBuildGoogleDriveSignInOptions_withActiveUser_requestsDriveScope() {
        val user = UserProfile(
            id = "user_alice",
            email = "alice@example.com",
            displayName = "Alice",
            isVip = true,
            tier = VipTier.VIP
        )
        val options = AppAuthManager.buildGoogleDriveSignInOptions(user)
        assertNotNull("Drive options must not be null", options)

        // Must request DRIVE_FILE_SCOPE
        assertTrue(
            "Drive options must request DRIVE_FILE_SCOPE",
            options.scopes.any { it.scopeUri == AppAuthManager.DRIVE_FILE_SCOPE }
        )
    }

    @Test
    fun testBuildGoogleDriveSignInOptions_withNullUser_requestsDriveScopeWithoutBoundAccount() {
        val options = AppAuthManager.buildGoogleDriveSignInOptions(null)
        assertNotNull("Drive options must not be null", options)
        assertTrue(
            "Drive options must request DRIVE_FILE_SCOPE even when user is null",
            options.scopes.any { it.scopeUri == AppAuthManager.DRIVE_FILE_SCOPE }
        )
        assertNull("Account name should not be set when user is null", options.account)
    }

    // -------------------------------------------------------------------------
    // Post-Authorization Sync Tests
    // -------------------------------------------------------------------------

    @Test
    fun testRunPostAuthorizationSync_freeUser_doesNotTriggerCloudBackup() {
        val freeUser = UserProfile(
            id = "user_free",
            email = "free@example.com",
            displayName = "Free User",
            isVip = false,
            tier = VipTier.FREE
        )
        saveAndSetCurrentUser(freeUser)

        val repo = DocumentRepo.getInstance(testContext)
        val testPdf = File(testContext.filesDir, "doc.pdf").apply { writeText("content") }
        repo.addDocument(DocumentItem(id = "doc_free", title = "Free Doc", ownerId = "user_free", pdfPath = testPdf.absolutePath, isSynced = false))

        var permissionProviderQueried = false
        AppAuthManager.runPostAuthorizationSync(testContext, hasDrivePermissionProvider = {
            permissionProviderQueried = true
            true
        })

        // Free user must not even query drive permission or enqueue backup
        assertFalse("Free user must not check drive permission for cloud sync", permissionProviderQueried)
        val docAfter = repo.getDocument("doc_free")
        assertFalse("Doc must remain unsynced", docAfter?.isSynced ?: true)
    }

    @Test
    fun testRunPostAuthorizationSync_vipUserWithoutDrivePermission_doesNotEnqueueBackup() {
        val repo = DocumentRepo.getInstance(testContext)
        val testPdf = File(testContext.filesDir, "doc_vip.pdf").apply { writeText("content") }
        repo.addDocument(DocumentItem(id = "doc_vip_1", title = "VIP Doc", ownerId = "user_vip_no_perm", pdfPath = testPdf.absolutePath, isSynced = false))

        val vipUser = UserProfile(
            id = "user_vip_no_perm",
            email = "vip_noperm@example.com",
            displayName = "VIP User No Perm",
            isVip = true,
            tier = VipTier.VIP,
            vipExpiresAt = System.currentTimeMillis() + 86400000L
        )
        saveAndSetCurrentUser(vipUser)

        var queried = false
        AppAuthManager.runPostAuthorizationSync(testContext, hasDrivePermissionProvider = {
            queried = true
            false // Drive permission NOT granted
        })

        assertTrue("Permission provider should be queried for VIP user", queried)
        val docAfter = repo.getDocument("doc_vip_1")
        assertFalse("Document must remain unsynced and not corrupt state", docAfter?.isSynced ?: true)
    }

    @Test
    fun testRunPostAuthorizationSync_vipUserWithDrivePermission_queriesUnsyncedDocs() {
        val vipUser = UserProfile(
            id = "user_vip_granted",
            email = "vip_granted@example.com",
            displayName = "VIP Granted",
            isVip = true,
            tier = VipTier.VIP,
            vipExpiresAt = System.currentTimeMillis() + 86400000L
        )
        saveAndSetCurrentUser(vipUser)

        val repo = DocumentRepo.getInstance(testContext)
        val unsynced = repo.getUnsyncedDocuments("user_vip_granted")
        assertTrue("Initially no unsynced documents", unsynced.isEmpty())

        var queried = false
        AppAuthManager.runPostAuthorizationSync(testContext, hasDrivePermissionProvider = {
            queried = true
            true
        })
        assertTrue("Permission provider should be queried and return true", queried)
    }

    // -------------------------------------------------------------------------
    // Drive Permission Result Handling Tests
    // -------------------------------------------------------------------------

    @Test
    fun testHandleDrivePermissionResult_denied_doesNotSignOutUser() {
        val user = UserProfile(
            id = "user_alice",
            email = "alice@example.com",
            displayName = "Alice",
            isVip = true,
            tier = VipTier.VIP
        )
        saveAndSetCurrentUser(user)

        val fakeParser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData {
                return GoogleSignInAccountData(
                    id = "g_alice",
                    email = "alice@example.com",
                    displayName = "Alice",
                    givenName = "Alice",
                    familyName = "Smith",
                    photoUrl = null,
                    idToken = null,
                    grantedScopes = emptySet() // Denied: no DRIVE_FILE_SCOPE
                )
            }
        }

        var errorReported: String? = null
        val attempt1 = AppAuthManager.createDriveAuthorizationAttempt()
        AppAuthManager.handleDrivePermissionResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = attempt1,
            parser = fakeParser,
            onSuccess = { fail("Must not succeed when Drive scope is denied") },
            onError = { msg -> errorReported = msg }
        )

        assertEquals("Quyền truy cập Google Drive bị từ chối", errorReported)
        // User must NOT be signed out
        assertEquals("user_alice", AppAuthManager.currentUser.value?.id)
        assertEquals("alice@example.com", AppAuthManager.currentUser.value?.email)
    }

    @Test
    fun testHandleDrivePermissionResult_accountMismatch_doesNotSignOutUser() {
        val user = UserProfile(
            id = "user_alice",
            email = "alice@example.com",
            displayName = "Alice",
            isVip = true,
            tier = VipTier.VIP
        )
        saveAndSetCurrentUser(user)

        val fakeParser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData {
                return GoogleSignInAccountData(
                    id = "g_bob",
                    email = "bob@example.com", // Mismatch!
                    displayName = "Bob",
                    givenName = "Bob",
                    familyName = "Jones",
                    photoUrl = null,
                    idToken = null,
                    grantedScopes = setOf(AppAuthManager.DRIVE_FILE_SCOPE)
                )
            }
        }

        var errorReported: String? = null
        val attempt2 = AppAuthManager.createDriveAuthorizationAttempt()
        AppAuthManager.handleDrivePermissionResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = attempt2,
            parser = fakeParser,
            onSuccess = { fail("Must not succeed when account mismatches") },
            onError = { msg -> errorReported = msg }
        )

        assertNotNull("Error message must be reported for mismatch", errorReported)
        // Current user must remain Alice
        assertEquals("user_alice", AppAuthManager.currentUser.value?.id)
        assertEquals("alice@example.com", AppAuthManager.currentUser.value?.email)
    }

    @Test
    fun testHandleDrivePermissionResult_success_maintainsCurrentUser() {
        val user = UserProfile(
            id = "user_alice",
            email = "alice@example.com",
            displayName = "Alice",
            isVip = true,
            tier = VipTier.VIP
        )
        saveAndSetCurrentUser(user)

        val fakeParser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData {
                return GoogleSignInAccountData(
                    id = "g_alice",
                    email = "alice@example.com",
                    displayName = "Alice",
                    givenName = "Alice",
                    familyName = "Smith",
                    photoUrl = null,
                    idToken = null,
                    grantedScopes = setOf(AppAuthManager.DRIVE_FILE_SCOPE)
                )
            }
        }

        var successCalled = false
        val attempt3 = AppAuthManager.createDriveAuthorizationAttempt()
        AppAuthManager.handleDrivePermissionResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = attempt3,
            parser = fakeParser,
            onSuccess = { successCalled = true },
            onError = { msg -> fail("Should not report error on valid grant: $msg") }
        )

        assertTrue("onSuccess must be called", successCalled)
        assertEquals("user_alice", AppAuthManager.currentUser.value?.id)
        assertEquals("alice@example.com", AppAuthManager.currentUser.value?.email)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun saveAndSetCurrentUser(profile: UserProfile) {
        val json = """
            {
                "id": "${profile.id}",
                "email": "${profile.email}",
                "displayName": "${profile.displayName}",
                "isVip": ${profile.isVip},
                "tier": "${profile.tier.name.lowercase()}",
                "vipExpiresAt": ${profile.vipExpiresAt ?: "null"}
            }
        """.trimIndent()
        fakePrefs.edit().putString("key_user_profile", json).apply()
        if (profile.isVip) {
            AppAuthManager.setVipForUserForTesting(
                testContext,
                profile.id,
                profile.tier,
                profile.vipPurchasedAt ?: System.currentTimeMillis(),
                profile.vipExpiresAt ?: (System.currentTimeMillis() + 86400000L)
            )
        }
        AppAuthManager.init(testContext)
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
