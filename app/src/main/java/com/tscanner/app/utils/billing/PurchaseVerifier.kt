package com.tscanner.app.utils.billing

/**
 * Data payload submitted to the verifier (local or remote backend).
 */
data class VerificationRequest(
    val ownerAppUserId: String?,
    val productId: String,
    val productType: String,
    val purchaseToken: String,
    val orderId: String? = null,
    val obfuscatedAccountId: String? = null,
    val clientPurchaseTimeMillis: Long = System.currentTimeMillis()
)

/**
 * Reasons why a purchase verification may be permanently rejected by the backend or verifier.
 */
enum class RejectionReason {
    /**
     * The purchase token is already permanently bound to another canonical user account.
     */
    OWNERSHIP_CONFLICT,

    /**
     * Token or cryptographic signature could not be verified by Google Play Developer API.
     */
    INVALID_SIGNATURE_OR_TOKEN,

    /**
     * Purchase was refunded, charged back, or revoked by Google.
     */
    PURCHASE_REVOKED,

    /**
     * Purchase subscription period has already expired.
     */
    PURCHASE_EXPIRED,

    /**
     * Product ID does not belong to this application or catalog.
     */
    PRODUCT_NOT_ALLOWED,

    /**
     * Package name does not match the configured application identifier.
     */
    PACKAGE_NAME_MISMATCH
}

/**
 * Typed outcome of a purchase verification attempt.
 */
sealed class VerificationResult {
    /**
     * Authoritative source verified the purchase and produced an immutable entitlement.
     */
    data class Success(val entitlement: BillingEntitlement) : VerificationResult()

    /**
     * Purchase is currently in pending status (e.g. slow payment method) and cannot yet be activated.
     */
    data class Pending(val purchaseToken: String, val message: String) : VerificationResult()

    /**
     * Purchase was authoritatively rejected. May carry a tombstone entitlement snapshot.
     */
    data class Rejected(
        val reason: RejectionReason,
        val message: String,
        val tombstone: BillingEntitlement? = null
    ) : VerificationResult()

    /**
     * Network or temporary service failure; client should keep existing cache and retry later.
     */
    data class TransientError(val cause: Throwable?, val message: String) : VerificationResult()

    /**
     * Verification cannot complete because the authoritative backend verifier has not yet been deployed
     * or credentials have not been configured (hard gate for B03/B04).
     */
    data class MissingBackendGate(val message: String) : VerificationResult()

    /**
     * Verification failed because user authentication is missing, expired, or rejected (HTTP 401).
     * Preserves receipt without re-launching billing flow (S04).
     */
    data class AuthRequired(val message: String) : VerificationResult()
}

/**
 * Seam interface for verifying purchases against an authoritative source.
 */
data class PurchaseCandidate(
    val productId: String,
    val productType: String,
    val purchaseToken: String
)

data class RestoreRequest(
    val ownerAppUserId: String?,
    val purchases: List<PurchaseCandidate> = emptyList()
)

data class RestoreItemResult(
    val purchaseToken: String,
    val status: String,
    val reason: String? = null
)

sealed class RestoreResult {
    data class Success(
        val snapshot: UserEntitlementSnapshot,
        val message: String? = null,
        val results: List<RestoreItemResult> = emptyList()
    ) : RestoreResult()

    data class Partial(
        val snapshot: UserEntitlementSnapshot,
        val message: String? = null,
        val results: List<RestoreItemResult> = emptyList()
    ) : RestoreResult()

    data class TransientError(val cause: Throwable?, val message: String) : RestoreResult()

    data class Rejected(
        val reason: RejectionReason,
        val message: String,
        val results: List<RestoreItemResult> = emptyList()
    ) : RestoreResult()

    data class AuthRequired(val message: String) : RestoreResult()

    data class NoActivePurchases(val message: String? = null) : RestoreResult()

    data class NotConfigured(val message: String) : RestoreResult()
}

interface PurchaseVerifier {
    suspend fun verifyPurchase(request: VerificationRequest): VerificationResult

    suspend fun restorePurchases(request: RestoreRequest): RestoreResult {
        return RestoreResult.NotConfigured("Restore API not implemented on this verifier")
    }
}

/**
 * Baseline verifier implementation.
 *
 * When no explicit mock result is supplied, it strictly returns [VerificationResult.MissingBackendGate],
 * enforcing the invariant that client code never fakes success or assumes verified entitlement.
 */
class NoOpLocalPurchaseVerifier(
    private val configuredResult: VerificationResult? = null
) : PurchaseVerifier {
    override suspend fun verifyPurchase(request: VerificationRequest): VerificationResult {
        return configuredResult ?: VerificationResult.MissingBackendGate(
            "Authoritative backend verification required by B03/B04 contract. " +
                    "Production backend endpoint (backend/billing-verifier/) pending deployment."
        )
    }
}
