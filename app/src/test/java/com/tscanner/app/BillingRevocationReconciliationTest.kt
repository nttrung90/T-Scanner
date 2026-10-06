package com.tscanner.app

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.billing.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit and integration tests for Authoritative Reconciliation & Source-Isolated Revocation (Q04 / R03).
 *
 * Verifies:
 * - Revocation -> reload / login / restart: Entitlement is durably revoked in store; no resurrection occurs.
 * - Promotion + empty Play: Non-Play promotional entitlements are strictly preserved when Play catalog is empty.
 * - Pending-only query: Purchases still in PENDING state do not cause premature revocation of active entitlements.
 * - Stale query arrival: A delayed empty query from an old session does not revoke a newly signed-in or updated account.
 * - Multiple entitlements: Revoking an expired subscription preserves an active lifetime in-app entitlement.
 * - Partial verification failures: Batch reconciliation returns clear partial counts (count, totalCount, failedCount).
 * - Network errors: Query network failures preserve existing cached billing state without downgrading.
 */
class BillingRevocationReconciliationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ctx: BillingTestContext
    private lateinit var client: FakeBillingClientWrapper
    private lateinit var manager: BillingManager
    private val store get() = BillingEntitlementStore.getInstance()

    private fun user(id: String) = UserProfile(id, "$id@example.test", "User $id")

    private fun testEntitlement(
        owner: String? = "User1",
        version: Long = 1L,
        state: EntitlementState = EntitlementState.VERIFIED_ACTIVE,
        source: EntitlementSource = EntitlementSource.GOOGLE_PLAY_INAPP,
        token: String = "test_tok"
    ) = BillingEntitlement(
        id = token,
        ownerAppUserId = owner,
        productId = BillingManager.PRODUCT_VIP_LIFETIME,
        productType = if (source == EntitlementSource.GOOGLE_PLAY_SUBSCRIPTION) "subs" else "inapp",
        purchaseToken = token,
        source = source,
        state = state,
        expiryTimeMillis = null,
        snapshotVersion = version
    )

    private val verifier = object : PurchaseVerifier {
        override suspend fun verifyPurchase(request: VerificationRequest): VerificationResult {
            if (request.purchaseToken == "failing_token") {
                return VerificationResult.Rejected(RejectionReason.INVALID_SIGNATURE_OR_TOKEN, "Receipt is invalid")
            }
            return VerificationResult.Success(
                testEntitlement(
                    owner = request.ownerAppUserId,
                    version = 1L,
                    state = EntitlementState.VERIFIED_ACTIVE,
                    token = request.purchaseToken
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
    }

    @After
    fun cleanup() {
        BillingManager.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
        BillingEntitlementStore.resetInstanceForTesting()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    @Test
    fun testRevoke_thenReload_thenRestart_persistedStateRemainsRevoked() {
        AppAuthManager.setCurrentUserForTesting(user("User1"))
        val initialEntitlement = testEntitlement(owner = "User1", version = 1L)
        AppAuthManager.applyEntitlement(ctx, initialEntitlement)
        assertTrue("User must initially be VIP", AppAuthManager.getCurrentUser()!!.isVipActive)

        // Authoritative server revocation via verifier
        val serverTombstone = initialEntitlement.copy(state = EntitlementState.REVOKED, snapshotVersion = 2L)
        val revokingManager = BillingManager.createInstanceForTesting(ctx, { client }, object : PurchaseVerifier {
            override suspend fun verifyPurchase(request: VerificationRequest) =
                VerificationResult.Rejected(RejectionReason.PURCHASE_REVOKED, "Subscription revoked by Google Play", tombstone = serverTombstone)
        })
        val purchase = createTestPurchase(BillingManager.PRODUCT_VIP_LIFETIME, token = "token_User1", acknowledged = true)
        revokingManager.processPurchase(purchase, BillingOperationOrigin.RESTORE)

        assertFalse("Current user VIP must be revoked after authoritative server revocation", AppAuthManager.getCurrentUser()!!.isVipActive)

        // 1. Reload profile from disk
        val reloadedProfile = user("User1")
        AppAuthManager.loadVipForUserForTesting(ctx, reloadedProfile)
        assertFalse("Reloaded profile must remain FREE", reloadedProfile.isVipActive)

        // 2. Restart store and re-check
        BillingEntitlementStore.resetInstanceForTesting()
        val freshStore = BillingEntitlementStore.getInstance()
        val snapshot = freshStore.getSnapshot(ctx, "User1")
        assertEquals(EntitlementState.REVOKED, snapshot.entitlements.first().state)
        assertEquals(2L, snapshot.entitlements.first().snapshotVersion)
        assertFalse("Store snapshot must remain revoked after restart", snapshot.isVipActive())
    }

    @Test
    fun testPromotionPreservedWhenNoPlayPurchases() {
        AppAuthManager.setCurrentUserForTesting(user("UserPromo"))
        val promo = testEntitlement(
            owner = "UserPromo",
            version = 1L,
            source = EntitlementSource.PROMOTIONAL,
            token = "promo_voucher_100"
        )
        AppAuthManager.applyEntitlement(ctx, promo)
        assertTrue("Promotional VIP must be active", AppAuthManager.getCurrentUser()!!.isVipActive)

        // Empty Play store restore
        manager.restorePurchases { success, _ ->
            assertFalse("No active Play purchases reported", success)
        }

        // Promotional entitlement must be preserved!
        val currentUser = AppAuthManager.getCurrentUser()!!
        assertTrue("Promotional VIP must be preserved despite empty Play query", currentUser.isVipActive)

        val snapshot = store.getSnapshot(ctx, "UserPromo")
        assertEquals(1, snapshot.entitlements.size)
        assertEquals(EntitlementSource.PROMOTIONAL, snapshot.entitlements.first().source)
        assertEquals(EntitlementState.VERIFIED_ACTIVE, snapshot.entitlements.first().state)
    }

    @Test
    fun testPendingOnlyPurchases_doesNotRevokeExistingEntitlements() {
        AppAuthManager.setCurrentUserForTesting(user("UserPending"))
        val existing = testEntitlement(owner = "UserPending", version = 1L)
        AppAuthManager.applyEntitlement(ctx, existing)
        assertTrue(AppAuthManager.getCurrentUser()!!.isVipActive)

        // Client reports 1 pending purchase
        client.addPurchase(
            createTestPurchase(
                productId = BillingManager.PRODUCT_VIP_YEARLY,
                token = "pending_tok",
                purchaseState = Purchase.PurchaseState.PENDING
            )
        )

        val reconciler = BillingReconciliation(
            context = ctx,
            billingClient = client,
            processPurchaseAction = { purchase, onComplete ->
                manager.processPurchase(purchase) { onComplete(it) }
            }
        )

        var result: ReconciliationResult? = null
        reconciler.reconcile { result = it }

        assertTrue("Result should be NoActivePurchases without revoking existing", result is ReconciliationResult.NoActivePurchases)
        assertTrue("Existing VIP must not be revoked when purchases are in pending state", AppAuthManager.getCurrentUser()!!.isVipActive)
    }

    @Test
    fun testStaleQueryFromUserA_doesNotRevokeUserB() {
        AppAuthManager.setCurrentUserForTesting(user("UserA"))
        client.deferQuery = true

        manager.restorePurchases { _, _ -> }

        // User B logs in and sets VIP
        AppAuthManager.setCurrentUserForTesting(user("UserB"))
        AppAuthManager.applyEntitlement(ctx, testEntitlement(owner = "UserB"))
        assertTrue(AppAuthManager.getCurrentUser()!!.isVipActive)

        // A's deferred query arrives now
        client.executeDeferredQueries()

        assertTrue("User B's VIP must remain active after User A's stale query", AppAuthManager.getCurrentUser()!!.isVipActive)
        assertEquals("UserB", AppAuthManager.getCurrentUser()!!.id)
    }

    @Test
    fun testMultipleEntitlements_oneRevokedOneActive_lifetimeDominates() {
        val userId = "UserMulti"
        AppAuthManager.setCurrentUserForTesting(user(userId))

        val monthlySub = testEntitlement(
            owner = userId,
            version = 1L,
            source = EntitlementSource.GOOGLE_PLAY_SUBSCRIPTION,
            token = "sub_monthly_tok"
        )
        val lifetime = testEntitlement(
            owner = userId,
            version = 1L,
            source = EntitlementSource.GOOGLE_PLAY_INAPP,
            token = "life_tok"
        )

        AppAuthManager.applyEntitlement(ctx, monthlySub)
        AppAuthManager.applyEntitlement(ctx, lifetime)
        assertTrue(AppAuthManager.getCurrentUser()!!.isVipActive)

        // Only the lifetime purchase is returned in Play query (monthly is gone)
        client.addPurchase(
            createTestPurchase(BillingManager.PRODUCT_VIP_LIFETIME, token = "life_tok", acknowledged = true)
        )

        manager.restorePurchases { success, _ ->
            assertTrue("Restore succeeds for active lifetime", success)
        }

        assertTrue("User remains VIP active due to lifetime entitlement", AppAuthManager.getCurrentUser()!!.isVipActive)
    }

    @Test
    fun testPartialFailureInBatch_returnsRestoredWithPartialCounts() {
        AppAuthManager.setCurrentUserForTesting(user("UserPartial"))

        val itemGood = createTestPurchase(BillingManager.PRODUCT_VIP_LIFETIME, token = "good_token", acknowledged = true)
        val itemBad = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "failing_token", acknowledged = true)

        client.addPurchase(itemGood)
        client.addPurchase(itemBad)

        val reconciler = BillingReconciliation(
            context = ctx,
            billingClient = client,
            processPurchaseAction = { purchase, onComplete ->
                manager.processPurchase(purchase, BillingOperationOrigin.RESTORE) { onComplete(it) }
            }
        )

        var finalResult: ReconciliationResult? = null
        reconciler.reconcile { res ->
            finalResult = res
        }

        assertTrue("Final result must be Restored", finalResult is ReconciliationResult.Restored)
        val restored = finalResult as ReconciliationResult.Restored
        assertEquals("Successful count must be 1", 1, restored.count)
        assertEquals("Total count must be 2", 2, restored.totalCount)
        assertEquals("Failed count must be 1", 1, restored.failedCount)
    }

    @Test
    fun testNetworkErrorDuringQuery_preservesCachedState() {
        AppAuthManager.setCurrentUserForTesting(user("UserNet"))
        AppAuthManager.applyEntitlement(ctx, testEntitlement(owner = "UserNet"))
        assertTrue(AppAuthManager.getCurrentUser()!!.isVipActive)

        // Set failure code in client
        client.queryResponse = BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE

        val reconciler = BillingReconciliation(
            context = ctx,
            billingClient = client,
            processPurchaseAction = { purchase, onComplete ->
                manager.processPurchase(purchase) { onComplete(it) }
            }
        )

        var result: ReconciliationResult? = null
        reconciler.reconcile { result = it }

        assertTrue("Network error must be reported", result is ReconciliationResult.NetworkError)
        assertTrue("Existing VIP state must be preserved during network error", AppAuthManager.getCurrentUser()!!.isVipActive)
    }
}
