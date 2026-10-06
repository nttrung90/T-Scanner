package com.tscanner.app.utils

import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

/**
 * Trình hỗ trợ xử lý Window Insets và Edge-to-Edge chuẩn mực cho T-Scanner.
 * 
 * Đáp ứng các nguyên tắc nghiêm ngặt:
 * 1. Xử lý cả 4 cạnh (left, top, right, bottom) dựa trên systemBars và displayCutout thực tế.
 * 2. Lưu trữ padding/margin ban đầu một lần duy nhất, ngăn ngừa 100% việc cộng dồn padding qua nhiều lần dispatch.
 * 3. Hỗ trợ bàn phím ảo (IME) cho các màn hình có ô nhập liệu (OCR, tìm kiếm, đặt tên tệp) bằng công thức:
 *    effectiveBottom = max(systemBars.bottom, ime.bottom), đảm bảo không bao giờ cộng lặp 2 lần nav + IME.
 * 4. Phân chia rõ ràng: vùng nền vẽ tràn viền, vùng điều khiển/nội dung tránh thanh hệ thống và tai thỏ / camera khoét lỗ.
 */
object EdgeToEdgeInsetsHelper {

    data class InitialPadding(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    data class InitialMargin(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    /**
     * Ghi nhận padding ban đầu của View trước bất kỳ lần dispatch insets nào.
     */
    fun recordInitialPadding(view: View): InitialPadding {
        return InitialPadding(
            left = view.paddingLeft,
            top = view.paddingTop,
            right = view.paddingRight,
            bottom = view.paddingBottom
        )
    }

    /**
     * Ghi nhận margin ban đầu của View trước bất kỳ lần dispatch insets nào.
     */
    fun recordInitialMargin(view: View): InitialMargin {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
        return InitialMargin(
            left = lp?.leftMargin ?: 0,
            top = lp?.topMargin ?: 0,
            right = lp?.rightMargin ?: 0,
            bottom = lp?.bottomMargin ?: 0
        )
    }

    /**
     * Lấy insets bao gồm systemBars (status bar, navigation bar, caption bar) và displayCutout (tai thỏ, nốt ruồi).
     */
    fun getSystemBarAndCutoutInsets(insets: WindowInsetsCompat): Insets {
        return insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
    }

    /**
     * Tính toán chiều cao cần tránh ở cạnh đáy (pure math calculation).
     * Ngăn chặn hoàn toàn việc cộng dồn nav + IME 2 lần.
     */
    fun calculateEffectiveBottom(systemBarBottom: Int, imeBottom: Int, includeIme: Boolean = false): Int {
        if (!includeIme) {
            return systemBarBottom
        }
        return max(systemBarBottom, imeBottom)
    }

    /**
     * Tính toán chiều cao cần tránh ở cạnh đáy từ WindowInsetsCompat.
     */
    fun getEffectiveBottomInset(insets: WindowInsetsCompat, includeIme: Boolean = false): Int {
        val sysInsets = getSystemBarAndCutoutInsets(insets)
        val imeBottom = if (includeIme) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
        return calculateEffectiveBottom(sysInsets.bottom, imeBottom, includeIme)
    }

    /**
     * Áp dụng insets an toàn cho Top Bar / Toolbar:
     * - Top: padding gốc + statusBar / cutout top + extraTop (nếu có)
     * - Left: padding gốc + cutout / side navigation bar left
     * - Right: padding gốc + cutout / side navigation bar right
     * - Bottom: giữ nguyên padding gốc
     */
    fun applyTopBarInsets(
        view: View,
        initial: InitialPadding,
        sysInsets: Insets,
        extraTop: Int = 0
    ) {
        view.setPadding(
            initial.left + sysInsets.left,
            initial.top + sysInsets.top + extraTop,
            initial.right + sysInsets.right,
            initial.bottom
        )
    }

    /**
     * Áp dụng insets an toàn cho Bottom Bar / Bottom Navigation / Actions:
     * - Bottom: padding gốc + bottomInset + extraBottom (nếu có)
     * - Left: padding gốc + cutout / side navigation bar left
     * - Right: padding gốc + cutout / side navigation bar right
     * - Top: giữ nguyên padding gốc
     */
    fun applyBottomBarInsets(
        view: View,
        initial: InitialPadding,
        sysInsets: Insets,
        bottomInset: Int,
        extraBottom: Int = 0
    ) {
        view.setPadding(
            initial.left + sysInsets.left,
            initial.top,
            initial.right + sysInsets.right,
            initial.bottom + bottomInset + extraBottom
        )
    }

    /**
     * Áp dụng insets an toàn cho vùng nội dung (Content) ở 2 bên trái/phải:
     * Giúp nội dung không bị che bởi camera cutout hoặc navigation bar ngang khi xoay landscape.
     */
    fun applyContentHorizontalInsets(
        view: View,
        initial: InitialPadding,
        sysInsets: Insets
    ) {
        view.setPadding(
            initial.left + sysInsets.left,
            view.paddingTop,
            initial.right + sysInsets.right,
            view.paddingBottom
        )
    }
}
