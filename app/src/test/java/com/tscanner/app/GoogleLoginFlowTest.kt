package com.tscanner.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.GoogleCredentialClient
import com.tscanner.app.utils.GoogleSignInAccountData
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

class GoogleLoginFlowTest {

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

    @Test
    fun testSignInWithGoogle_success_updatesUserAndCallsOnSuccess() = runBlocking {
        val expectedAccount = GoogleSignInAccountData(
            id = "google_user_v02",
            email = "v02@gmail.com",
            displayName = "V02 User",
            givenName = "V02",
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
        var cancelledCalled = false
        var errorCalled: String? = null

        val started = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = fakeClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = { fallbackCalled = true },
            onSuccess = { successProfile = it },
            onCancelled = { cancelledCalled = true },
            onError = { errorCalled = it }
        )

        assertTrue("signInWithGoogle should start successfully", started)
        assertNotNull("onSuccess must be called", successProfile)
        assertEquals("google_user_v02", successProfile?.id)
        assertFalse("Fallback must not be called on success", fallbackCalled)
        assertFalse("onCancelled must not be called on success", cancelledCalled)
        assertNull("onError must not be called on success", errorCalled)
        assertEquals("CurrentUser must be updated", "google_user_v02", AppAuthManager.currentUser.value?.id)
        assertFalse("isSignInInProgress must be reset to false", AppAuthManager.isSignInInProgress())
    }

    @Test
    fun testSignInWithGoogle_userCancelled_callsOnCancelledAndDoesNotFallback() = runBlocking {
        val fakeClient = object : GoogleCredentialClient {
            override suspend fun getCredential(
                activity: Activity,
                request: GetCredentialRequest
            ): GoogleSignInAccountData? {
                throw GetCredentialCancellationException("User dismissed credential sheet")
            }
        }

        var successCalled = false
        var fallbackCalled = false
        var cancelledCalled = false
        var errorCalled: String? = null

        val started = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = fakeClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = { fallbackCalled = true },
            onSuccess = { successCalled = true },
            onCancelled = { cancelledCalled = true },
            onError = { errorCalled = it }
        )

        assertTrue("signInWithGoogle should start", started)
        assertTrue("onCancelled must be called when user cancels", cancelledCalled)
        assertFalse("onFallbackToIntent MUST NOT be called when user cancels", fallbackCalled)
        assertFalse("onSuccess must not be called", successCalled)
        assertNull("onError must not be called on cancellation", errorCalled)
        assertFalse("isSignInInProgress must be reset to false", AppAuthManager.isSignInInProgress())
    }

    @Test
    fun testSignInWithGoogle_technicalError_triggersFallbackExactlyOnce() = runBlocking {
        val fallbackCounter = AtomicInteger(0)

        val fakeClient = object : GoogleCredentialClient {
            override suspend fun getCredential(
                activity: Activity,
                request: GetCredentialRequest
            ): GoogleSignInAccountData? {
                throw NoCredentialException("No credentials found on device")
            }
        }

        var successCalled = false
        var cancelledCalled = false
        var errorCalled: String? = null

        val started = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = fakeClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = { fallbackCounter.incrementAndGet() },
            onSuccess = { successCalled = true },
            onCancelled = { cancelledCalled = true },
            onError = { errorCalled = it }
        )

        assertTrue("signInWithGoogle should start", started)
        assertEquals("Fallback must be triggered exactly once", 1, fallbackCounter.get())
        assertFalse("onCancelled must not be called on technical error", cancelledCalled)
        assertFalse("onSuccess must not be called", successCalled)
        assertNull("onError must not be called when falling back to intent", errorCalled)
    }

    @Test
    fun testSignInWithGoogle_doubleTap_rejectsSecondRequest() = runBlocking {
        val startedLatch = CountDownLatch(1)
        val resumeLatch = CountDownLatch(1)
        val callCount = AtomicInteger(0)

        val slowFakeClient = object : GoogleCredentialClient {
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
            credentialClient = slowFakeClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = {},
            onError = {}
        )

        // Wait until first request is actively in-flight inside getCredential
        startedLatch.await(2, TimeUnit.SECONDS)

        val secondStarted = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = scope,
            credentialClient = slowFakeClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = {},
            onError = {}
        )

        // Release first request
        resumeLatch.countDown()

        assertTrue("First sign-in request must be accepted", firstStarted)
        assertFalse("Second concurrent sign-in request must be rejected (debouncing)", secondStarted)
        assertEquals("Fake client must only be called once", 1, callCount.get())
    }

    @Test
    fun testSignInWithGoogle_lifecycleCancellation_doesNotFallback() = runBlocking {
        val fakeClient = object : GoogleCredentialClient {
            override suspend fun getCredential(
                activity: Activity,
                request: GetCredentialRequest
            ): GoogleSignInAccountData? {
                throw CancellationException("Scope cancelled")
            }
        }

        var fallbackCalled = false
        var successCalled = false
        var cancelledCalled = false

        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = fakeClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = { fallbackCalled = true },
            onSuccess = { successCalled = true },
            onCancelled = { cancelledCalled = true },
            onError = {}
        )

        assertFalse("Lifecycle cancellation MUST NOT trigger fallback", fallbackCalled)
        assertFalse("onSuccess must not be called", successCalled)
        assertFalse("isSignInInProgress must be reset to false", AppAuthManager.isSignInInProgress())
    }

    @Test
    fun testSignInWithGoogle_activityFinishing_doesNotInvokeCallbacks() = runBlocking {
        testActivity.mockIsFinishing = true

        val fakeClient = object : GoogleCredentialClient {
            override suspend fun getCredential(
                activity: Activity,
                request: GetCredentialRequest
            ): GoogleSignInAccountData? {
                return GoogleSignInAccountData(
                    id = "google_user_dead",
                    email = "dead@gmail.com",
                    displayName = "Dead Activity",
                    givenName = "Dead",
                    familyName = "Activity",
                    photoUrl = null,
                    idToken = null
                )
            }
        }

        var successCalled = false
        var fallbackCalled = false

        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            credentialClient = fakeClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = { fallbackCalled = true },
            onSuccess = { successCalled = true },
            onError = {}
        )

        assertFalse("onSuccess must NOT be called on a finishing Activity", successCalled)
        assertFalse("onFallbackToIntent must NOT be called on a finishing Activity", fallbackCalled)
        assertFalse("isSignInInProgress must be reset to false", AppAuthManager.isSignInInProgress())
    }

    @Test
    fun testSignInWithGoogle_staleCompletion_doesNotOverwriteNewerSession() = runBlocking {
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
        // Request 1 starts
        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = scope,
            credentialClient = staleClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = {},
            onError = {}
        )

        // Wait for request 1 to be inside getCredential
        staleLatch.await(2, TimeUnit.SECONDS)

        // Clear in-progress flag to simulate user initiating request 2 while request 1 is stale
        AppAuthManager.cancelSignInProgress()

        // Request 2 completes with user_fresh
        val freshClient = object : GoogleCredentialClient {
            override suspend fun getCredential(
                activity: Activity,
                request: GetCredentialRequest
            ): GoogleSignInAccountData? = GoogleSignInAccountData(
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

        // Now resume request 1
        resumeStaleLatch.countDown()
        delay(100)

        // Current user must STILL be fresh_user, not overwritten by stale_user!
        assertEquals("Stale completion must not overwrite fresh session", "fresh_user", AppAuthManager.currentUser.value?.id)
    }

    // -------------------------------------------------------------------------
    // Test Helpers
    // -------------------------------------------------------------------------

    private class TestActivity(private val baseContext: Context) : Activity() {
        var mockIsFinishing: Boolean = false
        var mockIsDestroyed: Boolean = false

        override fun isFinishing(): Boolean = mockIsFinishing
        override fun isDestroyed(): Boolean = mockIsDestroyed
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
