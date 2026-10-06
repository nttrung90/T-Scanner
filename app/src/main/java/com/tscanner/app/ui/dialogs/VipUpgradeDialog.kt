package com.tscanner.app.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import com.tscanner.app.R
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.databinding.DialogVipUpgradeBinding
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.CloudBackupManager
import com.tscanner.app.utils.DefaultVipPurchaseLauncher
import com.tscanner.app.utils.SyncCatalogResult
import com.tscanner.app.utils.SyncResultPresenter
import com.tscanner.app.utils.VipPurchaseActionCoordinator

/**
 * Resolves the upgrade action depending on authentication state and callback availability.
 */
object VipUpgradeActionResolver {
    sealed class Action {
        data class ActivateVip(val email: String) : Action()
        object RequestSignIn : Action()
        object ShowSignInRequiredPrompt : Action()
    }

    fun resolveUpgradeAction(currentUser: com.tscanner.app.data.model.UserProfile?, hasSignInCallback: Boolean): Action {
        val token = currentUser?.idToken ?: com.tscanner.app.utils.AppAuthManager.getSessionToken()
        val isTokenMissingOrExpired = token.isNullOrBlank() || com.tscanner.app.utils.billing.PlayPurchaseVerifier.isTokenExpired(token)
        return if (currentUser != null && currentUser.email.isNotBlank() && !isTokenMissingOrExpired) {
            Action.ActivateVip(currentUser.email)
        } else if (hasSignInCallback) {
            Action.RequestSignIn
        } else {
            Action.ShowSignInRequiredPrompt
        }
    }
}

class VipUpgradeDialog(
    context: Context,
    private val onRequestDrivePermission: (() -> Unit)? = null,
    private val onUpgradeSuccess: (() -> Unit)? = null,
    private val onRequestSignIn: (() -> Unit)? = null,
    private val onSyncResult: ((SyncCatalogResult) -> Unit)? = null,
    private val coordinatorProvider: ((Context) -> VipPurchaseActionCoordinator)? = null,
    private val onRequestSignInForAction: ((com.tscanner.app.utils.VipContinuationAction) -> Unit)? = null,
    var onRequestSignInForRecovery: ((
        action: com.tscanner.app.utils.VipContinuationAction,
        opContext: com.tscanner.app.utils.billing.BillingOperationContext?,
        onStarted: () -> Unit,
        onRefused: () -> Unit
    ) -> Unit)? = null
) : Dialog(context) {

    private val hostContext: Context = context
    private lateinit var binding: DialogVipUpgradeBinding
    private val purchaseCoordinator: VipPurchaseActionCoordinator by lazy {
        coordinatorProvider?.invoke(context) ?: VipPurchaseActionCoordinator(DefaultVipPurchaseLauncher(context))
    }

    private val productsObserver: (Map<String, com.android.billingclient.api.ProductDetails>) -> Unit = { _ ->
        val act = findActivity(context)
        if (act != null && !act.isFinishing && !act.isDestroyed) {
            act.runOnUiThread {
                renderProductPricing()
            }
        } else {
            renderProductPricing()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogVipUpgradeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        applyDialogWidth()

        renderProductPricing()
        setupListeners()
    }

    private fun renderProductPricing() {
        if (!::binding.isInitialized) return
        val currentContext = context ?: return
        val billing = BillingManager.getInstance(currentContext)
        val state = billing.getProductPresentationState(BillingManager.PRODUCT_VIP_YEARLY)
        val currentUser = AppAuthManager.getCurrentUser()

        when (state) {
            is BillingManager.ProductPresentationState.Loading -> {
                binding.tvVipPrice.text = currentContext.getString(R.string.vip_price_loading)
                binding.tvVipPriceSub.visibility = android.view.View.GONE
                binding.btnConfirmVipUpgrade.isEnabled = false
                binding.btnConfirmVipUpgrade.text = billing.formatButtonText(currentContext, currentUser, state)
            }
            is BillingManager.ProductPresentationState.Unavailable -> {
                binding.tvVipPrice.text = currentContext.getString(R.string.vip_product_unavailable)
                binding.tvVipPriceSub.visibility = android.view.View.GONE
                binding.btnConfirmVipUpgrade.isEnabled = false
                binding.btnConfirmVipUpgrade.text = billing.formatButtonText(currentContext, currentUser, state)
            }
            is BillingManager.ProductPresentationState.Available -> {
                binding.tvVipPrice.text = state.displayPrice
                val subText = billing.formatSubText(currentContext, state)
                binding.tvVipPriceSub.text = subText
                binding.tvVipPriceSub.visibility = android.view.View.VISIBLE
                binding.btnConfirmVipUpgrade.isEnabled = true
                binding.btnConfirmVipUpgrade.text = billing.formatButtonText(currentContext, currentUser, state)
            }
        }
    }

    private val purchaseCallback = BillingManager.PurchaseCallback { success, message, _ ->
        if (!isShowing) return@PurchaseCallback
        val currentContext = context ?: return@PurchaseCallback
        val act = findActivity(currentContext)
        if (act == null || act.isFinishing || act.isDestroyed) {
            return@PurchaseCallback
        }
        if (success) {
            if (!message.isNullOrBlank()) {
                Toast.makeText(currentContext, message, Toast.LENGTH_LONG).show()
            }
            onPostUpgradeFlow()
        } else if (!message.isNullOrBlank()) {
            Toast.makeText(currentContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private val authRequiredListener: (BillingManager.PurchaseAuthRequiredEvent) -> Unit = { event ->
        val act = findActivity(context)
        if (act != null && !act.isFinishing && !act.isDestroyed && isShowing) {
            act.runOnUiThread {
                handlePurchaseAuthRequired(event)
            }
        }
    }

    private fun handlePurchaseAuthRequired(event: BillingManager.PurchaseAuthRequiredEvent) {
        val currentContext = context ?: return
        val act = findActivity(currentContext)
        val isUiActive = isShowing && (act != null && !act.isFinishing && !act.isDestroyed)

        val decision = VipPurchaseAuthConsumer.evaluate(
            event = event,
            currentOwnerId = AppAuthManager.getCurrentUser()?.id,
            currentGeneration = AppAuthManager.getSessionGeneration(),
            currentEpoch = AppAuthManager.getProcessEpoch(),
            isUiActive = isUiActive
        )
        executePurchaseAuthDecision(decision, event)
    }

    @androidx.annotation.VisibleForTesting
    fun handlePurchaseAuthRequiredWithDecision(
        event: BillingManager.PurchaseAuthRequiredEvent,
        currentOwnerId: String? = AppAuthManager.getCurrentUser()?.id,
        currentGeneration: Long = AppAuthManager.getSessionGeneration(),
        currentEpoch: String = AppAuthManager.getProcessEpoch(),
        isUiActive: Boolean = isShowing && (context?.let { findActivity(it) }?.let { !it.isFinishing && !it.isDestroyed } ?: false)
    ): PurchaseAuthDecision {
        val decision = VipPurchaseAuthConsumer.evaluate(
            event = event,
            currentOwnerId = currentOwnerId,
            currentGeneration = currentGeneration,
            currentEpoch = currentEpoch,
            isUiActive = isUiActive
        )
        executePurchaseAuthDecision(decision, event)
        return decision
    }

    private fun executePurchaseAuthDecision(
        decision: PurchaseAuthDecision,
        event: BillingManager.PurchaseAuthRequiredEvent
    ) {
        val currentContext = hostContext
        when (decision) {
            is PurchaseAuthDecision.Stop -> {
                runCatching { Toast.makeText(currentContext, decision.message, Toast.LENGTH_LONG).show() }
            }
            is PurchaseAuthDecision.RequestReauth -> {
                runCatching { dismiss() }
                val currentUser = AppAuthManager.getCurrentUser()
                if (currentUser != null) {
                    runCatching {
                        Toast.makeText(
                            currentContext,
                            currentContext.getString(decision.messageResId),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                val recoveryHandler = onRequestSignInForRecovery
                if (recoveryHandler != null) {
                    recoveryHandler.invoke(
                        decision.action,
                        decision.operationContext,
                        { decision.confirmStarted() },
                        { decision.release() }
                    )
                } else {
                    // S02: Missing recovery-capable host; safely release reservation instead of early commit
                    decision.release()
                    if (onRequestSignInForAction != null) {
                        onRequestSignInForAction?.invoke(decision.action)
                    } else {
                        onRequestSignIn?.invoke()
                    }
                }
            }
            is PurchaseAuthDecision.Ignore, is PurchaseAuthDecision.Defer -> {
                // Safely drop or defer without UI side-effects
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val billing = BillingManager.getInstance(context)
        billing.addPurchaseCallback(purchaseCallback)
        billing.addAuthRequiredListener(authRequiredListener)
        billing.addProductsObserver(productsObserver)
        if (billing.products.value.isEmpty()) {
            billing.queryAllProducts()
        }
        renderProductPricing()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        val billing = BillingManager.getInstance(context)
        billing.removePurchaseCallback(purchaseCallback)
        billing.removeAuthRequiredListener(authRequiredListener)
        billing.removeProductsObserver(productsObserver)
    }

    @VisibleForTesting
    internal fun hasSignInCallbackEvaluated(): Boolean {
        return (onRequestSignIn != null || onRequestSignInForAction != null)
    }

    @VisibleForTesting
    internal fun resolveUpgradeActionForTesting(): VipUpgradeActionResolver.Action {
        val user = AppAuthManager.getCurrentUser()
        return VipUpgradeActionResolver.resolveUpgradeAction(
            currentUser = user,
            hasSignInCallback = hasSignInCallbackEvaluated()
        )
    }

    @VisibleForTesting
    internal fun handlePurchaseAuthRequiredForTesting(event: BillingManager.PurchaseAuthRequiredEvent) {
        handlePurchaseAuthRequired(event)
    }

    @VisibleForTesting
    internal fun triggerUpgradeClickForTesting(
        activity: android.app.Activity? = null,
        testListener: VipPurchaseActionCoordinator.Listener? = null
    ) {
        val user = AppAuthManager.getCurrentUser()
        purchaseCoordinator.onUpgradeClicked(
            activity = activity,
            currentUser = user,
            hasSignInCallback = hasSignInCallbackEvaluated(),
            productId = BillingManager.PRODUCT_VIP_YEARLY,
            listener = testListener ?: object : VipPurchaseActionCoordinator.Listener {
                override fun onLoading(message: String) {}
                override fun onError(message: String, canRetry: Boolean) {}
                override fun onLaunchSuccess() {}
                override fun onRequestSignIn() {
                    dismiss()
                    if (onRequestSignInForAction != null) {
                        onRequestSignInForAction.invoke(com.tscanner.app.utils.VipContinuationAction.UPGRADE)
                    } else {
                        onRequestSignIn?.invoke()
                    }
                }
                override fun onShowSignInPrompt() {
                    dismiss()
                }
            }
        )
    }

    private fun setupListeners() {
        // Nâng cấp VIP qua Google Play Billing
        binding.btnConfirmVipUpgrade.setOnClickListener {
            val user = AppAuthManager.getCurrentUser()
            val activity = findActivity(context)
            purchaseCoordinator.onUpgradeClicked(
                activity = activity,
                currentUser = user,
                hasSignInCallback = hasSignInCallbackEvaluated(),
                productId = BillingManager.PRODUCT_VIP_YEARLY,
                listener = object : VipPurchaseActionCoordinator.Listener {
                    override fun onLoading(message: String) {
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }

                    override fun onError(message: String, canRetry: Boolean) {
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }

                    override fun onLaunchSuccess() {
                        // Billing flow launched on Google Play; awaiting result via purchaseCallback
                    }

                    override fun onRequestSignIn() {
                        dismiss()
                        val currentUser = AppAuthManager.getCurrentUser()
                        if (currentUser != null) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.vip_session_expired_reauth_prompt),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        if (onRequestSignInForAction != null) {
                            onRequestSignInForAction.invoke(com.tscanner.app.utils.VipContinuationAction.UPGRADE)
                        } else {
                            onRequestSignIn?.invoke()
                        }
                    }

                    override fun onShowSignInPrompt() {
                        dismiss()
                        val currentUser = AppAuthManager.getCurrentUser()
                        val message = if (currentUser != null) {
                            context.getString(R.string.vip_session_expired_reauth_prompt)
                        } else {
                            context.getString(R.string.sign_in_to_activate_vip_prompt)
                        }
                        Toast.makeText(
                            context,
                            message,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        }

        // Nút Khôi phục giao dịch mua từ Google Play
        binding.btnRestorePurchases.setOnClickListener {
            val billingManager = BillingManager.getInstance(context)
            binding.btnRestorePurchases.isEnabled = false
            billingManager.restorePurchases(
                onAuthRequired = { authMessage ->
                    binding.btnRestorePurchases.post {
                        binding.btnRestorePurchases.isEnabled = true
                        if (!isShowing) return@post
                        val currentContext = context ?: return@post
                        val act = findActivity(currentContext)
                        if (act == null || act.isFinishing || act.isDestroyed) return@post

                        Toast.makeText(currentContext, authMessage, Toast.LENGTH_LONG).show()
                        dismiss()
                        if (onRequestSignInForAction != null) {
                            onRequestSignInForAction.invoke(com.tscanner.app.utils.VipContinuationAction.RESTORE)
                        } else if (onRequestSignIn != null) {
                            onRequestSignIn.invoke()
                        } else {
                            val currentUser = AppAuthManager.getCurrentUser()
                            val msg = if (currentUser != null) {
                                currentContext.getString(R.string.vip_session_expired_reauth_prompt)
                            } else {
                                currentContext.getString(R.string.sign_in_to_activate_vip_prompt)
                            }
                            Toast.makeText(
                                currentContext,
                                msg,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                onComplete = { success, message ->
                    binding.btnRestorePurchases.post {
                        binding.btnRestorePurchases.isEnabled = true
                        if (!isShowing) return@post
                        val currentContext = context ?: return@post
                        val act = findActivity(currentContext)
                        if (act == null || act.isFinishing || act.isDestroyed) return@post

                        Toast.makeText(currentContext, message, Toast.LENGTH_LONG).show()
                        if (success) {
                            onPostUpgradeFlow()
                        }
                    }
                }
            )
        }

        // Luồng mở rộng: VIP PRO
        binding.cardTierVipPro.setOnClickListener {
            Toast.makeText(
                context,
                context.getString(R.string.vip_pro_coming_soon_toast),
                Toast.LENGTH_SHORT
            ).show()
        }

        // Luồng mở rộng: VIP PRO MAX
        binding.cardTierVipPromax.setOnClickListener {
            Toast.makeText(
                context,
                context.getString(R.string.vip_pro_max_coming_soon_toast),
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnCloseVipDialog.setOnClickListener {
            dismiss()
        }
    }

    /**
     * Development-only trial activation.
     * Separated from the purchase button flow.
     * Strictly blocked in release builds (FLAG_DEBUGGABLE == 0).
     */
    fun activateDevTrialForTesting(email: String): Boolean {
        val appInfo = hostContext.applicationInfo ?: hostContext.applicationContext?.applicationInfo
        val isDebuggable = if (appInfo != null) {
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } else false

        if (!isDebuggable) {
            android.util.Log.e("VipUpgradeDialog", "Dev trial activation rejected: not a debuggable build")
            return false
        }
        AppAuthManager.setUserVipTier(hostContext, VipTier.VIP, durationDays = 365)
        Toast.makeText(
            hostContext,
            "[DEBUG] " + hostContext.getString(R.string.vip_trial_activated_format, email),
            Toast.LENGTH_LONG
        ).show()
        onPostUpgradeFlow()
        return true
    }

    private fun onPostUpgradeFlow() {
        val hasDrive = AppAuthManager.hasDrivePermission(context)
        if (!hasDrive) {
            Toast.makeText(context, context.getString(R.string.grant_drive_permission_prompt), Toast.LENGTH_LONG).show()
            if (onRequestDrivePermission != null) {
                onRequestDrivePermission.invoke()
            } else {
                android.util.Log.w("VipUpgradeDialog", "Drive permission required but onRequestDrivePermission callback was not provided by host")
            }
        } else {
            val appContext = context.applicationContext ?: context
            val startUser = AppAuthManager.getCurrentUser()?.id
            val startGen = AppAuthManager.getSessionGeneration()
            AppAuthManager.runPostAuthorizationSync(context) { syncResult ->
                if (onSyncResult != null) {
                    onSyncResult.invoke(syncResult)
                } else {
                    SyncResultPresenter.present(
                        context = appContext,
                        result = syncResult,
                        expectedSessionGeneration = startGen,
                        expectedUserId = startUser,
                        isHostValid = null
                    )
                }
            }
        }

        onUpgradeSuccess?.invoke()
        dismiss()
    }

    private fun findActivity(ctx: Context): android.app.Activity? {
        var current: Context? = ctx
        while (current is android.content.ContextWrapper) {
            if (current is android.app.Activity) return current
            current = current.baseContext
        }
        return current as? android.app.Activity
    }

    override fun onStart() {
        super.onStart()
        applyDialogWidth()
    }

    private fun applyDialogWidth() {
        val displayMetrics = context.resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.90).toInt().coerceAtMost(
            (480 * displayMetrics.density).toInt()
        )
        window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun dismiss() {
        try {
            if (isShowing) {
                super.dismiss()
            }
        } catch (e: Exception) {
            android.util.Log.w("VipUpgradeDialog", "Error dismissing dialog safely", e)
        }
    }
}
