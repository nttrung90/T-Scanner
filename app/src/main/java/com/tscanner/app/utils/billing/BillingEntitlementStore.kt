package com.tscanner.app.utils.billing

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Typed result of applying an entitlement snapshot to storage (Q03 / R05 / R06).
 */
sealed class ApplySnapshotResult {
    data class Success(val snapshot: UserEntitlementSnapshot) : ApplySnapshotResult()
    data class Conflict(val reason: String) : ApplySnapshotResult()
    data class PersistenceFailed(val message: String) : ApplySnapshotResult()
}

/**
 * Thread-safe persistent store for User Entitlements.
 *
 * Responsibilities (B05):
 * - Persists entitlements partitioned by user account ID (`ownerAppUserId`).
 * - Enforces monotonic snapshot versioning: older versions cannot overwrite newer versions.
 * - Idempotent replay: Replaying identical or older snapshots never extends or alters absolute expiration timestamps.
 * - Safe migration: Legacy local VIP flags are preserved as UNVERIFIED_CLIENT without blind deletion.
 */
class BillingEntitlementStore private constructor() {

    companion object {
        private const val TAG = "BillingEntitlementStore"
        private const val PREFS_NAME = "tscanner_billing_entitlements"
        private const val KEY_USER_ENTITLEMENTS_PREFIX = "user_entitlements_"
        private const val KEY_GUEST_ENTITLEMENTS = "guest_entitlements"

        @Volatile
        private var instance: BillingEntitlementStore? = null

        fun getInstance(): BillingEntitlementStore {
            return instance ?: synchronized(this) {
                instance ?: BillingEntitlementStore().also { instance = it }
            }
        }

        @VisibleForTesting
        fun resetInstanceForTesting() {
            synchronized(this) {
                instance = null
            }
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getKeyForUser(ownerAppUserId: String?): String {
        return if (ownerAppUserId.isNullOrBlank()) {
            KEY_GUEST_ENTITLEMENTS
        } else {
            "${KEY_USER_ENTITLEMENTS_PREFIX}${ownerAppUserId}"
        }
    }

    /**
     * Reads all stored entitlements for [ownerAppUserId] as an immutable [UserEntitlementSnapshot].
     */
    @Synchronized
    fun getSnapshot(context: Context, ownerAppUserId: String?): UserEntitlementSnapshot {
        val prefs = getPrefs(context)
        val key = getKeyForUser(ownerAppUserId)
        val rawJson = prefs.getString(key, null) ?: return UserEntitlementSnapshot(ownerAppUserId = ownerAppUserId)

        try {
            val jsonArray = JSONArray(rawJson)
            val list = mutableListOf<BillingEntitlement>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val entitlement = deserializeEntitlement(obj)
                list.add(entitlement)
            }
            return UserEntitlementSnapshot(
                ownerAppUserId = ownerAppUserId,
                entitlements = list
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize entitlements for user '$ownerAppUserId'", e)
            return UserEntitlementSnapshot(ownerAppUserId = ownerAppUserId)
        }
    }

    /**
     * Applies an incoming [UserEntitlementSnapshot] to persistent storage with typed result.
     * Enforces monotonic versioning and absolute timestamp idempotency.
     */
    @Synchronized
    fun applySnapshotTyped(context: Context, incoming: UserEntitlementSnapshot): ApplySnapshotResult {
        val existing = getSnapshot(context, incoming.ownerAppUserId)
        val merged = try {
            existing.mergeNewerSnapshot(incoming)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Cannot apply snapshot due to conflict: ${e.message}")
            return ApplySnapshotResult.Conflict(e.message ?: "Snapshot conflict")
        }

        return try {
            val committed = persistSnapshot(context, merged)
            if (committed) {
                ApplySnapshotResult.Success(merged)
            } else {
                Log.e(TAG, "Failed to commit snapshot to SharedPreferences for user '${incoming.ownerAppUserId}'")
                ApplySnapshotResult.PersistenceFailed("Failed to commit SharedPreferences")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Exception during persistSnapshot", t)
            ApplySnapshotResult.PersistenceFailed(t.message ?: "Unknown persistence exception")
        }
    }

    /**
     * Applies an incoming [UserEntitlementSnapshot] to persistent storage.
     * Enforces monotonic versioning and absolute timestamp idempotency.
     *
     * @return true if storage was successfully committed, false otherwise.
     */
    @Synchronized
    fun applySnapshot(context: Context, incoming: UserEntitlementSnapshot): Boolean {
        return applySnapshotTyped(context, incoming) is ApplySnapshotResult.Success
    }

    /**
     * Applies a single [BillingEntitlement] to persistent storage.
     */
    @Synchronized
    fun applyEntitlement(context: Context, entitlement: BillingEntitlement): Boolean {
        val snapshot = UserEntitlementSnapshot(
            ownerAppUserId = entitlement.ownerAppUserId,
            entitlements = listOf(entitlement)
        )
        return applySnapshot(context, snapshot)
    }

    /**
     * Binds all guest entitlements to a signed-in user account.
     * Idempotent: once bound, guest list is cleared safely.
     * Enforces (R02):
     * - Does not increment server snapshotVersion during local bind.
     * - Rejects binding tokens already bound to another registered user.
     * - Legacy local entitlements remain UNVERIFIED_CLIENT (unverified candidate, not paid).
     */
    @Synchronized
    fun bindGuestEntitlementsToUser(context: Context, canonicalUserId: String): Boolean {
        if (canonicalUserId.isBlank()) return false
        val guestSnapshot = getSnapshot(context, null)
        if (guestSnapshot.entitlements.isEmpty()) return true

        val boundEntitlements = mutableListOf<BillingEntitlement>()
        for (item in guestSnapshot.entitlements) {
            // Cannot claim a token already owned by another registered user
            if (isTokenBoundToOtherUser(context, item.purchaseToken, canonicalUserId)) {
                Log.w(TAG, "Cannot bind guest token: token already owned by another user")
                continue
            }
            // Legacy local must stay UNVERIFIED_CLIENT (unverified candidate, not paid)
            val stateToBind = if (item.source == EntitlementSource.LEGACY_LOCAL) {
                EntitlementState.UNVERIFIED_CLIENT
            } else {
                item.state
            }
            boundEntitlements.add(
                item.copy(
                    ownerAppUserId = canonicalUserId,
                    state = stateToBind,
                    snapshotVersion = item.snapshotVersion // Không tăng snapshotVersion server khi bind cục bộ
                )
            )
        }

        if (boundEntitlements.isEmpty()) {
            // All guest entitlements were invalid or owned by other users
            getPrefs(context).edit().remove(KEY_GUEST_ENTITLEMENTS).apply()
            return true
        }

        val userSnapshot = UserEntitlementSnapshot(
            ownerAppUserId = canonicalUserId,
            entitlements = boundEntitlements
        )

        val applied = applySnapshot(context, userSnapshot)
        if (applied) {
            // Clear guest record
            getPrefs(context).edit().remove(KEY_GUEST_ENTITLEMENTS).apply()
            Log.i(TAG, "Successfully bound ${boundEntitlements.size} guest entitlements to user '$canonicalUserId'")
        }
        return applied
    }

    /**
     * Checks if a purchaseToken is already bound to any registered user other than [excludeUserId].
     */
    @Synchronized
    fun isTokenBoundToOtherUser(context: Context, purchaseToken: String, excludeUserId: String? = null): Boolean {
        if (purchaseToken.isBlank()) return false
        val prefs = getPrefs(context)
        val allEntries = prefs.all
        for ((key, value) in allEntries) {
            if (key.startsWith(KEY_USER_ENTITLEMENTS_PREFIX) && value is String) {
                val userId = key.removePrefix(KEY_USER_ENTITLEMENTS_PREFIX)
                if (excludeUserId != null && userId == excludeUserId) continue
                try {
                    val array = JSONArray(value)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        if (obj.optString("purchaseToken") == purchaseToken) {
                            return true
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking token ownership for user $userId", e)
                }
            }
        }
        return false
    }

    /**
     * Clears all entitlements for testing isolation.
     */
    @VisibleForTesting
    @Synchronized
    fun clearForTesting(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    private fun persistSnapshot(context: Context, snapshot: UserEntitlementSnapshot): Boolean {
        val key = getKeyForUser(snapshot.ownerAppUserId)
        val jsonArray = JSONArray()
        for (entitlement in snapshot.entitlements) {
            jsonArray.put(serializeEntitlement(entitlement))
        }

        val editor = getPrefs(context).edit()
        editor.putString(key, jsonArray.toString())
        return editor.commit()
    }

    private fun serializeEntitlement(e: BillingEntitlement): JSONObject {
        return JSONObject().apply {
            put("id", e.id)
            put("ownerAppUserId", e.ownerAppUserId ?: JSONObject.NULL)
            put("productId", e.productId)
            put("productType", e.productType)
            put("purchaseToken", e.purchaseToken)
            put("orderId", e.orderId ?: JSONObject.NULL)
            put("source", e.source.name)
            put("state", e.state.name)
            put("purchaseTimeMillis", e.purchaseTimeMillis)
            put("expiryTimeMillis", e.expiryTimeMillis ?: JSONObject.NULL)
            put("autoRenewing", e.autoRenewing)
            put("verifiedAtMillis", e.verifiedAtMillis ?: JSONObject.NULL)
            put("snapshotVersion", e.snapshotVersion)
            put("rawPayloadSignature", e.rawPayloadSignature ?: JSONObject.NULL)
        }
    }

    private fun deserializeEntitlement(json: JSONObject): BillingEntitlement {
        val ownerAppUserId = if (json.isNull("ownerAppUserId")) null else json.optString("ownerAppUserId")
        val orderId = if (json.isNull("orderId")) null else json.optString("orderId")
        val expiryTimeMillis = if (json.isNull("expiryTimeMillis")) null else json.optLong("expiryTimeMillis", -1L).takeIf { it > 0 }
        val verifiedAtMillis = if (json.isNull("verifiedAtMillis")) null else json.optLong("verifiedAtMillis", -1L).takeIf { it > 0 }
        val rawPayloadSignature = if (json.isNull("rawPayloadSignature")) null else json.optString("rawPayloadSignature")

        val sourceStr = json.optString("source", EntitlementSource.GOOGLE_PLAY_SUBSCRIPTION.name)
        val source = try {
            EntitlementSource.valueOf(sourceStr)
        } catch (_: Exception) {
            EntitlementSource.GOOGLE_PLAY_SUBSCRIPTION
        }

        val stateStr = json.optString("state", EntitlementState.UNVERIFIED_CLIENT.name)
        val state = try {
            EntitlementState.valueOf(stateStr)
        } catch (_: Exception) {
            EntitlementState.UNVERIFIED_CLIENT
        }

        return BillingEntitlement(
            id = json.optString("id"),
            ownerAppUserId = ownerAppUserId,
            productId = json.optString("productId"),
            productType = json.optString("productType"),
            purchaseToken = json.optString("purchaseToken"),
            orderId = orderId,
            source = source,
            state = state,
            purchaseTimeMillis = json.optLong("purchaseTimeMillis", System.currentTimeMillis()),
            expiryTimeMillis = expiryTimeMillis,
            autoRenewing = json.optBoolean("autoRenewing", false),
            verifiedAtMillis = verifiedAtMillis,
            snapshotVersion = json.optLong("snapshotVersion", 1L),
            rawPayloadSignature = rawPayloadSignature
        )
    }
}
