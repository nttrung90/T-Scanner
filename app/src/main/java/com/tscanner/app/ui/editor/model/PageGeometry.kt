package com.tscanner.app.ui.editor.model

/**
 * R01: Thống nhất hình học cắt và xoay trên toàn bộ ứng dụng.
 * Quy tắc:
 * 1. cropRect luôn nằm trong hệ tọa độ ảnh nguồn đã chuẩn hóa EXIF, [0.0f..1.0f].
 * 2. Pipeline hình học: Nguồn -> EXIF chuẩn hóa -> Cắt theo nguồn -> Xoay góc người dùng -> Scale hiển thị/xuất.
 * 3. Thao tác xoay chỉ cập nhật rotationDegrees, KHÔNG thay đổi cropRect.
 * 4. Thao tác cắt trên màn hình hiển thị được ánh xạ ngược về hệ tọa độ nguồn qua PageGeometry.
 */
object PageGeometry {

    /**
     * Chuẩn hóa góc xoay về tập {0, 90, 180, 270}.
     */
    fun normalizeRotation(degrees: Int): Int {
        val rem = degrees % 360
        return if (rem < 0) rem + 360 else rem
    }

    /**
     * Ánh xạ vùng chọn cắt trên màn hình [screenCrop] về hệ tọa độ chuẩn hóa của ảnh nguồn.
     * @param screenCrop Tọa độ cắt [0..1] do người dùng chọn trên ảnh hiển thị (đã xoay và có thể đã cắt).
     * @param currentSourceCrop Vùng cắt nguồn hiện thời [0..1] của trang.
     * @param rotationDegrees Góc xoay hiện thời của trang (0, 90, 180, 270).
     * @return Tọa độ cắt mới [0..1] trong hệ tọa độ của ảnh nguồn.
     */
    fun mapScreenCropToSource(
        screenCrop: NormalizedCropRect,
        currentSourceCrop: NormalizedCropRect,
        rotationDegrees: Int
    ): NormalizedCropRect {
        val sLeft = screenCrop.left.coerceIn(0f, 1f)
        val sTop = screenCrop.top.coerceIn(0f, 1f)
        val sRight = screenCrop.right.coerceIn(sLeft, 1f)
        val sBottom = screenCrop.bottom.coerceIn(sTop, 1f)

        // Tính vùng [u, v] trên ảnh đã crop trước đó
        val (uMin, uMax, vMin, vMax) = when (normalizeRotation(rotationDegrees)) {
            90 -> {
                val u1 = sTop
                val u2 = sBottom
                val v1 = 1f - sRight
                val v2 = 1f - sLeft
                listOf(minOf(u1, u2), maxOf(u1, u2), minOf(v1, v2), maxOf(v1, v2))
            }
            180 -> {
                val u1 = 1f - sRight
                val u2 = 1f - sLeft
                val v1 = 1f - sBottom
                val v2 = 1f - sTop
                listOf(minOf(u1, u2), maxOf(u1, u2), minOf(v1, v2), maxOf(v1, v2))
            }
            270 -> {
                val u1 = 1f - sBottom
                val u2 = 1f - sTop
                val v1 = sLeft
                val v2 = sRight
                listOf(minOf(u1, u2), maxOf(u1, u2), minOf(v1, v2), maxOf(v1, v2))
            }
            else -> { // 0 độ
                listOf(sLeft, sRight, sTop, sBottom)
            }
        }

        val baseLeft = currentSourceCrop.left
        val baseTop = currentSourceCrop.top
        val baseW = currentSourceCrop.width()
        val baseH = currentSourceCrop.height()

        val newLeft = baseLeft + uMin * baseW
        val newRight = baseLeft + uMax * baseW
        val newTop = baseTop + vMin * baseH
        val newBottom = baseTop + vMax * baseH

        return NormalizedCropRect(
            left = newLeft,
            top = newTop,
            right = newRight,
            bottom = newBottom
        ).safeNormalized()
    }
}
