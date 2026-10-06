package com.tscanner.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.DriveAuthorizationAttempt
import com.tscanner.app.utils.GoogleCredentialClient
import com.tscanner.app.utils.GoogleLoginAttempt
import com.tscanner.app.utils.GoogleSignInAccountData
import com.tscanner.app.utils.GoogleSignInAccountParser
import com.tscanner.app.utils.SyncCatalogResult
import com.tscanner.app.utils.SyncResultPresenter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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

/**
 * Permanent regression test suite for VIP/Login Round 4 audit findings (U01 - U04).
 *
 * H00 Baseline Expectations:
 * - 4 regression probe tests call production AppAuthManager / SyncResultPresenter and reproduce U01-U04 (failing red).
 * - 3 control tests verify the test harness, same-session action, and sequential logout-login (passing green).
 * Total: 7 tests (4 failed, 3 passed).
 */
class VipLoginRound4RegressionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: TestContext
    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var testActivity: TestActivity

    @Before
    fun setUp() {
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
        fakePrefs = FakeSharedPreferences()
        testContext = TestContext(tempFolder.root, fakePrefs)
        testActivity = TestActivity(testContext)
        AppAuthManager.resetForTesting()
        DocumentRepo.resetInstanceForTesting()
        SyncResultPresenter.resetForTesting()
    }

    @After
    fun tearDown() {
        AppAuthManager.resetForTesting()
        DocumentRepo.resetInstanceForTesting()
        SyncResultPresenter.resetForTesting()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    private fun auditAccount() = GoogleSignInAccountData("u", "u@example.com", "User", null, null, null, null)

    private fun auditParser() = object : GoogleSignInAccountParser {
        override fun parseAccountFromIntent(data: Intent?) =
            auditAccount().copy(grantedScopes = setOf(AppAuthManager.DRIVE_FILE_SCOPE))
    }

    // =========================================================================
    // Control Tests (Verify test harness health and positive paths)
    // =========================================================================

    @Test
    fun controlValidDriveRequest_succeeds() {
        AppAuthManager.processSignedInAccount(testContext, auditAccount())
        val current = AppAuthManager.createDriveAuthorizationAttempt()!!
        var calls = 0
        AppAuthManager.handleDrivePermissionResult(
            testContext,
            Activity.RESULT_OK,
            Intent(),
            current,
            auditParser(),
            { calls++ },
            {},
            {}
        )
        assertEquals("Valid Drive request must succeed", 1, calls)
    }

    @Test
    fun controlSyncActionInSameSession_succeeds() {
        SyncResultPresenter.resetForTesting()
        var prompt: SyncResultPresenter.ActionPrompt? = null
        SyncResultPresenter.actionPresenter = { _, p -> prompt = p }
        var calls = 0
        val currentGen = AppAuthManager.getSessionGeneration()
        SyncResultPresenter.present(
            testContext,
            SyncCatalogResult.AuthRequired("grant"),
            onRequestDrivePermission = { calls++ },
            expectedSessionGeneration = currentGen
        )
        assertNotNull("Action prompt must be surfaced in same session", prompt)
        prompt!!.onAction!!.invoke()
        assertEquals("Action in same session must execute on tap", 1, calls)
        SyncResultPresenter.resetForTesting()
    }

    @Test
    fun controlNormalLogoutAndLogin_inSequentialOrder_succeeds() = runBlocking {
        AppAuthManager.processSignedInAccount(testContext, auditAccount().copy(id = "userA", email = "a@example.com"))
        assertEquals("userA", AppAuthManager.getCurrentUser()?.id)

        var logoutCompleted = false
        AppAuthManager.signOut(testContext, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined) {
            logoutCompleted = true
        }
        assertTrue("Logout must complete", logoutCompleted)
        assertNull("User must be cleared", AppAuthManager.getCurrentUser())

        // Now login user B
        AppAuthManager.processSignedInAccount(testContext, auditAccount().copy(id = "userB", email = "b@example.com"))
        assertEquals("userB", AppAuthManager.getCurrentUser()?.id)
    }

    // =========================================================================
    // 4 Baseline Probes for Round 4 (U01 - U04)
    // =========================================================================

    /**
     * U01 (P1): Cleanup provider đã bắt đầu vẫn chồng với login mới
     * While provider cleanup is suspended in-flight, a new user logs in and commits.
     * The in-flight provider cleanup completes after new user is committed.
     */
    @Test
    fun probeProviderCleanupMustNotOverlapNewLogin() = runBlocking {
        AppAuthManager.processSignedInAccount(testContext, auditAccount())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cleanupFinishedWithNewUser = false
        AppAuthManager.googleSignOutAction = {
            entered.complete(Unit)
            release.await()
            cleanupFinishedWithNewUser = AppAuthManager.getCurrentUser() != null
        }
        AppAuthManager.signOut(testContext, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined) {}
        entered.await()
        val client = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: androidx.credentials.GetCredentialRequest) =
                auditAccount().copy(id = "b", email = "b@example.com")
        }
        AppAuthManager.signInWithGoogle(testActivity, CoroutineScope(Dispatchers.Unconfined), client, Dispatchers.Unconfined, {}, {}, {}, {})
        release.complete(Unit)
        assertFalse("Destructive provider cleanup must finish before new login commits", cleanupFinishedWithNewUser)
        assertEquals("User B must be successfully committed after cleanup completes", "b", AppAuthManager.getCurrentUser()?.id)
    }

    @Test
    fun regressionProviderCleanupCancelledScope_doesNotDeadlockFutureLogin() {
        val deadJob = kotlinx.coroutines.Job().apply { cancel() }
        val deadScope = CoroutineScope(Dispatchers.Unconfined + deadJob)

        AppAuthManager.processSignedInAccount(testContext, auditAccount())
        AppAuthManager.signOut(testContext, deadScope, Dispatchers.Unconfined) {}

        // Verify next login is not deadlocked or blocked by dead scope
        val client = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: androidx.credentials.GetCredentialRequest) =
                auditAccount().copy(id = "user_after_dead_scope", email = "alive@example.com")
        }

        var loginSuccess = false
        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = client,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = { loginSuccess = true },
            onError = {}
        )

        assertTrue("Login after dead scope must succeed and not deadlock", loginSuccess)
        assertEquals("user_after_dead_scope", AppAuthManager.getCurrentUser()?.id)
    }

    @Test
    fun regressionProviderCleanupException_doesNotDeadlockFutureLogin() {
        AppAuthManager.processSignedInAccount(testContext, auditAccount())
        AppAuthManager.googleSignOutAction = {
            throw RuntimeException("Simulated provider crash")
        }

        AppAuthManager.signOut(testContext, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined) {}

        val client = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: androidx.credentials.GetCredentialRequest) =
                auditAccount().copy(id = "user_after_err", email = "err@example.com")
        }

        var loginSuccess = false
        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = client,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = { loginSuccess = true },
            onError = {}
        )

        assertTrue("Login after provider exception must succeed and not deadlock", loginSuccess)
        assertEquals("user_after_err", AppAuthManager.getCurrentUser()?.id)
    }

    @Test
    fun regressionDoubleSignOut_sequencesCleanly() = runBlocking {
        AppAuthManager.processSignedInAccount(testContext, auditAccount())
        AppAuthManager.signOut(testContext, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined) {}
        AppAuthManager.signOut(testContext, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined) {}

        val client = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: androidx.credentials.GetCredentialRequest) =
                auditAccount().copy(id = "user_after_double_logout", email = "double@example.com")
        }

        var loginSuccess = false
        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = client,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = { loginSuccess = true },
            onError = {}
        )

        assertTrue("Login after double logout must succeed", loginSuccess)
        assertEquals("user_after_double_logout", AppAuthManager.getCurrentUser()?.id)
    }

    @Test
    fun regressionProviderTaskStillPendingAfterTimeout_rejectsLoginWithUiFeedback() {
        com.tscanner.app.utils.LogoutCoordinator.resetForTesting()
        com.tscanner.app.utils.LogoutCoordinator.cleanupTimeoutMs = 50L
        // Simulate a provider Task that is permanently pending
        com.tscanner.app.utils.LogoutCoordinator.startLogout()
        com.tscanner.app.utils.LogoutCoordinator.markProviderTaskStarted()

        val errorLatch = java.util.concurrent.CountDownLatch(1)
        var reportedError: String? = null
        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.IO),
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = {},
            onError = {
                reportedError = it
                errorLatch.countDown()
            }
        )

        assertTrue("Login must complete with error callback within timeout", errorLatch.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertNotNull("Login must be rejected with UI feedback while provider task is still pending", reportedError)
        assertTrue("Error message must indicate logout in progress", reportedError!!.contains("đăng xuất"))
        assertFalse("Sign in progress must be released", AppAuthManager.isSignInInProgress())
        assertNull("User must not be logged in", AppAuthManager.getCurrentUser())

        com.tscanner.app.utils.LogoutCoordinator.resetForTesting()
    }

    /**
     * U02 (P2): Drive callback thiếu token vẫn mượn pending request của host khác
     * Result with attempt=null falls back to pendingDriveAuthAttempt.getAndSet(null) and consumes active request.
     */
    @Test
    fun probeDriveNullHostTokenDoesNotBorrowOtherRequest() {
        AppAuthManager.processSignedInAccount(testContext, auditAccount())
        val current = AppAuthManager.createDriveAuthorizationAttempt()!!
        var calls = 0
        AppAuthManager.handleDrivePermissionResult(testContext, Activity.RESULT_OK, Intent(), null, auditParser(), { calls++ }, {}, {})
        assertEquals("Missing host token must not consume current request", 0, calls)
        // Legitimate result with matching token succeeds afterwards
        AppAuthManager.handleDrivePermissionResult(testContext, Activity.RESULT_OK, Intent(), current, auditParser(), { calls++ }, {}, {})
        assertEquals("Subsequent legitimate result must succeed", 1, calls)
    }

    @Test
    fun regressionDrive_duplicateCallbackAfterHostClearedToken_doesNotConsumeNewRequest() {
        AppAuthManager.processSignedInAccount(testContext, auditAccount())
        val requestA = AppAuthManager.createDriveAuthorizationAttempt()!!
        var callsA = 0
        // Host A receives real result and clears token
        AppAuthManager.handleDrivePermissionResult(testContext, Activity.RESULT_OK, Intent(), requestA, auditParser(), { callsA++ }, {}, {})
        assertEquals(1, callsA)

        // Host B creates fresh request
        val requestB = AppAuthManager.createDriveAuthorizationAttempt()!!
        var callsB = 0

        // Stale duplicate callback from host A arrives with null token (because host A cleared its token)
        AppAuthManager.handleDrivePermissionResult(testContext, Activity.RESULT_OK, Intent(), null, auditParser(), { callsB++ }, {}, {})
        assertEquals("Duplicate callback with null token must not consume request B", 0, callsB)

        // Host B real result arrives
        AppAuthManager.handleDrivePermissionResult(testContext, Activity.RESULT_OK, Intent(), requestB, auditParser(), { callsB++ }, {}, {})
        assertEquals("Request B must succeed when explicit token is provided", 1, callsB)
    }

    @Test
    fun regressionDrive_cancelLaunch_clearsOnlyOwnerRequest() {
        AppAuthManager.processSignedInAccount(testContext, auditAccount())
        val requestA = AppAuthManager.createDriveAuthorizationAttempt()!!
        val requestB = AppAuthManager.createDriveAuthorizationAttempt()!!

        // Host A cancels launch (e.g. exception during Activity launch)
        AppAuthManager.cancelDriveAuthorizationAttempt(requestA)

        var callsB = 0
        // Host B receives real result and succeeds
        AppAuthManager.handleDrivePermissionResult(testContext, Activity.RESULT_OK, Intent(), requestB, auditParser(), { callsB++ }, {}, {})
        assertEquals("Cancelling request A must not invalidate request B", 1, callsB)

        // Late result for cancelled request A is discarded
        var callsA = 0
        AppAuthManager.handleDrivePermissionResult(testContext, Activity.RESULT_OK, Intent(), requestA, auditParser(), { callsA++ }, {}, {})
        assertEquals("Cancelled request A must not be consumed", 0, callsA)
    }

    /**
     * U03 (P2): Counter reset làm token cũ trùng request mới
     * When state/counters reset, an old token with identical (requestId, sessionGen) is accepted by new process.
     */
    @Test
    fun probeOldDriveTokenDoesNotMatchNewProcessCounters() {
        AppAuthManager.processSignedInAccount(testContext, auditAccount())
        val old = AppAuthManager.createDriveAuthorizationAttempt()!!
        AppAuthManager.resetForTesting()
        AppAuthManager.processSignedInAccount(testContext, auditAccount())
        val fresh = AppAuthManager.createDriveAuthorizationAttempt()!!
        var calls = 0
        AppAuthManager.handleDrivePermissionResult(testContext, Activity.RESULT_OK, Intent(), old, auditParser(), { calls++ }, {}, {})
        assertEquals("Old process token must not match a newly registered request", 0, calls)
        // Fresh token with matching epoch succeeds
        AppAuthManager.handleDrivePermissionResult(testContext, Activity.RESULT_OK, Intent(), fresh, auditParser(), { calls++ }, {}, {})
        assertEquals("Fresh process token must succeed", 1, calls)
    }

    @Test
    fun regressionOldTokenWithSameCounters_doesNotCancelNewRequest() {
        AppAuthManager.processSignedInAccount(testContext, auditAccount())
        val old = AppAuthManager.createDriveAuthorizationAttempt()!!

        // Process reset
        AppAuthManager.resetForTesting()
        AppAuthManager.processSignedInAccount(testContext, auditAccount())
        val fresh = AppAuthManager.createDriveAuthorizationAttempt()!!
        assertEquals(old.requestId, fresh.requestId)

        // Attempting to cancel using the old token must not cancel the fresh request in the new process
        AppAuthManager.cancelDriveAuthorizationAttempt(old)

        var calls = 0
        AppAuthManager.handleDrivePermissionResult(testContext, Activity.RESULT_OK, Intent(), fresh, auditParser(), { calls++ }, {}, {})
        assertEquals("Fresh request must not be cancelled by stale process token", 1, calls)
    }

    @Test
    fun regressionSameProcessTokenSurvivesSerializationRoundTrip_andMatchesEpoch() {
        AppAuthManager.processSignedInAccount(testContext, auditAccount())
        val original = AppAuthManager.createDriveAuthorizationAttempt()!!

        val baos = java.io.ByteArrayOutputStream()
        java.io.ObjectOutputStream(baos).use { it.writeObject(original) }
        val restored = java.io.ObjectInputStream(java.io.ByteArrayInputStream(baos.toByteArray())).use {
            it.readObject() as DriveAuthorizationAttempt
        }

        assertEquals("Restored token must preserve processEpoch", original.processEpoch, restored.processEpoch)
        assertEquals(AppAuthManager.getProcessEpoch(), restored.processEpoch)

        var calls = 0
        AppAuthManager.handleDrivePermissionResult(testContext, Activity.RESULT_OK, Intent(), restored, auditParser(), { calls++ }, {}, {})
        assertEquals("Restored token within same process must succeed", 1, calls)
    }

    @Test
    fun regressionLegacyTokenMissingEpoch_isSafelyDiscarded() {
        AppAuthManager.processSignedInAccount(testContext, auditAccount())
        val current = AppAuthManager.createDriveAuthorizationAttempt()!!

        // Fabricate a legacy token without processEpoch (empty string)
        val legacyToken = DriveAuthorizationAttempt(
            requestId = current.requestId,
            userId = current.userId,
            userEmail = current.userEmail,
            sessionGeneration = current.sessionGeneration,
            processEpoch = ""
        )

        var calls = 0
        AppAuthManager.handleDrivePermissionResult(testContext, Activity.RESULT_OK, Intent(), legacyToken, auditParser(), { calls++ }, {}, {})
        assertEquals("Legacy token missing epoch must be safely discarded", 0, calls)
    }

    /**
     * U04 (P2): Snackbar của phiên trước còn thực thi khi người dùng bấm
     * Action rendered for session A is tapped after session changes to B; action callback still executes.
     */
    @Test
    fun probeSyncActionMustRecheckSessionAtClick() {
        SyncResultPresenter.resetForTesting()
        var prompt: SyncResultPresenter.ActionPrompt? = null
        SyncResultPresenter.actionPresenter = { _, p -> prompt = p }
        var calls = 0
        SyncResultPresenter.present(
            testContext,
            SyncCatalogResult.AuthRequired("grant"),
            onRequestDrivePermission = { calls++ },
            expectedSessionGeneration = AppAuthManager.getSessionGeneration()
        )
        AppAuthManager.nextSessionGeneration()
        prompt!!.onAction!!.invoke()
        SyncResultPresenter.resetForTesting()
        assertEquals("Action shown for previous session must be rejected on tap", 0, calls)
    }

    // =========================================================================
    // Test Harness Fixtures
    // =========================================================================

    private class TestActivity(private val baseContext: Context) : Activity() {
        var mockIsFinishing: Boolean = false
        var mockIsDestroyed: Boolean = false
        var throwOnPrefs: Boolean = false

        override fun isFinishing(): Boolean = mockIsFinishing
        override fun isDestroyed(): Boolean = mockIsDestroyed
        override fun getApplicationContext(): Context = baseContext
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            if (throwOnPrefs) {
                throw IllegalStateException("Simulated storage failure accessing preferences in Activity")
            }
            return baseContext.getSharedPreferences(name, mode)
        }
        override fun getFilesDir(): File = baseContext.filesDir
        override fun getCacheDir(): File = baseContext.cacheDir
    }

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
