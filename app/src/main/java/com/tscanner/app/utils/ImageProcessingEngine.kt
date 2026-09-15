package com.tscanner.app.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.tscanner.app.ui.editor.model.DocumentFilterType
import com.tscanner.app.ui.editor.model.PageEditState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/**
 * Điều phối quy trình xử lý ảnh 6 bước cho màn hình Chỉnh sửa sau quét (Post-Scan Editor).
 * Xử lý trực tiếp từ ảnh nguồn đầu vào, hỗ trợ preview 2 mức (toàn trang & zoom ROI)
 * và kết xuất file độ phân giải đầy đủ an toàn.
 */
object ImageProcessingEngine {
    private const val TAG = "ImageProcessingEngine"
    const val PREVIEW_MAX_DIMENSION = 1280

    /**
     * Đọc góc quay EXIF từ file ảnh
     */
    fun getExifRotation(filePath: String): Float {
        return try {
            val exif = ExifInterface(filePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) {
            0f
        }
    }

    /**
     * Đọc ảnh preview thu nhỏ từ file nguồn đầu vào (đã chuẩn hóa EXIF)
     */
    fun loadNormalizedPreviewBitmap(filePath: String, maxDim: Int = PREVIEW_MAX_DIMENSION): Bitmap? {
        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) return null

        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, boundsOptions)
            val origW = boundsOptions.outWidth
            val origH = boundsOptions.outHeight
            if (origW <= 0 || origH <= 0) return null

            var inSampleSize = 1
            val maxOriginalDim = max(origW, origH)
            while (maxOriginalDim / (inSampleSize * 2) >= maxDim) {
                inSampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val rawBmp = BitmapFactory.decodeFile(filePath, decodeOptions) ?: return null

            val exifRotation = getExifRotation(filePath)
            if (exifRotation != 0f) {
                val matrix = Matrix().apply { postRotate(exifRotation) }
                val rotated = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, matrix, true)
                if (rotated != rawBmp) rawBmp.recycle()
                rotated
            } else {
                rawBmp
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi loadNormalizedPreviewBitmap: ${e.message}")
            null
        }
    }

    /**
     * Xử lý và hiển thị bản xem trước toàn trang theo PageEditState.
     * Kiểm tra hủy coroutine chủ động để phản hồi mượt mà khi người dùng kéo slider liên tục.
     */
    suspend fun renderPagePreview(
        basePreviewBitmap: Bitmap,
        state: PageEditState
    ): Bitmap? = withContext(Dispatchers.Default) {
        try {
            coroutineContext.ensureActive()

            // 1 & 2. Cắt khung và xoay
            var currentBitmap = basePreviewBitmap

            // Crop
            if (!state.cropRect.isFull) {
                val cropX = (state.cropRect.left * basePreviewBitmap.width).toInt().coerceIn(0, basePreviewBitmap.width - 1)
                val cropY = (state.cropRect.top * basePreviewBitmap.height).toInt().coerceIn(0, basePreviewBitmap.height - 1)
                val cropW = (state.cropRect.width() * basePreviewBitmap.width).toInt().coerceIn(1, basePreviewBitmap.width - cropX)
                val cropH = (state.cropRect.height() * basePreviewBitmap.height).toInt().coerceIn(1, basePreviewBitmap.height - cropY)
                currentBitmap = Bitmap.createBitmap(basePreviewBitmap, cropX, cropY, cropW, cropH)
            }

            coroutineContext.ensureActive()

            // Xoay
            val rotation = (state.rotationDegrees % 360 + 360) % 360
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(currentBitmap, 0, 0, currentBitmap.width, currentBitmap.height, matrix, true)
                if (currentBitmap != basePreviewBitmap && currentBitmap != rotated) {
                    currentBitmap.recycle()
                }
                currentBitmap = rotated
            }

            coroutineContext.ensureActive()

            // Nếu không có bất kỳ hiệu ứng bộ lọc, tăng nét hoặc làm sạch nào -> trả về luôn
            if (state.shadowRemovalIntensity == 0 &&
                state.backgroundLightenIntensity == 0 &&
                state.sharpnessIntensity == 0 &&
                state.filterType == DocumentFilterType.ORIGINAL
            ) {
                return@withContext currentBitmap
            }

            // 3, 4, 5, 6. Thực hiện xử lý điểm ảnh
            val w = currentBitmap.width
            val h = currentBitmap.height
            val pixels = IntArray(w * h)
            currentBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            coroutineContext.ensureActive()

            // 3 & 4. Giảm bóng & Làm sáng nền
            if (state.shadowRemovalIntensity > 0 || state.backgroundLightenIntensity > 0) {
                ImageProcessingAlgorithms.applyPaperWhitening(
                    pixels = pixels,
                    width = w,
                    height = h,
                    shadowIntensity = state.shadowRemovalIntensity,
                    lightenIntensity = state.backgroundLightenIntensity,
                    checkActive = { coroutineContext.ensureActive() }
                )
            }

            coroutineContext.ensureActive()

            // 5. Tăng nét có ngưỡng
            if (state.sharpnessIntensity > 0) {
                ImageProcessingAlgorithms.applyThresholdedUnsharpMask(
                    pixels = pixels,
                    width = w,
                    height = h,
                    intensity = state.sharpnessIntensity,
                    checkActive = { coroutineContext.ensureActive() }
                )
            }

            coroutineContext.ensureActive()

            // 6. Bộ lọc tài liệu
            when (state.filterType) {
                DocumentFilterType.GRAYSCALE -> {
                    ImageProcessingAlgorithms.applyGrayscale(pixels, w, h) { coroutineContext.ensureActive() }
                }
                DocumentFilterType.BLACK_AND_WHITE -> {
                    ImageProcessingAlgorithms.applyAdaptiveBinarization(pixels, w, h) { coroutineContext.ensureActive() }
                }
                DocumentFilterType.ORIGINAL -> {
                    // Giữ nguyên màu
                }
            }

            coroutineContext.ensureActive()

            val resultBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            resultBitmap.setPixels(pixels, 0, w, 0, 0, w, h)

            if (currentBitmap != basePreviewBitmap) {
                currentBitmap.recycle()
            }

            resultBitmap
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Lỗi renderPagePreview: ${e.message}")
            null
        }
    }

    /**
     * Mức 2 (Detail Tile ROI on Zoom):
     * Khi người dùng phóng to (Zoom > 1.2x) và dừng tay, render lát cắt vùng viewport đang xem
     * trực tiếp từ ảnh đầu vào độ phân giải gốc để kiểm tra nét chữ và dấu thanh tiếng Việt.
     */
    suspend fun renderDetailRoi(
        sourceImagePath: String,
        state: PageEditState,
        viewportRectNorm: RectF,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? = withContext(Dispatchers.Default) {
        val file = File(sourceImagePath)
        if (!file.exists() || file.length() == 0L) return@withContext null
        if (targetWidth <= 0 || targetHeight <= 0) return@withContext null

        try {
            coroutineContext.ensureActive()

            // 1. Đọc kích thước gốc
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourceImagePath, boundsOptions)
            val origW = boundsOptions.outWidth
            val origH = boundsOptions.outHeight
            if (origW <= 0 || origH <= 0) return@withContext null

            // Ánh xạ vùng viewport sang tọa độ ảnh gốc
            val vLeft = (viewportRectNorm.left.coerceIn(0f, 1f) * origW).toInt()
            val vTop = (viewportRectNorm.top.coerceIn(0f, 1f) * origH).toInt()
            val vRight = (viewportRectNorm.right.coerceIn(0f, 1f) * origW).toInt()
            val vBottom = (viewportRectNorm.bottom.coerceIn(0f, 1f) * origH).toInt()

            val cropRect = Rect(
                min(vLeft, vRight).coerceIn(0, origW - 1),
                min(vTop, vBottom).coerceIn(0, origH - 1),
                max(vLeft, vRight).coerceIn(1, origW),
                max(vTop, vBottom).coerceIn(1, origH)
            )

            if (cropRect.width() <= 0 || cropRect.height() <= 0) return@withContext null

            // Dùng BitmapRegionDecoder để chỉ đọc vùng cần thiết, tiết kiệm RAM
            val decoder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                File(sourceImagePath).inputStream().use { input ->
                    android.graphics.BitmapRegionDecoder.newInstance(input)
                }
            } else {
                @Suppress("DEPRECATION")
                android.graphics.BitmapRegionDecoder.newInstance(sourceImagePath, false)
            } ?: return@withContext null
            val roiBmp = decoder.decodeRegion(cropRect, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: run {
                decoder.recycle()
                return@withContext null
            }
            decoder.recycle()

            coroutineContext.ensureActive()

            // Chuẩn hóa EXIF rotation cho vùng ROI nếu cần
            val exifRotation = getExifRotation(sourceImagePath)
            val matrix = Matrix()
            if (exifRotation != 0f) matrix.postRotate(exifRotation)
            val stateRot = (state.rotationDegrees % 360 + 360) % 360
            if (stateRot != 0) matrix.postRotate(stateRot.toFloat())

            val orientedRoi = if (!matrix.isIdentity) {
                val r = Bitmap.createBitmap(roiBmp, 0, 0, roiBmp.width, roiBmp.height, matrix, true)
                if (r != roiBmp) roiBmp.recycle()
                r
            } else {
                roiBmp
            }

            coroutineContext.ensureActive()

            // Áp dụng thuật toán lên vùng ROI
            val w = orientedRoi.width
            val h = orientedRoi.height
            val pixels = IntArray(w * h)
            orientedRoi.getPixels(pixels, 0, w, 0, 0, w, h)

            if (state.shadowRemovalIntensity > 0 || state.backgroundLightenIntensity > 0) {
                ImageProcessingAlgorithms.applyPaperWhitening(
                    pixels, w, h, state.shadowRemovalIntensity, state.backgroundLightenIntensity
                ) { coroutineContext.ensureActive() }
            }

            if (state.sharpnessIntensity > 0) {
                ImageProcessingAlgorithms.applyThresholdedUnsharpMask(
                    pixels, w, h, state.sharpnessIntensity
                ) { coroutineContext.ensureActive() }
            }

            when (state.filterType) {
                DocumentFilterType.GRAYSCALE -> ImageProcessingAlgorithms.applyGrayscale(pixels, w, h) { coroutineContext.ensureActive() }
                DocumentFilterType.BLACK_AND_WHITE -> ImageProcessingAlgorithms.applyAdaptiveBinarization(pixels, w, h) { coroutineContext.ensureActive() }
                DocumentFilterType.ORIGINAL -> {}
            }

            val resultRoi = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            resultRoi.setPixels(pixels, 0, w, 0, 0, w, h)
            if (orientedRoi != resultRoi) orientedRoi.recycle()

            resultRoi
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Lỗi renderDetailRoi: ${e.message}")
            null
        }
    }

    /**
     * R05: Kiểm tra ngân sách bộ nhớ trước khi xử lý ảnh full-resolution.
     */
    fun checkMemoryBudget(width: Int, height: Int): Boolean {
        val estimatedBytes = width.toLong() * height.toLong() * 4L * 3L
        val runtime = Runtime.getRuntime()
        val availableHeap = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        val safetyReserve = 48 * 1024 * 1024L // 48MB dự phòng cho hệ thống và UI
        return (availableHeap - estimatedBytes) >= safetyReserve
    }

    /**
     * Xuất trang ở độ phân giải đầy đủ (Full Resolution) cho bản lưu PDF/xuất file.
     * R04: Sử dụng SafeFileWriter để thay thế an toàn và nguyên tử (atomic replace).
     * R05: Kiểm tra ngân sách bộ nhớ trước khi decode.
     */
    suspend fun processAndSaveFullResolution(
        sourceImagePath: String,
        state: PageEditState,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        val file = File(sourceImagePath)
        if (!file.exists() || file.length() == 0L) return@withContext false

        // Kiểm tra kích thước trước khi decode
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourceImagePath, boundsOptions)
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return@withContext false

        if (!checkMemoryBudget(boundsOptions.outWidth, boundsOptions.outHeight)) {
            Log.w(TAG, "Không đủ ngân sách bộ nhớ cho ảnh ${boundsOptions.outWidth}x${boundsOptions.outHeight}")
            return@withContext false
        }

        val result = SafeFileWriter.writeSafely(
            destinationFile = outputFile,
            validator = { SafeFileWriter.validateImage(it) }
        ) { tempFile ->
            var fullBmp: Bitmap? = null
            var orientedBmp: Bitmap? = null
            try {
                fullBmp = BitmapFactory.decodeFile(sourceImagePath) ?: return@writeSafely false

                // Chuẩn hóa EXIF
                val exifRotation = getExifRotation(sourceImagePath)
                orientedBmp = if (exifRotation != 0f) {
                    val matrix = Matrix().apply { postRotate(exifRotation) }
                    val rot = Bitmap.createBitmap(fullBmp, 0, 0, fullBmp.width, fullBmp.height, matrix, true)
                    if (rot != fullBmp) fullBmp.recycle()
                    rot
                } else {
                    fullBmp
                }

                // 1. Cắt theo hệ tọa độ ảnh nguồn
                if (!state.cropRect.isFull) {
                    val cropX = (state.cropRect.left * orientedBmp.width).toInt().coerceIn(0, orientedBmp.width - 1)
                    val cropY = (state.cropRect.top * orientedBmp.height).toInt().coerceIn(0, orientedBmp.height - 1)
                    val cropW = (state.cropRect.width() * orientedBmp.width).toInt().coerceIn(1, orientedBmp.width - cropX)
                    val cropH = (state.cropRect.height() * orientedBmp.height).toInt().coerceIn(1, orientedBmp.height - cropY)
                    val cropped = Bitmap.createBitmap(orientedBmp, cropX, cropY, cropW, cropH)
                    if (cropped != orientedBmp) orientedBmp.recycle()
                    orientedBmp = cropped
                }

                // 2. Xoay góc người dùng
                val rotation = com.tscanner.app.ui.editor.model.PageGeometry.normalizeRotation(state.rotationDegrees)
                if (rotation != 0) {
                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                    val rotated = Bitmap.createBitmap(orientedBmp, 0, 0, orientedBmp.width, orientedBmp.height, matrix, true)
                    if (rotated != orientedBmp) orientedBmp.recycle()
                    orientedBmp = rotated
                }

                // 3. Xử lý điểm ảnh nếu có hiệu ứng
                if (state.shadowRemovalIntensity > 0 ||
                    state.backgroundLightenIntensity > 0 ||
                    state.sharpnessIntensity > 0 ||
                    state.filterType != DocumentFilterType.ORIGINAL
                ) {
                    val w = orientedBmp.width
                    val h = orientedBmp.height
                    val pixels = IntArray(w * h)
                    orientedBmp.getPixels(pixels, 0, w, 0, 0, w, h)

                    if (state.shadowRemovalIntensity > 0 || state.backgroundLightenIntensity > 0) {
                        ImageProcessingAlgorithms.applyPaperWhitening(
                            pixels, w, h, state.shadowRemovalIntensity, state.backgroundLightenIntensity
                        )
                    }

                    if (state.sharpnessIntensity > 0) {
                        ImageProcessingAlgorithms.applyThresholdedUnsharpMask(
                            pixels, w, h, state.sharpnessIntensity
                        )
                    }

                    when (state.filterType) {
                        DocumentFilterType.GRAYSCALE -> ImageProcessingAlgorithms.applyGrayscale(pixels, w, h)
                        DocumentFilterType.BLACK_AND_WHITE -> ImageProcessingAlgorithms.applyAdaptiveBinarization(pixels, w, h)
                        DocumentFilterType.ORIGINAL -> {}
                    }

                    val finalBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    finalBmp.setPixels(pixels, 0, w, 0, 0, w, h)
                    if (finalBmp != orientedBmp) orientedBmp.recycle()
                    orientedBmp = finalBmp
                }

                // Ghi ra temp file
                FileOutputStream(tempFile).use { out ->
                    orientedBmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi processAndSaveFullResolution: ${e.message}", e)
                false
            } finally {
                orientedBmp?.recycle()
            }
        }
        result is SafeFileWriter.Result.Success
    }
}
