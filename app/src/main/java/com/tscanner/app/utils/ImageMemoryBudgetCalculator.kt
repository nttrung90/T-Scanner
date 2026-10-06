package com.tscanner.app.utils

import com.tscanner.app.ui.editor.model.DocumentFilterType
import com.tscanner.app.ui.editor.model.PageEditState

/**
 * R05: Ước lượng ngân sách bộ nhớ RAM theo từng nhánh thuật toán xử lý ảnh.
 * Tách biệt hoàn toàn logic tính toán thuần khỏi Android Runtime để phục vụ kiểm thử đơn vị.
 */
object ImageMemoryBudgetCalculator {

    const val SAFETY_RESERVE_BYTES = 48L * 1024L * 1024L // 48MB dự phòng cho OS và UI

    /**
     * Xác định số byte đỉnh trên mỗi điểm ảnh (peak bytes per pixel) dựa trên các cấu trúc dữ liệu
     * sống đồng thời trong bộ nhớ ở từng giai đoạn xử lý:
     * - ORIGINAL không hiệu ứng: 8 bytes/pixel (2 Bitmap đồng thời khi xoay/cắt ảnh nguồn).
     * - WHITENING_ONLY hoặc GRAYSCALE: 12 bytes/pixel (Bitmap nguồn 4 + mảng pixels 4 + Bitmap kết quả 4).
     * - SHARPEN (Thresholded Unsharp Mask): 20 bytes/pixel (Bitmap nguồn 4 + pixels 4 + lum 4 + blur1 4 + blur2 4).
     * - BLACK_AND_WHITE (Adaptive Binarization): 20 bytes/pixel (Bitmap nguồn 4 + pixels 4 + lum 4 + integral 8).
     * - COMBINED (Vừa tăng nét vừa nhị phân): 24 bytes/pixel (dự phòng đệm chuyển tiếp giữa các tầng lọc).
     */
    fun getBytesPerPixel(state: PageEditState): Int {
        val hasWhitening = state.shadowRemovalIntensity > 0 || state.backgroundLightenIntensity > 0
        val hasSharpen = state.sharpnessIntensity > 0
        val isBw = state.filterType == DocumentFilterType.BLACK_AND_WHITE
        val isGray = state.filterType == DocumentFilterType.GRAYSCALE

        return when {
            hasSharpen && isBw -> 24
            hasSharpen -> 20
            isBw -> 20
            hasWhitening || isGray -> 12
            else -> 8
        }
    }

    /**
     * Tính toán tổng dung lượng byte bộ nhớ đỉnh cần thiết.
     * Trả về -1L nếu kích thước không hợp lệ (<= 0) hoặc xảy ra tràn số Long.
     */
    fun calculateRequiredBytes(width: Int, height: Int, bytesPerPixel: Int): Long {
        if (width <= 0 || height <= 0 || bytesPerPixel <= 0) return -1L

        val w = width.toLong()
        val h = height.toLong()
        val bpp = bytesPerPixel.toLong()

        // Kiểm tra tràn số: w * h
        if (w > Long.MAX_VALUE / h) return -1L
        val pixels = w * h

        // Kiểm tra tràn số: pixels * bpp
        if (pixels > Long.MAX_VALUE / bpp) return -1L
        val baseBytes = pixels * bpp

        // Phần bổ sung cho mảng tích phân integral (w+1)*(h+1)*8 ở nhánh nhị phân
        val extraBytes = if (bytesPerPixel >= 20) {
            val intW = w + 1
            val intH = h + 1
            if (intW > Long.MAX_VALUE / intH) return -1L
            val integralEntries = intW * intH
            val extraPixels = integralEntries - pixels
            if (extraPixels > 0) extraPixels * 8L else 0L
        } else {
            0L
        }

        return if (Long.MAX_VALUE - baseBytes < extraBytes) -1L else baseBytes + extraBytes
    }

    fun calculateRequiredBytes(width: Int, height: Int, state: PageEditState): Long {
        return calculateRequiredBytes(width, height, getBytesPerPixel(state))
    }

    /**
     * Kiểm tra xem dung lượng heap khả dụng có đủ đáp ứng dung lượng yêu cầu + ngưỡng an toàn hay không.
     * Cho phép tiêm availableHeapBytes để phục vụ unit test thuần.
     */
    fun hasSufficientMemory(
        width: Int,
        height: Int,
        state: PageEditState,
        availableHeapBytes: Long,
        safetyReserveBytes: Long = SAFETY_RESERVE_BYTES
    ): Boolean {
        val requiredBytes = calculateRequiredBytes(width, height, state)
        if (requiredBytes <= 0L) return false
        if (availableHeapBytes < safetyReserveBytes) return false
        return (availableHeapBytes - requiredBytes) >= safetyReserveBytes
    }

    /**
     * Kiểm tra trực tiếp trên Runtime JVM hiện tại.
     */
    fun hasSufficientRuntimeMemory(
        width: Int,
        height: Int,
        state: PageEditState,
        safetyReserveBytes: Long = SAFETY_RESERVE_BYTES
    ): Boolean {
        val runtime = Runtime.getRuntime()
        val availableHeap = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        return hasSufficientMemory(width, height, state, availableHeap, safetyReserveBytes)
    }
}
