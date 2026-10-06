package com.tscanner.app

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.ui.dialogs.PurchaseAuthDecision
import com.tscanner.app.ui.dialogs.VipPurchaseAuthConsumer
import com.tscanner.app.utils.*
import com.tscanner.app.utils.billing.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class VipSessionRound5ProbeTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var manager: BillingManager
    private lateinit var operation: BillingOperationContext
    private val receipt by lazy { createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "synthetic-round5") }
    @Before fun setup() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread() = true
        })
        AppAuthManager.resetForTesting()
        AppAuthManager.setCurrentUserForTesting(UserProfile("A", "A@example.com", "A"))
        VipPurchaseAuthConsumer.resetForTesting()
        operation = BillingOperationContext(ownerAppUserId = "A", sessionGeneration = AppAuthManager.getSessionGeneration())
        manager = BillingManager.createInstanceForTesting(BillingTestContext(folder.root), { FakeBillingClientWrapper() },
            object : PurchaseVerifier {
                override suspend fun verifyPurchase(request: VerificationRequest) = VerificationResult.AuthRequired("synthetic-auth")
            })
    }
    @After fun cleanup() {
        BillingManager.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
        VipPurchaseAuthConsumer.resetForTesting()
        ArchTaskExecutor.getInstance().setDelegate(null)
    }
    private fun evaluate(e: BillingManager.PurchaseAuthRequiredEvent, active: Boolean = true) = VipPurchaseAuthConsumer.evaluate(
        e, AppAuthManager.getCurrentUser()?.id, AppAuthManager.getSessionGeneration(), AppAuthManager.getProcessEpoch(), active)
    private fun emit() = manager.processPurchase(receipt, BillingOperationOrigin.PURCHASE, operation)

    @Test fun P01_deferredUiMustAllowFirstActualProviderStart() {
        var active = false
        var starts = 0
        val decisions = mutableListOf<PurchaseAuthDecision>()
        manager.addAuthRequiredListener { event ->
            val decision = evaluate(event, active)
            decisions.add(decision)
            if (decision is PurchaseAuthDecision.RequestReauth) starts++
        }
        emit()
        assertTrue(decisions.first() is PurchaseAuthDecision.Defer)
        assertEquals(0, starts)
        active = true
        emit()
        assertEquals("Deferred listener has not spent a provider attempt; active retry must start it", 1, starts)
    }
    @Test fun P02_providerRefusalMustNotSpendAcceptedAttempt() {
        var busy = true
        var acceptedStarts = 0
        manager.addAuthRequiredListener { event ->
            val decision = evaluate(event)
            // Fake provider boundary refuses the first request (equivalent to signInWithGoogle=false).
            if (decision is PurchaseAuthDecision.RequestReauth) {
                if (!busy) {
                    decision.confirmStarted()
                    acceptedStarts++
                } else {
                    decision.release()
                }
            }
        }
        emit()
        assertEquals(0, acceptedStarts)
        busy = false
        emit()
        assertEquals("No provider accepted first request; allow recovery when it becomes available", 1, acceptedStarts)
    }
    @Test fun P03_twoConsumersMustNotStartRecoveryTwiceForSameOperation() {
        var starts = 0
        manager.addAuthRequiredListener { if (evaluate(it) is PurchaseAuthDecision.RequestReauth) starts++ }
        manager.addAuthRequiredListener { if (evaluate(it) is PurchaseAuthDecision.RequestReauth) starts++ }
        emit()
        assertEquals("Exactly one consumer may own recovery for the operation", 1, starts)
    }
    @Test fun C01_oneActiveConsumerStartsOnce() {
        var starts = 0
        manager.addAuthRequiredListener { if (evaluate(it) is PurchaseAuthDecision.RequestReauth) starts++ }
        emit()
        emit()
        assertEquals(1, starts)
        assertFalse(AppAuthManager.isUserVip())
    }
}
