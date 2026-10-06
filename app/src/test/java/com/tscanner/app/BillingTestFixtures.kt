package com.tscanner.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.UnfetchedProduct
import com.tscanner.app.utils.BillingManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared test fixtures for Billing tests and regression suites.
 *
 * Provides:
 * - [BillingTestContext]: isolates SharedPreferences by name (no cross-file state bleeding).
 * - [BillingFakeSharedPreferences]: thread-safe in-memory SharedPreferences.
 * - [FakeBillingClientWrapper]: full seam implementation supporting deferred callbacks,
 *   separated SUBS vs INAPP purchase lists, and distinct query/ack response codes.
 * - [createTestPurchase]: JSON-backed Purchase factory for JVM tests.
 * - [createTestProductDetails]: JSON-backed ProductDetails factory for JVM tests.
 * - [createTestUnfetchedProduct]: JSON-backed UnfetchedProduct factory for JVM tests.
 */

fun createTestProductDetails(
    productId: String,
    productType: String = BillingClient.ProductType.SUBS,
    title: String = "Test Product $productId",
    formattedPrice: String = "20.000 ₫"
): ProductDetails {
    val json = if (productType == BillingClient.ProductType.SUBS) {
        """
        {
            "productId": "$productId",
            "type": "$productType",
            "title": "$title",
            "name": "$title",
            "description": "Test description",
            "subscriptionOfferDetails": [
                {
                    "offerIdToken": "test_offer_token",
                    "basePlanId": "test_base_plan",
                    "pricingPhases": [
                        {
                            "formattedPrice": "$formattedPrice",
                            "priceAmountMicros": 20000000000,
                            "priceCurrencyCode": "VND",
                            "billingPeriod": "P1M",
                            "recurrenceMode": 1
                        }
                    ]
                }
            ]
        }
        """.trimIndent()
    } else {
        """
        {
            "productId": "$productId",
            "type": "$productType",
            "title": "$title",
            "name": "$title",
            "description": "Test description",
            "oneTimePurchaseOfferDetails": {
                "formattedPrice": "$formattedPrice",
                "priceAmountMicros": 50000000000,
                "priceCurrencyCode": "VND"
            }
        }
        """.trimIndent()
    }
    val constructor = ProductDetails::class.java.getDeclaredConstructor(String::class.java)
    constructor.isAccessible = true
    return constructor.newInstance(json)
}

fun createTestUnfetchedProduct(
    productId: String,
    productType: String = BillingClient.ProductType.SUBS,
    statusCode: Int = 2
): UnfetchedProduct {
    val json = """
    {
        "productId": "$productId",
        "type": "$productType",
        "statusCode": $statusCode
    }
    """.trimIndent()
    val constructor = UnfetchedProduct::class.java.getDeclaredConstructor(String::class.java)
    constructor.isAccessible = true
    return constructor.newInstance(json)
}

fun createTestPurchase(
    productId: String,
    token: String = "token_$productId",
    acknowledged: Boolean = false,
    purchaseState: Int = Purchase.PurchaseState.PURCHASED,
    orderId: String = "GPA.TEST-$token",
    packageName: String = "com.tscanner.app",
    purchaseTime: Long = System.currentTimeMillis()
): Purchase {
    val rawPurchaseState = when (purchaseState) {
        Purchase.PurchaseState.PENDING, 4 -> 4
        else -> purchaseState
    }
    val json = """
        {
            "orderId": "$orderId",
            "packageName": "$packageName",
            "productId": "$productId",
            "productIds": ["$productId"],
            "purchaseTime": $purchaseTime,
            "purchaseState": $rawPurchaseState,
            "purchaseToken": "$token",
            "acknowledged": $acknowledged
        }
    """.trimIndent()
    return Purchase(json, "test_signature")
}

class BillingTestContext(
    private val baseDir: File
) : ContextWrapper(null) {

    private val prefsMap = ConcurrentHashMap<String, BillingFakeSharedPreferences>()

    override fun getFilesDir(): File = File(baseDir, "files").apply { mkdirs() }
    override fun getCacheDir(): File = File(baseDir, "cache").apply { mkdirs() }
    override fun getApplicationContext(): Context = this
    override fun getPackageName(): String = "com.tscanner.app"

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        val key = name ?: "default_prefs"
        return prefsMap.computeIfAbsent(key) { BillingFakeSharedPreferences() }
    }

    fun getFakePreferences(name: String): BillingFakeSharedPreferences {
        return prefsMap.computeIfAbsent(name) { BillingFakeSharedPreferences() }
    }

    fun clearAllPreferences() {
        prefsMap.values.forEach { it.clearDirectly() }
        prefsMap.clear()
    }
}

class BillingFakeSharedPreferences : SharedPreferences {
    private val map = ConcurrentHashMap<String, Any>()

    fun clearDirectly() {
        map.clear()
    }

    override fun getAll(): MutableMap<String, *> = HashMap(map)

    override fun getString(key: String?, defValue: String?): String? {
        if (key == null) return defValue
        return map[key] as? String ?: defValue
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        if (key == null) return defValues
        return (map[key] as? Set<*>)?.mapNotNull { it?.toString() }?.toMutableSet() ?: defValues
    }

    override fun getInt(key: String?, defValue: Int): Int {
        if (key == null) return defValue
        return (map[key] as? Number)?.toInt() ?: defValue
    }

    override fun getLong(key: String?, defValue: Long): Long {
        if (key == null) return defValue
        return (map[key] as? Number)?.toLong() ?: defValue
    }

    override fun getFloat(key: String?, defValue: Float): Float {
        if (key == null) return defValue
        return (map[key] as? Number)?.toFloat() ?: defValue
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        if (key == null) return defValue
        return map[key] as? Boolean ?: defValue
    }

    override fun contains(key: String?): Boolean {
        if (key == null) return false
        return map.containsKey(key)
    }

    override fun edit(): SharedPreferences.Editor = Editor(this)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    class Editor(private val prefs: BillingFakeSharedPreferences) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removes = mutableSetOf<String>()
        private var clearFlag = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            if (key != null) {
                if (value != null) pending[key] = value else removes.add(key)
            }
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
            if (key != null) {
                if (values != null) pending[key] = HashSet(values) else removes.add(key)
            }
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            if (key != null) removes.add(key)
        }

        override fun clear(): SharedPreferences.Editor = apply {
            clearFlag = true
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearFlag) {
                prefs.map.clear()
            }
            removes.forEach { prefs.map.remove(it) }
            pending.forEach { (k, v) ->
                if (v != null) prefs.map[k] = v else prefs.map.remove(k)
            }
        }
    }
}

class FakeBillingClientWrapper : BillingManager.BillingClientWrapper {
    var isReadyValue: Boolean = false
    override val isReady: Boolean get() = isReadyValue

    // Connection setup
    var deferSetup: Boolean = false
    var deferredSetup: BillingClientStateListener? = null
    var returnBillingResultForSetup: BillingResult = BillingResult.newBuilder()
        .setResponseCode(BillingClient.BillingResponseCode.OK)
        .build()

    override fun startConnection(listener: BillingClientStateListener) {
        if (deferSetup) {
            deferredSetup = listener
            return
        }
        isReadyValue = (returnBillingResultForSetup.responseCode == BillingClient.BillingResponseCode.OK)
        listener.onBillingSetupFinished(returnBillingResultForSetup)
    }

    fun completeDeferredSetup(result: BillingResult = returnBillingResultForSetup) {
        isReadyValue = (result.responseCode == BillingClient.BillingResponseCode.OK)
        deferredSetup?.onBillingSetupFinished(result)
        deferredSetup = null
    }

    override fun endConnection() {
        isReadyValue = false
    }

    // Product Details
    val productDetailsListToReturn = mutableListOf<ProductDetails>()
    val unfetchedProductsListToReturn = mutableListOf<UnfetchedProduct>()
    var returnBillingResultForProductDetails: BillingResult = BillingResult.newBuilder()
        .setResponseCode(BillingClient.BillingResponseCode.OK)
        .build()
    var deferProductDetails: Boolean = false
    var deferredProductDetailsListener: ((BillingResult, QueryProductDetailsResult) -> Unit)? = null

    override fun queryProductDetailsAsync(
        params: QueryProductDetailsParams,
        listener: (BillingResult, QueryProductDetailsResult) -> Unit
    ) {
        if (deferProductDetails) {
            deferredProductDetailsListener = listener
            return
        }
        val queryResult = QueryProductDetailsResult.create(
            productDetailsListToReturn.toList(),
            unfetchedProductsListToReturn.toList()
        )
        listener(returnBillingResultForProductDetails, queryResult)
    }

    fun completeDeferredProductDetails(result: BillingResult = returnBillingResultForProductDetails) {
        val queryResult = QueryProductDetailsResult.create(
            productDetailsListToReturn.toList(),
            unfetchedProductsListToReturn.toList()
        )
        deferredProductDetailsListener?.invoke(result, queryResult)
        deferredProductDetailsListener = null
    }

    // Billing Flow Launch
    var lastLaunchedActivity: Activity? = null
    var lastLaunchedParams: BillingFlowParams? = null
    var returnBillingResultForLaunch: BillingResult = BillingResult.newBuilder()
        .setResponseCode(BillingClient.BillingResponseCode.OK)
        .build()

    override fun launchBillingFlow(activity: Activity, params: BillingFlowParams): BillingResult {
        lastLaunchedActivity = activity
        lastLaunchedParams = params
        return returnBillingResultForLaunch
    }

    // Acknowledge Purchase
    var lastAcknowledgedParams: AcknowledgePurchaseParams? = null
    val acknowledgedTokens = mutableListOf<String>()
    var returnBillingResultForAck: BillingResult = BillingResult.newBuilder()
        .setResponseCode(BillingClient.BillingResponseCode.OK)
        .build()
    var deferAck: Boolean = false
    var deferredAck: ((BillingResult) -> Unit)? = null

    override fun acknowledgePurchase(
        params: AcknowledgePurchaseParams,
        listener: (BillingResult) -> Unit
    ) {
        lastAcknowledgedParams = params
        acknowledgedTokens.add(params.purchaseToken)
        if (deferAck) {
            deferredAck = listener
        } else {
            listener(returnBillingResultForAck)
        }
    }

    fun completeDeferredAck(result: BillingResult = returnBillingResultForAck) {
        deferredAck?.invoke(result)
        deferredAck = null
    }

    // Query Purchases (Separated SUBS and INAPP)
    val subsPurchases = mutableListOf<Purchase>()
    val inAppPurchases = mutableListOf<Purchase>()
    var subsQueryResponse: Int = BillingClient.BillingResponseCode.OK
    var inAppQueryResponse: Int = BillingClient.BillingResponseCode.OK

    var queryResponse: Int
        get() = subsQueryResponse
        set(value) {
            subsQueryResponse = value
            inAppQueryResponse = value
        }

    var deferQuery: Boolean = false
    val deferredQueries = mutableListOf<() -> Unit>()

    /**
     * Backward-compatible routing list:
     * Lifetime purchases go to inAppPurchases, everything else to subsPurchases.
     */
    val purchasesToReturn: MutableList<Purchase> = object : ArrayList<Purchase>() {
        override fun add(element: Purchase): Boolean {
            val isLifetime = element.products.any { it.contains("lifetime", ignoreCase = true) } ||
                    element.skus.any { it.contains("lifetime", ignoreCase = true) }
            if (isLifetime) {
                inAppPurchases.add(element)
            } else {
                subsPurchases.add(element)
            }
            return super.add(element)
        }

        override fun clear() {
            subsPurchases.clear()
            inAppPurchases.clear()
            super.clear()
        }
    }

    fun addPurchase(purchase: Purchase) {
        purchasesToReturn.add(purchase)
    }

    private fun extractProductType(params: QueryPurchasesParams): String? {
        try {
            val method = params.javaClass.getMethod("getProductType")
            return method.invoke(params) as? String
        } catch (_: Throwable) {}
        try {
            val method = params.javaClass.getMethod("zza")
            return method.invoke(params) as? String
        } catch (_: Throwable) {}
        for (field in params.javaClass.declaredFields) {
            if (field.type == String::class.java) {
                try {
                    field.isAccessible = true
                    val value = field.get(params) as? String
                    if (value == BillingClient.ProductType.SUBS || value == BillingClient.ProductType.INAPP) {
                        return value
                    }
                } catch (_: Throwable) {}
            }
        }
        return null
    }

    override fun queryPurchasesAsync(
        params: QueryPurchasesParams,
        listener: (BillingResult, List<Purchase>) -> Unit
    ) {
        val productType = extractProductType(params)
        val isSubs = (productType == BillingClient.ProductType.SUBS)
        val responseCode = if (isSubs) subsQueryResponse else inAppQueryResponse
        val purchases = if (isSubs) subsPurchases.toList() else inAppPurchases.toList()
        val result = BillingResult.newBuilder().setResponseCode(responseCode).build()

        if (deferQuery) {
            deferredQueries.add { listener(result, purchases) }
        } else {
            listener(result, purchases)
        }
    }

    fun executeDeferredQueries() {
        val pending = ArrayList(deferredQueries)
        deferredQueries.clear()
        pending.forEach { it.invoke() }
    }
}
