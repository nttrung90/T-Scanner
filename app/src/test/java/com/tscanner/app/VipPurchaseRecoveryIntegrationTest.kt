package com.tscanner.app

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.ui.dialogs.PurchaseAuthDecision
import com.tscanner.app.ui.dialogs.VipPurchaseAuthConsumer
import com.tscanner.app.utils.*
import com.tscanner.app.utils.billing.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VipPurchaseRecoveryIntegrationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var billingManager: BillingManager
    private lateinit var fakeClient: FakeBillingClientWrapper
    private lateinit var continuationHandler: VipLoginContinuationHandler
    private var verifierResult: VerificationResult = VerificationResult.AuthRequired("Session expired on backend")

    @Before
    fun setup() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread() = true
        })
        AppAuthManager.resetForTesting()
        AppAuthManager.setCurrentUserForTesting(UserProfile("owner_A", "a@example.com", "A"))
        VipPurchaseAuthConsumer.resetForTesting()

        fakeClient = FakeBillingClientWrapper()
        billingManager = BillingManager.createInstanceForTesting(
            BillingTestContext(folder.root),
            clientProvider = { fakeClient },
            verifier = object : PurchaseVerifier {
                override suspend fun verifyPurchase(request: VerificationRequest) = verifierResult
            }
        )
        continuationHandler = VipLoginContinuationHandler()
    }

    @After
    fun cleanup() {
        BillingManager.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
        VipPurchaseAuthConsumer.resetForTesting()
        ArchTaskExecutor.getInstance().setDelegate(null)
    }

    private fun createOperation(ownerId: String = "owner_A"): BillingOperationContext {
        return BillingOperationContext(
            ownerAppUserId = ownerId,
            sessionGeneration = AppAuthManager.getSessionGeneration(),
            operationType = BillingOperationType.PURCHASE
        )
    }

    @Test
    fun contract1_inactiveUiThenActive_acceptedStartsZeroToOne() {
        val op = createOperation()
        val receipt = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "receipt_inactive_1")
        var isUiActive = false
        var acceptedStarts = 0

        billingManager.addAuthRequiredListener { event ->
            val decision = VipPurchaseAuthConsumer.evaluate(
                event = event,
                currentOwnerId = AppAuthManager.getCurrentUser()?.id,
                currentGeneration = AppAuthManager.getSessionGeneration(),
                currentEpoch = AppAuthManager.getProcessEpoch(),
                isUiActive = isUiActive
            )
            if (decision is PurchaseAuthDecision.RequestReauth) {
                decision.confirmStarted()
                acceptedStarts++
            }
        }

        // 1st emit while inactive
        billingManager.processPurchase(receipt, BillingOperationOrigin.PURCHASE, op)
        assertEquals("Inactive UI must not start provider recovery", 0, acceptedStarts)

        // 2nd emit after UI becomes active in same operation
        isUiActive = true
        billingManager.processPurchase(receipt, BillingOperationOrigin.PURCHASE, op)
        assertEquals("Active retry in same operation must start provider recovery", 1, acceptedStarts)
    }

    @Test
    fun contract2_providerBusyRefusalThenAvailable_acceptedStartsZeroToOne() {
        val op = createOperation()
        val receipt = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "receipt_busy_1")
        var isProviderBusy = true
        var acceptedStarts = 0

        billingManager.addAuthRequiredListener { event ->
            val decision = VipPurchaseAuthConsumer.evaluate(
                event = event,
                currentOwnerId = AppAuthManager.getCurrentUser()?.id,
                currentGeneration = AppAuthManager.getSessionGeneration(),
                currentEpoch = AppAuthManager.getProcessEpoch(),
                isUiActive = true
            )
            if (decision is PurchaseAuthDecision.RequestReauth) {
                if (isProviderBusy) {
                    decision.release() // Host / provider busy: release reservation
                } else {
                    decision.confirmStarted()
                    acceptedStarts++
                }
            }
        }

        // 1st emit: provider busy
        billingManager.processPurchase(receipt, BillingOperationOrigin.PURCHASE, op)
        assertEquals("Busy provider must not increment accepted starts", 0, acceptedStarts)

        // 2nd emit: provider becomes available
        isProviderBusy = false
        billingManager.processPurchase(receipt, BillingOperationOrigin.PURCHASE, op)
        assertEquals("Available provider receives recovery after prior refusal", 1, acceptedStarts)
    }

    @Test
    fun contract3_duplicateConsumers_onlyOneProviderInvocationAllowed() {
        val op = createOperation()
        val receipt = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "receipt_dup_1")
        var invocationCount = 0

        val listener1 = { event: BillingManager.PurchaseAuthRequiredEvent ->
            val decision = VipPurchaseAuthConsumer.evaluate(
                event = event,
                currentOwnerId = AppAuthManager.getCurrentUser()?.id,
                currentGeneration = AppAuthManager.getSessionGeneration(),
                currentEpoch = AppAuthManager.getProcessEpoch(),
                isUiActive = true
            )
            if (decision is PurchaseAuthDecision.RequestReauth) {
                decision.confirmStarted()
                invocationCount++
            }
        }

        val listener2 = { event: BillingManager.PurchaseAuthRequiredEvent ->
            val decision = VipPurchaseAuthConsumer.evaluate(
                event = event,
                currentOwnerId = AppAuthManager.getCurrentUser()?.id,
                currentGeneration = AppAuthManager.getSessionGeneration(),
                currentEpoch = AppAuthManager.getProcessEpoch(),
                isUiActive = true
            )
            if (decision is PurchaseAuthDecision.RequestReauth) {
                decision.confirmStarted()
                invocationCount++
            }
        }

        billingManager.addAuthRequiredListener(listener1)
        billingManager.addAuthRequiredListener(listener2)

        billingManager.processPurchase(receipt, BillingOperationOrigin.PURCHASE, op)
        assertEquals("Exactly 1 provider invocation allowed across duplicate consumers", 1, invocationCount)
    }

    @Test
    fun contract4_acceptedReauthToSuccess_restoresOnceWithZeroExtraLaunch() {
        val op = createOperation()
        val receipt = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "receipt_success_1")
        fakeClient.addPurchase(receipt)

        var providerStarted = false
        var capturedOpContext: BillingOperationContext? = null

        billingManager.addAuthRequiredListener { event ->
            val decision = VipPurchaseAuthConsumer.evaluate(
                event = event,
                currentOwnerId = AppAuthManager.getCurrentUser()?.id,
                currentGeneration = AppAuthManager.getSessionGeneration(),
                currentEpoch = AppAuthManager.getProcessEpoch(),
                isUiActive = true
            )
            if (decision is PurchaseAuthDecision.RequestReauth) {
                decision.confirmStarted()
                providerStarted = true
                capturedOpContext = decision.operationContext

                // Production Host flow: request continuation with captured context
                continuationHandler.requestContinuation(
                    action = decision.action,
                    sessionGeneration = AppAuthManager.getSessionGeneration(),
                    initialOwnerId = decision.targetOwnerId,
                    processEpoch = AppAuthManager.getProcessEpoch(),
                    originatingOperationContext = decision.operationContext
                )
            }
        }

        // Step 1: Process purchase with 401
        billingManager.processPurchase(receipt, BillingOperationOrigin.PURCHASE, op)
        assertTrue("Provider must be started", providerStarted)
        assertNotNull("Operation context must be preserved", capturedOpContext)
        assertEquals(op.operationId, capturedOpContext?.operationId)
        assertNull("0 billing flow launch so far", fakeClient.lastLaunchedParams)

        verifierResult = VerificationResult.Success(
            entitlement = com.tscanner.app.utils.billing.BillingEntitlement(
                id = receipt.purchaseToken,
                ownerAppUserId = "owner_A",
                productId = BillingManager.PRODUCT_VIP_YEARLY,
                productType = "subs",
                purchaseToken = receipt.purchaseToken,
                source = com.tscanner.app.utils.billing.EntitlementSource.GOOGLE_PLAY_SUBSCRIPTION,
                state = com.tscanner.app.utils.billing.EntitlementState.VERIFIED_ACTIVE,
                purchaseTimeMillis = System.currentTimeMillis()
            )
        )

        var restoreCallCount = 0
        var restoredSuccess = false
        val currentGen = AppAuthManager.getSessionGeneration()
        val currentUser = AppAuthManager.getCurrentUser()?.id

        continuationHandler.onSignInSuccessWithAction(currentGen, currentUser, -1L) { action, _, restoredContext ->
            if (action == VipContinuationAction.RESTORE) {
                restoreCallCount++
                billingManager.restorePurchases(opContext = restoredContext) { success, _ ->
                    restoredSuccess = success
                }
            }
        }

        assertEquals("Restore must be invoked exactly once", 1, restoreCallCount)
        assertTrue("Restore must complete successfully", restoredSuccess)
        assertNull("0 extra launchBillingFlow during restore", fakeClient.lastLaunchedParams)
        assertTrue("VIP entitlement active", AppAuthManager.isUserVip())

        // Step 3: Repeated 401 in same operation returns Stop (no infinite loop)
        val repeatEvent = BillingManager.PurchaseAuthRequiredEvent(
            purchase = receipt,
            message = "Repeated 401",
            targetOwnerId = "owner_A",
            sessionGeneration = currentGen,
            processEpoch = AppAuthManager.getProcessEpoch(),
            isRetry = true,
            operationContext = capturedOpContext
        )
        val repeatDecision = VipPurchaseAuthConsumer.evaluate(
            event = repeatEvent,
            currentOwnerId = currentUser,
            currentGeneration = currentGen,
            currentEpoch = AppAuthManager.getProcessEpoch(),
            isUiActive = true
        )
        assertTrue("Repeated 401 in same operation must Stop", repeatDecision is PurchaseAuthDecision.Stop)
    }

    @Test
    fun contract5_cancelOrFailureThenNewOperation_allowsRetryAndStaleCallbackSafe() {
        val op1 = createOperation()
        val receipt = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "receipt_cancel_1")

        var latestDecision: PurchaseAuthDecision.RequestReauth? = null
        billingManager.addAuthRequiredListener { event ->
            val decision = VipPurchaseAuthConsumer.evaluate(
                event = event,
                currentOwnerId = AppAuthManager.getCurrentUser()?.id,
                currentGeneration = AppAuthManager.getSessionGeneration(),
                currentEpoch = AppAuthManager.getProcessEpoch(),
                isUiActive = true
            )
            if (decision is PurchaseAuthDecision.RequestReauth) {
                latestDecision = decision
            }
        }

        billingManager.processPurchase(receipt, BillingOperationOrigin.PURCHASE, op1)
        val firstDecision = latestDecision
        assertNotNull("First operation must receive RequestReauth", firstDecision)

        // User cancels / launch fails -> release first reservation
        firstDecision?.release()

        // New manual user operation
        val op2 = createOperation()
        assertNotEquals(op1.operationId, op2.operationId)

        latestDecision = null
        billingManager.processPurchase(receipt, BillingOperationOrigin.PURCHASE, op2)
        val secondDecision = latestDecision
        assertNotNull("New operation must receive fresh RequestReauth", secondDecision)
        secondDecision?.confirmStarted()

        // Stale late callback from op1 tries to release
        firstDecision?.release()

        // Reservation of op2 must NOT be corrupted
        val op2Key = "${op2.operationId}:owner_A:${op2.sessionGeneration}:${AppAuthManager.getProcessEpoch()}:${receipt.purchaseToken}"
        assertEquals(
            VipPurchaseAuthConsumer.ReservationState.STARTED,
            VipPurchaseAuthConsumer.getReservationState(op2Key)
        )
    }

    @Test
    fun contract6_ownerOrGenerationChange_suppressesStaleAuthAndRestore() {
        val op = createOperation("owner_A")
        val receipt = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "receipt_switch_1")

        var staleDelivered = 0
        billingManager.addAuthRequiredListener { event ->
            // Simulate user account switch before evaluation
            AppAuthManager.nextSessionGeneration()
            AppAuthManager.setCurrentUserForTesting(UserProfile("owner_B", "b@example.com", "B"))

            val decision = VipPurchaseAuthConsumer.evaluate(
                event = event,
                currentOwnerId = AppAuthManager.getCurrentUser()?.id,
                currentGeneration = AppAuthManager.getSessionGeneration(),
                currentEpoch = AppAuthManager.getProcessEpoch(),
                isUiActive = true
            )
            if (decision is PurchaseAuthDecision.RequestReauth) {
                staleDelivered++
            }
        }

        billingManager.processPurchase(receipt, BillingOperationOrigin.PURCHASE, op)
        assertEquals("Mismatched owner/generation must not deliver RequestReauth", 0, staleDelivered)
    }

    @Test
    fun contract7_guestUpgradeToFirstAccount_validContinuation() {
        AppAuthManager.setCurrentUserForTesting(null) // Guest
        val guestGen = AppAuthManager.getSessionGeneration()

        continuationHandler.requestContinuation(
            action = VipContinuationAction.UPGRADE,
            sessionGeneration = guestGen,
            initialOwnerId = null,
            processEpoch = AppAuthManager.getProcessEpoch()
        )

        // Guest signs in: owner becomes A, gen increments to guestGen + 1
        AppAuthManager.setCurrentUserForTesting(UserProfile("owner_A", "a@example.com", "A"))
        val newGen = AppAuthManager.getSessionGeneration()
        assertEquals(guestGen + 1L, newGen)

        var actionExecuted: VipContinuationAction? = null
        continuationHandler.onSignInSuccessWithAction(newGen, "owner_A", -1L) { action, _ ->
            actionExecuted = action
        }

        assertEquals("Guest upgrade must legitimately continue to UPGRADE after sign in", VipContinuationAction.UPGRADE, actionExecuted)
        assertFalse("VIP not granted prematurely", AppAuthManager.isUserVip())
    }
}
