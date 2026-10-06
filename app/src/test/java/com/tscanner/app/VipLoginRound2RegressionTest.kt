package com.tscanner.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.DriveAuthorizationAttempt
import com.tscanner.app.utils.GoogleCredentialClient
import com.tscanner.app.utils.GoogleLoginAttempt
import com.tscanner.app.utils.GoogleSignInAccountData
import com.tscanner.app.utils.GoogleSignInAccountParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
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

/**
 * Baseline regression test suite for VIP/Login Round 2 audit findings (R01 - R04).
 *
 * S00 Baseline Expectations:
 * - 4 regression probe tests call production AppAuthManager and reproduce R01-R04 (failing red).
 * - 2 control tests verify the test harness and positive production paths (passing green).
 * Total: 6 tests (4 failed, 2 passed).
 */
class VipLoginRound2RegressionTest {

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
    // Control Tests (Positive controls to prove harness health)
    // =========================================================================

    @Test
    fun controlValidSignIn_succeedsAndUpdatesUser() = runBlocking {
        val expectedAccount = GoogleSignInAccountData(
            id = "control_user_1",
            email = "control@gmail.com",
            displayName = "Control User",
            givenName = "Control",
            familyName = "User",
            photoUrl = null,
            idToken = null
        )

        val fakeClient = object : GoogleCredentialClient {
            override suspend fun getCredential(
                activity: Activity,
                request: GetCredentialRequest
            ): GoogleSignInAccountData? = expectedAccount
        }

        var successProfile: UserProfile? = null
        var fallbackCalled = false

        val started = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = fakeClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = { fallbackCalled = true },
            onSuccess = { successProfile = it },
            onCancelled = {},
            onError = {}
        )

        assertTrue("signInWithGoogle should start successfully", started)
        assertNotNull("onSuccess must be called", successProfile)
        assertEquals("control_user_1", successProfile?.id)
        assertFalse("Fallback must not be called on valid sign in", fallbackCalled)
        assertEquals("CurrentUser must be updated", "control_user_1", AppAuthManager.currentUser.value?.id)
        assertFalse("isSignInInProgress must be reset to false", AppAuthManager.isSignInInProgress())
    }

    @Test
    fun controlValidDriveAuthorization_withPendingRequest_succeeds() {
        AppAuthManager.processSignedInAccount(
            testContext,
            GoogleSignInAccountData("control_drive_user", "drive_ctrl@example.com", "Drive Control", null, null, null, null)
        )

        // Properly register pending authorization request
        AppAuthManager.setPendingDriveAuthSessionForTesting(
            AppAuthManager.DriveAuthSessionSnapshot(
                "control_drive_user",
                "drive_ctrl@example.com",
                AppAuthManager.getSessionGeneration()
            )
        )

        val parser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? {
                return GoogleSignInAccountData(
                    "control_drive_user",
                    "drive_ctrl@example.com",
                    "Drive Control",
                    null,
                    null,
                    null,
                    null,
                    setOf(AppAuthManager.DRIVE_FILE_SCOPE)
                )
            }
        }

        val attempt = AppAuthManager.getPendingDriveAuthAttemptForTesting()
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

        assertEquals("Valid Drive authorization with pending request must succeed", 1, successes)
    }

    // =========================================================================
    // 4 Baseline Probes (R01 - R04)
    // =========================================================================

    /**
     * R02: Scope đã hủy làm khóa đăng nhập toàn cục.
     * AppAuthManager.signInWithGoogle sets isSignInInProgress before launch; reset is inside coroutine body.
     * When scope is already cancelled, body never executes, leaving global lock locked forever.
     */
    @Test
    fun probeCancelledScopeMustNotLockFutureSignIn() {
        val job = Job()
        job.cancel()
        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined + job),
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = {},
            onError = {}
        )
        assertFalse("Cancelled scope must not leave global login locked", AppAuthManager.isSignInInProgress())
    }

    /**
     * R01: Vô hiệu hóa phiên không vô hiệu hóa kết quả Credential Manager.
     * When session is invalidated and progress cancelled while a credential request is in-flight,
     * delayed credential completion still commits user and restores session.
     */
    @Test
    fun probeSessionInvalidationMustRejectPendingCredential() = runBlocking {
        val result = CompletableDeferred<GoogleSignInAccountData?>()
        val client = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: GetCredentialRequest): GoogleSignInAccountData? = result.await()
        }
        AppAuthManager.signInWithGoogle(testActivity, CoroutineScope(Dispatchers.Unconfined), client, Dispatchers.Unconfined, {}, {}, {}, {})
        AppAuthManager.nextSessionGeneration()
        AppAuthManager.cancelSignInProgress()
        result.complete(GoogleSignInAccountData("stale", "stale@example.com", "Old", null, null, null, null))
        assertNull("Invalidated credential must not restore a logged-in account", AppAuthManager.getCurrentUser())
    }

    /**
     * R03: Lỗi UI/sync sau đăng nhập thành công kích hoạt đăng nhập dự phòng.
     * In signInWithGoogle, processSignedInAccount and onSuccess are wrapped in the outer try block.
     * If onSuccess throws an exception, the catch block triggers onFallbackToIntent even though the user is already logged in.
     */
    @Test
    fun probeSuccessCallbackFailureMustNotRestartLogin() {
        var fallbacks = 0
        val client = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: GetCredentialRequest): GoogleSignInAccountData? =
                GoogleSignInAccountData("valid", "valid@example.com", "Valid", null, null, null, null)
        }
        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = client,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = { fallbacks++ },
            onSuccess = { throw IllegalStateException("post-login UI or sync failure") },
            onError = {}
        )
        assertNotNull(AppAuthManager.getCurrentUser())
        assertEquals("Successful authentication must not fallback because its UI callback failed", 0, fallbacks)
    }

    /**
     * R04: Drive result không gắn yêu cầu còn sống vẫn được nhận.
     * When pendingSnapshot is null, handleDrivePermissionResult falls back to currentUser and current sessionGeneration,
     * allowing unsolicited or duplicate Drive results to grant permissions without a pending request.
     */
    @Test
    fun probeDriveResultWithoutPendingRequestMustNotSucceed() {
        AppAuthManager.processSignedInAccount(
            testContext,
            GoogleSignInAccountData("valid", "valid@example.com", "Valid", null, null, null, null)
        )
        val parser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? =
                GoogleSignInAccountData("valid", "valid@example.com", "Valid", null, null, null, null, setOf(AppAuthManager.DRIVE_FILE_SCOPE))
        }
        var successes = 0
        AppAuthManager.handleDrivePermissionResult(
            testContext,
            Activity.RESULT_OK,
            Intent(),
            parser,
            { successes++ },
            {},
            {}
        )
        assertEquals("Uncorrelated or duplicate Drive result must not authorize a sync", 0, successes)
    }

    // =========================================================================
    // S01 Regression Tests (Token & Session Invalidation)
    // =========================================================================

    @Test
    fun regressionClassicSignInAfterCancel_isDiscardedAndUserNull() {
        val attempt = AppAuthManager.createSignInAttemptForTesting()
        AppAuthManager.cancelSignInProgress(attempt)

        val fakeParser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? =
                GoogleSignInAccountData("cancelled_classic", "cancelled@example.com", "Cancelled", null, null, null, null)
        }

        var successCalled = false
        AppAuthManager.handleGoogleSignInResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = attempt,
            parser = fakeParser,
            onSuccess = { successCalled = true },
            onCancelled = {},
            onError = {}
        )

        assertFalse("Classic result after cancel must not call onSuccess", successCalled)
        assertNull("Cancelled classic result must not restore logged in user", AppAuthManager.getCurrentUser())
        assertNull("Cancelled classic result must not persist user to prefs", fakePrefs.getString("user_profile_json", null))
    }

    @Test
    fun regressionStaleAttemptA_whileAttemptBInProgress_doesNotOverwriteOrUnlockB() = runBlocking {
        val deferredA = CompletableDeferred<GoogleSignInAccountData?>()
        val clientA = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: GetCredentialRequest): GoogleSignInAccountData? =
                deferredA.await()
        }

        // Attempt A starts
        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = clientA,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = {},
            onError = {}
        )

        // Attempt A cancelled/superseded, Attempt B starts with fresh user
        AppAuthManager.cancelSignInProgress()

        val clientB = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: GetCredentialRequest): GoogleSignInAccountData? =
                GoogleSignInAccountData("user_b", "b@example.com", "User B", null, null, null, null)
        }

        var successB = false
        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = clientB,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = { successB = true },
            onError = {}
        )

        assertTrue("Attempt B must succeed", successB)
        assertEquals("User B must be logged in", "user_b", AppAuthManager.getCurrentUser()?.id)

        // Now deferred A finally returns
        deferredA.complete(GoogleSignInAccountData("user_a", "a@example.com", "User A", null, null, null, null))

        // Must still be user B!
        assertEquals("Stale attempt A must not overwrite newer session B", "user_b", AppAuthManager.getCurrentUser()?.id)
    }

    @Test
    fun regressionStaleException_doesNotTriggerFallback() = runBlocking {
        val deferred = CompletableDeferred<GoogleSignInAccountData?>()
        val client = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: GetCredentialRequest): GoogleSignInAccountData? =
                deferred.await()
        }

        var fallbackCount = 0
        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = client,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = { fallbackCount++ },
            onSuccess = {},
            onError = {}
        )

        // Invalidate attempt by changing session generation and cancelling
        AppAuthManager.nextSessionGeneration()
        AppAuthManager.cancelSignInProgress()

        // Deferred throws technical error (e.g. NoCredentialException)
        deferred.completeExceptionally(NoCredentialException("No credentials found"))

        assertEquals("Stale exception from invalidated attempt must not trigger fallback", 0, fallbackCount)
    }

    @Test
    fun regressionValidSignIn_claimsGuestDocumentsOnce() = runBlocking {
        val repo = DocumentRepo.getInstance(testContext)
        val guestDoc = DocumentItem(id = "doc_guest_1", title = "Unowned Document", ownerId = null)
        repo.addDocument(guestDoc)

        val client = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: GetCredentialRequest): GoogleSignInAccountData? =
                GoogleSignInAccountData("claimed_owner_id", "owner@example.com", "Doc Owner", null, null, null, null)
        }

        var successCalled = false
        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = client,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = { successCalled = true },
            onError = {}
        )

        assertTrue("Valid sign-in must succeed", successCalled)
        assertEquals("claimed_owner_id", AppAuthManager.getCurrentUser()?.id)
        val updatedDocs = repo.documents.value
        val claimedDoc = updatedDocs?.find { it.id == "doc_guest_1" }
        assertNotNull("Guest document must still exist", claimedDoc)
        assertEquals("Guest document must be claimed by canonicalId", "claimed_owner_id", claimedDoc?.ownerId)
    }

    @Test
    fun regressionInvalidatedCredential_doesNotMutatePrefsOrGuestDocuments() = runBlocking {
        val repo = DocumentRepo.getInstance(testContext)
        val guestDoc = DocumentItem(id = "doc_guest_stale", title = "Stale Unowned Document", ownerId = null)
        repo.addDocument(guestDoc)

        val deferred = CompletableDeferred<GoogleSignInAccountData?>()
        val client = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: GetCredentialRequest): GoogleSignInAccountData? =
                deferred.await()
        }

        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = client,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = {},
            onError = {}
        )

        // Cancel progress
        AppAuthManager.cancelSignInProgress()

        // Stale credential arrives
        deferred.complete(GoogleSignInAccountData("stale_owner", "stale@example.com", "Stale Owner", null, null, null, null))

        assertNull("Current user must remain null", AppAuthManager.getCurrentUser())
        val doc = repo.documents.value?.find { it.id == "doc_guest_stale" }
        assertNull("Guest document must remain unclaimed (ownerId null)", doc?.ownerId)
        assertNull("Preferences must not have saved user profile", fakePrefs.getString("user_profile_json", null))
    }

    // =========================================================================
    // S02 Regression Tests (Scope Cancellation & Completion Cleanup)
    // =========================================================================

    @Test
    fun regressionCanceledBeforeLaunch_releasesLockAndAllowsNextSignIn() {
        val deadJob = Job()
        deadJob.cancel()
        val deadScope = CoroutineScope(Dispatchers.Unconfined + deadJob)

        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = deadScope,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = {},
            onError = {}
        )

        assertFalse("Dead scope must not lock sign-in", AppAuthManager.isSignInInProgress())

        // Next attempt with live scope must be accepted
        val liveScope = CoroutineScope(Dispatchers.Unconfined)
        val secondAttemptStarted = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = liveScope,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = {},
            onError = {}
        )

        assertTrue("Subsequent sign-in after cancelled scope must start successfully", secondAttemptStarted)
    }

    @Test
    fun regressionCancelDuringAwait_releasesLockAndAllowsNextSignIn() = runBlocking {
        val job = Job()
        val scope = CoroutineScope(Dispatchers.Unconfined + job)
        val deferred = CompletableDeferred<GoogleSignInAccountData?>()
        val slowClient = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: GetCredentialRequest): GoogleSignInAccountData? =
                deferred.await()
        }

        val started = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = scope,
            credentialClient = slowClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = {},
            onError = {}
        )

        assertTrue("First attempt should start", started)
        assertTrue("Sign in should be in progress", AppAuthManager.isSignInInProgress())

        // Cancel during await
        job.cancel()

        assertFalse("Cancelling coroutine during await must release lock", AppAuthManager.isSignInInProgress())

        // Next sign in should be accepted
        val nextStarted = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = {},
            onError = {}
        )

        assertTrue("Next attempt after cancel-during-await must succeed", nextStarted)
    }

    @Test
    fun regressionFallbackWaitingIntent_blocksDoubleTapUntilResultHandled() {
        val noCredClient = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: GetCredentialRequest): GoogleSignInAccountData? = null
        }

        var fallbackInvoked = false
        val started = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = noCredClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = { fallbackInvoked = true },
            onSuccess = {},
            onError = {}
        )

        assertTrue("Initial sign-in starts", started)
        assertTrue("Fallback to Intent must be invoked", fallbackInvoked)
        assertTrue("Attempt waiting for Intent must maintain active sign-in lock", AppAuthManager.isSignInInProgress())

        // Double tap while Intent is waiting must be rejected
        val doubleTapStarted = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = {},
            onError = {}
        )

        assertFalse("Double-tap while Intent is pending must be rejected", doubleTapStarted)

        // Result arrives or cancel occurs
        AppAuthManager.cancelSignInProgress()
        assertFalse("Sign in lock must be released after explicit cancel or result handling", AppAuthManager.isSignInInProgress())
    }

    @Test
    fun regressionAttemptACancelledThenAttemptBStarted_completionOfADoesNotUnlockB() = runBlocking {
        val deferredA = CompletableDeferred<GoogleSignInAccountData?>()
        val jobA = Job()
        val scopeA = CoroutineScope(Dispatchers.Unconfined + jobA)
        val clientA = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: GetCredentialRequest): GoogleSignInAccountData? =
                deferredA.await()
        }

        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = scopeA,
            credentialClient = clientA,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = {},
            onError = {}
        )

        val tokenA = AppAuthManager.getActiveSignInAttempt()
        assertNotNull(tokenA)

        // Cancel attempt A and start attempt B
        AppAuthManager.cancelSignInProgress()

        val deferredB = CompletableDeferred<GoogleSignInAccountData?>()
        val clientB = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: GetCredentialRequest): GoogleSignInAccountData? =
                deferredB.await()
        }

        val startedB = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = clientB,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = {},
            onError = {}
        )

        assertTrue("Attempt B must start", startedB)
        val tokenB = AppAuthManager.getActiveSignInAttempt()
        assertNotNull(tokenB)
        assertTrue("Token B must differ from Token A", tokenB!!.requestId > tokenA!!.requestId)

        // Now cancel job A
        jobA.cancel()

        // Verify Attempt B's lock was NOT destroyed by Job A's completion
        assertTrue("Attempt B must STILL be in progress", AppAuthManager.isSignInInProgress())
        assertEquals("Active attempt must still be token B", tokenB, AppAuthManager.getActiveSignInAttempt())

        // Finally complete Attempt B
        deferredB.complete(GoogleSignInAccountData("user_b_final", "b@example.com", "User B", null, null, null, null))
        assertEquals("User B must be signed in", "user_b_final", AppAuthManager.getCurrentUser()?.id)
        assertFalse("Sign in progress must now be cleared", AppAuthManager.isSignInInProgress())
    }

    // =========================================================================
    // S03 Regression Tests (Isolated Provider SDK, Commit, and Callback Boundaries)
    // =========================================================================

    /**
     * S03: onSuccess throw in Credential flow must not trigger fallback or onError.
     * User profile is already committed; UI/sync failures do not invalidate auth.
     */
    @Test
    fun regressionCredentialOnSuccessThrow_doesNotCallOnErrorOrFallback() {
        var fallbacks = 0
        var errors = 0
        var successCalls = 0
        val client = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: GetCredentialRequest): GoogleSignInAccountData? =
                GoogleSignInAccountData("cred_user_s03", "s03@example.com", "S03 User", null, null, null, null)
        }

        val started = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = client,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = { fallbacks++ },
            onSuccess = {
                successCalls++
                throw RuntimeException("Fragment view destroyed or sync error during onSuccess")
            },
            onError = { errors++ }
        )

        assertTrue(started)
        assertEquals("onSuccess should have been invoked", 1, successCalls)
        assertEquals("Post-auth callback crash must not trigger fallback", 0, fallbacks)
        assertEquals("Post-auth callback crash must not trigger onError", 0, errors)
        assertEquals("User must remain committed and logged in", "cred_user_s03", AppAuthManager.getCurrentUser()?.id)
        assertFalse("Sign in progress lock must be cleanly cleared", AppAuthManager.isSignInInProgress())
    }

    /**
     * S03: onSuccess throw in Classic flow must not trigger onError.
     */
    @Test
    fun regressionClassicOnSuccessThrow_doesNotCallOnError() {
        var errors = 0
        var successCalls = 0

        var attemptToken: GoogleLoginAttempt? = null
        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = object : GoogleCredentialClient {
                override suspend fun getCredential(activity: Activity, request: GetCredentialRequest): GoogleSignInAccountData? = null
            },
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = {},
            onError = {},
            onAttemptCreated = { attemptToken = it }
        )

        assertNotNull(attemptToken)
        assertTrue(attemptToken!!.isFallbackActive())

        val parser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? =
                GoogleSignInAccountData("classic_user_s03", "classic@example.com", "Classic S03", null, null, null, null)
        }

        AppAuthManager.handleGoogleSignInResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = attemptToken,
            parser = parser,
            onSuccess = {
                successCalls++
                throw RuntimeException("UI exception in classic onSuccess")
            },
            onCancelled = {},
            onError = { errors++ }
        )

        assertEquals("onSuccess should have been invoked", 1, successCalls)
        assertEquals("onError must not be called when onSuccess throws", 0, errors)
        assertEquals("User must be committed", "classic_user_s03", AppAuthManager.getCurrentUser()?.id)
        assertFalse("Lock must be released", AppAuthManager.isSignInInProgress())
    }

    /**
     * S03: Provider technical error triggers fallback to Intent exactly once.
     */
    @Test
    fun regressionProviderError_triggersFallbackExactlyOnce() {
        var fallbacks = 0
        var errors = 0
        var successes = 0

        val client = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: GetCredentialRequest): GoogleSignInAccountData? {
                throw NoCredentialException("Provider technical error: no credentials")
            }
        }

        var attemptToken: GoogleLoginAttempt? = null
        val started = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = client,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = { fallbacks++ },
            onSuccess = { successes++ },
            onError = { errors++ },
            onAttemptCreated = { attemptToken = it }
        )

        assertTrue(started)
        assertEquals("Fallback must be triggered exactly once on provider error", 1, fallbacks)
        assertEquals("Success must not be called", 0, successes)
        assertEquals("onError must not be called when falling back to intent", 0, errors)
        assertNotNull(attemptToken)
        assertTrue("Fallback must be marked active on the attempt", attemptToken!!.isFallbackActive())
        assertTrue("Lock must be preserved waiting for fallback intent", AppAuthManager.isSignInInProgress())
    }

    /**
     * S03: User cancellation does not trigger fallback or error.
     */
    @Test
    fun regressionUserCancellation_doesNotTriggerFallbackOrError() {
        var fallbacks = 0
        var errors = 0
        var cancellations = 0

        val client = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: GetCredentialRequest): GoogleSignInAccountData? {
                throw androidx.credentials.exceptions.GetCredentialCancellationException("User dismissed sheet")
            }
        }

        val started = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = client,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = { fallbacks++ },
            onSuccess = {},
            onCancelled = { cancellations++ },
            onError = { errors++ }
        )

        assertTrue(started)
        assertEquals("onCancelled must be called exactly once", 1, cancellations)
        assertEquals("Fallback must NOT be triggered on user cancellation", 0, fallbacks)
        assertEquals("onError must NOT be called on user cancellation", 0, errors)
        assertFalse("Lock must be released", AppAuthManager.isSignInInProgress())
    }

    /**
     * S03: Local commit failure invokes onError and does NOT trigger fallback to Intent.
     */
    @Test
    fun regressionCommitError_callsOnErrorAndDoesNotFallback() {
        var fallbacks = 0
        var errors = 0
        var successes = 0

        val client = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: GetCredentialRequest): GoogleSignInAccountData? =
                GoogleSignInAccountData("commit_fail_user", "commit_fail@example.com", "Commit Fail", null, null, null, null)
        }

        testActivity.throwOnPrefs = true
        try {
            val started = AppAuthManager.signInWithGoogle(
                activity = testActivity,
                coroutineScope = CoroutineScope(Dispatchers.Unconfined),
                credentialClient = client,
                mainDispatcher = Dispatchers.Unconfined,
                onFallbackToIntent = { fallbacks++ },
                onSuccess = { successes++ },
                onError = { errors++ }
            )

            assertTrue(started)
            assertEquals("Success must NOT be called on commit failure", 0, successes)
            assertEquals("onError MUST be called on commit failure", 1, errors)
            assertEquals("Fallback to Intent must NEVER be called on commit failure", 0, fallbacks)
            assertNull("User must not be logged in", AppAuthManager.getCurrentUser())
            assertFalse("Lock must be cleanly released", AppAuthManager.isSignInInProgress())
        } finally {
            testActivity.throwOnPrefs = false
        }
    }

    // =========================================================================
    // S04 Regression Tests (Drive Authorization Request ID & Single-Consume)
    // =========================================================================

    /**
     * S04: Missing request is discarded safely without calling onSuccess or onError.
     */
    @Test
    fun regressionDrive_missingRequest_isDiscardedSafely() {
        AppAuthManager.processSignedInAccount(
            testContext,
            GoogleSignInAccountData("alice_id", "alice@example.com", "Alice", null, null, null, null)
        )
        val parser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? =
                GoogleSignInAccountData("alice_id", "alice@example.com", "Alice", null, null, null, null, setOf(AppAuthManager.DRIVE_FILE_SCOPE))
        }
        var successCalled = false
        var errorCalled = false
        AppAuthManager.handleDrivePermissionResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = null, // No attempt provided and no pending request
            parser = parser,
            onSuccess = { successCalled = true },
            onError = { errorCalled = true }
        )
        assertFalse("Missing request must not trigger onSuccess", successCalled)
        assertFalse("Missing request must not trigger onError", errorCalled)
    }

    /**
     * S04: Duplicate result for same request token is consumed only once.
     */
    @Test
    fun regressionDrive_duplicateResultSameRequest_consumedOnlyOnce() {
        AppAuthManager.processSignedInAccount(
            testContext,
            GoogleSignInAccountData("alice_id", "alice@example.com", "Alice", null, null, null, null)
        )
        val attempt = AppAuthManager.createDriveAuthorizationAttempt()
        assertNotNull(attempt)

        val parser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? =
                GoogleSignInAccountData("alice_id", "alice@example.com", "Alice", null, null, null, null, setOf(AppAuthManager.DRIVE_FILE_SCOPE))
        }

        var successCount = 0
        // First delivery: succeeds
        AppAuthManager.handleDrivePermissionResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = attempt,
            parser = parser,
            onSuccess = { successCount++ },
            onError = { fail("First delivery should succeed") }
        )
        assertEquals("First delivery must succeed", 1, successCount)

        // Second delivery with SAME attempt token: discarded due to single-consume semantics
        AppAuthManager.handleDrivePermissionResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = attempt,
            parser = parser,
            onSuccess = { successCount++ },
            onError = { fail("Duplicate delivery must be safely discarded without error") }
        )
        assertEquals("Duplicate result for same attempt must not be processed again", 1, successCount)
    }

    /**
     * S04: Overlapping hosts launching Drive authorization do not collide.
     */
    @Test
    fun regressionDrive_overlappingHosts_doNotCollide() {
        AppAuthManager.processSignedInAccount(
            testContext,
            GoogleSignInAccountData("alice_id", "alice@example.com", "Alice", null, null, null, null)
        )

        // Host A creates attempt
        val attemptA = AppAuthManager.createDriveAuthorizationAttempt()
        // Host B creates attempt afterwards
        val attemptB = AppAuthManager.createDriveAuthorizationAttempt()

        assertNotNull(attemptA)
        assertNotNull(attemptB)
        assertTrue(attemptA!!.requestId < attemptB!!.requestId)

        val parser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? =
                GoogleSignInAccountData("alice_id", "alice@example.com", "Alice", null, null, null, null, setOf(AppAuthManager.DRIVE_FILE_SCOPE))
        }

        var hostASucceeded = false
        var hostBSucceeded = false

        // Host A receives its result
        AppAuthManager.handleDrivePermissionResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = attemptA,
            parser = parser,
            onSuccess = { hostASucceeded = true },
            onError = { fail("Host A result should succeed") }
        )
        assertTrue("Host A must succeed", hostASucceeded)

        // Host B receives its result
        AppAuthManager.handleDrivePermissionResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = attemptB,
            parser = parser,
            onSuccess = { hostBSucceeded = true },
            onError = { fail("Host B result should succeed") }
        )
        assertTrue("Host B must succeed", hostBSucceeded)
    }

    /**
     * S04: Account switch or session generation advance while consent is open causes rejection.
     */
    @Test
    fun regressionDrive_accountSwitchedWhileConsentOpen_rejected() {
        AppAuthManager.processSignedInAccount(
            testContext,
            GoogleSignInAccountData("alice_id", "alice@example.com", "Alice", null, null, null, null)
        )
        val attempt = AppAuthManager.createDriveAuthorizationAttempt()
        assertNotNull(attempt)

        // Advance session generation / switch user
        AppAuthManager.nextSessionGeneration()

        val parser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? =
                GoogleSignInAccountData("alice_id", "alice@example.com", "Alice", null, null, null, null, setOf(AppAuthManager.DRIVE_FILE_SCOPE))
        }

        var successCalled = false
        AppAuthManager.handleDrivePermissionResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = attempt,
            parser = parser,
            onSuccess = { successCalled = true },
            onError = { fail("Stale session result should be silently discarded") }
        )
        assertFalse("Stale session result must not trigger onSuccess", successCalled)
    }

    /**
     * S04: Sign-out while consent is open rejects with "Chưa đăng nhập tài khoản".
     */
    @Test
    fun regressionDrive_logoutWhileConsentOpen_rejected() {
        AppAuthManager.processSignedInAccount(
            testContext,
            GoogleSignInAccountData("alice_id", "alice@example.com", "Alice", null, null, null, null)
        )
        val attempt = AppAuthManager.createDriveAuthorizationAttempt()
        assertNotNull(attempt)

        // Sign out user
        AppAuthManager.signOut(testContext, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined) {}

        var errorMsg: String? = null
        AppAuthManager.handleDrivePermissionResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = attempt,
            onSuccess = { fail("Must not succeed when user signed out") },
            onError = { errorMsg = it }
        )
        assertEquals("Chưa đăng nhập tài khoản", errorMsg)
    }

    /**
     * S04: DriveAuthorizationAttempt is serializable and survives round-trip.
     */
    @Test
    fun regressionDrive_attemptSurvivesSerializationRoundTrip() {
        val original = DriveAuthorizationAttempt(
            requestId = 42L,
            userId = "user_42",
            userEmail = "user42@example.com",
            sessionGeneration = 7L
        )

        val baos = java.io.ByteArrayOutputStream()
        val oos = java.io.ObjectOutputStream(baos)
        oos.writeObject(original)
        oos.flush()

        val bais = java.io.ByteArrayInputStream(baos.toByteArray())
        val ois = java.io.ObjectInputStream(bais)
        val restored = ois.readObject() as DriveAuthorizationAttempt

        assertEquals(original.requestId, restored.requestId)
        assertEquals(original.userId, restored.userId)
        assertEquals(original.userEmail, restored.userEmail)
        assertEquals(original.sessionGeneration, restored.sessionGeneration)
        assertTrue(restored.consume())
        assertFalse(restored.consume()) // Single consume still works
    }

    // =========================================================================
    // Test Harness
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
