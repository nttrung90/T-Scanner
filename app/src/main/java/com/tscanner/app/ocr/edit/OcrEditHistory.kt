package com.tscanner.app.ocr.edit

import com.tscanner.app.ocr.model.OcrDocument

/**
 * Manages undo/redo command stacks with bounded capacity and dirty tracking.
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S11).
 */
class OcrEditHistory(
    initialDocument: OcrDocument,
    val maxHistorySize: Int = 50
) {
    var currentDocument: OcrDocument = initialDocument
        private set

    private val undoStack = ArrayDeque<OcrEditCommand>()
    private val redoStack = ArrayDeque<OcrEditCommand>()

    var isDirty: Boolean = false
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoCount: Int get() = undoStack.size
    val redoCount: Int get() = redoStack.size

    /**
     * Executes a new edit command:
     * - Reduces document state via OcrEditReducer
     * - Pushes command to undo stack (enforcing maxHistorySize)
     * - Invalidates redo stack
     * - Sets dirty flag
     */
    @Synchronized
    fun execute(command: OcrEditCommand): OcrDocument {
        val cmdWithSnapshot = if (command is OcrEditCommand.ReplacePageText && command.oldEditedContent == null) {
            val targetPage = currentDocument.pages.firstOrNull {
                (command.pageId != null && it.pageId == command.pageId) ||
                (command.pageId == null && it.pageIndex == command.pageIndex)
            }
            command.copy(oldEditedContent = targetPage?.editedContent)
        } else {
            command
        }
        val beforeTable = when (cmdWithSnapshot) {
            is OcrEditCommand.DeleteTableRow -> currentDocument.pages
                .firstOrNull { it.pageIndex == cmdWithSnapshot.pageIndex }
                ?.tables?.firstOrNull { it.tableId == cmdWithSnapshot.tableId }
            is OcrEditCommand.DeleteTableColumn -> currentDocument.pages
                .firstOrNull { it.pageIndex == cmdWithSnapshot.pageIndex }
                ?.tables?.firstOrNull { it.tableId == cmdWithSnapshot.tableId }
            else -> null
        }
        val reduced = OcrEditReducer.apply(currentDocument, cmdWithSnapshot)
        val deletedTableId = when (cmdWithSnapshot) {
            is OcrEditCommand.DeleteTableRow -> cmdWithSnapshot.tableId
            is OcrEditCommand.DeleteTableColumn -> cmdWithSnapshot.tableId
            else -> null
        }
        val afterTable = when (cmdWithSnapshot) {
            is OcrEditCommand.DeleteTableRow,
            is OcrEditCommand.DeleteTableColumn -> deletedTableId?.let { tableId -> reduced.pages
                .firstOrNull { it.pageIndex == cmdWithSnapshot.pageIndex }
                ?.tables?.firstOrNull { it.tableId == tableId }
            }
            else -> null
        }
        val historyCommand = if (beforeTable != null && afterTable != null && beforeTable != afterTable) {
            OcrEditCommand.ReplaceTableSnapshot(
                pageIndex = cmdWithSnapshot.pageIndex,
                tableId = beforeTable.tableId,
                beforeTable = beforeTable,
                afterTable = afterTable,
                timestamp = cmdWithSnapshot.timestamp
            )
        } else cmdWithSnapshot
        currentDocument = reduced
        undoStack.addLast(historyCommand)
        if (undoStack.size > maxHistorySize) {
            undoStack.removeFirst()
        }
        redoStack.clear()
        isDirty = true
        return currentDocument
    }

    /**
     * Reverts the most recent command by applying its inverted command:
     * - Pops from undo stack
     * - Pushes to redo stack
     * - Sets dirty flag
     */
    @Synchronized
    fun undo(): OcrDocument? {
        if (!canUndo) return null
        val cmd = undoStack.removeLast()
        currentDocument = OcrEditReducer.apply(currentDocument, cmd.invert())
        redoStack.addLast(cmd)
        isDirty = true
        return currentDocument
    }

    /**
     * Re-applies the most recently reverted command:
     * - Pops from redo stack
     * - Pushes to undo stack
     * - Sets dirty flag
     */
    @Synchronized
    fun redo(): OcrDocument? {
        if (!canRedo) return null
        val cmd = redoStack.removeLast()
        currentDocument = OcrEditReducer.apply(currentDocument, cmd)
        undoStack.addLast(cmd)
        isDirty = true
        return currentDocument
    }

    /**
     * Resets the dirty flag (e.g. after successful disk commit or autosave).
     */
    @Synchronized
    fun markCommitted() {
        isDirty = false
    }

    /**
     * Acknowledges repository metadata without replacing the editable snapshot or its history.
     * When edits arrived while the saved snapshot was in flight, the current content stays dirty.
     */
    @Synchronized
    fun acknowledgeCommit(committedDocument: OcrDocument, noNewerEdits: Boolean): OcrDocument {
        if (committedDocument.id == currentDocument.id &&
            committedDocument.revision >= currentDocument.revision
        ) {
            currentDocument = currentDocument.copy(
                schemaVersion = committedDocument.schemaVersion,
                revision = committedDocument.revision,
                updatedAt = committedDocument.updatedAt,
                lastCommitToken = committedDocument.lastCommitToken
            )
            isDirty = !noNewerEdits
        }
        return currentDocument
    }

    /**
     * Produces a clean, immutable snapshot copy for export operations
     * that will not mutate if user continues editing.
     */
    @Synchronized
    fun createExportSnapshot(): OcrDocument {
        return currentDocument.copy()
    }
}
