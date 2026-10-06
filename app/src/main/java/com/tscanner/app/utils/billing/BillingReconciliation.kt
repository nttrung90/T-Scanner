package com.tscanner.app.utils.billing

import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicInteger

/**
 * Result of a complete reconciliation operation across SUBS and INAPP (B07).
 */
sealed class ReconciliationResult {
    data class Restored(
        val count: Int,
        val productIds: List<String>,
        val totalCount: Int = count,
        val failedCount: Int = 0
    ) : ReconciliationResult()
    object NoActivePurchases : ReconciliationResult()
    data class PendingApproval(val message: String = "Giao dịch đang chờ xác nhận từ Google Play.") : ReconciliationResult()
    data class NetworkError(val responseCode: Int, val message: String) : ReconciliationResult()
    data class ProcessingFailed(val message: String) : ReconciliationResult()
    data class AuthRequired(val message: String) : ReconciliationResult()
}

/**
 * Handles coalesced query, verification, acknowledgment, and authoritative reconciliation (B07).
 *
 * Enforces Invariants (F03/F06):
 * 1. Coalesces both SUBS and INAPP queries before making any state decisions.
 * 2. Only authoritative empty responses (both queries OK and empty) revoke VIP entitlements.
 * 3. Network or service errors (SERVICE_UNAVAILABLE, ERROR) preserve existing cached billing state.
 * 4. Restore only reports success after all found purchases have been fully verified and acknowledged.
 * 5. De-duplicates purchase tokens to prevent redundant processing.
 */
class BillingReconciliation(
    private val context: Context,
    private val billingClient: BillingManager.BillingClientWrapper,
    private val processPurchaseAction: (Purchase, onComplete: (Boolean) -> Unit) -> Unit,
    private val operationContext: BillingOperationContext? = null,
    private val verifier: PurchaseVerifier? = null,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
    private val coroutineScope: kotlinx.coroutines.CoroutineScope? = null
) {

    companion object {
        private const val TAG = "BillingReconciliation"
        private const val PREFS_NAME = "tscanner_billing_prefs"
        private const val KEY_BILLING_VIP_ACTIVE = "billing_vip_active"
    }

    /**
     * Executes a complete reconciliation cycle.
     */
    fun reconcile(onResult: (ReconciliationResult) -> Unit) {
        if (!billingClient.isReady) {
            Log.w(TAG, "Reconcile aborted: BillingClient not ready")
            onResult(ReconciliationResult.NetworkError(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED, "BillingClient chưa sẵn sàng"))
            return
        }

        var subsResult: BillingResult? = null
        var inAppResult: BillingResult? = null
        val collectedPurchases = mutableListOf<Purchase>()
        val queryCounter = AtomicInteger(0)

        val onQueryFinished = {
            if (queryCounter.incrementAndGet() == 2) {
                evaluateAndProcess(subsResult!!, inAppResult!!, collectedPurchases, onResult)
            }
        }

        // 1. Query SUBS
        val subParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        billingClient.queryPurchasesAsync(subParams) { result, purchases ->
            subsResult = result
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                synchronized(collectedPurchases) {
                    collectedPurchases.addAll(purchases)
                }
            } else {
                Log.w(TAG, "SUBS query failed with code ${result.responseCode}: ${result.debugMessage}")
            }
            onQueryFinished()
        }

        // 2. Query INAPP
        val inAppParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(inAppParams) { result, purchases ->
            inAppResult = result
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                synchronized(collectedPurchases) {
                    collectedPurchases.addAll(purchases)
                }
            } else {
                Log.w(TAG, "INAPP query failed with code ${result.responseCode}: ${result.debugMessage}")
            }
            onQueryFinished()
        }
    }

    private fun evaluateAndProcess(
        subsResult: BillingResult,
        inAppResult: BillingResult,
        purchases: List<Purchase>,
        onResult: (ReconciliationResult) -> Unit
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Stale session / operation check (Q02 / R04): discard if owner or session generation changed
        if (operationContext != null) {
            val currentUserId = AppAuthManager.getCurrentUser()?.id
            val currentGen = AppAuthManager.getSessionGeneration()
            if (operationContext.isStale(currentUserId, currentGen)) {
                Log.w(
                    TAG,
                    "Reconciliation discarded: operation context is stale. " +
                            "Expected owner '${operationContext.ownerAppUserId}' gen ${operationContext.sessionGeneration}, " +
                            "but current user is '$currentUserId' gen $currentGen."
                )
                return
            }
        }

        val targetOwnerId = operationContext?.ownerAppUserId ?: AppAuthManager.getCurrentUser()?.id
        val hasPendingItems = purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING }
        val validPurchasedItems = purchases
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            .distinctBy { it.purchaseToken }

        val playQueryError = (subsResult.responseCode != BillingClient.BillingResponseCode.OK ||
                inAppResult.responseCode != BillingClient.BillingResponseCode.OK)
        val failureCode = if (subsResult.responseCode != BillingClient.BillingResponseCode.OK) {
            subsResult.responseCode
        } else if (inAppResult.responseCode != BillingClient.BillingResponseCode.OK) {
            inAppResult.responseCode
        } else {
            null
        }

        // Case 1: If either query returned a network / service error, preserve cached state unless verifier can refresh
        if (playQueryError) {
            if (validPurchasedItems.isEmpty()) {
                if (targetOwnerId != null && verifier != null) {
                    Log.w(
                        TAG,
                        "Play query error occurred (code $failureCode). Attempting independent backend refresh for owner '$targetOwnerId'."
                    )
                    executeRemoteRestore(
                        targetOwnerId = targetOwnerId,
                        deviceSuccessfulPurchases = emptyList(),
                        totalDeviceCount = 0,
                        prefs = prefs,
                        onResult = onResult,
                        playQueryErrorCode = failureCode,
                        devicePurchases = purchases
                    )
                    return
                }
                Log.w(TAG, "Query error occurred (code $failureCode) and verifier unavailable. Preserving last known billing state.")
                onResult(ReconciliationResult.NetworkError(failureCode ?: 503, "Lỗi truy vấn Google Play: $failureCode"))
                return
            }
        }

        // Subcase 2A: Empty local device catalog (F03 / R01 / R03)
        // Invariant: Empty or pending Play Store purchases on device does NOT revoke valid server-authoritative entitlements.
        // Instead, contact authoritative backend (/api/v1/billing/restore) to query app-account entitlements.
        if (validPurchasedItems.isEmpty()) {
            if (verifier != null) {
                executeRemoteRestore(
                    targetOwnerId = targetOwnerId,
                    deviceSuccessfulPurchases = emptyList(),
                    totalDeviceCount = 0,
                    prefs = prefs,
                    onResult = onResult,
                    devicePurchases = purchases
                )
                return
            }

            Log.i(TAG, "Device Google Play catalog returned 0 purchases and verifier is null. Preserving server-authoritative entitlements.")
            val store = BillingEntitlementStore.getInstance()
            val snapshot = store.getSnapshot(context, targetOwnerId)

            prefs.edit().putBoolean(KEY_BILLING_VIP_ACTIVE, snapshot.isVipActive()).apply()

            val currentUser = AppAuthManager.getCurrentUser()
            if (currentUser != null && (targetOwnerId == null || currentUser.id == targetOwnerId)) {
                AppAuthManager.projectSnapshotToProfile(context, snapshot)
            }

            onResult(ReconciliationResult.NoActivePurchases)
            return
        }

        // Subcase 2B: Non-empty active purchases. Must process, acknowledge, and verify all before success (F06 / R01).
        val totalCount = validPurchasedItems.size
        val processedCounter = AtomicInteger(0)
        val successfulPurchases = mutableListOf<Purchase>()

        for (purchase in validPurchasedItems) {
            processPurchaseAction(purchase) { success ->
                if (success) {
                    synchronized(successfulPurchases) {
                        successfulPurchases.add(purchase)
                    }
                }
                if (processedCounter.incrementAndGet() == totalCount) {
                    if (operationContext != null) {
                        val currentUserId = AppAuthManager.getCurrentUser()?.id
                        val currentGen = AppAuthManager.getSessionGeneration()
                        if (operationContext.isStale(currentUserId, currentGen)) {
                            Log.w(
                                TAG,
                                "Reconciliation completion discarded: operation context became stale during processing. " +
                                        "Expected owner '${operationContext.ownerAppUserId}' gen ${operationContext.sessionGeneration}, " +
                                        "current user '$currentUserId' gen $currentGen."
                            )
                            return@processPurchaseAction
                        }
                    }
                    if (verifier != null) {
                        executeRemoteRestore(
                            targetOwnerId = targetOwnerId,
                            deviceSuccessfulPurchases = successfulPurchases,
                            totalDeviceCount = totalCount,
                            prefs = prefs,
                            onResult = onResult,
                            playQueryErrorCode = failureCode,
                            devicePurchases = purchases
                        )
                    } else {
                        if (successfulPurchases.isNotEmpty()) {
                            prefs.edit().putBoolean(KEY_BILLING_VIP_ACTIVE, true).apply()
                            val productIds = successfulPurchases.flatMap { it.products }.distinct()
                            val failedCount = totalCount - successfulPurchases.size
                            onResult(
                                ReconciliationResult.Restored(
                                    count = successfulPurchases.size,
                                    productIds = productIds,
                                    totalCount = totalCount,
                                    failedCount = failedCount
                                )
                            )
                        } else {
                            Log.e(TAG, "All $totalCount found purchases failed acknowledge or verification.")
                            onResult(ReconciliationResult.ProcessingFailed("Không thể xác nhận hoặc xác thực giao dịch."))
                        }
                    }
                }
            }
        }
    }

    private fun executeRemoteRestore(
        targetOwnerId: String?,
        deviceSuccessfulPurchases: List<Purchase>,
        totalDeviceCount: Int,
        prefs: android.content.SharedPreferences,
        onResult: (ReconciliationResult) -> Unit,
        playQueryErrorCode: Int? = null,
        devicePurchases: List<Purchase> = emptyList()
    ) {
        val initialUserId = AppAuthManager.getCurrentUser()?.id
        val initialGen = AppAuthManager.getSessionGeneration()
        val targetScope = coroutineScope ?: kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + ioDispatcher)
        targetScope.launch {
            val candidateList = devicePurchases.mapNotNull { p ->
                val productId = p.products.firstOrNull() ?: return@mapNotNull null
                val type = if (BillingManager.ALL_SUBSCRIPTION_IDS.contains(productId)) "subs" else "inapp"
                PurchaseCandidate(productId = productId, productType = type, purchaseToken = p.purchaseToken)
            }
            val restoreResult = try {
                verifier!!.restorePurchases(RestoreRequest(targetOwnerId, candidateList))
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "Restore operation cancelled: ${e.message}")
                throw e
            }
            if (!isActive) {
                Log.w(TAG, "Restore coroutine inactive after await, skipping commit")
                return@launch
            }
            val currentUserId = AppAuthManager.getCurrentUser()?.id
            val currentGen = AppAuthManager.getSessionGeneration()
            val isStale = (operationContext?.isStale(currentUserId, currentGen) == true) ||
                    (currentUserId != initialUserId) ||
                    (currentGen != initialGen)
            if (isStale) {
                Log.w(
                    TAG,
                    "Restore response discarded: session changed during await. " +
                            "Initial user '$initialUserId' gen $initialGen, current user '$currentUserId' gen $currentGen."
                )
                return@launch
            }
            when (restoreResult) {
                is RestoreResult.Success, is RestoreResult.Partial -> {
                    val store = BillingEntitlementStore.getInstance()
                    val incomingSnapshot = if (restoreResult is RestoreResult.Success) {
                        restoreResult.snapshot
                    } else {
                        (restoreResult as RestoreResult.Partial).snapshot
                    }
                    val applyResult = store.applySnapshotTyped(context, incomingSnapshot)
                    if (applyResult !is ApplySnapshotResult.Success) {
                        val err = when (applyResult) {
                            is ApplySnapshotResult.Conflict -> "Xung đột dữ liệu khôi phục: ${applyResult.reason}"
                            is ApplySnapshotResult.PersistenceFailed -> "Không thể lưu trạng thái khôi phục: ${applyResult.message}"
                            else -> "Lỗi khi lưu dữ liệu khôi phục."
                        }
                        Log.e(TAG, err)
                        onResult(ReconciliationResult.ProcessingFailed(err))
                        return@launch
                    }

                    val committedSnapshot = applyResult.snapshot
                    val currentUser = AppAuthManager.getCurrentUser()
                    if (currentUser != null && (targetOwnerId == null || currentUser.id == targetOwnerId)) {
                        val projected = AppAuthManager.projectSnapshotToProfile(context, committedSnapshot)
                        if (!projected) {
                            Log.e(TAG, "Không thể lưu thông tin hồ sơ người dùng.")
                            onResult(ReconciliationResult.ProcessingFailed("Không thể lưu thông tin hồ sơ."))
                            return@launch
                        }
                    }
                    val isVip = committedSnapshot.isVipActive()
                    prefs.edit().putBoolean(KEY_BILLING_VIP_ACTIVE, isVip).apply()
                    val restoreResults = when (restoreResult) {
                        is RestoreResult.Success -> restoreResult.results
                        is RestoreResult.Partial -> restoreResult.results
                        is RestoreResult.Rejected -> restoreResult.results
                        else -> emptyList()
                    }

                    if (isVip) {
                        val locallySucceededTokens = deviceSuccessfulPurchases.map { it.purchaseToken }.toSet()
                        val locallyFailedTokens = (devicePurchases - deviceSuccessfulPurchases.toSet()).map { it.purchaseToken }.toSet()

                        val remotelySucceededTokens = restoreResults.filter { it.status == "SUCCESS" }.map { it.purchaseToken }.toSet()
                        val remotelyFailedTokens = restoreResults.filter { it.status == "TRANSIENT_ERROR" || it.status == "REJECTED" || it.status == "PENDING" }.map { it.purchaseToken }.toSet()

                        // Token identity deduplication:
                        // 1. Authoritative remote success overrides earlier local failure for the same token
                        // 2. Receipt failing both locally and remotely counts as only one failure
                        val allFailedTokens = remotelyFailedTokens + (locallyFailedTokens - remotelySucceededTokens)
                        val playQueryUnresolved = if (playQueryErrorCode != null) 1 else 0
                        val partialFallbackUnresolved = if (restoreResult is RestoreResult.Partial && allFailedTokens.isEmpty() && restoreResults.isEmpty()) 1 else 0
                        val failedCount = allFailedTokens.size + playQueryUnresolved + partialFallbackUnresolved

                        // Fresh count solely based on freshly verified/restored tokens, not stale cached snapshot
                        val allSuccessTokens = remotelySucceededTokens + locallySucceededTokens
                        val activeCount = committedSnapshot.getActiveEntitlements().count { allSuccessTokens.contains(it.purchaseToken) }

                        val productIds = committedSnapshot.getActiveEntitlements().map { it.productId }
                        onResult(
                            ReconciliationResult.Restored(
                                count = activeCount,
                                productIds = productIds,
                                totalCount = activeCount + failedCount,
                                failedCount = failedCount
                            )
                        )
                    } else {
                        val hasPending = restoreResults.any { it.status == "PENDING" } ||
                                devicePurchases.any { it.purchaseState == Purchase.PurchaseState.PENDING } ||
                                (restoreResult is RestoreResult.Partial && restoreResult.message?.contains("pending", ignoreCase = true) == true)

                        val hasTransient = restoreResults.any { it.status == "TRANSIENT_ERROR" } ||
                                (restoreResult is RestoreResult.Partial && !hasPending && restoreResults.isEmpty())

                        if (hasPending) {
                            val msg = (restoreResult as? RestoreResult.Partial)?.message
                                ?: (restoreResult as? RestoreResult.Success)?.message
                                ?: "Giao dịch đang chờ xác nhận từ Google Play."
                            onResult(ReconciliationResult.PendingApproval(msg))
                        } else if (hasTransient) {
                            val code = playQueryErrorCode ?: 503
                            val msg = (restoreResult as? RestoreResult.Partial)?.message ?: "Một số giao dịch không thể xác thực do lỗi mạng."
                            onResult(ReconciliationResult.NetworkError(code, msg))
                        } else {
                            onResult(ReconciliationResult.NoActivePurchases)
                        }
                    }
                }
                is RestoreResult.NoActivePurchases -> {
                    val store = BillingEntitlementStore.getInstance()
                    val snapshot = store.getSnapshot(context, targetOwnerId)
                    val isVip = snapshot.isVipActive()
                    prefs.edit().putBoolean(KEY_BILLING_VIP_ACTIVE, isVip).apply()
                    onResult(ReconciliationResult.NoActivePurchases)
                }
                is RestoreResult.TransientError -> {
                    Log.w(TAG, "Remote restore failed with transient error: ${restoreResult.message}")
                    val store = BillingEntitlementStore.getInstance()
                    val snapshot = store.getSnapshot(context, targetOwnerId)
                    val isVip = snapshot.isVipActive()
                    prefs.edit().putBoolean(KEY_BILLING_VIP_ACTIVE, isVip).apply()
                    if (deviceSuccessfulPurchases.isNotEmpty()) {
                        val productIds = deviceSuccessfulPurchases.flatMap { it.products }.distinct()
                        onResult(
                            ReconciliationResult.Restored(
                                count = deviceSuccessfulPurchases.size,
                                productIds = productIds,
                                totalCount = deviceSuccessfulPurchases.size + 1,
                                failedCount = 1
                            )
                        )
                    } else {
                        val code = playQueryErrorCode ?: 503
                        onResult(ReconciliationResult.NetworkError(code, restoreResult.message))
                    }
                }
                is RestoreResult.Rejected -> {
                    Log.w(TAG, "Remote restore rejected: ${restoreResult.message}")
                    if (deviceSuccessfulPurchases.isNotEmpty()) {
                        val productIds = deviceSuccessfulPurchases.flatMap { it.products }.distinct()
                        onResult(
                            ReconciliationResult.Restored(
                                count = deviceSuccessfulPurchases.size,
                                productIds = productIds,
                                totalCount = deviceSuccessfulPurchases.size + 1,
                                failedCount = 1
                            )
                        )
                    } else {
                        onResult(ReconciliationResult.ProcessingFailed(restoreResult.message))
                    }
                }
                is RestoreResult.AuthRequired -> {
                    Log.w(TAG, "Remote restore requires authentication: ${restoreResult.message}")
                    onResult(ReconciliationResult.AuthRequired(restoreResult.message))
                }
                is RestoreResult.NotConfigured -> {
                    val store = BillingEntitlementStore.getInstance()
                    val snapshot = store.getSnapshot(context, targetOwnerId)
                    val isVip = snapshot.isVipActive()
                    prefs.edit().putBoolean(KEY_BILLING_VIP_ACTIVE, isVip).apply()
                    if (deviceSuccessfulPurchases.isNotEmpty()) {
                        val productIds = deviceSuccessfulPurchases.flatMap { it.products }.distinct()
                        onResult(ReconciliationResult.Restored(deviceSuccessfulPurchases.size, productIds))
                    } else {
                        if (playQueryErrorCode != null) {
                            onResult(ReconciliationResult.NetworkError(playQueryErrorCode, "Lỗi truy vấn Google Play: $playQueryErrorCode"))
                        } else {
                            onResult(ReconciliationResult.NoActivePurchases)
                        }
                    }
                }
            }
        }
    }
}
