package com.tscanner.app

import com.tscanner.app.utils.LogoutCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Permanent regression suite for VIP / Login Round 6 audit findings per RECHECK_VIP_LOGIN_ROUND6_2026-09-25.md.
 *
 * Verifies that initiating a new logout operation does not mark older running cleanup coroutines
 * as COMPLETED while they are still executing in background.
 *
 * Acceptance for K00:
 * - 1 regression test fails (RED) reproducing the defect.
 * - 2 control tests pass (GREEN) verifying normal completion and pending task gating.
 */
class VipLoginRound6RegressionTest {

    @Before
    fun setup() {
        LogoutCoordinator.resetForTesting()
    }

    @After
    fun cleanup() {
        LogoutCoordinator.resetForTesting()
    }

    /**
     * Regression probe: A has not finished or cancelled; B completion must not grant login.
     * In current code, startLogout(B) marks A as COMPLETED, so B completion unlocks prematurely.
     */
    @Test
    fun finishingNewLogoutMustNotFinishOlderRunningCoroutine(): Unit = runBlocking {
        val old = LogoutCoordinator.startLogout()
        // A is suspended in clearCredentialState, before registering GoogleSignIn Task.
        val newer = LogoutCoordinator.startLogout()
        LogoutCoordinator.onCleanupCompleted(newer)
        assertFalse("A never finished or cancelled; B completion cannot grant login", LogoutCoordinator.awaitProviderCleanup(1))
        LogoutCoordinator.onCleanupCompleted(old)
        assertTrue(LogoutCoordinator.awaitProviderCleanup(1))
    }

    /**
     * Control 1: When both A and B actually finish, login is allowed.
     */
    @Test
    fun allActualCoroutinesFinishedAllowsLogin(): Unit = runBlocking {
        val a = LogoutCoordinator.startLogout()
        val b = LogoutCoordinator.startLogout()
        LogoutCoordinator.onCleanupCompleted(b)
        LogoutCoordinator.onCleanupCompleted(a)
        assertTrue(LogoutCoordinator.awaitProviderCleanup(1))
    }

    /**
     * Control 2: Pending provider Task continues to gate after coroutines finish until task completes.
     */
    @Test
    fun pendingTaskStillBlocksAfterCoroutinesFinish(): Unit = runBlocking {
        val a = LogoutCoordinator.startLogout()
        LogoutCoordinator.markProviderTaskStarted(a, "task")
        val b = LogoutCoordinator.startLogout()
        LogoutCoordinator.onCleanupCompleted(b)
        LogoutCoordinator.onCleanupCompleted(a)
        assertFalse(LogoutCoordinator.awaitProviderCleanup(1))
        LogoutCoordinator.markProviderTaskCompleted(a, "task")
        assertTrue(LogoutCoordinator.awaitProviderCleanup(1))
    }
}
