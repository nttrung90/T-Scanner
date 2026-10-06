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

class VipSessionRound6ProbeTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var manager: BillingManager
    private lateinit var op: BillingOperationContext
    private val receipt by lazy { createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token="synthetic-round6") }
    @Before fun setup() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(r: Runnable) = r.run()
            override fun postToMainThread(r: Runnable) = r.run()
            override fun isMainThread() = true
        })
        AppAuthManager.resetForTesting()
        VipPurchaseAuthConsumer.resetForTesting()
        AppAuthManager.setCurrentUserForTesting(UserProfile("A", "a@example.com", "A"))
        op=BillingOperationContext(ownerAppUserId="A", sessionGeneration=AppAuthManager.getSessionGeneration())
        manager=BillingManager.createInstanceForTesting(BillingTestContext(folder.root), { FakeBillingClientWrapper() },
            object : PurchaseVerifier {
                override suspend fun verifyPurchase(request: VerificationRequest)=VerificationResult.AuthRequired("synthetic-auth")
            })
    }
    @After fun cleanup() {
        BillingManager.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
        VipPurchaseAuthConsumer.resetForTesting()
        ArchTaskExecutor.getInstance().setDelegate(null)
    }
    private fun emit()=manager.processPurchase(receipt, BillingOperationOrigin.PURCHASE, op)
    private fun evaluate(e: BillingManager.PurchaseAuthRequiredEvent)=VipPurchaseAuthConsumer.evaluate(
        e, AppAuthManager.getCurrentUser()?.id, AppAuthManager.getSessionGeneration(), AppAuthManager.getProcessEpoch(), true)

    @Test fun P01_listenerDropsBeforeConsumerMustNotSpendRecovery() {
        var hostCanConsume=false
        var acceptedStarts=0
        var consumerCalls=0
        manager.addAuthRequiredListener { event ->
            // Fault injection at listener boundary: same early return as Dialog's activity/isShowing guard.
            // This is not a real Android lifecycle execution.
            if (hostCanConsume) {
                consumerCalls++
                val decision=evaluate(event)
                if(decision is PurchaseAuthDecision.RequestReauth) {
                    decision.confirmStarted()
                    acceptedStarts++
                }
            }
        }
        emit()
        assertEquals(0, consumerCalls)
        assertEquals(0, acceptedStarts)
        hostCanConsume=true
        emit()
        assertEquals(1, consumerCalls)
        assertEquals("No consumer/provider accepted the first event; retry must get its first recovery", 1, acceptedStarts)
    }

    @Test fun C01_explicitRefusalThenAcceptanceWorksAndDoesNotRepeat() {
        var busy=true
        var acceptedStarts=0
        manager.addAuthRequiredListener { event ->
            val decision=evaluate(event)
            if(decision is PurchaseAuthDecision.RequestReauth) {
                if(busy) decision.release() else { decision.confirmStarted(); acceptedStarts++ }
            }
        }
        emit()
        assertEquals(0, acceptedStarts)
        busy=false
        emit()
        emit()
        assertEquals(1, acceptedStarts)
        assertFalse(AppAuthManager.isUserVip())
    }
}
