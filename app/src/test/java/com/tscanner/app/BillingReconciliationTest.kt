package com.tscanner.app

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.billing.BillingReconciliation
import com.tscanner.app.utils.billing.PlayPurchaseVerifier
import com.tscanner.app.utils.billing.ReconciliationResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unit tests for B07: Reconcile và restore chờ đủ kết quả (F03/F06).
 *
 * Verifies:
 * - Coalesces SUBS and INAPP queries before making any state decisions.
 * - F03: Only authoritative empty responses (both queries OK and empty) revoke VIP entitlements.
 * - F06: Network/service errors preserve existing cached billing state; never wipe cache on error.
 * - F06: Restore only succeeds after all found purchases have been fully verified and acknowledged.
 * - Token de-duplication: duplicate purchase tokens are merged and processed once.
 * - Multi-entitlements across SUBS and INAPP are combined without order dependency.
 */
class BillingReconciliationTest {

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
        fakeWrapper.isReadyValue = true
        AppAuthManager.resetForTesting()
        BillingManager.resetInstanceForTesting()
        PlayPurchaseVerifier.setAllowLocalFallbackDefault(true)
    }

    @After
    fun tearDown() {
        BillingManager.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
        PlayPurchaseVerifier.setAllowLocalFallbackDefault(null)
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    @Test
    fun testReconciliation_bothQueriesOkAndEmpty_authoritativelyRevokesVip() {
        AppAuthManager.signInWithDemoAccount(testContext) {}
        AppAuthManager.setUserVipTier(testContext, VipTier.VIP, durationDays = 30)
        assertTrue("User must start as active VIP", AppAuthManager.getCurrentUser()!!.isVipActive)

        // Pre-populate local billing prefs as active
        testContext.getSharedPreferences("tscanner_billing_prefs", 0)
            .edit()
            .putBoolean("billing_vip_active", true)
            .commit()

        val reconciliation = BillingReconciliation(
            context = testContext,
            billingClient = fakeWrapper,
            processPurchaseAction = { _, onComplete -> onComplete(true) }
        )

        var finalResult: ReconciliationResult? = null
        val latch = CountDownLatch(1)

        reconciliation.reconcile { result ->
            finalResult = result
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue("Result must be NoActivePurchases", finalResult is ReconciliationResult.NoActivePurchases)
        assertFalse("Authoritative empty sync must revoke local VIP", AppAuthManager.getCurrentUser()!!.isVipActive)
        assertEquals(VipTier.FREE, AppAuthManager.getCurrentUser()!!.tier)

        val isLocalBillingActive = testContext.getSharedPreferences("tscanner_billing_prefs", 0)
            .getBoolean("billing_vip_active", false)
        assertFalse("Local billing flag must be marked false", isLocalBillingActive)
    }

    @Test
    fun testReconciliation_oneQueryOk_otherQueryNetworkError_preservesLastKnownState() {
        testContext.getSharedPreferences("tscanner_billing_prefs", 0)
            .edit()
            .putBoolean("billing_vip_active", true)
            .commit()

        fakeWrapper.subsQueryResponse = BillingClient.BillingResponseCode.OK
        fakeWrapper.inAppQueryResponse = BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE

        val reconciliation = BillingReconciliation(
            context = testContext,
            billingClient = fakeWrapper,
            processPurchaseAction = { _, onComplete -> onComplete(true) }
        )

        var finalResult: ReconciliationResult? = null
        val latch = CountDownLatch(1)

        reconciliation.reconcile { result ->
            finalResult = result
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue("Result must be NetworkError", finalResult is ReconciliationResult.NetworkError)
        assertEquals(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, (finalResult as ReconciliationResult.NetworkError).responseCode)

        val isLocalBillingActive = testContext.getSharedPreferences("tscanner_billing_prefs", 0)
            .getBoolean("billing_vip_active", false)
        assertTrue("Network error must preserve last known billing state as true", isLocalBillingActive)
    }

    @Test
    fun testReconciliation_bothQueriesError_preservesLastKnownState() {
        testContext.getSharedPreferences("tscanner_billing_prefs", 0)
            .edit()
            .putBoolean("billing_vip_active", true)
            .commit()

        fakeWrapper.queryResponse = BillingClient.BillingResponseCode.ERROR

        val reconciliation = BillingReconciliation(
            context = testContext,
            billingClient = fakeWrapper,
            processPurchaseAction = { _, onComplete -> onComplete(true) }
        )

        var finalResult: ReconciliationResult? = null
        val latch = CountDownLatch(1)

        reconciliation.reconcile { result ->
            finalResult = result
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue("Result must be NetworkError", finalResult is ReconciliationResult.NetworkError)

        val isLocalBillingActive = testContext.getSharedPreferences("tscanner_billing_prefs", 0)
            .getBoolean("billing_vip_active", false)
        assertTrue("Errors must preserve last known billing state", isLocalBillingActive)
    }

    @Test
    fun testReconciliation_multiplePurchases_deduplicatesTokens() {
        val token = "shared_token_123"
        val purchase1 = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = token)
        val purchase2 = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = token)

        fakeWrapper.subsPurchases.add(purchase1)
        fakeWrapper.subsPurchases.add(purchase2)

        var processCount = 0
        val reconciliation = BillingReconciliation(
            context = testContext,
            billingClient = fakeWrapper,
            processPurchaseAction = { _, onComplete ->
                processCount++
                onComplete(true)
            }
        )

        val latch = CountDownLatch(1)
        var finalResult: ReconciliationResult? = null

        reconciliation.reconcile { result ->
            finalResult = result
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals("Duplicate token must be processed exactly once", 1, processCount)
        assertTrue(finalResult is ReconciliationResult.Restored)
        assertEquals(1, (finalResult as ReconciliationResult.Restored).count)
    }

    @Test
    fun testReconciliation_processingFailure_returnsProcessingFailed_neverFalseSuccess() {
        val purchase = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY)
        fakeWrapper.subsPurchases.add(purchase)

        val reconciliation = BillingReconciliation(
            context = testContext,
            billingClient = fakeWrapper,
            processPurchaseAction = { _, onComplete ->
                // Simulate acknowledge or verification failure
                onComplete(false)
            }
        )

        val latch = CountDownLatch(1)
        var finalResult: ReconciliationResult? = null

        reconciliation.reconcile { result ->
            finalResult = result
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue("Processing failure must return ProcessingFailed", finalResult is ReconciliationResult.ProcessingFailed)
        assertFalse("Must never falsely report Restored", finalResult is ReconciliationResult.Restored)
    }

    @Test
    fun testReconciliation_multiEntitlement_combinesBothSubsAndInApp() {
        val subPurchase = createTestPurchase(BillingManager.PRODUCT_VIP_MONTHLY, token = "tok_sub_month")
        val inAppPurchase = createTestPurchase(BillingManager.PRODUCT_VIP_LIFETIME, token = "tok_inapp_life")

        fakeWrapper.subsPurchases.add(subPurchase)
        fakeWrapper.inAppPurchases.add(inAppPurchase)

        val processedProducts = mutableListOf<String>()
        val reconciliation = BillingReconciliation(
            context = testContext,
            billingClient = fakeWrapper,
            processPurchaseAction = { purchase, onComplete ->
                processedProducts.addAll(purchase.products)
                onComplete(true)
            }
        )

        val latch = CountDownLatch(1)
        var finalResult: ReconciliationResult? = null

        reconciliation.reconcile { result ->
            finalResult = result
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(finalResult is ReconciliationResult.Restored)
        val restored = finalResult as ReconciliationResult.Restored
        assertEquals(2, restored.count)
        assertTrue(restored.productIds.contains(BillingManager.PRODUCT_VIP_MONTHLY))
        assertTrue(restored.productIds.contains(BillingManager.PRODUCT_VIP_LIFETIME))
    }
}
