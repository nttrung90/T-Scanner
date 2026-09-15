package com.tscanner.app.data.model

import java.io.Serializable

data class UserProfile(
    val id: String,
    val email: String,
    val displayName: String,
    val givenName: String? = null,
    val familyName: String? = null,
    val photoUrl: String? = null,
    val idToken: String? = null,
    var isVip: Boolean = false,
    var tier: VipTier = if (isVip) VipTier.VIP else VipTier.FREE,
    val loginTime: Long = System.currentTimeMillis(),
    var vipPurchasedAt: Long? = null,
    var vipExpiresAt: Long? = null
) : Serializable {
    /**
     * Checks if VIP status is currently active and within valid expiration timeframe.
     */
    val isVipActive: Boolean
        get() = isVip && tier != VipTier.FREE && (vipExpiresAt == null || System.currentTimeMillis() <= vipExpiresAt!!)

    /**
     * Returns the remaining days of VIP subscription.
     */
    val daysRemaining: Int
        get() = if (vipExpiresAt != null) {
            (((vipExpiresAt!! - System.currentTimeMillis()) / (1000 * 60 * 60 * 24L)).coerceAtLeast(0L)).toInt()
        } else {
            0
        }
}
