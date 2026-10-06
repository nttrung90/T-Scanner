package com.tscanner.app

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.billing.BillingEntitlementStore
import com.tscanner.app.utils.billing.BillingOperationOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

class BillingOperationEventsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: BillingTestContext
    private lateinit var fakeWrapper: FakeBillingClientWrapper

    @Before
    fun setUp() {
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
        testContext = BillingTestContext(tempFolder.root)
        fakeWrapper = FakeBillingClientWrapper()
        AppAuthManager.resetForTesting()
        BillingManager.resetInstanceForTesting()
        BillingEntitlementStore.resetInstanceForTesting()
    }

    @After
    fun tearDown() {
        BillingManager.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
    }

    @Test
    fun testSilentSync_doesNotEmitPurchaseSuccessCallback() {
        // F08: Background silent sync must NOT emit interactive UI toast/post-upgrade events
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        AppAuthManager.signInWithDemoAccount(testContext) {}

        fakeWrapper.purchasesToReturn.add(
            createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "tok_silent_1", acknowledged = true)
        )

        var purchaseEventCount = 0
        manager.addPurchaseCallback { success, _, _ ->
            if (success) purchaseEventCount++
        }

        // Silent sync on start
        manager.syncPurchasesOnStart()

        // Entitlement must be active in store and auth
        val user = AppAuthManager.getCurrentUser()
        assertTrue("Entitlement should be active", user?.isVipActive == true)
        // But NO interactive UI purchase callback should have fired!
        assertEquals("Silent sync must NOT emit purchase callback", 0, purchaseEventCount)
    }

    @Test
    fun testRestoreMultiplePurchases_emitsExactlyOneRestoreCompletion_andNoPurchaseCallback() {
        // F08 + F01 async: Restoring multiple purchases emits exactly one restore callback
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        AppAuthManager.signInWithDemoAccount(testContext) {}

        fakeWrapper.purchasesToReturn.add(
            createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "tok_yearly_multi", acknowledged = true)
        )
        fakeWrapper.purchasesToReturn.add(
            createTestPurchase(BillingManager.PRODUCT_VIP_LIFETIME, token = "tok_lifetime_multi", acknowledged = true)
        )

        var interactivePurchaseEvents = 0
        manager.addPurchaseCallback { success, _, _ ->
            if (success) interactivePurchaseEvents++
        }

        val restoreCompletionCount = AtomicInteger(0)
        var restoreSuccess: Boolean? = null
        manager.restorePurchases { ok, _ ->
            restoreCompletionCount.incrementAndGet()
            restoreSuccess = ok
        }

        assertEquals("Restore completion must be called exactly once", 1, restoreCompletionCount.get())
        assertEquals(true, restoreSuccess)
        assertEquals("Restore must not emit interactive purchase callbacks", 0, interactivePurchaseEvents)
    }

    @Test
    fun testInteractivePurchase_emitsPurchaseCallback() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        AppAuthManager.signInWithDemoAccount(testContext) {}

        var purchaseSuccessCount = 0
        manager.addPurchaseCallback { success, _, _ ->
            if (success) purchaseSuccessCount++
        }

        val purchase = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "tok_user_buy", acknowledged = false)
        manager.processPurchase(purchase, origin = BillingOperationOrigin.PURCHASE)

        assertEquals("Interactive purchase must emit purchase callback", 1, purchaseSuccessCount)
    }

    @Test
    fun testLogoutBeforePurchaseCallback_dropsInteractiveUiEvent() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        AppAuthManager.setCurrentUserForTesting(UserProfile("account_buyer", "buyer@example.test", "Buyer"))
        manager.setActivePurchaseOwnerForTesting("account_buyer")

        var purchaseEventCount = 0
        manager.addPurchaseCallback { success, _, _ ->
            if (success) purchaseEventCount++
        }

        val purchase = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "tok_buyer_late", acknowledged = false)

        // Launch purchase for account_buyer, but user logs out before callback returns
        AppAuthManager.setCurrentUserForTesting(null)

        manager.processPurchase(purchase, origin = BillingOperationOrigin.PURCHASE)

        // Entitlement is saved for account_buyer in store, but interactive UI event for logged-out state is dropped
        assertEquals("Purchase event must be dropped after logout", 0, purchaseEventCount)
        val snapshot = BillingEntitlementStore.getInstance().getSnapshot(testContext, "account_buyer")
        assertEquals(1, snapshot.entitlements.size)
    }

    @Test
    fun testSingleClickPurchase_withMultiplePurchasesInBatch_emitsSingleSuccessEvent() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        AppAuthManager.signInWithDemoAccount(testContext) {}

        var purchaseEventCount = 0
        manager.addPurchaseCallback { success, _, _ ->
            if (success) purchaseEventCount++
        }

        // Google Play returns a batch with 2 purchases (e.g. sub + inapp)
        val p1 = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "tok_batch_1", acknowledged = false)
        val p2 = createTestPurchase(BillingManager.PRODUCT_VIP_LIFETIME, token = "tok_batch_2", acknowledged = false)

        manager.onPurchasesUpdated(
            BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build(),
            listOf(p1, p2)
        )

        assertEquals("Batch purchase completion must emit single success event", 1, purchaseEventCount)
    }

    @Test
    fun testUserCanceledPurchase_notifiesInteractiveCallbackWithFailure() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        var resultSuccess: Boolean? = null
        var resultMessage: String? = null

        manager.addPurchaseCallback { success, message, _ ->
            resultSuccess = success
            resultMessage = message
        }

        manager.onPurchasesUpdated(
            BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.USER_CANCELED).build(),
            null
        )

        assertEquals(false, resultSuccess)
        assertEquals("Đã hủy giao dịch", resultMessage)
    }
}
