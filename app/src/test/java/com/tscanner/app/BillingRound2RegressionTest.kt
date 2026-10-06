package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.android.billingclient.api.Purchase
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.billing.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

/**
 * Permanent regression suite for VIP Billing Round 2 defects and controls.
 *
 * - probePendingFixtureMustRepresentPendingPurchase (R11): FIXED in Q00 via BillingTestFixtures.kt.
 * - controlDuplicateVerifiedReceiptDoesNotExtend: PASSING.
 * - controlConfiguredRemoteVerifierStillReportsMissingImplementation: PASSING.
 * - 7 production probes (R02, R03, R04, R05, R06) intentionally reproduce defects until fixed in Q01-Q04.
 */
class BillingRound2RegressionTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var ctx: BillingTestContext
    private lateinit var client: FakeBillingClientWrapper
    private lateinit var manager: BillingManager
    private val store get() = BillingEntitlementStore.getInstance()
    private fun user(id: String) = UserProfile(id, "$id@example.test", id)
    private fun entitlement(owner: String = "A", source: EntitlementSource = EntitlementSource.GOOGLE_PLAY_INAPP,
                            version: Long = 1, state: EntitlementState = EntitlementState.VERIFIED_ACTIVE) = BillingEntitlement(
        id = "receipt", ownerAppUserId = owner, productId = BillingManager.PRODUCT_VIP_LIFETIME,
        productType = "inapp", purchaseToken = "receipt", source = source, state = state,
        expiryTimeMillis = null, snapshotVersion = version)
    private val verifier = object : PurchaseVerifier {
        override suspend fun verifyPurchase(request: VerificationRequest) = VerificationResult.Success(
            entitlement(request.ownerAppUserId ?: "guest").copy(id=request.purchaseToken, purchaseToken=request.purchaseToken))
    }
    @Before fun setup() {
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
            override fun executeOnDiskIO(r: Runnable) = r.run()
            override fun postToMainThread(r: Runnable) = r.run()
            override fun isMainThread() = true
        })
        BillingManager.resetInstanceForTesting(); AppAuthManager.resetForTesting(); BillingEntitlementStore.resetInstanceForTesting()
        ctx = BillingTestContext(tmp.root); client = FakeBillingClientWrapper()
        manager = BillingManager.createInstanceForTesting(ctx, {client}, verifier)
        AppAuthManager.setCurrentUserForTesting(user("A"))
    }
    @After fun cleanup() {
        BillingManager.resetInstanceForTesting(); AppAuthManager.resetForTesting(); BillingEntitlementStore.resetInstanceForTesting()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }
    @Test fun probeRevokedReceiptMustNotReturnOnReload() {
        val initial = entitlement()
        AppAuthManager.applyEntitlement(ctx, initial)
        val serverTombstone = initial.copy(state = EntitlementState.REVOKED, snapshotVersion = 2L)
        val revokingVerifier = object : PurchaseVerifier {
            override suspend fun verifyPurchase(request: VerificationRequest) =
                VerificationResult.Rejected(RejectionReason.PURCHASE_REVOKED, "Authoritatively revoked by Google", tombstone = serverTombstone)
        }
        val revokingManager = BillingManager.createInstanceForTesting(ctx, { client }, revokingVerifier)
        revokingManager.processPurchase(createTestPurchase(BillingManager.PRODUCT_VIP_LIFETIME, acknowledged = true))
        assertFalse("User must be revoked after authoritative revocation", AppAuthManager.getCurrentUser()!!.isVipActive)
        val reloaded = user("A")
        AppAuthManager.loadVipForUserForTesting(ctx, reloaded)
        assertFalse("Entitlement store must not resurrect receipt after authoritative revocation", reloaded.isVipActive)
    }
    @Test fun probeEmptyPlayQueryMustPreservePromotion() {
        AppAuthManager.applyEntitlement(ctx, entitlement(source=EntitlementSource.PROMOTIONAL))
        manager.restorePurchases { _, _ -> }
        assertTrue("Empty Play catalog removed non-Play promotion", AppAuthManager.getCurrentUser()!!.isVipActive)
    }
    @Test fun probeOldEmptyQueryMustNotRevokeNewAccount() {
        client.deferQuery = true
        manager.restorePurchases { _, _ -> }
        AppAuthManager.setCurrentUserForTesting(user("B"))
        AppAuthManager.applyEntitlement(ctx, entitlement(owner="B"))
        client.executeDeferredQueries()
        assertTrue("A's delayed empty query revoked B", AppAuthManager.getCurrentUser()!!.isVipActive)
    }
    @Test fun probeOwnedReceiptMustNotReappearAsGuestAndBindToB() {
        manager.processPurchase(createTestPurchase(BillingManager.PRODUCT_VIP_LIFETIME, acknowledged=true))
        assertTrue(AppAuthManager.getCurrentUser()!!.isVipActive)
        AppAuthManager.setCurrentUserForTesting(user("B"))
        manager.bindPurchasesToCurrentUser(ctx)
        assertFalse("Global last receipt recreated as verified legacy and bound to B", AppAuthManager.getCurrentUser()!!.isVipActive)
    }
    @Test fun probeEqualVersionCannotResurrectRevokedState() {
        store.applyEntitlement(ctx, entitlement(version=2, state=EntitlementState.REVOKED))
        store.applyEntitlement(ctx, entitlement(version=2))
        assertFalse("Equal-version conflicting snapshot reactivates revoked VIP", store.getSnapshot(ctx,"A").isVipActive())
    }
    @Test fun probeCommitFailureMustNotReportPurchaseSuccess() {
        BillingManager.resetInstanceForTesting()
        val failing = object : ContextWrapper(ctx) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                val real = ctx.getSharedPreferences(name, mode)
                if (name != "tscanner_billing_entitlements") return real
                return object : SharedPreferences by real {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = real.edit()
                        return object : SharedPreferences.Editor by editor { override fun commit() = false }
                    }
                }
            }
        }
        manager = BillingManager.createInstanceForTesting(failing, {client}, verifier)
        var success: Boolean? = null
        manager.processPurchase(createTestPurchase(BillingManager.PRODUCT_VIP_LIFETIME, acknowledged=true)) { success=it }
        assertEquals("Persistence failed but purchase returned success", false, success)
    }
    @Test fun probeRestoreMustNotReusePreviousPurchaseOwner() {
        manager.setActivePurchaseOwnerForTesting("A")
        AppAuthManager.setCurrentUserForTesting(user("B"))
        var requestedOwner: String? = null
        manager.setPurchaseVerifierForTesting(object : PurchaseVerifier {
            override suspend fun verifyPurchase(request: VerificationRequest): VerificationResult {
                requestedOwner=request.ownerAppUserId
                return VerificationResult.MissingBackendGate("probe")
            }
        })
        client.addPurchase(createTestPurchase(BillingManager.PRODUCT_VIP_LIFETIME, acknowledged=true))
        manager.restorePurchases { _, _ -> }
        assertEquals("Restore inherited stale purchase owner A", "B", requestedOwner)
    }
    @Test fun probePendingFixtureMustRepresentPendingPurchase() {
        val pending = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, purchaseState=Purchase.PurchaseState.PENDING)
        assertEquals("Fixture JSON does not encode SDK pending state", Purchase.PurchaseState.PENDING, pending.purchaseState)
    }
    @Test fun controlDuplicateVerifiedReceiptDoesNotExtend() {
        val value=entitlement().copy(expiryTimeMillis=System.currentTimeMillis()+86400000)
        AppAuthManager.applyEntitlement(ctx,value); val first=AppAuthManager.getCurrentUser()!!.vipExpiresAt
        AppAuthManager.applyEntitlement(ctx,value)
        assertEquals(first,AppAuthManager.getCurrentUser()!!.vipExpiresAt)
    }
    @Test fun controlConfiguredRemoteVerifierReportsTransientErrorOnUnreachableHost() = runBlocking {
        val result = PlayPurchaseVerifier("https://billing.example.test", false).verifyPurchase(
            VerificationRequest("A", BillingManager.PRODUCT_VIP_LIFETIME, "inapp", "synthetic-receipt"))
        assertTrue(result is VerificationResult.TransientError)
    }
}
