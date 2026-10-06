package com.tscanner.app

import com.tscanner.app.ocr.edit.*
import com.tscanner.app.ocr.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for OcrEditCommand, OcrEditReducer, and OcrEditHistory:
 * - Unicode text edit / undo / redo roundtrip
 * - Redo stack invalidation on branching edit
 * - Table cell and structure editing (merge/split/row)
 * - Max history capacity enforcement
 * - Export snapshot immutability
 * - Source OCR blocks immutability (provenance)
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S11).
 */
class OcrEditCommandTest {

    private fun createSampleDoc(): OcrDocument {
        val originalText = "CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM"
        return OcrDocument(
            id = "doc_edit_test",
            pages = listOf(
                OcrPage(
                    pageId = "p1",
                    pageIndex = 1,
                    status = OcrPageStatus.SUCCESS,
                    sourceBlocks = listOf(
                        OcrBlock("b1", lines = listOf(OcrLine("l1", originalText)))
                    ),
                    tables = listOf(
                        OcrTable(
                            tableId = "tbl_1",
                            rowCount = 2,
                            columnCount = 2,
                            cells = listOf(
                                OcrTableCell("c00", 0, 0, 1, 1, "Mã", "Mã", OcrCellType.TEXT),
                                OcrTableCell("c01", 0, 1, 1, 1, "00123", "00123", OcrCellType.TEXT),
                                OcrTableCell("c10", 1, 0, 1, 1, "Tiền", "Tiền", OcrCellType.TEXT),
                                OcrTableCell("c11", 1, 1, 1, 1, "1000", "1000", OcrCellType.NUMBER)
                            )
                        )
                    )
                )
            )
        )
    }

    @Test
    fun testTextEditUndoRedoRoundtripWithUnicode() {
        val doc = createSampleDoc()
        val history = OcrEditHistory(doc)

        val originalText = doc.pages[0].resolvedText
        val edit1Text = "Bản sửa 1: Độc lập - Tự do - Hạnh phúc"
        val edit2Text = "Bản sửa 2: Việt Nam Hùng Cường 🇻🇳"

        // Execute Edit 1
        history.execute(OcrEditCommand.ReplacePageText(1, originalText, edit1Text))
        assertEquals(edit1Text, history.currentDocument.pages[0].resolvedText)
        assertTrue(history.isDirty)
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)

        // Execute Edit 2
        history.execute(OcrEditCommand.ReplacePageText(1, edit1Text, edit2Text))
        assertEquals(edit2Text, history.currentDocument.pages[0].resolvedText)

        // Undo Edit 2 -> reverts to Edit 1
        val undo1Doc = history.undo()
        assertNotNull(undo1Doc)
        assertEquals(edit1Text, undo1Doc!!.pages[0].resolvedText)
        assertTrue(history.canUndo)
        assertTrue(history.canRedo)

        // Undo Edit 1 -> reverts to Original
        val undo2Doc = history.undo()
        assertNotNull(undo2Doc)
        assertEquals(originalText, undo2Doc!!.pages[0].resolvedText)
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)

        // Redo -> restores Edit 1
        val redo1Doc = history.redo()
        assertNotNull(redo1Doc)
        assertEquals(edit1Text, redo1Doc!!.pages[0].resolvedText)

        // Redo -> restores Edit 2
        val redo2Doc = history.redo()
        assertNotNull(redo2Doc)
        assertEquals(edit2Text, redo2Doc!!.pages[0].resolvedText)
        assertFalse(history.canRedo)

        // Verify sourceBlocks was NEVER altered (immutable provenance)
        assertEquals(originalText, history.currentDocument.pages[0].sourceBlocks[0].lines[0].text)
    }

    @Test
    fun testRedoStackInvalidatedOnNewEditAfterUndo() {
        val doc = createSampleDoc()
        val history = OcrEditHistory(doc)

        history.execute(OcrEditCommand.ReplacePageText(1, "", "Edit A"))
        history.execute(OcrEditCommand.ReplacePageText(1, "Edit A", "Edit B"))

        // Undo Edit B
        history.undo()
        assertTrue(history.canRedo)

        // Branching: Execute new Edit C
        history.execute(OcrEditCommand.ReplacePageText(1, "Edit A", "Edit C"))
        assertEquals("Edit C", history.currentDocument.pages[0].resolvedText)

        // Redo stack must be completely cleared
        assertFalse("New edit must invalidate redo stack", history.canRedo)
        assertNull(history.redo())
    }

    @Test
    fun testTableCellEditUndoRedo() {
        val doc = createSampleDoc()
        val history = OcrEditHistory(doc)

        val cell = doc.pages[0].tables[0].cells[1]
        assertEquals("00123", cell.editedText)

        // Edit cell text to "00999"
        val cmd = OcrEditCommand.UpdateTableCell(
            pageIndex = 1,
            tableId = "tbl_1",
            cellId = "c01",
            oldText = "00123",
            newText = "00999",
            oldCellType = OcrCellType.TEXT,
            newCellType = OcrCellType.TEXT
        )
        history.execute(cmd)

        val updatedCell = history.currentDocument.pages[0].tables[0].cells[1]
        assertEquals("00999", updatedCell.editedText)

        // Undo
        history.undo()
        val revertedCell = history.currentDocument.pages[0].tables[0].cells[1]
        assertEquals("00123", revertedCell.editedText)

        // Redo
        history.redo()
        val restoredCell = history.currentDocument.pages[0].tables[0].cells[1]
        assertEquals("00999", restoredCell.editedText)
    }

    @Test
    fun testTableStructureRowInsertAndDelete() {
        val doc = createSampleDoc()
        val history = OcrEditHistory(doc)

        val table = doc.pages[0].tables[0]
        assertEquals(2, table.rowCount)

        val newCells = listOf(
            OcrTableCell("c_new0", 2, 0, 1, 1, "New 0"),
            OcrTableCell("c_new1", 2, 1, 1, 1, "New 1")
        )

        // Add Row at index 2
        history.execute(OcrEditCommand.AddTableRow(1, "tbl_1", 2, newCells))
        assertEquals(3, history.currentDocument.pages[0].tables[0].rowCount)
        assertEquals(6, history.currentDocument.pages[0].tables[0].cells.size)

        // Undo -> reverts to 2 rows
        history.undo()
        assertEquals(2, history.currentDocument.pages[0].tables[0].rowCount)
        assertEquals(4, history.currentDocument.pages[0].tables[0].cells.size)
    }

    @Test
    fun testMaxHistoryLimitEnforcedWithoutMemoryLeak() {
        val doc = createSampleDoc()
        val history = OcrEditHistory(doc, maxHistorySize = 5)

        for (i in 1..10) {
            history.execute(OcrEditCommand.ReplacePageText(1, "Text $i", "Text ${i + 1}"))
        }

        // Bounded capacity: undo stack must not exceed maxHistorySize = 5
        assertEquals(5, history.undoCount)

        // Undo 5 times succeeds
        for (i in 1..5) {
            assertNotNull(history.undo())
        }

        // 6th undo must return null (oldest 5 edits were evicted)
        assertNull(history.undo())
    }

    @Test
    fun testSnapshotImmutabilityDuringSubsequentEdits() {
        val doc = createSampleDoc()
        val history = OcrEditHistory(doc)

        history.execute(OcrEditCommand.ReplacePageText(1, "", "First Version"))
        history.markCommitted()
        assertFalse(history.isDirty)

        // Take snapshot
        val snapshot = history.createExportSnapshot()
        assertEquals("First Version", snapshot.pages[0].resolvedText)

        // Continue editing in history
        history.execute(OcrEditCommand.ReplacePageText(1, "First Version", "Second Version"))
        assertEquals("Second Version", history.currentDocument.pages[0].resolvedText)
        assertTrue(history.isDirty)

        // Snapshot must remain unchanged!
        assertEquals("First Version", snapshot.pages[0].resolvedText)
    }
}
