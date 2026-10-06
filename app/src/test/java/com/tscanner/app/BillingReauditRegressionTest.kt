package com.tscanner.app

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.billing.PurchaseVerifier
import com.tscanner.app.utils.billing.VerificationRequest
import com.tscanner.app.utils.billing.VerificationResult
import com.tscanner.app.utils.billing.RejectionReason
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

/**
 * Permanent regression suite for VIP / Google Play Billing re-audit defects (F02-F08).
 *
 * Transferred from probe tests to permanent regressions as part of package B00.
 * Includes:
 * - 5 Control tests (đối chứng) ensuring the test harness and standard billing flows behave correctly.
 * - 9 Regression probes verifying that defects F02 to F08 reproduce on the current unpatched code
 *   and will turn green once each corresponding package (B01-B10) patches the production logic.
 */
class BillingReauditRegressionTest {

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

    // =========================================================================
    // CONTROLS (Đối chứng): Verify harness & standard flows pass
    // =========================================================================

    @Test
    fun testControl_setupConnectionSuccess_setsConnectedState() {
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
    fun testControl_newPurchase_unacknowledged_acknowledgesAndGrantsVip() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }

        AppAuthManager.signInWithDemoAccount(testContext) {}
        val user = AppAuthManager.getCurrentUser()
        assertNotNull(user)
        assertFalse(user!!.isVipActive)

        val purchase = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "tok_ctrl_1", acknowledged = false)

        val latch = CountDownLatch(1)
        manager.processPurchase(purchase) { success ->
            assertTrue(success)
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertNotNull("Must call acknowledgePurchase on client", fakeWrapper.lastAcknowledgedParams)
        assertEquals("tok_ctrl_1", fakeWrapper.lastAcknowledgedParams?.purchaseToken)

        val updatedUser = AppAuthManager.getCurrentUser()
        assertNotNull(updatedUser)
        assertTrue("User must now be VIP", updatedUser!!.isVipActive)
        assertEquals(VipTier.VIP, updatedUser.tier)
        assertTrue(manager.isBillingVipActiveLocally())
    }

    @Test
    fun testControl_alreadyAcknowledged_grantsVipWithoutDuplicateAck() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }

        AppAuthManager.signInWithDemoAccount(testContext) {}
        val user = AppAuthManager.getCurrentUser()
        assertNotNull(user)
        assertFalse(user!!.isVipActive)

        val purchase = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "tok_ctrl_acked", acknowledged = true)

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
    fun testControl_userCanceled_notifiesCallback() {
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
    fun testControl_restorePurchases_withActivePurchases_restoresVip() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        fakeWrapper.isReadyValue = true

        AppAuthManager.signInWithDemoAccount(testContext) {}
        assertFalse(AppAuthManager.getCurrentUser()!!.isVipActive)

        fakeWrapper.purchasesToReturn.add(
            createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "ctrl_restore_tok", acknowledged = true)
        )

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

    // =========================================================================
    // REGRESSIONS: Defects F02 to F08 (P1 & P2)
    // =========================================================================

    /**
     * F02 (P1): Same receipt token presented multiple times must NOT extend VIP expiry.
     * Currently fails because AppAuthManager adds duration to max(now, currentExpiry) on each call.
     */
    @Test
    fun probeDuplicateTokenMustNotExtendExpiry() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        AppAuthManager.signInWithDemoAccount(testContext) {}
        val purchase = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, acknowledged = true)
        manager.processPurchase(purchase)
        val expiry = AppAuthManager.getCurrentUser()!!.vipExpiresAt
        manager.processPurchase(purchase)
        assertEquals("Same receipt must not add a second year", expiry, AppAuthManager.getCurrentUser()!!.vipExpiresAt)
    }

    /**
     * Round 3 Contract (F03): Empty device catalog preserves server entitlements.
     * Authoritative server revocation (PURCHASE_REVOKED) revokes VIP status.
     */
    @Test
    fun probeEmptyAuthoritativeSyncMustRevokeBillingVip() {
        val revokingVerifier = object : PurchaseVerifier {
            override suspend fun verifyPurchase(request: VerificationRequest) =
                VerificationResult.Rejected(RejectionReason.PURCHASE_REVOKED, "Authoritatively revoked by Google")
        }
        val manager = BillingManager.createInstanceForTesting(testContext, { fakeWrapper }, revokingVerifier)
        AppAuthManager.signInWithDemoAccount(testContext) {}
        manager.processPurchase(createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, acknowledged = true))
        assertFalse("Authoritative server revocation must revoke app VIP status", AppAuthManager.getCurrentUser()!!.isVipActive)
    }

    /**
     * F04 (P1): Unrecognized product ID must NOT be granted VIP entitlement.
     * Currently fails because resolveDurationDays defaults to 365 days for unrecognized products.
     */
    @Test
    fun probeUnknownProductMustNotGrantVip() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        AppAuthManager.signInWithDemoAccount(testContext) {}
        manager.processPurchase(createTestPurchase("unrelated_product", acknowledged = true))
        assertFalse("Unrecognized product granted VIP", AppAuthManager.getCurrentUser()!!.isVipActive)
    }

    /**
     * Round 4 Contract (R10): Server is the ack authority (grant + outbox atomic).
     * Client SDK acknowledge failure does not block restore of verified entitlement.
     * When verification fails (e.g. unverified/rejected purchase), restore must report false.
     */
    @Test
    fun probeAckFailureMustNotReportRestoreSuccess() {
        val failingVerifier = object : PurchaseVerifier {
            override suspend fun verifyPurchase(request: VerificationRequest) =
                VerificationResult.Rejected(RejectionReason.INVALID_SIGNATURE_OR_TOKEN, "Unverified purchase token")
        }
        val manager = BillingManager.createInstanceForTesting(testContext, { fakeWrapper }, failingVerifier)
        AppAuthManager.signInWithDemoAccount(testContext) {}
        fakeWrapper.purchasesToReturn.add(createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY))
        var success: Boolean? = null
        manager.restorePurchases { ok, _ -> success = ok }
        assertEquals("Unverified purchase must not report restore success", false, success)
    }

    /**
     * F07 (P2): Calling restorePurchases while BillingClient is CONNECTING must complete when connected.
     * Currently fails because startConnection returns immediately without queueing or awaiting waiters.
     */
    @Test
    fun probeRestoreDuringConnectingMustComplete() {
        fakeWrapper.deferSetup = true
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        var completed = false
        manager.restorePurchases { _, _ -> completed = true }
        fakeWrapper.isReadyValue = true
        fakeWrapper.deferredSetup!!.onBillingSetupFinished(fakeWrapper.returnBillingResultForSetup)
        assertTrue("Restore waiter was silently discarded while CONNECTING", completed)
    }

    /**
     * F05 (P1): Delayed acknowledgment must NOT grant entitlement to a different logged-in account.
     * Currently fails because grantVipForPurchase reads currentUser dynamically at acknowledgment completion time.
     */
    @Test
    fun probeLateAckMustNotGrantToDifferentAccount() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        AppAuthManager.setCurrentUserForTesting(UserProfile("account_A", "a@example.test", "A"))
        fakeWrapper.deferAck = true
        manager.processPurchase(createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY))
        AppAuthManager.setCurrentUserForTesting(UserProfile("account_B", "b@example.test", "B"))
        fakeWrapper.deferredAck!!.invoke(fakeWrapper.returnBillingResultForAck)
        assertFalse("Purchase started by A granted to B after delayed acknowledge", AppAuthManager.getCurrentUser()!!.isVipActive)
    }

    /**
     * F02 (P1): Repeated binding of the same local guest receipt must be idempotent.
     * Currently fails because bindPurchasesToCurrentUser calls setUserVipTier each time, extending expiry.
     */
    @Test
    fun probeRepeatedBindingMustNotExtendExpiry() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        manager.processPurchase(createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, acknowledged = true))
        AppAuthManager.signInWithDemoAccount(testContext) {}
        manager.bindPurchasesToCurrentUser(testContext)
        val expiry = AppAuthManager.getCurrentUser()!!.vipExpiresAt
        manager.bindPurchasesToCurrentUser(testContext)
        assertEquals("Binding same local receipt must be idempotent", expiry, AppAuthManager.getCurrentUser()!!.vipExpiresAt)
    }

    /**
     * F06 (P1): Query error (e.g. SERVICE_UNAVAILABLE) must NOT clear last known billing state.
     * Currently fails because empty results due to network failure set KEY_BILLING_VIP_ACTIVE to false.
     */
    @Test
    fun probeQueryErrorMustPreserveLastKnownBillingState() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        manager.processPurchase(createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, acknowledged = true))
        fakeWrapper.queryResponse = BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE
        manager.restorePurchases { _, _ -> }
        assertTrue("Network failure was treated as no purchases", manager.isBillingVipActiveLocally())
    }

    /**
     * F08 (P2): Silent background reconciliation must NOT emit interactive purchase UI success events.
     * Currently fails because syncPurchasesOnStart invokes processPurchase which calls notifyCallbacks.
     */
    @Test
    fun probeSilentSyncMustNotEmitPurchaseSuccessEvent() {
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        AppAuthManager.signInWithDemoAccount(testContext) {}
        fakeWrapper.purchasesToReturn.add(createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, acknowledged = true))
        var successes = 0
        manager.addPurchaseCallback { ok, _, _ -> if (ok) successes++ }
        manager.syncPurchasesOnStart()
        assertEquals("Silent reconciliation triggers interactive purchase completion", 0, successes)
    }
}
