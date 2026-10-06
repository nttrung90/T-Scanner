package com.tscanner.app

import com.android.billingclient.api.Purchase
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.billing.BillingEntitlement
import com.tscanner.app.utils.billing.BillingEntitlementStore
import com.tscanner.app.utils.billing.EntitlementSource
import com.tscanner.app.utils.billing.EntitlementState
import com.tscanner.app.utils.billing.PurchaseVerifier
import com.tscanner.app.utils.billing.VerificationRequest
import com.tscanner.app.utils.billing.VerificationResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests verifying that [createTestPurchase] fixture correctly models
 * [Purchase.PurchaseState.PURCHASED] and [Purchase.PurchaseState.PENDING] states,
 * and that pending purchases are never acknowledged or granted VIP entitlement (R11).
 */
class BillingPurchaseFixtureTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ctx: BillingTestContext
    private lateinit var client: FakeBillingClientWrapper
    private lateinit var manager: BillingManager

    private val verifier = object : PurchaseVerifier {
        override suspend fun verifyPurchase(request: VerificationRequest): VerificationResult {
            return VerificationResult.Success(
                BillingEntitlement(
                    id = request.purchaseToken,
                    ownerAppUserId = request.ownerAppUserId ?: "guest",
                    productId = request.productId,
                    productType = request.productType,
                    purchaseToken = request.purchaseToken,
                    source = EntitlementSource.GOOGLE_PLAY_INAPP,
                    state = EntitlementState.VERIFIED_ACTIVE,
                    expiryTimeMillis = null,
                    snapshotVersion = 1L
                )
            )
        }
    }

    @Before
    fun setup() {
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
            override fun executeOnDiskIO(r: Runnable) = r.run()
            override fun postToMainThread(r: Runnable) = r.run()
            override fun isMainThread() = true
        })
        BillingManager.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
        BillingEntitlementStore.resetInstanceForTesting()

        ctx = BillingTestContext(tmp.root)
        client = FakeBillingClientWrapper()
        manager = BillingManager.createInstanceForTesting(ctx, { client }, verifier)
        AppAuthManager.setCurrentUserForTesting(UserProfile("test_user", "test@example.com", "Test User"))
    }

    @After
    fun cleanup() {
        BillingManager.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
        BillingEntitlementStore.resetInstanceForTesting()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    @Test
    fun factoryMustProducePurchasedStateByDefault() {
        val purchase = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY)
        assertEquals("Default purchaseState must be PURCHASED (1)", Purchase.PurchaseState.PURCHASED, purchase.purchaseState)
        assertEquals(1, purchase.purchaseState)
    }

    @Test
    fun factoryMustProducePurchasedStateWhenExplicitlyRequested() {
        val purchase = createTestPurchase(
            BillingManager.PRODUCT_VIP_YEARLY,
            purchaseState = Purchase.PurchaseState.PURCHASED
        )
        assertEquals("Explicit purchaseState must be PURCHASED (1)", Purchase.PurchaseState.PURCHASED, purchase.purchaseState)
        assertEquals(1, purchase.purchaseState)
    }

    @Test
    fun factoryMustProducePendingStateWhenRequestedWithEnum() {
        val purchase = createTestPurchase(
            BillingManager.PRODUCT_VIP_YEARLY,
            purchaseState = Purchase.PurchaseState.PENDING
        )
        assertEquals("SDK getter must return PENDING (2)", Purchase.PurchaseState.PENDING, purchase.purchaseState)
        assertEquals(2, purchase.purchaseState)
    }

    @Test
    fun factoryMustProducePendingStateWhenRequestedWithRawValueFour() {
        val purchase = createTestPurchase(
            BillingManager.PRODUCT_VIP_YEARLY,
            purchaseState = 4
        )
        assertEquals("SDK getter must return PENDING (2) when raw value 4 is supplied", Purchase.PurchaseState.PENDING, purchase.purchaseState)
        assertEquals(2, purchase.purchaseState)
    }

    @Test
    fun pendingPurchaseMustNotBeAcknowledgedOrGrantVip() {
        val pendingPurchase = createTestPurchase(
            productId = BillingManager.PRODUCT_VIP_YEARLY,
            token = "pending_token_123",
            acknowledged = false,
            purchaseState = Purchase.PurchaseState.PENDING
        )

        var processSuccess: Boolean? = null
        manager.processPurchase(pendingPurchase) { success ->
            processSuccess = success
        }

        assertEquals("Callback must report false for pending purchase", false, processSuccess)
        assertFalse("Pending purchase must not grant VIP to current user", AppAuthManager.getCurrentUser()!!.isVipActive)
        assertFalse("Pending purchase must not be acknowledged by billing client", client.acknowledgedTokens.contains("pending_token_123"))
    }

    @Test
    fun purchasedStateGrantsVipWhenProcessed() {
        val purchased = createTestPurchase(
            productId = BillingManager.PRODUCT_VIP_LIFETIME,
            token = "purchased_token_456",
            acknowledged = true,
            purchaseState = Purchase.PurchaseState.PURCHASED
        )

        var processSuccess: Boolean? = null
        manager.processPurchase(purchased) { success ->
            processSuccess = success
        }

        assertEquals("Callback must report true for verified purchased item", true, processSuccess)
        assertTrue("Purchased item must activate VIP", AppAuthManager.getCurrentUser()!!.isVipActive)
    }
}
