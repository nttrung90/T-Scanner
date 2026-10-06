package com.tscanner.app.utils

import android.os.Bundle

enum class VipContinuationAction {
    UPGRADE,
    RESTORE,
    NONE
}

/**
 * Manages the pending VIP continuation state machine across login flows,
 * configuration changes (rotation), and cancellation.
 *
 * Invariants (V05 / E02 / S01):
 * 1. Guest/expired user requests VIP continuation -> pending state is set to true.
 * 2. On sign-in success, dispatches continuation action exactly once and resets pending state.
 * 3. Does not automatically grant VIP on sign-in; user must confirm package or wait for restore.
 * 4. On cancellation or error, resets pending state; user remains Free/current state.
 * 5. Session generation mismatch drops stale continuation to prevent cross-account leakage,
 *    except when guest (null owner) successfully signs in and commits their first account,
 *    which legitimately increments session generation from G to G+1.
 * 6. Preserves optional targetProductId context across the flow without switching packages.
 * 7. Safely persists across Activity/Fragment recreation via SavedState.
 * 8. Does not retain Activity references across lifecycle boundaries.
 * 9. Rejects stale continuations across process death (mismatched processEpoch).
 */
class VipLoginContinuationHandler {

    var isPending: Boolean = false
        private set

    var pendingAction: VipContinuationAction = VipContinuationAction.UPGRADE
        private set

    var pendingSessionGeneration: Long = -1L
        private set

    var targetProductId: String? = null
        private set

    var initialOwnerId: String? = null
        private set

    var originatingRequestId: Long = -1L
        private set

    var processEpoch: String = ""
        private set

    var originatingOperationContext: com.tscanner.app.utils.billing.BillingOperationContext? = null
        private set

    fun requestContinuation(
        action: VipContinuationAction = VipContinuationAction.UPGRADE,
        sessionGeneration: Long = -1L,
        targetProductId: String? = null,
        initialOwnerId: String? = AppAuthManager.getCurrentUser()?.id,
        originatingRequestId: Long = -1L,
        processEpoch: String = AppAuthManager.getProcessEpoch(),
        originatingOperationContext: com.tscanner.app.utils.billing.BillingOperationContext? = null
    ) {
        isPending = true
        pendingAction = action
        pendingSessionGeneration = sessionGeneration
        this.targetProductId = targetProductId
        this.initialOwnerId = initialOwnerId
        this.originatingRequestId = originatingRequestId
        this.processEpoch = processEpoch
        this.originatingOperationContext = originatingOperationContext
    }

    fun bindAttempt(attempt: GoogleLoginAttempt) {
        if (!isPending) return
        this.originatingRequestId = attempt.requestId
        if (this.pendingSessionGeneration == -1L) {
            this.pendingSessionGeneration = attempt.initialSessionGeneration
        }
        if (this.processEpoch.isEmpty()) {
            this.processEpoch = attempt.processEpoch
        }
    }

    fun onSignInSuccess(onOpenConfirmation: () -> Unit) {
        onSignInSuccessWithAction { action, _ ->
            if (action == VipContinuationAction.UPGRADE) {
                onOpenConfirmation()
            }
        }
    }

    fun onSignInSuccessWithAction(onExecuteAction: (VipContinuationAction) -> Unit) {
        onSignInSuccessWithAction(currentSessionGeneration = -1L) { action, _ ->
            onExecuteAction(action)
        }
    }

    fun onSignInSuccessWithAction(
        currentSessionGeneration: Long,
        onExecuteAction: (VipContinuationAction) -> Unit
    ) {
        onSignInSuccessWithAction(currentSessionGeneration = currentSessionGeneration) { action, _ ->
            onExecuteAction(action)
        }
    }

    fun onSignInSuccessWithAction(
        currentSessionGeneration: Long = -1L,
        onExecuteAction: (VipContinuationAction, String?) -> Unit
    ) {
        onSignInSuccessWithAction(
            currentSessionGeneration = currentSessionGeneration,
            currentOwnerId = AppAuthManager.getCurrentUser()?.id,
            attemptRequestId = -1L,
            onExecuteAction = onExecuteAction
        )
    }

    fun onSignInSuccessWithAction(
        currentSessionGeneration: Long = -1L,
        currentOwnerId: String? = AppAuthManager.getCurrentUser()?.id,
        attemptRequestId: Long = -1L,
        onExecuteAction: (VipContinuationAction, String?) -> Unit
    ) {
        onSignInSuccessWithAction(
            currentSessionGeneration = currentSessionGeneration,
            currentOwnerId = currentOwnerId,
            attemptRequestId = attemptRequestId
        ) { action, product, _ ->
            onExecuteAction(action, product)
        }
    }

    fun onSignInSuccessWithAction(
        currentSessionGeneration: Long = -1L,
        currentOwnerId: String? = AppAuthManager.getCurrentUser()?.id,
        attemptRequestId: Long = -1L,
        onExecuteAction: (VipContinuationAction, String?, com.tscanner.app.utils.billing.BillingOperationContext?) -> Unit
    ) {
        if (!isPending) return

        val currentEpoch = AppAuthManager.getProcessEpoch()
        // 1. Process epoch validation: reject across process restart
        if (processEpoch.isNotEmpty() && currentEpoch.isNotEmpty() && processEpoch != currentEpoch) {
            reset()
            return
        }

        // 2. Monotonic request ID validation (if bound to attempt)
        if (originatingRequestId != -1L) {
            if (attemptRequestId == -1L || attemptRequestId != originatingRequestId) {
                // Ignore unrelated attempt or missing attempt ID on bound continuation without resetting active continuation
                return
            }
        }

        // 3. Owner validation:
        if (initialOwnerId != null) {
            // Existing user reauthentication: owner MUST NOT change and cannot be null (logout)
            if (currentOwnerId != initialOwnerId) {
                reset()
                return
            }
        }

        // 4. Session generation transition validation:
        if (pendingSessionGeneration != -1L && currentSessionGeneration != -1L) {
            val isGuestIncrement = (initialOwnerId == null && currentOwnerId != null && currentSessionGeneration == pendingSessionGeneration + 1L)
            val isSameGeneration = (currentSessionGeneration == pendingSessionGeneration)
            if (!isGuestIncrement && !isSameGeneration) {
                reset()
                return
            }
        }

        val action = pendingAction
        val product = targetProductId
        val opContext = originatingOperationContext
        reset()
        if (action != VipContinuationAction.NONE) {
            onExecuteAction(action, product, opContext)
        }
    }

    fun onSignInCancelled(attemptRequestId: Long = -1L) {
        if (originatingRequestId != -1L) {
            if (attemptRequestId == -1L || attemptRequestId != originatingRequestId) {
                return
            }
        }
        reset()
    }

    fun onSignInError(attemptRequestId: Long = -1L) {
        if (originatingRequestId != -1L) {
            if (attemptRequestId == -1L || attemptRequestId != originatingRequestId) {
                return
            }
        }
        reset()
    }

    fun reset() {
        isPending = false
        pendingAction = VipContinuationAction.UPGRADE
        pendingSessionGeneration = -1L
        targetProductId = null
        initialOwnerId = null
        originatingRequestId = -1L
        processEpoch = ""
        originatingOperationContext = null
    }

    fun saveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_PENDING_VIP_CONTINUATION, isPending)
        outState.putString(KEY_PENDING_VIP_ACTION, pendingAction.name)
        outState.putLong(KEY_PENDING_VIP_SESSION_GEN, pendingSessionGeneration)
        outState.putString(KEY_PENDING_VIP_PRODUCT_ID, targetProductId)
        outState.putString(KEY_PENDING_VIP_INITIAL_OWNER_ID, initialOwnerId)
        outState.putLong(KEY_PENDING_VIP_REQ_ID, originatingRequestId)
        outState.putString(KEY_PENDING_VIP_PROCESS_EPOCH, processEpoch)
    }

    fun restoreInstanceState(savedInstanceState: Bundle?) {
        val wasPending = savedInstanceState?.getBoolean(KEY_PENDING_VIP_CONTINUATION, false) ?: false
        if (!wasPending) {
            reset()
            return
        }
        val savedEpoch = savedInstanceState?.getString(KEY_PENDING_VIP_PROCESS_EPOCH)
        val currentEpoch = AppAuthManager.getProcessEpoch()
        // Process death/restart: do not replay stale pending continuation from previous process
        if (savedEpoch.isNullOrEmpty() || savedEpoch != currentEpoch) {
            reset()
            return
        }

        isPending = true
        val actionName = savedInstanceState.getString(KEY_PENDING_VIP_ACTION)
        pendingAction = try {
            if (actionName != null) VipContinuationAction.valueOf(actionName) else VipContinuationAction.UPGRADE
        } catch (_: Exception) {
            VipContinuationAction.UPGRADE
        }
        pendingSessionGeneration = savedInstanceState.getLong(KEY_PENDING_VIP_SESSION_GEN, -1L)
        targetProductId = savedInstanceState.getString(KEY_PENDING_VIP_PRODUCT_ID)
        initialOwnerId = savedInstanceState.getString(KEY_PENDING_VIP_INITIAL_OWNER_ID)
        originatingRequestId = savedInstanceState.getLong(KEY_PENDING_VIP_REQ_ID, -1L)
        processEpoch = savedEpoch
    }

    fun saveToMap(outState: MutableMap<String, Any>) {
        outState[KEY_PENDING_VIP_CONTINUATION] = isPending
        outState[KEY_PENDING_VIP_ACTION] = pendingAction.name
        outState[KEY_PENDING_VIP_SESSION_GEN] = pendingSessionGeneration
        targetProductId?.let { outState[KEY_PENDING_VIP_PRODUCT_ID] = it }
        initialOwnerId?.let { outState[KEY_PENDING_VIP_INITIAL_OWNER_ID] = it }
        outState[KEY_PENDING_VIP_REQ_ID] = originatingRequestId
        outState[KEY_PENDING_VIP_PROCESS_EPOCH] = processEpoch
    }

    fun restoreFromMap(savedInstanceState: Map<String, Any>?) {
        val wasPending = savedInstanceState?.get(KEY_PENDING_VIP_CONTINUATION) as? Boolean ?: false
        if (!wasPending) {
            reset()
            return
        }
        val savedEpoch = savedInstanceState?.get(KEY_PENDING_VIP_PROCESS_EPOCH) as? String
        val currentEpoch = AppAuthManager.getProcessEpoch()
        // Process death/restart: do not replay stale pending continuation from previous process
        if (savedEpoch.isNullOrEmpty() || savedEpoch != currentEpoch) {
            reset()
            return
        }

        isPending = true
        val actionName = savedInstanceState[KEY_PENDING_VIP_ACTION] as? String
        pendingAction = try {
            if (actionName != null) VipContinuationAction.valueOf(actionName) else VipContinuationAction.UPGRADE
        } catch (_: Exception) {
            VipContinuationAction.UPGRADE
        }
        pendingSessionGeneration = (savedInstanceState[KEY_PENDING_VIP_SESSION_GEN] as? Number)?.toLong() ?: -1L
        targetProductId = savedInstanceState[KEY_PENDING_VIP_PRODUCT_ID] as? String
        initialOwnerId = savedInstanceState[KEY_PENDING_VIP_INITIAL_OWNER_ID] as? String
        originatingRequestId = (savedInstanceState[KEY_PENDING_VIP_REQ_ID] as? Number)?.toLong() ?: -1L
        processEpoch = savedEpoch
    }

    companion object {
        const val KEY_PENDING_VIP_CONTINUATION = "key_pending_vip_continuation"
        const val KEY_PENDING_VIP_ACTION = "key_pending_vip_action"
        const val KEY_PENDING_VIP_SESSION_GEN = "key_pending_vip_session_gen"
        const val KEY_PENDING_VIP_PRODUCT_ID = "key_pending_vip_product_id"
        const val KEY_PENDING_VIP_INITIAL_OWNER_ID = "key_pending_vip_initial_owner_id"
        const val KEY_PENDING_VIP_REQ_ID = "key_pending_vip_req_id"
        const val KEY_PENDING_VIP_PROCESS_EPOCH = "key_pending_vip_process_epoch"
    }
}
