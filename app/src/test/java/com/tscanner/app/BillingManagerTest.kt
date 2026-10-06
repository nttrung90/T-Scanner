package com.tscanner.app

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BillingManagerTest {

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
    }

    @After
    fun tearDown() {
        BillingManager.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    private fun createPurchase(
        productId: String,
        token: String = "token_$productId",
        acknowledged: Boolean = false,
        purchaseState: Int = 1
    ): Purchase {
        return createTestPurchase(productId, token, acknowledged, purchaseState)
    }

    @Test
    fun testSetupConnectionSuccess_setsConnectedState() {
        fakeWrapper.returnBillingResultForSetup = BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.OK)
            .build()

        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }

        val latch = CountDownLatch(1)
        var connectionResult = false
        manager.startConnection { success ->
            connectionResult = success
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(connectionResult)
        assertEquals(BillingManager.ConnectionState.CONNECTED, manager.connectionState.value)
    }

    @Test
    fun testSetupConnectionFailure_setsDisconnectedState() {
        fakeWrapper.returnBillingResultForSetup = BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)
            .setDebugMessage("Service down")
            .build()

        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }

        val latch = CountDownLatch(1)
        var connectionResult = true
        manager.startConnection { success ->
            connectionResult = success
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertFalse(connectionResult)
        assertEquals(BillingManager.ConnectionState.DISCONNECTED, manager.connectionState.value)
    }

    @Test
    fun testProcessPurchase_unacknowledged_acknowledgesAndGrantsVip() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }

        // Sign in a user first
        AppAuthManager.signInWithDemoAccount(testContext) {}
        val user = AppAuthManager.getCurrentUser()
        assertNotNull(user)
        assertFalse(user!!.isVipActive)

        val purchase = createPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "tok_yearly_1", acknowledged = false)

        val latch = CountDownLatch(1)
        manager.processPurchase(purchase) { success ->
            assertTrue(success)
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertNotNull("Must call acknowledgePurchase on client", fakeWrapper.lastAcknowledgedParams)
        assertEquals("tok_yearly_1", fakeWrapper.lastAcknowledgedParams?.purchaseToken)

        // Verify VIP granted in AppAuthManager
        val updatedUser = AppAuthManager.getCurrentUser()
        assertNotNull(updatedUser)
        assertTrue("User must now be VIP", updatedUser!!.isVipActive)
        assertEquals(VipTier.VIP, updatedUser.tier)
        assertTrue(manager.isBillingVipActiveLocally())
    }

    @Test
    fun testProcessPurchase_alreadyAcknowledged_grantsVipWithoutDuplicateAck() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }

        AppAuthManager.signInWithDemoAccount(testContext) {}
        val user = AppAuthManager.getCurrentUser()
        assertNotNull(user)
        assertFalse(user!!.isVipActive)

        val purchase = createPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "tok_already_acked", acknowledged = true)

        val latch = CountDownLatch(1)
        manager.processPurchase(purchase) { success ->
            assertTrue(success)
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertNull("Should not call acknowledge again if already acknowledged", fakeWrapper.lastAcknowledgedParams)

        val updatedUser = AppAuthManager.getCurrentUser()
        assertNotNull(updatedUser)
        assertTrue("User must be VIP", updatedUser!!.isVipActive)
    }

    @Test
    fun testProcessPurchase_monthlyProduct_grants30Days() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }

        AppAuthManager.signInWithDemoAccount(testContext) {}
        val purchase = createPurchase(BillingManager.PRODUCT_VIP_MONTHLY, token = "tok_month", acknowledged = true)

        val latch = CountDownLatch(1)
        manager.processPurchase(purchase) { success ->
            assertTrue(success)
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))

        val updatedUser = AppAuthManager.getCurrentUser()
        assertNotNull(updatedUser)
        assertTrue(updatedUser!!.isVipActive)
        val durationDays = ((updatedUser.vipExpiresAt!! - updatedUser.vipPurchasedAt!!) / (24 * 60 * 60 * 1000L)).toInt()
        assertEquals(30, durationDays)
    }

    @Test
    fun testProcessPurchase_lifetimeProduct_grants10Years() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }

        AppAuthManager.signInWithDemoAccount(testContext) {}
        val purchase = createPurchase(BillingManager.PRODUCT_VIP_LIFETIME, token = "tok_life", acknowledged = true)

        val latch = CountDownLatch(1)
        manager.processPurchase(purchase) { success ->
            assertTrue(success)
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))

        val updatedUser = AppAuthManager.getCurrentUser()
        assertNotNull(updatedUser)
        assertTrue(updatedUser!!.isVipActive)
        assertNull("Lifetime entitlement must have null expiration date", updatedUser.vipExpiresAt)
    }

    @Test
    fun testOnPurchasesUpdated_userCanceled_notifiesCallback() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }

        val latch = CountDownLatch(1)
        var callbackSuccess: Boolean? = null
        var callbackMessage: String? = null

        manager.addPurchaseCallback { success, message, _ ->
            callbackSuccess = success
            callbackMessage = message
            latch.countDown()
        }

        val canceledResult = BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.USER_CANCELED)
            .setDebugMessage("User dismissed dialog")
            .build()

        manager.onPurchasesUpdated(canceledResult, null)

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertFalse(callbackSuccess!!)
        assertNotNull(callbackMessage)
        assertTrue(callbackMessage!!.contains("hủy"))
    }

    @Test
    fun testRestorePurchases_withActivePurchases_restoresVip() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        fakeWrapper.isReadyValue = true

        AppAuthManager.signInWithDemoAccount(testContext) {}
        assertFalse(AppAuthManager.getCurrentUser()!!.isVipActive)

        // Google Play returns 1 active yearly purchase
        fakeWrapper.purchasesToReturn.add(createPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "restore_tok_1", acknowledged = true))

        val latch = CountDownLatch(1)
        var restoredSuccess = false
        var restoredMsg = ""

        manager.restorePurchases { success, message ->
            restoredSuccess = success
            restoredMsg = message
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(restoredSuccess)
        assertTrue(restoredMsg.contains("thành công"))
        assertTrue("User must have active VIP after restore", AppAuthManager.getCurrentUser()!!.isVipActive)
    }

    @Test
    fun testRestorePurchases_whenNoPurchases_reportsNotFound() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        fakeWrapper.isReadyValue = true

        val latch = CountDownLatch(1)
        var restoredSuccess = true
        var restoredMsg = ""

        manager.restorePurchases { success, message ->
            restoredSuccess = success
            restoredMsg = message
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertFalse(restoredSuccess)
        assertTrue(restoredMsg.contains("Không tìm thấy"))
    }

    @Test
    fun testGuestPurchase_savedLocally_andBindsToUserOnSignIn() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }

        // User is not signed in (Guest)
        assertNull(AppAuthManager.getCurrentUser())

        val purchase = createPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "guest_tok", acknowledged = true)
        fakeWrapper.subsPurchases.add(purchase)
        val latch = CountDownLatch(1)
        manager.processPurchase(purchase) {
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue("Billing VIP must be active locally for guest", manager.isBillingVipActiveLocally())

        // Now user signs in with Google
        AppAuthManager.signInWithDemoAccount(testContext) {}
        manager.bindPurchasesToCurrentUser(testContext)

        val signedInUser = AppAuthManager.getCurrentUser()
        assertNotNull(signedInUser)
        assertTrue("Signed in user must inherit the locally purchased VIP", signedInUser!!.isVipActive)
    }

    @Test
    fun testQueryAllProducts_success_populatesProductsMapAndFormatsPrice() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        fakeWrapper.isReadyValue = true

        val yearlySub = createTestProductDetails(
            BillingManager.PRODUCT_VIP_YEARLY,
            BillingClient.ProductType.SUBS,
            "VIP Yearly",
            "200.000 ₫"
        )
        val lifetimeInApp = createTestProductDetails(
            BillingManager.PRODUCT_VIP_LIFETIME,
            BillingClient.ProductType.INAPP,
            "VIP Lifetime",
            "500.000 ₫"
        )
        fakeWrapper.productDetailsListToReturn.addAll(listOf(yearlySub, lifetimeInApp))

        val latch = CountDownLatch(1)
        var queriedProducts: Map<String, com.android.billingclient.api.ProductDetails>? = null

        manager.queryAllProducts { products ->
            queriedProducts = products
            latch.countDown()
        }

        assertTrue("queryAllProducts must complete within 2s", latch.await(2, TimeUnit.SECONDS))
        assertNotNull(queriedProducts)
        assertEquals(2, queriedProducts!!.size)
        assertTrue(queriedProducts!!.containsKey(BillingManager.PRODUCT_VIP_YEARLY))
        assertTrue(queriedProducts!!.containsKey(BillingManager.PRODUCT_VIP_LIFETIME))

        assertEquals("200.000 ₫", manager.getFormattedPrice(BillingManager.PRODUCT_VIP_YEARLY))
        assertEquals("500.000 ₫", manager.getFormattedPrice(BillingManager.PRODUCT_VIP_LIFETIME))
    }

    @Test
    fun testQueryAllProducts_partialResultWithUnfetchedProducts_stillLoadsAvailableProducts() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        fakeWrapper.isReadyValue = true

        val availableSub = createTestProductDetails(
            BillingManager.PRODUCT_VIP_YEARLY,
            BillingClient.ProductType.SUBS,
            "VIP Yearly",
            "200.000 ₫"
        )
        val unfetchedSub = createTestUnfetchedProduct(
            BillingManager.PRODUCT_VIP_MONTHLY,
            BillingClient.ProductType.SUBS,
            statusCode = 2 // PRODUCT_NOT_FOUND
        )

        fakeWrapper.productDetailsListToReturn.add(availableSub)
        fakeWrapper.unfetchedProductsListToReturn.add(unfetchedSub)

        val latch = CountDownLatch(1)
        var queriedProducts: Map<String, com.android.billingclient.api.ProductDetails>? = null

        manager.queryAllProducts { products ->
            queriedProducts = products
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertNotNull(queriedProducts)
        assertEquals(1, queriedProducts!!.size)
        assertTrue(queriedProducts!!.containsKey(BillingManager.PRODUCT_VIP_YEARLY))
        assertFalse(queriedProducts!!.containsKey(BillingManager.PRODUCT_VIP_MONTHLY))
    }

    @Test
    fun testQueryAllProducts_errorResponse_doesNotCorruptProductsMap() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        fakeWrapper.isReadyValue = true

        fakeWrapper.returnBillingResultForProductDetails = BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)
            .setDebugMessage("Service down")
            .build()

        val latch = CountDownLatch(1)
        var queriedProducts: Map<String, com.android.billingclient.api.ProductDetails>? = null

        manager.queryAllProducts { products ->
            queriedProducts = products
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertNotNull(queriedProducts)
        assertTrue(queriedProducts!!.isEmpty())
        assertNull(manager.getFormattedPrice(BillingManager.PRODUCT_VIP_YEARLY))
    }

    @Test
    fun testQueryAllProducts_whenBillingNotReady_returnsExistingProductsWithoutCrashing() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        fakeWrapper.isReadyValue = false

        val latch = CountDownLatch(1)
        var queriedProducts: Map<String, com.android.billingclient.api.ProductDetails>? = null

        manager.queryAllProducts { products ->
            queriedProducts = products
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertNotNull(queriedProducts)
        assertTrue(queriedProducts!!.isEmpty())
    }
}
