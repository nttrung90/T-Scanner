package com.tscanner.app.utils.billing

/**
 * Origin of a billing operation (F08).
 * Distinguishes between interactive user actions and silent background state synchronizations.
 */
enum class BillingOperationOrigin {
    /**
     * Interactive checkout flow initiated by the user clicking purchase.
     * Emits interactive purchase UI success/failure callbacks.
     */
    PURCHASE,

    /**
     * Interactive restore flow initiated by the user clicking "Khôi phục giao dịch".
     * Only emits completion to the restorePurchases caller callback.
     * Does NOT emit interactive purchase UI events.
     */
    RESTORE,

    /**
     * Silent background reconciliation on app startup, foregrounding, or login.
     * Updates internal entitlement store without emitting any interactive UI events.
     */
    RECONCILE
}
