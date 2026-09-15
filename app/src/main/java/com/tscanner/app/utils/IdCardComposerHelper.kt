package com.tscanner.app.utils

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.exifinterface.media.ExifInterface
import androidx.print.PrintHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

enum class IdCardLayoutMode {
    A4_PORTRAIT_TWO_SIDES,  // Mặt trước trên, Mặt sau dưới (Chuẩn hồ sơ)
    A4_LANDSCAPE_TWO_SIDES, // Mặt trước trái, Mặt sau phải
    A4_PORTRAIT_SINGLE_SIDE // Chỉ 1 mặt ở giữa
}

enum class IdCardScaleMode {
    ACTUAL_SIZE, // Chuẩn tỉ lệ thật 1:1 (Thẻ 85.60 x 53.98 mm trên khổ A4 210 x 297 mm)
    FIT_PAGE     // Phóng to vừa trang (~1.5x) dễ đọc trên điện thoại
}

data class IdCardComposeConfig(
    val layoutMode: IdCardLayoutMode = IdCardLayoutMode.A4_PORTRAIT_TWO_SIDES,
    val scaleMode: IdCardScaleMode = IdCardScaleMode.ACTUAL_SIZE,
    val showCutBorder: Boolean = true,
    val addWatermark: Boolean = true,
    val watermarkText: String = WatermarkHelper.WATERMARK_TEXT
)

object IdCardComposerHelper {

    // Standard ISO 216 A4 dimensions at 300 DPI
    const val A4_WIDTH_300DPI = 2480
    const val A4_HEIGHT_300DPI = 3508

    // Screen preview dimensions (150 DPI for fast 60fps rendering in UI)
    const val A4_PREVIEW_WIDTH = 1240
    const val A4_PREVIEW_HEIGHT = 1754

    // Standard ISO/IEC 7810 ID-1 card aspect ratio (85.60 mm / 53.98 mm = 1.58577)
    const val CARD_ASPECT_RATIO = 85.60f / 53.98f

    // Card width percentage relative to A4 portrait width
    // 85.60 mm / 210 mm = 0.4076 (approx 40.8%)
    const val ACTUAL_WIDTH_RATIO = 85.60f / 210.0f
    const val FIT_PAGE_WIDTH_RATIO = 0.65f

    /**
     * Renders an A4 canvas containing front and back ID cards according to configuration.
     */
    fun renderA4Bitmap(
        frontBmp: Bitmap?,
        backBmp: Bitmap?,
        config: IdCardComposeConfig,
        isHighRes: Boolean = false
    ): Bitmap {
        val isLandscape = config.layoutMode == IdCardLayoutMode.A4_LANDSCAPE_TWO_SIDES

        val (canvasW, canvasH) = if (isHighRes) {
            if (isLandscape) Pair(A4_HEIGHT_300DPI, A4_WIDTH_300DPI)
            else Pair(A4_WIDTH_300DPI, A4_HEIGHT_300DPI)
        } else {
            if (isLandscape) Pair(A4_PREVIEW_HEIGHT, A4_PREVIEW_WIDTH)
            else Pair(A4_PREVIEW_WIDTH, A4_PREVIEW_HEIGHT)
        }

        val output = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)

        val widthRatio = if (config.scaleMode == IdCardScaleMode.ACTUAL_SIZE) {
            if (isLandscape) ACTUAL_WIDTH_RATIO * (210f / 297f) else ACTUAL_WIDTH_RATIO
        } else {
            if (isLandscape) 0.42f else FIT_PAGE_WIDTH_RATIO
        }

        val cardWidth = (canvasW * widthRatio)
        val cardHeight = (cardWidth / CARD_ASPECT_RATIO)
        val cornerRadius = cardHeight * 0.055f // ID card corner radius ~3.18mm

        val (frontRect, backRect) = calculateCardPlacements(
            canvasW, canvasH, cardWidth, cardHeight, config.layoutMode
        )

        // Draw Front Card
        if (frontBmp != null && frontRect != null) {
            drawCardOnCanvas(canvas, frontBmp, frontRect, cornerRadius, config.showCutBorder)
        } else if (frontRect != null) {
            drawEmptyCardSlot(canvas, frontRect, cornerRadius, "MẶT TRƯỚC")
        }

        // Draw Back Card (if applicable)
        if (config.layoutMode != IdCardLayoutMode.A4_PORTRAIT_SINGLE_SIDE && backRect != null) {
            if (backBmp != null) {
                drawCardOnCanvas(canvas, backBmp, backRect, cornerRadius, config.showCutBorder)
            } else {
                drawEmptyCardSlot(canvas, backRect, cornerRadius, "MẶT SAU")
            }
        }

        // Draw "T-Scanner" watermark at bottom-right corner of the A4 canvas (Free tier only; VIP tier clean)
        if (config.addWatermark) {
            WatermarkHelper.drawBottomRightWatermark(
                canvas = canvas,
                pageWidth = canvasW.toFloat(),
                pageHeight = canvasH.toFloat(),
                text = config.watermarkText
            )
        }

        return output
    }

    private fun calculateCardPlacements(
        canvasW: Int,
        canvasH: Int,
        cardW: Float,
        cardH: Float,
        layoutMode: IdCardLayoutMode
    ): Pair<RectF?, RectF?> {
        return when (layoutMode) {
            IdCardLayoutMode.A4_PORTRAIT_TWO_SIDES -> {
                val left = (canvasW - cardW) / 2f
                val top1 = canvasH * 0.25f - (cardH / 2f)
                val top2 = canvasH * 0.65f - (cardH / 2f)
                val frontRect = RectF(left, top1, left + cardW, top1 + cardH)
                val backRect = RectF(left, top2, left + cardW, top2 + cardH)
                Pair(frontRect, backRect)
            }
            IdCardLayoutMode.A4_LANDSCAPE_TWO_SIDES -> {
                val centerY = canvasH / 2f
                val top = centerY - (cardH / 2f)
                val centerX1 = canvasW * 0.28f
                val centerX2 = canvasW * 0.72f
                val frontRect = RectF(centerX1 - (cardW / 2f), top, centerX1 + (cardW / 2f), top + cardH)
                val backRect = RectF(centerX2 - (cardW / 2f), top, centerX2 + (cardW / 2f), top + cardH)
                Pair(frontRect, backRect)
            }
            IdCardLayoutMode.A4_PORTRAIT_SINGLE_SIDE -> {
                val left = (canvasW - cardW) / 2f
                val top = (canvasH - cardH) / 2f
                val frontRect = RectF(left, top, left + cardW, top + cardH)
                Pair(frontRect, null)
            }
        }
    }

    private fun drawCardOnCanvas(
        canvas: Canvas,
        bitmap: Bitmap,
        destRect: RectF,
        cornerRadius: Float,
        showBorder: Boolean
    ) {
        val saveCount = canvas.save()

        // Clip rounded rectangle for smooth physical card corners
        val clipPath = Path().apply {
            addRoundRect(destRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }
        canvas.clipPath(clipPath)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
        canvas.drawBitmap(bitmap, srcRect, destRect, paint)

        canvas.restoreToCount(saveCount)

        // Draw card border
        if (showBorder) {
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.parseColor("#B8B8B8")
                strokeWidth = (destRect.width() * 0.003f).coerceAtLeast(2f)
            }
            canvas.drawRoundRect(destRect, cornerRadius, cornerRadius, borderPaint)
        }
    }

    private fun drawEmptyCardSlot(
        canvas: Canvas,
        rect: RectF,
        cornerRadius: Float,
        label: String
    ) {
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#9E9E9E")
            strokeWidth = (rect.width() * 0.004f).coerceAtLeast(3f)
            pathEffect = DashPathEffect(floatArrayOf(20f, 15f), 0f)
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#757575")
            textSize = rect.height() * 0.12f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val yPos = rect.centerY() - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(label, rect.centerX(), yPos, textPaint)
    }

    /**
     * Creates a standard A4 PDF document containing the composed ID card page.
     */
    suspend fun createA4Pdf(
        frontBmp: Bitmap?,
        backBmp: Bitmap?,
        config: IdCardComposeConfig,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()

            val highResBitmap = renderA4Bitmap(frontBmp, backBmp, config, isHighRes = true)

            // PDF standard dimensions in points (72 pt per inch)
            val isLandscape = config.layoutMode == IdCardLayoutMode.A4_LANDSCAPE_TWO_SIDES
            val pageW = if (isLandscape) 842 else 595
            val pageH = if (isLandscape) 595 else 842

            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val srcRect = Rect(0, 0, highResBitmap.width, highResBitmap.height)
            val destRect = Rect(0, 0, pageW, pageH)
            canvas.drawBitmap(highResBitmap, srcRect, destRect, paint)

            pdfDocument.finishPage(page)

            FileOutputStream(outputFile).use { out ->
                pdfDocument.writeTo(out)
            }
            pdfDocument.close()
            highResBitmap.recycle()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Saves a 300 DPI high-resolution JPEG of the A4 composed card.
     */
    suspend fun saveA4Image(
        frontBmp: Bitmap?,
        backBmp: Bitmap?,
        config: IdCardComposeConfig,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()

            val highResBitmap = renderA4Bitmap(frontBmp, backBmp, config, isHighRes = true)
            FileOutputStream(outputFile).use { out ->
                highResBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            highResBitmap.recycle()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Prints the document directly to a Wi-Fi or connected printer via Android PrintHelper.
     */
    fun printDocument(activity: Activity, a4Bitmap: Bitmap, jobName: String = "TScanner_IDCard_A4") {
        val printHelper = PrintHelper(activity).apply {
            scaleMode = PrintHelper.SCALE_MODE_FIT
        }
        printHelper.printBitmap(jobName, a4Bitmap)
    }

    /**
     * Safely decodes a bitmap from file with inSampleSize to avoid OOM.
     */
    fun decodeSampledBitmap(path: String, reqWidth: Int = 2000, reqHeight: Int = 2000): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, options)

            var sampleSize = 1
            if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while (halfHeight / sampleSize >= reqHeight && halfWidth / sampleSize >= reqWidth) {
                    sampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeFile(path, decodeOptions) ?: return null

            val exif = ExifInterface(path)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rotationDegrees != 0f) {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) bitmap.recycle()
                rotated
            } else {
                bitmap
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
