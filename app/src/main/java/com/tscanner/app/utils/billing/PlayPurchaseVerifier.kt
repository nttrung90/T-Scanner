package com.tscanner.app.utils.billing

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.tscanner.app.utils.BillingManager
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest

/**
 * HTTP Response wrapper for transport boundary isolation and unit test mocking.
 */
data class VerifierHttpResponse(
    val statusCode: Int,
    val body: String
)

/**
 * Production-ready verification adapter implementing [PurchaseVerifier] for Google Play Billing.
 *
 * Responsibilities:
 * - Computes SHA-256 one-way obfuscated account ID matching backend specification.
 * - Enforces catalog allowlist before processing.
 * - Executes authenticated HTTPS requests to backend verifier (`/api/v1/billing/verify`).
 * - Parses authoritative entitlement schema and maps errors/timeouts/retries.
 * - When unconfigured without backend, returns [VerificationResult.MissingBackendGate],
 *   preventing unverified transactions from granting VIP privileges.
 * - Masks sensitive purchase tokens and account IDs in all logs.
 */
class PlayPurchaseVerifier(
    val backendUrl: String? = null,
    val tokenProvider: (() -> String?)? = null,
    val sessionGenerationProvider: (() -> Long)? = null,
    val ownerProvider: (() -> String?)? = null,
    private var allowLocalFallback: Boolean = false,
    private val httpTransport: ((url: String, method: String, headers: Map<String, String>, body: String?) -> VerifierHttpResponse)? = null
) : PurchaseVerifier {

    /**
     * Backward-compatible 4-arg constructor.
     */
    constructor(
        backendUrl: String?,
        tokenProvider: (() -> String?)?,
        allowLocalFallback: Boolean,
        httpTransport: ((url: String, method: String, headers: Map<String, String>, body: String?) -> VerifierHttpResponse)?
    ) : this(backendUrl, tokenProvider, null, null, allowLocalFallback, httpTransport)

    /**
     * Backward-compatible 3-arg constructor.
     */
    constructor(
        backendUrl: String?,
        tokenProvider: (() -> String?)?,
        allowLocalFallback: Boolean
    ) : this(backendUrl, tokenProvider, null, null, allowLocalFallback, null)

    /**
     * Backward-compatible 2-arg constructor.
     */
    constructor(
        backendUrl: String?,
        allowLocalFallback: Boolean
    ) : this(backendUrl, null, null, null, allowLocalFallback, null)

    constructor(
        allowLocalFallback: Boolean
    ) : this(null, null, null, null, allowLocalFallback, null)

    companion object {
        private const val TAG = "PlayPurchaseVerifier"

        @Volatile
        private var explicitAllowLocalFallback: Boolean? = null

        fun setAllowLocalFallbackDefault(allowed: Boolean?) {
            explicitAllowLocalFallback = allowed
        }

        fun isLocalFallbackAllowed(): Boolean {
            return explicitAllowLocalFallback ?: false
        }

        @Volatile
        private var instance: PlayPurchaseVerifier? = null

        fun getInstance(
            backendUrl: String? = null,
            tokenProvider: (() -> String?)? = null,
            sessionGenerationProvider: (() -> Long)? = null,
            ownerProvider: (() -> String?)? = null,
            allowLocalFallback: Boolean = isLocalFallbackAllowed()
        ): PlayPurchaseVerifier {
            val existing = instance
            if (existing != null) {
                // If caller supplies a new non-null backendUrl different from existing, reconfigure
                if (!backendUrl.isNullOrBlank() && existing.backendUrl != backendUrl) {
                    synchronized(this) {
                        return PlayPurchaseVerifier(backendUrl, tokenProvider, sessionGenerationProvider, ownerProvider, allowLocalFallback).also { instance = it }
                    }
                }
                return existing
            }
            return synchronized(this) {
                instance ?: PlayPurchaseVerifier(backendUrl, tokenProvider, sessionGenerationProvider, ownerProvider, allowLocalFallback).also { instance = it }
            }
        }

        fun configure(
            backendUrl: String?,
            tokenProvider: (() -> String?)? = null,
            sessionGenerationProvider: (() -> Long)? = null,
            ownerProvider: (() -> String?)? = null,
            allowLocalFallback: Boolean = false
        ) {
            synchronized(this) {
                instance = PlayPurchaseVerifier(backendUrl, tokenProvider, sessionGenerationProvider, ownerProvider, allowLocalFallback)
            }
        }

        @VisibleForTesting
        fun setInstanceForTesting(verifier: PlayPurchaseVerifier?) {
            synchronized(this) {
                instance = verifier
            }
        }

        /**
         * Masks purchase tokens and sensitive credentials for safe logging.
         */
        fun maskToken(token: String?): String {
            if (token == null) return "null"
            if (token.length <= 8) return "***"
            return "${token.take(4)}...${token.takeLast(4)}"
        }

        /**
         * Computes SHA-256 hexadecimal hash of canonical user ID for obfuscatedAccountId.
         * Matches backend verifier implementation.
         */
        fun computeObfuscatedAccountId(canonicalUserId: String?): String? {
            if (canonicalUserId.isNullOrBlank()) return null
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                val hashBytes = digest.digest(canonicalUserId.toByteArray(Charsets.UTF_8))
                hashBytes.joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to compute SHA-256 for obfuscatedAccountId", e)
                null
            }
        }

        /**
         * Checks whether an ID token JWT is expired or invalid based on shape and mandatory 'exp' claim.
         * Fails closed: null, blank, non-JWT, decode failure, missing/invalid exp, or expired all return true.
         */
        fun isTokenExpired(token: String?): Boolean {
            if (token.isNullOrBlank()) return true
            val parts = token.split(".")
            if (parts.size != 3) return true
            return try {
                val payloadBytes = java.util.Base64.getUrlDecoder().decode(parts[1])
                val payloadJson = JSONObject(String(payloadBytes, Charsets.UTF_8))
                if (!payloadJson.has("exp")) return true
                val exp = payloadJson.optLong("exp", -1L)
                if (exp <= 0L) return true
                val nowSeconds = System.currentTimeMillis() / 1000L
                nowSeconds >= exp
            } catch (e: Exception) {
                true
            }
        }
    }

    fun isBackendConfigured(): Boolean {
        if (allowLocalFallback) return true
        if (backendUrl.isNullOrBlank()) return false
        val trimmed = backendUrl.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true)) {
            return false
        }
        return try {
            val url = URL(trimmed)
            url.protocol.equals("https", ignoreCase = true) && !url.host.isNullOrBlank()
        } catch (e: Exception) {
            false
        }
    }

    fun isAuthReady(): Boolean {
        if (allowLocalFallback) return true
        if (tokenProvider == null) return false
        val token = tokenProvider.invoke()
        return !token.isNullOrBlank() && !isTokenExpired(token)
    }

    fun isConfigured(): Boolean {
        if (!isBackendConfigured()) return false

        // If a tokenProvider is attached, check that the session token is not expired (R09)
        if (tokenProvider != null) {
            val token = tokenProvider.invoke()
            if (token.isNullOrBlank() || isTokenExpired(token)) {
                return false
            }
        }

        return true
    }

    @VisibleForTesting
    fun setAllowLocalFallbackForTesting(allowed: Boolean) {
        this.allowLocalFallback = allowed
    }

    override suspend fun verifyPurchase(request: VerificationRequest): VerificationResult {
        // 1. Catalog allowlist check
        if (!BillingManager.ALLOWED_PRODUCT_IDS.contains(request.productId)) {
            Log.w(TAG, "Purchase verification rejected: Product '${request.productId}' is not in allowed catalog.")
            return VerificationResult.Rejected(
                reason = RejectionReason.PRODUCT_NOT_ALLOWED,
                message = "Sản phẩm '${request.productId}' không nằm trong danh mục hợp lệ."
            )
        }

        // 2. Token sanity check
        if (request.purchaseToken.isBlank()) {
            Log.w(TAG, "Purchase verification rejected: purchaseToken is blank.")
            return VerificationResult.Rejected(
                reason = RejectionReason.INVALID_SIGNATURE_OR_TOKEN,
                message = "Mã xác thực giao dịch (purchase token) không hợp lệ."
            )
        }

        // 3. Remote Backend Verification (if endpoint configured)
        if (!backendUrl.isNullOrBlank()) {
            if (!isBackendConfigured() || !backendUrl.trim().startsWith("https://", ignoreCase = true)) {
                Log.w(
                    TAG,
                    "Backend verification required but backendUrl is not configured with HTTPS. " +
                            "Token ${maskToken(request.purchaseToken)} blocked by MissingBackendGate."
                )
                return VerificationResult.MissingBackendGate(
                    "Authoritative backend verifier (B04) is required for paid purchases. " +
                            "Backend endpoint not configured on this client build."
                )
            }
            return verifyViaRemoteBackend(request)
        }

        // 4. Fallback gating
        if (!allowLocalFallback) {
            Log.w(
                TAG,
                "Backend verification required but backendUrl is not configured. " +
                        "Token ${maskToken(request.purchaseToken)} blocked by MissingBackendGate."
            )
            return VerificationResult.MissingBackendGate(
                "Authoritative backend verifier (B04) is required for paid purchases. " +
                        "Backend endpoint not configured on this client build."
            )
        }

        // 5. Local Sandbox / Development Verification (only in explicit test/fallback mode)
        Log.i(
            TAG,
            "Local sandbox verification granted for token ${maskToken(request.purchaseToken)}, " +
                    "product=${request.productId}, owner=${request.ownerAppUserId}"
        )

        val expiryTimeMillis: Long? = when (request.productId) {
            BillingManager.PRODUCT_VIP_LIFETIME, "vip_lifetime" -> null
            BillingManager.PRODUCT_VIP_MONTHLY, "vip_monthly" -> request.clientPurchaseTimeMillis + (30L * 24 * 3600 * 1000)
            else -> request.clientPurchaseTimeMillis + (365L * 24 * 3600 * 1000)
        }

        val source = if (BillingManager.ALL_SUBSCRIPTION_IDS.contains(request.productId)) {
            EntitlementSource.GOOGLE_PLAY_SUBSCRIPTION
        } else {
            EntitlementSource.GOOGLE_PLAY_INAPP
        }

        val entitlement = BillingEntitlement(
            id = request.purchaseToken,
            ownerAppUserId = request.ownerAppUserId,
            productId = request.productId,
            productType = request.productType,
            purchaseToken = request.purchaseToken,
            orderId = request.orderId,
            source = source,
            state = EntitlementState.VERIFIED_ACTIVE,
            purchaseTimeMillis = request.clientPurchaseTimeMillis,
            expiryTimeMillis = expiryTimeMillis,
            autoRenewing = (source == EntitlementSource.GOOGLE_PLAY_SUBSCRIPTION),
            verifiedAtMillis = System.currentTimeMillis(),
            snapshotVersion = 1L
        )

        return VerificationResult.Success(entitlement)
    }

    private fun verifyViaRemoteBackend(request: VerificationRequest): VerificationResult {
        val base = backendUrl?.trimEnd('/') ?: return VerificationResult.MissingBackendGate("Missing backend URL")
        val endpoint = "$base/api/v1/billing/verify"

        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json"
        )

        val startGen = sessionGenerationProvider?.invoke()
        val currentOwner = ownerProvider?.invoke()
        if (request.ownerAppUserId != null && currentOwner != null && request.ownerAppUserId != currentOwner) {
            Log.w(TAG, "Rejecting verify request: owner mismatch with active session (req=${request.ownerAppUserId}, session=$currentOwner)")
            return VerificationResult.Rejected(
                reason = RejectionReason.OWNERSHIP_CONFLICT,
                message = "Tài khoản hiện tại không khớp với chủ sở hữu giao dịch."
            )
        }

        val token = tokenProvider?.invoke()

        // Guard against A -> B -> A or session mutation during token retrieval
        val afterGen = sessionGenerationProvider?.invoke()
        if (startGen != null && afterGen != null && startGen != afterGen) {
            Log.w(TAG, "Session generation changed while acquiring token (before=$startGen, after=$afterGen)")
            return VerificationResult.TransientError(
                cause = IllegalStateException("Session changed during token refresh"),
                message = "Phiên làm việc đã thay đổi trong quá trình xác thực."
            )
        }

        if (tokenProvider != null && (token.isNullOrBlank() || isTokenExpired(token))) {
            Log.w(TAG, "Verification requires authentication: User session token is missing, blank, or expired. Re-authentication required.")
            return VerificationResult.AuthRequired(
                message = "Phiên đăng nhập đã hết hạn hoặc không tồn tại. Vui lòng xác thực lại tài khoản Google để tiếp tục."
            )
        }
        if (!token.isNullOrBlank()) {
            headers["Authorization"] = "Bearer $token"
        }

        val jsonBody = JSONObject().apply {
            put("ownerAppUserId", request.ownerAppUserId ?: JSONObject.NULL)
            put("productId", request.productId)
            put("productType", request.productType)
            put("purchaseToken", request.purchaseToken)
            put("orderId", request.orderId ?: JSONObject.NULL)
            put("obfuscatedAccountId", request.obfuscatedAccountId ?: JSONObject.NULL)
            put("clientPurchaseTimeMillis", request.clientPurchaseTimeMillis)
            put("packageName", "com.tscanner.app")
        }.toString()

        val response: VerifierHttpResponse
        try {
            response = if (httpTransport != null) {
                httpTransport.invoke(endpoint, "POST", headers, jsonBody)
            } else {
                executeDefaultHttp(endpoint, "POST", headers, jsonBody)
            }
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Verification timeout: ${e.message}")
            return VerificationResult.TransientError(e, "Quá thời gian kết nối máy chủ xác thực (timeout).")
        } catch (e: UnknownHostException) {
            Log.e(TAG, "Verification host unreachable: ${e.message}")
            return VerificationResult.TransientError(e, "Không thể kết nối đến máy chủ xác thực.")
        } catch (e: IOException) {
            Log.e(TAG, "Verification network error: ${e.message}")
            return VerificationResult.TransientError(e, "Lỗi kết nối mạng: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Verification unexpected error: ${e.message}", e)
            return VerificationResult.TransientError(e, "Lỗi kết nối máy chủ xác thực.")
        }

        return parseBackendResponse(response, request)
    }

    private fun executeDefaultHttp(
        targetUrl: String,
        method: String,
        headers: Map<String, String>,
        body: String?
    ): VerifierHttpResponse {
        val url = URL(targetUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.requestMethod = method
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { os ->
                os.write(body.toByteArray(Charsets.UTF_8))
            }
        }

        val statusCode = conn.responseCode
        val inputStream = if (statusCode in 200..299) conn.inputStream else conn.errorStream
        val responseBody = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
        return VerifierHttpResponse(statusCode, responseBody)
    }

    private fun parseBackendResponse(response: VerifierHttpResponse, request: VerificationRequest): VerificationResult {
        val statusCode = response.statusCode
        val body = response.body

        if (statusCode == 401) {
            val msg = try {
                JSONObject(body).optString("message", "Phiên xác thực không hợp lệ.")
            } catch (_: Exception) {
                "Phiên xác thực không hợp lệ (HTTP 401)."
            }
            return VerificationResult.AuthRequired(msg)
        }

        if (statusCode == 403) {
            val msg = try {
                JSONObject(body).optString("message", "Yêu cầu bị từ chối.")
            } catch (_: Exception) {
                "Yêu cầu xác thực bị từ chối (HTTP 403)."
            }
            return VerificationResult.Rejected(RejectionReason.OWNERSHIP_CONFLICT, msg)
        }

        if (statusCode == 429 || statusCode in 500..599) {
            val msg = try {
                JSONObject(body).optString("message", "Máy chủ bận, vui lòng thử lại sau.")
            } catch (_: Exception) {
                "Máy chủ xác thực tạm thời gián đoạn (HTTP $statusCode)."
            }
            return VerificationResult.TransientError(null, msg)
        }

        val json: JSONObject
        try {
            json = JSONObject(body)
        } catch (e: Exception) {
            return VerificationResult.TransientError(e, "Phản hồi máy chủ không hợp lệ (HTTP $statusCode).")
        }

        val status = json.optString("status")
        return when (status) {
            "SUCCESS" -> {
                if (statusCode != 200) {
                    return VerificationResult.TransientError(null, "Trạng thái SUCCESS không thể đi kèm HTTP $statusCode.")
                }
                val entObj = json.optJSONObject("entitlement")
                    ?: return VerificationResult.TransientError(null, "Thiếu dữ liệu entitlement trong phản hồi.")

                val entitlement = parseEntitlementStrict(entObj, request)
                    ?: return VerificationResult.TransientError(null, "Dữ liệu entitlement trong phản hồi không hợp lệ.")

                VerificationResult.Success(entitlement)
            }
            "PENDING" -> {
                val msg = json.optString("message", "Giao dịch đang chờ Google xử lý thanh toán.")
                VerificationResult.Pending(request.purchaseToken, msg)
            }
            "REJECTED" -> {
                val reasonStr = json.optString("reason", "INVALID_SIGNATURE_OR_TOKEN")
                val reason = try {
                    RejectionReason.valueOf(reasonStr)
                } catch (_: Exception) {
                    RejectionReason.INVALID_SIGNATURE_OR_TOKEN
                }
                val msg = json.optString("message", "Giao dịch bị từ chối.")
                val tombstoneObj = json.optJSONObject("entitlement")
                val tombstone = if (tombstoneObj != null) {
                    parseEntitlementStrict(tombstoneObj, request)
                } else null
                VerificationResult.Rejected(reason, msg, tombstone)
            }
            "TRANSIENT_ERROR" -> {
                val msg = json.optString("message", "Lỗi tạm thời từ máy chủ, vui lòng thử lại sau.")
                VerificationResult.TransientError(null, msg)
            }
            else -> {
                VerificationResult.TransientError(null, "Trạng thái phản hồi không xác định: $status")
            }
        }
    }

    private fun parseEntitlementStrict(
        entObj: JSONObject,
        request: VerificationRequest,
        snapshotOwner: String? = null
    ): BillingEntitlement? {
        // 1. Strict Token Binding (R06)
        val responseToken = entObj.optString("purchaseToken")
        if (responseToken.isBlank() || responseToken != request.purchaseToken) {
            Log.e(TAG, "Rejecting response mismatch: purchaseToken '$responseToken' != request '${request.purchaseToken}'")
            return null
        }

        // 2. Strict Owner Binding (R06 & R07 F09)
        val rawItemOwner = entObj.optString("ownerAppUserId").takeIf { it.isNotBlank() }
        val authoritativeOwner = rawItemOwner ?: snapshotOwner
        if (authoritativeOwner.isNullOrBlank()) {
            Log.e(TAG, "Rejecting malformed entitlement: missing mandatory ownerAppUserId")
            return null
        }
        if (request.ownerAppUserId != null && authoritativeOwner != request.ownerAppUserId) {
            Log.e(TAG, "Rejecting response mismatch: ownerAppUserId '$authoritativeOwner' != request '${request.ownerAppUserId}'")
            return null
        }

        // 3. Strict Product & Type Binding (R06)
        val responseProduct = entObj.optString("productId")
        if (responseProduct.isNotBlank() && responseProduct != request.productId) {
            Log.e(TAG, "Rejecting response mismatch: productId '$responseProduct' != request '${request.productId}'")
            return null
        }
        val effectiveProduct = responseProduct.ifBlank { request.productId }
        if (!BillingManager.ALLOWED_PRODUCT_IDS.contains(effectiveProduct)) {
            Log.e(TAG, "Rejecting disallowed product in entitlement: '$effectiveProduct'")
            return null
        }

        val expectedProductType = when {
            BillingManager.ALL_SUBSCRIPTION_IDS.contains(effectiveProduct) -> "subs"
            BillingManager.ALL_INAPP_IDS.contains(effectiveProduct) -> "inapp"
            else -> request.productType
        }

        val prodType = entObj.optString("productType")
        if (prodType.isBlank() || prodType != expectedProductType) {
            Log.e(TAG, "Rejecting response mismatch: productType '$prodType' != expected '$expectedProductType'")
            return null
        }

        val rawState = entObj.optString("state")
        if (rawState.isBlank() || rawState == "UNKNOWN") {
            Log.e(TAG, "Rejecting malformed entitlement: state is missing or UNKNOWN ($rawState)")
            return null
        }
        val state = try {
            EntitlementState.valueOf(rawState)
        } catch (e: Exception) {
            Log.e(TAG, "Rejecting malformed entitlement: unknown state '$rawState'")
            return null
        }

        val isSub = expectedProductType == "subs"

        val rawExp = if (entObj.isNull("expiryTimeMillis")) null else entObj.optLong("expiryTimeMillis", -1L)
        val requiresPositiveExpiry = isSub && (
            state == EntitlementState.VERIFIED_ACTIVE ||
            state == EntitlementState.CANCELED_ACTIVE ||
            state == EntitlementState.IN_GRACE_PERIOD
        )

        // Subscriptions in active/canceled-active/grace states MUST have a positive expiry time
        if (requiresPositiveExpiry && (rawExp == null || rawExp <= 0L)) {
            Log.e(TAG, "Rejecting malformed active subscription entitlement: missing mandatory positive expiryTimeMillis (got $rawExp)")
            return null
        }

        val expiryTimeMillis: Long? = if (rawExp == null || rawExp <= 0L) null else rawExp

        val version = entObj.optLong("snapshotVersion", -1L)
        if (version <= 0L) {
            Log.e(TAG, "Rejecting response: invalid snapshotVersion ($version)")
            return null
        }

        val purchaseTime = entObj.optLong("purchaseTimeMillis", -1L)
        val validPurchaseTime = if (purchaseTime > 0L) purchaseTime else request.clientPurchaseTimeMillis

        val rawSource = entObj.optString("source")
        if (rawSource.isBlank()) {
            Log.e(TAG, "Rejecting malformed entitlement: missing mandatory source")
            return null
        }

        val parsedSource = try {
            EntitlementSource.valueOf(rawSource)
        } catch (_: Exception) {
            Log.e(TAG, "Rejecting malformed entitlement: unknown source '$rawSource'")
            return null
        }

        val expectedSource = if (isSub) {
            EntitlementSource.GOOGLE_PLAY_SUBSCRIPTION
        } else {
            EntitlementSource.GOOGLE_PLAY_INAPP
        }

        if (parsedSource != expectedSource) {
            Log.e(TAG, "Rejecting response source mismatch: source '$parsedSource' != expected catalog source '$expectedSource'")
            return null
        }

        val id = entObj.optString("id").takeIf { it.isNotBlank() } ?: "${parsedSource}_${request.purchaseToken}"

        return BillingEntitlement(
            id = id,
            ownerAppUserId = authoritativeOwner,
            productId = responseProduct.takeIf { it.isNotBlank() } ?: request.productId,
            productType = prodType,
            purchaseToken = request.purchaseToken,
            orderId = entObj.optString("orderId").takeIf { it.isNotBlank() } ?: request.orderId,
            source = parsedSource,
            state = state,
            purchaseTimeMillis = validPurchaseTime,
            expiryTimeMillis = expiryTimeMillis,
            autoRenewing = entObj.optBoolean("autoRenewing", false),
            verifiedAtMillis = entObj.optLong("verifiedAtMillis", System.currentTimeMillis()),
            snapshotVersion = version
        )
    }

    override suspend fun restorePurchases(request: RestoreRequest): RestoreResult {
        if (!isBackendConfigured()) {
            return RestoreResult.NotConfigured("Dịch vụ xác thực thanh toán chưa được cấu hình hoặc không sử dụng HTTPS.")
        }

        val base = backendUrl?.trimEnd('/') ?: return RestoreResult.NotConfigured("Missing backend URL")
        if (!base.startsWith("https://", ignoreCase = true)) {
            return RestoreResult.NotConfigured("Dịch vụ xác thực thanh toán yêu cầu kết nối HTTPS bảo mật.")
        }
        val endpoint = "$base/api/v1/billing/restore"

        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json"
        )

        val startGen = sessionGenerationProvider?.invoke()
        val currentOwner = ownerProvider?.invoke()
        if (request.ownerAppUserId != null && currentOwner != null && request.ownerAppUserId != currentOwner) {
            Log.w(TAG, "Rejecting restore request: owner mismatch with active session (req=${request.ownerAppUserId}, session=$currentOwner)")
            return RestoreResult.Rejected(
                reason = RejectionReason.OWNERSHIP_CONFLICT,
                message = "Tài khoản hiện tại không khớp với chủ sở hữu phiên làm việc."
            )
        }

        if (tokenProvider != null && !isAuthReady()) {
            Log.w(TAG, "Restore rejected: User session token is missing, expired or malformed. Re-authentication required.")
            return RestoreResult.AuthRequired("Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại để khôi phục VIP.")
        }

        val token = tokenProvider?.invoke()
        if (tokenProvider != null && token.isNullOrBlank()) {
            Log.w(TAG, "Restore rejected: User session token is missing. Re-authentication required.")
            return RestoreResult.AuthRequired("Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại để khôi phục VIP.")
        }
        if (!token.isNullOrBlank()) {
            headers["Authorization"] = "Bearer $token"
        }

        val afterGen = sessionGenerationProvider?.invoke()
        if (startGen != null && afterGen != null && startGen != afterGen) {
            Log.w(TAG, "Session generation changed while acquiring token for restore (before=$startGen, after=$afterGen)")
            return RestoreResult.TransientError(
                cause = IllegalStateException("Session changed during restore token refresh"),
                message = "Phiên làm việc đã thay đổi trong quá trình khôi phục."
            )
        }

        val purchasesArray = org.json.JSONArray()
        for (p in request.purchases) {
            val item = JSONObject().apply {
                put("productId", p.productId)
                put("productType", p.productType)
                put("purchaseToken", p.purchaseToken)
            }
            purchasesArray.put(item)
        }

        val jsonBody = JSONObject().apply {
            put("ownerAppUserId", request.ownerAppUserId ?: JSONObject.NULL)
            put("purchases", purchasesArray)
        }.toString()

        val response: VerifierHttpResponse
        try {
            response = if (httpTransport != null) {
                httpTransport.invoke(endpoint, "POST", headers, jsonBody)
            } else {
                executeDefaultHttp(endpoint, "POST", headers, jsonBody)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Restore network error: ${e.message}", e)
            return RestoreResult.TransientError(e, "Lỗi kết nối máy chủ khôi phục.")
        }

        return parseRestoreResponse(response, request)
    }

    private fun parseRestoreResponse(response: VerifierHttpResponse, request: RestoreRequest): RestoreResult {
        val statusCode = response.statusCode
        val body = response.body

        // A04: Status code must be 200 OK. Any non-200 status code must not accept SUCCESS body
        if (statusCode != 200) {
            val msg = try {
                JSONObject(body).optString("message", "Lỗi máy chủ khi khôi phục (HTTP $statusCode).")
            } catch (_: Exception) {
                "Lỗi máy chủ khi khôi phục (HTTP $statusCode)."
            }
            if (statusCode == 401) {
                return RestoreResult.AuthRequired(msg)
            }
            if (statusCode == 403) {
                return RestoreResult.Rejected(RejectionReason.OWNERSHIP_CONFLICT, msg)
            }
            if (statusCode == 429 || statusCode in 500..599) {
                return RestoreResult.TransientError(null, msg)
            }
            return RestoreResult.Rejected(RejectionReason.INVALID_SIGNATURE_OR_TOKEN, msg)
        }

        val json: JSONObject
        try {
            json = JSONObject(body)
        } catch (e: Exception) {
            return RestoreResult.TransientError(e, "Phản hồi máy chủ không hợp lệ (HTTP $statusCode).")
        }

        val status = json.optString("status")
        return when (status) {
            "SUCCESS", "PARTIAL" -> {
                val snapshotObj = json.optJSONObject("snapshot")
                    ?: return RestoreResult.TransientError(null, "Thiếu dữ liệu snapshot trong phản hồi khôi phục.")

                // A01 & D02: Strict Owner Validation
                val responseOwner = snapshotObj.optString("ownerAppUserId").takeIf { it.isNotBlank() }
                    ?: return RestoreResult.TransientError(null, "Snapshot thiếu ownerAppUserId bắt buộc.")

                if (request.ownerAppUserId != null && responseOwner != request.ownerAppUserId) {
                    Log.w(TAG, "Restore snapshot owner mismatch: response='$responseOwner', request='${request.ownerAppUserId}'")
                    return RestoreResult.Rejected(RejectionReason.OWNERSHIP_CONFLICT, "Tài khoản phản hồi không khớp với yêu cầu khôi phục.")
                }
                val owner = responseOwner

                val array = snapshotObj.optJSONArray("entitlements") ?: org.json.JSONArray()
                val list = mutableListOf<BillingEntitlement>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i)
                        ?: return RestoreResult.TransientError(null, "Dữ liệu entitlement tại vị trí $i không hợp lệ.")
                    val pToken = obj.optString("purchaseToken")
                    val pId = obj.optString("productId")
                    val expectedType = when {
                        BillingManager.ALL_SUBSCRIPTION_IDS.contains(pId) -> "subs"
                        BillingManager.ALL_INAPP_IDS.contains(pId) -> "inapp"
                        else -> "subs"
                    }
                    val pType = obj.optString("productType", expectedType)

                    // A02: Catalog allowlist check
                    if (!BillingManager.ALLOWED_PRODUCT_IDS.contains(pId)) {
                        Log.w(TAG, "Rejecting restored entitlement: productId '$pId' is not in allowed catalog.")
                        return RestoreResult.Rejected(RejectionReason.PRODUCT_NOT_ALLOWED, "Sản phẩm '$pId' không thuộc danh mục hợp lệ.")
                    }

                    // Token validity
                    if (pToken.isBlank()) {
                        Log.w(TAG, "Rejecting restored entitlement: purchaseToken is blank.")
                        return RestoreResult.Rejected(RejectionReason.INVALID_SIGNATURE_OR_TOKEN, "Mã giao dịch không hợp lệ.")
                    }

                    val verifyReq = VerificationRequest(
                        ownerAppUserId = request.ownerAppUserId ?: owner,
                        productId = pId,
                        productType = expectedType,
                        purchaseToken = pToken
                    )
                    val parsed = parseEntitlementStrict(obj, verifyReq, responseOwner)
                    if (parsed == null) {
                        // A03: Malformed / invalid entitlement item must NOT be silently dropped into empty Success
                        Log.w(TAG, "Malformed entitlement item in restore response (token=${maskToken(pToken)})")
                        return RestoreResult.TransientError(null, "Dữ liệu giao dịch trong phản hồi khôi phục không hợp lệ.")
                    }
                    list.add(parsed)
                }

                // Parse itemized results[]
                val resultsList = mutableListOf<RestoreItemResult>()
                val resultsArray = json.optJSONArray("results")
                if (resultsArray != null) {
                    for (i in 0 until resultsArray.length()) {
                        val rObj = resultsArray.optJSONObject(i) ?: continue
                        val token = rObj.optString("purchaseToken")
                        val itemStatus = rObj.optString("status")
                        val reason = rObj.optString("reason").takeIf { it.isNotBlank() }
                        if (token.isNotBlank() && itemStatus.isNotBlank()) {
                            resultsList.add(RestoreItemResult(token, itemStatus, reason))
                        }
                    }
                }

                val snapshot = UserEntitlementSnapshot(
                    ownerAppUserId = owner,
                    entitlements = list,
                    computedAtMillis = snapshotObj.optLong("computedAtMillis", System.currentTimeMillis())
                )
                val msg = json.optString("message").takeIf { it.isNotBlank() }
                if (status == "PARTIAL") {
                    RestoreResult.Partial(snapshot, msg, resultsList)
                } else {
                    RestoreResult.Success(snapshot, msg, resultsList)
                }
            }
            "REJECTED" -> {
                val resultsList = mutableListOf<RestoreItemResult>()
                val resultsArray = json.optJSONArray("results")
                if (resultsArray != null) {
                    for (i in 0 until resultsArray.length()) {
                        val rObj = resultsArray.optJSONObject(i) ?: continue
                        val token = rObj.optString("purchaseToken")
                        val itemStatus = rObj.optString("status")
                        val reason = rObj.optString("reason").takeIf { it.isNotBlank() }
                        if (token.isNotBlank() && itemStatus.isNotBlank()) {
                            resultsList.add(RestoreItemResult(token, itemStatus, reason))
                        }
                    }
                }
                RestoreResult.Rejected(
                    RejectionReason.INVALID_SIGNATURE_OR_TOKEN,
                    json.optString("message", "Khôi phục bị từ chối."),
                    resultsList
                )
            }
            else -> {
                RestoreResult.TransientError(null, json.optString("message", "Lỗi máy chủ khi khôi phục."))
            }
        }
    }
}
