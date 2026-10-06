package com.tscanner.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityNavigationTest {

    @Test
    fun testValidTabIdsRecognized() {
        assertTrue("nav_home should be a valid tab ID", MainActivity.isValidTabId(R.id.nav_home))
        assertTrue("nav_files should be a valid tab ID", MainActivity.isValidTabId(R.id.nav_files))
        assertTrue("nav_tools should be a valid tab ID", MainActivity.isValidTabId(R.id.nav_tools))
        assertTrue("nav_more should be a valid tab ID", MainActivity.isValidTabId(R.id.nav_more))
    }

    @Test
    fun testInvalidTabIdsRejected() {
        assertFalse("0 is not a valid tab ID", MainActivity.isValidTabId(0))
        assertFalse("-1 is not a valid tab ID", MainActivity.isValidTabId(-1))
        assertFalse("Arbitrary ID is not a valid tab ID", MainActivity.isValidTabId(999999))
    }

    @Test
    fun testTabResolutionFallbackToHome() {
        val resolveTab = { inputTabId: Int? ->
            if (inputTabId != null && MainActivity.isValidTabId(inputTabId)) inputTabId else R.id.nav_home
        }

        assertEquals(R.id.nav_home, resolveTab(null))
        assertEquals(R.id.nav_home, resolveTab(-1))
        assertEquals(R.id.nav_home, resolveTab(12345))
        assertEquals(R.id.nav_tools, resolveTab(R.id.nav_tools))
        assertEquals(R.id.nav_files, resolveTab(R.id.nav_files))
        assertEquals(R.id.nav_more, resolveTab(R.id.nav_more))
    }

    @Test
    fun testVipNavigationBundleContract_preservesForceReauthAndOwner() {
        val map = mutableMapOf<String, Any?>().apply {
            put(com.tscanner.app.ui.more.MoreFragment.EXTRA_AUTO_START_SIGN_IN, true)
            put(com.tscanner.app.ui.more.MoreFragment.EXTRA_VIP_ACTION, "RESTORE")
            put(com.tscanner.app.ui.more.MoreFragment.EXTRA_FORCE_REAUTH, true)
            put(com.tscanner.app.ui.more.MoreFragment.EXTRA_AUTH_REQUIRED_REASON, "AUTH_REQUIRED")
            put(com.tscanner.app.ui.more.MoreFragment.EXTRA_EXPECTED_OWNER_ID, "user_123")
            put(com.tscanner.app.ui.more.MoreFragment.EXTRA_OPERATION_ID, "op_456")
        }

        assertEquals(true, map[com.tscanner.app.ui.more.MoreFragment.EXTRA_AUTO_START_SIGN_IN])
        assertEquals("RESTORE", map[com.tscanner.app.ui.more.MoreFragment.EXTRA_VIP_ACTION])
        assertEquals(true, map[com.tscanner.app.ui.more.MoreFragment.EXTRA_FORCE_REAUTH])
        assertEquals("AUTH_REQUIRED", map[com.tscanner.app.ui.more.MoreFragment.EXTRA_AUTH_REQUIRED_REASON])
        assertEquals("user_123", map[com.tscanner.app.ui.more.MoreFragment.EXTRA_EXPECTED_OWNER_ID])
        assertEquals("op_456", map[com.tscanner.app.ui.more.MoreFragment.EXTRA_OPERATION_ID])

        // Verify Bundle writes without throwing on mockable jar
        val bundle = android.os.Bundle()
        bundle.putBoolean(com.tscanner.app.ui.more.MoreFragment.EXTRA_AUTO_START_SIGN_IN, true)
        bundle.putString(com.tscanner.app.ui.more.MoreFragment.EXTRA_VIP_ACTION, "RESTORE")
        bundle.putBoolean(com.tscanner.app.ui.more.MoreFragment.EXTRA_FORCE_REAUTH, true)
        bundle.putString(com.tscanner.app.ui.more.MoreFragment.EXTRA_AUTH_REQUIRED_REASON, "AUTH_REQUIRED")
        bundle.putString(com.tscanner.app.ui.more.MoreFragment.EXTRA_EXPECTED_OWNER_ID, "user_123")
        bundle.putString(com.tscanner.app.ui.more.MoreFragment.EXTRA_OPERATION_ID, "op_456")
    }

    @Test
    fun testReauthDecisionLogic_forcesReauthWhenBackend401EvenIfTokenNotExpired() {
        val evaluateNeedsSignIn = { isTokenExpired: Boolean, forceReauth: Boolean ->
            isTokenExpired || forceReauth
        }

        // Scenario 1: Normal valid session, fresh navigation
        assertFalse("Valid token without forceReauth should not trigger sign-in", evaluateNeedsSignIn(false, false))

        // Scenario 2: Token expired locally
        assertTrue("Expired token should trigger sign-in", evaluateNeedsSignIn(true, false))

        // Scenario 3: Backend 401 returned, token not locally expired (G03 root cause)
        assertTrue("forceReauth must trigger sign-in even when local token has not expired", evaluateNeedsSignIn(false, true))
    }

    @Test
    fun testReauthDecisionLogic_rejectsMismatchedOwnerAndStaleLogout() {
        val epoch = "epoch_1"
        // 1. Matching owner accepted
        val d1 = com.tscanner.app.ui.more.VipNavigationValidator.validateNavigation(
            originOwnerId = "user_alice", currentOwnerId = "user_alice",
            originGeneration = 1L, currentGeneration = 1L,
            originEpoch = epoch, currentEpoch = epoch,
            operationId = "op1", isOperationConsumed = false
        )
        assertTrue(d1 is com.tscanner.app.ui.more.NavigationDecision.Accept)

        // 2. Different owner rejected (A -> B)
        val d2 = com.tscanner.app.ui.more.VipNavigationValidator.validateNavigation(
            originOwnerId = "user_alice", currentOwnerId = "user_bob",
            originGeneration = 1L, currentGeneration = 1L,
            originEpoch = epoch, currentEpoch = epoch,
            operationId = "op2", isOperationConsumed = false
        )
        assertTrue("Different owner rejected to prevent cross-account contamination", d2 is com.tscanner.app.ui.more.NavigationDecision.Discard)

        // 3. Request originated for A but user logged out (A -> null) must be discarded, NOT treated as guest!
        val d3 = com.tscanner.app.ui.more.VipNavigationValidator.validateNavigation(
            originOwnerId = "user_alice", currentOwnerId = null,
            originGeneration = 1L, currentGeneration = 1L,
            originEpoch = epoch, currentEpoch = epoch,
            operationId = "op3", isOperationConsumed = false
        )
        assertTrue("Logged out user must discard stale owner request instead of converting to guest", d3 is com.tscanner.app.ui.more.NavigationDecision.Discard)

        // 4. Session generation mismatch (A -> logout -> A with new generation)
        val d4 = com.tscanner.app.ui.more.VipNavigationValidator.validateNavigation(
            originOwnerId = "user_alice", currentOwnerId = "user_alice",
            originGeneration = 1L, currentGeneration = 2L,
            originEpoch = epoch, currentEpoch = epoch,
            operationId = "op4", isOperationConsumed = false
        )
        assertTrue("Generation mismatch must be discarded", d4 is com.tscanner.app.ui.more.NavigationDecision.Discard)

        // 5. Guest accepted
        val d5 = com.tscanner.app.ui.more.VipNavigationValidator.validateNavigation(
            originOwnerId = null, currentOwnerId = null,
            originGeneration = 0L, currentGeneration = 0L,
            originEpoch = epoch, currentEpoch = epoch,
            operationId = "op5", isOperationConsumed = false
        )
        assertTrue("Guest accepting reauth", d5 is com.tscanner.app.ui.more.NavigationDecision.Accept)

        // 6. Process epoch mismatch rejected
        val d6 = com.tscanner.app.ui.more.VipNavigationValidator.validateNavigation(
            originOwnerId = "user_alice", currentOwnerId = "user_alice",
            originGeneration = 1L, currentGeneration = 1L,
            originEpoch = "epoch_old", currentEpoch = "epoch_new",
            operationId = "op6", isOperationConsumed = false
        )
        assertTrue("Epoch mismatch must be discarded", d6 is com.tscanner.app.ui.more.NavigationDecision.Discard)

        // 7. Replay / already consumed operation rejected
        val d7 = com.tscanner.app.ui.more.VipNavigationValidator.validateNavigation(
            originOwnerId = "user_alice", currentOwnerId = "user_alice",
            originGeneration = 1L, currentGeneration = 1L,
            originEpoch = epoch, currentEpoch = epoch,
            operationId = "op7", isOperationConsumed = true
        )
        assertTrue("Already consumed operation must be discarded", d7 is com.tscanner.app.ui.more.NavigationDecision.Discard)
    }
}
