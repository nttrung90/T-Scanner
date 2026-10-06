package com.tscanner.app

import com.tscanner.app.ocr.layout.DocumentLayoutAnalyzer
import com.tscanner.app.ocr.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream

/**
 * Unit tests for DocumentLayoutAnalyzer:
 * - Multi-column detection and column-first reading order
 * - Single-column paragraph and heading heuristics
 * - Spanning banner preservation
 * - Blank page immunity against hallucination
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S15).
 */
class DocumentLayoutAnalyzerTest {

    private fun loadResource(name: String): String {
        val stream: InputStream = javaClass.getResourceAsStream("/ocr_reader/$name")
            ?: error("Resource /ocr_reader/$name not found on classpath")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test
    fun testTwoColumnFixtureMatchesGroundTruthReadingOrder() {
        val raw = loadResource("two_column_doc_01.json")
        val json = JSONObject(raw)

        val linesArray = json.getJSONArray("lines")
        val ocrLines = mutableListOf<OcrLine>()

        for (i in 0 until linesArray.length()) {
            val lObj = linesArray.getJSONObject(i)
            val polyArr = lObj.getJSONArray("polygon")
            val points = mutableListOf<OcrPoint>()
            for (j in 0 until polyArr.length()) {
                val ptObj = polyArr.getJSONObject(j)
                points.add(OcrPoint(ptObj.getDouble("x").toFloat(), ptObj.getDouble("y").toFloat()))
            }
            ocrLines.add(
                OcrLine(
                    lineId = lObj.getString("id"),
                    text = lObj.getString("text"),
                    polygon = OcrPolygon(points)
                )
            )
        }

        // Shuffle lines to ensure layout analyzer sort is independent of input order
        val shuffledLines = listOf(ocrLines[2], ocrLines[0], ocrLines[3], ocrLines[1])
        val page = OcrPage(
            pageId = "p_two_col",
            pageIndex = 1,
            status = OcrPageStatus.SUCCESS,
            sourceBlocks = listOf(OcrBlock("b1", lines = shuffledLines))
        )

        val analysis = DocumentLayoutAnalyzer.analyzePage(page)

        assertEquals("Must detect 2 columns", 2, analysis.columnCount)
        assertEquals(4, analysis.orderedLines.size)

        // Strict ground truth order: line_col1_1, line_col1_2, line_col2_1, line_col2_2
        val expectedOrder = listOf("line_col1_1", "line_col1_2", "line_col2_1", "line_col2_2")
        val actualOrder = analysis.orderedLines.map { it.lineId }
        assertEquals(expectedOrder, actualOrder)

        val expectedText = json.getString("groundTruthText")
        assertEquals(expectedText, analysis.orderedText)
    }

    @Test
    fun testSingleColumnViDocMatchesTopToBottomOrder() {
        val raw = loadResource("single_col_vi_01.json")
        val json = JSONObject(raw)

        val linesArray = json.getJSONArray("lines")
        val ocrLines = mutableListOf<OcrLine>()

        for (i in 0 until linesArray.length()) {
            val lObj = linesArray.getJSONObject(i)
            val polyArr = lObj.getJSONArray("polygon")
            val points = mutableListOf<OcrPoint>()
            for (j in 0 until polyArr.length()) {
                val ptObj = polyArr.getJSONObject(j)
                points.add(OcrPoint(ptObj.getDouble("x").toFloat(), ptObj.getDouble("y").toFloat()))
            }
            ocrLines.add(
                OcrLine(
                    lineId = lObj.getString("id"),
                    text = lObj.getString("text"),
                    polygon = OcrPolygon(points)
                )
            )
        }

        val page = OcrPage(
            pageId = "p_vi",
            pageIndex = 1,
            status = OcrPageStatus.SUCCESS,
            sourceBlocks = listOf(OcrBlock("b1", lines = ocrLines))
        )

        val analysis = DocumentLayoutAnalyzer.analyzePage(page)

        assertEquals("Single column document", 1, analysis.columnCount)
        assertEquals(4, analysis.orderedLines.size)

        // Verify top-to-bottom order preserved
        for (i in 0 until analysis.orderedLines.size - 1) {
            val curY = analysis.orderedLines[i].polygon!!.points[0].y
            val nextY = analysis.orderedLines[i + 1].polygon!!.points[0].y
            assertTrue("Lines must be sorted top to bottom", curY <= nextY)
        }
    }

    @Test
    fun testBlankPageImmunityAgainstHallucination() {
        val raw = loadResource("blank_page_01.json")
        val json = JSONObject(raw)

        val page = OcrPage(
            pageId = json.getString("id"),
            pageIndex = 1,
            status = OcrPageStatus.NO_TEXT,
            sourceBlocks = emptyList()
        )

        val analysis = DocumentLayoutAnalyzer.analyzePage(page)

        assertEquals(1, analysis.columnCount)
        assertTrue(analysis.columns.isEmpty())
        assertTrue(analysis.orderedLines.isEmpty())
        assertEquals("", analysis.orderedText)
    }

    @Test
    fun testSpanningBannerPlacedBeforeTwoColumns() {
        // Full width title crossing center gutter
        val titleLine = OcrLine(
            lineId = "l_title",
            text = "MAIN DOCUMENT TITLE SPANNING FULL PAGE",
            polygon = OcrPolygon(listOf(OcrPoint(0.1f, 0.02f), OcrPoint(0.9f, 0.02f), OcrPoint(0.9f, 0.06f), OcrPoint(0.1f, 0.06f)))
        )

        // Column 1 lines
        val c1l1 = OcrLine("c1_1", "Col 1 Line 1", polygon = OcrPolygon(listOf(OcrPoint(0.1f, 0.10f), OcrPoint(0.45f, 0.10f), OcrPoint(0.45f, 0.14f), OcrPoint(0.1f, 0.14f))))
        val c1l2 = OcrLine("c1_2", "Col 1 Line 2", polygon = OcrPolygon(listOf(OcrPoint(0.1f, 0.16f), OcrPoint(0.45f, 0.16f), OcrPoint(0.45f, 0.20f), OcrPoint(0.1f, 0.20f))))

        // Column 2 lines
        val c2l1 = OcrLine("c2_1", "Col 2 Line 1", polygon = OcrPolygon(listOf(OcrPoint(0.55f, 0.10f), OcrPoint(0.90f, 0.10f), OcrPoint(0.90f, 0.14f), OcrPoint(0.55f, 0.14f))))
        val c2l2 = OcrLine("c2_2", "Col 2 Line 2", polygon = OcrPolygon(listOf(OcrPoint(0.55f, 0.16f), OcrPoint(0.90f, 0.16f), OcrPoint(0.90f, 0.20f), OcrPoint(0.55f, 0.20f))))

        val page = OcrPage(
            pageId = "p_banner",
            pageIndex = 1,
            status = OcrPageStatus.SUCCESS,
            sourceBlocks = listOf(OcrBlock("b1", lines = listOf(c2l1, c1l2, titleLine, c1l1, c2l2)))
        )

        val analysis = DocumentLayoutAnalyzer.analyzePage(page)

        assertEquals(2, analysis.columnCount)
        assertEquals(5, analysis.orderedLines.size)

        // Title must be at index 0
        assertEquals("l_title", analysis.orderedLines[0].lineId)
        // Then col 1 lines
        assertEquals("c1_1", analysis.orderedLines[1].lineId)
        assertEquals("c1_2", analysis.orderedLines[2].lineId)
        // Then col 2 lines
        assertEquals("c2_1", analysis.orderedLines[3].lineId)
        assertEquals("c2_2", analysis.orderedLines[4].lineId)
    }
}
