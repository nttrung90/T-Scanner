package com.tscanner.app.ui.ocr.reader

import com.tscanner.app.ocr.model.*
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Represents a highlighted text match resulting from in-page Unicode search.
 */
data class SearchMatch(
    val lineId: String,
    val startIndex: Int,
    val endIndex: Int,
    val matchedText: String,
    val highlightBox: OcrRect
)

/**
 * Controller handling hit-testing, selection, copying, and Unicode searching
 * on scanned OCR pages while preserving reading order and geometric fidelity.
 *
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S10).
 */
class OcrSelectionController {

    private var currentPage: OcrPage? = null
    val page: OcrPage? get() = currentPage

    // Selected line IDs or token IDs
    private val selectedLineIds = mutableSetOf<String>()
    private val selectedTokenIds = mutableSetOf<String>()

    // Current search matches
    private val searchMatches = mutableListOf<SearchMatch>()
    private var currentSearchQuery: String = ""

    fun setPage(page: OcrPage?) {
        this.currentPage = page
        clearSelection()
        if (currentSearchQuery.isNotBlank()) {
            search(currentSearchQuery)
        }
    }

    fun clearSelection() {
        selectedLineIds.clear()
        selectedTokenIds.clear()
    }

    fun hasSelection(): Boolean = selectedLineIds.isNotEmpty() || selectedTokenIds.isNotEmpty()

    fun getSelectedLineIds(): Set<String> = selectedLineIds.toSet()
    fun getSelectedTokenIds(): Set<String> = selectedTokenIds.toSet()
    fun getSearchMatches(): List<SearchMatch> = searchMatches.toList()

    /**
     * Hit-tests a point in normalized [0..1] page coordinates.
     * Returns true if an item was hit and selection updated.
     */
    fun onSingleTap(normX: Float, normY: Float): Boolean {
        val page = currentPage ?: return false

        // Check if page content has been edited and modified in length
        val isEdited = page.editedContent != null && page.editedContent.text.isNotEmpty()
        if (isEdited) {
            // For edited content with mismatched lengths, anchor selection at line/paragraph level
            return hitTestLinesOnly(page, normX, normY)
        }

        // Try token-level hit test first if tokens are available
        var hitToken = false
        for (block in page.sourceBlocks) {
            for (line in block.lines) {
                if (line.tokens.isNotEmpty()) {
                    for (token in line.tokens) {
                        if (containsPoint(token.polygon, token.polygon?.let { getBoundingBox(it) }, normX, normY)) {
                            toggleTokenSelection(token.tokenId)
                            hitToken = true
                            break
                        }
                    }
                }
                if (hitToken) break
            }
            if (hitToken) break
        }

        if (hitToken) return true

        // Line-level fallback (for PaddleOCR or lines without tokens)
        return hitTestLinesOnly(page, normX, normY)
    }

    private fun hitTestLinesOnly(page: OcrPage, normX: Float, normY: Float): Boolean {
        for (block in page.sourceBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: line.polygon?.let { getBoundingBox(it) }
                if (containsPoint(line.polygon, box, normX, normY)) {
                    toggleLineSelection(line.lineId)
                    return true
                }
            }
        }
        return false
    }

    private fun toggleTokenSelection(tokenId: String) {
        if (selectedTokenIds.contains(tokenId)) {
            selectedTokenIds.remove(tokenId)
        } else {
            selectedTokenIds.add(tokenId)
        }
    }

    private fun toggleLineSelection(lineId: String) {
        if (selectedLineIds.contains(lineId)) {
            selectedLineIds.remove(lineId)
        } else {
            selectedLineIds.add(lineId)
        }
    }

    /**
     * Selects all lines/tokens overlapping with an area rectangle in normalized [0..1] coordinates.
     */
    fun selectArea(rect: OcrRect) {
        val page = currentPage ?: return
        clearSelection()

        for (block in page.sourceBlocks) {
            val blockBox = block.boundingBox
            for (line in block.lines) {
                val lineBox = line.boundingBox ?: line.polygon?.let { getBoundingBox(it) } ?: blockBox
                if (lineBox != null && intersects(rect, lineBox)) {
                    if (line.tokens.isNotEmpty()) {
                        for (token in line.tokens) {
                            val tokenBox = token.polygon?.let { getBoundingBox(it) } ?: lineBox
                            if (intersects(rect, tokenBox)) {
                                selectedTokenIds.add(token.tokenId)
                            }
                        }
                    } else {
                        selectedLineIds.add(line.lineId)
                    }
                }
            }
        }
    }

    /**
     * Concatenates all selected elements in strict reading order (Block -> Line -> Token).
     * Never flips column order.
     */
    fun getSelectedText(): String {
        val page = currentPage ?: return ""
        val result = StringBuilder()

        for (block in page.sourceBlocks) {
            for (line in block.lines) {
                if (selectedLineIds.contains(line.lineId)) {
                    if (result.isNotEmpty()) result.append("\n")
                    result.append(line.text)
                } else if (line.tokens.isNotEmpty()) {
                    val lineTokens = line.tokens.filter { selectedTokenIds.contains(it.tokenId) }
                    if (lineTokens.isNotEmpty()) {
                        if (result.isNotEmpty()) result.append("\n")
                        result.append(lineTokens.joinToString(" ") { it.text })
                    }
                }
            }
        }

        return result.toString().trim()
    }

    /**
     * Executes Unicode NFC case-insensitive search across the current page.
     */
    fun search(query: String): List<SearchMatch> {
        this.currentSearchQuery = query
        searchMatches.clear()

        val page = currentPage ?: return emptyList()
        if (query.isBlank()) return emptyList()

        val normQuery = query.normalizeNfc().lowercase(Locale.ROOT)

        for (block in page.sourceBlocks) {
            for (line in block.lines) {
                val lineTextNorm = line.text.normalizeNfc().lowercase(Locale.ROOT)
                var startIndex = 0

                while (startIndex < lineTextNorm.length) {
                    val matchIndex = lineTextNorm.indexOf(normQuery, startIndex)
                    if (matchIndex < 0) break

                    val endIndex = matchIndex + normQuery.length
                    val matchedSub = line.text.substring(
                        matchIndex.coerceIn(0, line.text.length),
                        endIndex.coerceIn(0, line.text.length)
                    )

                    // Estimate highlight bounding box along the line
                    val lineBox = line.boundingBox ?: line.polygon?.let { getBoundingBox(it) }
                        ?: OcrRect(0f, 0f, 1f, 1f)

                    val lineLen = line.text.length.coerceAtLeast(1)
                    val charWidth = lineBox.width / lineLen.toFloat()
                    val matchLeft = lineBox.left + matchIndex * charWidth
                    val matchRight = matchLeft + normQuery.length * charWidth

                    val highlight = OcrRect(
                        left = matchLeft.coerceIn(0f, 1f),
                        top = lineBox.top,
                        right = matchRight.coerceIn(0f, 1f),
                        bottom = lineBox.bottom
                    )

                    searchMatches.add(
                        SearchMatch(
                            lineId = line.lineId,
                            startIndex = matchIndex,
                            endIndex = endIndex,
                            matchedText = matchedSub,
                            highlightBox = highlight
                        )
                    )

                    startIndex = endIndex
                }
            }
        }

        return searchMatches.toList()
    }

    fun clearSearch() {
        currentSearchQuery = ""
        searchMatches.clear()
    }

    private fun String.normalizeNfc(): String {
        return Normalizer.normalize(this, Normalizer.Form.NFC)
    }

    private fun containsPoint(polygon: OcrPolygon?, box: OcrRect?, x: Float, y: Float): Boolean {
        if (box != null && (x < box.left || x > box.right || y < box.top || y > box.bottom)) {
            return false
        }
        if (polygon == null || polygon.points.size < 3) {
            return box != null && x >= box.left && x <= box.right && y >= box.top && y <= box.bottom
        }

        // Ray casting algorithm for arbitrary polygon
        var inside = false
        val pts = polygon.points
        var j = pts.size - 1
        for (i in pts.indices) {
            val pi = pts[i]
            val pj = pts[j]
            if ((pi.y > y) != (pj.y > y) &&
                (x < (pj.x - pi.x) * (y - pi.y) / (pj.y - pi.y) + pi.x)) {
                inside = !inside
            }
            j = i
        }
        return inside
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

    private fun intersects(a: OcrRect, b: OcrRect): Boolean {
        return !(a.right < b.left || a.left > b.right || a.bottom < b.top || a.top > b.bottom)
    }
}
