package com.tscanner.app.ocr.engine

import android.graphics.Point
import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import com.tscanner.app.ocr.geometry.OcrCoordinateMapper
import com.tscanner.app.ocr.model.*
import java.util.UUID

/**
 * Pure Kotlin data structures for coordinates to allow full JVM testing
 * without depending on android.jar stubs.
 */
data class MlKitPoint(val x: Int, val y: Int)

data class MlKitRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

interface MlKitElementAdapter {
    val text: String
    val boundingBox: MlKitRect?
    val cornerPoints: List<MlKitPoint>?
    val confidence: Float?
}

interface MlKitLineAdapter {
    val text: String
    val boundingBox: MlKitRect?
    val cornerPoints: List<MlKitPoint>?
    val confidence: Float?
    val elements: List<MlKitElementAdapter>
}

interface MlKitBlockAdapter {
    val text: String
    val boundingBox: MlKitRect?
    val cornerPoints: List<MlKitPoint>?
    val lines: List<MlKitLineAdapter>
}

interface MlKitTextAdapter {
    val text: String
    val textBlocks: List<MlKitBlockAdapter>
}

private fun Rect?.toMlKitRect(): MlKitRect? =
    if (this != null) MlKitRect(left, top, right, bottom) else null

private fun Array<Point>?.toMlKitPoints(): List<MlKitPoint>? =
    this?.map { MlKitPoint(it.x, it.y) }

/**
 * Adapter implementation wrapping real com.google.mlkit.vision.text.Text objects.
 */
class RealMlKitTextAdapter(private val visionText: Text) : MlKitTextAdapter {
    override val text: String get() = visionText.text
    override val textBlocks: List<MlKitBlockAdapter>
        get() = visionText.textBlocks.map { block ->
            object : MlKitBlockAdapter {
                override val text: String get() = block.text
                override val boundingBox: MlKitRect? get() = block.boundingBox.toMlKitRect()
                override val cornerPoints: List<MlKitPoint>? get() = block.cornerPoints.toMlKitPoints()
                override val lines: List<MlKitLineAdapter>
                    get() = block.lines.map { line ->
                        object : MlKitLineAdapter {
                            override val text: String get() = line.text
                            override val boundingBox: MlKitRect? get() = line.boundingBox.toMlKitRect()
                            override val cornerPoints: List<MlKitPoint>? get() = line.cornerPoints.toMlKitPoints()
                            override val confidence: Float? get() = line.confidence
                            override val elements: List<MlKitElementAdapter>
                                get() = line.elements.map { element ->
                                    object : MlKitElementAdapter {
                                        override val text: String get() = element.text
                                        override val boundingBox: MlKitRect? get() = element.boundingBox.toMlKitRect()
                                        override val cornerPoints: List<MlKitPoint>? get() = element.cornerPoints.toMlKitPoints()
                                        override val confidence: Float? get() = element.confidence
                                    }
                                }
                        }
                    }
            }
        }
}

/**
 * Maps ML Kit recognition results to structured OcrPage maintaining provenance,
 * bounding boxes, polygons, and reading order.
 *
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S05).
 */
object MlKitLayoutMapper {

    fun mapToOcrPage(
        adapter: MlKitTextAdapter,
        pageIndex: Int = 1,
        engineId: String = "mlkit_latin",
        documentLanguage: String = "en",
        bitmapWidthPx: Int = 1,
        bitmapHeightPx: Int = 1,
        imageUri: String? = null,
        coordinateMapper: OcrCoordinateMapper? = null
    ): OcrPage {
        val safeWidth = bitmapWidthPx.coerceAtLeast(1)
        val safeHeight = bitmapHeightPx.coerceAtLeast(1)

        val blocks = mutableListOf<OcrBlock>()
        var globalLineIndex = 0
        var globalTokenIndex = 0

        for ((bIdx, block) in adapter.textBlocks.withIndex()) {
            val lines = mutableListOf<OcrLine>()

            for (line in block.lines) {
                val tokens = mutableListOf<OcrToken>()

                for (elem in line.elements) {
                    val tokenPolygon = mapCornerPoints(
                        points = elem.cornerPoints,
                        bounds = elem.boundingBox,
                        bitmapWidth = safeWidth,
                        bitmapHeight = safeHeight,
                        mapper = coordinateMapper
                    )

                    tokens.add(
                        OcrToken(
                            tokenId = "tok_${pageIndex}_$globalTokenIndex",
                            text = elem.text,
                            polygon = tokenPolygon,
                            confidence = elem.confidence
                        )
                    )
                    globalTokenIndex++
                }

                val linePolygon = mapCornerPoints(
                    points = line.cornerPoints,
                    bounds = line.boundingBox,
                    bitmapWidth = safeWidth,
                    bitmapHeight = safeHeight,
                    mapper = coordinateMapper
                )

                val lineBox = mapBoundingBox(
                    bounds = line.boundingBox,
                    bitmapWidth = safeWidth,
                    bitmapHeight = safeHeight,
                    mapper = coordinateMapper
                )

                lines.add(
                    OcrLine(
                        lineId = "line_${pageIndex}_$globalLineIndex",
                        text = line.text,
                        polygon = linePolygon,
                        boundingBox = lineBox,
                        confidence = line.confidence,
                        tokens = tokens
                    )
                )
                globalLineIndex++
            }

            val blockBox = mapBoundingBox(
                bounds = block.boundingBox,
                bitmapWidth = safeWidth,
                bitmapHeight = safeHeight,
                mapper = coordinateMapper
            )

            blocks.add(
                OcrBlock(
                    blockId = "blk_${pageIndex}_$bIdx",
                    boundingBox = blockBox,
                    lines = lines
                )
            )
        }

        val status = if (adapter.text.isBlank() && blocks.isEmpty()) {
            OcrPageStatus.NO_TEXT
        } else {
            OcrPageStatus.SUCCESS
        }

        val imageInfo = if (imageUri != null || bitmapWidthPx > 1 || bitmapHeightPx > 1) {
            OcrImageInfo(
                localUri = imageUri ?: "",
                widthPx = safeWidth,
                heightPx = safeHeight,
                rotationDegrees = coordinateMapper?.config?.normalizedRotation ?: 0
            )
        } else {
            null
        }

        return OcrPage(
            pageId = "page_${pageIndex}_${UUID.randomUUID().toString().take(8)}",
            pageIndex = pageIndex,
            status = status,
            imageInfo = imageInfo,
            engineId = engineId,
            sourceLanguage = documentLanguage,
            sourceBlocks = blocks
        )
    }

    fun mapVisionTextToOcrPage(
        visionText: Text,
        pageIndex: Int = 1,
        engineId: String = "mlkit_latin",
        documentLanguage: String = "en",
        bitmapWidthPx: Int = 1,
        bitmapHeightPx: Int = 1,
        imageUri: String? = null,
        coordinateMapper: OcrCoordinateMapper? = null
    ): OcrPage {
        return mapToOcrPage(
            adapter = RealMlKitTextAdapter(visionText),
            pageIndex = pageIndex,
            engineId = engineId,
            documentLanguage = documentLanguage,
            bitmapWidthPx = bitmapWidthPx,
            bitmapHeightPx = bitmapHeightPx,
            imageUri = imageUri,
            coordinateMapper = coordinateMapper
        )
    }

    private fun mapCornerPoints(
        points: List<MlKitPoint>?,
        bounds: MlKitRect?,
        bitmapWidth: Int,
        bitmapHeight: Int,
        mapper: OcrCoordinateMapper?
    ): OcrPolygon? {
        if (points != null && points.size >= 4) {
            val pts = points.map { pt ->
                if (mapper != null) {
                    mapper.mapOcrPixelToNormalized(OcrPoint(pt.x.toFloat(), pt.y.toFloat()))
                } else {
                    OcrPoint(
                        x = (pt.x.toFloat() / bitmapWidth.toFloat()).coerceIn(0f, 1f),
                        y = (pt.y.toFloat() / bitmapHeight.toFloat()).coerceIn(0f, 1f)
                    )
                }
            }
            return OcrPolygon(pts)
        }

        if (bounds != null) {
            val l = bounds.left.toFloat()
            val t = bounds.top.toFloat()
            val r = bounds.right.toFloat()
            val b = bounds.bottom.toFloat()

            val rawCorners = listOf(
                OcrPoint(l, t),
                OcrPoint(r, t),
                OcrPoint(r, b),
                OcrPoint(l, b)
            )

            val pts = rawCorners.map { pt ->
                if (mapper != null) {
                    mapper.mapOcrPixelToNormalized(pt)
                } else {
                    OcrPoint(
                        x = (pt.x / bitmapWidth.toFloat()).coerceIn(0f, 1f),
                        y = (pt.y / bitmapHeight.toFloat()).coerceIn(0f, 1f)
                    )
                }
            }
            return OcrPolygon(pts)
        }

        return null
    }

    private fun mapBoundingBox(
        bounds: MlKitRect?,
        bitmapWidth: Int,
        bitmapHeight: Int,
        mapper: OcrCoordinateMapper?
    ): OcrRect? {
        if (bounds == null) return null
        val rawRect = OcrRect(
            left = bounds.left.toFloat(),
            top = bounds.top.toFloat(),
            right = bounds.right.toFloat(),
            bottom = bounds.bottom.toFloat()
        )

        return if (mapper != null) {
            mapper.mapOcrRectToNormalized(rawRect)
        } else {
            OcrRect(
                left = (rawRect.left / bitmapWidth.toFloat()).coerceIn(0f, 1f),
                top = (rawRect.top / bitmapHeight.toFloat()).coerceIn(0f, 1f),
                right = (rawRect.right / bitmapWidth.toFloat()).coerceIn(0f, 1f),
                bottom = (rawRect.bottom / bitmapHeight.toFloat()).coerceIn(0f, 1f)
            )
        }
    }
}
