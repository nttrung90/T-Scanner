package com.tscanner.app

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.utils.*
import com.tscanner.app.utils.billing.*
import com.tscanner.app.ui.more.VipNavigationValidator
import com.tscanner.app.ui.more.NavigationDecision
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class VipSessionRound4ProbeTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var manager: BillingManager
    private val events = mutableListOf<BillingManager.PurchaseAuthRequiredEvent>()
    private val receipt by lazy { createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "synthetic-receipt-round4") }
    private fun context() = BillingOperationContext(
        ownerAppUserId = "A", sessionGeneration = AppAuthManager.getSessionGeneration(),
        operationType = BillingOperationType.PURCHASE)
    @Before fun setup() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread() = true
        })
        AppAuthManager.resetForTesting()
        AppAuthManager.setCurrentUserForTesting(UserProfile("A", "A@example.com", "A"))
        manager = BillingManager.createInstanceForTesting(
            BillingTestContext(folder.root), clientProvider = { FakeBillingClientWrapper() },
            verifier = object : PurchaseVerifier {
                override suspend fun verifyPurchase(request: VerificationRequest) = VerificationResult.AuthRequired("synthetic auth failure")
            })
    }
    @After fun cleanup() {
        BillingManager.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
        ArchTaskExecutor.getInstance().setDelegate(null)
    }
    @Test fun P01_backgroundVerificationMustNotSpendInteractiveRecoveryAttempt() {
        manager.addAuthRequiredListener { events.add(it) }
        manager.processPurchase(receipt, BillingOperationOrigin.RECONCILE, context())
        assertEquals(0, events.size)
        manager.processPurchase(receipt, BillingOperationOrigin.PURCHASE, context())
        assertEquals(1, events.size)
        assertFalse("First foreground recovery must not be marked retry by earlier silent sync", events.single().isRetry)
    }
    @Test fun P02_noListenerMustNotSpendRecoveryBeforeUserCanAuthenticate() {
        manager.processPurchase(receipt, BillingOperationOrigin.PURCHASE, context())
        manager.addAuthRequiredListener { events.add(it) }
        manager.processPurchase(receipt, BillingOperationOrigin.PURCHASE, context())
        assertEquals(1, events.size)
        assertFalse("No UI received the first event, so no recovery was attempted", events.single().isRetry)
    }
    @Test fun P03_newExplicitOperationMustNotInheritReceiptLifetimeRetryFlag() {
        manager.addAuthRequiredListener { events.add(it) }
        val first = context()
        manager.processPurchase(receipt, BillingOperationOrigin.PURCHASE, first)
        // Prior UI flow ended/cancelled. A new explicit operation uses a new context.
        val next = context()
        assertNotEquals(first.operationId, next.operationId)
        manager.processPurchase(receipt, BillingOperationOrigin.PURCHASE, next)
        assertEquals(2, events.size)
        assertFalse("Retry allowance belongs to a user operation, not receipt lifetime in singleton", events.last().isRetry)
    }
    @Test fun P04_sessionChangeDuringDispatchMustSuppressRemainingOldEvents() {
        manager.addAuthRequiredListener {
            AppAuthManager.nextSessionGeneration()
            AppAuthManager.setCurrentUserForTesting(UserProfile("B", "B@example.com", "B"))
        }
        var staleDelivered = 0
        manager.addAuthRequiredListener { if (it.targetOwnerId != AppAuthManager.getCurrentUser()?.id) staleDelivered++ }
        manager.processPurchase(receipt, BillingOperationOrigin.PURCHASE, context())
        assertEquals("A's event must not reach another consumer after switching to B", 0, staleDelivered)
    }
    @Test fun P05_oldGuestNavigationMustNotRunInNewGuestSession() {
        val decision = VipNavigationValidator.validateNavigation(
            originOwnerId = null, currentOwnerId = null,
            originGeneration = 1, currentGeneration = 3,
            originEpoch = "same-process", currentEpoch = "same-process",
            operationId = "old-guest-operation", isOperationConsumed = false)
        assertTrue("Guest -> login -> logout must invalidate original guest navigation", decision is NavigationDecision.Discard)
    }
    @Test fun C01_firstInteractiveEventIsNotRetryAndDoesNotGrantVip() {
        manager.addAuthRequiredListener { events.add(it) }
        manager.processPurchase(receipt, BillingOperationOrigin.PURCHASE, context())
        assertEquals(1, events.size)
        assertFalse(events.single().isRetry)
        assertFalse(AppAuthManager.isUserVip())
    }
    @Test fun C02_matchingGuestNavigationIsAccepted() {
        assertTrue(VipNavigationValidator.validateNavigation(null, null, 1, 1,
            "same-process", "same-process", "fresh", false) is NavigationDecision.Accept)
    }
}
