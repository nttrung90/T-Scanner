package com.tscanner.app.ui.dialogs

import androidx.annotation.VisibleForTesting
import com.tscanner.app.R
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.VipContinuationAction
import com.tscanner.app.utils.billing.BillingOperationContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Output decisions resulting from consuming a [BillingManager.PurchaseAuthRequiredEvent].
 */
sealed class PurchaseAuthDecision {
    /**
     * Reauthentication is required to recover the purchase receipt.
     */
    data class RequestReauth(
        val action: VipContinuationAction,
        val targetOwnerId: String?,
        val messageResId: Int = R.string.vip_session_expired_reauth_prompt,
        val operationContext: BillingOperationContext? = null,
        val reservationToken: String = "",
        val onStarted: () -> Unit = {},
        val onReleased: () -> Unit = {}
    ) : PurchaseAuthDecision() {
        fun confirmStarted() = onStarted()
        fun release() = onReleased()
    }

    /**
     * Repeated failure or non-recoverable auth error in same operation; stop automatic recovery.
     */
    data class Stop(val message: String) : PurchaseAuthDecision()

    /**
     * Stale, mismatched, or invalid event; safely ignored without any UI or auth side effects.
     */
    data class Ignore(val reason: String) : PurchaseAuthDecision()

    /**
     * UI is inactive or not ready to receive auth recovery; event is deferred without consuming budget.
     */
    data class Defer(val reason: String) : PurchaseAuthDecision()
}

/**
 * Production consumer evaluator for purchase authentication required events.
 * Validates session ownership, generation, epoch, and operation recovery limits at consumption time (K01, K02).
 * Enforces atomic reservation per recovery key to guarantee single-consumer admission (R01).
 */
object VipPurchaseAuthConsumer {

    enum class ReservationState {
        RESERVED,
        STARTED,
        COMPLETED
    }

    data class ReservationRecord(
        val token: String,
        @Volatile var state: ReservationState,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val reservations = ConcurrentHashMap<String, ReservationRecord>()

    @VisibleForTesting
    fun resetForTesting() {
        reservations.clear()
    }

    @VisibleForTesting
    fun getReservationState(recoveryKey: String): ReservationState? {
        return reservations[recoveryKey]?.state
    }

    fun isRecoveryStartedOrCompleted(recoveryKey: String): Boolean {
        val cur = reservations[recoveryKey] ?: return false
        return cur.state == ReservationState.STARTED || cur.state == ReservationState.COMPLETED
    }

    fun evaluate(
        event: BillingManager.PurchaseAuthRequiredEvent,
        currentOwnerId: String?,
        currentGeneration: Long,
        currentEpoch: String,
        isUiActive: Boolean
    ): PurchaseAuthDecision {
        if (!isUiActive) {
            event.releaseAttempt()
            return PurchaseAuthDecision.Defer("UI is inactive, destroyed, or not showing")
        }

        // K02: Strict session origin validation at time of consumption
        if (event.targetOwnerId != null && event.targetOwnerId != currentOwnerId) {
            event.releaseAttempt()
            return PurchaseAuthDecision.Ignore(
                "Owner mismatch at consumption: event target='${event.targetOwnerId}' vs current='$currentOwnerId'"
            )
        }

        if (event.sessionGeneration != -1L && event.sessionGeneration != currentGeneration) {
            event.releaseAttempt()
            return PurchaseAuthDecision.Ignore(
                "Session generation mismatch at consumption: event gen=${event.sessionGeneration} vs current gen=$currentGeneration"
            )
        }

        if (event.processEpoch.isNotEmpty() && currentEpoch.isNotEmpty() && event.processEpoch != currentEpoch) {
            event.releaseAttempt()
            return PurchaseAuthDecision.Ignore(
                "Process epoch mismatch at consumption: event epoch='${event.processEpoch}' vs current epoch='$currentEpoch'"
            )
        }

        val opContext = event.operationContext
        if (opContext != null && opContext.isStale(currentOwnerId, currentGeneration)) {
            event.releaseAttempt()
            return PurchaseAuthDecision.Ignore("Operation context is stale at consumption ($opContext)")
        }

        val key = event.recoveryKey

        // R01: Check existing reservation for this operation/receipt
        val existing = reservations[key]
        if (existing != null) {
            when (existing.state) {
                ReservationState.STARTED, ReservationState.COMPLETED -> {
                    return PurchaseAuthDecision.Stop("Recovery already started or completed for this operation")
                }
                ReservationState.RESERVED -> {
                    return PurchaseAuthDecision.Ignore("Recovery already reserved by another consumer for operation")
                }
            }
        }

        // K01: Limit to maximum 1 reauth per user operation
        if (event.isRetry) {
            return PurchaseAuthDecision.Stop(event.message)
        }

        // Atomic reservation claim
        val token = java.util.UUID.randomUUID().toString()
        val record = ReservationRecord(token, ReservationState.RESERVED)
        val prior = reservations.putIfAbsent(key, record)
        if (prior != null) {
            return when (prior.state) {
                ReservationState.STARTED, ReservationState.COMPLETED -> {
                    PurchaseAuthDecision.Stop("Recovery already started or completed for this operation")
                }
                ReservationState.RESERVED -> {
                    PurchaseAuthDecision.Ignore("Recovery already reserved by another consumer for operation")
                }
            }
        }

        if (reservations.size > 100) {
            cleanUpOldReservations()
        }

        return PurchaseAuthDecision.RequestReauth(
            action = VipContinuationAction.RESTORE,
            targetOwnerId = event.targetOwnerId,
            operationContext = event.operationContext,
            reservationToken = token,
            onStarted = {
                synchronized(reservations) {
                    val cur = reservations[key]
                    if (cur != null && cur.token == token) {
                        cur.state = ReservationState.STARTED
                        event.commitAttempt()
                    }
                }
            },
            onReleased = {
                synchronized(reservations) {
                    val cur = reservations[key]
                    if (cur != null && cur.token == token) {
                        reservations.remove(key)
                        event.releaseAttempt()
                    }
                }
            }
        )
    }

    private fun cleanUpOldReservations() {
        val now = System.currentTimeMillis()
        val timeoutMs = 10 * 60 * 1000L // 10 minutes
        val iterator = reservations.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.createdAt > timeoutMs) {
                iterator.remove()
            }
        }
    }
}
