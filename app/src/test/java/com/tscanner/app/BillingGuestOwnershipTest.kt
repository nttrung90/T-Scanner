package com.tscanner.app

import android.content.Context
import com.android.billingclient.api.Purchase
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.billing.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit and integration tests for VIP Billing Guest Ownership & Legacy Migration (Q01 / R02).
 *
 * Verifies:
 * - A mua -> B login -> C login: Verified purchase belonging to A is never granted to B or C.
 * - Record for A is completely preserved in BillingEntitlementStore.
 * - True guest purchase differs from legacy: valid guest purchase binds to user upon sign-in.
 * - Legacy local preferences are migrated as UNVERIFIED_CLIENT and do not self-grant paid VIP.
 * - Owner conflict: tokens already bound to another registered user cannot be claimed.
 * - Multiple consecutive logins/binds are idempotent.
 * - Crash between bind and clear does not corrupt or duplicate entitlements.
 * - Local binding does NOT increment server snapshotVersion.
 */
class BillingGuestOwnershipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ctx: BillingTestContext
    private lateinit var client: FakeBillingClientWrapper
    private lateinit var manager: BillingManager
    private val store get() = BillingEntitlementStore.getInstance()

    private fun user(id: String) = UserProfile(id, "$id@example.test", "User $id")

    private val verifier = object : PurchaseVerifier {
        override suspend fun verifyPurchase(request: VerificationRequest): VerificationResult {
            val owner = request.ownerAppUserId ?: "guest"
            return VerificationResult.Success(
                BillingEntitlement(
                    id = request.purchaseToken,
                    ownerAppUserId = request.ownerAppUserId,
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
    }

    @After
    fun cleanup() {
        BillingManager.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
        BillingEntitlementStore.resetInstanceForTesting()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    @Test
    fun testUserAPurchases_thenUserBLogsIn_thenUserCLogsIn_noVipGrantedToBOrC() {
        // 1. User A is logged in and purchases lifetime VIP
        AppAuthManager.setCurrentUserForTesting(user("A"))
        val purchase = createTestPurchase(BillingManager.PRODUCT_VIP_LIFETIME, token = "receipt_A", acknowledged = true)
        manager.processPurchase(purchase)

        assertTrue("User A must have active VIP", AppAuthManager.getCurrentUser()!!.isVipActive)

        // 2. User B logs in
        AppAuthManager.setCurrentUserForTesting(user("B"))
        manager.bindPurchasesToCurrentUser(ctx)

        assertFalse("User B must NOT receive VIP from User A's purchase", AppAuthManager.getCurrentUser()!!.isVipActive)
        assertFalse("User B snapshot must not be VIP active", store.getSnapshot(ctx, "B").isVipActive())

        // 3. User C logs in
        AppAuthManager.setCurrentUserForTesting(user("C"))
        manager.bindPurchasesToCurrentUser(ctx)

        assertFalse("User C must NOT receive VIP from User A's purchase", AppAuthManager.getCurrentUser()!!.isVipActive)
        assertFalse("User C snapshot must not be VIP active", store.getSnapshot(ctx, "C").isVipActive())

        // 4. Verify User A's entitlement record remains intact and active
        val userASnapshot = store.getSnapshot(ctx, "A")
        assertTrue("User A entitlement record must remain active in store", userASnapshot.isVipActive())
        assertEquals(1, userASnapshot.entitlements.size)
        assertEquals("receipt_A", userASnapshot.entitlements.first().purchaseToken)
    }

    @Test
    fun testTrueGuestPurchase_differsFromLegacy_bindsToUserOnSignIn() {
        // 1. User is not logged in (Guest)
        AppAuthManager.setCurrentUserForTesting(null)

        val guestPurchase = createTestPurchase(
            BillingManager.PRODUCT_VIP_LIFETIME,
            token = "guest_token_123",
            acknowledged = true
        )
        manager.processPurchase(guestPurchase)

        // Guest store has the entitlement
        val guestSnap = store.getSnapshot(ctx, null)
        assertEquals(1, guestSnap.entitlements.size)
        assertEquals(EntitlementState.VERIFIED_ACTIVE, guestSnap.entitlements.first().state)

        // 2. User signs in
        val signedInUser = user("registered_user_1")
        AppAuthManager.setCurrentUserForTesting(signedInUser)
        manager.bindPurchasesToCurrentUser(ctx)

        // User must inherit the guest purchase
        assertTrue("Signed in user must inherit true guest purchase", AppAuthManager.getCurrentUser()!!.isVipActive)
        assertTrue("User snapshot in store must be VIP active", store.getSnapshot(ctx, "registered_user_1").isVipActive())

        // Guest store must be cleared
        assertTrue("Guest snapshot must be empty after binding", store.getSnapshot(ctx, null).entitlements.isEmpty())
    }

    @Test
    fun testLegacyLocalPurchase_migratedAsUnverifiedClient_doesNotGrantPaidVip() {
        // Simulate legacy SharedPreferences flags from an older app release
        val prefs = ctx.getSharedPreferences("tscanner_billing_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("billing_vip_active", true)
            .putString("last_purchased_product", BillingManager.PRODUCT_VIP_YEARLY)
            .putString("last_purchase_token", "legacy_raw_token_777")
            .putLong("last_purchase_time", System.currentTimeMillis() - 100000)
            .apply()

        // User logs in
        val user = user("migrated_user")
        AppAuthManager.setCurrentUserForTesting(user)
        manager.bindPurchasesToCurrentUser(ctx)

        // Must NOT grant paid VIP
        assertFalse("Legacy migration must NOT grant paid VIP", AppAuthManager.getCurrentUser()!!.isVipActive)

        // Entitlement must be stored as UNVERIFIED_CLIENT
        val snapshot = store.getSnapshot(ctx, "migrated_user")
        val migratedItem = snapshot.entitlements.firstOrNull { it.purchaseToken == "legacy_raw_token_777" }
        assertNotNull("Migrated item must exist in store", migratedItem)
        assertEquals("Legacy migration must be UNVERIFIED_CLIENT", EntitlementState.UNVERIFIED_CLIENT, migratedItem!!.state)
        assertEquals(EntitlementSource.LEGACY_LOCAL, migratedItem.source)

        // Legacy flag must be consumed
        assertFalse("Legacy billing_vip_active flag must be cleared", prefs.contains("billing_vip_active"))
    }

    @Test
    fun testOwnerConflict_guestStoreTokenBelongsToUserA_cannotBindToUserB() {
        // User A owns the token in store
        val entitlementA = BillingEntitlement(
            id = "token_conflict_888",
            ownerAppUserId = "user_A",
            productId = BillingManager.PRODUCT_VIP_LIFETIME,
            productType = "inapp",
            purchaseToken = "token_conflict_888",
            source = EntitlementSource.GOOGLE_PLAY_INAPP,
            state = EntitlementState.VERIFIED_ACTIVE
        )
        store.applyEntitlement(ctx, entitlementA)

        // Simulate guest store having an entitlement with the same token
        val guestEntitlement = entitlementA.copy(ownerAppUserId = null)
        store.applyEntitlement(ctx, guestEntitlement)

        // User B logs in and tries to bind
        val bound = store.bindGuestEntitlementsToUser(ctx, "user_B")
        assertTrue(bound)

        // User B must NOT have received token_conflict_888
        val snapB = store.getSnapshot(ctx, "user_B")
        assertFalse("User B must not have any entitlements", snapB.entitlements.any { it.purchaseToken == "token_conflict_888" })
        assertFalse("User B must not be VIP", snapB.isVipActive())

        // User A still owns token_conflict_888
        val snapA = store.getSnapshot(ctx, "user_A")
        assertTrue("User A must still own the token", snapA.entitlements.any { it.purchaseToken == "token_conflict_888" })
        assertTrue("User A must still be VIP", snapA.isVipActive())
    }

    @Test
    fun testMultipleLogins_idempotentAndSafe() {
        // True guest purchase
        AppAuthManager.setCurrentUserForTesting(null)
        val guestPurchase = createTestPurchase(BillingManager.PRODUCT_VIP_LIFETIME, token = "idempotent_tok", acknowledged = true)
        manager.processPurchase(guestPurchase)

        val user = user("repeat_user")
        AppAuthManager.setCurrentUserForTesting(user)

        // Multiple calls to bind
        manager.bindPurchasesToCurrentUser(ctx)
        val snap1 = store.getSnapshot(ctx, "repeat_user")
        assertEquals(1, snap1.entitlements.size)

        manager.bindPurchasesToCurrentUser(ctx)
        val snap2 = store.getSnapshot(ctx, "repeat_user")
        assertEquals(1, snap2.entitlements.size)

        manager.bindPurchasesToCurrentUser(ctx)
        val snap3 = store.getSnapshot(ctx, "repeat_user")
        assertEquals(1, snap3.entitlements.size)

        assertEquals("Snapshot version must remain unchanged", snap1.entitlements.first().snapshotVersion, snap3.entitlements.first().snapshotVersion)
        assertTrue(snap3.isVipActive())
    }

    @Test
    fun testCrashBetweenBindAndClear_secondCallClearsAndDoesNotDuplicate() {
        val guestEntitlement = BillingEntitlement(
            id = "crash_recovery_tok",
            ownerAppUserId = null,
            productId = BillingManager.PRODUCT_VIP_LIFETIME,
            productType = "inapp",
            purchaseToken = "crash_recovery_tok",
            source = EntitlementSource.GOOGLE_PLAY_INAPP,
            state = EntitlementState.VERIFIED_ACTIVE,
            snapshotVersion = 1L
        )
        store.applyEntitlement(ctx, guestEntitlement)

        // Simulate crash right after applySnapshot but before removing guest prefs
        val userEntitlement = guestEntitlement.copy(ownerAppUserId = "user_recovered")
        store.applyEntitlement(ctx, userEntitlement)
        // Notice: guest store still has the item!
        assertEquals(1, store.getSnapshot(ctx, null).entitlements.size)

        // Next launch / login runs bindGuestEntitlementsToUser
        val bound = store.bindGuestEntitlementsToUser(ctx, "user_recovered")
        assertTrue(bound)

        // Guest record must now be cleared
        assertTrue("Guest store must be cleared after recovery", store.getSnapshot(ctx, null).entitlements.isEmpty())

        // User snapshot must NOT have duplicated entitlements
        val userSnap = store.getSnapshot(ctx, "user_recovered")
        assertEquals(1, userSnap.entitlements.size)
        assertEquals("crash_recovery_tok", userSnap.entitlements.first().purchaseToken)
    }

    @Test
    fun testLocalBindDoesNotIncrementSnapshotVersion() {
        val serverVersion = 7L
        val guestEntitlement = BillingEntitlement(
            id = "version_test_tok",
            ownerAppUserId = null,
            productId = BillingManager.PRODUCT_VIP_LIFETIME,
            productType = "inapp",
            purchaseToken = "version_test_tok",
            source = EntitlementSource.GOOGLE_PLAY_INAPP,
            state = EntitlementState.VERIFIED_ACTIVE,
            snapshotVersion = serverVersion
        )
        store.applyEntitlement(ctx, guestEntitlement)

        store.bindGuestEntitlementsToUser(ctx, "target_user")

        val userSnap = store.getSnapshot(ctx, "target_user")
        val boundItem = userSnap.entitlements.first()
        assertEquals("Local bind must NOT increment server snapshotVersion", serverVersion, boundItem.snapshotVersion)
    }
}
