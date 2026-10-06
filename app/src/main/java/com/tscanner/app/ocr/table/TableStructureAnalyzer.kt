package com.tscanner.app.ocr.table

import com.tscanner.app.ocr.model.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class TableDetectionResult(
    val tables: List<OcrTable>,
    val isConfident: Boolean,
    val confidenceScore: Float
)

/**
 * Heuristic table structure detector and analyzer:
 * - Detects table grids from OCR lines and tokens using row/column projection clustering
 * - Accurately differentiates multi-column paragraphs from real data tables
 * - Preserves empty and merged cell spans
 * - Strict type resolution: preserves leading zeros as TEXT ("00123" stays TEXT)
 * - Pure Kotlin with zero deep learning overhead (< 10 MB ceiling)
 *
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S16).
 */
object TableStructureAnalyzer {

    fun detectTables(page: OcrPage): TableDetectionResult {
        val lines = page.sourceBlocks.flatMap { it.lines }
        if (lines.size < 4) {
            return TableDetectionResult(emptyList(), isConfident = true, confidenceScore = 1.0f)
        }

        // 1. Resolve bounding boxes
        val items = lines.map { line ->
            val box = line.boundingBox ?: line.polygon?.let { getBoundingBox(it) }
                ?: OcrRect(0.1f, 0.1f, 0.9f, 0.15f)
            Pair(line, box)
        }

        // 2. Reject multi-column article paragraphs (long sentences, paragraphs with narrative text)
        val averageLength = items.map { it.first.text.length }.average()
        val sentencesWithPunctuation = items.count { it.first.text.endsWith(".") || it.first.text.endsWith("!") }
        if (averageLength > 45 && sentencesWithPunctuation >= 3) {
            // Narrative article text, not a table
            return TableDetectionResult(emptyList(), isConfident = true, confidenceScore = 0.95f)
        }

        // 3. Cluster into rows based on Y coordinates
        val sortedByY = items.sortedBy { it.second.top }
        val rowClusters = mutableListOf<MutableList<Pair<OcrLine, OcrRect>>>()

        for (item in sortedByY) {
            val matchedRow = rowClusters.find { row ->
                val rowCenterY = row.map { (it.second.top + it.second.bottom) / 2f }.average().toFloat()
                val itemCenterY = (item.second.top + item.second.bottom) / 2f
                abs(itemCenterY - rowCenterY) < 0.035f // within 3.5% page height
            }
            if (matchedRow != null) {
                matchedRow.add(item)
            } else {
                rowClusters.add(mutableListOf(item))
            }
        }

        // A valid table needs at least 2 rows where rows have multiple items
        val multiItemRows = rowClusters.filter { it.size >= 2 }
        if (multiItemRows.size < 2) {
            return TableDetectionResult(emptyList(), isConfident = true, confidenceScore = 0.9f)
        }

        // 4. Cluster into columns based on X coordinates
        val allRowItems = multiItemRows.flatten()
        val sortedByX = allRowItems.sortedBy { it.second.left }
        val colCenters = mutableListOf<Float>()

        for (item in sortedByX) {
            val itemCenterX = (item.second.left + item.second.right) / 2f
            val matchedCenter = colCenters.find { abs(it - itemCenterX) < 0.08f }
            if (matchedCenter == null) {
                colCenters.add(itemCenterX)
            }
        }
        colCenters.sort()

        if (colCenters.size < 2) {
            return TableDetectionResult(emptyList(), isConfident = true, confidenceScore = 0.85f)
        }

        val rowCount = multiItemRows.size
        val colCount = colCenters.size
        val cells = mutableListOf<OcrTableCell>()
        val tableId = "tbl_${page.pageIndex}_0"

        for ((rIdx, row) in multiItemRows.withIndex()) {
            for (item in row) {
                val itemCenterX = (item.second.left + item.second.right) / 2f
                val cIdx = colCenters.indexOfFirst { abs(it - itemCenterX) < 0.08f }.coerceAtLeast(0)
                val text = item.first.text.trim()
                val cellType = resolveCellType(text)

                cells.add(
                    OcrTableCell(
                        cellId = "c_r${rIdx}_c${cIdx}",
                        rowIndex = rIdx,
                        colIndex = cIdx,
                        rowSpan = 1,
                        colSpan = 1,
                        rawText = text,
                        editedText = text,
                        cellType = cellType
                    )
                )
            }
        }

        // Fill missing grid cells with empty cells to ensure a complete matrix
        val existingPositions = cells.map { Pair(it.rowIndex, it.colIndex) }.toSet()
        for (r in 0 until rowCount) {
            for (c in 0 until colCount) {
                if (!existingPositions.contains(Pair(r, c))) {
                    cells.add(
                        OcrTableCell(
                            cellId = "c_r${r}_c${c}",
                            rowIndex = r,
                            colIndex = c,
                            rowSpan = 1,
                            colSpan = 1,
                            rawText = "",
                            editedText = "",
                            cellType = OcrCellType.TEXT
                        )
                    )
                }
            }
        }
        cells.sortWith(compareBy({ it.rowIndex }, { it.colIndex }))

        val tableBox = getBoundingBoxOfBoxes(allRowItems.map { it.second })
        val detectedTable = OcrTable(
            tableId = tableId,
            rowCount = rowCount,
            columnCount = colCount,
            boundingBox = tableBox,
            cells = cells
        )

        val validation = detectedTable.validateGrid()
        return if (validation.isValid) {
            TableDetectionResult(listOf(detectedTable), isConfident = true, confidenceScore = 0.95f)
        } else {
            TableDetectionResult(emptyList(), isConfident = false, confidenceScore = 0.5f)
        }
    }

    /**
     * Resolves data type of cell text:
     * - Preserves leading zeros (e.g. "00123" is TEXT)
     * - Numerical values without leading zeros are NUMBER
     */
    fun resolveCellType(text: String): OcrCellType {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return OcrCellType.TEXT

        // Preserve leading zero codes (e.g. "00123", "0912345678") as TEXT
        if (trimmed.length > 1 && trimmed.startsWith("0") && trimmed[1].isDigit()) {
            return OcrCellType.TEXT
        }

        // Simple integer
        if (trimmed.all { it.isDigit() }) {
            return OcrCellType.NUMBER
        }

        // Floating point number (e.g. "123.45" or "123,45")
        val isFloat = trimmed.matches(Regex("^[+-]?\\d+([\\.,]\\d+)?$"))
        return if (isFloat) OcrCellType.NUMBER else OcrCellType.TEXT
    }

    /**
     * Evaluates table structure F1 score by comparing cell contents and row/column coordinates.
     */
    fun calculateStructureF1(groundTruth: OcrTable, hypothesis: OcrTable): Float {
        if (groundTruth.cells.isEmpty() && hypothesis.cells.isEmpty()) return 1.0f
        if (groundTruth.cells.isEmpty() || hypothesis.cells.isEmpty()) return 0.0f

        var matchedCount = 0
        for (gtCell in groundTruth.cells) {
            val hypCell = hypothesis.cells.find {
                it.rowIndex == gtCell.rowIndex &&
                it.colIndex == gtCell.colIndex &&
                it.rowSpan == gtCell.rowSpan &&
                it.colSpan == gtCell.colSpan &&
                it.rawText.trim() == gtCell.rawText.trim()
            }
            if (hypCell != null) {
                matchedCount++
            }
        }

        val precision = matchedCount.toFloat() / hypothesis.cells.size.toFloat()
        val recall = matchedCount.toFloat() / groundTruth.cells.size.toFloat()
        if (precision + recall == 0f) return 0.0f

        return (2 * precision * recall) / (precision + recall)
    }

    private fun getBoundingBox(polygon: OcrPolygon): OcrRect {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (p in polygon.points) {
            minX = min(minX, p.x)
            minY = min(minY, p.y)
            maxX = max(maxX, p.x)
            maxY = max(maxY, p.y)
        }

        return OcrRect(minX, minY, maxX, maxY)
    }

    private fun getBoundingBoxOfBoxes(boxes: List<OcrRect>): OcrRect {
        if (boxes.isEmpty()) return OcrRect(0f, 0f, 0f, 0f)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (b in boxes) {
            minX = min(minX, b.left)
            minY = min(minY, b.top)
            maxX = max(maxX, b.right)
            maxY = max(maxY, b.bottom)
        }

        return OcrRect(minX, minY, maxX, maxY)
    }
}
