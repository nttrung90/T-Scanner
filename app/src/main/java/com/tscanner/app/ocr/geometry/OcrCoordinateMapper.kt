package com.tscanner.app.ocr.geometry

import com.tscanner.app.ocr.model.OcrPoint
import com.tscanner.app.ocr.model.OcrPolygon
import com.tscanner.app.ocr.model.OcrRect
import kotlin.math.max
import kotlin.math.min

/**
 * Geometric configuration specifying dimensions and transformations
 * between raw source image, cropped/oriented page, and OCR inference bitmap.
 */
data class PageTransformConfig(
    val sourceWidthPx: Int,
    val sourceHeightPx: Int,
    val cropRect: OcrRect = OcrRect(0f, 0f, 1f, 1f),
    val rotationDegrees: Int = 0,
    val ocrBitmapWidthPx: Int,
    val ocrBitmapHeightPx: Int
) {
    init {
        require(sourceWidthPx > 0 && sourceHeightPx > 0) {
            "Source dimensions must be positive ($sourceWidthPx x $sourceHeightPx)"
        }
        require(ocrBitmapWidthPx > 0 && ocrBitmapHeightPx > 0) {
            "OCR bitmap dimensions must be positive ($ocrBitmapWidthPx x $ocrBitmapHeightPx)"
        }
        require(cropRect.width > 0f && cropRect.height > 0f) {
            "Crop rectangle must have positive non-zero area (width=${cropRect.width}, height=${cropRect.height})"
        }
        val normalizedRotation = normalizeRotation(rotationDegrees)
        require(normalizedRotation in setOf(0, 90, 180, 270)) {
            "Rotation must be multiple of 90 degrees: $rotationDegrees"
        }
    }

    companion object {
        fun normalizeRotation(deg: Int): Int {
            val rem = deg % 360
            return if (rem < 0) rem + 360 else rem
        }
    }

    val normalizedRotation: Int get() = normalizeRotation(rotationDegrees)

    /**
     * Unrotated cropped width and height in source pixels.
     */
    val unrotatedCropWidthPx: Float get() = cropRect.width * sourceWidthPx
    val unrotatedCropHeightPx: Float get() = cropRect.height * sourceHeightPx

    /**
     * Oriented page width and height in display/page pixel space.
     */
    val pageWidthPx: Float
        get() = if (normalizedRotation == 90 || normalizedRotation == 270) {
            unrotatedCropHeightPx
        } else {
            unrotatedCropWidthPx
        }

    val pageHeightPx: Float
        get() = if (normalizedRotation == 90 || normalizedRotation == 270) {
            unrotatedCropWidthPx
        } else {
            unrotatedCropHeightPx
        }
}

/**
 * High-precision coordinate mapper between OCR bitmap pixels, oriented page pixels,
 * normalized [0..1] coordinates, and source uncropped coordinates.
 *
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S04).
 */
class OcrCoordinateMapper(
    val config: PageTransformConfig
) {
    val scaleX: Float = config.pageWidthPx / config.ocrBitmapWidthPx.toFloat()
    val scaleY: Float = config.pageHeightPx / config.ocrBitmapHeightPx.toFloat()

    init {
        require(scaleX > 0f && !scaleX.isNaN() && !scaleX.isInfinite()) {
            "Degenerate horizontal scale factor: $scaleX"
        }
        require(scaleY > 0f && !scaleY.isNaN() && !scaleY.isInfinite()) {
            "Degenerate vertical scale factor: $scaleY"
        }
    }

    /**
     * Maps a pixel point from OCR bitmap space to oriented page pixel space.
     */
    fun mapOcrPixelToPagePixel(point: OcrPoint): OcrPoint {
        return OcrPoint(
            x = point.x * scaleX,
            y = point.y * scaleY
        )
    }

    /**
     * Maps a pixel point from oriented page pixel space to OCR bitmap pixel space.
     */
    fun mapPagePixelToOcrPixel(point: OcrPoint): OcrPoint {
        return OcrPoint(
            x = point.x / scaleX,
            y = point.y / scaleY
        )
    }

    /**
     * Maps a pixel point from OCR bitmap space directly to normalized [0.0..1.0] page space.
     */
    fun mapOcrPixelToNormalized(point: OcrPoint): OcrPoint {
        return OcrPoint(
            x = (point.x / config.ocrBitmapWidthPx.toFloat()).coerceIn(0f, 1f),
            y = (point.y / config.ocrBitmapHeightPx.toFloat()).coerceIn(0f, 1f)
        )
    }

    /**
     * Maps a normalized [0.0..1.0] page point to OCR bitmap pixel space.
     */
    fun mapNormalizedToOcrPixel(point: OcrPoint): OcrPoint {
        return OcrPoint(
            x = point.x * config.ocrBitmapWidthPx.toFloat(),
            y = point.y * config.ocrBitmapHeightPx.toFloat()
        )
    }

    /**
     * Maps an oriented page pixel point to normalized [0.0..1.0] page space.
     */
    fun mapPagePixelToNormalized(point: OcrPoint): OcrPoint {
        return OcrPoint(
            x = (point.x / config.pageWidthPx).coerceIn(0f, 1f),
            y = (point.y / config.pageHeightPx).coerceIn(0f, 1f)
        )
    }

    /**
     * Maps a normalized [0.0..1.0] page point to oriented page pixel space.
     */
    fun mapNormalizedToPagePixel(point: OcrPoint): OcrPoint {
        return OcrPoint(
            x = point.x * config.pageWidthPx,
            y = point.y * config.pageHeightPx
        )
    }

    /**
     * Maps a normalized point on the oriented page [0..1] back to the uncropped source image [0..1].
     */
    fun mapNormalizedPageToSource(point: OcrPoint): OcrPoint {
        val pX = point.x.coerceIn(0f, 1f)
        val pY = point.y.coerceIn(0f, 1f)

        // 1. Invert rotation: from oriented page [0..1] to unrotated crop [0..1]
        val (cropX, cropY) = when (config.normalizedRotation) {
            90 -> Pair(pY, 1f - pX)
            180 -> Pair(1f - pX, 1f - pY)
            270 -> Pair(1f - pY, pX)
            else -> Pair(pX, pY)
        }

        // 2. Invert crop: from unrotated crop [0..1] to full source [0..1]
        val sourceX = config.cropRect.left + cropX * config.cropRect.width
        val sourceY = config.cropRect.top + cropY * config.cropRect.height

        return OcrPoint(
            x = sourceX.coerceIn(0f, 1f),
            y = sourceY.coerceIn(0f, 1f)
        )
    }

    /**
     * Maps a normalized source image point [0..1] to normalized oriented page space [0..1].
     */
    fun mapSourceToNormalizedPage(point: OcrPoint): OcrPoint {
        val sX = point.x.coerceIn(0f, 1f)
        val sY = point.y.coerceIn(0f, 1f)

        // 1. Forward crop: from full source [0..1] to unrotated crop [0..1]
        val cropX = ((sX - config.cropRect.left) / config.cropRect.width).coerceIn(0f, 1f)
        val cropY = ((sY - config.cropRect.top) / config.cropRect.height).coerceIn(0f, 1f)

        // 2. Forward rotation: from unrotated crop [0..1] to oriented page [0..1]
        val (pX, pY) = when (config.normalizedRotation) {
            90 -> Pair(1f - cropY, cropX)
            180 -> Pair(1f - cropX, 1f - cropY)
            270 -> Pair(cropY, 1f - cropX)
            else -> Pair(cropX, cropY)
        }

        return OcrPoint(x = pX.coerceIn(0f, 1f), y = pY.coerceIn(0f, 1f))
    }

    /**
     * Maps an entire polygon from OCR pixel space to oriented page pixel space.
     */
    fun mapOcrPolygonToPagePolygon(polygon: OcrPolygon): OcrPolygon {
        return OcrPolygon(polygon.points.map { mapOcrPixelToPagePixel(it) })
    }

    /**
     * Maps an entire polygon from OCR pixel space to normalized [0..1] page space.
     */
    fun mapOcrPolygonToNormalized(polygon: OcrPolygon): OcrPolygon {
        return OcrPolygon(polygon.points.map { mapOcrPixelToNormalized(it) })
    }

    /**
     * Maps a rectangle from OCR pixel space to oriented page pixel space.
     */
    fun mapOcrRectToPageRect(rect: OcrRect): OcrRect {
        val p1 = mapOcrPixelToPagePixel(OcrPoint(rect.left, rect.top))
        val p2 = mapOcrPixelToPagePixel(OcrPoint(rect.right, rect.bottom))
        return OcrRect(
            left = min(p1.x, p2.x),
            top = min(p1.y, p2.y),
            right = max(p1.x, p2.x),
            bottom = max(p1.y, p2.y)
        )
    }

    /**
     * Maps a rectangle from OCR pixel space to normalized [0..1] page space.
     */
    fun mapOcrRectToNormalized(rect: OcrRect): OcrRect {
        val p1 = mapOcrPixelToNormalized(OcrPoint(rect.left, rect.top))
        val p2 = mapOcrPixelToNormalized(OcrPoint(rect.right, rect.bottom))
        return OcrRect(
            left = min(p1.x, p2.x),
            top = min(p1.y, p2.y),
            right = max(p1.x, p2.x),
            bottom = max(p1.y, p2.y)
        )
    }
}
