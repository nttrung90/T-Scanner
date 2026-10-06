package com.tscanner.app.ocr.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Pure Kotlin document model with geometry, layout, and provenance.
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S02)
 * and docs/ocr-reader/contract.md.
 */

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

enum class OcrPageStatus {
    PENDING,
    SUCCESS,
    NO_TEXT,
    ERROR,
    UNSUPPORTED_LANGUAGE,
    CANCELLED
}

enum class OcrCellType {
    TEXT,
    NUMBER
}

enum class OcrTextAlignment {
    LEFT,
    CENTER,
    RIGHT,
    JUSTIFY
}

data class OcrPoint(
    val x: Float,
    val y: Float
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("x", x.toDouble())
        put("y", y.toDouble())
    }

    companion object {
        fun fromJson(json: JSONObject): OcrPoint = OcrPoint(
            x = json.optDouble("x", 0.0).toFloat(),
            y = json.optDouble("y", 0.0).toFloat()
        )
    }
}

data class OcrRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)

    fun toJson(): JSONObject = JSONObject().apply {
        put("left", left.toDouble())
        put("top", top.toDouble())
        put("right", right.toDouble())
        put("bottom", bottom.toDouble())
    }

    companion object {
        fun fromJson(json: JSONObject): OcrRect = OcrRect(
            left = json.optDouble("left", 0.0).toFloat(),
            top = json.optDouble("top", 0.0).toFloat(),
            right = json.optDouble("right", 0.0).toFloat(),
            bottom = json.optDouble("bottom", 0.0).toFloat()
        )
    }
}

data class OcrPolygon(
    val points: List<OcrPoint>
) {
    fun toJson(): JSONArray = JSONArray().apply {
        points.forEach { put(it.toJson()) }
    }

    companion object {
        fun fromJson(array: JSONArray): OcrPolygon {
            val list = mutableListOf<OcrPoint>()
            for (i in 0 until array.length()) {
                list.add(OcrPoint.fromJson(array.getJSONObject(i)))
            }
            return OcrPolygon(list)
        }
    }
}

data class OcrImageInfo(
    val localUri: String = "",
    val widthPx: Int,
    val heightPx: Int,
    val rotationDegrees: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("localUri", localUri)
        put("widthPx", widthPx)
        put("heightPx", heightPx)
        put("rotationDegrees", rotationDegrees)
    }

    companion object {
        fun fromJson(json: JSONObject): OcrImageInfo = OcrImageInfo(
            localUri = json.optString("localUri", ""),
            widthPx = json.optInt("widthPx", 0),
            heightPx = json.optInt("heightPx", 0),
            rotationDegrees = json.optInt("rotationDegrees", 0)
        )
    }
}

data class OcrToken(
    val tokenId: String,
    val text: String,
    val polygon: OcrPolygon? = null,
    val confidence: Float? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("tokenId", tokenId)
        put("text", text)
        polygon?.let { put("polygon", it.toJson()) }
        confidence?.let { put("confidence", it.toDouble()) }
    }

    companion object {
        fun fromJson(json: JSONObject): OcrToken = OcrToken(
            tokenId = json.getString("tokenId"),
            text = json.getString("text"),
            polygon = json.optJSONArray("polygon")?.let { OcrPolygon.fromJson(it) },
            confidence = if (json.has("confidence")) json.getDouble("confidence").toFloat() else null
        )
    }
}

data class OcrLine(
    val lineId: String,
    val text: String,
    val polygon: OcrPolygon? = null,
    val boundingBox: OcrRect? = null,
    val confidence: Float? = null,
    val tokens: List<OcrToken> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("lineId", lineId)
        put("text", text)
        polygon?.let { put("polygon", it.toJson()) }
        boundingBox?.let { put("boundingBox", it.toJson()) }
        confidence?.let { put("confidence", it.toDouble()) }
        if (tokens.isNotEmpty()) {
            val arr = JSONArray()
            tokens.forEach { arr.put(it.toJson()) }
            put("tokens", arr)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): OcrLine {
            val tokensList = mutableListOf<OcrToken>()
            json.optJSONArray("tokens")?.let { arr ->
                for (i in 0 until arr.length()) {
                    tokensList.add(OcrToken.fromJson(arr.getJSONObject(i)))
                }
            }
            return OcrLine(
                lineId = json.getString("lineId"),
                text = json.getString("text"),
                polygon = json.optJSONArray("polygon")?.let { OcrPolygon.fromJson(it) },
                boundingBox = json.optJSONObject("boundingBox")?.let { OcrRect.fromJson(it) },
                confidence = if (json.has("confidence")) json.getDouble("confidence").toFloat() else null,
                tokens = tokensList
            )
        }
    }
}

data class OcrBlock(
    val blockId: String,
    val boundingBox: OcrRect? = null,
    val lines: List<OcrLine> = emptyList(),
    val confidence: Float? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("blockId", blockId)
        boundingBox?.let { put("boundingBox", it.toJson()) }
        confidence?.let { put("confidence", it.toDouble()) }
        val arr = JSONArray()
        lines.forEach { arr.put(it.toJson()) }
        put("lines", arr)
    }

    companion object {
        fun fromJson(json: JSONObject): OcrBlock {
            val linesList = mutableListOf<OcrLine>()
            json.optJSONArray("lines")?.let { arr ->
                for (i in 0 until arr.length()) {
                    linesList.add(OcrLine.fromJson(arr.getJSONObject(i)))
                }
            }
            return OcrBlock(
                blockId = json.getString("blockId"),
                boundingBox = json.optJSONObject("boundingBox")?.let { OcrRect.fromJson(it) },
                lines = linesList,
                confidence = if (json.has("confidence")) json.getDouble("confidence").toFloat() else null
            )
        }
    }
}

data class OcrTableCell(
    val cellId: String,
    val rowIndex: Int,
    val colIndex: Int,
    val rowSpan: Int = 1,
    val colSpan: Int = 1,
    val rawText: String = "",
    val editedText: String = rawText,
    val cellType: OcrCellType = OcrCellType.TEXT,
    val confidence: Float? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("cellId", cellId)
        put("rowIndex", rowIndex)
        put("colIndex", colIndex)
        put("rowSpan", rowSpan)
        put("colSpan", colSpan)
        put("rawText", rawText)
        put("editedText", editedText)
        put("cellType", cellType.name)
        confidence?.let { put("confidence", it.toDouble()) }
    }

    companion object {
        fun fromJson(json: JSONObject): OcrTableCell = OcrTableCell(
            cellId = json.getString("cellId"),
            rowIndex = json.getInt("rowIndex"),
            colIndex = json.getInt("colIndex"),
            rowSpan = json.optInt("rowSpan", 1),
            colSpan = json.optInt("colSpan", 1),
            rawText = json.optString("rawText", ""),
            editedText = json.optString("editedText", json.optString("rawText", "")),
            cellType = try {
                OcrCellType.valueOf(json.optString("cellType", "TEXT"))
            } catch (_: Exception) {
                OcrCellType.TEXT
            },
            confidence = if (json.has("confidence")) json.getDouble("confidence").toFloat() else null
        )
    }
}

data class TableValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList()
)

data class OcrTable(
    val tableId: String,
    val rowCount: Int,
    val columnCount: Int,
    val boundingBox: OcrRect? = null,
    val cells: List<OcrTableCell> = emptyList()
) {
    fun getCell(row: Int, col: Int): OcrTableCell? {
        return cells.find { row in it.rowIndex until (it.rowIndex + it.rowSpan) && col in it.colIndex until (it.colIndex + it.colSpan) }
    }

    fun validateGrid(): TableValidationResult {
        val errors = mutableListOf<String>()
        if (rowCount <= 0 || columnCount <= 0) {
            errors.add("Table dimensions must be positive ($rowCount x $columnCount)")
            return TableValidationResult(false, errors)
        }

        val grid = Array(rowCount) { Array(columnCount) { false } }
        for (cell in cells) {
            if (cell.rowIndex < 0 || cell.rowIndex + cell.rowSpan > rowCount) {
                errors.add("Cell ${cell.cellId} row range [${cell.rowIndex}..${cell.rowIndex + cell.rowSpan}] out of bounds ($rowCount)")
            }
            if (cell.colIndex < 0 || cell.colIndex + cell.colSpan > columnCount) {
                errors.add("Cell ${cell.cellId} col range [${cell.colIndex}..${cell.colIndex + cell.colSpan}] out of bounds ($columnCount)")
            }
            for (r in cell.rowIndex until (cell.rowIndex + cell.rowSpan)) {
                for (c in cell.colIndex until (cell.colIndex + cell.colSpan)) {
                    if (r in 0 until rowCount && c in 0 until columnCount) {
                        if (grid[r][c]) {
                            errors.add("Overlapping cells at position ($r, $c) from cell ${cell.cellId}")
                        }
                        grid[r][c] = true
                    }
                }
            }
        }
        return TableValidationResult(errors.isEmpty(), errors)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("tableId", tableId)
        put("rowCount", rowCount)
        put("columnCount", columnCount)
        boundingBox?.let { put("boundingBox", it.toJson()) }
        val arr = JSONArray()
        cells.forEach { arr.put(it.toJson()) }
        put("cells", arr)
    }

    companion object {
        fun fromJson(json: JSONObject): OcrTable {
            val cellsList = mutableListOf<OcrTableCell>()
            json.optJSONArray("cells")?.let { arr ->
                for (i in 0 until arr.length()) {
                    cellsList.add(OcrTableCell.fromJson(arr.getJSONObject(i)))
                }
            }
            return OcrTable(
                tableId = json.getString("tableId"),
                rowCount = json.getInt("rowCount"),
                columnCount = json.getInt("columnCount"),
                boundingBox = json.optJSONObject("boundingBox")?.let { OcrRect.fromJson(it) },
                cells = cellsList
            )
        }
    }
}

data class OcrTextRun(
    val text: String,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val fontSizePt: Float = 11.0f
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("text", text)
        put("isBold", isBold)
        put("isItalic", isItalic)
        put("fontSizePt", fontSizePt.toDouble())
    }

    companion object {
        fun fromJson(json: JSONObject): OcrTextRun = OcrTextRun(
            text = json.getString("text"),
            isBold = json.optBoolean("isBold", false),
            isItalic = json.optBoolean("isItalic", false),
            fontSizePt = json.optDouble("fontSizePt", 11.0).toFloat()
        )
    }
}

data class OcrParagraph(
    val paragraphId: String,
    val sourceAnchorLineId: String? = null,
    val text: String,
    val alignment: OcrTextAlignment = OcrTextAlignment.LEFT,
    val runs: List<OcrTextRun> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("paragraphId", paragraphId)
        sourceAnchorLineId?.let { put("sourceAnchorLineId", it) }
        put("text", text)
        put("alignment", alignment.name)
        val arr = JSONArray()
        runs.forEach { arr.put(it.toJson()) }
        put("runs", arr)
    }

    companion object {
        fun fromJson(json: JSONObject): OcrParagraph {
            val runsList = mutableListOf<OcrTextRun>()
            json.optJSONArray("runs")?.let { arr ->
                for (i in 0 until arr.length()) {
                    runsList.add(OcrTextRun.fromJson(arr.getJSONObject(i)))
                }
            }
            return OcrParagraph(
                paragraphId = json.getString("paragraphId"),
                sourceAnchorLineId = json.optNullableString("sourceAnchorLineId"),
                text = json.getString("text"),
                alignment = try {
                    OcrTextAlignment.valueOf(json.optString("alignment", "LEFT"))
                } catch (_: Exception) {
                    OcrTextAlignment.LEFT
                },
                runs = runsList
            )
        }
    }
}

data class OcrEditedContent(
    val text: String = "",
    val paragraphs: List<OcrParagraph> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("text", text)
        val arr = JSONArray()
        paragraphs.forEach { arr.put(it.toJson()) }
        put("paragraphs", arr)
    }

    companion object {
        fun fromJson(json: JSONObject): OcrEditedContent {
            val parasList = mutableListOf<OcrParagraph>()
            json.optJSONArray("paragraphs")?.let { arr ->
                for (i in 0 until arr.length()) {
                    parasList.add(OcrParagraph.fromJson(arr.getJSONObject(i)))
                }
            }
            return OcrEditedContent(
                text = json.optString("text", ""),
                paragraphs = parasList
            )
        }
    }
}

data class OcrPage(
    val pageId: String,
    val pageIndex: Int,
    val status: OcrPageStatus = OcrPageStatus.PENDING,
    val imageInfo: OcrImageInfo? = null,
    val engineId: String? = null,
    val sourceLanguage: String? = null,
    val sourceBlocks: List<OcrBlock> = emptyList(),
    val tables: List<OcrTable> = emptyList(),
    val editedContent: OcrEditedContent? = null,
    val errorMessage: String? = null
) {
    /**
     * Resolves displayable/exportable text for this page.
     * Prefers editedContent.text if present, otherwise reconstructs from source blocks.
     */
    val resolvedText: String
        get() {
            editedContent?.let {
                return it.text
            }
            return sourceBlocks.joinToString("\n") { block ->
                block.lines.joinToString("\n") { it.text }
            }
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("pageId", pageId)
        put("pageIndex", pageIndex)
        put("status", status.name)
        imageInfo?.let { put("imageInfo", it.toJson()) }
        engineId?.let { put("engineId", it) }
        sourceLanguage?.let { put("sourceLanguage", it) }
        errorMessage?.let { put("errorMessage", it) }

        val bArr = JSONArray()
        sourceBlocks.forEach { bArr.put(it.toJson()) }
        put("sourceBlocks", bArr)

        val tArr = JSONArray()
        tables.forEach { tArr.put(it.toJson()) }
        put("tables", tArr)

        editedContent?.let { put("editedContent", it.toJson()) }
    }

    companion object {
        fun fromJson(json: JSONObject): OcrPage {
            val blocks = mutableListOf<OcrBlock>()
            json.optJSONArray("sourceBlocks")?.let { arr ->
                for (i in 0 until arr.length()) {
                    blocks.add(OcrBlock.fromJson(arr.getJSONObject(i)))
                }
            }
            val tbls = mutableListOf<OcrTable>()
            json.optJSONArray("tables")?.let { arr ->
                for (i in 0 until arr.length()) {
                    tbls.add(OcrTable.fromJson(arr.getJSONObject(i)))
                }
            }
            return OcrPage(
                pageId = json.getString("pageId"),
                pageIndex = json.getInt("pageIndex"),
                status = try {
                    OcrPageStatus.valueOf(json.getString("status"))
                } catch (_: Exception) {
                    OcrPageStatus.ERROR
                },
                imageInfo = json.optJSONObject("imageInfo")?.let { OcrImageInfo.fromJson(it) },
                engineId = json.optNullableString("engineId"),
                sourceLanguage = json.optNullableString("sourceLanguage"),
                sourceBlocks = blocks,
                tables = tbls,
                editedContent = json.optJSONObject("editedContent")?.let { OcrEditedContent.fromJson(it) },
                errorMessage = json.optNullableString("errorMessage")
            )
        }
    }
}

data class DocumentValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList()
)

data class OcrDocument(
    val id: String = "doc_" + UUID.randomUUID().toString(),
    val schemaVersion: Int = 1,
    val revision: Long = 1L,
    val title: String = "Scanned Document",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val sourceLanguage: String? = null,
    val pages: List<OcrPage> = emptyList(),
    /** True after a user has edited this document; kept separately from OCR-generated editedContent. */
    val hasUserEdits: Boolean = false,
    /** Repository assigned identity for the latest persisted write. */
    val lastCommitToken: String? = null
) {
    val totalPages: Int get() = pages.size
    val pagesWithText: Int get() = pages.count { it.status == OcrPageStatus.SUCCESS && it.resolvedText.isNotBlank() }
    val blankPages: Int get() = pages.count { it.status == OcrPageStatus.NO_TEXT }
    val errorPages: Int get() = pages.count { it.status == OcrPageStatus.ERROR || it.status == OcrPageStatus.UNSUPPORTED_LANGUAGE }

    val fullText: String
        get() = pages
            .filter { it.status == OcrPageStatus.SUCCESS }
            .joinToString("\n\n") { it.resolvedText }

    fun findPage(pageId: String): OcrPage? = pages.find { it.pageId == pageId }

    fun validate(): DocumentValidationResult {
        val errors = mutableListOf<String>()
        if (id.isBlank()) errors.add("Document ID cannot be blank")
        if (revision < 1L) errors.add("Revision must be >= 1")

        val pageIds = HashSet<String>()
        pages.forEachIndexed { idx, page ->
            val expectedIndex = idx + 1
            if (page.pageIndex != expectedIndex) {
                errors.add("Page at index $idx has invalid pageIndex ${page.pageIndex}, expected $expectedIndex")
            }
            if (!pageIds.add(page.pageId)) {
                errors.add("Duplicate pageId detected: ${page.pageId}")
            }
            page.tables.forEach { table ->
                val tableVal = table.validateGrid()
                if (!tableVal.isValid) {
                    errors.addAll(tableVal.errors.map { "Page ${page.pageIndex} Table ${table.tableId}: $it" })
                }
            }
        }
        return DocumentValidationResult(errors.isEmpty(), errors)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("schemaVersion", schemaVersion)
        put("revision", revision)
        put("title", title)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        sourceLanguage?.let { put("sourceLanguage", it) }
        put("hasUserEdits", hasUserEdits)
        lastCommitToken?.let { put("lastCommitToken", it) }

        val pArr = JSONArray()
        pages.forEach { pArr.put(it.toJson()) }
        put("pages", pArr)
    }

    fun toJsonString(indentSpaces: Int = 0): String =
        if (indentSpaces > 0) toJson().toString(indentSpaces) else toJson().toString()

    companion object {
        fun fromJson(json: JSONObject): OcrDocument {
            val pagesList = mutableListOf<OcrPage>()
            json.optJSONArray("pages")?.let { arr ->
                for (i in 0 until arr.length()) {
                    pagesList.add(OcrPage.fromJson(arr.getJSONObject(i)))
                }
            }
            return OcrDocument(
                id = json.getString("id"),
                schemaVersion = json.optInt("schemaVersion", 1),
                revision = json.optLong("revision", 1L),
                title = json.optString("title", "Scanned Document"),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                sourceLanguage = json.optNullableString("sourceLanguage"),
                // Legacy documents without provenance are treated conservatively: replacement
                // recognition must ask before discarding content whose edit status is unknown.
                hasUserEdits = json.optBoolean("hasUserEdits", true),
                lastCommitToken = json.optNullableString("lastCommitToken"),
                pages = pagesList
            )
        }

        fun fromJsonString(jsonStr: String): OcrDocument =
            fromJson(JSONObject(jsonStr))
    }
}
