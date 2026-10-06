package com.tscanner.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.DriveAuthorizationAttempt
import com.tscanner.app.utils.GoogleLoginAttempt
import com.tscanner.app.utils.GoogleSignInAccountData
import com.tscanner.app.utils.GoogleSignInAccountParser
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.ArrayDeque

/**
 * Permanent regression test suite for VIP/Login Round 3 audit findings (T01 - T04).
 *
 * G00 Baseline Expectations:
 * - 4 regression probe tests call production AppAuthManager and reproduce T01-T04 (failing red).
 * - Control tests verify valid request token handling and normal logout (passing green).
 */
class VipLoginRound3RegressionTest {

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
    }

    @After
    fun tearDown() {
        AppAuthManager.resetForTesting()
        DocumentRepo.resetInstanceForTesting()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    // =========================================================================
    // Control Tests (Verify test harness and positive paths)
    // =========================================================================

    @Test
    fun controlValidGoogleSignInRequestToken_succeeds() {
        val attempt = AppAuthManager.createSignInAttemptForTesting()
        val parser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? =
                GoogleSignInAccountData("valid_user", "valid@example.com", "Valid User", null, null, null, null)
        }
        var successProfile: UserProfile? = null
        AppAuthManager.handleGoogleSignInResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = attempt,
            parser = parser,
            onSuccess = { successProfile = it },
            onCancelled = {},
            onError = {}
        )
        assertNotNull("Valid token result must call onSuccess", successProfile)
        assertEquals("valid_user", successProfile?.id)
        assertEquals("valid_user", AppAuthManager.getCurrentUser()?.id)
        assertFalse("isSignInInProgress must be reset", AppAuthManager.isSignInInProgress())
    }

    @Test
    fun controlValidDriveRequestToken_succeeds() {
        AppAuthManager.processSignedInAccount(
            testContext,
            GoogleSignInAccountData("drive_user", "drive@example.com", "Drive User", null, null, null, null)
        )
        val attempt = AppAuthManager.createDriveAuthorizationAttemptForTesting()!!
        val parser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? {
                return GoogleSignInAccountData(
                    "drive_user",
                    "drive@example.com",
                    "Drive User",
                    null,
                    null,
                    null,
                    null,
                    setOf(AppAuthManager.DRIVE_FILE_SCOPE)
                )
            }
        }
        var successes = 0
        AppAuthManager.handleDrivePermissionResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = attempt,
            parser = parser,
            onSuccess = { successes++ },
            onCancelled = {},
            onError = {}
        )
        assertEquals("Valid Drive authorization with token must succeed", 1, successes)
    }

    @Test
    fun controlNormalSignOut_clearsCurrentUserAndPreferences() = runBlocking {
        AppAuthManager.processSignedInAccount(
            testContext,
            GoogleSignInAccountData("user_to_logout", "out@example.com", "Logout User", null, null, null, null)
        )
        assertEquals("user_to_logout", AppAuthManager.getCurrentUser()?.id)

        var signOutCompleted = false
        AppAuthManager.signOut(
            context = testContext,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            mainDispatcher = Dispatchers.Unconfined
        ) {
            signOutCompleted = true
        }

        assertTrue("signOut completion callback must be called", signOutCompleted)
        assertNull("Current user must be cleared after normal sign out", AppAuthManager.getCurrentUser())
        assertNull("Saved profile in prefs must be cleared", fakePrefs.getString("key_user_profile", null))
        assertFalse("Sign in progress must be false", AppAuthManager.isSignInInProgress())
    }

    // =========================================================================
    // 4 Regression Probes for Round 3 (T01 - T04)
    // =========================================================================

    /**
     * T01: Token tái tạo hợp lệ nhưng không giải phóng khóa đăng nhập
     * Host recreated GoogleLoginAttempt has identical requestId & initialSessionGeneration,
     * but CAS identity check fails and leaves isSignInInProgress == true.
     */
    @Test
    fun probeRecreatedLoginCancelReleasesAttempt() {
        val original = AppAuthManager.createSignInAttemptForTesting()
        val restored = GoogleLoginAttempt(original.requestId, original.initialSessionGeneration, original.processEpoch)
        AppAuthManager.handleGoogleSignInResult(
            context = testContext,
            resultCode = Activity.RESULT_CANCELED,
            data = null,
            attempt = restored,
            onSuccess = {},
            onCancelled = {},
            onError = {}
        )
        assertFalse("Recreated host cancellation must release active attempt", AppAuthManager.isSignInInProgress())
        assertNull("Active login attempt reference must be cleared", AppAuthManager.getActiveSignInAttempt())
    }

    @Test
    fun regressionRecreatedLoginSuccess_commitsProfileAndReleasesActiveReference() {
        val original = AppAuthManager.createSignInAttemptForTesting()
        val restored = GoogleLoginAttempt(original.requestId, original.initialSessionGeneration, original.processEpoch)
        val parser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? =
                GoogleSignInAccountData("recreated_user", "recreated@example.com", "Recreated User", null, null, null, null)
        }

        var successProfile: UserProfile? = null
        AppAuthManager.handleGoogleSignInResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = restored,
            parser = parser,
            onSuccess = { successProfile = it },
            onCancelled = {},
            onError = {}
        )

        assertNotNull("Success must be invoked with recreated token", successProfile)
        assertEquals("recreated_user", successProfile?.id)
        assertEquals("recreated_user", AppAuthManager.getCurrentUser()?.id)
        assertNotNull("Persistent profile in preferences must be saved", fakePrefs.getString("key_user_profile", null))
        assertNull("Active login attempt reference must be cleared after success", AppAuthManager.getActiveSignInAttempt())
        assertFalse("isSignInInProgress must be reset after success", AppAuthManager.isSignInInProgress())

        // Subsequent sign-in must be accepted
        val nextAttempt = AppAuthManager.createSignInAttemptForTesting()
        assertNotNull("Subsequent sign in attempt must be allowed", nextAttempt)
        assertTrue(AppAuthManager.isSignInInProgress())
    }

    @Test
    fun regressionRecreatedLoginError_releasesActiveReferenceAndAllowsNextSignIn() {
        val original = AppAuthManager.createSignInAttemptForTesting()
        val restored = GoogleLoginAttempt(original.requestId, original.initialSessionGeneration, original.processEpoch)
        val parser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? = null
        }

        var errorReported: String? = null
        AppAuthManager.handleGoogleSignInResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = restored,
            parser = parser,
            onSuccess = {},
            onCancelled = {},
            onError = { errorReported = it }
        )

        assertNotNull("Error callback must be invoked", errorReported)
        assertNull("Current user must remain null", AppAuthManager.getCurrentUser())
        assertNull("Active login attempt reference must be cleared after error", AppAuthManager.getActiveSignInAttempt())
        assertFalse("isSignInInProgress must be reset after error", AppAuthManager.isSignInInProgress())

        // Subsequent sign-in must be accepted
        val nextAttempt = AppAuthManager.createSignInAttemptForTesting()
        assertNotNull("Subsequent sign in attempt must be allowed after error", nextAttempt)
        assertTrue(AppAuthManager.isSignInInProgress())
    }

    @Test
    fun regressionStaleRecreatedAttemptA_whileAttemptBInProgress_doesNotReleaseOrOverwriteB() {
        val attemptA = AppAuthManager.createSignInAttemptForTesting()
        AppAuthManager.cancelSignInProgress()

        val attemptB = AppAuthManager.createSignInAttemptForTesting()
        assertTrue("Attempt B must have higher requestId than Attempt A", attemptB.requestId > attemptA.requestId)

        val staleRestoredA = GoogleLoginAttempt(attemptA.requestId, attemptA.initialSessionGeneration, attemptA.processEpoch)

        // Cancel with stale restored A must not release B
        AppAuthManager.handleGoogleSignInResult(
            context = testContext,
            resultCode = Activity.RESULT_CANCELED,
            data = null,
            attempt = staleRestoredA,
            onSuccess = {},
            onCancelled = {},
            onError = {}
        )

        assertTrue("Attempt B must STILL be in progress after stale A cancel", AppAuthManager.isSignInInProgress())
        assertEquals("Active attempt must remain attempt B", attemptB, AppAuthManager.getActiveSignInAttempt())

        // Success with stale restored A must not commit user A or clear B
        val parserA = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? =
                GoogleSignInAccountData("stale_a", "a@example.com", "Stale A", null, null, null, null)
        }

        AppAuthManager.handleGoogleSignInResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = staleRestoredA,
            parser = parserA,
            onSuccess = {},
            onCancelled = {},
            onError = {}
        )

        assertNull("Stale A must not be logged in", AppAuthManager.getCurrentUser())
        assertTrue("Attempt B must STILL be in progress after stale A success attempt", AppAuthManager.isSignInInProgress())
        assertEquals("Active attempt must remain attempt B", attemptB, AppAuthManager.getActiveSignInAttempt())
    }

    /**
     * T02: Kết quả thiếu token của host mượn attempt hiện hành
     * Result callback without attempt token falls back to activeLoginAttempt.get(),
     * erroneously committing an uncorrelated account to an active pending attempt.
     */
    @Test
    fun probeMissingHostTokenMustNotBorrowActiveAttempt() {
        val activeAttempt = AppAuthManager.createSignInAttemptForTesting()
        val parser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?) =
                GoogleSignInAccountData("old", "old@example.com", "Old", null, null, null, null)
        }
        AppAuthManager.handleGoogleSignInResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = null,
            parser = parser,
            onSuccess = {},
            onCancelled = {},
            onError = {}
        )
        assertNull("Uncorrelated host result must not borrow another attempt", AppAuthManager.getCurrentUser())
        assertTrue("Active attempt must remain untouched", AppAuthManager.isSignInInProgress())
        assertEquals("Active attempt must remain unchanged", activeAttempt, AppAuthManager.getActiveSignInAttempt())
    }

    @Test
    fun regressionOldDuplicateCallbackWithoutToken_doesNotCancelOrCommitAttemptB() {
        val attemptB = AppAuthManager.createSignInAttemptForTesting()

        // 1. Cancel callback without token must not cancel attempt B
        AppAuthManager.handleGoogleSignInResult(
            context = testContext,
            resultCode = Activity.RESULT_CANCELED,
            data = null,
            attempt = null,
            onSuccess = {},
            onCancelled = {},
            onError = {}
        )
        assertTrue("Attempt B must not be cancelled by tokenless cancel callback", AppAuthManager.isSignInInProgress())
        assertEquals(attemptB, AppAuthManager.getActiveSignInAttempt())

        // 2. Success callback without token must not commit user or clear attempt B
        val parser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?) =
                GoogleSignInAccountData("stale_old", "stale@example.com", "Stale", null, null, null, null)
        }
        AppAuthManager.handleGoogleSignInResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = null,
            parser = parser,
            onSuccess = {},
            onCancelled = {},
            onError = {}
        )
        assertNull("Stale user must not be committed", AppAuthManager.getCurrentUser())
        assertTrue("Attempt B must remain in progress", AppAuthManager.isSignInInProgress())
        assertEquals(attemptB, AppAuthManager.getActiveSignInAttempt())
    }

    @Test
    fun regressionNullToken_doesNotInvokeParserOrProduceSideEffects() {
        AppAuthManager.createSignInAttemptForTesting()
        var parserCalled = false
        val bombParser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? {
                parserCalled = true
                throw IllegalStateException("Parser should not be called when token is null!")
            }
        }

        AppAuthManager.handleGoogleSignInResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = null,
            parser = bombParser,
            onSuccess = {},
            onCancelled = {},
            onError = {}
        )

        assertFalse("Parser must NOT be called when token is null", parserCalled)
        assertNull("User must not be logged in", AppAuthManager.getCurrentUser())
    }

    @Test
    fun regressionSavedStateLostToken_allowsSafeRetry() {
        // Saved state lost token -> attempt is null
        var errorCount = 0
        var successCount = 0
        AppAuthManager.handleGoogleSignInResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = null,
            onSuccess = { successCount++ },
            onCancelled = {},
            onError = { errorCount++ }
        )

        assertEquals("No success for null token", 0, successCount)
        assertNull(AppAuthManager.getCurrentUser())

        // Safe retry is possible
        val retryAttempt = AppAuthManager.createSignInAttemptForTesting()
        assertNotNull("Retry must be allowed", retryAttempt)
        assertTrue(AppAuthManager.isSignInInProgress())
    }

    /**
     * T03: Drive single-consume chỉ nằm trong object, không theo request ID
     * Deserializing a DriveAuthorizationAttempt allows consuming it a second time
     * because single-consume state is transient in memory, allowing replay.
     */
    @Test
    fun probeCopiedDriveAttemptCannotBeConsumedTwice() {
        AppAuthManager.processSignedInAccount(
            testContext,
            GoogleSignInAccountData("u", "u@example.com", "User", null, null, null, null)
        )
        val original = AppAuthManager.createDriveAuthorizationAttemptForTesting()!!
        val bytes = ByteArrayOutputStream().also { stream ->
            ObjectOutputStream(stream).use { it.writeObject(original) }
        }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use {
            it.readObject() as DriveAuthorizationAttempt
        }
        val parser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?) =
                GoogleSignInAccountData("u", "u@example.com", "User", null, null, null, null, setOf(AppAuthManager.DRIVE_FILE_SCOPE))
        }
        var count = 0
        for (token in listOf(original, restored)) {
            AppAuthManager.handleDrivePermissionResult(
                context = testContext,
                resultCode = Activity.RESULT_OK,
                data = Intent(),
                attempt = token,
                parser = parser,
                onSuccess = { count++ },
                onCancelled = {},
                onError = {}
            )
        }
        assertEquals("Single-consume must bind request identity across host reconstruction", 1, count)
    }

    @Test
    fun regressionDrive_serializeAfterConsume_rejected() {
        AppAuthManager.processSignedInAccount(
            testContext,
            GoogleSignInAccountData("u2", "u2@example.com", "User 2", null, null, null, null)
        )
        val attempt = AppAuthManager.createDriveAuthorizationAttemptForTesting()!!
        val parser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?) =
                GoogleSignInAccountData("u2", "u2@example.com", "User 2", null, null, null, null, setOf(AppAuthManager.DRIVE_FILE_SCOPE))
        }

        var successes = 0
        // First consume
        AppAuthManager.handleDrivePermissionResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = attempt,
            parser = parser,
            onSuccess = { successes++ },
            onCancelled = {},
            onError = {}
        )
        assertEquals("First delivery must succeed", 1, successes)

        // Serialize after consume
        val bytes = ByteArrayOutputStream().also { stream ->
            ObjectOutputStream(stream).use { it.writeObject(attempt) }
        }.toByteArray()
        val restoredAfterConsume = ObjectInputStream(ByteArrayInputStream(bytes)).use {
            it.readObject() as DriveAuthorizationAttempt
        }

        // Deliver deserialized token
        AppAuthManager.handleDrivePermissionResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = restoredAfterConsume,
            parser = parser,
            onSuccess = { successes++ },
            onCancelled = {},
            onError = {}
        )
        assertEquals("Token serialized after consume must still be rejected on replay", 1, successes)
    }

    @Test
    fun regressionDrive_twoHosts_consumeIndependentlyWithoutCollision() {
        AppAuthManager.processSignedInAccount(
            testContext,
            GoogleSignInAccountData("multi_host", "multi@example.com", "Multi User", null, null, null, null)
        )
        val attemptHost1 = AppAuthManager.createDriveAuthorizationAttemptForTesting()!!
        val attemptHost2 = AppAuthManager.createDriveAuthorizationAttemptForTesting()!!
        assertTrue("Request IDs must be unique across hosts", attemptHost2.requestId > attemptHost1.requestId)

        val parser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?) =
                GoogleSignInAccountData("multi_host", "multi@example.com", "Multi User", null, null, null, null, setOf(AppAuthManager.DRIVE_FILE_SCOPE))
        }

        var host1Success = false
        var host2Success = false

        AppAuthManager.handleDrivePermissionResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = attemptHost1,
            parser = parser,
            onSuccess = { host1Success = true },
            onCancelled = {},
            onError = {}
        )
        AppAuthManager.handleDrivePermissionResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = attemptHost2,
            parser = parser,
            onSuccess = { host2Success = true },
            onCancelled = {},
            onError = {}
        )

        assertTrue("Host 1 must succeed independently", host1Success)
        assertTrue("Host 2 must succeed independently", host2Success)
    }

    @Test
    fun regressionDrive_sameRequestIdDifferentGeneration_rejected() {
        AppAuthManager.processSignedInAccount(
            testContext,
            GoogleSignInAccountData("gen_user", "gen@example.com", "Gen User", null, null, null, null)
        )
        val attemptGen1 = AppAuthManager.createDriveAuthorizationAttemptForTesting()!!

        // Advance session generation (e.g. user session invalidation)
        AppAuthManager.nextSessionGeneration()

        val parser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?) =
                GoogleSignInAccountData("gen_user", "gen@example.com", "Gen User", null, null, null, null, setOf(AppAuthManager.DRIVE_FILE_SCOPE))
        }

        var successes = 0
        AppAuthManager.handleDrivePermissionResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = attemptGen1,
            parser = parser,
            onSuccess = { successes++ },
            onCancelled = {},
            onError = {}
        )

        assertEquals("Token from obsolete session generation must be rejected", 0, successes)
    }

    @Test
    fun regressionDrive_processResetPolicy_rejectsUnregisteredAttempt() {
        AppAuthManager.processSignedInAccount(
            testContext,
            GoogleSignInAccountData("reset_user", "reset@example.com", "Reset User", null, null, null, null)
        )

        // Fabricate an unregistered attempt (simulates token arriving from before process kill without authoritative registry entry)
        val staleTokenFromDeadProcess = DriveAuthorizationAttempt(
            requestId = 9999L,
            userId = "reset_user",
            userEmail = "reset@example.com",
            sessionGeneration = AppAuthManager.getSessionGeneration()
        )

        val parser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?) =
                GoogleSignInAccountData("reset_user", "reset@example.com", "Reset User", null, null, null, null, setOf(AppAuthManager.DRIVE_FILE_SCOPE))
        }

        var successes = 0
        AppAuthManager.handleDrivePermissionResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = staleTokenFromDeadProcess,
            parser = parser,
            onSuccess = { successes++ },
            onCancelled = {},
            onError = {}
        )

        assertEquals("Unregistered attempt from process reset must be discarded", 0, successes)

        // Safe retry: host creates fresh attempt and succeeds
        val freshAttempt = AppAuthManager.createDriveAuthorizationAttemptForTesting()!!
        AppAuthManager.handleDrivePermissionResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = freshAttempt,
            parser = parser,
            onSuccess = { successes++ },
            onCancelled = {},
            onError = {}
        )
        assertEquals("Fresh attempt after process reset must succeed on retry", 1, successes)
    }

    /**
     * T04: Logout cũ xóa login mới
     * A slow sign-out coroutine from user A runs after user B signs in,
     * and sign-out's finally block unconditionally wipes currentUser and preferences.
     */
    @Test
    fun probeLateSignOutMustPreserveNewLogin() {
        val queued = ArrayDeque<Runnable>()
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                queued.add(block)
            }
        }
        AppAuthManager.processSignedInAccount(
            testContext,
            GoogleSignInAccountData("a", "a@example.com", "A", null, null, null, null)
        )
        AppAuthManager.signOut(
            testContext,
            CoroutineScope(dispatcher),
            Dispatchers.Unconfined
        ) {}
        AppAuthManager.processSignedInAccount(
            testContext,
            GoogleSignInAccountData("b", "b@example.com", "B", null, null, null, null)
        )
        while (queued.isNotEmpty()) queued.removeFirst().run()
        assertEquals("Old logout completion must not erase the new session", "b", AppAuthManager.getCurrentUser()?.id)
        val savedJson = fakePrefs.getString("key_user_profile", null)
        assertNotNull("User B profile in preferences must be preserved", savedJson)
        assertTrue("Saved profile must belong to user B", savedJson!!.contains("b@example.com"))
    }

    @Test
    fun regressionLateSignOut_doesNotInvokeProviderSignOutForNewSession() {
        var providerSignOutCount = 0
        AppAuthManager.googleSignOutAction = {
            providerSignOutCount++
        }

        val queued = ArrayDeque<Runnable>()
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                queued.add(block)
            }
        }
        AppAuthManager.processSignedInAccount(
            testContext,
            GoogleSignInAccountData("a", "a@example.com", "A", null, null, null, null)
        )
        AppAuthManager.signOut(
            testContext,
            CoroutineScope(dispatcher),
            Dispatchers.Unconfined
        ) {}
        // User B logs in before A's provider cleanup runs
        AppAuthManager.processSignedInAccount(
            testContext,
            GoogleSignInAccountData("b", "b@example.com", "B", null, null, null, null)
        )
        while (queued.isNotEmpty()) queued.removeFirst().run()

        assertEquals("Provider sign out must NOT be invoked when newer session B is active", 0, providerSignOutCount)
        assertEquals("User B must remain current user", "b", AppAuthManager.getCurrentUser()?.id)
    }

    @Test
    fun regressionSignOutCalledTwice_doesNotHoldLockAndClearsState() = runBlocking {
        AppAuthManager.processSignedInAccount(
            testContext,
            GoogleSignInAccountData("user1", "user1@example.com", "User 1", null, null, null, null)
        )
        var callback1Called = false
        var callback2Called = false

        AppAuthManager.signOut(testContext, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined) {
            callback1Called = true
        }
        AppAuthManager.signOut(testContext, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined) {
            callback2Called = true
        }

        assertTrue("First sign out callback must be called", callback1Called)
        assertTrue("Second sign out callback must be called", callback2Called)
        assertNull("Current user must be null", AppAuthManager.getCurrentUser())
        assertFalse("Sign in progress must be false", AppAuthManager.isSignInInProgress())
    }

    @Test
    fun regressionSignOutProviderError_stillInvokesCompletionCallback() = runBlocking {
        AppAuthManager.processSignedInAccount(
            testContext,
            GoogleSignInAccountData("err_user", "err@example.com", "Err User", null, null, null, null)
        )
        AppAuthManager.googleSignOutAction = {
            throw RuntimeException("Simulated provider outage during sign out")
        }

        var completionCalled = false
        AppAuthManager.signOut(testContext, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined) {
            completionCalled = true
        }

        assertTrue("Completion callback must be invoked even on provider failure", completionCalled)
        assertNull("Current user must still be cleared locally", AppAuthManager.getCurrentUser())
        assertNull("Saved profile must be cleared", fakePrefs.getString("key_user_profile", null))
        assertFalse("Lock must be released", AppAuthManager.isSignInInProgress())
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
