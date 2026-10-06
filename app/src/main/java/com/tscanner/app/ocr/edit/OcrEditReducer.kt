package com.tscanner.app.ocr.edit

import com.tscanner.app.ocr.model.*

/**
 * Pure state reducer applying or reverting edit commands on OcrDocument.
 * Guaranteed to never mutate original source blocks (immutable provenance).
 *
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S11).
 */
object OcrEditReducer {

    private data class StyledSourceChar(
        val run: OcrTextRun,
        val paragraphIndex: Int
    )

    fun apply(document: OcrDocument, command: OcrEditCommand): OcrDocument {
        return when (command) {
            is OcrEditCommand.ReplacePageText -> applyReplacePageText(document, command)
            is OcrEditCommand.UpdateRunStyle -> applyUpdateRunStyle(document, command)
            is OcrEditCommand.SetParagraphAlignment -> applySetParagraphAlignment(document, command)
            is OcrEditCommand.ReplaceParagraphs -> applyReplaceParagraphs(document, command)
            is OcrEditCommand.UpdateTableCell -> applyUpdateTableCell(document, command)
            is OcrEditCommand.AddTableRow -> applyAddTableRow(document, command)
            is OcrEditCommand.DeleteTableRow -> applyDeleteTableRow(document, command)
            is OcrEditCommand.AddTableColumn -> applyAddTableColumn(document, command)
            is OcrEditCommand.DeleteTableColumn -> applyDeleteTableColumn(document, command)
            is OcrEditCommand.ReplaceTableSnapshot -> applyReplaceTableSnapshot(document, command)
            is OcrEditCommand.MergeCells -> applyMergeCells(document, command)
            is OcrEditCommand.SplitCell -> applySplitCell(document, command)
            is OcrEditCommand.AddTable -> applyAddTable(document, command)
            is OcrEditCommand.DeleteTable -> applyDeleteTable(document, command)
            is OcrEditCommand.CompositeCommand -> {
                command.commands.fold(document) { doc, subCommand ->
                    apply(doc, subCommand)
                }
            }
        }.copy(hasUserEdits = true)
    }

    private fun applyReplacePageText(
        doc: OcrDocument,
        cmd: OcrEditCommand.ReplacePageText
    ): OcrDocument {
        val updatedPages = doc.pages.map { page ->
            val matches = (cmd.pageId != null && page.pageId == cmd.pageId) ||
                          (cmd.pageId == null && page.pageIndex == cmd.pageIndex)
            if (matches) {
                if (cmd.newEditedContent != null) {
                    page.copy(editedContent = cmd.newEditedContent)
                } else {
                    val currentEdited = page.editedContent ?: OcrEditedContent()
                    val newParas = if (cmd.newText.isEmpty()) {
                        emptyList()
                    } else {
                        transformParagraphsPreservingText(currentEdited, cmd.newText, cmd.pageIndex, cmd.timestamp)
                    }
                    val updatedEdited = currentEdited.copy(
                        text = cmd.newText,
                        paragraphs = newParas
                    )
                    page.copy(editedContent = updatedEdited)
                }
            } else {
                page
            }
        }
        return doc.copy(pages = updatedPages, updatedAt = cmd.timestamp)
    }

    /** Maps unchanged prefix/suffix characters across paragraph boundaries, preserving their runs. */
    private fun transformParagraphsPreservingText(
        edited: OcrEditedContent,
        newText: String,
        pageIndex: Int,
        timestamp: Long
    ): List<OcrParagraph> {
        val oldParagraphs = when {
            edited.paragraphs.isNotEmpty() -> edited.paragraphs
            edited.text.isNotEmpty() -> listOf(
                OcrParagraph("p_${pageIndex}_0", text = edited.text, runs = listOf(OcrTextRun(edited.text)))
            )
            else -> emptyList()
        }
        val oldText = if (edited.paragraphs.isNotEmpty()) {
            edited.paragraphs.joinToString("\n") { it.text }
        } else {
            edited.text
        }

        val oldCharStyles = arrayOfNulls<StyledSourceChar>(oldText.length)
        var paragraphStart = 0
        oldParagraphs.forEachIndexed { paragraphIndex, paragraph ->
            var runEnd = 0
            val runEnds = paragraph.runs.map { run ->
                runEnd += run.text.length
                runEnd
            }
            for (charIndex in paragraph.text.indices) {
                val runIndex = runEnds.indexOfFirst { charIndex < it }
                val run = paragraph.runs.getOrNull(runIndex) ?: paragraph.runs.lastOrNull() ?: OcrTextRun("")
                oldCharStyles[paragraphStart + charIndex] = StyledSourceChar(run, paragraphIndex)
            }
            paragraphStart += paragraph.text.length + 1
        }

        var prefixLength = 0
        val commonLimit = minOf(oldText.length, newText.length)
        while (prefixLength < commonLimit && oldText[prefixLength] == newText[prefixLength]) {
            prefixLength++
        }
        var suffixLength = 0
        while (suffixLength < oldText.length - prefixLength && suffixLength < newText.length - prefixLength &&
            oldText[oldText.lastIndex - suffixLength] == newText[newText.lastIndex - suffixLength]
        ) {
            suffixLength++
        }

        fun sourceIndexAt(outputIndex: Int): Int? = when {
            outputIndex < prefixLength -> outputIndex
            outputIndex >= newText.length - suffixLength ->
                oldText.length - suffixLength + outputIndex - (newText.length - suffixLength)
            else -> null
        }

        val insertedStyle = oldCharStyles.getOrNull(prefixLength - 1)?.run
            ?: oldCharStyles.getOrNull(prefixLength)?.run
            ?: oldParagraphs.firstOrNull()?.runs?.firstOrNull()
            ?: OcrTextRun("")

        val lines = newText.split('\n')
        val lineStarts = IntArray(lines.size)
        var nextStart = 0
        lines.forEachIndexed { index, line ->
            lineStarts[index] = nextStart
            nextStart += line.length + 1
        }

        val contributions = Array(oldParagraphs.size) { IntArray(lines.size) }
        lines.forEachIndexed { lineIndex, line ->
            val start = lineStarts[lineIndex]
            for (offset in line.indices) {
                val sourceIndex = sourceIndexAt(start + offset) ?: continue
                val source = oldCharStyles.getOrNull(sourceIndex) ?: continue
                contributions[source.paragraphIndex][lineIndex]++
            }
        }

        val primaryLineForParagraph = IntArray(oldParagraphs.size) { -1 }
        contributions.forEachIndexed { paragraphIndex, lineCounts ->
            var bestCount = 0
            lineCounts.forEachIndexed { lineIndex, count ->
                if (count > bestCount) {
                    bestCount = count
                    primaryLineForParagraph[paragraphIndex] = lineIndex
                }
            }
        }

        val nearestParagraph = run {
            val before = (prefixLength - 1 downTo 0).firstNotNullOfOrNull { oldCharStyles.getOrNull(it)?.paragraphIndex }
            before ?: (prefixLength until oldText.length).firstNotNullOfOrNull { oldCharStyles.getOrNull(it)?.paragraphIndex }
        }

        val usedIds = mutableSetOf<String>()
        return lines.mapIndexed { lineIndex, line ->
            val ownerIndex = contributions.indices.maxByOrNull { contributions[it][lineIndex] }
                ?.takeIf { contributions[it][lineIndex] > 0 }
            val owner = ownerIndex?.let(oldParagraphs::get)
            val keepOriginalIdentity = ownerIndex != null && primaryLineForParagraph[ownerIndex] == lineIndex &&
                owner!!.paragraphId !in usedIds
            val paragraphId = if (keepOriginalIdentity) {
                owner!!.paragraphId.also(usedIds::add)
            } else {
                "edit_${pageIndex}_${timestamp}_$lineIndex".also(usedIds::add)
            }

            val lineRuns = mutableListOf<OcrTextRun>()
            val lineStart = lineStarts[lineIndex]
            for (offset in line.indices) {
                val sourceIndex = sourceIndexAt(lineStart + offset)
                val style = sourceIndex?.let { oldCharStyles.getOrNull(it)?.run } ?: insertedStyle
                val last = lineRuns.lastOrNull()
                if (last != null && last.copy(text = "") == style.copy(text = "")) {
                    lineRuns[lineRuns.lastIndex] = last.copy(text = last.text + line[offset])
                } else {
                    lineRuns.add(style.copy(text = line[offset].toString()))
                }
            }

            val alignmentSource = owner ?: nearestParagraph?.let(oldParagraphs::get)
            OcrParagraph(
                paragraphId = paragraphId,
                sourceAnchorLineId = if (keepOriginalIdentity) owner?.sourceAnchorLineId else null,
                text = line,
                alignment = alignmentSource?.alignment ?: OcrTextAlignment.LEFT,
                runs = lineRuns
            )
        }
    }

    private fun applyUpdateRunStyle(
        doc: OcrDocument,
        cmd: OcrEditCommand.UpdateRunStyle
    ): OcrDocument {
        val updatedPages = doc.pages.map { page ->
            if (page.pageIndex == cmd.pageIndex) {
                val currentEdited = page.editedContent ?: OcrEditedContent(text = page.resolvedText)
                val updatedParas = currentEdited.paragraphs.mapIndexed { pIdx, para ->
                    if (pIdx == cmd.paragraphIndex) {
                        val updatedRuns = para.runs.mapIndexed { rIdx, run ->
                            if (rIdx == cmd.runIndex) cmd.newRun else run
                        }
                        para.copy(runs = updatedRuns)
                    } else {
                        para
                    }
                }
                page.copy(editedContent = currentEdited.copy(paragraphs = updatedParas))
            } else {
                page
            }
        }
        return doc.copy(pages = updatedPages, updatedAt = cmd.timestamp)
    }

    private fun applySetParagraphAlignment(
        doc: OcrDocument,
        cmd: OcrEditCommand.SetParagraphAlignment
    ): OcrDocument {
        val updatedPages = doc.pages.map { page ->
            if (page.pageIndex == cmd.pageIndex) {
                val currentEdited = page.editedContent ?: OcrEditedContent(text = page.resolvedText)
                val updatedParas = currentEdited.paragraphs.mapIndexed { pIdx, para ->
                    if (pIdx == cmd.paragraphIndex) {
                        para.copy(alignment = cmd.newAlignment)
                    } else {
                        para
                    }
                }
                page.copy(editedContent = currentEdited.copy(paragraphs = updatedParas))
            } else {
                page
            }
        }
        return doc.copy(pages = updatedPages, updatedAt = cmd.timestamp)
    }

    private fun applyReplaceParagraphs(
        doc: OcrDocument,
        cmd: OcrEditCommand.ReplaceParagraphs
    ): OcrDocument {
        val updatedPages = doc.pages.map { page ->
            if (page.pageIndex == cmd.pageIndex) {
                val fullText = cmd.newParagraphs.joinToString("\n") { it.text }
                val currentEdited = (page.editedContent ?: OcrEditedContent()).copy(
                    text = fullText,
                    paragraphs = cmd.newParagraphs
                )
                page.copy(editedContent = currentEdited)
            } else {
                page
            }
        }
        return doc.copy(pages = updatedPages, updatedAt = cmd.timestamp)
    }

    private fun applyUpdateTableCell(
        doc: OcrDocument,
        cmd: OcrEditCommand.UpdateTableCell
    ): OcrDocument {
        val updatedPages = doc.pages.map { page ->
            if (page.pageIndex == cmd.pageIndex) {
                val updatedTables = page.tables.map { table ->
                    if (table.tableId == cmd.tableId) {
                        val updatedCells = table.cells.map { cell ->
                            if (cell.cellId == cmd.cellId) {
                                cell.copy(
                                    editedText = cmd.newText,
                                    cellType = cmd.newCellType
                                )
                            } else {
                                cell
                            }
                        }
                        table.copy(cells = updatedCells)
                    } else {
                        table
                    }
                }
                page.copy(tables = updatedTables)
            } else {
                page
            }
        }
        return doc.copy(pages = updatedPages, updatedAt = cmd.timestamp)
    }

    private fun applyAddTableRow(
        doc: OcrDocument,
        cmd: OcrEditCommand.AddTableRow
    ): OcrDocument {
        val updatedPages = doc.pages.map { page ->
            val matches = (cmd.pageId != null && page.pageId == cmd.pageId) ||
                          (cmd.pageId == null && page.pageIndex == cmd.pageIndex)
            if (matches) {
                val updatedTables = page.tables.map { table ->
                    if (table.tableId == cmd.tableId) {
                        require(cmd.rowIndex in 0..table.rowCount) {
                            "Inserted row ${cmd.rowIndex} is outside table row range 0..${table.rowCount}"
                        }
                        require(cmd.newCells.all { it.rowIndex == cmd.rowIndex && it.rowSpan == 1 }) {
                            "New row cells must originate at the inserted row and have rowSpan 1"
                        }

                        val adjustedCells = table.cells.map { cell ->
                            when {
                                cell.rowIndex >= cmd.rowIndex -> cell.copy(rowIndex = cell.rowIndex + 1)
                                cmd.rowIndex < cell.rowIndex + cell.rowSpan -> cell.copy(rowSpan = cell.rowSpan + 1)
                                else -> cell
                            }
                        }
                        val allCells = (adjustedCells + cmd.newCells).sortedWith(
                            compareBy({ it.rowIndex }, { it.colIndex })
                        )
                        val updated = table.copy(
                            rowCount = table.rowCount + 1,
                            cells = allCells
                        )
                        val validation = updated.validateGrid()
                        require(validation.isValid) {
                            "Inserted row creates an invalid table grid: ${validation.errors.joinToString()}"
                        }
                        updated
                    } else {
                        table
                    }
                }
                page.copy(tables = updatedTables)
            } else {
                page
            }
        }
        return doc.copy(pages = updatedPages, updatedAt = cmd.timestamp)
    }

    private fun applyDeleteTableRow(
        doc: OcrDocument,
        cmd: OcrEditCommand.DeleteTableRow
    ): OcrDocument {
        val updatedPages = doc.pages.map { page ->
            val matches = (cmd.pageId != null && page.pageId == cmd.pageId) ||
                          (cmd.pageId == null && page.pageIndex == cmd.pageIndex)
            if (matches) {
                val updatedTables = page.tables.map { table ->
                    if (table.tableId == cmd.tableId) {
                        val targetRow = cmd.rowIndex
                        val updatedCells = mutableListOf<OcrTableCell>()
                        for (cell in table.cells) {
                            when {
                                cell.rowIndex > targetRow -> {
                                    updatedCells.add(cell.copy(rowIndex = cell.rowIndex - 1))
                                }
                                targetRow >= cell.rowIndex && targetRow < cell.rowIndex + cell.rowSpan -> {
                                    if (cell.rowSpan > 1) {
                                        updatedCells.add(cell.copy(rowSpan = cell.rowSpan - 1))
                                    }
                                }
                                else -> {
                                    updatedCells.add(cell)
                                }
                            }
                        }
                        table.copy(
                            rowCount = (table.rowCount - 1).coerceAtLeast(1),
                            cells = updatedCells
                        )
                    } else {
                        table
                    }
                }
                page.copy(tables = updatedTables)
            } else {
                page
            }
        }
        return doc.copy(pages = updatedPages, updatedAt = cmd.timestamp)
    }

    private fun applyAddTableColumn(
        doc: OcrDocument,
        cmd: OcrEditCommand.AddTableColumn
    ): OcrDocument {
        val updatedPages = doc.pages.map { page ->
            val matches = (cmd.pageId != null && page.pageId == cmd.pageId) ||
                          (cmd.pageId == null && page.pageIndex == cmd.pageIndex)
            if (matches) {
                val updatedTables = page.tables.map { table ->
                    if (table.tableId == cmd.tableId) {
                        require(cmd.colIndex in 0..table.columnCount) {
                            "Inserted column ${cmd.colIndex} is outside table column range 0..${table.columnCount}"
                        }
                        require(cmd.newCells.all { it.colIndex == cmd.colIndex && it.colSpan == 1 }) {
                            "New column cells must originate at the inserted column and have colSpan 1"
                        }

                        val adjustedCells = table.cells.map { cell ->
                            when {
                                cell.colIndex >= cmd.colIndex -> cell.copy(colIndex = cell.colIndex + 1)
                                cmd.colIndex < cell.colIndex + cell.colSpan -> cell.copy(colSpan = cell.colSpan + 1)
                                else -> cell
                            }
                        }
                        val allCells = (adjustedCells + cmd.newCells).sortedWith(
                            compareBy({ it.rowIndex }, { it.colIndex })
                        )
                        val updated = table.copy(
                            columnCount = table.columnCount + 1,
                            cells = allCells
                        )
                        val validation = updated.validateGrid()
                        require(validation.isValid) {
                            "Inserted column creates an invalid table grid: ${validation.errors.joinToString()}"
                        }
                        updated
                    } else {
                        table
                    }
                }
                page.copy(tables = updatedTables)
            } else {
                page
            }
        }
        return doc.copy(pages = updatedPages, updatedAt = cmd.timestamp)
    }

    private fun applyDeleteTableColumn(
        doc: OcrDocument,
        cmd: OcrEditCommand.DeleteTableColumn
    ): OcrDocument {
        val updatedPages = doc.pages.map { page ->
            val matches = (cmd.pageId != null && page.pageId == cmd.pageId) ||
                          (cmd.pageId == null && page.pageIndex == cmd.pageIndex)
            if (matches) {
                val updatedTables = page.tables.map { table ->
                    if (table.tableId == cmd.tableId) {
                        val targetCol = cmd.colIndex
                        val updatedCells = mutableListOf<OcrTableCell>()
                        for (cell in table.cells) {
                            when {
                                cell.colIndex > targetCol -> {
                                    updatedCells.add(cell.copy(colIndex = cell.colIndex - 1))
                                }
                                targetCol >= cell.colIndex && targetCol < cell.colIndex + cell.colSpan -> {
                                    if (cell.colSpan > 1) {
                                        updatedCells.add(cell.copy(colSpan = cell.colSpan - 1))
                                    }
                                }
                                else -> {
                                    updatedCells.add(cell)
                                }
                            }
                        }
                        table.copy(
                            columnCount = (table.columnCount - 1).coerceAtLeast(1),
                            cells = updatedCells
                        )
                    } else {
                        table
                    }
                }
                page.copy(tables = updatedTables)
            } else {
                page
            }
        }
        return doc.copy(pages = updatedPages, updatedAt = cmd.timestamp)
    }

    private fun applyReplaceTableSnapshot(
        doc: OcrDocument,
        cmd: OcrEditCommand.ReplaceTableSnapshot
    ): OcrDocument {
        val updatedPages = doc.pages.map { page ->
            if (page.pageIndex == cmd.pageIndex) {
                page.copy(tables = page.tables.map { table ->
                    if (table.tableId == cmd.tableId) cmd.afterTable else table
                })
            } else page
        }
        return doc.copy(pages = updatedPages, updatedAt = cmd.timestamp)
    }

    private fun applyMergeCells(
        doc: OcrDocument,
        cmd: OcrEditCommand.MergeCells
    ): OcrDocument {
        val updatedPages = doc.pages.map { page ->
            if (page.pageIndex == cmd.pageIndex) {
                val updatedTables = page.tables.map { table ->
                    if (table.tableId == cmd.tableId) {
                        val originalIds = cmd.originalCells.map { it.cellId }.toSet()
                        val remainingCells = table.cells.filter { !originalIds.contains(it.cellId) }
                        val allCells = (remainingCells + cmd.mergedCell).sortedWith(
                            compareBy({ it.rowIndex }, { it.colIndex })
                        )
                        table.copy(cells = allCells)
                    } else {
                        table
                    }
                }
                page.copy(tables = updatedTables)
            } else {
                page
            }
        }
        return doc.copy(pages = updatedPages, updatedAt = cmd.timestamp)
    }

    private fun applySplitCell(
        doc: OcrDocument,
        cmd: OcrEditCommand.SplitCell
    ): OcrDocument {
        val updatedPages = doc.pages.map { page ->
            if (page.pageIndex == cmd.pageIndex) {
                val updatedTables = page.tables.map { table ->
                    if (table.tableId == cmd.tableId) {
                        val remainingCells = table.cells.filter { it.cellId != cmd.mergedCell.cellId }
                        val allCells = (remainingCells + cmd.restoredCells).sortedWith(
                            compareBy({ it.rowIndex }, { it.colIndex })
                        )
                        table.copy(cells = allCells)
                    } else {
                        table
                    }
                }
                page.copy(tables = updatedTables)
            } else {
                page
            }
        }
        return doc.copy(pages = updatedPages, updatedAt = cmd.timestamp)
    }

    private fun applyAddTable(
        doc: OcrDocument,
        cmd: OcrEditCommand.AddTable
    ): OcrDocument {
        val updatedPages = doc.pages.map { page ->
            if (page.pageIndex == cmd.pageIndex) {
                page.copy(tables = page.tables + cmd.table)
            } else {
                page
            }
        }
        return doc.copy(pages = updatedPages, updatedAt = cmd.timestamp)
    }

    private fun applyDeleteTable(
        doc: OcrDocument,
        cmd: OcrEditCommand.DeleteTable
    ): OcrDocument {
        val updatedPages = doc.pages.map { page ->
            if (page.pageIndex == cmd.pageIndex) {
                page.copy(tables = page.tables.filter { it.tableId != cmd.table.tableId })
            } else {
                page
            }
        }
        return doc.copy(pages = updatedPages, updatedAt = cmd.timestamp)
    }
}
