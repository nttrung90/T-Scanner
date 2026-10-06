package com.tscanner.app

import com.tscanner.app.ocr.engine.*
import com.tscanner.app.ocr.geometry.OcrCoordinateMapper
import com.tscanner.app.ocr.geometry.PageTransformConfig
import com.tscanner.app.ocr.model.OcrPageStatus
import com.tscanner.app.ocr.model.OcrRect
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for MlKitLayoutMapper:
 * - Block / Line / Element token mapping
 * - Polygon geometry preservation
 * - Reading order preservation
 * - Empty text handling (NO_TEXT status)
 * - Coordinate mapping integration
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S05).
 */
class MlKitLayoutMapperTest {

    private class MockElement(
        override val text: String,
        override val boundingBox: MlKitRect? = null,
        override val cornerPoints: List<MlKitPoint>? = null,
        override val confidence: Float? = 0.95f
    ) : MlKitElementAdapter

    private class MockLine(
        override val text: String,
        override val boundingBox: MlKitRect? = null,
        override val cornerPoints: List<MlKitPoint>? = null,
        override val confidence: Float? = 0.95f,
        override val elements: List<MlKitElementAdapter> = emptyList()
    ) : MlKitLineAdapter

    private class MockBlock(
        override val text: String,
        override val boundingBox: MlKitRect? = null,
        override val cornerPoints: List<MlKitPoint>? = null,
        override val lines: List<MlKitLineAdapter> = emptyList()
    ) : MlKitBlockAdapter

    private class MockText(
        override val text: String,
        override val textBlocks: List<MlKitBlockAdapter>
    ) : MlKitTextAdapter

    @Test
    fun testLayoutHierarchyAndPolygonMapping() {
        val elem1 = MockElement(
            text = "HỢP",
            boundingBox = MlKitRect(100, 200, 250, 260),
            cornerPoints = listOf(MlKitPoint(100, 200), MlKitPoint(250, 200), MlKitPoint(250, 260), MlKitPoint(100, 260)),
            confidence = 0.99f
        )
        val elem2 = MockElement(
            text = "ĐỒNG",
            boundingBox = MlKitRect(260, 200, 450, 260),
            cornerPoints = listOf(MlKitPoint(260, 200), MlKitPoint(450, 200), MlKitPoint(450, 260), MlKitPoint(260, 260)),
            confidence = 0.98f
        )
        val line1 = MockLine(
            text = "HỢP ĐỒNG",
            boundingBox = MlKitRect(100, 200, 450, 260),
            cornerPoints = listOf(MlKitPoint(100, 200), MlKitPoint(450, 200), MlKitPoint(450, 260), MlKitPoint(100, 260)),
            confidence = 0.985f,
            elements = listOf(elem1, elem2)
        )
        val block1 = MockBlock(
            text = "HỢP ĐỒNG",
            boundingBox = MlKitRect(100, 200, 450, 260),
            cornerPoints = listOf(MlKitPoint(100, 200), MlKitPoint(450, 200), MlKitPoint(450, 260), MlKitPoint(100, 260)),
            lines = listOf(line1)
        )
        val mockText = MockText(
            text = "HỢP ĐỒNG",
            textBlocks = listOf(block1)
        )

        val page = MlKitLayoutMapper.mapToOcrPage(
            adapter = mockText,
            pageIndex = 1,
            engineId = "mlkit_latin",
            documentLanguage = "vi",
            bitmapWidthPx = 1000,
            bitmapHeightPx = 2000
        )

        assertEquals("page_1_", page.pageId.take(7))
        assertEquals(1, page.pageIndex)
        assertEquals(OcrPageStatus.SUCCESS, page.status)
        assertEquals("mlkit_latin", page.engineId)
        assertEquals("vi", page.sourceLanguage)
        assertEquals(1, page.sourceBlocks.size)

        val block = page.sourceBlocks[0]
        assertEquals("blk_1_0", block.blockId)
        assertEquals(1, block.lines.size)

        val line = block.lines[0]
        assertEquals("line_1_0", line.lineId)
        assertEquals("HỢP ĐỒNG", line.text)
        assertNotNull(line.polygon)
        assertEquals(4, line.polygon!!.points.size)

        // Coordinates normalized by width 1000 and height 2000:
        // Point(100, 200) -> (0.1, 0.1)
        assertEquals(0.1f, line.polygon!!.points[0].x, 0.001f)
        assertEquals(0.1f, line.polygon!!.points[0].y, 0.001f)
        // Point(450, 260) -> (0.45, 0.13)
        assertEquals(0.45f, line.polygon!!.points[2].x, 0.001f)
        assertEquals(0.13f, line.polygon!!.points[2].y, 0.001f)

        // Verify tokens
        assertEquals(2, line.tokens.size)
        assertEquals("tok_1_0", line.tokens[0].tokenId)
        assertEquals("HỢP", line.tokens[0].text)
        assertEquals(0.99f, line.tokens[0].confidence ?: 0f, 0.001f)

        assertEquals("tok_1_1", line.tokens[1].tokenId)
        assertEquals("ĐỒNG", line.tokens[1].text)
        assertEquals(0.98f, line.tokens[1].confidence ?: 0f, 0.001f)
    }

    @Test
    fun testEmptyVisionTextReturnsNoTextStatus() {
        val emptyMock = MockText(
            text = "   ",
            textBlocks = emptyList()
        )

        val page = MlKitLayoutMapper.mapToOcrPage(
            adapter = emptyMock,
            pageIndex = 2,
            engineId = "mlkit_latin",
            documentLanguage = "en",
            bitmapWidthPx = 800,
            bitmapHeightPx = 1200
        )

        assertEquals(2, page.pageIndex)
        assertEquals(OcrPageStatus.NO_TEXT, page.status)
        assertTrue(page.sourceBlocks.isEmpty())
        assertEquals("", page.resolvedText)
    }

    @Test
    fun testCoordinateMapperIntegration() {
        val elem = MockElement(
            text = "Rotated",
            boundingBox = MlKitRect(100, 100, 200, 200),
            cornerPoints = listOf(MlKitPoint(100, 100), MlKitPoint(200, 100), MlKitPoint(200, 200), MlKitPoint(100, 200))
        )
        val line = MockLine(
            text = "Rotated",
            boundingBox = MlKitRect(100, 100, 200, 200),
            cornerPoints = listOf(MlKitPoint(100, 100), MlKitPoint(200, 100), MlKitPoint(200, 200), MlKitPoint(100, 200)),
            elements = listOf(elem)
        )
        val block = MockBlock(text = "Rotated", boundingBox = MlKitRect(100, 100, 200, 200), lines = listOf(line))
        val mockText = MockText(text = "Rotated", textBlocks = listOf(block))

        val coordMapper = OcrCoordinateMapper(
            PageTransformConfig(
                sourceWidthPx = 1000,
                sourceHeightPx = 1000,
                cropRect = OcrRect(0f, 0f, 1f, 1f),
                rotationDegrees = 0,
                ocrBitmapWidthPx = 500,
                ocrBitmapHeightPx = 500
            )
        )

        val page = MlKitLayoutMapper.mapToOcrPage(
            adapter = mockText,
            pageIndex = 1,
            coordinateMapper = coordMapper,
            bitmapWidthPx = 500,
            bitmapHeightPx = 500
        )

        val mappedLine = page.sourceBlocks[0].lines[0]
        assertNotNull(mappedLine.polygon)
        // In 500x500 OCR bitmap, point (100, 100) maps to normalized (0.2, 0.2)
        assertEquals(0.2f, mappedLine.polygon!!.points[0].x, 0.001f)
        assertEquals(0.2f, mappedLine.polygon!!.points[0].y, 0.001f)
    }

    @Test
    fun testReadingOrderPreserved() {
        val lines = (0..4).map { i ->
            MockLine(
                text = "Line $i",
                boundingBox = MlKitRect(10, i * 50, 300, (i + 1) * 50),
                cornerPoints = null
            )
        }
        val block = MockBlock(text = "Multi line", boundingBox = null, lines = lines)
        val mockText = MockText(text = "Multi line", textBlocks = listOf(block))

        val page = MlKitLayoutMapper.mapToOcrPage(
            adapter = mockText,
            pageIndex = 1,
            bitmapWidthPx = 1000,
            bitmapHeightPx = 1000
        )

        assertEquals(5, page.sourceBlocks[0].lines.size)
        for (i in 0..4) {
            assertEquals("Line $i", page.sourceBlocks[0].lines[i].text)
            assertEquals("line_1_$i", page.sourceBlocks[0].lines[i].lineId)
        }
    }
}
