package com.tscanner.app

import com.tscanner.app.data.model.VipTier
import com.tscanner.app.utils.billing.BillingEntitlement
import com.tscanner.app.utils.billing.EntitlementSource
import com.tscanner.app.utils.billing.EntitlementState
import com.tscanner.app.utils.billing.NoOpLocalPurchaseVerifier
import com.tscanner.app.utils.billing.UserEntitlementSnapshot
import com.tscanner.app.utils.billing.VerificationRequest
import com.tscanner.app.utils.billing.VerificationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for B03 Entitlement & Ownership Contract.
 * Validates invariants from docs/billing/ENTITLEMENT_CONTRACT.md:
 * - Lifetime has no expiration date (null) and does not expire.
 * - Subscriptions use absolute expiration timestamps, never relative increments upon replay.
 * - Pending, account hold, and revoked purchases never grant VIP privileges.
 * - Idempotent merging never extends expiration dates.
 * - Ownership conflicts (user A vs user B) are rejected.
 * - Multi-entitlements properly resolve the highest active tier.
 * - Missing backend verifier explicitly produces MissingBackendGate, never faking Success.
 */
class BillingEntitlementContractTest {

    private val now = 1727330000000L // arbitrary fixed epoch millis

    @Test
    fun testLifetimeEntitlement_hasNoExpiry_remainsActiveIndefinitely() {
        val lifetime = BillingEntitlement(
            id = "life_1",
            ownerAppUserId = "user_100",
            productId = "tscanner_vip_lifetime",
            productType = "inapp",
            purchaseToken = "tok_life_1",
            source = EntitlementSource.GOOGLE_PLAY_INAPP,
            state = EntitlementState.VERIFIED_ACTIVE,
            purchaseTimeMillis = now,
            expiryTimeMillis = null // No expiration date
        )

        assertNull("Lifetime entitlement must have null expiryTimeMillis", lifetime.expiryTimeMillis)
        assertTrue("Active at purchase time", lifetime.isCurrentlyActive(now))
        assertTrue("Active after 10 years", lifetime.isCurrentlyActive(now + 10L * 365 * 24 * 3600 * 1000))
        assertTrue("Active after 100 years", lifetime.isCurrentlyActive(now + 100L * 365 * 24 * 3600 * 1000))
        assertEquals(VipTier.VIP, lifetime.resolveVipTier())
    }

    @Test
    fun testSubscriptionEntitlement_activeBeforeExpiry_expiresAfterExpiry() {
        val expiry = now + (30L * 24 * 3600 * 1000) // 30 days
        val sub = BillingEntitlement(
            id = "sub_monthly_1",
            ownerAppUserId = "user_100",
            productId = "tscanner_vip_monthly",
            productType = "subs",
            purchaseToken = "tok_sub_1",
            source = EntitlementSource.GOOGLE_PLAY_SUBSCRIPTION,
            state = EntitlementState.VERIFIED_ACTIVE,
            purchaseTimeMillis = now,
            expiryTimeMillis = expiry,
            autoRenewing = true
        )

        assertTrue("Active before expiry", sub.isCurrentlyActive(now + 1000))
        assertTrue("Active 1 second before expiry", sub.isCurrentlyActive(expiry - 1000))
        assertFalse("Inactive exactly at expiry", sub.isCurrentlyActive(expiry))
        assertFalse("Inactive after expiry", sub.isCurrentlyActive(expiry + 1000))
        assertEquals(VipTier.VIP, sub.resolveVipTier(now + 1000))
        assertEquals(VipTier.FREE, sub.resolveVipTier(expiry + 1000))
    }

    @Test
    fun testGracePeriodEntitlement_remainsActiveDuringGracePeriod() {
        val expiry = now + (7L * 24 * 3600 * 1000)
        val graceSub = BillingEntitlement(
            id = "sub_grace_1",
            ownerAppUserId = "user_100",
            productId = "tscanner_vip_yearly",
            productType = "subs",
            purchaseToken = "tok_grace",
            state = EntitlementState.IN_GRACE_PERIOD,
            expiryTimeMillis = expiry
        )

        assertTrue("Active during grace period", graceSub.isCurrentlyActive(now + 1000))
        assertFalse("Inactive after grace period expiry", graceSub.isCurrentlyActive(expiry + 1000))
    }

    @Test
    fun testAccountHoldEntitlement_isNotActive() {
        val holdSub = BillingEntitlement(
            id = "sub_hold_1",
            ownerAppUserId = "user_100",
            productId = "tscanner_vip_yearly",
            productType = "subs",
            purchaseToken = "tok_hold",
            state = EntitlementState.ON_HOLD,
            expiryTimeMillis = now + (30L * 24 * 3600 * 1000)
        )

        assertFalse("Account hold must never grant VIP", holdSub.isCurrentlyActive(now))
        assertEquals(VipTier.FREE, holdSub.resolveVipTier())
    }

    @Test
    fun testPendingPaymentEntitlement_isNotActive() {
        val pending = BillingEntitlement(
            id = "pending_1",
            ownerAppUserId = "user_100",
            productId = "tscanner_vip_yearly",
            productType = "subs",
            purchaseToken = "tok_pending",
            state = EntitlementState.PENDING_PAYMENT,
            expiryTimeMillis = null
        )

        assertFalse("Pending payment must never grant VIP", pending.isCurrentlyActive(now))
        assertEquals(VipTier.FREE, pending.resolveVipTier())
    }

    @Test
    fun testCanceledAutoRenewEntitlement_remainsActiveUntilExpiry() {
        val expiry = now + (15L * 24 * 3600 * 1000)
        val canceledSub = BillingEntitlement(
            id = "canceled_sub",
            ownerAppUserId = "user_100",
            productId = "tscanner_vip_yearly",
            productType = "subs",
            purchaseToken = "tok_cancel",
            state = EntitlementState.CANCELED_ACTIVE,
            autoRenewing = false,
            expiryTimeMillis = expiry
        )

        assertTrue("Active before current term expires even if canceled", canceledSub.isCurrentlyActive(now))
        assertFalse("Inactive after term expires", canceledSub.isCurrentlyActive(expiry + 1000))
    }

    @Test
    fun testRevokedEntitlement_isNeverActive() {
        val revoked = BillingEntitlement(
            id = "revoked_1",
            ownerAppUserId = "user_100",
            productId = "tscanner_vip_yearly",
            productType = "subs",
            purchaseToken = "tok_revoked",
            state = EntitlementState.REVOKED,
            expiryTimeMillis = now + (365L * 24 * 3600 * 1000)
        )

        assertFalse("Revoked purchase must never grant VIP even if future expiry date exists", revoked.isCurrentlyActive(now))
        assertEquals(VipTier.FREE, revoked.resolveVipTier())
    }

    @Test
    fun testSnapshotMerge_idempotent_neverAccumulatesExpiry() {
        val originalExpiry = now + (365L * 24 * 3600 * 1000)
        val initialSub = BillingEntitlement(
            id = "ent_yearly",
            ownerAppUserId = "user_100",
            productId = "tscanner_vip_yearly",
            productType = "subs",
            purchaseToken = "tok_yearly",
            state = EntitlementState.VERIFIED_ACTIVE,
            expiryTimeMillis = originalExpiry,
            snapshotVersion = 1L
        )

        val snapshotA = UserEntitlementSnapshot(
            ownerAppUserId = "user_100",
            entitlements = listOf(initialSub)
        )

        // Incoming snapshot for the exact same purchase (e.g. repeated restore)
        val incomingSub = initialSub.copy(snapshotVersion = 2L)
        val snapshotB = UserEntitlementSnapshot(
            ownerAppUserId = "user_100",
            entitlements = listOf(incomingSub)
        )

        val merged = snapshotA.mergeNewerSnapshot(snapshotB)
        assertEquals(1, merged.entitlements.size)
        val mergedItem = merged.entitlements.first()
        assertEquals(
            "Expiry must remain strictly absolute and not accumulate additional time",
            originalExpiry,
            mergedItem.expiryTimeMillis
        )
        assertEquals(2L, mergedItem.snapshotVersion)
    }

    @Test
    fun testSnapshotMerge_olderVersionCannotOverwriteNewerVersion() {
        val newer = BillingEntitlement(
            id = "ent_1",
            ownerAppUserId = "user_100",
            productId = "tscanner_vip_yearly",
            productType = "subs",
            purchaseToken = "tok_1",
            state = EntitlementState.VERIFIED_ACTIVE,
            snapshotVersion = 5L
        )
        val existingSnapshot = UserEntitlementSnapshot("user_100", listOf(newer))

        val older = newer.copy(state = EntitlementState.EXPIRED, snapshotVersion = 3L)
        val staleSnapshot = UserEntitlementSnapshot("user_100", listOf(older))

        val merged = existingSnapshot.mergeNewerSnapshot(staleSnapshot)
        assertEquals(EntitlementState.VERIFIED_ACTIVE, merged.entitlements.first().state)
        assertEquals(5L, merged.entitlements.first().snapshotVersion)
    }

    @Test
    fun testOwnershipConflict_cannotMergeDifferentOwner() {
        val snapUserA = UserEntitlementSnapshot(
            ownerAppUserId = "user_A",
            entitlements = listOf(
                BillingEntitlement(
                    id = "tok_1",
                    ownerAppUserId = "user_A",
                    productId = "tscanner_vip_yearly",
                    productType = "subs",
                    purchaseToken = "tok_1",
                    state = EntitlementState.VERIFIED_ACTIVE
                )
            )
        )

        val snapUserB = UserEntitlementSnapshot(
            ownerAppUserId = "user_B",
            entitlements = emptyList()
        )

        try {
            snapUserA.mergeNewerSnapshot(snapUserB)
            fail("Must throw IllegalArgumentException on ownership conflict")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Ownership conflict"))
        }
    }

    @Test
    fun testMultiEntitlement_prefersHighestActiveTier() {
        val monthly = BillingEntitlement(
            id = "sub_month",
            ownerAppUserId = "user_100",
            productId = "tscanner_vip_monthly",
            productType = "subs",
            purchaseToken = "tok_m",
            state = EntitlementState.VERIFIED_ACTIVE,
            expiryTimeMillis = now + (30L * 24 * 3600 * 1000)
        )
        val lifetime = BillingEntitlement(
            id = "inapp_life",
            ownerAppUserId = "user_100",
            productId = "tscanner_vip_lifetime",
            productType = "inapp",
            purchaseToken = "tok_l",
            state = EntitlementState.VERIFIED_ACTIVE,
            expiryTimeMillis = null
        )

        val snapshot = UserEntitlementSnapshot(
            ownerAppUserId = "user_100",
            entitlements = listOf(monthly, lifetime)
        )

        assertTrue(snapshot.isVipActive(now))
        assertTrue(snapshot.isLifetimeActive(now))
        assertEquals(VipTier.VIP, snapshot.getHighestActiveTier(now))

        // If lifetime gets revoked, falls back cleanly to monthly VIP (not lifetime)
        val lifetimeRevoked = lifetime.copy(state = EntitlementState.REVOKED)
        val updatedSnapshot = UserEntitlementSnapshot(
            ownerAppUserId = "user_100",
            entitlements = listOf(monthly, lifetimeRevoked)
        )

        assertTrue(updatedSnapshot.isVipActive(now))
        assertFalse(updatedSnapshot.isLifetimeActive(now))
        assertEquals(VipTier.VIP, updatedSnapshot.getHighestActiveTier(now))
    }

    @Test
    fun testUnknownLegacyLocalVip_doesNotBecomeVerified() {
        val legacy = BillingEntitlement(
            id = "legacy_1",
            ownerAppUserId = "user_100",
            productId = "tscanner_vip_yearly",
            productType = "subs",
            purchaseToken = "unknown_legacy_token",
            source = EntitlementSource.LEGACY_LOCAL,
            state = EntitlementState.UNVERIFIED_CLIENT
        )

        assertFalse("Legacy unverified entitlement must never be active verified VIP", legacy.isCurrentlyActive(now))
        assertEquals(VipTier.FREE, legacy.resolveVipTier())
    }

    @Test
    fun testNoOpLocalPurchaseVerifier_returnsMissingBackendGate_neverFakesVerified() = runBlocking {
        val verifier = NoOpLocalPurchaseVerifier()
        val request = VerificationRequest(
            ownerAppUserId = "user_100",
            productId = "tscanner_vip_yearly",
            productType = "subs",
            purchaseToken = "tok_test_123"
        )

        val result = verifier.verifyPurchase(request)
        assertTrue("Verifier must report missing backend gate", result is VerificationResult.MissingBackendGate)
        assertFalse("Verifier must NEVER fake Success when backend is absent", result is VerificationResult.Success)
    }
}
