package com.tscanner.app.utils.billing

import com.tscanner.app.data.model.VipTier

/**
 * Origin source of an entitlement.
 */
enum class EntitlementSource {
    GOOGLE_PLAY_SUBSCRIPTION,
    GOOGLE_PLAY_INAPP,
    LEGACY_LOCAL,
    PROMOTIONAL
}

/**
 * Lifecycle state of a purchased or granted entitlement.
 */
enum class EntitlementState {
    /**
     * Authenticated and confirmed by authoritative source (Google Play Developer API).
     */
    VERIFIED_ACTIVE,

    /**
     * In grace period (payment failed, Google Play allows user to continue using VIP temporarily).
     */
    IN_GRACE_PERIOD,

    /**
     * User canceled auto-renew, but current paid period has not yet expired.
     */
    CANCELED_ACTIVE,

    /**
     * Pending payment (e.g. slow credit card or cash payment at local store).
     * Entitlement MUST NOT be granted while in this state.
     */
    PENDING_PAYMENT,

    /**
     * Account on hold (grace period ended without successful payment).
     * VIP privileges MUST be suspended.
     */
    ON_HOLD,

    /**
     * User paused subscription. VIP privileges suspended during pause.
     */
    PAUSED,

    /**
     * Paid period expired without renewal.
     */
    EXPIRED,

    /**
     * Purchase was refunded, charged back, or explicitly revoked.
     */
    REVOKED,

    /**
     * Reported by client Play SDK as PURCHASED, but authoritative server verification
     * is pending or has not completed. Must NOT automatically be treated as active paid VIP.
     */
    UNVERIFIED_CLIENT
}

/**
 * Immutable entitlement representation satisfying the contract in docs/billing/ENTITLEMENT_CONTRACT.md.
 */
data class BillingEntitlement(
    val id: String,
    val ownerAppUserId: String?,
    val productId: String,
    val productType: String,
    val purchaseToken: String,
    val orderId: String? = null,
    val source: EntitlementSource = EntitlementSource.GOOGLE_PLAY_SUBSCRIPTION,
    val state: EntitlementState = EntitlementState.UNVERIFIED_CLIENT,
    val purchaseTimeMillis: Long = System.currentTimeMillis(),
    val expiryTimeMillis: Long? = null, // null represents lifetime / non-expiring entitlement
    val autoRenewing: Boolean = false,
    val verifiedAtMillis: Long? = null,
    val snapshotVersion: Long = 1L,
    val rawPayloadSignature: String? = null
) {
    /**
     * Determines whether this entitlement grants active VIP status at [currentTimeMillis].
     *
     * Invariants:
     * - Only states {VERIFIED_ACTIVE, IN_GRACE_PERIOD, CANCELED_ACTIVE} can be active.
     * - If [expiryTimeMillis] is non-null, [currentTimeMillis] must be strictly less than [expiryTimeMillis].
     * - If [expiryTimeMillis] is null (Lifetime), it remains active as long as state is VERIFIED_ACTIVE.
     */
    fun isCurrentlyActive(currentTimeMillis: Long = System.currentTimeMillis()): Boolean {
        val isPermittedState = state == EntitlementState.VERIFIED_ACTIVE ||
                state == EntitlementState.IN_GRACE_PERIOD ||
                state == EntitlementState.CANCELED_ACTIVE

        if (!isPermittedState) {
            return false
        }

        return if (expiryTimeMillis != null) {
            currentTimeMillis < expiryTimeMillis
        } else {
            // Lifetime entitlement is active whenever in VERIFIED_ACTIVE
            state == EntitlementState.VERIFIED_ACTIVE
        }
    }

    /**
     * Resolves the corresponding VipTier for this entitlement.
     */
    fun resolveVipTier(currentTimeMillis: Long = System.currentTimeMillis()): VipTier {
        if (!isCurrentlyActive(currentTimeMillis)) return VipTier.FREE
        return VipTier.VIP
    }
}

/**
 * Represents a complete snapshot of all entitlements belonging to a user at a specific point in time.
 */
data class UserEntitlementSnapshot(
    val ownerAppUserId: String?,
    val entitlements: List<BillingEntitlement> = emptyList(),
    val computedAtMillis: Long = System.currentTimeMillis()
) {
    /**
     * Returns all currently active entitlements in this snapshot.
     */
    fun getActiveEntitlements(currentTimeMillis: Long = System.currentTimeMillis()): List<BillingEntitlement> {
        return entitlements.filter { it.isCurrentlyActive(currentTimeMillis) }
    }

    /**
     * Returns true if user has at least one active VIP entitlement.
     */
    fun isVipActive(currentTimeMillis: Long = System.currentTimeMillis()): Boolean {
        return getActiveEntitlements(currentTimeMillis).isNotEmpty()
    }

    /**
     * Returns true if any active entitlement is a non-expiring lifetime entitlement.
     */
    fun isLifetimeActive(currentTimeMillis: Long = System.currentTimeMillis()): Boolean {
        return getActiveEntitlements(currentTimeMillis).any { it.expiryTimeMillis == null }
    }

    /**
     * Determines the highest active tier among all entitlements.
     */
    fun getHighestActiveTier(currentTimeMillis: Long = System.currentTimeMillis()): VipTier {
        return if (isVipActive(currentTimeMillis)) VipTier.VIP else VipTier.FREE
    }

    /**
     * Merges an incoming snapshot into this snapshot with idempotency and monotonic versioning.
     *
     * Invariants:
     * - Replay of identical or older snapshot versions does NOT alter expiry dates or mutate state.
     * - Cannot merge snapshot belonging to a different non-null ownerAppUserId (throws [IllegalArgumentException]).
     * - Expiry time is never accumulated/extended by merge (expiry is absolute).
     */
    fun mergeNewerSnapshot(incoming: UserEntitlementSnapshot): UserEntitlementSnapshot {
        if (this.ownerAppUserId != null && incoming.ownerAppUserId != null && this.ownerAppUserId != incoming.ownerAppUserId) {
            throw IllegalArgumentException(
                "Ownership conflict: cannot merge entitlement snapshot for user '${incoming.ownerAppUserId}' into '${this.ownerAppUserId}'"
            )
        }

        val effectiveOwner = incoming.ownerAppUserId ?: this.ownerAppUserId
        val mergedEntitlementsMap = mutableMapOf<String, BillingEntitlement>()

        // 1. Seed with current entitlements, deduplicating legacy entries sharing the same purchaseToken
        for (entitlement in this.entitlements) {
            val existing = if (entitlement.purchaseToken.isNotBlank()) {
                mergedEntitlementsMap.values.find { it.purchaseToken.isNotBlank() && it.purchaseToken == entitlement.purchaseToken }
            } else null

            if (existing == null) {
                mergedEntitlementsMap[entitlement.id] = entitlement
            } else {
                if (entitlement.snapshotVersion > existing.snapshotVersion) {
                    mergedEntitlementsMap.remove(existing.id)
                    mergedEntitlementsMap[entitlement.id] = entitlement
                }
            }
        }

        // 2. Overlay incoming entitlements if newer or equal (deduplicating by purchaseToken across ID variations)
        for (incomingItem in incoming.entitlements) {
            // Validate owner of each item matches snapshot owner
            if (effectiveOwner != null && incomingItem.ownerAppUserId != null && incomingItem.ownerAppUserId != effectiveOwner) {
                throw IllegalArgumentException(
                    "Item owner mismatch: item '${incomingItem.id}' owner '${incomingItem.ownerAppUserId}' does not match snapshot owner '$effectiveOwner'"
                )
            }

            val existingByToken = if (incomingItem.purchaseToken.isNotBlank()) {
                mergedEntitlementsMap.values.find {
                    it.purchaseToken.isNotBlank() && it.purchaseToken == incomingItem.purchaseToken
                }
            } else null
            val existingItem = existingByToken ?: mergedEntitlementsMap[incomingItem.id]

            if (existingItem == null) {
                mergedEntitlementsMap[incomingItem.id] = incomingItem
            } else if (incomingItem.snapshotVersion > existingItem.snapshotVersion) {
                if (existingItem.id != incomingItem.id) {
                    mergedEntitlementsMap.remove(existingItem.id)
                }
                mergedEntitlementsMap[incomingItem.id] = incomingItem
            } else if (incomingItem.snapshotVersion == existingItem.snapshotVersion) {
                if (isPayloadIdentical(incomingItem, existingItem)) {
                    // Idempotent replay: retain existing, no mutation
                } else {
                    // Equal-version conflict: different payload for same token/ID at same version (R08)
                    throw IllegalArgumentException(
                        "Equal-version conflict: token '${incomingItem.purchaseToken.ifBlank { incomingItem.id }}' v${incomingItem.snapshotVersion} has conflicting payload (incoming state=${incomingItem.state}, existing state=${existingItem.state})"
                    )
                }
            } else {
                // incomingItem.snapshotVersion < existingItem.snapshotVersion: stale, ignore
                android.util.Log.d("BillingEntitlement", "Ignoring stale snapshot update for item '${incomingItem.id}': incoming v${incomingItem.snapshotVersion} < existing v${existingItem.snapshotVersion}")
            }
        }

        return UserEntitlementSnapshot(
            ownerAppUserId = effectiveOwner,
            entitlements = mergedEntitlementsMap.values.toList(),
            computedAtMillis = maxOf(this.computedAtMillis, incoming.computedAtMillis)
        )
    }

    private fun isPayloadIdentical(a: BillingEntitlement, b: BillingEntitlement): Boolean {
        return a.state == b.state &&
                a.productId == b.productId &&
                a.productType == b.productType &&
                a.purchaseToken == b.purchaseToken &&
                a.expiryTimeMillis == b.expiryTimeMillis &&
                a.autoRenewing == b.autoRenewing
    }
}
