package com.tscanner.app.ui.editor.model

import org.json.JSONObject

/**
 * Tọa độ vùng cắt chuẩn hóa [0.0f..1.0f] theo hệ tọa độ của ảnh nguồn đã chuẩn hóa EXIF.
 * Lớp này bất biến (immutable) để bảo đảm an toàn khi lưu trữ, hoàn tác (undo) và xử lý nền.
 */
data class NormalizedCropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    val isFull: Boolean
        get() = left <= 0.001f && top <= 0.001f && right >= 0.999f && bottom >= 0.999f

    fun width(): Float = (right - left).coerceAtLeast(0f)
    fun height(): Float = (bottom - top).coerceAtLeast(0f)

    /**
     * Ràng buộc tọa độ trong khoảng [0.0f..1.0f], bảo đảm left < right và top < bottom.
     */
    fun safeNormalized(minDimension: Float = 0.02f): NormalizedCropRect {
        val sLeft = left.coerceIn(0f, 1f - minDimension)
        val sTop = top.coerceIn(0f, 1f - minDimension)
        val sRight = right.coerceIn(sLeft + minDimension, 1f)
        val sBottom = bottom.coerceIn(sTop + minDimension, 1f)
        return NormalizedCropRect(sLeft, sTop, sRight, sBottom)
    }

    /**
     * Biến đổi vùng cắt khi xoay 90 độ thuận chiều kim đồng hồ (+90°).
     * Điểm (x, y) trên hệ tọa độ chuẩn hóa chuyển thành (1 - y, x).
     */
    fun rotate90Clockwise(): NormalizedCropRect {
        val nLeft = 1f - bottom
        val nTop = left
        val nRight = 1f - top
        val nBottom = right
        return NormalizedCropRect(
            left = minOf(nLeft, nRight),
            top = minOf(nTop, nBottom),
            right = maxOf(nLeft, nRight),
            bottom = maxOf(nTop, nBottom)
        ).safeNormalized()
    }

    /**
     * Biến đổi vùng cắt khi xoay 90 độ ngược chiều kim đồng hồ (-90°).
     * Điểm (x, y) trên hệ tọa độ chuẩn hóa chuyển thành (y, 1 - x).
     */
    fun rotate90CounterClockwise(): NormalizedCropRect {
        val nLeft = top
        val nTop = 1f - right
        val nRight = bottom
        val nBottom = 1f - left
        return NormalizedCropRect(
            left = minOf(nLeft, nRight),
            top = minOf(nTop, nBottom),
            right = maxOf(nLeft, nRight),
            bottom = maxOf(nTop, nBottom)
        ).safeNormalized()
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("left", left.toDouble())
            put("top", top.toDouble())
            put("right", right.toDouble())
            put("bottom", bottom.toDouble())
        }
    }

    companion object {
        fun fromJson(json: JSONObject?): NormalizedCropRect {
            if (json == null) return NormalizedCropRect()
            return NormalizedCropRect(
                left = json.optDouble("left", 0.0).toFloat(),
                top = json.optDouble("top", 0.0).toFloat(),
                right = json.optDouble("right", 1.0).toFloat(),
                bottom = json.optDouble("bottom", 1.0).toFloat()
            ).safeNormalized()
        }
    }
}
