package com.tscanner.app.utils

import android.app.Activity
import android.content.Context
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.ui.dialogs.VipUpgradeActionResolver
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Seam interface abstracting Google Play Billing interactions for purchase action coordination.
 */
interface VipPurchaseLauncher {
    val connectionState: BillingManager.ConnectionState
    fun isProductDetailsAvailable(productId: String): Boolean
    fun isVerifierConfigured(): Boolean = true
    fun launchBillingFlow(
        activity: Activity,
        productId: String,
        onError: ((String) -> Unit)?
    ): Boolean
    fun launchBillingFlow(
        activity: Activity,
        productId: String,
        onAuthRequired: (() -> Unit)?,
        onError: ((String) -> Unit)?
    ): Boolean = launchBillingFlow(activity, productId, onError)
    fun startConnection(onComplete: ((Boolean) -> Unit)?)
    fun queryProducts(onComplete: ((Boolean) -> Unit)?)
}

/**
 * Default implementation of [VipPurchaseLauncher] binding directly to production [BillingManager].
 */
class DefaultVipPurchaseLauncher(private val context: Context) : VipPurchaseLauncher {
    private val billingManager: BillingManager
        get() = BillingManager.getInstance(context)

    override val connectionState: BillingManager.ConnectionState
        get() = billingManager.connectionState.value

    override fun isProductDetailsAvailable(productId: String): Boolean {
        return billingManager.products.value[productId] != null
    }

    override fun isVerifierConfigured(): Boolean {
        return billingManager.isVerifierConfigured()
    }

    override fun launchBillingFlow(
        activity: Activity,
        productId: String,
        onError: ((String) -> Unit)?
    ): Boolean {
        return launchBillingFlow(activity, productId, onAuthRequired = null, onError = onError)
    }

    override fun launchBillingFlow(
        activity: Activity,
        productId: String,
        onAuthRequired: (() -> Unit)?,
        onError: ((String) -> Unit)?
    ): Boolean {
        return billingManager.launchBillingFlow(
            activity = activity,
            productId = productId,
            offerToken = null,
            onAuthRequired = onAuthRequired,
            onError = onError
        )
    }

    override fun startConnection(onComplete: ((Boolean) -> Unit)?) {
        billingManager.startConnection(onComplete ?: {})
    }

    override fun queryProducts(onComplete: ((Boolean) -> Unit)?) {
        billingManager.queryAllProducts { detailsMap ->
            onComplete?.invoke(detailsMap.isNotEmpty())
        }
    }
}

/**
 * Coordinates user purchase interactions from the VIP upgrade dialog.
 *
 * Invariants (Defect F01 / G04):
 * - Failures, disconnections, missing activities, or empty product caches NEVER grant trial VIP.
 * - Coalesces rapid multiple clicks (double-clicks).
 * - Reports clear loading / error / retry states.
 * - Guarantees exactly one terminal completion callback per action invocation.
 * - Each operation maintains a monotonic operation ID and isolated terminal guard.
 * - Late callbacks from prior operations can never mutate active state or trigger duplicate billing flows.
 * - Never calls post-upgrade flows until Google Play purchase is successfully verified.
 */
class VipPurchaseActionCoordinator(
    private val launcher: VipPurchaseLauncher
) {
    interface Listener {
        fun onLoading(message: String)
        fun onError(message: String, canRetry: Boolean)
        fun onLaunchSuccess()
        fun onRequestSignIn()
        fun onShowSignInPrompt()
    }

    private class OperationState(
        val operationId: Long,
        val initialUserId: String?,
        val initialGen: Long,
        val hasSignInCallback: Boolean
    ) {
        val connectHandled = AtomicBoolean(false)
        val queryHandled = AtomicBoolean(false)
        val launchedFlow = AtomicBoolean(false)
        val isTerminal = AtomicBoolean(false)
    }

    private val currentOperationId = AtomicLong(0L)
    private val activeOperationId = AtomicLong(0L)

    fun isActionInProgress(): Boolean = activeOperationId.get() != 0L

    fun resetProcessingState() {
        activeOperationId.set(0L)
    }

    private fun terminate(
        state: OperationState,
        terminalBlock: () -> Unit
    ) {
        if (activeOperationId.get() != state.operationId) {
            return
        }
        if (state.isTerminal.compareAndSet(false, true)) {
            activeOperationId.compareAndSet(state.operationId, 0L)
            terminalBlock()
        }
    }

    private fun validateCurrentSession(
        state: OperationState,
        listener: Listener
    ): Boolean {
        if (activeOperationId.get() != state.operationId) {
            return false
        }
        val currentUserId = AppAuthManager.getCurrentUser()?.id
        val currentGen = AppAuthManager.getSessionGeneration()
        if (currentUserId != state.initialUserId || currentGen != state.initialGen) {
            terminate(state) {
                listener.onError("Phiên người dùng đã thay đổi. Vui lòng thử lại.", canRetry = true)
            }
            return false
        }
        val token = AppAuthManager.getSessionToken() ?: AppAuthManager.getCurrentUser()?.idToken
        if (token.isNullOrBlank() || com.tscanner.app.utils.billing.PlayPurchaseVerifier.isTokenExpired(token)) {
            terminate(state) {
                if (state.hasSignInCallback) {
                    listener.onRequestSignIn()
                } else {
                    listener.onShowSignInPrompt()
                }
            }
            return false
        }
        return true
    }

    /**
     * Handles the user clicking the VIP upgrade button.
     */
    fun onUpgradeClicked(
        activity: Activity?,
        currentUser: UserProfile?,
        hasSignInCallback: Boolean,
        productId: String = BillingManager.PRODUCT_VIP_YEARLY,
        listener: Listener
    ) {
        val nextOpId = currentOperationId.incrementAndGet()
        if (!activeOperationId.compareAndSet(0L, nextOpId)) {
            // Drop rapid duplicate clicks
            return
        }

        val initialUserId = currentUser?.id ?: AppAuthManager.getCurrentUser()?.id
        val initialGen = AppAuthManager.getSessionGeneration()
        val state = OperationState(
            operationId = nextOpId,
            initialUserId = initialUserId,
            initialGen = initialGen,
            hasSignInCallback = hasSignInCallback
        )

        val action = VipUpgradeActionResolver.resolveUpgradeAction(currentUser, hasSignInCallback)
        when (action) {
            is VipUpgradeActionResolver.Action.RequestSignIn -> {
                terminate(state) {
                    listener.onRequestSignIn()
                }
            }
            is VipUpgradeActionResolver.Action.ShowSignInRequiredPrompt -> {
                terminate(state) {
                    listener.onShowSignInPrompt()
                }
            }
            is VipUpgradeActionResolver.Action.ActivateVip -> {
                val token = currentUser?.idToken ?: AppAuthManager.getSessionToken()
                val tokenMissingOrExpired = token.isNullOrBlank() || com.tscanner.app.utils.billing.PlayPurchaseVerifier.isTokenExpired(token)
                if (tokenMissingOrExpired) {
                    terminate(state) {
                        if (hasSignInCallback) {
                            listener.onRequestSignIn()
                        } else {
                            listener.onShowSignInPrompt()
                        }
                    }
                    return
                }

                if (!launcher.isVerifierConfigured()) {
                    terminate(state) {
                        listener.onError(
                            "Dịch vụ xác thực thanh toán hiện chưa sẵn sàng. Vui lòng thử lại sau.",
                            canRetry = false
                        )
                    }
                    return
                }

                if (activity == null || activity.isFinishing || activity.isDestroyed) {
                    terminate(state) {
                        listener.onError(
                            "Không thể mở giao diện thanh toán (màn hình hiển thị không khả dụng). Vui lòng thử lại.",
                            canRetry = true
                        )
                    }
                    return
                }

                when (launcher.connectionState) {
                    BillingManager.ConnectionState.DISCONNECTED,
                    BillingManager.ConnectionState.CLOSED -> {
                        listener.onLoading("Đang kết nối lại với Google Play...")
                        launcher.startConnection { connected ->
                            if (activeOperationId.get() != state.operationId) {
                                return@startConnection
                            }
                            if (!state.connectHandled.compareAndSet(false, true)) {
                                return@startConnection
                            }
                            if (!validateCurrentSession(state, listener)) {
                                return@startConnection
                            }
                            if (connected && launcher.connectionState == BillingManager.ConnectionState.CONNECTED) {
                                checkProductAndLaunch(activity, productId, state, listener)
                            } else {
                                terminate(state) {
                                    listener.onError(
                                        "Không thể kết nối đến Google Play. Vui lòng kiểm tra kết nối mạng và thử lại.",
                                        canRetry = true
                                    )
                                }
                            }
                        }
                    }
                    BillingManager.ConnectionState.CONNECTING -> {
                        listener.onLoading("Đang kết nối đến Google Play...")
                        launcher.startConnection { connected ->
                            if (activeOperationId.get() != state.operationId) {
                                return@startConnection
                            }
                            if (!state.connectHandled.compareAndSet(false, true)) {
                                return@startConnection
                            }
                            if (!validateCurrentSession(state, listener)) {
                                return@startConnection
                            }
                            if (connected && launcher.connectionState == BillingManager.ConnectionState.CONNECTED) {
                                checkProductAndLaunch(activity, productId, state, listener)
                            } else {
                                terminate(state) {
                                    listener.onError(
                                        "Không thể kết nối đến Google Play. Vui lòng kiểm tra kết nối mạng và thử lại.",
                                        canRetry = true
                                    )
                                }
                            }
                        }
                    }
                    BillingManager.ConnectionState.CONNECTED -> {
                        checkProductAndLaunch(activity, productId, state, listener)
                    }
                }
            }
        }
    }

    private fun checkProductAndLaunch(
        activity: Activity,
        productId: String,
        state: OperationState,
        listener: Listener
    ) {
        if (!validateCurrentSession(state, listener)) {
            return
        }
        if (launcher.isProductDetailsAvailable(productId)) {
            executeLaunch(activity, productId, state, listener)
        } else {
            listener.onLoading("Đang tải thông tin gói VIP từ Google Play...")
            launcher.queryProducts { available ->
                if (activeOperationId.get() != state.operationId) {
                    return@queryProducts
                }
                if (!state.queryHandled.compareAndSet(false, true)) {
                    return@queryProducts
                }
                if (!validateCurrentSession(state, listener)) {
                    return@queryProducts
                }
                if (activity.isFinishing || activity.isDestroyed) {
                    terminate(state) {
                        listener.onError(
                            "Giao diện đã đóng trước khi hoàn tất tải gói VIP. Vui lòng thử lại.",
                            canRetry = true
                        )
                    }
                    return@queryProducts
                }
                if (available) {
                    executeLaunch(activity, productId, state, listener)
                } else {
                    terminate(state) {
                        listener.onError(
                            "Sản phẩm VIP chưa sẵn sàng trên Google Play. Vui lòng thử lại sau.",
                            canRetry = true
                        )
                    }
                }
            }
        }
    }

    private fun executeLaunch(
        activity: Activity,
        productId: String,
        state: OperationState,
        listener: Listener
    ) {
        if (activeOperationId.get() != state.operationId) {
            return
        }
        if (!validateCurrentSession(state, listener)) {
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            terminate(state) {
                listener.onError(
                    "Không thể mở giao diện thanh toán (màn hình hiển thị không khả dụng). Vui lòng thử lại.",
                    canRetry = true
                )
            }
            return
        }
        if (launcher.connectionState != BillingManager.ConnectionState.CONNECTED) {
            terminate(state) {
                listener.onError(
                    "Dịch vụ Google Play đã ngắt kết nối. Vui lòng thử lại.",
                    canRetry = true
                )
            }
            return
        }
        if (!state.launchedFlow.compareAndSet(false, true)) {
            return
        }
        val launched = launcher.launchBillingFlow(
            activity = activity,
            productId = productId,
            onAuthRequired = {
                terminate(state) {
                    if (state.hasSignInCallback) {
                        listener.onRequestSignIn()
                    } else {
                        listener.onShowSignInPrompt()
                    }
                }
            },
            onError = { errMsg ->
                terminate(state) {
                    listener.onError(errMsg, canRetry = true)
                }
            }
        )

        if (!launched) {
            // Synchronous launch failure
            terminate(state) {
                listener.onError(
                    "Không thể bắt đầu thanh toán qua Google Play. Vui lòng thử lại sau.",
                    canRetry = true
                )
            }
        } else {
            terminate(state) {
                listener.onLaunchSuccess()
            }
        }
    }
}
