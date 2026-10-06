package com.tscanner.app

import com.tscanner.app.ocr.model.*
import com.tscanner.app.ocr.table.TableStructureAnalyzer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream

/**
 * Unit tests for TableStructureAnalyzer:
 * - Simple table detection and F1 score calculation
 * - Leading zero preservation as TEXT
 * - Rejection of multi-column narrative text (not misclassified as tables)
 * - Merged cell span validation
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S16).
 */
class TableStructureAnalyzerTest {

    private fun loadResource(name: String): String {
        val stream: InputStream = javaClass.getResourceAsStream("/ocr_reader/$name")
            ?: error("Resource /ocr_reader/$name not found on classpath")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test
    fun testSimpleTableDetectionMeetsF1Target() {
        val raw = loadResource("simple_table_01.json")
        val json = JSONObject(raw)
        val tableObj = json.getJSONObject("table")
        val gtTable = OcrTable.fromJson(tableObj)

        // Synthesize OCR lines from ground truth cells
        val lines = gtTable.cells.map { cell ->
            val colLeft = 0.1f + cell.colIndex * 0.25f
            val colRight = colLeft + 0.20f
            val rowTop = 0.2f + cell.rowIndex * 0.12f
            val rowBottom = rowTop + 0.08f

            OcrLine(
                lineId = "l_${cell.cellId}",
                text = cell.rawText,
                boundingBox = OcrRect(colLeft, rowTop, colRight, rowBottom)
            )
        }

        val page = OcrPage(
            pageId = "p_table_test",
            pageIndex = 1,
            status = OcrPageStatus.SUCCESS,
            sourceBlocks = listOf(OcrBlock("b1", lines = lines))
        )

        val result = TableStructureAnalyzer.detectTables(page)

        assertEquals("Must detect 1 table", 1, result.tables.size)
        val detected = result.tables[0]
        assertEquals(3, detected.rowCount)
        assertEquals(3, detected.columnCount)

        val f1 = TableStructureAnalyzer.calculateStructureF1(gtTable, detected)
        assertTrue("Structure F1 score ($f1) must be >= 0.95 (95%)", f1 >= 0.95f)
    }

    @Test
    fun testLeadingZerosPreservedAsTextType() {
        // Leading zero codes must be TEXT
        assertEquals(OcrCellType.TEXT, TableStructureAnalyzer.resolveCellType("00123"))
        assertEquals(OcrCellType.TEXT, TableStructureAnalyzer.resolveCellType("00456"))
        assertEquals(OcrCellType.TEXT, TableStructureAnalyzer.resolveCellType("0912345678"))

        // Pure numbers without leading zero are NUMBER
        assertEquals(OcrCellType.NUMBER, TableStructureAnalyzer.resolveCellType("123"))
        assertEquals(OcrCellType.NUMBER, TableStructureAnalyzer.resolveCellType("50"))
        assertEquals(OcrCellType.NUMBER, TableStructureAnalyzer.resolveCellType("1200000"))
        assertEquals(OcrCellType.NUMBER, TableStructureAnalyzer.resolveCellType("99.5"))

        // Alphanumeric is TEXT
        assertEquals(OcrCellType.TEXT, TableStructureAnalyzer.resolveCellType("STT"))
        assertEquals(OcrCellType.TEXT, TableStructureAnalyzer.resolveCellType("Mã định danh"))
        assertEquals(OcrCellType.TEXT, TableStructureAnalyzer.resolveCellType("=SUM(A1:A10)"))
    }

    @Test
    fun testMultiColumnNarrativeArticleNotMisclassifiedAsTable() {
        // Multi-column newspaper / article with narrative sentences ending in periods
        val articleLines = listOf(
            OcrLine("l1", "Đây là bài viết phân tích chuyên sâu về thị trường công nghệ năm 2026.", boundingBox = OcrRect(0.05f, 0.1f, 0.45f, 0.15f)),
            OcrLine("l2", "Các chuyên gia kinh tế đưa ra nhiều nhận định lạc quan về tăng trưởng số.", boundingBox = OcrRect(0.05f, 0.16f, 0.45f, 0.21f)),
            OcrLine("l3", "Doanh nghiệp trong nước đẩy mạnh chuyển đổi ứng dụng trí tuệ nhân tạo toàn diện.", boundingBox = OcrRect(0.05f, 0.22f, 0.45f, 0.27f)),
            OcrLine("l4", "Ở một diễn biến khác, thị trường quốc tế cũng ghi nhận sự phục hồi mạnh mẽ.", boundingBox = OcrRect(0.55f, 0.1f, 0.95f, 0.15f)),
            OcrLine("l5", "Các chuỗi cung ứng linh kiện điện tử dần ổn định trở lại sau biến động.", boundingBox = OcrRect(0.55f, 0.16f, 0.95f, 0.21f))
        )

        val page = OcrPage(
            pageId = "p_article",
            pageIndex = 1,
            status = OcrPageStatus.SUCCESS,
            sourceBlocks = listOf(OcrBlock("b1", lines = articleLines))
        )

        val result = TableStructureAnalyzer.detectTables(page)

        assertTrue("Narrative 2-column text must NOT be misclassified as a table", result.tables.isEmpty())
    }

    @Test
    fun testMergedTableGridValidationFromFixture() {
        val raw = loadResource("merged_table_01.json")
        val json = JSONObject(raw)
        val table = OcrTable.fromJson(json.getJSONObject("table"))

        val valResult = table.validateGrid()
        assertTrue("Merged table from fixture must be valid without overlapping cells", valResult.isValid)
        assertEquals(0, valResult.errors.size)
    }
}
