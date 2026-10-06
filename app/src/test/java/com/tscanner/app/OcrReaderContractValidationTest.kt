package com.tscanner.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import kotlin.math.min

/**
 * Validates the data contract and ground truth fixtures defined in S01.
 * Complies with docs/ocr-reader/contract.md and PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md.
 */
class OcrReaderContractValidationTest {

    private fun loadResource(name: String): String {
        val stream: InputStream = javaClass.getResourceAsStream("/ocr_reader/$name")
            ?: error("Resource /ocr_reader/$name not found on classpath")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * Computes standard Character Error Rate (CER) based on Levenshtein distance:
     * CER = (Substitutions + Deletions + Insertions) / GroundTruthLength
     */
    private fun calculateCer(groundTruth: String, hypothesis: String): Double {
        val gt = groundTruth.normalizeNfc()
        val hyp = hypothesis.normalizeNfc()
        if (gt.isEmpty()) return if (hyp.isEmpty()) 0.0 else 1.0

        val dp = Array(gt.length + 1) { IntArray(hyp.length + 1) }
        for (i in 0..gt.length) dp[i][0] = i
        for (j in 0..hyp.length) dp[0][j] = j

        for (i in 1..gt.length) {
            for (j in 1..hyp.length) {
                val cost = if (gt[i - 1] == hyp[j - 1]) 0 else 1
                dp[i][j] = min(
                    dp[i - 1][j] + 1, // deletion
                    min(
                        dp[i][j - 1] + 1, // insertion
                        dp[i - 1][j - 1] + cost // substitution
                    )
                )
            }
        }
        val edits = dp[gt.length][hyp.length]
        return edits.toDouble() / gt.length.toDouble()
    }

    private fun String.normalizeNfc(): String {
        return java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFC)
    }

    /**
     * Sanitizes spreadsheet cell strings against formula injection.
     * Escapes leading '=', '+', '-', '@' with a single quote.
     */
    private fun sanitizeSpreadsheetCell(input: String): String {
        if (input.isEmpty()) return input
        val firstChar = input[0]
        return if (firstChar == '=' || firstChar == '+' || firstChar == '-' || firstChar == '@') {
            "'$input"
        } else {
            input
        }
    }

    @Test
    fun testCorpusIndexIntegrity() {
        val indexRaw = loadResource("corpus_index.json")
        val index = JSONObject(indexRaw)

        assertEquals("1.0.0", index.getString("corpusVersion"))
        assertTrue(index.getInt("totalSamples") >= 30)

        val samples = index.getJSONArray("samples")
        assertTrue("Corpus index must list sample fixtures", samples.length() >= 10)

        for (i in 0 until samples.length()) {
            val item = samples.getJSONObject(i)
            val fileName = item.getString("file")
            val content = loadResource(fileName)
            assertNotNull("Fixture file $fileName must be loadable", content)
            val json = JSONObject(content)
            assertEquals("Fixture ID must match index", item.getString("id"), json.getString("id"))
        }
    }

    @Test
    fun testVietnameseSingleColumnCerMeetsTarget() {
        val raw01 = loadResource("single_col_vi_01.json")
        val json01 = JSONObject(raw01)
        val cer01 = calculateCer(json01.getString("groundTruthText"), json01.getString("ocrHypothesisText"))
        assertEquals(0.0, cer01, 0.0001)

        val raw02 = loadResource("single_col_vi_02.json")
        val json02 = JSONObject(raw02)
        val cer02 = calculateCer(json02.getString("groundTruthText"), json02.getString("ocrHypothesisText"))
        // Target: CER <= 2.0% (0.02)
        assertTrue("Vietnamese sample CER ($cer02) must meet target <= 2.0%", cer02 <= 0.02)
    }

    @Test
    fun testEnglishSingleColumnCerMeetsTarget() {
        val raw = loadResource("single_col_en_01.json")
        val json = JSONObject(raw)
        val cer = calculateCer(json.getString("groundTruthText"), json.getString("ocrHypothesisText"))
        // Target: CER <= 1.0% (0.01)
        assertTrue("English sample CER ($cer) must meet target <= 1.0%", cer <= 0.01)
    }

    @Test
    fun testTwoColumnReadingOrderPreservation() {
        val raw = loadResource("two_column_doc_01.json")
        val json = JSONObject(raw)
        val lines = json.getJSONArray("lines")
        val order = json.getJSONArray("expectedReadingOrder")

        // First 2 lines should be from column 0, remaining 2 from column 1
        assertEquals(4, lines.length())
        assertEquals("line_col1_1", order.getString(0))
        assertEquals("line_col1_2", order.getString(1))
        assertEquals("line_col2_1", order.getString(2))
        assertEquals("line_col2_2", order.getString(3))
    }

    @Test
    fun testSimpleTableNonOverlappingGridAndLeadingZeros() {
        val raw = loadResource("simple_table_01.json")
        val json = JSONObject(raw)
        val table = json.getJSONObject("table")
        val rows = table.getInt("rowCount")
        val cols = table.getInt("columnCount")
        val cells = table.getJSONArray("cells")

        assertEquals(3, rows)
        assertEquals(3, cols)
        assertEquals(9, cells.length())

        val grid = Array(rows) { Array(cols) { false } }
        var foundLeadingZeroCell = false

        for (i in 0 until cells.length()) {
            val cell = cells.getJSONObject(i)
            val r = cell.getInt("rowIndex")
            val c = cell.getInt("colIndex")
            val rs = cell.getInt("rowSpan")
            val cs = cell.getInt("colSpan")
            val text = cell.getString("text")

            if (text == "00123" || text == "00456") {
                foundLeadingZeroCell = true
                assertEquals("Leading zero codes must have TEXT type", "TEXT", cell.getString("cellType"))
            }

            // Ensure no overlapping cells in grid
            for (dr in 0 until rs) {
                for (dc in 0 until cs) {
                    val targetR = r + dr
                    val targetC = c + dc
                    assertTrue("Cell position out of bounds ($targetR, $targetC)", targetR < rows && targetC < cols)
                    assertTrue("Grid cell ($targetR, $targetC) is already occupied!", !grid[targetR][targetC])
                    grid[targetR][targetC] = true
                }
            }
        }

        assertTrue("Table fixture must contain leading zero test cell", foundLeadingZeroCell)

        // All grid cells must be covered
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                assertTrue("Grid cell ($r, $c) must be covered", grid[r][c])
            }
        }
    }

    @Test
    fun testMergedTableSpansWithoutOverlapOrHoles() {
        val raw = loadResource("merged_table_01.json")
        val json = JSONObject(raw)
        val table = json.getJSONObject("table")
        val rows = table.getInt("rowCount")
        val cols = table.getInt("columnCount")
        val cells = table.getJSONArray("cells")

        assertEquals(3, rows)
        assertEquals(3, cols)

        val grid = Array(rows) { Array(cols) { false } }
        var totalCoveredCells = 0

        for (i in 0 until cells.length()) {
            val cell = cells.getJSONObject(i)
            val r = cell.getInt("rowIndex")
            val c = cell.getInt("colIndex")
            val rs = cell.getInt("rowSpan")
            val cs = cell.getInt("colSpan")

            for (dr in 0 until rs) {
                for (dc in 0 until cs) {
                    val targetR = r + dr
                    val targetC = c + dc
                    assertTrue(!grid[targetR][targetC])
                    grid[targetR][targetC] = true
                    totalCoveredCells++
                }
            }
        }

        assertEquals("Total cells covered by spans must equal rows x cols", rows * cols, totalCoveredCells)
    }

    @Test
    fun testFormulaInjectionSanitizationRule() {
        val raw = loadResource("formula_defense_01.json")
        val json = JSONObject(raw)
        val cells = json.getJSONObject("table").getJSONArray("cells")

        for (i in 0 until cells.length()) {
            val cell = cells.getJSONObject(i)
            val rawText = cell.getString("text")
            if (rawText.startsWith("=") || rawText.startsWith("+") || rawText.startsWith("-") || rawText.startsWith("@")) {
                val sanitized = sanitizeSpreadsheetCell(rawText)
                assertTrue("Sanitized text must be escaped with single quote: $sanitized", sanitized.startsWith("'"))
            }
        }
    }

    @Test
    fun testMultipageSequenceOrderingStrictness() {
        val raw = loadResource("multipage_sequence_01.json")
        val json = JSONObject(raw)
        val totalPages = json.getInt("totalPages")
        val pages = json.getJSONArray("pages")

        assertEquals(3, totalPages)
        assertEquals(3, pages.length())

        for (i in 0 until pages.length()) {
            val page = pages.getJSONObject(i)
            val expectedPageIndex = i + 1
            assertEquals("Page index must strictly match 1-based order", expectedPageIndex, page.getInt("pageIndex"))
        }

        // Page 2 is blank, but retains pageIndex 2
        val page2 = pages.getJSONObject(1)
        assertEquals("NO_TEXT", page2.getString("status"))
        assertEquals("", page2.getString("text"))
        assertEquals(2, page2.getInt("pageIndex"))

        // Page 3 retains pageIndex 3
        val page3 = pages.getJSONObject(2)
        assertEquals("SUCCESS", page3.getString("status"))
        assertEquals(3, page3.getInt("pageIndex"))
    }
}
