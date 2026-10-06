package com.tscanner.app.utils

import android.content.Context
import com.tscanner.app.R

/**
 * Các đích đến có thể có của luồng Quét trong ứng dụng T-Scanner.
 */
enum class ScanTarget {
    /**
     * Google ML Kit Document Scanner: Giao diện do Google Play Services cung cấp.
     * Có tính năng AI (tự chụp khi đứng yên, tự động xóa ngón tay/vết bẩn/bóng đổ).
     * Giới hạn: Ngôn ngữ UI do Google Play Services chi phối (theo ngôn ngữ hệ thống máy).
     */
    GOOGLE_AI,

    /**
     * Trình quét Camera nội bộ của T-Scanner (CameraScanActivity).
     * Tốc độ cao (Zero Shutter Lag UX), Two-phase commit C01, Auto Crop viền tài liệu.
     * Ưu điểm: Đảm bảo 100% ngôn ngữ UI theo cấu hình đa ngữ của ứng dụng.
     */
    INTERNAL_CAMERA
}

/**
 * Lựa chọn ưu tiên của người dùng đối với trình quét tài liệu.
 */
enum class ScannerUserPreference {
    /**
     * Tự động theo chính sách mặc định của ứng dụng (Hướng B: Google AI Scanner).
     */
    AUTOMATIC,

    /**
     * Luôn sử dụng Google AI Scanner.
     */
    GOOGLE_AI,

    /**
     * Luôn sử dụng Camera nội bộ của T-Scanner.
     */
    INTERNAL_CAMERA
}

/**
 * Mô tả siêu dữ liệu của từng trình quét phục vụ hiển thị trên giao diện và kiểm thử.
 */
data class ScannerDescriptor(
    val target: ScanTarget,
    val isRecommended: Boolean,
    val isAppLocaleGuaranteed: Boolean,
    val isGoogleUiLocaleUncontrolled: Boolean,
    val titleResId: Int,
    val descResId: Int,
    val badgeResId: Int
)

/**
 * Chính sách định tuyến giao diện Quét (Gói L01 - Triển khai theo Hướng B).
 *
 * Hợp đồng:
 * 1. Mặc định cho luồng quét tài liệu và quét thẻ là Google AI Scanner (Hướng B).
 * 2. Cung cấp Camera nội bộ như một tùy chọn song song để người dùng chủ động chọn khi cần UI thuần ngữ.
 * 3. Ghi nhận rõ ràng giới hạn kỹ thuật: Google SDK không cho phép can thiệp locale qua mã ứng dụng.
 * 4. Độc lập tuyệt đối với ngôn ngữ OCR (OcrModels) và không can thiệp ngôn ngữ thiết bị.
 */
object ScanUiPolicy {

    const val PREFS_NAME = "tscanner_scan_policy_prefs"
    const val KEY_PREFERRED_SCANNER = "key_preferred_scanner_mode"

    /**
     * Hướng B: Đích quét mặc định là Google AI Scanner.
     */
    val DEFAULT_SCAN_TARGET: ScanTarget = ScanTarget.GOOGLE_AI

    /**
     * Xác định đích quét tài liệu theo tùy chọn người dùng (Hàm thuần túy).
     */
    fun resolveDocumentScanTarget(pref: ScannerUserPreference = ScannerUserPreference.AUTOMATIC): ScanTarget {
        return when (pref) {
            ScannerUserPreference.INTERNAL_CAMERA -> ScanTarget.INTERNAL_CAMERA
            ScannerUserPreference.GOOGLE_AI -> ScanTarget.GOOGLE_AI
            ScannerUserPreference.AUTOMATIC -> DEFAULT_SCAN_TARGET
        }
    }

    /**
     * Xác định đích quét thẻ theo tùy chọn người dùng (Hàm thuần túy).
     */
    fun resolveIdCardScanTarget(pref: ScannerUserPreference = ScannerUserPreference.AUTOMATIC): ScanTarget {
        return when (pref) {
            ScannerUserPreference.INTERNAL_CAMERA -> ScanTarget.INTERNAL_CAMERA
            ScannerUserPreference.GOOGLE_AI -> ScanTarget.GOOGLE_AI
            ScannerUserPreference.AUTOMATIC -> DEFAULT_SCAN_TARGET
        }
    }

    /**
     * Lấy đích quét thay thế cho nút chuyển đổi (Toggle/Fallback).
     */
    fun getAlternativeTarget(current: ScanTarget): ScanTarget {
        return when (current) {
            ScanTarget.GOOGLE_AI -> ScanTarget.INTERNAL_CAMERA
            ScanTarget.INTERNAL_CAMERA -> ScanTarget.GOOGLE_AI
        }
    }

    /**
     * Kiểm tra nguy cơ lệch ngôn ngữ giữa App Locale và System Locale.
     * Khi ngôn ngữ app khác ngôn ngữ máy, Google SDK có nguy cơ hiển thị ngôn ngữ khác với app.
     */
    fun isLocaleMismatchRisk(appLocaleTag: String?, systemLocaleTag: String?): Boolean {
        if (appLocaleTag.isNullOrBlank() || systemLocaleTag.isNullOrBlank()) return false
        val normApp = appLocaleTag.trim().lowercase().split("-", "_")[0]
        val normSys = systemLocaleTag.trim().lowercase().split("-", "_")[0]
        return normApp != normSys
    }

    /**
     * Lấy mô tả chi tiết của từng trình quét.
     */
    fun getScannerDescriptor(target: ScanTarget): ScannerDescriptor {
        return when (target) {
            ScanTarget.GOOGLE_AI -> ScannerDescriptor(
                target = ScanTarget.GOOGLE_AI,
                isRecommended = true,
                isAppLocaleGuaranteed = false,
                isGoogleUiLocaleUncontrolled = true,
                titleResId = R.string.scan_option_ai,
                descResId = R.string.scan_option_ai_desc,
                badgeResId = R.string.scan_badge_recommended
            )
            ScanTarget.INTERNAL_CAMERA -> ScannerDescriptor(
                target = ScanTarget.INTERNAL_CAMERA,
                isRecommended = false,
                isAppLocaleGuaranteed = true,
                isGoogleUiLocaleUncontrolled = false,
                titleResId = R.string.scan_option_fast,
                descResId = R.string.scan_option_fast_desc,
                badgeResId = R.string.scan_badge_optional
            )
        }
    }

    /**
     * Đọc tùy chọn người dùng từ SharedPreferences.
     */
    fun getPreferredScanner(context: Context): ScannerUserPreference {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_PREFERRED_SCANNER, ScannerUserPreference.AUTOMATIC.name)
        return try {
            ScannerUserPreference.valueOf(name ?: ScannerUserPreference.AUTOMATIC.name)
        } catch (_: Exception) {
            ScannerUserPreference.AUTOMATIC
        }
    }

    /**
     * Ghi tùy chọn người dùng vào SharedPreferences.
     */
    fun setPreferredScanner(context: Context, pref: ScannerUserPreference) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PREFERRED_SCANNER, pref.name).apply()
    }

    /**
     * Xác định đích quét tài liệu dựa trên Context và SharedPreferences.
     */
    fun resolveDocumentScanTarget(context: Context): ScanTarget {
        return resolveDocumentScanTarget(getPreferredScanner(context))
    }

    /**
     * Xác định đích quét thẻ dựa trên Context và SharedPreferences.
     */
    fun resolveIdCardScanTarget(context: Context): ScanTarget {
        return resolveIdCardScanTarget(getPreferredScanner(context))
    }
}
