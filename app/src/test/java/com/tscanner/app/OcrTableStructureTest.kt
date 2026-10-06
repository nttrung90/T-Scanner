package com.tscanner.app

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.tscanner.app.ocr.data.OcrDocumentRepository
import com.tscanner.app.ocr.model.*
import com.tscanner.app.ui.ocr.reader.OcrReaderViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for structural table operations:
 * - Adding and deleting rows
 * - Adding and deleting columns
 * - Cell merge preserving contents without silent loss
 * - Splitting cells
 * - Non-overlapping grid validation across structural transformations
 * - Atomic undo/redo of structure and values
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S18).
 */
class OcrTableStructureTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class TestApplication(private val baseDir: File) : Application() {
        override fun getFilesDir(): File = baseDir
    }

    private lateinit var app: Application
    private lateinit var baseDir: File
    private lateinit var repository: OcrDocumentRepository
    private val testScope = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("ocr_struct_test")
        repository = OcrDocumentRepository(baseDir)
        app = TestApplication(baseDir)
    }

    private fun createSample2x2TableDoc(): OcrDocument {
        val cells = listOf(
            OcrTableCell("c00", 0, 0, 1, 1, "Họ và tên", "Họ và tên", OcrCellType.TEXT),
            OcrTableCell("c01", 0, 1, 1, 1, "Nguyễn Văn A", "Nguyễn Văn A", OcrCellType.TEXT),
            OcrTableCell("c10", 1, 0, 1, 1, "Số điện thoại", "Số điện thoại", OcrCellType.TEXT),
            OcrTableCell("c11", 1, 1, 1, 1, "0912345678", "0912345678", OcrCellType.TEXT)
        )
        val table = OcrTable(
            tableId = "tbl_struct",
            rowCount = 2,
            columnCount = 2,
            cells = cells
        )
        return OcrDocument(
            id = "doc_struct_01",
            pages = listOf(
                OcrPage(
                    pageId = "p1",
                    pageIndex = 1,
                    status = OcrPageStatus.SUCCESS,
                    tables = listOf(table)
                )
            )
        )
    }

    @Test
    fun testMergeCellsPreservesContentWithoutSilentLoss() {
        val doc = createSample2x2TableDoc()
        val handle = SavedStateHandle()
        val vm = OcrReaderViewModel(app, handle, repository, testScope)
        vm.initialize(doc.id, doc)

        // Merge c00 and c01
        vm.mergeCells("tbl_struct", listOf("c00", "c01"))

        val tableAfterMerge = vm.document.value?.pages?.get(0)?.tables?.get(0)
        assertNotNull(tableAfterMerge)
        // Must now have 3 cells: merged cell (span 1x2) and 2 cells in row 1
        assertEquals(3, tableAfterMerge!!.cells.size)

        val merged = tableAfterMerge.cells.find { it.rowIndex == 0 && it.colIndex == 0 }
        assertNotNull(merged)
        assertEquals(1, merged!!.rowSpan)
        assertEquals(2, merged.colSpan)
        // Merged text must preserve both original strings!
        assertEquals("Họ và tên Nguyễn Văn A", merged.editedText)

        // Grid must remain valid without overlap
        assertTrue(tableAfterMerge.validateGrid().isValid)

        // Undo -> restores both original cells
        assertTrue(vm.undo())
        val tableAfterUndo = vm.document.value?.pages?.get(0)?.tables?.get(0)
        assertEquals(4, tableAfterUndo?.cells?.size)
        assertEquals("Họ và tên", tableAfterUndo?.cells?.find { it.cellId == "c00" }?.editedText)
        assertEquals("Nguyễn Văn A", tableAfterUndo?.cells?.find { it.cellId == "c01" }?.editedText)
    }

    @Test
    fun testAddAndDeleteTableRow() {
        val doc = createSample2x2TableDoc()
        val handle = SavedStateHandle()
        val vm = OcrReaderViewModel(app, handle, repository, testScope)
        vm.initialize(doc.id, doc)

        val initialTable = vm.document.value?.pages?.get(0)?.tables?.get(0)!!
        assertEquals(2, initialTable.rowCount)
        assertEquals(4, initialTable.cells.size)

        // 1. Add row at index 1
        vm.addTableRow("tbl_struct", rowIndex = 1)
        val tableRowAdded = vm.document.value?.pages?.get(0)?.tables?.get(0)!!
        assertEquals(3, tableRowAdded.rowCount)
        assertEquals(6, tableRowAdded.cells.size)
        assertTrue(tableRowAdded.validateGrid().isValid)

        // 2. Undo -> back to 2 rows
        assertTrue(vm.undo())
        val tableRowUndone = vm.document.value?.pages?.get(0)?.tables?.get(0)!!
        assertEquals(2, tableRowUndone.rowCount)
        assertEquals(4, tableRowUndone.cells.size)

        // 3. Delete row at index 0
        vm.deleteTableRow("tbl_struct", rowIndex = 0)
        val tableRowDeleted = vm.document.value?.pages?.get(0)?.tables?.get(0)!!
        assertEquals(1, tableRowDeleted.rowCount)
        assertEquals(2, tableRowDeleted.cells.size)
        // Remaining row must be shifted to rowIndex 0
        assertEquals(0, tableRowDeleted.cells[0].rowIndex)
        assertEquals(0, tableRowDeleted.cells[1].rowIndex)
    }

    @Test
    fun testAddAndDeleteTableColumn() {
        val doc = createSample2x2TableDoc()
        val handle = SavedStateHandle()
        val vm = OcrReaderViewModel(app, handle, repository, testScope)
        vm.initialize(doc.id, doc)

        // 1. Add column at index 1
        vm.addTableColumn("tbl_struct", colIndex = 1)
        val tableColAdded = vm.document.value?.pages?.get(0)?.tables?.get(0)!!
        assertEquals(3, tableColAdded.columnCount)
        assertEquals(6, tableColAdded.cells.size)
        assertTrue(tableColAdded.validateGrid().isValid)

        // 2. Undo -> back to 2 columns
        assertTrue(vm.undo())
        val tableColUndone = vm.document.value?.pages?.get(0)?.tables?.get(0)!!
        assertEquals(2, tableColUndone.columnCount)
        assertEquals(4, tableColUndone.cells.size)

        // 3. Delete column at index 0
        vm.deleteTableColumn("tbl_struct", colIndex = 0)
        val tableColDeleted = vm.document.value?.pages?.get(0)?.tables?.get(0)!!
        assertEquals(1, tableColDeleted.columnCount)
        assertEquals(2, tableColDeleted.cells.size)
        // Remaining column shifted to colIndex 0
        assertEquals(0, tableColDeleted.cells[0].colIndex)
        assertEquals(0, tableColDeleted.cells[1].colIndex)
    }

    @Test
    fun testSplitCellRestoresUnitCells() {
        val doc = createSample2x2TableDoc()
        val handle = SavedStateHandle()
        val vm = OcrReaderViewModel(app, handle, repository, testScope)
        vm.initialize(doc.id, doc)

        // First merge c00 and c01
        vm.mergeCells("tbl_struct", listOf("c00", "c01"))
        val mergedTable = vm.document.value?.pages?.get(0)?.tables?.get(0)!!
        val mergedCell = mergedTable.cells.find { it.rowSpan > 1 || it.colSpan > 1 }!!

        // Split the merged cell back
        vm.splitCell("tbl_struct", mergedCell.cellId)
        val splitTable = vm.document.value?.pages?.get(0)?.tables?.get(0)!!
        assertEquals(4, splitTable.cells.size)
        assertTrue("Restored grid must be non-overlapping", splitTable.validateGrid().isValid)
    }
}
