package com.tscanner.app.ocr.edit

import com.tscanner.app.ocr.model.OcrCellType
import com.tscanner.app.ocr.model.OcrTable
import com.tscanner.app.ocr.model.OcrTableCell
import com.tscanner.app.ocr.model.OcrTextRun

/**
 * Atomic, invertible commands representing user modifications to OCR documents.
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S11).
 */
sealed class OcrEditCommand {
    abstract val pageIndex: Int
    open val pageId: String? get() = null
    abstract val timestamp: Long
    abstract fun invert(): OcrEditCommand

    /**
     * Replaces text on a page.
     */
    data class ReplacePageText(
        override val pageIndex: Int,
        val oldText: String,
        val newText: String,
        override val pageId: String? = null,
        val oldEditedContent: com.tscanner.app.ocr.model.OcrEditedContent? = null,
        val newEditedContent: com.tscanner.app.ocr.model.OcrEditedContent? = null,
        override val timestamp: Long = System.currentTimeMillis()
    ) : OcrEditCommand() {
        override fun invert(): OcrEditCommand = ReplacePageText(
            pageIndex = pageIndex,
            oldText = newText,
            newText = oldText,
            pageId = pageId,
            oldEditedContent = newEditedContent,
            newEditedContent = oldEditedContent,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Updates character run formatting (bold, italic, font size).
     */
    data class UpdateRunStyle(
        override val pageIndex: Int,
        val paragraphIndex: Int,
        val runIndex: Int,
        val oldRun: OcrTextRun,
        val newRun: OcrTextRun,
        override val timestamp: Long = System.currentTimeMillis()
    ) : OcrEditCommand() {
        override fun invert(): OcrEditCommand = UpdateRunStyle(
            pageIndex = pageIndex,
            paragraphIndex = paragraphIndex,
            runIndex = runIndex,
            oldRun = newRun,
            newRun = oldRun,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Sets text alignment for a paragraph (LEFT, CENTER, RIGHT, JUSTIFY).
     */
    data class SetParagraphAlignment(
        override val pageIndex: Int,
        val paragraphIndex: Int,
        val oldAlignment: com.tscanner.app.ocr.model.OcrTextAlignment,
        val newAlignment: com.tscanner.app.ocr.model.OcrTextAlignment,
        override val timestamp: Long = System.currentTimeMillis()
    ) : OcrEditCommand() {
        override fun invert(): OcrEditCommand = SetParagraphAlignment(
            pageIndex = pageIndex,
            paragraphIndex = paragraphIndex,
            oldAlignment = newAlignment,
            newAlignment = oldAlignment,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Replaces formatted paragraphs on a page.
     */
    data class ReplaceParagraphs(
        override val pageIndex: Int,
        val oldParagraphs: List<com.tscanner.app.ocr.model.OcrParagraph>,
        val newParagraphs: List<com.tscanner.app.ocr.model.OcrParagraph>,
        override val timestamp: Long = System.currentTimeMillis()
    ) : OcrEditCommand() {
        override fun invert(): OcrEditCommand = ReplaceParagraphs(
            pageIndex = pageIndex,
            oldParagraphs = newParagraphs,
            newParagraphs = oldParagraphs,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Updates text or cell type in a specific table cell.
     */
    data class UpdateTableCell(
        override val pageIndex: Int,
        val tableId: String,
        val cellId: String,
        val oldText: String,
        val newText: String,
        val oldCellType: OcrCellType,
        val newCellType: OcrCellType,
        override val timestamp: Long = System.currentTimeMillis()
    ) : OcrEditCommand() {
        override fun invert(): OcrEditCommand = UpdateTableCell(
            pageIndex = pageIndex,
            tableId = tableId,
            cellId = cellId,
            oldText = newText,
            newText = oldText,
            oldCellType = newCellType,
            newCellType = oldCellType,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Adds a new row to a table.
     */
    data class AddTableRow(
        override val pageIndex: Int,
        val tableId: String,
        val rowIndex: Int,
        val newCells: List<OcrTableCell>,
        override val timestamp: Long = System.currentTimeMillis()
    ) : OcrEditCommand() {
        override fun invert(): OcrEditCommand = DeleteTableRow(
            pageIndex = pageIndex,
            tableId = tableId,
            rowIndex = rowIndex,
            deletedCells = newCells,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Deletes a row from a table.
     */
    data class DeleteTableRow(
        override val pageIndex: Int,
        val tableId: String,
        val rowIndex: Int,
        val deletedCells: List<OcrTableCell>,
        override val timestamp: Long = System.currentTimeMillis()
    ) : OcrEditCommand() {
        override fun invert(): OcrEditCommand = AddTableRow(
            pageIndex = pageIndex,
            tableId = tableId,
            rowIndex = rowIndex,
            newCells = deletedCells,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Adds a new column to a table.
     */
    data class AddTableColumn(
        override val pageIndex: Int,
        val tableId: String,
        val colIndex: Int,
        val newCells: List<OcrTableCell>,
        override val timestamp: Long = System.currentTimeMillis()
    ) : OcrEditCommand() {
        override fun invert(): OcrEditCommand = DeleteTableColumn(
            pageIndex = pageIndex,
            tableId = tableId,
            colIndex = colIndex,
            deletedCells = newCells,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Deletes a column from a table.
     */
    data class DeleteTableColumn(
        override val pageIndex: Int,
        val tableId: String,
        val colIndex: Int,
        val deletedCells: List<OcrTableCell>,
        override val timestamp: Long = System.currentTimeMillis()
    ) : OcrEditCommand() {
        override fun invert(): OcrEditCommand = AddTableColumn(
            pageIndex = pageIndex,
            tableId = tableId,
            colIndex = colIndex,
            newCells = deletedCells,
            timestamp = System.currentTimeMillis()
        )
    }

    /** Replaces one table with an exact snapshot, used to make structural undo lossless. */
    data class ReplaceTableSnapshot(
        override val pageIndex: Int,
        val tableId: String,
        val beforeTable: OcrTable,
        val afterTable: OcrTable,
        override val timestamp: Long = System.currentTimeMillis()
    ) : OcrEditCommand() {
        override fun invert(): OcrEditCommand = ReplaceTableSnapshot(
            pageIndex = pageIndex,
            tableId = tableId,
            beforeTable = afterTable,
            afterTable = beforeTable,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Merges adjacent rectangular table cells into a single cell.
     */
    data class MergeCells(
        override val pageIndex: Int,
        val tableId: String,
        val originalCells: List<OcrTableCell>,
        val mergedCell: OcrTableCell,
        override val timestamp: Long = System.currentTimeMillis()
    ) : OcrEditCommand() {
        override fun invert(): OcrEditCommand = SplitCell(
            pageIndex = pageIndex,
            tableId = tableId,
            mergedCell = mergedCell,
            restoredCells = originalCells,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Splits a merged cell back into its component cells.
     */
    data class SplitCell(
        override val pageIndex: Int,
        val tableId: String,
        val mergedCell: OcrTableCell,
        val restoredCells: List<OcrTableCell>,
        override val timestamp: Long = System.currentTimeMillis()
    ) : OcrEditCommand() {
        override fun invert(): OcrEditCommand = MergeCells(
            pageIndex = pageIndex,
            tableId = tableId,
            originalCells = restoredCells,
            mergedCell = mergedCell,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Adds a newly created table to a page.
     */
    data class AddTable(
        override val pageIndex: Int,
        val table: OcrTable,
        override val timestamp: Long = System.currentTimeMillis()
    ) : OcrEditCommand() {
        override fun invert(): OcrEditCommand = DeleteTable(
            pageIndex = pageIndex,
            table = table,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Deletes a table from a page.
     */
    data class DeleteTable(
        override val pageIndex: Int,
        val table: OcrTable,
        override val timestamp: Long = System.currentTimeMillis()
    ) : OcrEditCommand() {
        override fun invert(): OcrEditCommand = AddTable(
            pageIndex = pageIndex,
            table = table,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Composite command grouping multiple atomic operations into a single undoable step.
     */
    data class CompositeCommand(
        val commands: List<OcrEditCommand>,
        override val pageIndex: Int = commands.firstOrNull()?.pageIndex ?: 1,
        override val timestamp: Long = System.currentTimeMillis()
    ) : OcrEditCommand() {
        override fun invert(): OcrEditCommand = CompositeCommand(
            commands = commands.reversed().map { it.invert() },
            pageIndex = pageIndex,
            timestamp = System.currentTimeMillis()
        )
    }
}
