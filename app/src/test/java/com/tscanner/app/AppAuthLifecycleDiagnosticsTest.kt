package com.tscanner.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCustomException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.GoogleCredentialClient
import com.tscanner.app.utils.GoogleSignInAccountData
import com.tscanner.app.utils.GoogleSignInAccountParser
import com.tscanner.app.utils.GoogleSignInResult
import com.tscanner.app.utils.GoogleSignInResultRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import java.util.concurrent.atomic.AtomicInteger

class AppAuthLifecycleDiagnosticsTest {

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

    // -------------------------------------------------------------------------
    // 1. Sanitization verification
    // -------------------------------------------------------------------------

    @Test
    fun testSanitizeForLog_redactsSensitiveDataAndLimitsLength() {
        // Redacts email
        val withEmail = "User john.doe@example.com failed to authenticate"
        val sanitizedEmail = AppAuthManager.sanitizeForLog(withEmail)
        assertFalse(sanitizedEmail.contains("john.doe@example.com"))
        assertTrue(sanitizedEmail.contains("[EMAIL_REDACTED]"))

        // Redacts JWT Token
        val withJwt = "Token: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.doNotLeakThisSignaturePart123"
        val sanitizedJwt = AppAuthManager.sanitizeForLog(withJwt)
        assertFalse(sanitizedJwt.contains("doNotLeakThisSignaturePart123"))
        assertTrue(sanitizedJwt.contains("[TOKEN_REDACTED]"))

        // Redacts Bearer token
        val withBearer = "Authorization Bearer secret_access_token_12345"
        val sanitizedBearer = AppAuthManager.sanitizeForLog(withBearer)
        assertFalse(sanitizedBearer.contains("secret_access_token_12345"))
        assertTrue(sanitizedBearer.contains("[TOKEN_REDACTED]"))

        // Length limit
        val veryLong = "A".repeat(500)
        val sanitizedLong = AppAuthManager.sanitizeForLog(veryLong)
        assertTrue(sanitizedLong.length <= 200)

        // Null / blank
        assertEquals("none", AppAuthManager.sanitizeForLog(null))
        assertEquals("none", AppAuthManager.sanitizeForLog("   "))
    }

    @Test
    fun testSanitizeIdForLog_masksIdentifiers() {
        assertEquals("none", AppAuthManager.sanitizeIdForLog(null))
        assertEquals("none", AppAuthManager.sanitizeIdForLog(""))
        assertEquals("***", AppAuthManager.sanitizeIdForLog("abc"))
        assertEquals("goog***", AppAuthManager.sanitizeIdForLog("google_user_12345"))
    }

    // -------------------------------------------------------------------------
    // 2. Cancellation: MUST NOT fallback, MUST allow immediate manual retry
    // -------------------------------------------------------------------------

    @Test
    fun testCancellation_doesNotTriggerFallbackAndPermitsImmediateRetry() = runBlocking {
        val fakeClient = object : GoogleCredentialClient {
            override suspend fun getCredential(
                activity: Activity,
                request: GetCredentialRequest
            ): GoogleSignInAccountData? {
                throw GetCredentialCancellationException("User dismissed credential sheet or OAuth config rejected")
            }
        }

        var fallbackCalled = false
        var cancelledCalled = false
        var errorCalled: String? = null

        val started = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = fakeClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = { fallbackCalled = true },
            onSuccess = {},
            onCancelled = { cancelledCalled = true },
            onError = { errorCalled = it }
        )

        assertTrue("First attempt should start", started)
        assertTrue("onCancelled must be called on cancellation", cancelledCalled)
        assertFalse("CRITICAL: onFallbackToIntent MUST NOT be called when cancelled", fallbackCalled)
        assertNull("onError must not be called on cancellation", errorCalled)
        assertFalse("Busy state must be cleared", AppAuthManager.isSignInInProgress())

        // Now verify manual retry is immediately accepted (not blocked by stale busy state)
        val successfulAccount = GoogleSignInAccountData(
            id = "retry_success_user",
            email = "user@example.com",
            displayName = "Retry User",
            givenName = "Retry",
            familyName = "User",
            photoUrl = null,
            idToken = null
        )
        val retryClient = object : GoogleCredentialClient {
            override suspend fun getCredential(
                activity: Activity,
                request: GetCredentialRequest
            ): GoogleSignInAccountData = successfulAccount
        }

        var retrySuccessProfile: UserProfile? = null
        val retryStarted = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = retryClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = { retrySuccessProfile = it },
            onCancelled = {},
            onError = {}
        )

        assertTrue("Manual retry must start immediately after previous cancellation", retryStarted)
        assertNotNull("Retry must succeed and update profile", retrySuccessProfile)
        assertEquals("retry_success_user", retrySuccessProfile?.id)
        assertFalse("Busy state must remain false after retry completion", AppAuthManager.isSignInInProgress())
    }

    // -------------------------------------------------------------------------
    // 3. Technical errors: Fallback triggered exactly once
    // -------------------------------------------------------------------------

    @Test
    fun testTechnicalError_NoCredential_triggersFallbackOnce() = runBlocking {
        val fallbackCounter = AtomicInteger(0)
        val fakeClient = object : GoogleCredentialClient {
            override suspend fun getCredential(
                activity: Activity,
                request: GetCredentialRequest
            ): GoogleSignInAccountData? {
                throw NoCredentialException("No credentials found")
            }
        }

        val started = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = fakeClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = { fallbackCounter.incrementAndGet() },
            onSuccess = {},
            onCancelled = {},
            onError = {}
        )

        assertTrue("Sign in should start", started)
        assertEquals("Fallback must be triggered exactly once", 1, fallbackCounter.get())
    }

    @Test
    fun testTechnicalError_GetCredentialException_triggersFallbackOnce() = runBlocking {
        val fallbackCounter = AtomicInteger(0)
        val fakeClient = object : GoogleCredentialClient {
            override suspend fun getCredential(
                activity: Activity,
                request: GetCredentialRequest
            ): GoogleSignInAccountData? {
                throw GetCredentialCustomException("CUSTOM_TYPE", "Custom provider error")
            }
        }

        val started = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = fakeClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = { fallbackCounter.incrementAndGet() },
            onSuccess = {},
            onCancelled = {},
            onError = {}
        )

        assertTrue("Sign in should start", started)
        assertEquals("Fallback must be triggered exactly once", 1, fallbackCounter.get())
    }

    // -------------------------------------------------------------------------
    // 4. Lifecycle cancellation
    // -------------------------------------------------------------------------

    @Test
    fun testLifecycleCancellation_doesNotTriggerFallbackAndCleansUp() = runBlocking {
        val fakeClient = object : GoogleCredentialClient {
            override suspend fun getCredential(
                activity: Activity,
                request: GetCredentialRequest
            ): GoogleSignInAccountData? {
                throw CancellationException("Lifecycle scope cancelled")
            }
        }

        var fallbackCalled = false
        var successCalled = false

        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = fakeClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = { fallbackCalled = true },
            onSuccess = { successCalled = true },
            onError = {}
        )

        assertFalse("Lifecycle cancellation MUST NOT trigger fallback", fallbackCalled)
        assertFalse("onSuccess must not be called", successCalled)
        assertFalse("Busy state must be reset", AppAuthManager.isSignInInProgress())
    }

    // -------------------------------------------------------------------------
    // 5. Double-tap debouncing
    // -------------------------------------------------------------------------

    @Test
    fun testDoubleTap_rejectsSecondRequestWhileFirstInFlight() = runBlocking {
        val startedLatch = CountDownLatch(1)
        val resumeLatch = CountDownLatch(1)
        val callCount = AtomicInteger(0)

        val slowClient = object : GoogleCredentialClient {
            override suspend fun getCredential(
                activity: Activity,
                request: GetCredentialRequest
            ): GoogleSignInAccountData? {
                callCount.incrementAndGet()
                startedLatch.countDown()
                withContext(Dispatchers.IO) {
                    resumeLatch.await(2, TimeUnit.SECONDS)
                }
                return null
            }
        }

        val scope = CoroutineScope(Dispatchers.IO)
        val firstStarted = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = scope,
            credentialClient = slowClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = {},
            onError = {}
        )

        startedLatch.await(2, TimeUnit.SECONDS)

        val secondStarted = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = scope,
            credentialClient = slowClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = {},
            onError = {}
        )

        resumeLatch.countDown()

        assertTrue("First request must be accepted", firstStarted)
        assertFalse("Second concurrent request must be rejected", secondStarted)
        assertEquals("Client must be called only once", 1, callCount.get())
    }

    // -------------------------------------------------------------------------
    // 6. Stale session completion
    // -------------------------------------------------------------------------

    @Test
    fun testStaleCompletion_doesNotOverwriteNewerSession() = runBlocking {
        val staleLatch = CountDownLatch(1)
        val resumeStaleLatch = CountDownLatch(1)

        val staleClient = object : GoogleCredentialClient {
            override suspend fun getCredential(
                activity: Activity,
                request: GetCredentialRequest
            ): GoogleSignInAccountData? {
                staleLatch.countDown()
                withContext(Dispatchers.IO) {
                    resumeStaleLatch.await(2, TimeUnit.SECONDS)
                }
                return GoogleSignInAccountData(
                    id = "stale_user",
                    email = "stale@gmail.com",
                    displayName = "Stale User",
                    givenName = "Stale",
                    familyName = "User",
                    photoUrl = null,
                    idToken = null
                )
            }
        }

        val scope = CoroutineScope(Dispatchers.IO)
        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = scope,
            credentialClient = staleClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = {},
            onError = {}
        )

        staleLatch.await(2, TimeUnit.SECONDS)

        // User logs in via another request or logs out, changing generation
        AppAuthManager.cancelSignInProgress()

        val freshClient = object : GoogleCredentialClient {
            override suspend fun getCredential(
                activity: Activity,
                request: GetCredentialRequest
            ): GoogleSignInAccountData = GoogleSignInAccountData(
                id = "fresh_user",
                email = "fresh@gmail.com",
                displayName = "Fresh User",
                givenName = "Fresh",
                familyName = "User",
                photoUrl = null,
                idToken = null
            )
        }

        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = freshClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = {},
            onError = {}
        )

        assertEquals("Current user should be fresh_user", "fresh_user", AppAuthManager.currentUser.value?.id)

        // Resume stale request
        resumeStaleLatch.countDown()
        delay(100)

        // Stale result must be discarded and NOT overwrite fresh_user
        assertEquals("Stale completion must not overwrite fresh session", "fresh_user", AppAuthManager.currentUser.value?.id)
    }

    // -------------------------------------------------------------------------
    // 7. GoogleSignInResultRouter error and cancel tests
    // -------------------------------------------------------------------------

    @Test
    fun testResultRouter_developerError10_mapsToConfigError() {
        val parser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? {
                throw ApiException(Status(GoogleSignInResultRouter.STATUS_DEVELOPER_ERROR, "DEVELOPER_ERROR"))
            }
        }

        var dispatchedFailure: String? = null
        GoogleSignInResultRouter.dispatchResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            parser = parser,
            onSuccess = {},
            onCancelled = {},
            onError = { dispatchedFailure = it }
        )

        assertNotNull("Should dispatch error for status 10", dispatchedFailure)
        assertTrue(dispatchedFailure!!.contains("10"))
    }

    @Test
    fun testResultRouter_signInCancelled12501_mapsToCancelled() {
        val parser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? {
                throw ApiException(Status(GoogleSignInResultRouter.STATUS_SIGN_IN_CANCELLED, "SIGN_IN_CANCELLED"))
            }
        }

        var cancelledDispatched = false
        var errorDispatched = false

        GoogleSignInResultRouter.dispatchResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = Intent(),
            parser = parser,
            onSuccess = {},
            onCancelled = { cancelledDispatched = true },
            onError = { errorDispatched = true }
        )

        assertTrue("Status 12501 must route to cancelled", cancelledDispatched)
        assertFalse("Status 12501 must not route to error", errorDispatched)
    }

    @Test
    fun testResultRouter_nullIntentWithCanceledResult_routesToCancelledQuietly() {
        var cancelledDispatched = false
        var errorDispatched = false

        GoogleSignInResultRouter.dispatchResult(
            context = testContext,
            resultCode = Activity.RESULT_CANCELED,
            data = null,
            onSuccess = {},
            onCancelled = { cancelledDispatched = true },
            onError = { errorDispatched = true }
        )

        assertTrue("Null intent with RESULT_CANCELED must route to cancelled", cancelledDispatched)
        assertFalse("Must not route to error", errorDispatched)
    }

    // -------------------------------------------------------------------------
    // Test Helpers
    // -------------------------------------------------------------------------

    private class TestActivity(private val baseContext: Context) : Activity() {
        override fun isFinishing(): Boolean = false
        override fun isDestroyed(): Boolean = false
        override fun getApplicationContext(): Context = baseContext
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences(name, mode)
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
