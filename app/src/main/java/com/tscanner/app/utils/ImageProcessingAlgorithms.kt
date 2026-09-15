package com.tscanner.app.utils

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Các thuật toán xử lý ảnh số thuần túy (pure Kotlin) thao tác trên mảng IntArray (ARGB_8888).
 * Không phụ thuộc Android SDK để phục vụ kiểm thử đơn vị JVM và tối ưu tốc độ xử lý điểm ảnh.
 */
object ImageProcessingAlgorithms {

    /**
     * Tính bán kính lọc (kernel radius) theo tỷ lệ kích thước ảnh.
     * Ảnh lớn cần bán kính lớn hơn tương ứng để hiệu ứng unsharp mask có cùng tỷ lệ thị giác.
     */
    fun calculateScaledRadius(width: Int, height: Int, baseRadiusFor1080p: Int = 2): Int {
        val maxDim = max(width, height)
        val scale = maxDim.toFloat() / 1080f
        return (baseRadiusFor1080p * scale).toInt().coerceIn(1, 12)
    }

    /**
     * Tính kích thước cửa sổ phân ngưỡng cục bộ (adaptive window size) theo tỷ lệ ảnh.
     */
    fun calculateAdaptiveWindowSize(width: Int, height: Int): Int {
        val maxDim = max(width, height)
        // Khoảng ~2-3% kích thước ảnh, luôn là số lẻ
        val size = (maxDim * 0.025f).toInt()
        val oddSize = if (size % 2 == 0) size + 1 else size
        return oddSize.coerceIn(15, 101)
    }

    /**
     * Tăng nét có ngưỡng (Thresholded Unsharp Masking):
     * - Làm mờ hộp nhanh (separable box blur) trên kênh độ sáng/màu.
     * - Tính sai phân Delta = Original - Blurred.
     * - Bỏ qua sai phân nhỏ (< noiseThreshold) để không khuếch đại hạt nhiễu cảm biến.
     * - Giới hạn biên độ Delta (clamp [-maxDiff, +maxDiff]) để tránh quầng sáng trắng (halo) và không làm dính chữ.
     * - Cộng bù lại theo cường độ intensity (0..100).
     */
    fun applyThresholdedUnsharpMask(
        pixels: IntArray,
        width: Int,
        height: Int,
        intensity: Int,
        radius: Int = calculateScaledRadius(width, height),
        noiseThreshold: Int = 3,
        maxDiff: Int = 65,
        checkActive: (() -> Unit)? = null
    ) {
        if (intensity <= 0 || width <= 0 || height <= 0) return

        val size = width * height
        if (pixels.size < size) return

        // 1. Tạo bản sao tạm để tính toán mờ
        val blurred = IntArray(size)
        System.arraycopy(pixels, 0, blurred, 0, size)

        // 2. Separable box blur theo chiều ngang
        val tempHorizontal = IntArray(size)
        val r = radius.coerceAtLeast(1)
        val div = 2 * r + 1

        for (y in 0 until height) {
            checkActive?.invoke()
            val rowOffset = y * width

            var sumR = 0
            var sumG = 0
            var sumB = 0

            // Khởi tạo cửa sổ đầu dòng
            val firstPx = blurred[rowOffset]
            val fR = (firstPx shr 16) and 0xFF
            val fG = (firstPx shr 8) and 0xFF
            val fB = firstPx and 0xFF
            sumR += fR * (r + 1)
            sumG += fG * (r + 1)
            sumB += fB * (r + 1)

            for (i in 1..r) {
                val p = blurred[rowOffset + min(i, width - 1)]
                sumR += (p shr 16) and 0xFF
                sumG += (p shr 8) and 0xFF
                sumB += p and 0xFF
            }

            for (x in 0 until width) {
                tempHorizontal[rowOffset + x] = (0xFF shl 24) or
                        ((sumR / div) shl 16) or
                        ((sumG / div) shl 8) or
                        (sumB / div)

                val xRemove = max(0, x - r)
                val xAdd = min(width - 1, x + r + 1)

                val pRemove = blurred[rowOffset + xRemove]
                val pAdd = blurred[rowOffset + xAdd]

                sumR += ((pAdd shr 16) and 0xFF) - ((pRemove shr 16) and 0xFF)
                sumG += ((pAdd shr 8) and 0xFF) - ((pRemove shr 8) and 0xFF)
                sumB += (pAdd and 0xFF) - (pRemove and 0xFF)
            }
        }

        // 3. Separable box blur theo chiều dọc
        for (x in 0 until width) {
            checkActive?.invoke()
            var sumR = 0
            var sumG = 0
            var sumB = 0

            val firstPx = tempHorizontal[x]
            val fR = (firstPx shr 16) and 0xFF
            val fG = (firstPx shr 8) and 0xFF
            val fB = firstPx and 0xFF
            sumR += fR * (r + 1)
            sumG += fG * (r + 1)
            sumB += fB * (r + 1)

            for (i in 1..r) {
                val p = tempHorizontal[min(i, height - 1) * width + x]
                sumR += (p shr 16) and 0xFF
                sumG += (p shr 8) and 0xFF
                sumB += p and 0xFF
            }

            for (y in 0 until height) {
                blurred[y * width + x] = (0xFF shl 24) or
                        ((sumR / div) shl 16) or
                        ((sumG / div) shl 8) or
                        (sumB / div)

                val yRemove = max(0, y - r)
                val yAdd = min(height - 1, y + r + 1)

                val pRemove = tempHorizontal[yRemove * width + x]
                val pAdd = tempHorizontal[yAdd * width + x]

                sumR += ((pAdd shr 16) and 0xFF) - ((pRemove shr 16) and 0xFF)
                sumG += ((pAdd shr 8) and 0xFF) - ((pRemove shr 8) and 0xFF)
                sumB += (pAdd and 0xFF) - (pRemove and 0xFF)
            }
        }

        // 4. Tính toán unsharp mask có ngưỡng và giới hạn biên độ
        val factor = intensity / 50f // Tại 50 là 1.0x, tại 100 là 2.0x

        for (i in 0 until size) {
            if (i % 2048 == 0) checkActive?.invoke()

            val orig = pixels[i]
            val a = (orig shr 24) and 0xFF
            val oR = (orig shr 16) and 0xFF
            val oG = (orig shr 8) and 0xFF
            val oB = orig and 0xFF

            val blur = blurred[i]
            val bR = (blur shr 16) and 0xFF
            val bG = (blur shr 8) and 0xFF
            val bB = blur and 0xFF

            val diffR = oR - bR
            val diffG = oG - bG
            val diffB = oB - bB

            val nR = sharpenChannel(oR, diffR, factor, noiseThreshold, maxDiff)
            val nG = sharpenChannel(oG, diffG, factor, noiseThreshold, maxDiff)
            val nB = sharpenChannel(oB, diffB, factor, noiseThreshold, maxDiff)

            pixels[i] = (a shl 24) or (nR shl 16) or (nG shl 8) or nB
        }
    }

    private fun sharpenChannel(
        original: Int,
        diff: Int,
        factor: Float,
        noiseThreshold: Int,
        maxDiff: Int
    ): Int {
        if (abs(diff) < noiseThreshold) return original
        val scaled = (diff * factor).toInt().coerceIn(-maxDiff, maxDiff)
        return (original + scaled).coerceIn(0, 255)
    }

    /**
     * Giảm bóng và làm sáng nền giấy:
     * - shadowIntensity: Bù sáng vùng bóng tối theo trường chiếu sáng
     * - lightenIntensity: Kéo giãn dải trắng phần giấy mà vẫn giữ nguyên màu mực đen
     */
    fun applyPaperWhitening(
        pixels: IntArray,
        width: Int,
        height: Int,
        shadowIntensity: Int,
        lightenIntensity: Int,
        checkActive: (() -> Unit)? = null
    ) {
        if (shadowIntensity <= 0 && lightenIntensity <= 0) return
        val size = width * height
        if (pixels.size < size) return

        val shadowWeight = shadowIntensity / 100f
        val lightenWeight = lightenIntensity / 100f

        // Ngưỡng phân định mực (text) vs nền giấy (paper)
        // Mực: dưới 90; Nền giấy: trên 130
        for (i in 0 until size) {
            if (i % 4096 == 0) checkActive?.invoke()

            val p = pixels[i]
            val a = (p shr 24) and 0xFF
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF

            val lum = (299 * r + 587 * g + 114 * b) / 1000

            var boost = 0
            if (lum > 90) {
                // Tỷ lệ tăng sáng tăng dần đối với pixel càng gần màu trắng giấy
                val paperFactor = ((lum - 90) / 165f).coerceIn(0f, 1f)
                val totalFactor = (shadowWeight * 0.5f + lightenWeight * 0.8f) * paperFactor
                boost = (totalFactor * (255 - lum)).toInt()
            }

            val newR = (r + boost).coerceIn(0, 255)
            val newG = (g + boost).coerceIn(0, 255)
            val newB = (b + boost).coerceIn(0, 255)

            pixels[i] = (a shl 24) or (newR shl 16) or (newG shl 8) or newB
        }
    }

    /**
     * Chuyển đổi sắc độ xám chuẩn BT.601
     */
    fun applyGrayscale(
        pixels: IntArray,
        width: Int,
        height: Int,
        checkActive: (() -> Unit)? = null
    ) {
        val size = width * height
        for (i in 0 until size) {
            if (i % 4096 == 0) checkActive?.invoke()

            val p = pixels[i]
            val a = (p shr 24) and 0xFF
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val gray = (299 * r + 587 * g + 114 * b) / 1000
            pixels[i] = (a shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
    }

    /**
     * Nhị phân hóa thích ứng (Adaptive Binarization) cho tài liệu:
     * Dùng mảng tích phân (Integral Image) để tính ngưỡng trung bình cục bộ O(1) cho mỗi pixel.
     * Bảo toàn các nét chữ mảnh, dấu thanh tiếng Việt và chữ viết tay trên nền không đồng đều.
     */
    fun applyAdaptiveBinarization(
        pixels: IntArray,
        width: Int,
        height: Int,
        windowSize: Int = calculateAdaptiveWindowSize(width, height),
        cOffset: Int = 10,
        checkActive: (() -> Unit)? = null
    ) {
        val size = width * height
        if (size <= 0) return

        // 1. Chuyển sang mảng độ sáng và tạo Integral Image
        val lum = IntArray(size)
        val integral = LongArray((width + 1) * (height + 1))

        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val p = pixels[rowOffset + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                lum[rowOffset + x] = (299 * r + 587 * g + 114 * b) / 1000
            }
        }

        val intW = width + 1
        for (y in 0 until height) {
            checkActive?.invoke()
            var sum = 0L
            val rowOffset = y * width
            val intRowOffset = (y + 1) * intW
            val prevIntRowOffset = y * intW

            for (x in 0 until width) {
                sum += lum[rowOffset + x]
                integral[intRowOffset + (x + 1)] = integral[prevIntRowOffset + (x + 1)] + sum
            }
        }

        // 2. Quét và phân ngưỡng cục bộ
        val r = windowSize / 2
        for (y in 0 until height) {
            checkActive?.invoke()
            val y1 = max(0, y - r)
            val y2 = min(height - 1, y + r)
            val rowOffset = y * width

            for (x in 0 until width) {
                val x1 = max(0, x - r)
                val x2 = min(width - 1, x + r)

                val count = (x2 - x1 + 1) * (y2 - y1 + 1)

                // Tính tổng từ integral image
                val sum = integral[(y2 + 1) * intW + (x2 + 1)] -
                        integral[y1 * intW + (x2 + 1)] -
                        integral[(y2 + 1) * intW + x1] +
                        integral[y1 * intW + x1]

                val mean = (sum / count).toInt()
                val threshold = (mean - cOffset).coerceIn(0, 255)

                val pixelLum = lum[rowOffset + x]
                val binaryColor = if (pixelLum < threshold) 0x00 else 0xFF

                pixels[rowOffset + x] = (0xFF shl 24) or
                        (binaryColor shl 16) or
                        (binaryColor shl 8) or
                        binaryColor
            }
        }
    }
}
