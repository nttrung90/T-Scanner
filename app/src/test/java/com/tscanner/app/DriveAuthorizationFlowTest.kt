package com.tscanner.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.DriveAuthorizationResult
import com.tscanner.app.utils.DriveAuthorizationResultRouter
import com.tscanner.app.utils.GoogleSignInAccountData
import com.tscanner.app.utils.GoogleSignInAccountParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class DriveAuthorizationFlowTest {

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
    // 1. Router Classification Tests
    // -------------------------------------------------------------------------

    @Test
    fun testRouteResult_validAccountAndDriveScope_returnsSuccess() {
        val parser = FakeParser(
            result = GoogleSignInAccountData(
                id = "user_123",
                email = "alice@gmail.com",
                displayName = "Alice",
                givenName = "Alice",
                familyName = "Test",
                photoUrl = null,
                idToken = null,
                grantedScopes = setOf(DriveAuthorizationResultRouter.DRIVE_FILE_SCOPE)
            )
        )
        val dummyIntent = Intent()
        val result = DriveAuthorizationResultRouter.routeResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = dummyIntent,
            expectedUserEmail = "alice@gmail.com",
            currentSessionGeneration = 1L,
            expectedSessionGeneration = 1L,
            parser = parser
        )

        assertTrue(result is DriveAuthorizationResult.Success)
        val success = result as DriveAuthorizationResult.Success
        assertEquals("alice@gmail.com", success.accountData.email)
    }

    @Test
    fun testRouteResult_differentAccountEmail_returnsAccountMismatch() {
        val parser = FakeParser(
            result = GoogleSignInAccountData(
                id = "user_bob",
                email = "bob@gmail.com",
                displayName = "Bob",
                givenName = "Bob",
                familyName = "Test",
                photoUrl = null,
                idToken = null,
                grantedScopes = setOf(DriveAuthorizationResultRouter.DRIVE_FILE_SCOPE)
            )
        )
        val dummyIntent = Intent()
        val result = DriveAuthorizationResultRouter.routeResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = dummyIntent,
            expectedUserEmail = "alice@gmail.com",
            currentSessionGeneration = 1L,
            expectedSessionGeneration = 1L,
            parser = parser
        )

        assertTrue("Must detect account mismatch when granted account differs from logged in user", result is DriveAuthorizationResult.AccountMismatch)
        val mismatch = result as DriveAuthorizationResult.AccountMismatch
        assertEquals("alice@gmail.com", mismatch.expectedEmail)
        assertEquals("bob@gmail.com", mismatch.grantedEmail)
    }

    @Test
    fun testRouteResult_missingDriveScope_returnsPermissionDenied() {
        val parser = FakeParser(
            result = GoogleSignInAccountData(
                id = "user_alice",
                email = "alice@gmail.com",
                displayName = "Alice",
                givenName = "Alice",
                familyName = "Test",
                photoUrl = null,
                idToken = null,
                grantedScopes = emptySet() // Missing drive.file scope
            )
        )
        val dummyIntent = Intent()
        val result = DriveAuthorizationResultRouter.routeResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = dummyIntent,
            expectedUserEmail = "alice@gmail.com",
            currentSessionGeneration = 1L,
            expectedSessionGeneration = 1L,
            parser = parser
        )

        assertTrue("Must report PermissionDenied when scope is missing", result is DriveAuthorizationResult.PermissionDenied)
    }

    @Test
    fun testRouteResult_nullDataWithResultCanceled_returnsCancelled() {
        val result = DriveAuthorizationResultRouter.routeResult(
            context = testContext,
            resultCode = Activity.RESULT_CANCELED,
            data = null,
            expectedUserEmail = "alice@gmail.com",
            currentSessionGeneration = 1L,
            expectedSessionGeneration = 1L
        )

        assertTrue("Null data with RESULT_CANCELED represents pure cancellation", result is DriveAuthorizationResult.Cancelled)
    }

    @Test
    fun testRouteResult_apiException12501_returnsCancelled() {
        val parser = FakeParser(error = ApiException(Status(12501, "Sign-in cancelled by user")))
        val dummyIntent = Intent()
        val result = DriveAuthorizationResultRouter.routeResult(
            context = testContext,
            resultCode = Activity.RESULT_CANCELED,
            data = dummyIntent,
            expectedUserEmail = "alice@gmail.com",
            currentSessionGeneration = 1L,
            expectedSessionGeneration = 1L,
            parser = parser
        )

        assertTrue("Status code 12501 in Intent represents cancellation", result is DriveAuthorizationResult.Cancelled)
    }

    @Test
    fun testRouteResult_apiExceptionNetworkError7_returnsFailure() {
        val parser = FakeParser(error = ApiException(Status(7, "Network Error")))
        val dummyIntent = Intent()
        val result = DriveAuthorizationResultRouter.routeResult(
            context = testContext,
            resultCode = Activity.RESULT_CANCELED,
            data = dummyIntent,
            expectedUserEmail = "alice@gmail.com",
            currentSessionGeneration = 1L,
            expectedSessionGeneration = 1L,
            parser = parser
        )

        assertTrue("Non-cancel ApiException must be routed to Failure even if RESULT_CANCELED", result is DriveAuthorizationResult.Failure)
        val failure = result as DriveAuthorizationResult.Failure
        assertEquals(7, failure.statusCode)
    }

    @Test
    fun testRouteResult_sessionChangedWhileConsentOpen_returnsSessionExpiredOrChanged() {
        val parser = FakeParser(
            result = GoogleSignInAccountData(
                id = "user_123",
                email = "alice@gmail.com",
                displayName = "Alice",
                givenName = "Alice",
                familyName = "Test",
                photoUrl = null,
                idToken = null,
                grantedScopes = setOf(DriveAuthorizationResultRouter.DRIVE_FILE_SCOPE)
            )
        )
        val dummyIntent = Intent()
        val result = DriveAuthorizationResultRouter.routeResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = dummyIntent,
            expectedUserEmail = "alice@gmail.com",
            currentSessionGeneration = 2L, // Session changed while consent was open
            expectedSessionGeneration = 1L,
            parser = parser
        )

        assertTrue("Stale session generation must yield SessionExpiredOrChanged", result is DriveAuthorizationResult.SessionExpiredOrChanged)
    }

    // -------------------------------------------------------------------------
    // 2. Dispatcher Guarantees (Single callback, Error handling)
    // -------------------------------------------------------------------------

    @Test
    fun testDispatchResult_success_invokesOnSuccessExactlyOnce() {
        val parser = FakeParser(
            result = GoogleSignInAccountData(
                id = "user_123",
                email = "alice@gmail.com",
                displayName = "Alice",
                givenName = "Alice",
                familyName = "Test",
                photoUrl = null,
                idToken = null,
                grantedScopes = setOf(DriveAuthorizationResultRouter.DRIVE_FILE_SCOPE)
            )
        )
        val successCount = AtomicInteger(0)
        val cancelCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)

        DriveAuthorizationResultRouter.dispatchResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            expectedUserEmail = "alice@gmail.com",
            currentSessionGeneration = 1L,
            expectedSessionGeneration = 1L,
            parser = parser,
            onSuccess = { successCount.incrementAndGet() },
            onCancelled = { cancelCount.incrementAndGet() },
            onError = { errorCount.incrementAndGet() }
        )

        assertEquals(1, successCount.get())
        assertEquals(0, cancelCount.get())
        assertEquals(0, errorCount.get())
    }

    @Test
    fun testDispatchResult_accountMismatch_callsOnError() {
        val parser = FakeParser(
            result = GoogleSignInAccountData(
                id = "user_bob",
                email = "bob@gmail.com",
                displayName = "Bob",
                givenName = "Bob",
                familyName = "Test",
                photoUrl = null,
                idToken = null,
                grantedScopes = setOf(DriveAuthorizationResultRouter.DRIVE_FILE_SCOPE)
            )
        )
        var receivedError: String? = null

        DriveAuthorizationResultRouter.dispatchResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            expectedUserEmail = "alice@gmail.com",
            currentSessionGeneration = 1L,
            expectedSessionGeneration = 1L,
            parser = parser,
            onSuccess = { org.junit.Assert.fail("Must not succeed on account mismatch") },
            onCancelled = { org.junit.Assert.fail("Must not cancel on account mismatch") },
            onError = { msg -> receivedError = msg }
        )

        assertTrue(receivedError != null && receivedError!!.contains("không khớp"))
    }

    // -------------------------------------------------------------------------
    // 3. AppAuthManager Integration & Post-Auth Sync Guarantees
    // -------------------------------------------------------------------------

    @Test
    fun testAppAuthManager_handleDrivePermissionResult_validAccount_triggersPostAuthSync() {
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
        val alicePdf = File(testContext.filesDir, "alice_scan.pdf").apply { writeText("alice doc") }
        repo.addDocument(DocumentItem(id = "doc_alice_1", title = "Scan 1", ownerId = "user_alice", pdfPath = alicePdf.absolutePath, isSynced = false))

        val parser = FakeParser(
            result = GoogleSignInAccountData(
                id = "user_alice",
                email = "alice@gmail.com",
                displayName = "Alice",
                givenName = "Alice",
                familyName = "Test",
                photoUrl = null,
                idToken = null,
                grantedScopes = setOf(DriveAuthorizationResultRouter.DRIVE_FILE_SCOPE)
            )
        )

        val attempt = AppAuthManager.createDriveAuthorizationAttempt()
        var successCalled = false
        AppAuthManager.handleDrivePermissionResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = attempt,
            parser = parser,
            onSuccess = {
                successCalled = true
                AppAuthManager.runPostAuthorizationSync(testContext) { true }
            },
            onError = { org.junit.Assert.fail("Should not fail with valid matching account") }
        )

        assertTrue("onSuccess must be invoked", successCalled)
        val unsynced = repo.getUnsyncedDocuments("user_alice")
        assertEquals(1, unsynced.size)
    }

    @Test
    fun testAppAuthManager_handleDrivePermissionResult_mismatchedAccount_doesNotTriggerSync() {
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

        val parser = FakeParser(
            result = GoogleSignInAccountData(
                id = "user_bob",
                email = "bob@gmail.com",
                displayName = "Bob",
                givenName = "Bob",
                familyName = "Test",
                photoUrl = null,
                idToken = null,
                grantedScopes = setOf(DriveAuthorizationResultRouter.DRIVE_FILE_SCOPE)
            )
        )

        val attempt = AppAuthManager.createDriveAuthorizationAttempt()
        var errorCalled = false
        AppAuthManager.handleDrivePermissionResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = attempt,
            parser = parser,
            onSuccess = { org.junit.Assert.fail("Must not succeed when account email is Bob but active user is Alice") },
            onError = { msg ->
                errorCalled = true
                assertTrue(msg.contains("không khớp"))
            }
        )

        assertTrue("onError must be invoked on mismatched account", errorCalled)
    }

    @Test
    fun testAppAuthManager_handleDrivePermissionResult_cancelled_quietAndNoSync() {
        val userAlice = UserProfile(
            id = "user_alice",
            email = "alice@gmail.com",
            displayName = "Alice",
            isVip = true,
            tier = VipTier.VIP
        )
        fakePrefs.edit().putString(
            "key_user_profile",
            """{"id":"user_alice","email":"alice@gmail.com","displayName":"Alice","isVip":true,"tier":"vip"}"""
        ).apply()
        AppAuthManager.init(testContext)

        val attempt = AppAuthManager.createDriveAuthorizationAttempt()
        var cancelCalled = false
        AppAuthManager.handleDrivePermissionResult(
            context = testContext,
            resultCode = Activity.RESULT_CANCELED,
            data = null,
            attempt = attempt,
            onSuccess = { org.junit.Assert.fail("Must not succeed when user cancelled") },
            onCancelled = { cancelCalled = true },
            onError = { org.junit.Assert.fail("Cancellation should not call onError") }
        )

        assertTrue("onCancelled must be called when user cancels", cancelCalled)
    }

    @Test
    fun testAppAuthManager_handleDrivePermissionResult_staleSession_doesNotTriggerSync() {
        val userAlice = UserProfile(
            id = "user_alice",
            email = "alice@gmail.com",
            displayName = "Alice",
            isVip = true,
            tier = VipTier.VIP
        )
        fakePrefs.edit().putString(
            "key_user_profile",
            """{"id":"user_alice","email":"alice@gmail.com","displayName":"Alice","isVip":true,"tier":"vip"}"""
        ).apply()
        AppAuthManager.init(testContext)

        // Set pending snapshot for generation 1
        AppAuthManager.setPendingDriveAuthSessionForTesting(
            AppAuthManager.DriveAuthSessionSnapshot("user_alice", "alice@gmail.com", 1L)
        )
        // Advance generation (simulating user sign-out/switch while consent was open)
        AppAuthManager.nextSessionGeneration()

        val parser = FakeParser(
            result = GoogleSignInAccountData(
                id = "user_alice",
                email = "alice@gmail.com",
                displayName = "Alice",
                givenName = "Alice",
                familyName = "Test",
                photoUrl = null,
                idToken = null,
                grantedScopes = setOf(DriveAuthorizationResultRouter.DRIVE_FILE_SCOPE)
            )
        )

        var successCalled = false
        AppAuthManager.handleDrivePermissionResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            parser = parser,
            onSuccess = { successCalled = true },
            onError = {}
        )

        assertFalse("Stale session result must be discarded and never invoke onSuccess", successCalled)
    }

    // -------------------------------------------------------------------------
    // Test Helpers
    // -------------------------------------------------------------------------

    private class FakeParser(
        private val result: GoogleSignInAccountData? = null,
        private val error: Exception? = null
    ) : GoogleSignInAccountParser {
        override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? {
            if (error != null) throw error
            return result
        }
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
