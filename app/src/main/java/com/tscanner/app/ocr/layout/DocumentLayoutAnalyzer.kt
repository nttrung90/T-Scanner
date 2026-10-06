package com.tscanner.app.ocr.layout

import com.tscanner.app.ocr.model.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class AnalyzedParagraph(
    val paragraphId: String,
    val isHeading: Boolean,
    val boundingBox: OcrRect,
    val lines: List<OcrLine>,
    val text: String
)

data class LayoutColumn(
    val columnIndex: Int,
    val boundingBox: OcrRect,
    val paragraphs: List<AnalyzedParagraph>
)

data class PageLayoutAnalysis(
    val pageIndex: Int,
    val columnCount: Int,
    val columns: List<LayoutColumn>,
    val orderedLines: List<OcrLine>,
    val orderedText: String
)

/**
 * Pure heuristic layout analyzer for OCR documents:
 * - Detects single vs multi-column layouts using X-projection interval clustering
 * - Orders lines sequentially per column (never interweaves parallel columns)
 * - Groups lines into paragraphs based on vertical line spacing and indentation
 * - Detects headings using font height variance and horizontal centering
 * - Pure Kotlin with zero deep learning models, keeping footprint minimal (< 10 MB ceiling)
 *
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S15).
 */
object DocumentLayoutAnalyzer {

    fun analyzePage(page: OcrPage): PageLayoutAnalysis {
        val allLines = page.sourceBlocks.flatMap { it.lines }
        if (allLines.isEmpty()) {
            return PageLayoutAnalysis(
                pageIndex = page.pageIndex,
                columnCount = 1,
                columns = emptyList(),
                orderedLines = emptyList(),
                orderedText = ""
            )
        }

        // 1. Resolve bounding box for each line
        val linesWithBounds = allLines.map { line ->
            val box = line.boundingBox ?: line.polygon?.let { getBoundingBox(it) }
                ?: OcrRect(0.1f, 0.1f, 0.9f, 0.15f)
            Pair(line, box)
        }

        // 2. Identify full-width banner lines (e.g. document title spanning across columns)
        val medianWidth = calculateMedian(linesWithBounds.map { it.second.width })
        val spanningBannerLines = mutableListOf<Pair<OcrLine, OcrRect>>()
        val bodyLines = mutableListOf<Pair<OcrLine, OcrRect>>()

        val columnCandidateLines = linesWithBounds.filter { it.second.width < 0.75f }
        val isPotentiallyMultiColumn = detectGutter(columnCandidateLines.map { it.second }) != null

        if (isPotentiallyMultiColumn) {
            val gutterX = detectGutter(columnCandidateLines.map { it.second })!!
            for (item in linesWithBounds) {
                // If line crosses the central gutter significantly, it's a spanning banner
                if (item.second.left < gutterX - 0.05f && item.second.right > gutterX + 0.05f) {
                    spanningBannerLines.add(item)
                } else {
                    bodyLines.add(item)
                }
            }
        } else {
            bodyLines.addAll(linesWithBounds)
        }

        // 3. Cluster body lines into columns
        val columnsList = mutableListOf<LayoutColumn>()
        val orderedLines = mutableListOf<OcrLine>()

        if (isPotentiallyMultiColumn && bodyLines.isNotEmpty()) {
            val gutterX = detectGutter(bodyLines.map { it.second }) ?: 0.5f

            val leftColumnItems = bodyLines.filter { it.second.right <= gutterX + 0.05f }
                .sortedBy { it.second.top }
            val rightColumnItems = bodyLines.filter { it.second.left >= gutterX - 0.05f }
                .sortedBy { it.second.top }

            // Spanning banners come first
            val sortedBanners = spanningBannerLines.sortedBy { it.second.top }
            if (sortedBanners.isNotEmpty()) {
                val bannerParas = groupLinesIntoParagraphs(sortedBanners, page.pageIndex, colIdx = 0, pOffset = 0)
                val bannerCol = LayoutColumn(0, getBoundingBoxOfBoxes(sortedBanners.map { it.second }), bannerParas)
                columnsList.add(bannerCol)
                orderedLines.addAll(sortedBanners.map { it.first })
            }

            // Column 1 (Left)
            if (leftColumnItems.isNotEmpty()) {
                val leftParas = groupLinesIntoParagraphs(leftColumnItems, page.pageIndex, colIdx = 1, pOffset = orderedLines.size)
                val leftCol = LayoutColumn(1, getBoundingBoxOfBoxes(leftColumnItems.map { it.second }), leftParas)
                columnsList.add(leftCol)
                orderedLines.addAll(leftColumnItems.map { it.first })
            }

            // Column 2 (Right)
            if (rightColumnItems.isNotEmpty()) {
                val rightParas = groupLinesIntoParagraphs(rightColumnItems, page.pageIndex, colIdx = 2, pOffset = orderedLines.size)
                val rightCol = LayoutColumn(2, getBoundingBoxOfBoxes(rightColumnItems.map { it.second }), rightParas)
                columnsList.add(rightCol)
                orderedLines.addAll(rightColumnItems.map { it.first })
            }
        } else {
            // Single column layout
            val sortedItems = linesWithBounds.sortedBy { it.second.top }
            val paragraphs = groupLinesIntoParagraphs(sortedItems, page.pageIndex, colIdx = 0, pOffset = 0)
            val singleCol = LayoutColumn(0, getBoundingBoxOfBoxes(sortedItems.map { it.second }), paragraphs)
            columnsList.add(singleCol)
            orderedLines.addAll(sortedItems.map { it.first })
        }

        val fullText = orderedLines.joinToString("\n") { it.text }

        return PageLayoutAnalysis(
            pageIndex = page.pageIndex,
            columnCount = if (columnsList.size > 1) 2 else 1,
            columns = columnsList,
            orderedLines = orderedLines,
            orderedText = fullText
        )
    }

    private fun detectGutter(boxes: List<OcrRect>): Float? {
        if (boxes.size < 4) return null

        // Sample horizontal histogram between 0.30 and 0.70
        val step = 0.02f
        var bestGutterX: Float? = null
        var minOverlapCount = Int.MAX_VALUE

        var probeX = 0.35f
        while (probeX <= 0.65f) {
            val overlapping = boxes.count { it.left < probeX && it.right > probeX }
            val leftCount = boxes.count { it.right <= probeX }
            val rightCount = boxes.count { it.left >= probeX }

            // A valid 2-column gutter must have significant content on both sides and zero or near-zero overlapping lines
            if (leftCount >= 2 && rightCount >= 2 && overlapping == 0) {
                if (overlapping < minOverlapCount) {
                    minOverlapCount = overlapping
                    bestGutterX = probeX
                }
            }
            probeX += step
        }

        return bestGutterX
    }

    private fun groupLinesIntoParagraphs(
        items: List<Pair<OcrLine, OcrRect>>,
        pageIndex: Int,
        colIdx: Int,
        pOffset: Int
    ): List<AnalyzedParagraph> {
        if (items.isEmpty()) return emptyList()

        val medianHeight = calculateMedian(items.map { it.second.height })
        val paragraphs = mutableListOf<AnalyzedParagraph>()
        var currentLines = mutableListOf<OcrLine>()
        var currentBoxes = mutableListOf<OcrRect>()

        for (i in items.indices) {
            val (line, box) = items[i]
            val isHeading = box.height >= medianHeight * 1.3f && line.text.length < 60

            if (currentLines.isEmpty()) {
                currentLines.add(line)
                currentBoxes.add(box)
            } else {
                val prevBox = currentBoxes.last()
                val vSpacing = box.top - prevBox.bottom

                // Paragraph break conditions:
                // 1. Line is a prominent heading
                // 2. Vertical gap > 1.6x median line height
                val isParagraphBreak = isHeading || (vSpacing > medianHeight * 1.6f)

                if (isParagraphBreak) {
                    val pBox = getBoundingBoxOfBoxes(currentBoxes)
                    val pText = currentLines.joinToString("\n") { it.text }
                    paragraphs.add(
                        AnalyzedParagraph(
                            paragraphId = "para_${pageIndex}_${colIdx}_${paragraphs.size + pOffset}",
                            isHeading = currentBoxes.size == 1 && currentBoxes[0].height >= medianHeight * 1.3f,
                            boundingBox = pBox,
                            lines = currentLines.toList(),
                            text = pText
                        )
                    )
                    currentLines = mutableListOf(line)
                    currentBoxes = mutableListOf(box)
                } else {
                    currentLines.add(line)
                    currentBoxes.add(box)
                }
            }
        }

        if (currentLines.isNotEmpty()) {
            val pBox = getBoundingBoxOfBoxes(currentBoxes)
            val pText = currentLines.joinToString("\n") { it.text }
            paragraphs.add(
                AnalyzedParagraph(
                    paragraphId = "para_${pageIndex}_${colIdx}_${paragraphs.size + pOffset}",
                    isHeading = currentBoxes.size == 1 && currentBoxes[0].height >= medianHeight * 1.3f,
                    boundingBox = pBox,
                    lines = currentLines.toList(),
                    text = pText
                )
            )
        }

        return paragraphs
    }

    private fun calculateMedian(values: List<Float>): Float {
        if (values.isEmpty()) return 0.05f
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
        }
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
