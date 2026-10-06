package com.tscanner.app

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.billing.BillingEntitlement
import com.tscanner.app.utils.billing.BillingEntitlementStore
import com.tscanner.app.utils.billing.EntitlementSource
import com.tscanner.app.utils.billing.EntitlementState
import com.tscanner.app.utils.billing.PlayPurchaseVerifier
import com.tscanner.app.utils.billing.PurchaseVerifier
import com.tscanner.app.utils.billing.RejectionReason
import com.tscanner.app.utils.billing.VerificationRequest
import com.tscanner.app.utils.billing.VerificationResult
import kotlinx.coroutines.runBlocking
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
 * Unit tests for B06: Verify và session ownership ở client (F04/F05).
 *
 * Verifies:
 * - F04: Direct unverified grant is prevented; client requires PurchaseVerifier.
 * - MissingBackendGate: Production client without configured backend strictly blocks paid purchases.
 * - Catalog allowlist: Unrecognized/unlisted products are rejected before acknowledge or entitlement.
 * - F05: Late callback guard prevents cross-account mutation when active user switches.
 * - SHA-256 Obfuscated Account ID matches backend specification.
 * - Idempotency: Duplicate tokens do not extend expiration or corrupt state.
 * - Token masking: Sensitive tokens are masked in logs.
 */
class BillingPurchaseVerificationTest {

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
        BillingManager.resetInstanceForTesting()
        BillingEntitlementStore.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
        PlayPurchaseVerifier.setAllowLocalFallbackDefault(true)
    }

    @After
    fun tearDown() {
        BillingManager.resetInstanceForTesting()
        BillingEntitlementStore.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
        PlayPurchaseVerifier.setAllowLocalFallbackDefault(null)
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    @Test
    fun testCatalogAllowlist_unknownProduct_isRejectedImmediately() {
        val manager = BillingManager.createInstanceForTesting(testContext, { fakeWrapper })
        AppAuthManager.signInWithDemoAccount(testContext) {}

        val unknownPurchase = createTestPurchase("malicious_unlisted_product", acknowledged = true)
        val latch = CountDownLatch(1)
        var callbackSuccess: Boolean? = null

        manager.processPurchase(unknownPurchase) { success ->
            callbackSuccess = success
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(false, callbackSuccess)
        assertFalse("Unknown product must never grant VIP", AppAuthManager.getCurrentUser()!!.isVipActive)
    }

    @Test
    fun testMissingBackendGate_whenFallbackDisabled_blocksPaidPurchase() {
        // Enforce production mode: no fallback allowed, no backend URL
        val strictVerifier = PlayPurchaseVerifier(backendUrl = null, allowLocalFallback = false)
        val manager = BillingManager.createInstanceForTesting(testContext, { fakeWrapper }, strictVerifier)
        AppAuthManager.signInWithDemoAccount(testContext) {}

        val purchase = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, acknowledged = false)
        val latch = CountDownLatch(1)
        var callbackSuccess: Boolean? = null

        manager.processPurchase(purchase) { success ->
            callbackSuccess = success
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals("Missing backend gate must report false", false, callbackSuccess)
        assertFalse("Unverified purchase must not grant VIP without backend", AppAuthManager.getCurrentUser()!!.isVipActive)
        assertNull("Unverified purchase must not be acknowledged", fakeWrapper.lastAcknowledgedParams)
    }

    @Test
    fun testLateAck_accountSwitchBeforeCallback_doesNotMutateNewUser() {
        val manager = BillingManager.createInstanceForTesting(testContext, { fakeWrapper })
        val userA = UserProfile("user_A_101", "a@example.com", "User A")
        val userB = UserProfile("user_B_202", "b@example.com", "User B")

        AppAuthManager.setCurrentUserForTesting(userA)
        fakeWrapper.deferAck = true

        val now = System.currentTimeMillis()
        val purchase = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, acknowledged = false, purchaseTime = now)
        val latch = CountDownLatch(1)

        // Process purchase while user A is active
        manager.processPurchase(purchase) {
            latch.countDown()
        }

        // Wait until coroutine reaches acknowledge and sets deferredAck
        val startWait = System.currentTimeMillis()
        while (fakeWrapper.deferredAck == null && System.currentTimeMillis() - startWait < 1500) {
            Thread.sleep(10)
        }
        assertNotNull("deferredAck must be reached by billing flow", fakeWrapper.deferredAck)

        // User A logs out / switches to User B before Google ack completes
        AppAuthManager.setCurrentUserForTesting(userB)
        assertEquals("Current user is now B", "user_B_202", AppAuthManager.getCurrentUser()?.id)

        // Google acknowledge callback completes late
        fakeWrapper.completeDeferredAck()
        assertTrue(latch.await(2, TimeUnit.SECONDS))

        // Invariant F05: User B must NOT receive VIP!
        assertFalse("Late ack of A's purchase must NOT grant VIP to B", AppAuthManager.getCurrentUser()!!.isVipActive)

        // But user A's entitlement is safely preserved in store
        val snapA = BillingEntitlementStore.getInstance().getSnapshot(testContext, "user_A_101")
        assertEquals("Entitlement must be stored under User A", 1, snapA.entitlements.size)
        assertTrue(snapA.isVipActive(now))
    }

    @Test
    fun testObfuscatedAccountId_computesSha256HexMatchingBackend() {
        val userId = "google_user_123456789"
        val computed = PlayPurchaseVerifier.computeObfuscatedAccountId(userId)
        assertNotNull(computed)
        assertEquals("SHA-256 must produce 64-char hex string", 64, computed!!.length)

        // Known SHA-256 vector check
        val testInput = "test_user_id"
        val expectedHash = "32b02ac70148c6013f368b0c5f4f929e46eacf7801cc0d783546628e476967c2"
        assertEquals(expectedHash, PlayPurchaseVerifier.computeObfuscatedAccountId(testInput))

        // Null / blank inputs return null
        assertNull(PlayPurchaseVerifier.computeObfuscatedAccountId(null))
        assertNull(PlayPurchaseVerifier.computeObfuscatedAccountId(""))
    }

    @Test
    fun testTokenMasking_masksSensitiveDataInLogs() {
        assertEquals("null", PlayPurchaseVerifier.maskToken(null))
        assertEquals("***", PlayPurchaseVerifier.maskToken("short"))
        assertEquals("tok_...4567", PlayPurchaseVerifier.maskToken("tok_123456789abcdef4567"))
    }

    @Test
    fun testDuplicatePurchaseProcessing_isIdempotent() {
        val manager = BillingManager.createInstanceForTesting(testContext, { fakeWrapper })
        AppAuthManager.signInWithDemoAccount(testContext) {}

        val now = System.currentTimeMillis()
        val purchase = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, acknowledged = true, purchaseTime = now)

        val latch1 = CountDownLatch(1)
        manager.processPurchase(purchase) { latch1.countDown() }
        assertTrue(latch1.await(2, TimeUnit.SECONDS))

        val expiry1 = AppAuthManager.getCurrentUser()!!.vipExpiresAt
        assertNotNull("Active yearly purchase must have non-null expiry", expiry1)

        // Process same purchase second time
        val latch2 = CountDownLatch(1)
        manager.processPurchase(purchase) { latch2.countDown() }
        assertTrue(latch2.await(2, TimeUnit.SECONDS))

        val expiry2 = AppAuthManager.getCurrentUser()!!.vipExpiresAt
        assertEquals("Replay of same purchase token must not extend expiry", expiry1, expiry2)
    }

    @Test
    fun testCustomVerifierInjection_rejectedReasonHandledGracefully() {
        val rejectingVerifier = object : PurchaseVerifier {
            override suspend fun verifyPurchase(request: VerificationRequest): VerificationResult {
                return VerificationResult.Rejected(RejectionReason.PURCHASE_REVOKED, "Đơn hàng đã bị Google hoàn tiền")
            }
        }

        val manager = BillingManager.createInstanceForTesting(testContext, { fakeWrapper }, rejectingVerifier)
        AppAuthManager.signInWithDemoAccount(testContext) {}

        val purchase = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, acknowledged = false)
        val latch = CountDownLatch(1)
        var resultSuccess: Boolean? = null

        manager.processPurchase(purchase) { ok ->
            resultSuccess = ok
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(false, resultSuccess)
        assertFalse("Revoked purchase must not grant VIP", AppAuthManager.getCurrentUser()!!.isVipActive)
    }
}
