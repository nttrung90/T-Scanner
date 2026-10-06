package com.tscanner.app.data.model

import java.io.Serializable

enum class VipTier(
    val id: String,
    val displayName: String,
    val isAvailable: Boolean
) : Serializable {
    FREE("free", "Miễn phí", true),
    VIP("vip", "VIP", true),
    VIP_PRO("vip_pro", "VIP PRO", false),
    VIP_PRO_MAX("vip_pro_max", "VIP PRO MAX", false);

    @Deprecated("Static prices are removed in favor of Google Play Billing product details (F10)")
    val priceDisplay: String get() = ""

    @Deprecated("Static prices are removed in favor of Google Play Billing product details (F10)")
    val priceVnd: Long get() = 0L

    companion object {
        fun fromId(id: String?): VipTier {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: FREE
        }
    }
}
