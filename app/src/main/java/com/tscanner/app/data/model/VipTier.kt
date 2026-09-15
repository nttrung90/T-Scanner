package com.tscanner.app.data.model

import java.io.Serializable

enum class VipTier(
    val id: String,
    val displayName: String,
    val priceDisplay: String,
    val priceVnd: Long,
    val isAvailable: Boolean
) : Serializable {
    FREE("free", "Miễn phí", "0 đ", 0, true),
    VIP("vip", "VIP", "20.000 đ / năm", 20000, true),
    VIP_PRO("vip_pro", "VIP PRO", "Sắp ra mắt", 0, false),
    VIP_PRO_MAX("vip_pro_max", "VIP PRO MAX", "Sắp ra mắt", 0, false);

    companion object {
        fun fromId(id: String?): VipTier {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: FREE
        }
    }
}
