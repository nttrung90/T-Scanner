package com.tscanner.app

import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.ui.dialogs.PurchaseAuthDecision
import com.tscanner.app.ui.dialogs.VipPurchaseAuthConsumer
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.VipContinuationAction
import com.tscanner.app.utils.billing.BillingOperationContext
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VipPurchaseAuthConsumerTest {

    private val testReceipt by lazy {
        createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "test_purchase_token_123")
    }

    @Before
    fun setup() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread() = true
        })
        AppAuthManager.resetForTesting()
        AppAuthManager.setCurrentUserForTesting(UserProfile("user_A", "a@example.com", "A"))
        VipPurchaseAuthConsumer.resetForTesting()
    }

    @After
    fun cleanup() {
        BillingManager.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
        VipPurchaseAuthConsumer.resetForTesting()
        ArchTaskExecutor.getInstance().setDelegate(null)
    }

    private fun createEvent(
        opContext: BillingOperationContext? = BillingOperationContext(
            ownerAppUserId = "user_A",
            sessionGeneration = AppAuthManager.getSessionGeneration()
        ),
        targetOwnerId: String? = "user_A",
        sessionGen: Long = AppAuthManager.getSessionGeneration(),
        processEpoch: String = AppAuthManager.getProcessEpoch(),
        isRetry: Boolean = false,
        onRelease: (() -> Unit)? = null
    ): BillingManager.PurchaseAuthRequiredEvent {
        return BillingManager.PurchaseAuthRequiredEvent(
            purchase = testReceipt,
            message = "Auth required",
            targetOwnerId = targetOwnerId,
            sessionGeneration = sessionGen,
            processEpoch = processEpoch,
            isRetry = isRetry,
            operationContext = opContext,
            onReleaseAttempt = onRelease
        )
    }

    @Test
    fun inactiveUi_defersAndReleasesAttempt() {
        var released = false
        val event = createEvent(onRelease = { released = true })

        val decision = VipPurchaseAuthConsumer.evaluate(
            event = event,
            currentOwnerId = "user_A",
            currentGeneration = AppAuthManager.getSessionGeneration(),
            currentEpoch = AppAuthManager.getProcessEpoch(),
            isUiActive = false
        )

        assertTrue(decision is PurchaseAuthDecision.Defer)
        assertTrue(released)
        assertNull(VipPurchaseAuthConsumer.getReservationState(event.recoveryKey))
    }

    @Test
    fun ownerMismatch_ignoresAndReleasesAttempt() {
        var released = false
        val event = createEvent(targetOwnerId = "user_B", onRelease = { released = true })

        val decision = VipPurchaseAuthConsumer.evaluate(
            event = event,
            currentOwnerId = "user_A",
            currentGeneration = AppAuthManager.getSessionGeneration(),
            currentEpoch = AppAuthManager.getProcessEpoch(),
            isUiActive = true
        )

        assertTrue(decision is PurchaseAuthDecision.Ignore)
        assertTrue(released)
    }

    @Test
    fun sessionGenMismatch_ignoresAndReleasesAttempt() {
        var released = false
        val event = createEvent(sessionGen = 999L, onRelease = { released = true })

        val decision = VipPurchaseAuthConsumer.evaluate(
            event = event,
            currentOwnerId = "user_A",
            currentGeneration = AppAuthManager.getSessionGeneration(),
            currentEpoch = AppAuthManager.getProcessEpoch(),
            isUiActive = true
        )

        assertTrue(decision is PurchaseAuthDecision.Ignore)
        assertTrue(released)
    }

    @Test
    fun atomicClaim_secondConsumerGetsIgnored() {
        val event = createEvent()

        val decision1 = VipPurchaseAuthConsumer.evaluate(
            event = event,
            currentOwnerId = "user_A",
            currentGeneration = AppAuthManager.getSessionGeneration(),
            currentEpoch = AppAuthManager.getProcessEpoch(),
            isUiActive = true
        )

        val decision2 = VipPurchaseAuthConsumer.evaluate(
            event = event,
            currentOwnerId = "user_A",
            currentGeneration = AppAuthManager.getSessionGeneration(),
            currentEpoch = AppAuthManager.getProcessEpoch(),
            isUiActive = true
        )

        assertTrue(decision1 is PurchaseAuthDecision.RequestReauth)
        assertTrue(decision2 is PurchaseAuthDecision.Ignore)
    }

    @Test
    fun releaseReservation_allowsNextConsumerToClaim() {
        var releasedCalled = false
        val event = createEvent(onRelease = { releasedCalled = true })

        val decision1 = VipPurchaseAuthConsumer.evaluate(
            event = event,
            currentOwnerId = "user_A",
            currentGeneration = AppAuthManager.getSessionGeneration(),
            currentEpoch = AppAuthManager.getProcessEpoch(),
            isUiActive = true
        ) as PurchaseAuthDecision.RequestReauth

        // Provider busy -> release
        decision1.release()
        assertTrue(releasedCalled)
        assertNull(VipPurchaseAuthConsumer.getReservationState(event.recoveryKey))

        // Next evaluation can now claim
        val decision2 = VipPurchaseAuthConsumer.evaluate(
            event = event,
            currentOwnerId = "user_A",
            currentGeneration = AppAuthManager.getSessionGeneration(),
            currentEpoch = AppAuthManager.getProcessEpoch(),
            isUiActive = true
        )
        assertTrue(decision2 is PurchaseAuthDecision.RequestReauth)
    }

    @Test
    fun confirmStarted_blocksSubsequentAttemptsForSameOperation() {
        val event = createEvent()

        val decision1 = VipPurchaseAuthConsumer.evaluate(
            event = event,
            currentOwnerId = "user_A",
            currentGeneration = AppAuthManager.getSessionGeneration(),
            currentEpoch = AppAuthManager.getProcessEpoch(),
            isUiActive = true
        ) as PurchaseAuthDecision.RequestReauth

        decision1.confirmStarted()
        assertEquals(
            VipPurchaseAuthConsumer.ReservationState.STARTED,
            VipPurchaseAuthConsumer.getReservationState(event.recoveryKey)
        )

        val decision2 = VipPurchaseAuthConsumer.evaluate(
            event = event,
            currentOwnerId = "user_A",
            currentGeneration = AppAuthManager.getSessionGeneration(),
            currentEpoch = AppAuthManager.getProcessEpoch(),
            isUiActive = true
        )
        assertTrue(decision2 is PurchaseAuthDecision.Stop)
    }

    @Test
    fun staleToken_cannotCorruptNewReservation() {
        val event = createEvent()

        val decision1 = VipPurchaseAuthConsumer.evaluate(
            event = event,
            currentOwnerId = "user_A",
            currentGeneration = AppAuthManager.getSessionGeneration(),
            currentEpoch = AppAuthManager.getProcessEpoch(),
            isUiActive = true
        ) as PurchaseAuthDecision.RequestReauth

        decision1.release() // Reservation cleared

        val decision2 = VipPurchaseAuthConsumer.evaluate(
            event = event,
            currentOwnerId = "user_A",
            currentGeneration = AppAuthManager.getSessionGeneration(),
            currentEpoch = AppAuthManager.getProcessEpoch(),
            isUiActive = true
        ) as PurchaseAuthDecision.RequestReauth

        decision2.confirmStarted()
        assertEquals(
            VipPurchaseAuthConsumer.ReservationState.STARTED,
            VipPurchaseAuthConsumer.getReservationState(event.recoveryKey)
        )

        // Late callback from decision1 tries to release
        decision1.release()

        // State remains STARTED because decision1 token doesn't match
        assertEquals(
            VipPurchaseAuthConsumer.ReservationState.STARTED,
            VipPurchaseAuthConsumer.getReservationState(event.recoveryKey)
        )
    }

    @Test
    fun newOperation_getsFreshReservationEvenForSameReceipt() {
        val gen = AppAuthManager.getSessionGeneration()
        val op1 = BillingOperationContext(ownerAppUserId = "user_A", sessionGeneration = gen)
        val op2 = BillingOperationContext(ownerAppUserId = "user_A", sessionGeneration = gen)
        assertNotEquals(op1.operationId, op2.operationId)

        val event1 = createEvent(opContext = op1, sessionGen = gen)
        val event2 = createEvent(opContext = op2, sessionGen = gen)

        val decision1 = VipPurchaseAuthConsumer.evaluate(
            event = event1,
            currentOwnerId = "user_A",
            currentGeneration = gen,
            currentEpoch = AppAuthManager.getProcessEpoch(),
            isUiActive = true
        ) as PurchaseAuthDecision.RequestReauth
        decision1.confirmStarted()

        val decision2 = VipPurchaseAuthConsumer.evaluate(
            event = event2,
            currentOwnerId = "user_A",
            currentGeneration = gen,
            currentEpoch = AppAuthManager.getProcessEpoch(),
            isUiActive = true
        )
        assertTrue("New operation must receive fresh RequestReauth", decision2 is PurchaseAuthDecision.RequestReauth)
    }

    @Test
    fun confirmStarted_triggersCommitAttemptAndUpdatesLedger() {
        var commitCalled = false
        val event = BillingManager.PurchaseAuthRequiredEvent(
            purchase = testReceipt,
            message = "Auth required",
            targetOwnerId = "user_A",
            sessionGeneration = AppAuthManager.getSessionGeneration(),
            processEpoch = AppAuthManager.getProcessEpoch(),
            isRetry = false,
            operationContext = BillingOperationContext(
                ownerAppUserId = "user_A",
                sessionGeneration = AppAuthManager.getSessionGeneration()
            ),
            onCommitAttempt = { commitCalled = true }
        )

        val decision = VipPurchaseAuthConsumer.evaluate(
            event = event,
            currentOwnerId = "user_A",
            currentGeneration = AppAuthManager.getSessionGeneration(),
            currentEpoch = AppAuthManager.getProcessEpoch(),
            isUiActive = true
        ) as PurchaseAuthDecision.RequestReauth

        assertFalse("Before confirmStarted, commitAttempt must not be called", commitCalled)
        assertFalse(VipPurchaseAuthConsumer.isRecoveryStartedOrCompleted(event.recoveryKey))

        decision.confirmStarted()
        assertTrue("After confirmStarted, commitAttempt must be called", commitCalled)
        assertTrue(VipPurchaseAuthConsumer.isRecoveryStartedOrCompleted(event.recoveryKey))
    }

    @Test
    fun release_doesNotTriggerCommitAttempt() {
        var commitCalled = false
        var releaseCalled = false
        val event = BillingManager.PurchaseAuthRequiredEvent(
            purchase = testReceipt,
            message = "Auth required",
            targetOwnerId = "user_A",
            sessionGeneration = AppAuthManager.getSessionGeneration(),
            processEpoch = AppAuthManager.getProcessEpoch(),
            isRetry = false,
            operationContext = BillingOperationContext(
                ownerAppUserId = "user_A",
                sessionGeneration = AppAuthManager.getSessionGeneration()
            ),
            onCommitAttempt = { commitCalled = true },
            onReleaseAttempt = { releaseCalled = true }
        )

        val decision = VipPurchaseAuthConsumer.evaluate(
            event = event,
            currentOwnerId = "user_A",
            currentGeneration = AppAuthManager.getSessionGeneration(),
            currentEpoch = AppAuthManager.getProcessEpoch(),
            isUiActive = true
        ) as PurchaseAuthDecision.RequestReauth

        decision.release()
        assertFalse(commitCalled)
        assertTrue(releaseCalled)
        assertFalse(VipPurchaseAuthConsumer.isRecoveryStartedOrCompleted(event.recoveryKey))
    }
}
