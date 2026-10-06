package com.tscanner.app

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.tscanner.app.ocr.data.OcrDocumentRepository
import com.tscanner.app.ocr.data.RepositoryResult
import com.tscanner.app.ocr.model.*
import com.tscanner.app.ui.ocr.reader.DocumentSaveState
import com.tscanner.app.ui.ocr.reader.OcrReaderViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for table cell editing, leading zero preservation, manual table creation,
 * and autosave synchronization.
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S17).
 */
class OcrTableEditorTest {

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
        baseDir = tempFolder.newFolder("ocr_table_test")
        repository = OcrDocumentRepository(baseDir)
        app = TestApplication(baseDir)
    }

    private fun createDocumentWithTable(): OcrDocument {
        val cells = listOf(
            OcrTableCell("c00", 0, 0, 1, 1, "Mã hàng", "Mã hàng", OcrCellType.TEXT),
            OcrTableCell("c01", 0, 1, 1, 1, "Số lượng", "Số lượng", OcrCellType.TEXT),
            OcrTableCell("c10", 1, 0, 1, 1, "00123", "00123", OcrCellType.TEXT),
            OcrTableCell("c11", 1, 1, 1, 1, "50", "50", OcrCellType.NUMBER)
        )
        val table = OcrTable(
            tableId = "tbl_1",
            rowCount = 2,
            columnCount = 2,
            cells = cells
        )
        return OcrDocument(
            id = "doc_table_test",
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
    fun testLeadingZeroCellEditAndPreservation() = runBlocking {
        val initialDoc = createDocumentWithTable()
        repository.saveDocument(initialDoc)

        val handle = SavedStateHandle()
        val vm = OcrReaderViewModel(app, handle, repository, testScope)
        vm.initialize(initialDoc.id, initialDoc)

        val cell = vm.document.value?.pages?.get(0)?.tables?.get(0)?.cells?.find { it.cellId == "c10" }
        assertEquals("00123", cell?.editedText)
        assertEquals(OcrCellType.TEXT, cell?.cellType)

        // Edit cell to another leading zero code: "009876"
        vm.updateTableCell("tbl_1", "c10", "009876", OcrCellType.TEXT)

        val updatedCell = vm.document.value?.pages?.get(0)?.tables?.get(0)?.cells?.find { it.cellId == "c10" }
        assertEquals("009876", updatedCell?.editedText)
        assertEquals(OcrCellType.TEXT, updatedCell?.cellType)
        assertTrue(vm.isDirty.value)

        // Flush to disk
        vm.flushPendingSaves()
        assertEquals(DocumentSaveState.SAVED, vm.saveState.value)

        // Verify disk has exact leading zeros
        val diskDoc = (repository.loadDocument(initialDoc.id) as RepositoryResult.Success).value
        val diskCell = diskDoc.pages[0].tables[0].cells.find { it.cellId == "c10" }
        assertEquals("009876", diskCell?.editedText)
        assertEquals(OcrCellType.TEXT, diskCell?.cellType)
    }

    @Test
    fun testVietnameseUnicodeAndNumberFormattingInCells() {
        val initialDoc = createDocumentWithTable()
        val handle = SavedStateHandle()
        val vm = OcrReaderViewModel(app, handle, repository, testScope)
        vm.initialize(initialDoc.id, initialDoc)

        // Edit header with Vietnamese accents: "Đơn giá (VNĐ)"
        vm.updateTableCell("tbl_1", "c01", "Đơn giá (VNĐ)", OcrCellType.TEXT)

        // Edit numeric value: "1,500,000.50"
        vm.updateTableCell("tbl_1", "c11", "1,500,000.50", OcrCellType.NUMBER)

        val table = vm.document.value?.pages?.get(0)?.tables?.get(0)
        val headerCell = table?.cells?.find { it.cellId == "c01" }
        val numCell = table?.cells?.find { it.cellId == "c11" }

        assertEquals("Đơn giá (VNĐ)", headerCell?.editedText)
        assertEquals(OcrCellType.TEXT, headerCell?.cellType)

        assertEquals("1,500,000.50", numCell?.editedText)
        assertEquals(OcrCellType.NUMBER, numCell?.cellType)

        // Undo numeric edit
        assertTrue(vm.undo())
        val revertedNumCell = vm.document.value?.pages?.get(0)?.tables?.get(0)?.cells?.find { it.cellId == "c11" }
        assertEquals("50", revertedNumCell?.editedText)
    }

    @Test
    fun testCreateManualTableWhenPageHasNoTable() {
        val docWithoutTable = OcrDocument(
            id = "doc_empty_table",
            pages = listOf(
                OcrPage(pageId = "p1", pageIndex = 1, status = OcrPageStatus.SUCCESS, tables = emptyList())
            )
        )
        val handle = SavedStateHandle()
        val vm = OcrReaderViewModel(app, handle, repository, testScope)
        vm.initialize(docWithoutTable.id, docWithoutTable)

        assertTrue(vm.document.value?.pages?.get(0)?.tables?.isEmpty() == true)

        // Trigger manual table creation (3x3 grid)
        vm.createManualTable(rowCount = 3, colCount = 3)

        val tables = vm.document.value?.pages?.get(0)?.tables
        assertNotNull(tables)
        assertEquals(1, tables!!.size)

        val table = tables[0]
        assertEquals(3, table.rowCount)
        assertEquals(3, table.columnCount)
        assertEquals(9, table.cells.size)

        val validation = table.validateGrid()
        assertTrue("Created manual table must form a valid non-overlapping grid", validation.isValid)
        assertTrue(vm.isDirty.value)
    }
}
