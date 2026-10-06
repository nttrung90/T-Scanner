package com.tscanner.app

import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.GoogleLoginAttempt
import com.tscanner.app.utils.LogoutCoordinator
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression suite for VIP / Login Round 5 re-audit findings (F01, F02)
 * per RECHECK_VIP_LOGIN_ROUND5_2026-09-25.md.
 *
 * NOTE: Tests that invoke [AppAuthManager.resetForTesting] simulate process identity
 * and singleton/counter re-initialization within the JVM. They do NOT execute
 * full Android instrumentation process death/recreation.
 *
 * Acceptance for J00:
 * - 4 regression tests fail (RED) reproducing F01a, F01b, F02.
 * - 1 control test passes (GREEN) verifying the harness and normal completion flow.
 */
class VipLoginRound5RegressionTest {

    @Before
    fun setup() {
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
            override fun executeOnDiskIO(r: Runnable) = r.run()
            override fun postToMainThread(r: Runnable) = r.run()
            override fun isMainThread() = true
        })
        AppAuthManager.resetForTesting()
    }

    @After
    fun cleanup() {
        AppAuthManager.resetForTesting()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    /**
     * F01a: Timeout or coroutine cancellation must not open login when provider Task is still pending.
     * Expectation: Coroutine completion is not SDK Task completion; awaitProviderCleanup should return false.
     */
    @Test
    fun pendingProviderTaskSurvivesCoroutineCompletion() {
        runBlocking {
            val op = LogoutCoordinator.startLogout()
            LogoutCoordinator.markProviderTaskStarted()
            LogoutCoordinator.onCleanupCompleted(op)
            assertFalse("Coroutine completion is not SDK Task completion", LogoutCoordinator.awaitProviderCleanup(1))
        }
    }

    /**
     * F01b: Replaced logout must not release waiter of previous logout when new cleanup is still active.
     * Expectation: New logout still active; old deferred completion cannot grant login.
     */
    @Test
    fun replacedLogoutMustNotReleaseOldWaiter() {
        runBlocking {
            LogoutCoordinator.startLogout()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { LogoutCoordinator.awaitProviderCleanup(50) }
            val replacement = LogoutCoordinator.startLogout()
            assertFalse("New logout still active; old deferred completion cannot grant login", waiter.await())
            LogoutCoordinator.onCleanupCompleted(replacement)
        }
    }

    /**
     * F02: Legacy restored login token without processEpoch must not match a new attempt in a new process.
     * NOTE: resetForTesting simulates process identity and counter re-initialization, not Android instrumentation.
     * Expectation: Epoch-less restored token must not match a new process attempt.
     */
    @Test
    fun legacyRestoredLoginTokenMustNotMatchNewProcess() {
        val old = AppAuthManager.createSignInAttemptForTesting()
        val restored = GoogleLoginAttempt(old.requestId, old.initialSessionGeneration)
        AppAuthManager.resetForTesting()
        AppAuthManager.createSignInAttemptForTesting()
        assertFalse("Epoch-less restored token must not match a new process attempt", AppAuthManager.isAttemptValid(restored))
    }

    /**
     * F02: Cancellation with old process epoch token must not clear active attempt of new process.
     * NOTE: resetForTesting simulates process identity and counter re-initialization, not Android instrumentation.
     * Expectation: Cancel must compare epoch as well as counters.
     */
    @Test
    fun oldEpochCancellationMustNotClearNewAttempt() {
        val old = AppAuthManager.createSignInAttemptForTesting()
        AppAuthManager.resetForTesting()
        val current = AppAuthManager.createSignInAttemptForTesting()
        AppAuthManager.cancelSignInProgress(old)
        assertEquals("Cancel must compare epoch as well as counters", current, AppAuthManager.getActiveSignInAttempt())
    }

    /**
     * Control test: Provider actually marked completed followed by coroutine completion allows login.
     */
    @Test
    fun completedProviderAllowsLoginControl() {
        runBlocking {
            val op = LogoutCoordinator.startLogout()
            LogoutCoordinator.markProviderTaskStarted()
            LogoutCoordinator.markProviderTaskCompleted()
            LogoutCoordinator.onCleanupCompleted(op)
            assertTrue(LogoutCoordinator.awaitProviderCleanup(10))
        }
    }
}
