package com.tscanner.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.GoogleCredentialClient
import com.tscanner.app.utils.GoogleSignInAccountData
import com.tscanner.app.utils.LogoutCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Integration tests for J02: Wiring real Google SignOut Task into LogoutCoordinator
 * and verifying AppAuthManager signOut/signIn boundaries under async Task conditions.
 */
class AppAuthGoogleLogoutIntegrationTest {

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
        LogoutCoordinator.resetForTesting()
    }

    @After
    fun tearDown() {
        AppAuthManager.resetForTesting()
        DocumentRepo.resetInstanceForTesting()
        LogoutCoordinator.resetForTesting()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    private class ControllableGoogleSignOutProvider(
        var autoSucceed: Boolean = false
    ) : AppAuthManager.GoogleSignOutProvider {
        val createdTasks = mutableListOf<TaskCompletionSource<Void>>()
        var shouldThrowSynchronously = false

        override fun signOut(context: Context): Task<Void>? {
            if (shouldThrowSynchronously) {
                throw RuntimeException("Synchronous failure before task creation")
            }
            val tcs = TaskCompletionSource<Void>()
            createdTasks.add(tcs)
            if (autoSucceed) {
                tcs.setResult(null)
            }
            return tcs.task
        }
    }

    private class ControllableCredentialClearProvider : AppAuthManager.CredentialClearProvider {
        var pauseOnFirstCall = true
        val firstCallStarted = CompletableDeferred<Unit>()
        val firstCallProceed = CompletableDeferred<Unit>()
        var shouldThrow = false
        var callCount = 0

        override suspend fun clearCredentialState(context: Context) {
            val current = synchronized(this) { ++callCount }
            if (shouldThrow) {
                throw RuntimeException("Clear credential failure")
            }
            if (current == 1 && pauseOnFirstCall) {
                firstCallStarted.complete(Unit)
                firstCallProceed.await()
            }
        }
    }

    private class FakeSuccessCredentialClient(
        private val accountData: GoogleSignInAccountData
    ) : GoogleCredentialClient {
        override suspend fun getCredential(activity: Activity, request: GetCredentialRequest): GoogleSignInAccountData {
            return accountData
        }
    }

    /**
     * 1. Task holds pending through timeout and scope cancel -> login cannot commit.
     * 2. Task completes afterwards -> subsequent valid login succeeds.
     */
    @Test
    fun taskPendingThroughTimeoutAndScopeCancel_gatesLoginUntilTaskCompletes() {
        val controllableProvider = ControllableGoogleSignOutProvider()
        AppAuthManager.googleSignOutProvider = controllableProvider

        // Set short coordinator timeout for testing
        LogoutCoordinator.cleanupTimeoutMs = 50L

        // Sign in initial user via demo account
        AppAuthManager.signInWithDemoAccount(testContext) {}
        assertNotNull("Initial user signed in", AppAuthManager.getCurrentUser())

        val logoutScope = CoroutineScope(Dispatchers.Unconfined)
        val logoutCompleteLatch = CountDownLatch(1)

        AppAuthManager.signOut(
            context = testContext,
            coroutineScope = logoutScope,
            mainDispatcher = Dispatchers.Unconfined,
            onComplete = {
                logoutCompleteLatch.countDown()
            }
        )

        assertEquals("Task should be created before suspension", 1, controllableProvider.createdTasks.size)
        val pendingTask = controllableProvider.createdTasks[0]

        // Cancel the logout coroutine scope to simulate host cancellation while task is pending
        logoutScope.cancel()

        assertTrue("Logout UI onComplete must fire even if scope cancelled", logoutCompleteLatch.await(2, TimeUnit.SECONDS))

        // At this point, the coroutine is finished/cancelled, BUT the SDK Task is STILL PENDING!
        assertTrue("LogoutCoordinator must detect provider task still pending", LogoutCoordinator.hasPendingProviderTasks())
        assertTrue("Coordinator must be in cleaning state", LogoutCoordinator.isCleaningProvider())

        val credClient = FakeSuccessCredentialClient(
            GoogleSignInAccountData("new_user", "new@test.com", "New User", "New", "User", null, null)
        )

        // Attempting to sign in while provider Task is pending must be gated and rejected
        val loginErrorLatch = CountDownLatch(1)
        var reportedError: String? = null
        val initiated = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.IO),
            credentialClient = credClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = {},
            onError = { err ->
                reportedError = err
                loginErrorLatch.countDown()
            }
        )

        assertTrue("SignIn request was initiated", initiated)
        assertTrue("Error callback must be invoked due to pending cleanup", loginErrorLatch.await(2, TimeUnit.SECONDS))
        assertNotNull(reportedError)
        assertTrue(reportedError!!.contains("đăng xuất"))
        assertNull("User must not be logged in while provider task was pending", AppAuthManager.getCurrentUser())

        // Now, the provider SDK task finally completes in background
        pendingTask.setResult(null)

        // Verify provider task is no longer pending
        assertFalse("Coordinator should have no pending tasks", LogoutCoordinator.hasPendingProviderTasks())
        assertFalse("Coordinator should no longer be cleaning", LogoutCoordinator.isCleaningProvider())

        // Subsequent valid login must now succeed
        val loginSuccessLatch = CountDownLatch(1)
        var loggedInUser: UserProfile? = null
        val retryInitiated = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.IO),
            credentialClient = credClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = { user ->
                loggedInUser = user
                loginSuccessLatch.countDown()
            },
            onError = {}
        )

        assertTrue(retryInitiated)
        assertTrue("Login must succeed after provider task completed", loginSuccessLatch.await(2, TimeUnit.SECONDS))
        assertNotNull(loggedInUser)
        assertEquals("new_user", loggedInUser?.id)
        assertEquals("new_user", AppAuthManager.getCurrentUser()?.id)
    }

    /**
     * Two logout operations with reversed completion order:
     * When task 2 completes before task 1, login remains gated until task 1 also completes.
     */
    @Test
    fun twoLogoutOperationsWithReversedCompletion_gatesUntilBothComplete() {
        val controllableProvider = ControllableGoogleSignOutProvider()
        AppAuthManager.googleSignOutProvider = controllableProvider
        LogoutCoordinator.cleanupTimeoutMs = 50L

        // Logout 1
        AppAuthManager.signOut(
            context = testContext,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            mainDispatcher = Dispatchers.Unconfined
        )

        // Logout 2
        AppAuthManager.signOut(
            context = testContext,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            mainDispatcher = Dispatchers.Unconfined
        )

        assertEquals("Both tasks should have been created", 2, controllableProvider.createdTasks.size)
        val task1 = controllableProvider.createdTasks[0]
        val task2 = controllableProvider.createdTasks[1]

        // Complete Task 2 first (reversed order)
        task2.setResult(null)

        // Task 1 is still pending -> login must remain gated!
        assertTrue("Task 1 must still be pending", LogoutCoordinator.hasPendingProviderTasks())

        val credClient = FakeSuccessCredentialClient(
            GoogleSignInAccountData("u3", "u3@test.com", "U3", "U", "3", null, null)
        )
        val errorLatch = CountDownLatch(1)
        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.IO),
            credentialClient = credClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = {},
            onError = { errorLatch.countDown() }
        )
        assertTrue("Login must be rejected while Task 1 is pending", errorLatch.await(2, TimeUnit.SECONDS))
        assertNull(AppAuthManager.getCurrentUser())

        // Now complete Task 1
        task1.setResult(null)
        assertFalse(LogoutCoordinator.hasPendingProviderTasks())

        // Now login must succeed
        val successLatch = CountDownLatch(1)
        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.IO),
            credentialClient = credClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = { successLatch.countDown() },
            onError = {}
        )
        assertTrue("Login succeeds after all tasks finish", successLatch.await(2, TimeUnit.SECONDS))
        assertEquals("u3", AppAuthManager.getCurrentUser()?.id)
    }

    /**
     * Coroutine cancelled before provider start does not deadlock future logins.
     */
    @Test
    fun coroutineCanceledBeforeProviderStart_doesNotDeadlock() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.cancel() // Cancelled before signOut launch

        val latch = CountDownLatch(1)
        AppAuthManager.signOut(
            context = testContext,
            coroutineScope = scope,
            mainDispatcher = Dispatchers.Unconfined,
            onComplete = { latch.countDown() }
        )

        assertFalse("Coordinator should not be cleaning", LogoutCoordinator.isCleaningProvider())
        assertFalse("Coordinator should have no pending tasks", LogoutCoordinator.hasPendingProviderTasks())

        // Subsequent login must not deadlock
        val credClient = FakeSuccessCredentialClient(
            GoogleSignInAccountData("u_cancel", "c@test.com", "C", "C", "C", null, null)
        )
        val loginLatch = CountDownLatch(1)
        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.IO),
            credentialClient = credClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = { loginLatch.countDown() },
            onError = {}
        )
        assertTrue("Login must succeed without deadlock", loginLatch.await(2, TimeUnit.SECONDS))
        assertEquals("u_cancel", AppAuthManager.getCurrentUser()?.id)
    }

    /**
     * Failure synchronous before task creation does not deadlock coordinator.
     */
    @Test
    fun failureSynchronousBeforeTaskCreation_doesNotDeadlock() {
        val controllableProvider = ControllableGoogleSignOutProvider()
        controllableProvider.shouldThrowSynchronously = true
        AppAuthManager.googleSignOutProvider = controllableProvider

        val latch = CountDownLatch(1)
        AppAuthManager.signOut(
            context = testContext,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            mainDispatcher = Dispatchers.Unconfined,
            onComplete = { latch.countDown() }
        )
        assertTrue(latch.await(2, TimeUnit.SECONDS))

        assertFalse("Coordinator should not have dangling tasks", LogoutCoordinator.hasPendingProviderTasks())
        assertFalse("Coordinator should not be cleaning", LogoutCoordinator.isCleaningProvider())

        val credClient = FakeSuccessCredentialClient(
            GoogleSignInAccountData("u_sync_fail", "s@test.com", "S", "S", "S", null, null)
        )
        val loginLatch = CountDownLatch(1)
        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.IO),
            credentialClient = credClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = { loginLatch.countDown() },
            onError = {}
        )
        assertTrue(loginLatch.await(2, TimeUnit.SECONDS))
        assertEquals("u_sync_fail", AppAuthManager.getCurrentUser()?.id)
    }

    /**
     * Control test: valid Google credential flow completes normally when task completes immediately.
     */
    @Test
    fun validGoogleCredentialControlFlow_completesNormally() {
        val controllableProvider = ControllableGoogleSignOutProvider(autoSucceed = true)
        AppAuthManager.googleSignOutProvider = controllableProvider

        val latch = CountDownLatch(1)
        AppAuthManager.signOut(
            context = testContext,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            mainDispatcher = Dispatchers.Unconfined,
            onComplete = { latch.countDown() }
        )

        assertTrue("Sign out must complete immediately when provider task auto-succeeds", latch.await(2, TimeUnit.SECONDS))

        val credClient = FakeSuccessCredentialClient(
            GoogleSignInAccountData("control_user", "ctrl@test.com", "Ctrl", "C", "U", null, null)
        )
        val loginLatch = CountDownLatch(1)
        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.IO),
            credentialClient = credClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = { loginLatch.countDown() },
            onError = {}
        )
        assertTrue(loginLatch.await(2, TimeUnit.SECONDS))
        assertEquals("control_user", AppAuthManager.getCurrentUser()?.id)
    }

    /**
     * K02 Primary Integration Test:
     * Logout A is suspended inside clearCredentialState before creating a GoogleSignIn Task.
     * Logout B starts and completes its cleanup.
     * Login C is requested: Login C must remain gated because Logout A's coroutine is still running.
     * When A is released from clearCredentialState, A proceeds to create its GoogleSignIn Task and keeps it pending.
     * Login C (or retry) must remain gated while A's Task is pending.
     * Once A's Task is completed, subsequent Login C succeeds.
     */
    @Test
    fun logoutAHeldAtClearCredentialState_blocksLoginC_untilABothJobAndTasksComplete() {
        val controllableClearProvider = ControllableCredentialClearProvider()
        AppAuthManager.credentialClearProvider = controllableClearProvider

        val controllableSignOutProvider = ControllableGoogleSignOutProvider()
        AppAuthManager.googleSignOutProvider = controllableSignOutProvider

        LogoutCoordinator.cleanupTimeoutMs = 50L

        // Sign in initial user
        AppAuthManager.signInWithDemoAccount(testContext) {}
        assertNotNull(AppAuthManager.getCurrentUser())

        // 1. Start Logout A (using Default dispatcher so it runs concurrently)
        val scopeA = CoroutineScope(Dispatchers.Default)
        val logoutALatch = CountDownLatch(1)
        AppAuthManager.signOut(
            context = testContext,
            coroutineScope = scopeA,
            mainDispatcher = Dispatchers.Unconfined,
            onComplete = {
                logoutALatch.countDown()
            }
        )

        // Wait until A enters clearCredentialState and suspends
        runBlocking {
            controllableClearProvider.firstCallStarted.await()
        }

        // At this point, A has NOT created a Google sign-out Task yet!
        assertEquals("A should not have created Google sign-out Task yet", 0, controllableSignOutProvider.createdTasks.size)
        assertTrue("Coordinator must be cleaning because op A is RUNNING", LogoutCoordinator.isCleaningProvider())

        // 2. Start and complete Logout B
        val scopeB = CoroutineScope(Dispatchers.Unconfined)
        AppAuthManager.signOut(
            context = testContext,
            coroutineScope = scopeB,
            mainDispatcher = Dispatchers.Unconfined
        )

        // B ran immediately: created 1 task (task for B)
        assertEquals("Logout B should have created its task", 1, controllableSignOutProvider.createdTasks.size)
        val taskB = controllableSignOutProvider.createdTasks[0]
        // Complete B's task
        taskB.setResult(null)

        // Now B is fully completed!
        // BUT Logout A is STILL suspended inside clearCredentialState (coroutine RUNNING, no task yet)!
        assertTrue("Coordinator must still be in cleaning state because A is still RUNNING", LogoutCoordinator.isCleaningProvider())
        assertFalse("No pending tasks yet (A hasn't created one, B finished)", LogoutCoordinator.hasPendingProviderTasks())

        // 3. Login C is attempted while A is still held at clearCredentialState
        val credClient = FakeSuccessCredentialClient(
            GoogleSignInAccountData("user_c", "c@test.com", "User C", "User", "C", null, null)
        )
        val loginC1ErrorLatch = CountDownLatch(1)
        var errorReportedC1: String? = null
        val initiatedC1 = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.IO),
            credentialClient = credClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = {},
            onError = { err ->
                errorReportedC1 = err
                loginC1ErrorLatch.countDown()
            }
        )

        assertTrue(initiatedC1)
        assertTrue("Login C must be rejected/gated because A is still running", loginC1ErrorLatch.await(2, TimeUnit.SECONDS))
        assertNotNull(errorReportedC1)
        assertTrue(errorReportedC1!!.contains("đăng xuất"))
        assertNull("Login C must not commit while A is running", AppAuthManager.getCurrentUser())

        // 4. Release A from clearCredentialState: A proceeds and creates its Google sign-out task
        controllableClearProvider.firstCallProceed.complete(Unit)

        // Wait until A creates its GoogleSignIn Task (task A) and registers it with coordinator
        val taskWaitLatch = CountDownLatch(1)
        val checkScope = CoroutineScope(Dispatchers.Default)
        checkScope.launch {
            while (controllableSignOutProvider.createdTasks.size < 2 || !LogoutCoordinator.hasPendingProviderTasks()) {
                delay(10)
            }
            taskWaitLatch.countDown()
        }
        assertTrue("Task A must be created and registered after A is released from clearCredentialState", taskWaitLatch.await(2, TimeUnit.SECONDS))
        assertEquals(2, controllableSignOutProvider.createdTasks.size)
        val taskA = controllableSignOutProvider.createdTasks[1]

        // Keep Task A pending!
        assertTrue("Task A must be pending in coordinator", LogoutCoordinator.hasPendingProviderTasks())
        assertTrue("Coordinator must still be in cleaning state due to Task A", LogoutCoordinator.isCleaningProvider())

        // 5. Attempt Login C again while Task A is pending -> must STILL be gated!
        val loginC2ErrorLatch = CountDownLatch(1)
        var errorReportedC2: String? = null
        val initiatedC2 = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.IO),
            credentialClient = credClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = {},
            onError = { err ->
                errorReportedC2 = err
                loginC2ErrorLatch.countDown()
            }
        )

        assertTrue(initiatedC2)
        assertTrue("Login C2 must be rejected while Task A is pending", loginC2ErrorLatch.await(2, TimeUnit.SECONDS))
        assertNotNull(errorReportedC2)
        assertNull("User must not be logged in while Task A is pending", AppAuthManager.getCurrentUser())

        // 6. Complete Task A and wait for Logout A to fully complete
        taskA.setResult(null)
        assertTrue("Logout A must fully complete", logoutALatch.await(2, TimeUnit.SECONDS))

        // Now both coroutine and tasks for all operations are finished
        assertFalse("No tasks should be pending", LogoutCoordinator.hasPendingProviderTasks())
        assertFalse("Coordinator should no longer be cleaning", LogoutCoordinator.isCleaningProvider())

        // 7. Login C retries -> now succeeds!
        val loginC3SuccessLatch = CountDownLatch(1)
        var loggedInUser: UserProfile? = null
        val initiatedC3 = AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.IO),
            credentialClient = credClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = { user ->
                loggedInUser = user
                loginC3SuccessLatch.countDown()
            },
            onError = {}
        )

        assertTrue(initiatedC3)
        assertTrue("Login C must succeed after A's task finishes", loginC3SuccessLatch.await(2, TimeUnit.SECONDS))
        assertNotNull(loggedInUser)
        assertEquals("user_c", loggedInUser?.id)
        assertEquals("user_c", AppAuthManager.getCurrentUser()?.id)
    }

    /**
     * Clear credential failure before task creation reports clean and does not deadlock future logins.
     */
    @Test
    fun clearCredentialStateThrowsException_doesNotDeadlockFutureLogins() {
        val controllableClearProvider = ControllableCredentialClearProvider()
        controllableClearProvider.shouldThrow = true
        AppAuthManager.credentialClearProvider = controllableClearProvider

        val latch = CountDownLatch(1)
        AppAuthManager.signOut(
            context = testContext,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            mainDispatcher = Dispatchers.Unconfined,
            onComplete = { latch.countDown() }
        )
        assertTrue(latch.await(2, TimeUnit.SECONDS))

        assertFalse("Coordinator should not be cleaning", LogoutCoordinator.isCleaningProvider())
        assertFalse("Coordinator should have no pending tasks", LogoutCoordinator.hasPendingProviderTasks())

        val credClient = FakeSuccessCredentialClient(
            GoogleSignInAccountData("u_clear_fail", "cf@test.com", "CF", "C", "F", null, null)
        )
        val loginLatch = CountDownLatch(1)
        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.IO),
            credentialClient = credClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = { loginLatch.countDown() },
            onError = {}
        )
        assertTrue("Login must succeed after clearCredentialState failure", loginLatch.await(2, TimeUnit.SECONDS))
        assertEquals("u_clear_fail", AppAuthManager.getCurrentUser()?.id)
    }

    /**
     * Coroutine cancelled while inside clearCredentialState correctly transitions to CANCELLED
     * and does not deadlock future logins.
     */
    @Test
    fun coroutineCancelledWhileInsideClearCredentialState_cancelsOperationAndDoesNotDeadlock() {
        val controllableClearProvider = ControllableCredentialClearProvider()
        AppAuthManager.credentialClearProvider = controllableClearProvider

        val scope = CoroutineScope(Dispatchers.Default)
        AppAuthManager.signOut(
            context = testContext,
            coroutineScope = scope,
            mainDispatcher = Dispatchers.Unconfined
        )

        // Wait until coroutine reaches clearCredentialState
        runBlocking {
            controllableClearProvider.firstCallStarted.await()
        }

        assertTrue("Coordinator must be cleaning while suspended in clearCredentialState", LogoutCoordinator.isCleaningProvider())

        // Cancel scope while inside clearCredentialState
        scope.cancel()

        // Wait for cancellation to be processed
        val latch = CountDownLatch(1)
        val checkScope = CoroutineScope(Dispatchers.Default)
        checkScope.launch {
            while (LogoutCoordinator.isCleaningProvider()) {
                delay(10)
            }
            latch.countDown()
        }
        assertTrue("Coordinator must transition to finished on cancellation", latch.await(2, TimeUnit.SECONDS))
        assertFalse("No pending tasks", LogoutCoordinator.hasPendingProviderTasks())

        // Subsequent login must succeed
        val credClient = FakeSuccessCredentialClient(
            GoogleSignInAccountData("u_cancel_clear", "cc@test.com", "CC", "C", "C", null, null)
        )
        val loginLatch = CountDownLatch(1)
        AppAuthManager.signInWithGoogle(
            activity = testActivity,
            coroutineScope = CoroutineScope(Dispatchers.IO),
            credentialClient = credClient,
            mainDispatcher = Dispatchers.Unconfined,
            onFallbackToIntent = {},
            onSuccess = { loginLatch.countDown() },
            onError = {}
        )
        assertTrue("Login must succeed without deadlock", loginLatch.await(2, TimeUnit.SECONDS))
        assertEquals("u_cancel_clear", AppAuthManager.getCurrentUser()?.id)
    }

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
