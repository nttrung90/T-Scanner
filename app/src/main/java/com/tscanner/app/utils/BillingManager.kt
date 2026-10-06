package com.tscanner.app.utils

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.UnfetchedProduct
import com.tscanner.app.data.model.VipTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.os.Handler
import android.os.Looper
import com.tscanner.app.R
import com.tscanner.app.utils.billing.BillingOperationOrigin
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages Google Play In-App Purchases and Subscriptions for T-Scanner VIP.
 *
 * Core Capabilities:
 * - Thread-safe initialization and lifecycle management with Google Play Store.
 * - Automatic reconnection with exponential backoff if Billing service disconnects.
 * - Querying product details for Subscriptions (yearly/monthly) and In-App products.
 * - Launching Google Play purchase flow seamlessly from any Activity.
 * - Secure purchase verification, acknowledgment, and idempotent VIP tier activation in [AppAuthManager].
 * - One-click purchase restoration across devices and reinstallations.
 * - Seam-based design enabling 100% JVM unit test coverage without physical Play Services.
 */
class BillingManager private constructor(
    private val context: Context,
    private val clientProvider: BillingClientProvider = DefaultBillingClientProvider(context),
    private var verifier: com.tscanner.app.utils.billing.PurchaseVerifier = com.tscanner.app.utils.billing.PlayPurchaseVerifier.getInstance(),
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "BillingManager"
        private const val PREFS_NAME = "tscanner_billing_prefs"
        private const val KEY_LAST_PURCHASED_PRODUCT = "last_purchased_product"
        private const val KEY_LAST_PURCHASE_TOKEN = "last_purchase_token"
        private const val KEY_LAST_PURCHASE_TIME = "last_purchase_time"
        private const val KEY_BILLING_VIP_ACTIVE = "billing_vip_active"

        // Google Play Console Product IDs
        const val PRODUCT_VIP_YEARLY = "tscanner_vip_yearly"
        const val PRODUCT_VIP_MONTHLY = "tscanner_vip_monthly"
        const val PRODUCT_VIP_LIFETIME = "tscanner_vip_lifetime"

        val ALL_SUBSCRIPTION_IDS = listOf(
            PRODUCT_VIP_YEARLY,
            PRODUCT_VIP_MONTHLY,
            "vip_yearly",
            "vip_monthly"
        )

        val ALL_INAPP_IDS = listOf(
            PRODUCT_VIP_LIFETIME,
            "vip_lifetime"
        )

        val ALLOWED_PRODUCT_IDS: Set<String> = setOf(
            PRODUCT_VIP_YEARLY,
            PRODUCT_VIP_MONTHLY,
            PRODUCT_VIP_LIFETIME,
            "vip_yearly",
            "vip_monthly",
            "vip_lifetime"
        )

        @Volatile
        private var instance: BillingManager? = null

        fun getInstance(context: Context): BillingManager {
            return instance ?: synchronized(this) {
                instance ?: BillingManager(context.applicationContext ?: context).also { instance = it }
            }
        }

        @VisibleForTesting
        fun createInstanceForTesting(
            context: Context,
            clientProvider: BillingClientProvider
        ): BillingManager {
            val mgr = BillingManager(
                context = context,
                clientProvider = clientProvider,
                verifier = com.tscanner.app.utils.billing.PlayPurchaseVerifier(allowLocalFallback = true),
                ioDispatcher = Dispatchers.Unconfined
            )
            instance = mgr
            return mgr
        }

        @VisibleForTesting
        fun createInstanceForTesting(
            context: Context,
            clientProvider: BillingClientProvider,
            verifier: com.tscanner.app.utils.billing.PurchaseVerifier,
            ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined
        ): BillingManager {
            val mgr = BillingManager(context, clientProvider, verifier, ioDispatcher)
            instance = mgr
            return mgr
        }

        @VisibleForTesting
        fun resetInstanceForTesting() {
            synchronized(this) {
                instance?.destroy()
                instance = null
            }
        }
    }

    @VisibleForTesting
    fun setPurchaseVerifierForTesting(verifier: com.tscanner.app.utils.billing.PurchaseVerifier) {
        this.verifier = verifier
    }

    fun isVerifierConfigured(): Boolean {
        return when (val v = verifier) {
            is com.tscanner.app.utils.billing.PlayPurchaseVerifier -> v.isConfigured()
            is com.tscanner.app.utils.billing.NoOpLocalPurchaseVerifier -> false
            else -> true
        }
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        CLOSED
    }

    data class PurchaseAuthRequiredEvent(
        val purchase: Purchase,
        val message: String,
        val targetOwnerId: String?,
        val sessionGeneration: Long,
        val processEpoch: String = AppAuthManager.getProcessEpoch(),
        val isRetry: Boolean = false,
        val operationContext: com.tscanner.app.utils.billing.BillingOperationContext? = null,
        val onReleaseAttempt: (() -> Unit)? = null,
        val onCommitAttempt: (() -> Unit)? = null
    ) {
        val recoveryKey: String
            get() {
                val opId = operationContext?.operationId ?: "standalone_op"
                return "$opId:${targetOwnerId ?: "guest"}:$sessionGeneration:$processEpoch:${purchase.purchaseToken}"
            }

        fun releaseAttempt() {
            onReleaseAttempt?.invoke()
        }

        fun commitAttempt() {
            onCommitAttempt?.invoke()
        }
    }

    fun interface PurchaseCallback {
        fun onPurchaseResult(success: Boolean, message: String?, purchase: Purchase?)
        fun onAuthRequired(event: PurchaseAuthRequiredEvent) {}
    }

    /**
     * Interface wrapping BillingClient for JVM testability.
     */
    interface BillingClientWrapper {
        val isReady: Boolean
        fun startConnection(listener: BillingClientStateListener)
        fun endConnection()
        fun queryProductDetailsAsync(
            params: QueryProductDetailsParams,
            listener: (BillingResult, QueryProductDetailsResult) -> Unit
        )
        fun launchBillingFlow(activity: Activity, params: BillingFlowParams): BillingResult
        fun acknowledgePurchase(
            params: AcknowledgePurchaseParams,
            listener: (BillingResult) -> Unit
        )
        fun queryPurchasesAsync(
            params: QueryPurchasesParams,
            listener: (BillingResult, List<Purchase>) -> Unit
        )
    }

    fun interface BillingClientProvider {
        fun createBillingClient(listener: PurchasesUpdatedListener): BillingClientWrapper
    }

    private class DefaultBillingClientProvider(private val appContext: Context) : BillingClientProvider {
        override fun createBillingClient(listener: PurchasesUpdatedListener): BillingClientWrapper {
            return DefaultBillingClientWrapper(appContext, listener)
        }
    }

    private class DefaultBillingClientWrapper(
        context: Context,
        listener: PurchasesUpdatedListener
    ) : BillingClientWrapper {
        private val client: BillingClient = BillingClient.newBuilder(context)
            .setListener(listener)
            .enableAutoServiceReconnection()
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .enablePrepaidPlans()
                    .build()
            )
            .build()

        override val isReady: Boolean
            get() = client.isReady

        override fun startConnection(listener: BillingClientStateListener) {
            client.startConnection(listener)
        }

        override fun endConnection() {
            client.endConnection()
        }

        override fun queryProductDetailsAsync(
            params: QueryProductDetailsParams,
            listener: (BillingResult, QueryProductDetailsResult) -> Unit
        ) {
            client.queryProductDetailsAsync(params) { result, queryProductDetailsResult ->
                listener(result, queryProductDetailsResult)
            }
        }

        override fun launchBillingFlow(activity: Activity, params: BillingFlowParams): BillingResult {
            return client.launchBillingFlow(activity, params)
        }

        override fun acknowledgePurchase(
            params: AcknowledgePurchaseParams,
            listener: (BillingResult) -> Unit
        ) {
            client.acknowledgePurchase(params) { result ->
                listener(result)
            }
        }

        override fun queryPurchasesAsync(
            params: QueryPurchasesParams,
            listener: (BillingResult, List<Purchase>) -> Unit
        ) {
            client.queryPurchasesAsync(params) { result, list ->
                listener(result, list)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val billingClient: BillingClientWrapper by lazy {
        clientProvider.createBillingClient(this)
    }

    val connectionCoordinator by lazy {
        com.tscanner.app.utils.billing.BillingConnectionCoordinator(
            clientProvider = { billingClient },
            onConnected = {
                queryAllProducts()
                syncActivePurchasesInternal()
            },
            onDisconnected = {
                retryConnectionWithBackoff()
            },
            maxReconnectAttempts = 5
        )
    }

    val connectionState: StateFlow<ConnectionState>
        get() = connectionCoordinator.connectionState

    private val _products = MutableStateFlow<Map<String, ProductDetails>>(emptyMap())
    val products: StateFlow<Map<String, ProductDetails>> = _products.asStateFlow()

    private val purchaseCallbacks = CopyOnWriteArrayList<PurchaseCallback>()
    private val authRequiredListeners = CopyOnWriteArrayList<(PurchaseAuthRequiredEvent) -> Unit>()
    private val attemptedRecoveryKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun getRecoveryKey(
        opContext: com.tscanner.app.utils.billing.BillingOperationContext?,
        targetOwnerId: String?,
        sessionGeneration: Long,
        processEpoch: String,
        purchaseToken: String
    ): String {
        val opId = opContext?.operationId ?: "standalone_op"
        return "$opId:${targetOwnerId ?: "guest"}:$sessionGeneration:$processEpoch:$purchaseToken"
    }

    fun recordRecoveryAttempted(recoveryKey: String) {
        attemptedRecoveryKeys.add(recoveryKey)
    }

    fun releaseRecoveryAttempt(recoveryKey: String) {
        attemptedRecoveryKeys.remove(recoveryKey)
    }

    fun isRecoveryAttempted(recoveryKey: String): Boolean {
        return attemptedRecoveryKeys.contains(recoveryKey) ||
                com.tscanner.app.ui.dialogs.VipPurchaseAuthConsumer.isRecoveryStartedOrCompleted(recoveryKey)
    }

    @Volatile
    private var activePurchaseOwnerUserId: String? = null
    @Volatile
    private var activePurchaseContext: com.tscanner.app.utils.billing.BillingOperationContext? = null
    private val isPurchaseFlowActive = AtomicBoolean(false)
    private val purchaseSuccessNotifiedForFlow = AtomicBoolean(false)
    @Volatile
    private var isDestroyed = false

    @VisibleForTesting
    fun getActivePurchaseContextForTesting(): com.tscanner.app.utils.billing.BillingOperationContext? = activePurchaseContext

    @VisibleForTesting
    fun isPurchaseFlowActiveForTesting(): Boolean = isPurchaseFlowActive.get()

    @VisibleForTesting
    fun setActivePurchaseOwnerForTesting(userId: String?) {
        activePurchaseOwnerUserId = userId
        activePurchaseContext = com.tscanner.app.utils.billing.BillingOperationContext(
            ownerAppUserId = userId,
            sessionGeneration = AppAuthManager.getSessionGeneration(),
            operationType = com.tscanner.app.utils.billing.BillingOperationType.PURCHASE
        )
    }

    init {
        startConnection()
    }

    fun addPurchaseCallback(callback: PurchaseCallback) {
        if (!purchaseCallbacks.contains(callback)) {
            purchaseCallbacks.add(callback)
        }
    }

    fun removePurchaseCallback(callback: PurchaseCallback) {
        purchaseCallbacks.remove(callback)
    }

    fun addAuthRequiredListener(listener: (PurchaseAuthRequiredEvent) -> Unit) {
        if (!authRequiredListeners.contains(listener)) {
            authRequiredListeners.add(listener)
        }
    }

    fun removeAuthRequiredListener(listener: (PurchaseAuthRequiredEvent) -> Unit) {
        authRequiredListeners.remove(listener)
    }

    private val productObservers = CopyOnWriteArrayList<(Map<String, ProductDetails>) -> Unit>()

    fun addProductsObserver(observer: (Map<String, ProductDetails>) -> Unit) {
        if (!productObservers.contains(observer)) {
            productObservers.add(observer)
        }
    }

    fun removeProductsObserver(observer: (Map<String, ProductDetails>) -> Unit) {
        productObservers.remove(observer)
    }

    private fun notifyProductObservers(products: Map<String, ProductDetails>) {
        productObservers.forEach { observer ->
            try {
                observer(products)
            } catch (e: Throwable) {
                Log.e(TAG, "Error in product observer", e)
            }
        }
    }

    /**
     * Connects to Google Play Billing service.
     * Coalesces concurrent calls and queues waiters when CONNECTING (F07).
     */
    fun startConnection(onSetupFinished: ((Boolean) -> Unit)? = null) {
        connectionCoordinator.startConnection(onSetupFinished)
    }

    private fun retryConnectionWithBackoff() {
        if (connectionCoordinator.state == ConnectionState.CLOSED) return
        val attempts = connectionCoordinator.reconnectAttempts
        val delayMs = (1000L * (1 shl attempts.coerceAtMost(4))).coerceAtMost(16000L)
        scope.launch {
            kotlinx.coroutines.delay(delayMs)
            connectionCoordinator.retryConnection()
        }
    }

    fun destroy() {
        isDestroyed = true
        connectionCoordinator.close()
        scope.cancel()
        purchaseCallbacks.clear()
        authRequiredListeners.clear()
        attemptedRecoveryKeys.clear()
        try {
            billingClient.endConnection()
        } catch (e: Exception) {
            Log.w(TAG, "Error ending billing client connection", e)
        }
    }

    /**
     * Queries both Subscription and In-App products from Google Play Console.
     */
    fun queryAllProducts(onComplete: ((Map<String, ProductDetails>) -> Unit)? = null) {
        if (!billingClient.isReady) {
            Log.w(TAG, "Cannot query products: BillingClient not ready")
            onComplete?.invoke(_products.value)
            return
        }

        val allDetails = mutableMapOf<String, ProductDetails>()

        // 1. Query Subscriptions
        val subProductList = ALL_SUBSCRIPTION_IDS.map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        }
        val subParams = QueryProductDetailsParams.newBuilder()
            .setProductList(subProductList)
            .build()

        billingClient.queryProductDetailsAsync(subParams) { result, queryResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                for (details in queryResult.productDetailsList) {
                    allDetails[details.productId] = details
                }
                for (unfetched in queryResult.unfetchedProductList) {
                    Log.w(TAG, "Unfetched subscription product: ${unfetched.productId}, statusCode: ${unfetched.statusCode}")
                }
            } else {
                Log.w(TAG, "Failed to query subscriptions: ${result.debugMessage}")
            }

            // 2. Query In-App One-Time Products
            val inAppProductList = ALL_INAPP_IDS.map { id ->
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(id)
                    .setProductType(BillingClient.ProductType.INAPP)
                .build()
            }
            val inAppParams = QueryProductDetailsParams.newBuilder()
                .setProductList(inAppProductList)
                .build()

            billingClient.queryProductDetailsAsync(inAppParams) { inAppResult, inAppQueryResult ->
                if (inAppResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    for (details in inAppQueryResult.productDetailsList) {
                        allDetails[details.productId] = details
                    }
                    for (unfetched in inAppQueryResult.unfetchedProductList) {
                        Log.w(TAG, "Unfetched in-app product: ${unfetched.productId}, statusCode: ${unfetched.statusCode}")
                    }
                }
                _products.value = allDetails
                notifyProductObservers(allDetails)
                Log.d(TAG, "Loaded ${allDetails.size} products from Google Play")
                onComplete?.invoke(allDetails)
            }
        }
    }

    sealed class ProductPresentationState {
        object Loading : ProductPresentationState()
        object Unavailable : ProductPresentationState()
        data class Available(
            val productId: String,
            val offerToken: String?,
            val displayPrice: String,
            val recurringPrice: String?,
            val recurringPeriod: String?,
            val hasFreeTrial: Boolean,
            val freeTrialPeriod: String?,
            val currencyCode: String,
            val isSubscription: Boolean
        ) : ProductPresentationState()
    }

    @VisibleForTesting
    fun setProductsForTesting(map: Map<String, ProductDetails>) {
        _products.value = map
        notifyProductObservers(map)
    }

    /**
     * Selects the most appropriate subscription offer:
     * 1. An offer with a free trial phase (if available)
     * 2. The base plan offer (offerId == null or empty)
     * 3. The first available offer
     */
    fun selectBestOffer(details: ProductDetails): ProductDetails.SubscriptionOfferDetails? {
        val offers = details.subscriptionOfferDetails
        if (offers.isNullOrEmpty()) return null

        val trialOffer = offers.firstOrNull { offer ->
            offer.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L }
        }
        if (trialOffer != null) {
            return trialOffer
        }

        return offers.firstOrNull { it.offerId.isNullOrEmpty() } ?: offers.firstOrNull()
    }

    /**
     * Resolves the presentation state of a product for the UI paywall.
     */
    fun getProductPresentationState(productId: String): ProductPresentationState {
        val map = _products.value ?: emptyMap()
        val details = map[productId]
        if (details == null) {
            return if (map.isEmpty()) {
                ProductPresentationState.Loading
            } else {
                ProductPresentationState.Unavailable
            }
        }

        if (details.productType == BillingClient.ProductType.SUBS) {
            val offer = selectBestOffer(details) ?: return ProductPresentationState.Unavailable
            val phases = offer.pricingPhases.pricingPhaseList
            if (phases.isEmpty()) return ProductPresentationState.Unavailable

            val trialPhase = phases.firstOrNull { it.priceAmountMicros == 0L }
            val recurringPhase = phases.lastOrNull { it.priceAmountMicros > 0L } ?: phases.last()

            return if (trialPhase != null) {
                ProductPresentationState.Available(
                    productId = productId,
                    offerToken = offer.offerToken,
                    displayPrice = recurringPhase.formattedPrice,
                    recurringPrice = recurringPhase.formattedPrice,
                    recurringPeriod = recurringPhase.billingPeriod,
                    hasFreeTrial = true,
                    freeTrialPeriod = trialPhase.billingPeriod,
                    currencyCode = recurringPhase.priceCurrencyCode,
                    isSubscription = true
                )
            } else {
                ProductPresentationState.Available(
                    productId = productId,
                    offerToken = offer.offerToken,
                    displayPrice = recurringPhase.formattedPrice,
                    recurringPrice = null,
                    recurringPeriod = recurringPhase.billingPeriod,
                    hasFreeTrial = false,
                    freeTrialPeriod = null,
                    currencyCode = recurringPhase.priceCurrencyCode,
                    isSubscription = true
                )
            }
        } else {
            val oneTime = details.oneTimePurchaseOfferDetails ?: return ProductPresentationState.Unavailable
            return ProductPresentationState.Available(
                productId = productId,
                offerToken = null,
                displayPrice = oneTime.formattedPrice,
                recurringPrice = null,
                recurringPeriod = null,
                hasFreeTrial = false,
                freeTrialPeriod = null,
                currencyCode = oneTime.priceCurrencyCode,
                isSubscription = false
            )
        }
    }

    fun formatBillingPeriod(context: Context, isoPeriod: String?): String {
        if (isoPeriod.isNullOrBlank()) return ""
        return try {
            when (isoPeriod.uppercase()) {
                "P1Y" -> context.getString(R.string.vip_period_yearly)
                "P1M" -> context.getString(R.string.vip_period_monthly)
                "P1W", "P7D" -> context.getString(R.string.vip_period_7_days)
                "P2W", "P14D" -> context.getString(R.string.vip_period_14_days)
                else -> isoPeriod
            }
        } catch (_: Throwable) {
            when (isoPeriod.uppercase()) {
                "P1Y" -> "year"
                "P1M" -> "month"
                "P1W", "P7D" -> "7 days"
                "P2W", "P14D" -> "14 days"
                else -> isoPeriod
            }
        }
    }

    fun formatSubText(context: Context, state: ProductPresentationState.Available): String {
        return try {
            if (state.hasFreeTrial && !state.freeTrialPeriod.isNullOrBlank()) {
                val trialPeriod = formatBillingPeriod(context, state.freeTrialPeriod)
                val recPrice = state.recurringPrice ?: state.displayPrice
                val recPeriod = formatBillingPeriod(context, state.recurringPeriod)
                val priceWithPeriod = if (recPeriod.isNotBlank()) "$recPrice / $recPeriod" else recPrice
                context.getString(R.string.vip_trial_recurring_format, trialPeriod, priceWithPeriod)
            } else {
                context.getString(R.string.vip_cancel_anytime)
            }
        } catch (_: Throwable) {
            if (state.hasFreeTrial && !state.freeTrialPeriod.isNullOrBlank()) {
                val trialPeriod = formatBillingPeriod(context, state.freeTrialPeriod)
                val recPrice = state.recurringPrice ?: state.displayPrice
                val recPeriod = formatBillingPeriod(context, state.recurringPeriod)
                val priceWithPeriod = if (recPeriod.isNotBlank()) "$recPrice / $recPeriod" else recPrice
                "Free trial $trialPeriod, then $priceWithPeriod • Cancel anytime"
            } else {
                "Cancel anytime on Google Play"
            }
        }
    }

    fun formatButtonText(
        context: Context,
        currentUser: com.tscanner.app.data.model.UserProfile?,
        state: ProductPresentationState
    ): String {
        val hasTrial = (state as? ProductPresentationState.Available)?.hasFreeTrial == true
        val email = currentUser?.email
        return try {
            if (!email.isNullOrBlank()) {
                if (hasTrial) {
                    context.getString(R.string.vip_btn_trial_email_format, email)
                } else {
                    context.getString(R.string.vip_btn_upgrade_email_format, email)
                }
            } else {
                if (hasTrial) {
                    context.getString(R.string.vip_btn_sign_in_for_trial)
                } else {
                    context.getString(R.string.vip_btn_sign_in_to_upgrade)
                }
            }
        } catch (_: Throwable) {
            if (!email.isNullOrBlank()) {
                if (hasTrial) "Start Free Trial ($email)" else "Upgrade VIP ($email)"
            } else {
                if (hasTrial) "Sign in to start Free Trial" else "Sign in to upgrade VIP"
            }
        }
    }

    /**
     * Retrieves the formatted price string (e.g. "20.000 ₫") for a given product ID.
     */
    fun getFormattedPrice(productId: String): String? {
        val state = getProductPresentationState(productId)
        return if (state is ProductPresentationState.Available) {
            state.displayPrice
        } else null
    }

    /**
     * Launches the Google Play billing flow for a specific product ID.
     *
     * @return true if billing flow was launched successfully, false otherwise.
     */
    fun launchBillingFlow(
        activity: Activity,
        productId: String = PRODUCT_VIP_YEARLY,
        onError: ((String) -> Unit)? = null
    ): Boolean {
        return launchBillingFlow(activity, productId, offerToken = null, onAuthRequired = null, onError = onError)
    }

    fun launchBillingFlow(
        activity: Activity,
        productId: String,
        offerToken: String?,
        onError: ((String) -> Unit)? = null
    ): Boolean {
        return launchBillingFlow(activity, productId, offerToken = offerToken, onAuthRequired = null, onError = onError)
    }

    fun launchBillingFlow(
        activity: Activity,
        productId: String,
        offerToken: String?,
        onAuthRequired: (() -> Unit)?,
        onError: ((String) -> Unit)? = null
    ): Boolean {
        val currentUserId = AppAuthManager.getCurrentUser()?.id
        val currentGen = AppAuthManager.getSessionGeneration()
        val purchaseContext = com.tscanner.app.utils.billing.BillingOperationContext(
            ownerAppUserId = currentUserId,
            sessionGeneration = currentGen,
            operationType = com.tscanner.app.utils.billing.BillingOperationType.PURCHASE
        )
        activePurchaseContext = purchaseContext
        activePurchaseOwnerUserId = currentUserId
        if (!billingClient.isReady) {
            Log.w(TAG, "Cannot launch billing flow: BillingClient is not ready")
            activePurchaseContext = null
            activePurchaseOwnerUserId = null
            isPurchaseFlowActive.set(false)
            onError?.invoke("Dịch vụ Google Play chưa sẵn sàng, vui lòng thử lại sau.")
            startConnection()
            return false
        }

        val details = _products.value?.get(productId)
        if (details == null) {
            Log.w(TAG, "Product '$productId' not found in cached product details. Attempting live re-query...")
            queryAllProducts { reloaded ->
                val reloadedDetails = reloaded[productId]
                if (reloadedDetails != null) {
                    launchWithProductDetails(activity, reloadedDetails, offerToken, onAuthRequired, onError)
                } else {
                    activePurchaseContext = null
                    activePurchaseOwnerUserId = null
                    isPurchaseFlowActive.set(false)
                    onError?.invoke("Sản phẩm VIP chưa sẵn sàng trên Google Play.")
                }
            }
            return false
        }

        return launchWithProductDetails(activity, details, offerToken, onAuthRequired, onError)
    }

    private fun launchWithProductDetails(
        activity: Activity,
        details: ProductDetails,
        explicitOfferToken: String? = null,
        onAuthRequired: (() -> Unit)? = null,
        onError: ((String) -> Unit)?
    ): Boolean {
        val currentUserId = AppAuthManager.getCurrentUser()?.id
        val currentGen = AppAuthManager.getSessionGeneration()
        val opContext = activePurchaseContext
        if (opContext != null && opContext.isStale(currentUserId, currentGen)) {
            Log.w(TAG, "Cannot launch billing flow: session changed or owner mismatch (expected=${opContext.ownerAppUserId}, current=$currentUserId)")
            activePurchaseContext = null
            activePurchaseOwnerUserId = null
            isPurchaseFlowActive.set(false)
            onError?.invoke("Phiên đăng nhập đã thay đổi. Vui lòng thử lại.")
            return false
        }
        val token = AppAuthManager.getSessionToken() ?: AppAuthManager.getCurrentUser()?.idToken
        if (token.isNullOrBlank() || com.tscanner.app.utils.billing.PlayPurchaseVerifier.isTokenExpired(token)) {
            Log.w(TAG, "Cannot launch billing flow: session token missing or expired")
            activePurchaseContext = null
            activePurchaseOwnerUserId = null
            isPurchaseFlowActive.set(false)
            if (onAuthRequired != null) {
                onAuthRequired()
            } else {
                onError?.invoke("Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.")
            }
            return false
        }
        if (!billingClient.isReady) {
            Log.w(TAG, "Cannot launch billing flow: BillingClient is not ready")
            activePurchaseContext = null
            activePurchaseOwnerUserId = null
            isPurchaseFlowActive.set(false)
            onError?.invoke("Dịch vụ Google Play chưa sẵn sàng, vui lòng thử lại sau.")
            return false
        }

        val productDetailsParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)

        if (details.productType == BillingClient.ProductType.SUBS) {
            val selectedOffer = selectBestOffer(details)
            val offerToken = explicitOfferToken ?: selectedOffer?.offerToken
            if (offerToken.isNullOrEmpty()) {
                Log.e(TAG, "No valid subscription offer token found for ${details.productId}")
                activePurchaseContext = null
                activePurchaseOwnerUserId = null
                isPurchaseFlowActive.set(false)
                onError?.invoke("Không tìm thấy gói ưu đãi hợp lệ trên Google Play.")
                return false
            }
            productDetailsParamsBuilder.setOfferToken(offerToken)
        }

        val flowParamsBuilder = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParamsBuilder.build()))

        val obfuscatedAccountId = com.tscanner.app.utils.billing.PlayPurchaseVerifier.computeObfuscatedAccountId(activePurchaseOwnerUserId)
        if (obfuscatedAccountId != null) {
            flowParamsBuilder.setObfuscatedAccountId(obfuscatedAccountId)
        }

        val flowParams = flowParamsBuilder.build()
        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "launchBillingFlow failed: code=${result.responseCode}, msg=${result.debugMessage}")
            activePurchaseContext = null
            activePurchaseOwnerUserId = null
            isPurchaseFlowActive.set(false)
            onError?.invoke("Không thể mở giao diện thanh toán Google Play (${result.responseCode})")
            return false
        }
        isPurchaseFlowActive.set(true)
        return true
    }

    /**
     * Callback from Google Play Billing when purchases update.
     */
    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        if (isDestroyed || !scope.isActive) {
            Log.w(TAG, "Suppressing onPurchasesUpdated because manager is destroyed")
            return
        }
        val currentUserId = AppAuthManager.getCurrentUser()?.id
        val currentGen = AppAuthManager.getSessionGeneration()
        val isStale = activePurchaseContext?.isStale(currentUserId, currentGen) ?: false

        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (!purchases.isNullOrEmpty()) {
                    isPurchaseFlowActive.set(true)
                    purchaseSuccessNotifiedForFlow.set(false)
                    val opContext = activePurchaseContext
                    for (purchase in purchases) {
                        processPurchase(
                            purchase = purchase,
                            origin = BillingOperationOrigin.PURCHASE,
                            operationContext = opContext
                        )
                    }
                } else {
                    Log.d(TAG, "onPurchasesUpdated: OK with empty purchases list")
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.d(TAG, "User canceled billing flow")
                isPurchaseFlowActive.set(false)
                if (!isStale) {
                    notifyCallbacks(false, "Đã hủy giao dịch", null, activePurchaseContext)
                } else {
                    Log.w(TAG, "Suppressing USER_CANCELED callback for stale session")
                }
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Log.d(TAG, "Item already owned. Triggering purchase sync...")
                isPurchaseFlowActive.set(false)
                val opContext = activePurchaseContext
                if (!isStale) {
                    syncActivePurchasesInternal { foundAny ->
                        val curUser = AppAuthManager.getCurrentUser()?.id
                        val curGen = AppAuthManager.getSessionGeneration()
                        if (opContext?.isStale(curUser, curGen) == true) {
                            Log.w(TAG, "Suppressing ITEM_ALREADY_OWNED callback for stale session")
                            return@syncActivePurchasesInternal
                        }
                        if (foundAny) {
                            notifyCallbacks(true, "Bạn đã sở hữu gói này. Đã khôi phục VIP thành công!", null, opContext)
                        } else {
                            notifyCallbacks(false, "Sản phẩm đã được sở hữu nhưng chưa thể đồng bộ.", null, opContext)
                        }
                    }
                } else {
                    Log.w(TAG, "Suppressing ITEM_ALREADY_OWNED callback for stale session")
                }
            }
            else -> {
                Log.e(TAG, "Purchase failed: code=${billingResult.responseCode}, msg=${billingResult.debugMessage}")
                isPurchaseFlowActive.set(false)
                if (!isStale) {
                    notifyCallbacks(false, "Giao dịch không thành công (${billingResult.responseCode})", null, activePurchaseContext)
                } else {
                    Log.w(TAG, "Suppressing failure callback for stale session")
                }
            }
        }
    }

    /**
     * Processes a single purchase: checks catalog allowlist, verifies via PurchaseVerifier (B06),
     * enforces ownership/session isolation, acknowledges if needed, and applies entitlement via BillingEntitlementStore (B05).
     *
     * Invariant (F08): only emits interactive UI completion events when [origin] is [BillingOperationOrigin.PURCHASE].
     */
    fun processPurchase(
        purchase: Purchase,
        origin: BillingOperationOrigin = BillingOperationOrigin.PURCHASE,
        operationContext: com.tscanner.app.utils.billing.BillingOperationContext? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (isDestroyed || !scope.isActive) {
            Log.w(TAG, "processPurchase aborted: BillingManager is destroyed")
            return
        }
        val opContext = operationContext ?: activePurchaseContext
        val matchedProduct = purchase.products.firstOrNull()
        if (matchedProduct == null || !ALLOWED_PRODUCT_IDS.contains(matchedProduct)) {
            Log.e(TAG, "Rejecting purchase for unknown/disallowed product: $matchedProduct")
            tryNotifyPurchaseFailure(origin, "Sản phẩm không thuộc danh mục được cấp phép.", purchase, opContext)
            onComplete?.invoke(false)
            return
        }

        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            val targetOwnerId = if (operationContext != null) {
                operationContext.ownerAppUserId
            } else if (origin == BillingOperationOrigin.RESTORE || origin == BillingOperationOrigin.RECONCILE) {
                AppAuthManager.getCurrentUser()?.id
            } else {
                activePurchaseContext?.ownerAppUserId ?: activePurchaseOwnerUserId ?: AppAuthManager.getCurrentUser()?.id
            }
            val productType = if (ALL_SUBSCRIPTION_IDS.contains(matchedProduct)) {
                BillingClient.ProductType.SUBS
            } else {
                BillingClient.ProductType.INAPP
            }

            val request = com.tscanner.app.utils.billing.VerificationRequest(
                ownerAppUserId = targetOwnerId,
                productId = matchedProduct,
                productType = productType,
                purchaseToken = purchase.purchaseToken,
                orderId = purchase.orderId,
                obfuscatedAccountId = purchase.accountIdentifiers?.obfuscatedAccountId
                    ?: com.tscanner.app.utils.billing.PlayPurchaseVerifier.computeObfuscatedAccountId(targetOwnerId),
                clientPurchaseTimeMillis = purchase.purchaseTime
            )

            scope.launch {
                val result = try {
                    verifier.verifyPurchase(request)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    Log.i(TAG, "Purchase verification cancelled: ${e.message}")
                    throw e
                }
                if (isDestroyed || !isActive) {
                    Log.w(TAG, "Discarding verification response: manager is destroyed or coroutine cancelled")
                    return@launch
                }
                when (result) {
                    is com.tscanner.app.utils.billing.VerificationResult.Success -> {
                        val currentUserId = AppAuthManager.getCurrentUser()?.id
                        val currentGen = AppAuthManager.getSessionGeneration()
                        val isStale = opContext?.isStale(currentUserId, currentGen)
                            ?: (targetOwnerId != currentUserId)

                        // Inactive/revoked verified entitlements must not report active VIP success (Q03)
                        if (!result.entitlement.isCurrentlyActive()) {
                            Log.w(TAG, "Purchase verified but entitlement is inactive: state=${result.entitlement.state}")
                            applyVerifiedEntitlement(result.entitlement, targetOwnerId)
                            tryNotifyPurchaseFailure(origin, "Gói VIP không còn hoạt động (${result.entitlement.state})", purchase, opContext)
                            onComplete?.invoke(false)
                            return@launch
                        }

                        // Apply the authoritatively verified entitlement immediately (R10)
                        val applied = applyVerifiedEntitlement(result.entitlement, targetOwnerId)
                        if (applied) {
                            if (!isStale) {
                                val successMsg = if (origin == BillingOperationOrigin.RESTORE) {
                                    "Đã khôi phục thành công gói VIP!"
                                } else {
                                    "Thanh toán thành công! Gói VIP đã được kích hoạt."
                                }
                                tryNotifyPurchaseSuccess(
                                    origin = origin,
                                    targetOwnerId = targetOwnerId,
                                    message = successMsg,
                                    purchase = purchase,
                                    operationContext = opContext
                                )
                                onComplete?.invoke(true)
                            } else {
                                Log.w(TAG, "Stale purchase flow: applied to owner '$targetOwnerId' in store, suppressing UI to current user '$currentUserId'")
                                onComplete?.invoke(false)
                            }

                            // Best-effort auxiliary acknowledge to Google Play client SDK (R10)
                            if (!purchase.isAcknowledged) {
                                val ackParams = AcknowledgePurchaseParams.newBuilder()
                                    .setPurchaseToken(purchase.purchaseToken)
                                    .build()
                                billingClient.acknowledgePurchase(ackParams) { ackResult ->
                                    if (ackResult.responseCode == BillingClient.BillingResponseCode.OK) {
                                        Log.i(TAG, "Client auxiliary acknowledge succeeded for ${purchase.orderId}")
                                    } else {
                                        Log.w(TAG, "Client auxiliary acknowledge non-critical failure: ${ackResult.debugMessage}. Server outbox guarantees recovery.")
                                    }
                                }
                            }
                        } else {
                            Log.e(TAG, "Failed to persist entitlement or stale inactive state in store")
                            tryNotifyPurchaseFailure(origin, "Gói VIP không hợp lệ hoặc không thể lưu", purchase, opContext)
                            onComplete?.invoke(false)
                        }
                    }
                    is com.tscanner.app.utils.billing.VerificationResult.MissingBackendGate -> {
                        Log.w(TAG, "Purchase verification blocked: ${result.message}")
                        tryNotifyPurchaseFailure(origin, result.message, purchase, opContext)
                        onComplete?.invoke(false)
                    }
                    is com.tscanner.app.utils.billing.VerificationResult.Pending -> {
                        Log.i(TAG, "Purchase pending: ${result.message}")
                        tryNotifyPurchaseFailure(origin, result.message, purchase, opContext)
                        onComplete?.invoke(false)
                    }
                    is com.tscanner.app.utils.billing.VerificationResult.Rejected -> {
                        Log.e(TAG, "Purchase rejected: ${result.reason} - ${result.message}")
                        if (result.tombstone != null) {
                            applyVerifiedEntitlement(result.tombstone, targetOwnerId)
                        } else {
                            Log.w(TAG, "Rejection received without authoritative tombstone snapshot; preserving existing entitlements without local mutation.")
                        }
                        tryNotifyPurchaseFailure(origin, "Giao dịch bị từ chối: ${result.message}", purchase, opContext)
                        onComplete?.invoke(false)
                    }
                    is com.tscanner.app.utils.billing.VerificationResult.TransientError -> {
                        Log.e(TAG, "Purchase verification transient error: ${result.message}", result.cause)
                        tryNotifyPurchaseFailure(origin, "Lỗi kết nối khi xác thực: ${result.message}", purchase, opContext)
                        onComplete?.invoke(false)
                    }
                    is com.tscanner.app.utils.billing.VerificationResult.AuthRequired -> {
                        Log.w(TAG, "Purchase verification requires authentication: ${result.message}")
                        val currentUserId = AppAuthManager.getCurrentUser()?.id
                        val currentGen = AppAuthManager.getSessionGeneration()
                        val currentEpoch = AppAuthManager.getProcessEpoch()
                        val isStale = opContext?.isStale(currentUserId, currentGen)
                            ?: (targetOwnerId != currentUserId)

                        val purchaseToken = purchase.purchaseToken
                        val recoveryKey = getRecoveryKey(opContext, targetOwnerId, currentGen, currentEpoch, purchaseToken)

                        val isInteractive = (origin == BillingOperationOrigin.PURCHASE) && !isStale
                        val alreadyRetried = isRecoveryAttempted(recoveryKey)

                        val event = PurchaseAuthRequiredEvent(
                            purchase = purchase,
                            message = result.message,
                            targetOwnerId = targetOwnerId,
                            sessionGeneration = currentGen,
                            processEpoch = currentEpoch,
                            isRetry = alreadyRetried,
                            operationContext = opContext,
                            onReleaseAttempt = {
                                releaseRecoveryAttempt(recoveryKey)
                            },
                            onCommitAttempt = {
                                recordRecoveryAttempted(recoveryKey)
                            }
                        )

                        if (isInteractive) {
                            notifyPurchaseAuthRequired(event, origin)
                        } else {
                            Log.d(TAG, "Deferred auth recovery for non-interactive origin or stale session (origin=$origin, isStale=$isStale)")
                        }
                        onComplete?.invoke(false)
                    }
                }
            }
        } else if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            Log.i(TAG, "Purchase is pending: ${purchase.orderId}")
            tryNotifyPurchaseFailure(origin, "Giao dịch đang chờ Google xử lý thanh toán.", purchase, opContext)
            onComplete?.invoke(false)
        } else {
            Log.w(TAG, "Purchase in unspecified state: ${purchase.purchaseState}")
            onComplete?.invoke(false)
        }
    }

    private fun tryNotifyPurchaseSuccess(
        origin: BillingOperationOrigin,
        targetOwnerId: String?,
        message: String,
        purchase: Purchase,
        operationContext: com.tscanner.app.utils.billing.BillingOperationContext? = null
    ) {
        if (origin != BillingOperationOrigin.PURCHASE) return

        val currentOwner = AppAuthManager.getCurrentUser()?.id
        val currentGen = AppAuthManager.getSessionGeneration()
        val opContext = operationContext ?: activePurchaseContext
        if (opContext != null && opContext.isStale(currentOwner, currentGen)) {
            Log.w(
                TAG,
                "Dropping interactive purchase success UI event: session is stale ($opContext vs current=$currentOwner, gen=$currentGen)"
            )
            return
        }
        if (targetOwnerId != null && currentOwner != targetOwnerId) {
            Log.w(
                TAG,
                "Dropping interactive purchase success UI event: user switched ($currentOwner != $targetOwnerId)"
            )
            return
        }

        if (isPurchaseFlowActive.get()) {
            if (purchaseSuccessNotifiedForFlow.compareAndSet(false, true)) {
                notifyCallbacks(true, message, purchase, opContext)
            } else {
                Log.d(TAG, "Coalescing duplicate purchase success event in batch")
            }
        } else {
            notifyCallbacks(true, message, purchase, opContext)
        }
    }

    private fun tryNotifyPurchaseFailure(
        origin: BillingOperationOrigin,
        message: String,
        purchase: Purchase?,
        operationContext: com.tscanner.app.utils.billing.BillingOperationContext? = null
    ) {
        if (origin != BillingOperationOrigin.PURCHASE) return
        val currentOwner = AppAuthManager.getCurrentUser()?.id
        val currentGen = AppAuthManager.getSessionGeneration()
        val opContext = operationContext ?: activePurchaseContext
        if (opContext != null && opContext.isStale(currentOwner, currentGen)) {
            Log.w(
                TAG,
                "Dropping interactive purchase failure UI event: session is stale ($opContext vs current=$currentOwner, gen=$currentGen)"
            )
            return
        }
        isPurchaseFlowActive.set(false)
        purchaseSuccessNotifiedForFlow.set(false)
        notifyCallbacks(false, message, purchase, opContext)
    }

    private fun notifyPurchaseAuthRequired(
        event: PurchaseAuthRequiredEvent,
        origin: BillingOperationOrigin
    ) {
        if (origin != BillingOperationOrigin.PURCHASE) return
        val currentOwner = AppAuthManager.getCurrentUser()?.id
        val currentGen = AppAuthManager.getSessionGeneration()
        val opContext = event.operationContext ?: activePurchaseContext
        if (opContext != null && opContext.isStale(currentOwner, currentGen)) {
            Log.w(
                TAG,
                "Dropping interactive purchase auth-required UI event: session is stale ($opContext vs current=$currentOwner, gen=$currentGen)"
            )
            return
        }
        isPurchaseFlowActive.set(false)
        purchaseSuccessNotifiedForFlow.set(false)

        val action = Runnable {
            if (isDestroyed || !scope.isActive) return@Runnable

            // K02: Validate session state right inside Runnable before dispatching
            val runOwner = AppAuthManager.getCurrentUser()?.id
            val runGen = AppAuthManager.getSessionGeneration()
            val runEpoch = AppAuthManager.getProcessEpoch()
            if (event.targetOwnerId != null && event.targetOwnerId != runOwner) {
                Log.w(TAG, "Dropping auth event: owner mismatch before dispatch (event=${event.targetOwnerId}, current=$runOwner)")
                return@Runnable
            }
            if (event.sessionGeneration != -1L && event.sessionGeneration != runGen) {
                Log.w(TAG, "Dropping auth event: generation mismatch before dispatch (event=${event.sessionGeneration}, current=$runGen)")
                return@Runnable
            }
            if (event.processEpoch.isNotEmpty() && runEpoch.isNotEmpty() && event.processEpoch != runEpoch) {
                Log.w(TAG, "Dropping auth event: epoch mismatch before dispatch (event=${event.processEpoch}, current=$runEpoch)")
                return@Runnable
            }
            if (opContext != null && opContext.isStale(runOwner, runGen)) {
                Log.w(TAG, "Dropping auth event: opContext is stale before dispatch ($opContext vs current=$runOwner, gen=$runGen)")
                return@Runnable
            }

            var handled = false
            for (listener in authRequiredListeners) {
                // K02: Re-check session mutation before invoking EACH listener to suppress delivery if earlier listener changed session
                val nowOwner = AppAuthManager.getCurrentUser()?.id
                val nowGen = AppAuthManager.getSessionGeneration()
                val nowEpoch = AppAuthManager.getProcessEpoch()
                if (event.targetOwnerId != null && event.targetOwnerId != nowOwner) {
                    Log.w(TAG, "Suppressed stale auth event delivery due to owner change ($event vs current=$nowOwner)")
                    break
                }
                if (event.sessionGeneration != -1L && event.sessionGeneration != nowGen) {
                    Log.w(TAG, "Suppressed stale auth event delivery due to generation change ($event vs currentGen=$nowGen)")
                    break
                }
                if (event.processEpoch.isNotEmpty() && nowEpoch.isNotEmpty() && event.processEpoch != nowEpoch) {
                    Log.w(TAG, "Suppressed stale auth event delivery due to epoch change ($event vs currentEpoch=$nowEpoch)")
                    break
                }
                if (opContext != null && opContext.isStale(nowOwner, nowGen)) {
                    Log.w(TAG, "Suppressed stale auth event delivery due to stale opContext ($opContext vs current=$nowOwner, gen=$nowGen)")
                    break
                }

                try {
                    listener.invoke(event)
                    handled = true
                } catch (e: Exception) {
                    Log.e(TAG, "Error invoking authRequiredListener", e)
                }
            }
            for (callback in purchaseCallbacks) {
                val nowOwner = AppAuthManager.getCurrentUser()?.id
                val nowGen = AppAuthManager.getSessionGeneration()
                val nowEpoch = AppAuthManager.getProcessEpoch()
                if (event.targetOwnerId != null && event.targetOwnerId != nowOwner) break
                if (event.sessionGeneration != -1L && event.sessionGeneration != nowGen) break
                if (event.processEpoch.isNotEmpty() && nowEpoch.isNotEmpty() && event.processEpoch != nowEpoch) break
                if (opContext != null && opContext.isStale(nowOwner, nowGen)) break

                try {
                    callback.onAuthRequired(event)
                } catch (e: Exception) {
                    Log.e(TAG, "Error invoking callback.onAuthRequired", e)
                }
            }
            if (!handled && authRequiredListeners.isEmpty()) {
                for (callback in purchaseCallbacks) {
                    try {
                        callback.onPurchaseResult(false, event.message, event.purchase)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error invoking fallback callback", e)
                    }
                }
            }
        }
        try {
            val mainLooper = Looper.getMainLooper()
            if (mainLooper != null && Looper.myLooper() != mainLooper) {
                Handler(mainLooper).post(action)
            } else {
                action.run()
            }
        } catch (_: Exception) {
            action.run()
        }
    }

    /**
     * Applies verified entitlement to BillingEntitlementStore and profile with late-callback guards.
     * Enforces (Q03 / R05): propagates persistence errors and eliminates redundant store writes.
     *
     * @return true if entitlement was committed to store successfully, false otherwise.
     */
    private fun applyVerifiedEntitlement(
        entitlement: com.tscanner.app.utils.billing.BillingEntitlement,
        targetOwnerId: String?
    ): Boolean {
        val store = com.tscanner.app.utils.billing.BillingEntitlementStore.getInstance()
        val snapshot = com.tscanner.app.utils.billing.UserEntitlementSnapshot(
            ownerAppUserId = targetOwnerId ?: entitlement.ownerAppUserId,
            entitlements = listOf(entitlement)
        )
        val applyResult = store.applySnapshotTyped(context, snapshot)
        if (applyResult !is com.tscanner.app.utils.billing.ApplySnapshotResult.Success) {
            Log.e(TAG, "Failed to persist entitlement in BillingEntitlementStore for owner '$targetOwnerId'")
            return false
        }

        val committedSnapshot = applyResult.snapshot
        val committedItem = committedSnapshot.entitlements.find { it.purchaseToken == entitlement.purchaseToken }
        val isCurrentlyActive = committedItem?.isCurrentlyActive() ?: false

        // 1. Save billing receipt locally
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_PURCHASED_PRODUCT, entitlement.productId)
            .putString(KEY_LAST_PURCHASE_TOKEN, entitlement.purchaseToken)
            .putLong(KEY_LAST_PURCHASE_TIME, entitlement.purchaseTimeMillis)
            .putBoolean(KEY_BILLING_VIP_ACTIVE, isCurrentlyActive)
            .apply()

        // 2. Project committed snapshot to user profile (Single store commit, eliminating double-write bug F07)
        val currentLoggedInUser = AppAuthManager.getCurrentUser()
        if (currentLoggedInUser != null && currentLoggedInUser.id == targetOwnerId) {
            val projected = AppAuthManager.projectSnapshotToProfile(context, committedSnapshot)
            if (!projected) {
                Log.e(TAG, "Failed to project snapshot to user profile")
                return false
            }
            Log.i(TAG, "Projected verified entitlement to current user '${currentLoggedInUser.id}'")
        } else if (targetOwnerId == null) {
            val projected = AppAuthManager.projectSnapshotToProfile(context, committedSnapshot)
            if (!projected) return false
            Log.i(TAG, "Projected verified guest entitlement")
        } else {
            Log.w(
                TAG,
                "Late purchase callback guard: Entitlement belongs to owner '$targetOwnerId', " +
                        "but current user is '${currentLoggedInUser?.id}'. Entitlement preserved in store for owner."
            )
        }
        return isCurrentlyActive
    }

    /**
     * Restores existing purchases across devices / reinstallations.
     * Enforces authoritative reconciliation (B07): waits for full verify & acknowledge of all purchases.
     */
    fun restorePurchases(
        onAuthRequired: ((message: String) -> Unit)? = null,
        opContext: com.tscanner.app.utils.billing.BillingOperationContext? = null,
        onComplete: (success: Boolean, message: String) -> Unit
    ) {
        val initialContext = opContext ?: com.tscanner.app.utils.billing.BillingOperationContext(
            ownerAppUserId = AppAuthManager.getCurrentUser()?.id,
            sessionGeneration = AppAuthManager.getSessionGeneration(),
            operationType = com.tscanner.app.utils.billing.BillingOperationType.RESTORE
        )

        if (!billingClient.isReady) {
            startConnection { connected ->
                if (isDestroyed || !scope.isActive) return@startConnection
                val currentUserId = AppAuthManager.getCurrentUser()?.id
                val currentGen = AppAuthManager.getSessionGeneration()
                if (initialContext.isStale(currentUserId, currentGen)) {
                    Log.w(TAG, "Suppressing restore after reconnect: session changed from ${initialContext.ownerAppUserId} to $currentUserId")
                    return@startConnection
                }
                if (connected) {
                    performReconciliation(initialContext, onAuthRequired, onComplete)
                } else {
                    onComplete(false, "Không thể kết nối đến Google Play. Vui lòng thử lại sau.")
                }
            }
            return
        }

        performReconciliation(initialContext, onAuthRequired, onComplete)
    }

    private fun performReconciliation(
        operationContext: com.tscanner.app.utils.billing.BillingOperationContext? = null,
        onAuthRequired: ((message: String) -> Unit)? = null,
        onComplete: (success: Boolean, message: String) -> Unit
    ) {
        if (isDestroyed || !scope.isActive) {
            Log.w(TAG, "performReconciliation aborted: BillingManager is destroyed")
            return
        }
        val restoreContext = operationContext ?: com.tscanner.app.utils.billing.BillingOperationContext(
            ownerAppUserId = AppAuthManager.getCurrentUser()?.id,
            sessionGeneration = AppAuthManager.getSessionGeneration(),
            operationType = com.tscanner.app.utils.billing.BillingOperationType.RESTORE
        )
        val reconciler = com.tscanner.app.utils.billing.BillingReconciliation(
            context = context,
            billingClient = billingClient,
            processPurchaseAction = { purchase, onProcessed ->
                processPurchase(
                    purchase = purchase,
                    origin = BillingOperationOrigin.RESTORE,
                    operationContext = restoreContext,
                    onComplete = onProcessed
                )
            },
            operationContext = restoreContext,
            verifier = verifier,
            ioDispatcher = ioDispatcher,
            coroutineScope = scope
        )

        reconciler.reconcile { result ->
            if (isDestroyed || !scope.isActive) {
                Log.w(TAG, "Suppressing restore UI callback because manager is destroyed")
                return@reconcile
            }
            val currentUserId = AppAuthManager.getCurrentUser()?.id
            val currentGen = AppAuthManager.getSessionGeneration()
            if (restoreContext.isStale(currentUserId, currentGen)) {
                Log.w(
                    TAG,
                    "Suppressing restore UI callback for stale session " +
                            "(opOwner=${restoreContext.ownerAppUserId} gen=${restoreContext.sessionGeneration}, " +
                            "current=$currentUserId gen=$currentGen)"
                )
                return@reconcile
            }
            when (result) {
                is com.tscanner.app.utils.billing.ReconciliationResult.Restored -> {
                    if (result.failedCount > 0) {
                        onComplete(true, "Khôi phục một phần: Một số giao dịch chưa thể hoàn tất.")
                    } else {
                        onComplete(true, "Đã khôi phục thành công gói VIP từ Google Play!")
                    }
                }
                is com.tscanner.app.utils.billing.ReconciliationResult.NoActivePurchases -> {
                    onComplete(false, "Không tìm thấy giao dịch VIP nào đang hoạt động trên tài khoản Google Play này.")
                }
                is com.tscanner.app.utils.billing.ReconciliationResult.PendingApproval -> {
                    onComplete(false, result.message)
                }
                is com.tscanner.app.utils.billing.ReconciliationResult.NetworkError -> {
                    onComplete(false, "Không thể kết nối đến Google Play. Vui lòng kiểm tra kết nối mạng (${result.responseCode}).")
                }
                is com.tscanner.app.utils.billing.ReconciliationResult.AuthRequired -> {
                    if (onAuthRequired != null) {
                        onAuthRequired(result.message)
                    } else {
                        onComplete(false, result.message)
                    }
                }
                is com.tscanner.app.utils.billing.ReconciliationResult.ProcessingFailed -> {
                    onComplete(false, "Xác thực hoặc xác nhận giao dịch không thành công.")
                }
            }
        }
    }

    /**
     * Synchronizes active purchases silently on app start, login, or foreground (F09).
     */
    fun syncPurchases(onComplete: ((Boolean) -> Unit)? = null) {
        val initialContext = com.tscanner.app.utils.billing.BillingOperationContext(
            ownerAppUserId = AppAuthManager.getCurrentUser()?.id,
            sessionGeneration = AppAuthManager.getSessionGeneration(),
            operationType = com.tscanner.app.utils.billing.BillingOperationType.SYNC
        )
        if (billingClient.isReady) {
            syncActivePurchasesInternal(initialContext, onComplete)
        } else {
            startConnection { connected ->
                if (isDestroyed || !scope.isActive) return@startConnection
                val currentUserId = AppAuthManager.getCurrentUser()?.id
                val currentGen = AppAuthManager.getSessionGeneration()
                if (initialContext.isStale(currentUserId, currentGen)) {
                    Log.w(TAG, "Suppressing sync after reconnect: session changed")
                    return@startConnection
                }
                if (connected) {
                    syncActivePurchasesInternal(initialContext, onComplete)
                } else {
                    onComplete?.invoke(false)
                }
            }
        }
    }

    /**
     * Synchronizes active purchases silently on app start or Google login.
     */
    fun syncPurchasesOnStart() {
        syncPurchases()
    }

    private fun syncActivePurchasesInternal(
        operationContext: com.tscanner.app.utils.billing.BillingOperationContext? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (isDestroyed || !scope.isActive) {
            Log.w(TAG, "syncActivePurchasesInternal aborted: BillingManager is destroyed")
            return
        }
        val syncContext = operationContext ?: com.tscanner.app.utils.billing.BillingOperationContext(
            ownerAppUserId = AppAuthManager.getCurrentUser()?.id,
            sessionGeneration = AppAuthManager.getSessionGeneration(),
            operationType = com.tscanner.app.utils.billing.BillingOperationType.SYNC
        )
        val reconciler = com.tscanner.app.utils.billing.BillingReconciliation(
            context = context,
            billingClient = billingClient,
            processPurchaseAction = { purchase, onProcessed ->
                processPurchase(
                    purchase = purchase,
                    origin = BillingOperationOrigin.RECONCILE,
                    operationContext = syncContext,
                    onComplete = onProcessed
                )
            },
            operationContext = syncContext,
            verifier = verifier,
            ioDispatcher = ioDispatcher,
            coroutineScope = scope
        )

        reconciler.reconcile { result ->
            if (isDestroyed || !scope.isActive) {
                Log.w(TAG, "Suppressing sync callback because manager is destroyed")
                return@reconcile
            }
            val currentUserId = AppAuthManager.getCurrentUser()?.id
            val currentGen = AppAuthManager.getSessionGeneration()
            if (syncContext.isStale(currentUserId, currentGen)) {
                Log.w(TAG, "Suppressing sync callback for stale session")
                return@reconcile
            }
            when (result) {
                is com.tscanner.app.utils.billing.ReconciliationResult.Restored -> {
                    onComplete?.invoke(true)
                }
                is com.tscanner.app.utils.billing.ReconciliationResult.NoActivePurchases,
                is com.tscanner.app.utils.billing.ReconciliationResult.PendingApproval -> {
                    onComplete?.invoke(false)
                }
                is com.tscanner.app.utils.billing.ReconciliationResult.NetworkError -> {
                    onComplete?.invoke(false)
                }
                is com.tscanner.app.utils.billing.ReconciliationResult.AuthRequired -> {
                    onComplete?.invoke(false)
                }
                is com.tscanner.app.utils.billing.ReconciliationResult.ProcessingFailed -> {
                    onComplete?.invoke(false)
                }
            }
        }
    }

    /**
     * Binds any pending local Google Play VIP purchase to a newly logged-in user.
     * Enforces (R02):
     * - Legacy local flags are migrated as UNVERIFIED_CLIENT, never VERIFIED_ACTIVE.
     * - Tokens already bound to another registered user cannot be claimed or transferred locally.
     * - Server snapshotVersion is not arbitrarily incremented locally.
     */
    fun bindPurchasesToCurrentUser(context: Context) {
        val user = AppAuthManager.getCurrentUser() ?: return
        val store = com.tscanner.app.utils.billing.BillingEntitlementStore.getInstance()

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isBillingVipActive = prefs.getBoolean(KEY_BILLING_VIP_ACTIVE, false)
        val productId = prefs.getString(KEY_LAST_PURCHASED_PRODUCT, PRODUCT_VIP_YEARLY) ?: PRODUCT_VIP_YEARLY
        val purchaseToken = prefs.getString(KEY_LAST_PURCHASE_TOKEN, null)
        val purchaseTime = prefs.getLong(KEY_LAST_PURCHASE_TIME, System.currentTimeMillis())

        if (isBillingVipActive && !purchaseToken.isNullOrBlank()) {
            val alreadyOwnedByOther = store.isTokenBoundToOtherUser(context, purchaseToken, null)
            if (!alreadyOwnedByOther) {
                val guestSnapshot = store.getSnapshot(context, null)
                val existsInGuest = guestSnapshot.entitlements.any { it.purchaseToken == purchaseToken }
                if (!existsInGuest) {
                    // Legacy migration: must be UNVERIFIED_CLIENT, never VERIFIED_ACTIVE
                    val legacyEntitlement = com.tscanner.app.utils.billing.BillingEntitlement(
                        id = purchaseToken,
                        ownerAppUserId = null,
                        productId = productId,
                        productType = if (ALL_SUBSCRIPTION_IDS.contains(productId)) "subs" else "inapp",
                        purchaseToken = purchaseToken,
                        source = com.tscanner.app.utils.billing.EntitlementSource.LEGACY_LOCAL,
                        state = com.tscanner.app.utils.billing.EntitlementState.UNVERIFIED_CLIENT,
                        purchaseTimeMillis = purchaseTime,
                        expiryTimeMillis = when (productId) {
                            PRODUCT_VIP_LIFETIME, "vip_lifetime" -> null
                            PRODUCT_VIP_MONTHLY, "vip_monthly" -> purchaseTime + (30L * 24 * 3600 * 1000)
                            else -> purchaseTime + (365L * 24 * 3600 * 1000)
                        }
                    )
                    store.applyEntitlement(context, legacyEntitlement)
                }
            }
            // Consume legacy boolean so it is never re-read
            prefs.edit().remove(KEY_BILLING_VIP_ACTIVE).apply()
        }

        val bound = store.bindGuestEntitlementsToUser(context, user.id)
        if (bound) {
            val snapshot = store.getSnapshot(context, user.id)
            AppAuthManager.applyEntitlementSnapshot(context, snapshot)
            Log.i(TAG, "Bound local Play Billing VIP to newly signed-in user '${user.id}'")
        }
    }

    private fun KEY_LAST_PURCHASECHASE_PRODUCT_SAFE(prefs: SharedPreferences): String {
        return KEY_LAST_PURCHASED_PRODUCT
    }

    private fun notifyCallbacks(
        success: Boolean,
        message: String?,
        purchase: Purchase?,
        operationContext: com.tscanner.app.utils.billing.BillingOperationContext? = null
    ) {
        if (isDestroyed || !scope.isActive) {
            Log.w(TAG, "Suppressing notifyCallbacks because manager is destroyed")
            return
        }
        val action = Runnable {
            if (isDestroyed || !scope.isActive) {
                Log.w(TAG, "Dropping main-thread UI callback because manager is destroyed")
                return@Runnable
            }
            if (operationContext != null) {
                val currentUserId = AppAuthManager.getCurrentUser()?.id
                val currentGen = AppAuthManager.getSessionGeneration()
                if (operationContext.isStale(currentUserId, currentGen)) {
                    Log.w(
                        TAG,
                        "Dropping main-thread UI callback for stale operation context: " +
                                "contextOwner=${operationContext.ownerAppUserId}, gen=${operationContext.sessionGeneration} " +
                                "vs current=$currentUserId, gen=$currentGen"
                    )
                    return@Runnable
                }
            }
            for (callback in purchaseCallbacks) {
                try {
                    callback.onPurchaseResult(success, message, purchase)
                } catch (e: Exception) {
                    Log.e(TAG, "Error invoking purchase callback", e)
                }
            }
        }
        try {
            val mainLooper = Looper.getMainLooper()
            if (mainLooper != null && Looper.myLooper() != mainLooper) {
                Handler(mainLooper).post(action)
            } else {
                action.run()
            }
        } catch (e: Exception) {
            action.run()
        }
    }

    fun isBillingVipActiveLocally(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_BILLING_VIP_ACTIVE, false)
    }
}
