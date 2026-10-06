package com.tscanner.app.utils.billing

import androidx.annotation.VisibleForTesting
import com.tscanner.app.utils.VipContinuationAction
import java.util.concurrent.ConcurrentHashMap

/**
 * Immutable typed recovery request holder for preserving operation context and
 * admission lifecycle callbacks across asynchronous navigation boundaries (e.g. Home -> More).
 */
data class VipRecoveryRequest(
    val action: VipContinuationAction,
    val operationContext: BillingOperationContext?,
    val expectedOwnerId: String?,
    val originGeneration: Long,
    val processEpoch: String,
    val onStarted: () -> Unit,
    val onRefused: () -> Unit,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Thread-safe memory registry for passing typed recovery envelopes across Android navigation
 * without serialization or closure leakage inside Android Bundles (S02).
 */
object VipRecoveryRegistry {

    private val pendingRequests = ConcurrentHashMap<String, VipRecoveryRequest>()

    fun register(operationId: String, request: VipRecoveryRequest) {
        pendingRequests[operationId] = request
    }

    fun consume(operationId: String): VipRecoveryRequest? {
        return pendingRequests.remove(operationId)
    }

    fun peek(operationId: String): VipRecoveryRequest? {
        return pendingRequests[operationId]
    }

    fun refuseAndRemove(operationId: String) {
        val req = pendingRequests.remove(operationId)
        req?.onRefused?.invoke()
    }

    @VisibleForTesting
    fun resetForTesting() {
        pendingRequests.clear()
    }
}
