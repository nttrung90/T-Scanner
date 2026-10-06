package com.tscanner.app

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.ui.dialogs.PurchaseAuthDecision
import com.tscanner.app.ui.dialogs.VipPurchaseAuthConsumer
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.VipContinuationAction
import com.tscanner.app.utils.VipLoginContinuationHandler
import com.tscanner.app.utils.billing.PurchaseVerifier
import com.tscanner.app.utils.billing.VerificationRequest
import com.tscanner.app.utils.billing.VerificationResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Integration test contract for Round 3 (T00):
 * 1. receiptAuthRequiredRequestsSameOwnerRecoveryWithoutNewPurchase (H01)
 * 2. fallbackLaunchFailureAllowsRetryOnSameHost (H03)
 * 3. navigationFromOwnerARejectedAfterLogoutAndOnReplay (H04)
 */
class VipSessionRound3IntegrationTest {

    @Before
    fun setup() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread() = true
        })
        AppAuthManager.resetForTesting()
        BillingManager.resetInstanceForTesting()
        VipPurchaseAuthConsumer.resetForTesting()
    }

    @After
    fun cleanup() {
        BillingManager.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
        VipPurchaseAuthConsumer.resetForTesting()
        ArchTaskExecutor.getInstance().setDelegate(null)
    }

    /**
     * T00 Test 1 Contract (H01):
     * When backend verification returns AuthRequired for an existing purchase receipt,
     * the system must emit a typed auth-recovery event bound to the receipt and current owner A.
     * It must not drop into generic purchase failure without recovery, and must never launch a new purchase flow.
     */
    @Test
    fun receiptAuthRequiredRequestsSameOwnerRecoveryWithoutNewPurchase_contractSpecification() {
        val testDir = File("build/tmp/test_r3_h01").apply { mkdirs() }
        val testContext = BillingTestContext(testDir)
        val fakeClient = FakeBillingClientWrapper().apply { isReadyValue = true }
        val clientProvider = BillingManager.BillingClientProvider { fakeClient }

        AppAuthManager.setCurrentUserForTesting(UserProfile("owner_A", "owner_A@example.com", "A", idToken = "fresh_token"))

        var verifierAuthRequired = true
        val fakeVerifier = object : PurchaseVerifier {
            override suspend fun verifyPurchase(request: VerificationRequest): VerificationResult {
                return if (verifierAuthRequired) {
                    VerificationResult.AuthRequired("Session expired on backend")
                } else {
                    VerificationResult.Success(
                        com.tscanner.app.utils.billing.BillingEntitlement(
                            id = request.purchaseToken,
                            ownerAppUserId = request.ownerAppUserId,
                            productId = request.productId,
                            productType = request.productType,
                            purchaseToken = request.purchaseToken,
                            source = com.tscanner.app.utils.billing.EntitlementSource.GOOGLE_PLAY_SUBSCRIPTION,
                            state = com.tscanner.app.utils.billing.EntitlementState.VERIFIED_ACTIVE,
                            purchaseTimeMillis = System.currentTimeMillis()
                        )
                    )
                }
            }
        }

        val billingManager = BillingManager.createInstanceForTesting(
            context = testContext,
            clientProvider = clientProvider,
            verifier = fakeVerifier
        )

        var typedEventReceived: BillingManager.PurchaseAuthRequiredEvent? = null
        var genericFailureReceived: String? = null
        billingManager.addAuthRequiredListener { typedEventReceived = it }
        billingManager.addPurchaseCallback { success, message, _ ->
            if (!success) genericFailureReceived = message
        }

        val testPurchase = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "receipt_token_123")
        billingManager.setActivePurchaseOwnerForTesting("owner_A")

        // 1. Process purchase with AuthRequired from backend
        billingManager.processPurchase(testPurchase, origin = com.tscanner.app.utils.billing.BillingOperationOrigin.PURCHASE)

        // Assertions:
        // - Typed event received
        org.junit.Assert.assertNotNull("Typed AuthRequired event must be dispatched to listener", typedEventReceived)
        assertEquals("owner_A", typedEventReceived?.targetOwnerId)
        assertEquals(false, typedEventReceived?.isRetry)
        assertEquals("Session expired on backend", typedEventReceived?.message)

        // - No generic failure toast/callback emitted to typed listener
        org.junit.Assert.assertNull("Generic failure callback must not be dispatched when typed listener is present", genericFailureReceived)

        // - 0 additional launchBillingFlow calls
        org.junit.Assert.assertNull("0 additional launchBillingFlow calls", fakeClient.lastLaunchedParams)

        // - VIP is NOT granted prior to verify success
        assertFalse("VIP must not be granted prematurely", AppAuthManager.isUserVip())

        // Round 6 W00 migration: Consumer evaluates first interactive event and provider commits acceptance
        val firstDecision = VipPurchaseAuthConsumer.evaluate(
            event = typedEventReceived!!,
            currentOwnerId = AppAuthManager.getCurrentUser()?.id,
            currentGeneration = AppAuthManager.getSessionGeneration(),
            currentEpoch = AppAuthManager.getProcessEpoch(),
            isUiActive = true
        )
        assertTrue("First interactive recovery must yield RequestReauth", firstDecision is PurchaseAuthDecision.RequestReauth)
        (firstDecision as PurchaseAuthDecision.RequestReauth).confirmStarted()

        // 2. Second AuthRequired on same purchase receipt terminates without infinite loop
        var secondTypedEvent: BillingManager.PurchaseAuthRequiredEvent? = null
        billingManager.addAuthRequiredListener { secondTypedEvent = it }
        billingManager.processPurchase(testPurchase, origin = com.tscanner.app.utils.billing.BillingOperationOrigin.PURCHASE)
        org.junit.Assert.assertNotNull("Second call dispatches event marked isRetry=true", secondTypedEvent)
        assertTrue(secondTypedEvent!!.isRetry)
        val secondDecision = VipPurchaseAuthConsumer.evaluate(
            event = secondTypedEvent!!,
            currentOwnerId = AppAuthManager.getCurrentUser()?.id,
            currentGeneration = AppAuthManager.getSessionGeneration(),
            currentEpoch = AppAuthManager.getProcessEpoch(),
            isUiActive = true
        )
        assertTrue("Second AuthRequired for same operation/receipt must yield Stop", secondDecision is PurchaseAuthDecision.Stop)

        // 3. Post-reauth recovery: verifier succeeds, restore recovers the receipt
        verifierAuthRequired = false
        fakeClient.addPurchase(testPurchase)

        var restoreCompleted = false
        billingManager.restorePurchases { success, _ ->
            restoreCompleted = true
        }

        assertTrue("Restore must complete successfully", restoreCompleted)
        assertTrue("VIP entitlement must now be active for owner_A", AppAuthManager.isUserVip())
        org.junit.Assert.assertNull("0 launchBillingFlow calls during restore/recovery", fakeClient.lastLaunchedParams)

        testDir.deleteRecursively()
    }

    /**
     * T00 Test 2 Contract (H03):
     * When Google sign-in fallback intent or launcher throws an exception,
     * the continuation state must be safely cancelled/cleared so subsequent clicks are accepted.
     */
    @Test
    fun fallbackLaunchFailureAllowsRetryOnSameHost_contractSpecification() {
        val handler = VipLoginContinuationHandler()
        val attempt1 = AppAuthManager.createSignInAttemptForTesting()
        handler.requestContinuation(VipContinuationAction.UPGRADE, AppAuthManager.getSessionGeneration())
        handler.bindAttempt(attempt1)
        assertTrue("Handler must be pending before launch", handler.isPending)

        // Simulate fallback launch failure on attempt1
        AppAuthManager.cancelSignInProgress(attempt1)
        handler.onSignInError(attempt1.requestId)

        assertFalse("Handler must reset isPending on launch error so retry is possible", handler.isPending)
        assertFalse("Auth progress must not be busy after cancel", AppAuthManager.isSignInInProgressForTesting())

        // Retry click creates a new attempt and continuation
        val attempt2 = AppAuthManager.createSignInAttemptForTesting()
        handler.requestContinuation(VipContinuationAction.UPGRADE, AppAuthManager.getSessionGeneration())
        handler.bindAttempt(attempt2)
        assertTrue("Retry continuation must be accepted and pending", handler.isPending)
        assertEquals(attempt2.requestId, handler.originatingRequestId)

        // Stale error from attempt1 must not cancel attempt2
        handler.onSignInError(attempt1.requestId)
        assertTrue("Stale error must not cancel attempt2", handler.isPending)

        // Valid error from attempt2 resets continuation
        handler.onSignInError(attempt2.requestId)
        assertFalse("Matching error cancels attempt2", handler.isPending)
    }

    /**
     * T00 Test 3 Contract (H04):
     * A navigation request originating for owner A must be rejected if the user logs out
     * (current user becomes null) or if generation/epoch mismatches or replay occurs.
     */
    @Test
    fun navigationFromOwnerARejectedAfterLogoutAndOnReplay_contractSpecification() {
        val currentEpoch = AppAuthManager.getProcessEpoch()
        val currentGen = AppAuthManager.getSessionGeneration()

        // 1. Origin A accepted when user is A
        val acceptResult = com.tscanner.app.ui.more.VipNavigationValidator.validateNavigation(
            originOwnerId = "owner_A",
            currentOwnerId = "owner_A",
            originGeneration = currentGen,
            currentGeneration = currentGen,
            originEpoch = currentEpoch,
            currentEpoch = currentEpoch,
            operationId = "op_test_1",
            isOperationConsumed = false
        )
        assertTrue("Origin owner A matches current owner A -> Accepted", acceptResult is com.tscanner.app.ui.more.NavigationDecision.Accept)

        // 2. Origin A rejected when user logged out (currentOwnerId == null) -> Discard, NOT guest login
        val loggedOutResult = com.tscanner.app.ui.more.VipNavigationValidator.validateNavigation(
            originOwnerId = "owner_A",
            currentOwnerId = null,
            originGeneration = currentGen,
            currentGeneration = currentGen + 1L,
            originEpoch = currentEpoch,
            currentEpoch = currentEpoch,
            operationId = "op_test_2",
            isOperationConsumed = false
        )
        assertTrue("Navigation for owner A must be discarded after logout", loggedOutResult is com.tscanner.app.ui.more.NavigationDecision.Discard)

        // 3. Replayed operation (already consumed) rejected
        val replayResult = com.tscanner.app.ui.more.VipNavigationValidator.validateNavigation(
            originOwnerId = "owner_A",
            currentOwnerId = "owner_A",
            originGeneration = currentGen,
            currentGeneration = currentGen,
            originEpoch = currentEpoch,
            currentEpoch = currentEpoch,
            operationId = "op_test_1",
            isOperationConsumed = true
        )
        assertTrue("Replayed operation must be discarded", replayResult is com.tscanner.app.ui.more.NavigationDecision.Discard)

        // 4. Owner switch A -> B rejected
        val ownerSwitchResult = com.tscanner.app.ui.more.VipNavigationValidator.validateNavigation(
            originOwnerId = "owner_A",
            currentOwnerId = "owner_B",
            originGeneration = currentGen,
            currentGeneration = currentGen,
            originEpoch = currentEpoch,
            currentEpoch = currentEpoch,
            operationId = "op_test_3",
            isOperationConsumed = false
        )
        assertTrue("Owner switch A -> B must be discarded", ownerSwitchResult is com.tscanner.app.ui.more.NavigationDecision.Discard)

        // 5. Epoch mismatch rejected
        val epochMismatchResult = com.tscanner.app.ui.more.VipNavigationValidator.validateNavigation(
            originOwnerId = "owner_A",
            currentOwnerId = "owner_A",
            originGeneration = currentGen,
            currentGeneration = currentGen,
            originEpoch = "epoch_old",
            currentEpoch = currentEpoch,
            operationId = "op_test_4",
            isOperationConsumed = false
        )
        assertTrue("Epoch mismatch across process restart must be discarded", epochMismatchResult is com.tscanner.app.ui.more.NavigationDecision.Discard)
    }
}
