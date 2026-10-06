package com.tscanner.app.utils.billing

import java.util.UUID

/**
 * Type of billing operation (Q02 / R04).
 */
enum class BillingOperationType {
    PURCHASE,
    RESTORE,
    RECONCILE,
    SYNC
}

/**
 * Immutable execution context for a specific billing operation (Q02 / R04).
 *
 * Each operation captures:
 * - [operationId]: Unique correlation ID for tracing the life of this operation.
 * - [ownerAppUserId]: Canonical user ID at initialization (or null for guest).
 * - [sessionGeneration]: Monotonic session generation at initialization.
 * - [operationType]: Purpose of the operation (PURCHASE, RESTORE, RECONCILE, SYNC).
 * - [createdAtMillis]: Creation timestamp.
 *
 * Invariants:
 * - A restore operation MUST NOT inherit or share state with an in-flight purchase operation.
 * - Callbacks arriving after user switch or session generation bump are classified as stale.
 */
data class BillingOperationContext(
    val operationId: String = UUID.randomUUID().toString(),
    val ownerAppUserId: String?,
    val sessionGeneration: Long = 0L,
    val operationType: BillingOperationType = BillingOperationType.PURCHASE,
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    /**
     * Determines whether this operation is stale compared to the current session state.
     */
    fun isStale(currentUserId: String?, currentGeneration: Long): Boolean {
        return ownerAppUserId != currentUserId || sessionGeneration != currentGeneration
    }
}
