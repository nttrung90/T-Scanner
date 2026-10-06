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
     * Tăng nét đa tỷ lệ trên kênh độ sáng (Multi-Scale Luminance Unsharp Masking):
     * - Thao tác hoàn toàn trên kênh độ sáng Y (Rec. 601) để bảo toàn 100% tỷ lệ màu (chroma), triệt tiêu viền màu giả.
     * - Đa tỷ lệ 2 tầng (Fine scale cho nét mảnh & dấu tiếng Việt; Coarse scale cho viền nhòe dày/out-of-focus).
     * - Khử nhiễu mềm (Soft coring) tránh tạo bậc nhảy và không khuếch đại hạt nhiễu trên nền giấy phẳng.
     * - Điều biến thích ứng nền (Paper Background Attenuation) giữ nền trắng sạch không lốm đốm.
     * - Kiểm soát quầng sáng bất đối xứng (Asymmetric halo control) ngăn viền trắng quanh chữ và ngăn dính nét hẹp.
     * - Giữ nguyên tuyệt đối điểm ảnh (strict no-op) khi intensity <= 0.
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

        // 1. Trích xuất kênh độ sáng Y (0..255)
        val lum = IntArray(size)
        for (i in 0 until size) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lum[i] = (299 * r + 587 * g + 114 * b + 500) / 1000
        }

        // 2. Thiết lập bán kính đa tầng: tầng 1 nét mảnh/dấu, tầng 2 viền nhòe dày
        val baseR = radius.coerceAtLeast(1)
        val r1 = max(1, (baseR * 0.5f).toInt())
        val r2 = max(r1 + 1, (baseR * 1.5f).toInt())

        // 3. Tính 2 tầng làm mờ trên kênh độ sáng
        val blur1 = IntArray(size)
        val blur2 = IntArray(size)

        boxBlurLuminance(lum, blur1, width, height, r1, checkActive)
        boxBlurLuminance(lum, blur2, width, height, r2, checkActive)

        // 4. Tổng hợp sai phân đa tỷ lệ, khử nhiễu mềm và giới hạn halo
        val factor = intensity / 50f // 1.0x ở mức 50, 2.0x ở mức 100
        val wFine = 1.1f * factor
        val wCoarse = 0.55f * factor
        val threshold = noiseThreshold.toFloat()

        for (i in 0 until size) {
            if (i % 2048 == 0) checkActive?.invoke()

            val yVal = lum[i]
            val b1 = blur1[i]
            val b2 = blur2[i]

            // Tách thành phần tần số cao (fine) và trung (band-pass)
            val diffFine = (yVal - b1).toFloat()
            val diffCoarse = (b1 - b2).toFloat()

            val rawDiff = wFine * diffFine + wCoarse * diffCoarse
            val absDiff = abs(rawDiff)

            // Khử nhiễu mềm (Soft Coring): triệt tiêu hạt nhiễu nhỏ êm dịu, không giật bậc
            val coredDiff = if (absDiff <= threshold) {
                0f
            } else {
                val excess = absDiff - threshold
                val smoothFactor = excess / (excess + threshold)
                val cored = excess * smoothFactor
                if (rawDiff > 0f) cored else -cored
            }

            if (coredDiff == 0f) continue

            // Điều biến giảm biên độ nếu ở nền giấy rất sáng (Y > 210) để giữ nền sạch
            var delta = coredDiff
            if (delta > 0f && yVal > 210) {
                val paperDampen = ((255 - yVal) / 45f).coerceIn(0.25f, 1.0f)
                delta *= paperDampen
            }

            val orig = pixels[i]
            val a = (orig shr 24) and 0xFF
            val oR = (orig shr 16) and 0xFF
            val oG = (orig shr 8) and 0xFF
            val oB = orig and 0xFF

            val minChannel = min(oR, min(oG, oB))
            val maxChannel = max(oR, max(oG, oB))

            // Kiểm soát quầng sáng bất đối xứng (Asymmetric Halo Control):
            // - Phía sáng (overshoot): giới hạn quầng trắng không vượt quá 45 hoặc mép 255 của kênh lớn nhất
            // - Phía tối (undershoot): tăng tương phản nét chữ nhưng không vượt quá maxDiff hoặc mép 0 của kênh nhỏ nhất
            val maxOvershoot = min(maxDiff, min(45, 255 - maxChannel))
            val maxUndershoot = min(maxDiff, minChannel)
            val clampedDelta = delta.toInt().coerceIn(-maxUndershoot, maxOvershoot)

            if (clampedDelta == 0) continue

            // Áp dụng độ lệch delta đồng nhất vào RGB, bảo toàn tuyệt đối sắc độ (R - G, B - G không đổi)
            val nR = oR + clampedDelta
            val nG = oG + clampedDelta
            val nB = oB + clampedDelta

            pixels[i] = (a shl 24) or (nR shl 16) or (nG shl 8) or nB
        }
    }

    /**
     * Làm mờ hộp phân tách (Separable Box Blur) nhanh trên mảng 1 kênh Luminance.
     * Sử dụng buffer 1D cột kích thước O(height) để làm mờ dọc tại chỗ, tiết kiệm RAM tối đa.
     */
    fun boxBlurLuminance(
        src: IntArray,
        dst: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        checkActive: (() -> Unit)? = null
    ) {
        if (width <= 0 || height <= 0) return
        if (width == 1 && height == 1) {
            dst[0] = src[0]
            return
        }

        val rH = radius.coerceIn(1, max(1, width - 1))
        val divH = 2 * rH + 1

        // 1. Quét ngang từ src sang dst
        for (y in 0 until height) {
            if (y % 64 == 0) checkActive?.invoke()
            val rowOffset = y * width

            var sum = src[rowOffset] * (rH + 1)
            for (i in 1..rH) {
                sum += src[rowOffset + min(i, width - 1)]
            }

            for (x in 0 until width) {
                dst[rowOffset + x] = sum / divH
                val xRemove = max(0, x - rH)
                val xAdd = min(width - 1, x + rH + 1)
                sum += src[rowOffset + xAdd] - src[rowOffset + xRemove]
            }
        }

        // 2. Quét dọc tại chỗ trên dst sử dụng buffer 1 cột
        val rV = radius.coerceIn(1, max(1, height - 1))
        val divV = 2 * rV + 1
        val colBuffer = IntArray(height)

        for (x in 0 until width) {
            if (x % 64 == 0) checkActive?.invoke()

            for (y in 0 until height) {
                colBuffer[y] = dst[y * width + x]
            }

            var sum = colBuffer[0] * (rV + 1)
            for (i in 1..rV) {
                sum += colBuffer[min(i, height - 1)]
            }

            for (y in 0 until height) {
                dst[y * width + x] = sum / divV
                val yRemove = max(0, y - rV)
                val yAdd = min(height - 1, y + rV + 1)
                sum += colBuffer[yAdd] - colBuffer[yRemove]
            }
        }
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
