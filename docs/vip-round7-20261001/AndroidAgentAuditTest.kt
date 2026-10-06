package com.tscanner.app

import android.content.Context
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import com.android.billingclient.api.BillingClient
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

/** Independent round7 audit probes. Production files are intentionally untouched. */
class AndroidAgentAuditTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var ctx: BillingTestContext
    private lateinit var client: FakeBillingClientWrapper
    private val store get() = BillingEntitlementStore.getInstance()

    private fun entitlement(owner: String = "A", token: String = "receipt", version: Long = 2) = BillingEntitlement(
        id = "GOOGLE_PLAY_SUBSCRIPTION_$token",
        ownerAppUserId = owner,
        productId = BillingManager.PRODUCT_VIP_YEARLY,
        productType = "subs",
        purchaseToken = token,
        state = EntitlementState.VERIFIED_ACTIVE,
        purchaseTimeMillis = 1700000000000,
        expiryTimeMillis = 4102444800000,
        verifiedAtMillis = 1700000000000,
        snapshotVersion = version
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

    private fun reconcile(verifier: PurchaseVerifier, onResult: (ReconciliationResult) -> Unit = {}) {
        BillingReconciliation(ctx, client, { _, done -> done(true) },
            BillingOperationContext(ownerAppUserId = "A", sessionGeneration = AppAuthManager.getSessionGeneration()),
            verifier, Dispatchers.Unconfined).reconcile(onResult)
    }

    private fun remote(result: RestoreResult) = object : PurchaseVerifier {
        override suspend fun verifyPurchase(request: VerificationRequest) = VerificationResult.Success(
            entitlement(token = request.purchaseToken))
        override suspend fun restorePurchases(request: RestoreRequest) = result
    }

    @Test fun A01PlayQueryErrorMustStillRefreshAuthoritativeAccountReceipts() {
        AppAuthManager.applyEntitlement(ctx, entitlement())
        client.subsQueryResponse = BillingClient.BillingResponseCode.ERROR
        var restores = 0
        val verifier = object : PurchaseVerifier {
            override suspend fun verifyPurchase(request: VerificationRequest) = VerificationResult.MissingBackendGate("unused")
            override suspend fun restorePurchases(request: RestoreRequest): RestoreResult {
                restores++
                return RestoreResult.Success(UserEntitlementSnapshot("A", listOf(entitlement(version = 3).copy(state = EntitlementState.REVOKED))))
            }
        }
        reconcile(verifier)
        assertEquals("A Play query failure prevented independent backend account refresh", 1, restores)
        assertFalse("Server-revoked cached VIP survived the failed device query", store.getSnapshot(ctx, "A").isVipActive())
    }

    @Test fun A02RemoteFailureAfterLocalSuccessMustNotReportCompleteRestore() {
        client.subsPurchases.add(createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "local-good", acknowledged = true))
        var result: ReconciliationResult? = null
        reconcile(remote(RestoreResult.TransientError(null, "Could not refresh server-only lifetime"))) { result = it }
        assertTrue("Local success hid failed account-wide restore as a full Restored result",
            result !is ReconciliationResult.Restored || (result as ReconciliationResult.Restored).failedCount > 0)
    }

    @Test fun A03PartialResultMustReachManagerCallbackAsPartial() {
        val manager = BillingManager.createInstanceForTesting(ctx, { client },
            remote(RestoreResult.Partial(UserEntitlementSnapshot("A", listOf(entitlement())), "One other receipt unresolved")))
        var success: Boolean? = null
        var message: String? = null
        manager.restorePurchases { ok, msg -> success = ok; message = msg }
        assertNotNull("Fixture must complete the restore callback", success)
        assertTrue("Manager discarded partial metadata and displayed complete-success text: $message",
            success == false || message?.contains("một phần", ignoreCase = true) == true || message?.contains("partial", ignoreCase = true) == true)
    }

    private fun partialBody(results: String) = """{"status":"PARTIAL","message":"Some receipts unresolved","snapshot":{"ownerAppUserId":"A","computedAtMillis":1700000000000,"entitlements":[{"id":"GOOGLE_PLAY_SUBSCRIPTION_receipt","ownerAppUserId":"A","productId":"tscanner_vip_yearly","productType":"subs","source":"GOOGLE_PLAY_SUBSCRIPTION","purchaseToken":"receipt","state":"VERIFIED_ACTIVE","purchaseTimeMillis":1700000000000,"verifiedAtMillis":1700000000000,"expiryTimeMillis":4102444800000,"snapshotVersion":2}]},"results":$results}"""

    @Test fun A04ParserMustPreserveActualPerTokenFailureCount() {
        val body = partialBody("""[{"purchaseToken":"receipt","status":"SUCCESS"},{"purchaseToken":"failed-1","status":"TRANSIENT_ERROR"},{"purchaseToken":"failed-2","status":"REJECTED","reason":"INVALID_TOKEN"}]""")
        val verifier = PlayPurchaseVerifier(backendUrl = "https://audit.invalid", httpTransport = { _, _, _, _ -> VerifierHttpResponse(200, body) })
        var result: ReconciliationResult? = null
        reconcile(verifier) { result = it }
        assertTrue("Fixture must produce an active partial result", result is ReconciliationResult.Restored)
        assertEquals("Parser discarded results[] and reconciliation fabricated one failure", 2, (result as ReconciliationResult.Restored).failedCount)
    }

    @Test fun A05CachedUnresolvedEntitlementMustNotCountAsFreshRestoreSuccess() {
        val body = partialBody("""[{"purchaseToken":"receipt","status":"REJECTED","reason":"INVALID_TOKEN"}]""")
        val verifier = PlayPurchaseVerifier(backendUrl = "https://audit.invalid", httpTransport = { _, _, _, _ -> VerifierHttpResponse(200, body) })
        var result: ReconciliationResult? = null
        reconcile(verifier) { result = it }
        assertTrue("Unresolved cached ACTIVE receipt was counted as successfully restored",
            result !is ReconciliationResult.Restored || (result as ReconciliationResult.Restored).count == 0)
    }

    @Test fun A06DestroyedManagerMustNotCommitNoncooperativeVerificationResponse() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callbacks = AtomicInteger(0)
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val verifier = object : PurchaseVerifier {
            override suspend fun verifyPurchase(request: VerificationRequest): VerificationResult {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Audit fixture verifier release timed out" }
                return VerificationResult.Success(entitlement(token = request.purchaseToken))
            }
        }
        val manager = BillingManager.createInstanceForTesting(ctx, { client }, verifier, dispatcher)
        try {
            manager.processPurchase(createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "late-http", acknowledged = true),
                origin = BillingOperationOrigin.RESTORE,
                operationContext = BillingOperationContext(ownerAppUserId = "A", sessionGeneration = AppAuthManager.getSessionGeneration()),
                onComplete = { callbacks.incrementAndGet() })
            assertTrue("Fixture must enter blocking verifier before destroy", entered.await(2, TimeUnit.SECONDS))
            manager.destroy()
            release.countDown()
            executor.submit {}.get(2, TimeUnit.SECONDS)
            assertTrue("Canceled manager committed a synchronous late HTTP verification response", store.getSnapshot(ctx, "A").entitlements.isEmpty())
            assertEquals("Canceled manager invoked purchase completion callback", 0, callbacks.get())
        } finally {
            release.countDown()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test fun A07RestoreMustKeepOriginalSessionOwnershipAcrossConnectionAwait() {
        client.isReadyValue = false
        val verifier = object : PurchaseVerifier {
            override suspend fun verifyPurchase(request: VerificationRequest) = VerificationResult.MissingBackendGate("unused")
            override suspend fun restorePurchases(request: RestoreRequest) = RestoreResult.Success(
                UserEntitlementSnapshot(request.ownerAppUserId, listOf(entitlement(owner = request.ownerAppUserId!!))))
        }
        val manager = BillingManager.createInstanceForTesting(ctx, { client }, verifier)
        var callbacks = 0
        manager.restorePurchases { _, _ -> callbacks++ }
        assertEquals("Fixture must wait for connection", 0, callbacks)
        AppAuthManager.setCurrentUserForTesting(UserProfile("B", "B@example.test", "B"))
        client.completeDeferredSetup()
        assertEquals("Restore initiated by A resumed as B and delivered callback to A's caller", 0, callbacks)
    }

    @Test fun A08ControlFullRestoreStillProjectsVipAndCompletes() {
        val manager = BillingManager.createInstanceForTesting(ctx, { client }, remote(RestoreResult.Success(UserEntitlementSnapshot("A", listOf(entitlement())))))
        var restored = false
        manager.restorePurchases { ok, _ -> restored = ok }
        assertTrue(restored)
        assertTrue(AppAuthManager.isUserVip())
        assertFalse(com.tscanner.app.utils.WatermarkHelper.shouldApplyWatermark(true))
        assertTrue(ctx.getSharedPreferences("tscanner_billing_prefs", Context.MODE_PRIVATE).getBoolean("billing_vip_active", false))
    }
}
