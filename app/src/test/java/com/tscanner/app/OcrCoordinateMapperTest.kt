package com.tscanner.app

import com.tscanner.app.ocr.geometry.OcrCoordinateMapper
import com.tscanner.app.ocr.geometry.PageTransformConfig
import com.tscanner.app.ocr.model.OcrPoint
import com.tscanner.app.ocr.model.OcrPolygon
import com.tscanner.app.ocr.model.OcrRect
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for OcrCoordinateMapper:
 * - Non-uniform scaling
 * - Rotations (0, 90, 180, 270)
 * - Corner mappings
 * - Roundtrip precision (<= 1.0 pixel)
 * - Degenerate matrix and invalid dimension validation
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S04).
 */
class OcrCoordinateMapperTest {

    @Test
    fun testNonUniformAspectScale() {
        val config = PageTransformConfig(
            sourceWidthPx = 1200,
            sourceHeightPx = 1800,
            cropRect = OcrRect(0f, 0f, 1f, 1f),
            rotationDegrees = 0,
            ocrBitmapWidthPx = 960,
            ocrBitmapHeightPx = 960
        )
        val mapper = OcrCoordinateMapper(config)

        // Center point in OCR space (480, 480)
        val ocrCenter = OcrPoint(480f, 480f)
        val pageCenter = mapper.mapOcrPixelToPagePixel(ocrCenter)

        // ScaleX = 1200 / 960 = 1.25 -> 480 * 1.25 = 600
        // ScaleY = 1800 / 960 = 1.875 -> 480 * 1.875 = 900
        assertEquals(600f, pageCenter.x, 0.001f)
        assertEquals(900f, pageCenter.y, 0.001f)

        // Normalized space should both be 0.5
        val norm = mapper.mapOcrPixelToNormalized(ocrCenter)
        assertEquals(0.5f, norm.x, 0.001f)
        assertEquals(0.5f, norm.y, 0.001f)
    }

    @Test
    fun testPixelRoundtripPrecisionUnderOnePixel() {
        val config = PageTransformConfig(
            sourceWidthPx = 2480,
            sourceHeightPx = 3508,
            cropRect = OcrRect(0.05f, 0.05f, 0.95f, 0.95f),
            rotationDegrees = 90,
            ocrBitmapWidthPx = 1024,
            ocrBitmapHeightPx = 1024
        )
        val mapper = OcrCoordinateMapper(config)

        // Sample 100 points across the page
        for (stepX in 0..10) {
            for (stepY in 0..10) {
                val origX = stepX * (mapper.config.pageWidthPx / 10f)
                val origY = stepY * (mapper.config.pageHeightPx / 10f)
                val originalPagePoint = OcrPoint(origX, origY)

                val ocrPoint = mapper.mapPagePixelToOcrPixel(originalPagePoint)
                val roundtripPagePoint = mapper.mapOcrPixelToPagePixel(ocrPoint)

                val errX = abs(originalPagePoint.x - roundtripPagePoint.x)
                val errY = abs(originalPagePoint.y - roundtripPagePoint.y)

                assertTrue("Roundtrip X error ($errX px) must be <= 1.0 px", errX <= 1.0f)
                assertTrue("Roundtrip Y error ($errY px) must be <= 1.0 px", errY <= 1.0f)
            }
        }
    }

    @Test
    fun testNormalizedCornerMappingsAcrossRotations() {
        // 1. 0 degrees: (0,0) -> (0,0); (1,1) -> (1,1)
        val mapper0 = OcrCoordinateMapper(
            PageTransformConfig(1000, 1000, OcrRect(0f, 0f, 1f, 1f), 0, 1000, 1000)
        )
        val p0 = mapper0.mapNormalizedPageToSource(OcrPoint(0f, 0f))
        assertEquals(0f, p0.x, 0.001f)
        assertEquals(0f, p0.y, 0.001f)

        // 2. 90 degrees clockwise: Top-left of rotated page (0,0) comes from source (0, 1)
        val mapper90 = OcrCoordinateMapper(
            PageTransformConfig(1000, 2000, OcrRect(0f, 0f, 1f, 1f), 90, 2000, 1000)
        )
        val p90 = mapper90.mapNormalizedPageToSource(OcrPoint(0f, 0f))
        assertEquals(0f, p90.x, 0.001f)
        assertEquals(1f, p90.y, 0.001f)

        // 3. 180 degrees: Top-left of rotated page (0,0) comes from source (1, 1)
        val mapper180 = OcrCoordinateMapper(
            PageTransformConfig(1000, 1000, OcrRect(0f, 0f, 1f, 1f), 180, 1000, 1000)
        )
        val p180 = mapper180.mapNormalizedPageToSource(OcrPoint(0f, 0f))
        assertEquals(1f, p180.x, 0.001f)
        assertEquals(1f, p180.y, 0.001f)

        // 4. 270 degrees clockwise: Top-left of rotated page (0,0) comes from source (1, 0)
        val mapper270 = OcrCoordinateMapper(
            PageTransformConfig(1000, 2000, OcrRect(0f, 0f, 1f, 1f), 270, 2000, 1000)
        )
        val p270 = mapper270.mapNormalizedPageToSource(OcrPoint(0f, 0f))
        assertEquals(1f, p270.x, 0.001f)
        assertEquals(0f, p270.y, 0.001f)
    }

    @Test
    fun testCropMappingRoundtrip() {
        val config = PageTransformConfig(
            sourceWidthPx = 1000,
            sourceHeightPx = 1000,
            cropRect = OcrRect(0.2f, 0.3f, 0.8f, 0.7f), // width=0.6, height=0.4
            rotationDegrees = 0,
            ocrBitmapWidthPx = 600,
            ocrBitmapHeightPx = 400
        )
        val mapper = OcrCoordinateMapper(config)

        // Point at center of cropped page (0.5, 0.5)
        val centerPage = OcrPoint(0.5f, 0.5f)
        val centerSource = mapper.mapNormalizedPageToSource(centerPage)
        // Expected source: 0.2 + 0.5*0.6 = 0.5, 0.3 + 0.5*0.4 = 0.5
        assertEquals(0.5f, centerSource.x, 0.001f)
        assertEquals(0.5f, centerSource.y, 0.001f)

        // Invert back
        val roundtrip = mapper.mapSourceToNormalizedPage(centerSource)
        assertEquals(0.5f, roundtrip.x, 0.001f)
        assertEquals(0.5f, roundtrip.y, 0.001f)
    }

    @Test
    fun testPolygonAndRectMapping() {
        val config = PageTransformConfig(
            sourceWidthPx = 1000,
            sourceHeightPx = 1000,
            cropRect = OcrRect(0f, 0f, 1f, 1f),
            rotationDegrees = 0,
            ocrBitmapWidthPx = 500,
            ocrBitmapHeightPx = 500
        )
        val mapper = OcrCoordinateMapper(config)

        val poly = OcrPolygon(
            listOf(
                OcrPoint(100f, 100f),
                OcrPoint(200f, 100f),
                OcrPoint(200f, 200f),
                OcrPoint(100f, 200f)
            )
        )
        val mappedPoly = mapper.mapOcrPolygonToPagePolygon(poly)
        assertEquals(200f, mappedPoly.points[0].x, 0.001f)
        assertEquals(200f, mappedPoly.points[0].y, 0.001f)
        assertEquals(400f, mappedPoly.points[2].x, 0.001f)
        assertEquals(400f, mappedPoly.points[2].y, 0.001f)

        val rect = OcrRect(100f, 100f, 200f, 200f)
        val mappedRect = mapper.mapOcrRectToPageRect(rect)
        assertEquals(200f, mappedRect.left, 0.001f)
        assertEquals(200f, mappedRect.top, 0.001f)
        assertEquals(400f, mappedRect.right, 0.001f)
        assertEquals(400f, mappedRect.bottom, 0.001f)
    }

    @Test
    fun testDegenerateMatrixAndDimensionsThrowErrors() {
        // Zero source width
        assertThrows(IllegalArgumentException::class.java) {
            PageTransformConfig(0, 1000, OcrRect(0f, 0f, 1f, 1f), 0, 500, 500)
        }

        // Zero OCR bitmap height
        assertThrows(IllegalArgumentException::class.java) {
            PageTransformConfig(1000, 1000, OcrRect(0f, 0f, 1f, 1f), 0, 500, 0)
        }

        // Degenerate crop rect with 0 width
        assertThrows(IllegalArgumentException::class.java) {
            PageTransformConfig(1000, 1000, OcrRect(0.5f, 0.1f, 0.5f, 0.9f), 0, 500, 500)
        }

        // Non-multiple of 90 rotation
        assertThrows(IllegalArgumentException::class.java) {
            PageTransformConfig(1000, 1000, OcrRect(0f, 0f, 1f, 1f), 45, 500, 500)
        }
    }
}
