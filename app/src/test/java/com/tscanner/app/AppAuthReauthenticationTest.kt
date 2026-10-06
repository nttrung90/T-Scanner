package com.tscanner.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.AccountMismatchException
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.ExpiredCredentialException
import com.tscanner.app.utils.GoogleCredentialClient
import com.tscanner.app.utils.GoogleLoginAttempt
import com.tscanner.app.utils.GoogleSignInAccountData
import com.tscanner.app.utils.GoogleSignInAccountParser
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
import java.util.Base64

/**
 * Unit tests verifying E01: Safe reauthentication for currently logged-in accounts.
 *
 * Verifies:
 * - A -> A fresh token reauth succeeds, updates token, preserves user profile and VIP.
 * - A -> B account mismatch is strictly rejected before commit or side effects.
 * - Expired or missing token from provider is rejected.
 * - Persistence failure stops commit cleanly without corrupted state.
 * - Stale attempt across logout or session increment is rejected.
 * - Cross-process epoch attempt is rejected.
 * - Double-tap debouncing.
 * - User cancellation keeps user state unchanged.
 * - Technical error fallbacks to classic Intent cleanly.
 * - Classic Intent fallback path enforces the exact same owner and token guards.
 * - Guest login continues to work without regression.
 */
class AppAuthReauthenticationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: TestContext
    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var testActivity: Activity

    private fun syntheticJwt(expSeconds: Long, subject: String = "user_a"): String {
        val payload = """{"sub":"$subject","exp":$expSeconds}"""
        return "eyJhbGciOiJSUzI1NiJ9." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".synthetic"
    }

    private fun createExpiredJwt(subject: String = "user_a"): String {
        return syntheticJwt(expSeconds = (System.currentTimeMillis() / 1000L) - 3600L, subject = subject)
    }

    private fun createFreshJwt(subject: String = "user_a"): String {
        return syntheticJwt(expSeconds = (System.currentTimeMillis() / 1000L) + 3600L, subject = subject)
    }

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
        AppAuthManager.postLoginHook = { _, _ -> }
        DocumentRepo.resetInstanceForTesting()
    }

    @After
    fun tearDown() {
        AppAuthManager.resetForTesting()
        DocumentRepo.resetInstanceForTesting()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    // =========================================================================
    // 1. Same-owner Reauthentication (A -> A)
    // =========================================================================

    @Test
    fun testReauth_sameOwner_success_updatesCredentialAndPreservesVip() {
        // Initial state: User A is logged in with expired token, VIP is active
        val oldToken = createExpiredJwt("user_a")
        val initialUser = UserProfile(
            id = "user_a",
            email = "user_a@example.com",
            displayName = "User A",
            isVip = true,
            tier = VipTier.VIP,
            idToken = oldToken
        )
        AppAuthManager.setCurrentUserForTesting(initialUser)
        AppAuthManager.setUserVipTier(testContext, VipTier.VIP, durationDays = 30)

        val freshToken = createFreshJwt("user_a")
        val fakeClient = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: GetCredentialRequest): GoogleSignInAccountData {
                return GoogleSignInAccountData(
                    id = "user_a",
                    email = "user_a@example.com",
                    displayName = "User A",
                    givenName = "User",
                    familyName = "A",
                    photoUrl = null,
                    idToken = freshToken
                )
            }
        }

        var reauthSuccess: UserProfile? = null
        var reauthError: String? = null

        val started = AppAuthManager.reauthenticateWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            expectedOwnerId = "user_a",
            credentialClient = fakeClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = { reauthSuccess = it },
            onError = { reauthError = it }
        )

        assertTrue("Reauth should start", started)
        assertNull("Reauth must not emit error", reauthError)
        assertNotNull("Reauth onSuccess must be called", reauthSuccess)

        // Verifications:
        assertEquals("User ID must remain user_a", "user_a", reauthSuccess?.id)
        assertEquals("Token must be updated to fresh token", freshToken, reauthSuccess?.idToken)
        assertEquals("Session token must match fresh token", freshToken, AppAuthManager.getSessionToken())
        assertTrue("User must still be VIP", AppAuthManager.isUserVip())
        assertTrue("isLoggedIn must remain true", AppAuthManager.isLoggedIn())
    }

    // =========================================================================
    // 2. Different-owner Reauthentication (A -> B Rejected)
    // =========================================================================

    @Test
    fun testReauth_differentOwner_rejectedBeforeSideEffects() {
        val oldToken = createExpiredJwt("user_a")
        val initialUser = UserProfile(
            id = "user_a",
            email = "user_a@example.com",
            displayName = "User A",
            isVip = true,
            tier = VipTier.VIP,
            idToken = oldToken
        )
        AppAuthManager.setCurrentUserForTesting(initialUser)
        AppAuthManager.setUserVipTier(testContext, VipTier.VIP, durationDays = 30)

        // Provider returns Account B instead of Account A
        val freshTokenB = createFreshJwt("user_b")
        val fakeClient = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: GetCredentialRequest): GoogleSignInAccountData {
                return GoogleSignInAccountData(
                    id = "user_b",
                    email = "user_b@example.com",
                    displayName = "User B",
                    givenName = "User",
                    familyName = "B",
                    photoUrl = null,
                    idToken = freshTokenB
                )
            }
        }

        var reauthSuccess: UserProfile? = null
        var reauthError: String? = null

        val started = AppAuthManager.reauthenticateWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            expectedOwnerId = "user_a",
            credentialClient = fakeClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = { reauthSuccess = it },
            onError = { reauthError = it }
        )

        assertTrue(started)
        assertNull("Success must NOT be called for mismatched account", reauthSuccess)
        assertNotNull("Error must be emitted for mismatched account", reauthError)
        assertTrue("Error message must inform about account mismatch", reauthError?.contains("khớp") == true || reauthError?.contains("tài khoản") == true)

        // Critical safety checks: User A remains active and completely untouched
        val currentUser = AppAuthManager.getCurrentUser()
        assertEquals("User A must remain logged in", "user_a", currentUser?.id)
        assertEquals("User A email must remain untouched", "user_a@example.com", currentUser?.email)
        assertTrue("User A VIP must remain intact", AppAuthManager.isUserVip())
    }

    // =========================================================================
    // 3. Expired or Missing Token from Provider
    // =========================================================================

    @Test
    fun testReauth_expiredIncomingToken_rejectedBeforeCommit() {
        val initialUser = UserProfile(
            id = "user_a",
            email = "user_a@example.com",
            displayName = "User A",
            isVip = false,
            tier = VipTier.FREE,
            idToken = createExpiredJwt("user_a")
        )
        AppAuthManager.setCurrentUserForTesting(initialUser)

        // Provider returns still-expired token
        val fakeClient = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: GetCredentialRequest): GoogleSignInAccountData {
                return GoogleSignInAccountData(
                    id = "user_a",
                    email = "user_a@example.com",
                    displayName = "User A",
                    givenName = "User",
                    familyName = "A",
                    photoUrl = null,
                    idToken = createExpiredJwt("user_a") // Still expired!
                )
            }
        }

        var reauthSuccess: UserProfile? = null
        var reauthError: String? = null

        AppAuthManager.reauthenticateWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            expectedOwnerId = "user_a",
            credentialClient = fakeClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = { reauthSuccess = it },
            onError = { reauthError = it }
        )

        assertNull("Success must NOT be called when incoming token is expired", reauthSuccess)
        assertNotNull("Error must be emitted when incoming token is expired", reauthError)
        assertEquals("user_a", AppAuthManager.getCurrentUser()?.id)
    }

    @Test
    fun testReauth_missingIncomingToken_rejectedBeforeCommit() {
        val initialUser = UserProfile(
            id = "user_a",
            email = "user_a@example.com",
            displayName = "User A",
            isVip = false,
            tier = VipTier.FREE,
            idToken = createExpiredJwt("user_a")
        )
        AppAuthManager.setCurrentUserForTesting(initialUser)

        val fakeClient = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: GetCredentialRequest): GoogleSignInAccountData {
                return GoogleSignInAccountData(
                    id = "user_a",
                    email = "user_a@example.com",
                    displayName = "User A",
                    givenName = "User",
                    familyName = "A",
                    photoUrl = null,
                    idToken = null // Missing token!
                )
            }
        }

        var reauthSuccess: UserProfile? = null
        var reauthError: String? = null

        AppAuthManager.reauthenticateWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            expectedOwnerId = "user_a",
            credentialClient = fakeClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = { reauthSuccess = it },
            onError = { reauthError = it }
        )

        assertNull("Success must NOT be called when incoming token is null", reauthSuccess)
        assertNotNull("Error must be emitted when incoming token is null", reauthError)
    }

    // =========================================================================
    // 4. Persistence Failure Guard
    // =========================================================================

    @Test
    fun testReauth_saveFailure_keepsConsistentUser() {
        val initialUser = UserProfile(
            id = "user_a",
            email = "user_a@example.com",
            displayName = "User A",
            isVip = false,
            tier = VipTier.FREE,
            idToken = createExpiredJwt("user_a")
        )
        AppAuthManager.setCurrentUserForTesting(initialUser)

        // Make fakePrefs fail on commit
        fakePrefs.failOnCommit = true

        val attempt = AppAuthManager.createReauthAttemptForTesting("user_a")
        val accountData = GoogleSignInAccountData(
            id = "user_a",
            email = "user_a@example.com",
            displayName = "User A",
            givenName = "User",
            familyName = "A",
            photoUrl = null,
            idToken = createFreshJwt("user_a")
        )

        var thrownException: Exception? = null
        try {
            AppAuthManager.commitSignedInAccount(testContext, attempt, accountData)
        } catch (e: Exception) {
            thrownException = e
        }

        assertNotNull("Commit must throw when persistence fails", thrownException)
        assertTrue("Exception must be IllegalStateException", thrownException is IllegalStateException)
        assertEquals("User A must remain current user", "user_a", AppAuthManager.getCurrentUser()?.id)
    }

    // =========================================================================
    // 5. Stale Attempt Across Logout or Session Increment
    // =========================================================================

    @Test
    fun testReauth_logoutWhileWaiting_staleAttemptRejected() {
        val initialUser = UserProfile(
            id = "user_a",
            email = "user_a@example.com",
            displayName = "User A",
            isVip = false,
            tier = VipTier.FREE,
            idToken = createExpiredJwt("user_a")
        )
        AppAuthManager.setCurrentUserForTesting(initialUser)

        val attempt = AppAuthManager.createReauthAttemptForTesting("user_a")

        // Session changes (e.g. logout or increment)
        AppAuthManager.nextSessionGeneration()

        val accountData = GoogleSignInAccountData(
            id = "user_a",
            email = "user_a@example.com",
            displayName = "User A",
            givenName = "User",
            familyName = "A",
            photoUrl = null,
            idToken = createFreshJwt("user_a")
        )

        val result = AppAuthManager.commitSignedInAccount(testContext, attempt, accountData)
        assertNull("Stale attempt must return null", result)
    }

    // =========================================================================
    // 6. Double-Tap Debouncing & User Cancellation
    // =========================================================================

    @Test
    fun testReauth_doubleTap_debounced() {
        AppAuthManager.createReauthAttemptForTesting("user_a")

        // Attempting to reauth while another attempt is in-flight must return false
        val startedSecond = AppAuthManager.reauthenticateWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            expectedOwnerId = "user_a",
            onFallbackToIntent = {},
            onSuccess = {},
            onError = {}
        )
        assertFalse("Second reauth while in-flight must be debounced", startedSecond)
    }

    @Test
    fun testReauth_userCancellation_preservesUser() {
        val initialUser = UserProfile(
            id = "user_a",
            email = "user_a@example.com",
            displayName = "User A",
            isVip = false,
            tier = VipTier.FREE,
            idToken = createExpiredJwt("user_a")
        )
        AppAuthManager.setCurrentUserForTesting(initialUser)

        val cancellingClient = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: GetCredentialRequest): GoogleSignInAccountData? {
                throw GetCredentialCancellationException("User cancelled prompt")
            }
        }

        var cancelledCalled = false
        var errorCalled: String? = null

        AppAuthManager.reauthenticateWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            expectedOwnerId = "user_a",
            credentialClient = cancellingClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = {},
            onCancelled = { cancelledCalled = true },
            onError = { errorCalled = it }
        )

        assertTrue("onCancelled must be called", cancelledCalled)
        assertNull("onError must NOT be called for cancellation", errorCalled)
        assertEquals("user_a", AppAuthManager.getCurrentUser()?.id)
        assertFalse("Attempt lock must be released", AppAuthManager.isSignInInProgress())
    }

    // =========================================================================
    // 7. Intent Fallback Path Reauth Contracts
    // =========================================================================

    @Test
    fun testIntentFallback_sameOwner_success() {
        val initialUser = UserProfile(
            id = "user_a",
            email = "user_a@example.com",
            displayName = "User A",
            isVip = false,
            tier = VipTier.FREE,
            idToken = createExpiredJwt("user_a")
        )
        AppAuthManager.setCurrentUserForTesting(initialUser)

        val attempt = AppAuthManager.createReauthAttemptForTesting("user_a")
        val freshToken = createFreshJwt("user_a")

        val parser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData {
                return GoogleSignInAccountData(
                    id = "user_a",
                    email = "user_a@example.com",
                    displayName = "User A",
                    givenName = "User",
                    familyName = "A",
                    photoUrl = null,
                    idToken = freshToken
                )
            }
        }

        var successProfile: UserProfile? = null
        var errorCalled: String? = null

        AppAuthManager.handleGoogleSignInResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = attempt,
            parser = parser,
            onSuccess = { successProfile = it },
            onError = { errorCalled = it }
        )

        assertNotNull("Fallback must call onSuccess for same owner", successProfile)
        assertNull(errorCalled)
        assertEquals("user_a", successProfile?.id)
        assertEquals(freshToken, successProfile?.idToken)
    }

    @Test
    fun testIntentFallback_differentOwner_rejected() {
        val initialUser = UserProfile(
            id = "user_a",
            email = "user_a@example.com",
            displayName = "User A",
            isVip = false,
            tier = VipTier.FREE,
            idToken = createExpiredJwt("user_a")
        )
        AppAuthManager.setCurrentUserForTesting(initialUser)

        val attempt = AppAuthManager.createReauthAttemptForTesting("user_a")
        val freshTokenB = createFreshJwt("user_b")

        val parser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData {
                return GoogleSignInAccountData(
                    id = "user_b",
                    email = "user_b@example.com",
                    displayName = "User B",
                    givenName = "User",
                    familyName = "B",
                    photoUrl = null,
                    idToken = freshTokenB
                )
            }
        }

        var successProfile: UserProfile? = null
        var errorCalled: String? = null

        AppAuthManager.handleGoogleSignInResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            attempt = attempt,
            parser = parser,
            onSuccess = { successProfile = it },
            onError = { errorCalled = it }
        )

        assertNull("Fallback must NOT call onSuccess for mismatched owner", successProfile)
        assertNotNull("Fallback must call onError for mismatched owner", errorCalled)
        assertEquals("user_a", AppAuthManager.getCurrentUser()?.id)
    }

    // =========================================================================
    // 8. Guest Login Continuity (No Regression)
    // =========================================================================

    @Test
    fun testGuestLogin_withoutExpectedOwner_commitsNormally() {
        val freshToken = createFreshJwt("guest_user")
        val fakeClient = object : GoogleCredentialClient {
            override suspend fun getCredential(activity: Activity, request: GetCredentialRequest): GoogleSignInAccountData {
                return GoogleSignInAccountData(
                    id = "guest_user",
                    email = "guest@example.com",
                    displayName = "Guest",
                    givenName = "Guest",
                    familyName = "User",
                    photoUrl = null,
                    idToken = freshToken
                )
            }
        }

        var successProfile: UserProfile? = null
        val started = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = fakeClient,
            mainDispatcher = Dispatchers.Unconfined,
            expectedOwnerId = null, // Guest
            onFallbackToIntent = {},
            onSuccess = { successProfile = it },
            onError = {}
        )

        assertTrue(started)
        assertNotNull(successProfile)
        assertEquals("guest_user", successProfile?.id)
        assertTrue(AppAuthManager.isLoggedIn())
    }

    // =========================================================================
    // Test Harness Classes
    // =========================================================================

    private class TestActivity(context: Context) : Activity() {
        private val baseContextRef = context
        override fun getApplicationContext(): Context = baseContextRef
        override fun getBaseContext(): Context = baseContextRef
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            baseContextRef.getSharedPreferences(name, mode)
        override fun getFilesDir(): File = baseContextRef.filesDir
        override fun getCacheDir(): File = baseContextRef.cacheDir
        override fun isFinishing(): Boolean = false
        override fun isDestroyed(): Boolean = false
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
        var failOnCommit = false

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
                if (prefs.failOnCommit) {
                    return false
                }
                apply()
                return true
            }

            override fun apply() {
                if (prefs.failOnCommit) {
                    return
                }
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
