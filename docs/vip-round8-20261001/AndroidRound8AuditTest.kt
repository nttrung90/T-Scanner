package com.tscanner.app

import android.content.Context
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.billing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Independent round8 audit. Every assertion exercises current production code. */
class AndroidRound8AuditTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var ctx: BillingTestContext
    private lateinit var client: FakeBillingClientWrapper
    private val store get() = BillingEntitlementStore.getInstance()

    private fun entitlement(token: String = "active", state: EntitlementState = EntitlementState.VERIFIED_ACTIVE, version: Long = 2) = BillingEntitlement(
        id = "GOOGLE_PLAY_SUBSCRIPTION_$token", ownerAppUserId = "A",
        productId = BillingManager.PRODUCT_VIP_YEARLY, productType = "subs", purchaseToken = token,
        state = state, purchaseTimeMillis = 1700000000000, expiryTimeMillis = 4102444800000,
        verifiedAtMillis = 1700000000000, snapshotVersion = version
    )

    @Before fun setup() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(r: Runnable) = r.run()
            override fun postToMainThread(r: Runnable) = r.run()
            override fun isMainThread() = true
        })
        BillingManager.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
        BillingEntitlementStore.resetInstanceForTesting()
        ctx = BillingTestContext(tmp.root)
        client = FakeBillingClientWrapper().apply { isReadyValue = true; deferSetup = true }
        AppAuthManager.setCurrentUserForTesting(UserProfile("A", "A@example.test", "A"))
    }

    @After fun cleanup() {
        BillingManager.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
        BillingEntitlementStore.resetInstanceForTesting()
        ArchTaskExecutor.getInstance().setDelegate(null)
    }

    private fun remote(result: RestoreResult) = object : PurchaseVerifier {
        override suspend fun verifyPurchase(request: VerificationRequest) = VerificationResult.MissingBackendGate("unused")
        override suspend fun restorePurchases(request: RestoreRequest) = result
    }

    private fun reconcile(result: RestoreResult, localSuccess: Boolean = true): ReconciliationResult? {
        var observed: ReconciliationResult? = null
        BillingReconciliation(ctx, client, { _, done -> done(localSuccess) },
            BillingOperationContext(ownerAppUserId = "A", sessionGeneration = AppAuthManager.getSessionGeneration()),
            remote(result), Dispatchers.Unconfined).reconcile { observed = it }
        return observed
    }

    @Test fun A801ActivePlusPendingMustNotReportCompleteRestore() {
        val snapshot = UserEntitlementSnapshot("A", listOf(entitlement()))
        val result = reconcile(RestoreResult.Partial(snapshot, "A payment is pending", listOf(
            RestoreItemResult("active", "SUCCESS"), RestoreItemResult("pending", "PENDING"))))
        assertTrue("Fixture should retain independently restored active VIP", result is ReconciliationResult.Restored)
        result as ReconciliationResult.Restored
        assertEquals(1, result.count)
        assertTrue("PENDING metadata was erased and partial restore became full success: $result", result.failedCount > 0)
    }

    @Test fun A802AllPendingMustNotBecomeAuthoritativeNoPurchases() {
        val result = reconcile(RestoreResult.Partial(UserEntitlementSnapshot("A"), "Payment pending", listOf(
            RestoreItemResult("pending", "PENDING"))))
        assertNotNull("Fixture must complete", result)
        assertFalse("All-pending response was collapsed to no active purchases, losing pending/retry guidance", result is ReconciliationResult.NoActivePurchases)
        assertFalse("Pending payment must never grant VIP", AppAuthManager.isUserVip())
    }

    @Test fun A803RemoteFailureWithoutActiveVipMustNotBecomeNoPurchases() {
        val result = reconcile(RestoreResult.Partial(UserEntitlementSnapshot("A", listOf(entitlement(state = EntitlementState.EXPIRED))), "One receipt expired; another could not be refreshed", listOf(
            RestoreItemResult("active", "EXPIRED"), RestoreItemResult("unresolved", "TRANSIENT_ERROR"))))
        assertNotNull(result)
        assertFalse("No-active branch silently discarded the backend unresolved failure and retry state", result is ReconciliationResult.NoActivePurchases)
        assertFalse(AppAuthManager.isUserVip())
    }

    @Test fun A804SameReceiptFailedLocallyAndRemotelyMustCountOnlyOnce() {
        client.subsPurchases.add(createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "failed", acknowledged = true))
        val result = reconcile(RestoreResult.Partial(UserEntitlementSnapshot("A", listOf(entitlement())), "One failed receipt", listOf(
            RestoreItemResult("active", "SUCCESS"), RestoreItemResult("failed", "TRANSIENT_ERROR"))), localSuccess = false)
        assertTrue(result is ReconciliationResult.Restored)
        result as ReconciliationResult.Restored
        assertEquals("Same purchase token was counted twice across local and remote verification", 1, result.failedCount)
        assertEquals("Receipt total should be one active and one failed token", 2, result.totalCount)
    }

    @Test fun A805RemoteAuthoritativeSuccessMustResolveEarlierLocalFailure() {
        client.subsPurchases.add(createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "active", acknowledged = true))
        val result = reconcile(RestoreResult.Success(UserEntitlementSnapshot("A", listOf(entitlement())), results = listOf(
            RestoreItemResult("active", "SUCCESS"))), localSuccess = false)
        assertTrue(result is ReconciliationResult.Restored)
        result as ReconciliationResult.Restored
        assertEquals("Final authoritative success for the same receipt still displayed a stale local failure", 0, result.failedCount)
        assertEquals(1, result.count)
        assertEquals(1, result.totalCount)
    }

    @Test fun A806LocalPendingMustReachRestoreRequestAndTerminalState() {
        client.subsPurchases.add(createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "new-pending", purchaseState = Purchase.PurchaseState.PENDING))
        var request: RestoreRequest? = null
        var result: ReconciliationResult? = null
        val verifier = object : PurchaseVerifier {
            override suspend fun verifyPurchase(request: VerificationRequest) = VerificationResult.MissingBackendGate("unused")
            override suspend fun restorePurchases(input: RestoreRequest): RestoreResult {
                request = input
                return RestoreResult.Success(UserEntitlementSnapshot("A"))
            }
        }
        BillingReconciliation(ctx, client, { _, _ -> fail("Pending cannot enter paid verification") },
            BillingOperationContext(ownerAppUserId = "A", sessionGeneration = AppAuthManager.getSessionGeneration()),
            verifier, Dispatchers.Unconfined).reconcile { result = it }
        assertNotNull("Fixture should call backend", request)
        assertFalse("Known local pending receipt must not disappear as no purchases", result is ReconciliationResult.NoActivePurchases)
        assertFalse("Pending payment must not become paid VIP", AppAuthManager.isUserVip())
    }

    @Test fun A807DestroyedManagerMustIgnoreLatePlayTerminalCallback() {
        val manager = BillingManager.createInstanceForTesting(ctx, { client }, remote(RestoreResult.NotConfigured("unused")))
        val callbacks = AtomicInteger()
        manager.setActivePurchaseOwnerForTesting("A")
        manager.addPurchaseCallback(BillingManager.PurchaseCallback { _, _, _ -> callbacks.incrementAndGet() })
        manager.destroy()
        manager.onPurchasesUpdated(BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.USER_CANCELED).build(), null)
        assertEquals("A late SDK terminal callback reached a listener after BillingManager destroy", 0, callbacks.get())
    }

    @Test fun A808DestroyedManagerMustIgnoreLatePendingPlayCallback() {
        val manager = BillingManager.createInstanceForTesting(ctx, { client }, remote(RestoreResult.NotConfigured("unused")))
        val callbacks = AtomicInteger()
        manager.setActivePurchaseOwnerForTesting("A")
        manager.addPurchaseCallback(BillingManager.PurchaseCallback { _, _, _ -> callbacks.incrementAndGet() })
        manager.destroy()
        manager.onPurchasesUpdated(BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build(), listOf(
            createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "late-pending", purchaseState = Purchase.PurchaseState.PENDING)))
        assertEquals("Non-coroutine pending branch emitted an interactive callback after destroy", 0, callbacks.get())
        assertTrue(store.getSnapshot(ctx, "A").entitlements.isEmpty())
    }

    @Test fun A809ControlFullSuccessRemainsFreshAndProjectsVip() {
        val result = reconcile(RestoreResult.Success(UserEntitlementSnapshot("A", listOf(entitlement())), results = listOf(RestoreItemResult("active", "SUCCESS"))))
        assertTrue(result is ReconciliationResult.Restored)
        result as ReconciliationResult.Restored
        assertEquals(1, result.count)
        assertEquals(0, result.failedCount)
        assertEquals(1, result.totalCount)
        assertTrue(AppAuthManager.isUserVip())
        assertFalse(com.tscanner.app.utils.WatermarkHelper.shouldApplyWatermark(true))
    }

    @Test fun A810ControlRevocationRemainsInactiveAndAuthoritative() {
        AppAuthManager.applyEntitlement(ctx, entitlement())
        val result = reconcile(RestoreResult.Success(UserEntitlementSnapshot("A", listOf(entitlement(state = EntitlementState.REVOKED, version = 3))), results = listOf(RestoreItemResult("active", "REVOKED"))))
        assertTrue(result is ReconciliationResult.NoActivePurchases)
        assertFalse(store.getSnapshot(ctx, "A").isVipActive())
        assertFalse(AppAuthManager.isUserVip())
        assertTrue(com.tscanner.app.utils.WatermarkHelper.shouldApplyWatermark(true))
    }

    @Test fun A811ControlDestroyStillSuppressesBlockingVerificationCommit() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val callbacks = AtomicInteger()
        val verifier = object : PurchaseVerifier {
            override suspend fun verifyPurchase(request: VerificationRequest): VerificationResult {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return VerificationResult.Success(entitlement(token = request.purchaseToken))
            }
        }
        val manager = BillingManager.createInstanceForTesting(ctx, { client }, verifier, dispatcher)
        try {
            manager.processPurchase(createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "blocking-late", acknowledged = true),
                BillingOperationOrigin.RESTORE, BillingOperationContext(ownerAppUserId = "A", sessionGeneration = AppAuthManager.getSessionGeneration())) { callbacks.incrementAndGet() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            manager.destroy()
            release.countDown()
            executor.submit {}.get(2, TimeUnit.SECONDS)
            assertTrue(store.getSnapshot(ctx, "A").entitlements.isEmpty())
            assertEquals(0, callbacks.get())
        } finally {
            release.countDown()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test fun A812CachedVipWithEmptyRemoteSnapshotMustNotCountAsFreshRestore() {
        AppAuthManager.applyEntitlement(ctx, entitlement(token = "cached"))
        assertTrue("Fixture should begin with valid cached access", AppAuthManager.isUserVip())
        val result = reconcile(RestoreResult.Success(UserEntitlementSnapshot("A"), results = emptyList()))
        assertNotNull(result)
        // Cached access may be retained under the documented policy; only freshness is asserted.
        assertTrue("The existing access policy may preserve a valid cached entitlement", store.getSnapshot(ctx, "A").isVipActive())
        assertTrue("Empty remote results relabeled a previously cached receipt as freshly restored: $result",
            result !is ReconciliationResult.Restored || (result as ReconciliationResult.Restored).count == 0)
    }
}
