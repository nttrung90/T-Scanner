package com.tscanner.app.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object DocumentEdgeDetector {

    /**
     * Lấy góc quay EXIF của file ảnh
     */
    fun getRotationDegrees(file: File): Float {
        return try {
            val exif = ExifInterface(file.absolutePath)
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
     * Chuẩn hóa chiều xoay của ảnh dựa trên EXIF và lưu ra file đích
     */
    suspend fun normalizeImageOrientation(sourceFile: File, outputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!sourceFile.exists()) return@withContext false
            val rotation = getRotationDegrees(sourceFile)
            if (rotation != 0f) {
                val bmp = BitmapFactory.decodeFile(sourceFile.absolutePath) ?: return@withContext false
                val matrix = Matrix().apply { postRotate(rotation) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                outputFile.parentFile?.mkdirs()
                FileOutputStream(outputFile).use { out ->
                    rotated.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                if (rotated != bmp) rotated.recycle()
                bmp.recycle()
                true
            } else {
                sourceFile.copyTo(outputFile, overwrite = true)
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            sourceFile.copyTo(outputFile, overwrite = true)
            false
        }
    }

    /**
     * Tự động phát hiện viền trang tài liệu và cắt bỏ phần viền thừa (bàn, nền) trong luồng nền IO.
     * Tự động chuẩn hóa xoay theo chiều đứng EXIF.
     * @param sourceFile File ảnh gốc vừa chụp
     * @param outputFile File đích lưu ảnh đã cắt
     * @return true nếu tự động nhận diện và cắt thành công, false nếu giữ nguyên
     */
    suspend fun detectAndCrop(sourceFile: File, outputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!sourceFile.exists()) return@withContext false

            val rotation = getRotationDegrees(sourceFile)

            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourceFile.absolutePath, boundsOptions)
            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight
            if (origWidth <= 0 || origHeight <= 0) return@withContext false

            // Đọc một bản thu nhỏ (sample) để phân tích cạnh siêu nhanh (< 20ms)
            val targetSampleDim = 400
            val maxDim = max(origWidth, origHeight)
            var sampleSize = 1
            while (maxDim / (sampleSize * 2) >= targetSampleDim) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var sampleBitmap = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOptions) ?: return@withContext false

            // Xoay sampleBitmap thẳng đứng nếu cần trước khi phân tích viền
            if (rotation != 0f) {
                val matrix = Matrix().apply { postRotate(rotation) }
                val rotatedSample = Bitmap.createBitmap(sampleBitmap, 0, 0, sampleBitmap.width, sampleBitmap.height, matrix, true)
                if (rotatedSample != sampleBitmap) sampleBitmap.recycle()
                sampleBitmap = rotatedSample
            }

            val detectedNormRect = findDocumentBounds(sampleBitmap)
            sampleBitmap.recycle()

            // Đọc ảnh gốc với kích thước chuẩn
            val fullOptions = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var fullBitmap = BitmapFactory.decodeFile(sourceFile.absolutePath, fullOptions) ?: return@withContext false

            // Xoay fullBitmap thẳng đứng nếu cần
            if (rotation != 0f) {
                val matrix = Matrix().apply { postRotate(rotation) }
                val rotatedFull = Bitmap.createBitmap(fullBitmap, 0, 0, fullBitmap.width, fullBitmap.height, matrix, true)
                if (rotatedFull != fullBitmap) fullBitmap.recycle()
                fullBitmap = rotatedFull
            }

            // Nếu vùng nhận diện hợp lệ (chiếm từ 20% đến 96% diện tích khung hình)
            val area = detectedNormRect.width() * detectedNormRect.height()
            val resultBitmap = if (area in 0.20f..0.96f) {
                val x = (detectedNormRect.left * fullBitmap.width).toInt().coerceIn(0, fullBitmap.width - 1)
                val y = (detectedNormRect.top * fullBitmap.height).toInt().coerceIn(0, fullBitmap.height - 1)
                val w = (detectedNormRect.width() * fullBitmap.width).toInt().coerceIn(1, fullBitmap.width - x)
                val h = (detectedNormRect.height() * fullBitmap.height).toInt().coerceIn(1, fullBitmap.height - y)

                val cropped = Bitmap.createBitmap(fullBitmap, x, y, w, h)
                if (cropped != fullBitmap) fullBitmap.recycle()
                cropped
            } else {
                fullBitmap
            }

            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { out ->
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            resultBitmap.recycle()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            normalizeImageOrientation(sourceFile, outputFile)
            false
        }
    }

    /**
     * Tìm đường biên tài liệu trên ảnh thu nhỏ dựa trên độ tương phản cạnh & độ sáng trung bình.
     * Trả về RectF chuẩn hóa [0f..1f].
     */
    fun findDocumentBounds(bitmap: Bitmap): RectF {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 10 || h <= 10) return RectF(0f, 0f, 1f, 1f)

        // Tính mảng độ sáng (Luminance)
        val lums = IntArray(w * h)
        var totalLum = 0L
        for (y in 0 until h) {
            for (x in 0 until w) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                lums[y * w + x] = lum
                totalLum += lum
            }
        }
        val avgLum = (totalLum / (w * h)).toInt()

        // Tính gradient chênh lệch theo chiều ngang (X) và dọc (Y) để tìm mép giấy
        var minX = w
        var maxX = 0
        var minY = h
        var maxY = 0

        val thresholdGradient = 28 // Ngưỡng chênh lệch sáng/tối của mép giấy

        for (y in (h * 0.05).toInt() until (h * 0.95).toInt() step 2) {
            for (x in (w * 0.05).toInt() until (w * 0.95).toInt() step 2) {
                val cur = lums[y * w + x]
                // Kiểm tra cạnh ngang
                val dx = if (x + 2 < w) abs(cur - lums[y * w + (x + 2)]) else 0
                val dy = if (y + 2 < h) abs(cur - lums[(y + 2) * w + x]) else 0

                if (dx > thresholdGradient || dy > thresholdGradient) {
                    // Nếu là điểm cạnh và có độ sáng thiên về giấy sáng màu
                    if (cur > avgLum * 0.75) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
        }

        // Nếu không quét được vùng rõ ràng
        if (minX >= maxX || minY >= maxY) {
            return RectF(0.04f, 0.04f, 0.96f, 0.96f)
        }

        // Thêm 2% lề an toàn (safety padding) để không vô tình cắt phạm vào chữ
        val padX = ((maxX - minX) * 0.02f).toInt()
        val padY = ((maxY - minY) * 0.02f).toInt()

        val finalLeft = max(0, minX - padX).toFloat() / w
        val finalTop = max(0, minY - padY).toFloat() / h
        val finalRight = min(w, maxX + padX).toFloat() / w
        val finalBottom = min(h, maxY + padY).toFloat() / h

        return RectF(finalLeft, finalTop, finalRight, finalBottom)
    }
}
