package com.tscanner.app.ui.ocr.editor

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tscanner.app.R
import com.tscanner.app.databinding.FragmentOcrTableEditorBinding
import com.tscanner.app.ocr.model.OcrCellType
import com.tscanner.app.ocr.model.OcrTable
import com.tscanner.app.ocr.model.OcrTableCell
import com.tscanner.app.ui.ocr.reader.OcrReaderViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Fragment for displaying and editing OCR table cells with multi-table support,
 * multi-cell selection and merge/split controls, and row recycling.
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S17, S18, G4, G6).
 */
class OcrTableEditorFragment : Fragment() {

    private var _binding: FragmentOcrTableEditorBinding? = null
    private val binding get() = _binding!!

    private val viewModel: OcrReaderViewModel by activityViewModels()
    private var adapter: TableRowAdapter? = null

    private var selectedTableIndex = 0
    private var lastObservedPageIndex = -1

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOcrTableEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupActions()
        observeState()
    }

    private fun setupRecyclerView() {
        adapter = TableRowAdapter(
            onCellClick = { cell, tableId ->
                showCellEditDialog(cell, tableId)
            },
            onSelectionChanged = { selectedCount ->
                if (_binding != null) {
                    if (selectedCount > 0) {
                        binding.btnTableClearSelection.visibility = View.VISIBLE
                        if (selectedCount >= 2) {
                            binding.btnTableMerge.visibility = View.VISIBLE
                            binding.btnTableMerge.text = getString(R.string.ocr_table_merge_selected_format, selectedCount)
                        } else {
                            binding.btnTableMerge.visibility = View.GONE
                        }
                    } else {
                        binding.btnTableClearSelection.visibility = View.GONE
                        binding.btnTableMerge.visibility = View.GONE
                    }
                }
            }
        )
        binding.rvTableRows.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
        binding.rvTableRows.adapter = adapter
    }

    private fun setupActions() {
        binding.btnTableUndo.setOnClickListener {
            viewModel.undo()
        }

        binding.btnTableRedo.setOnClickListener {
            viewModel.redo()
        }

        binding.btnTableAddRow.setOnClickListener {
            val doc = viewModel.document.value
            val pIdx = viewModel.currentPageIndex.value
            val table = doc?.pages?.getOrNull(pIdx - 1)?.tables?.getOrNull(selectedTableIndex)
            if (table != null) {
                viewModel.addTableRow(table.tableId, table.rowCount)
            }
        }

        binding.btnTableAddCol.setOnClickListener {
            val doc = viewModel.document.value
            val pIdx = viewModel.currentPageIndex.value
            val table = doc?.pages?.getOrNull(pIdx - 1)?.tables?.getOrNull(selectedTableIndex)
            if (table != null) {
                viewModel.addTableColumn(table.tableId, table.columnCount)
            }
        }

        binding.btnTableMerge.setOnClickListener {
            val selectedIds = adapter?.getSelectedCellIds().orEmpty()
            val tableId = adapter?.getCurrentTableId()
            if (selectedIds.size >= 2 && tableId != null) {
                viewModel.mergeCells(tableId, selectedIds.toList())
                adapter?.clearSelection()
            }
        }

        binding.btnTableClearSelection.setOnClickListener {
            adapter?.clearSelection()
        }

        binding.btnPrevTable.setOnClickListener {
            if (selectedTableIndex > 0) {
                selectedTableIndex--
                adapter?.clearSelection()
                renderCurrentTable()
            }
        }

        binding.btnNextTable.setOnClickListener {
            val doc = viewModel.document.value
            val pIdx = viewModel.currentPageIndex.value
            val tables = doc?.pages?.getOrNull(pIdx - 1)?.tables.orEmpty()
            if (selectedTableIndex < tables.size - 1) {
                selectedTableIndex++
                adapter?.clearSelection()
                renderCurrentTable()
            }
        }

        binding.btnCreateManualTable.setOnClickListener {
            viewModel.createManualTable(rowCount = 3, colCount = 3)
        }
    }

    private fun renderCurrentTable() {
        val doc = viewModel.document.value ?: return
        val pageIdx = viewModel.currentPageIndex.value
        val curPage = doc.pages.getOrNull(pageIdx - 1)
        val tables = curPage?.tables.orEmpty()

        if (tables.isNotEmpty()) {
            if (selectedTableIndex >= tables.size) {
                selectedTableIndex = tables.size - 1
            } else if (selectedTableIndex < 0) {
                selectedTableIndex = 0
            }
            val table = tables[selectedTableIndex]

            binding.layoutEmptyTable.visibility = View.GONE
            binding.scrollTableHorizontal.visibility = View.VISIBLE
            adapter?.setTable(table)

            if (tables.size > 1) {
                binding.layoutTableSelector.visibility = View.VISIBLE
                binding.dividerTableSelector.visibility = View.VISIBLE
                binding.tvTableSelectorTitle.text = getString(R.string.ocr_table_indicator, selectedTableIndex + 1, tables.size)
                binding.btnPrevTable.isEnabled = selectedTableIndex > 0
                binding.btnPrevTable.alpha = if (selectedTableIndex > 0) 1.0f else 0.4f
                binding.btnNextTable.isEnabled = selectedTableIndex < tables.size - 1
                binding.btnNextTable.alpha = if (selectedTableIndex < tables.size - 1) 1.0f else 0.4f
            } else {
                binding.layoutTableSelector.visibility = View.GONE
                binding.dividerTableSelector.visibility = View.GONE
            }
        } else {
            binding.scrollTableHorizontal.visibility = View.GONE
            binding.layoutEmptyTable.visibility = View.VISIBLE
            binding.layoutTableSelector.visibility = View.GONE
            binding.dividerTableSelector.visibility = View.GONE
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(viewModel.document, viewModel.currentPageIndex) { doc, pageIdx ->
                        doc to pageIdx
                    }.collect { (doc, pageIdx) ->
                        if (pageIdx != lastObservedPageIndex) {
                            lastObservedPageIndex = pageIdx
                            selectedTableIndex = 0
                            adapter?.clearSelection()
                        }
                        renderCurrentTable()
                    }
                }

                launch {
                    viewModel.canUndo.collect { canUndo ->
                        binding.btnTableUndo.isEnabled = canUndo
                        binding.btnTableUndo.alpha = if (canUndo) 1.0f else 0.4f
                    }
                }

                launch {
                    viewModel.canRedo.collect { canRedo ->
                        binding.btnTableRedo.isEnabled = canRedo
                        binding.btnTableRedo.alpha = if (canRedo) 1.0f else 0.4f
                    }
                }

                launch {
                    viewModel.isDirty.collect { isDirty ->
                        binding.tvTableStatus.text = if (isDirty) {
                            getString(R.string.ocr_editor_status_edited)
                        } else {
                            getString(R.string.ocr_editor_status_saved)
                        }
                    }
                }
            }
        }
    }

    private fun showCellEditDialog(cell: OcrTableCell, tableId: String) {
        val doc = viewModel.document.value
        val pIdx = viewModel.currentPageIndex.value
        val table = doc?.pages?.getOrNull(pIdx - 1)?.tables?.find { it.tableId == tableId }

        val context = requireContext()
        val input = EditText(context).apply {
            setText(cell.editedText)
            setSelection(cell.editedText.length)
        }

        var selectedType = cell.cellType

        val typeToggle = TextView(context).apply {
            text = getString(
                R.string.ocr_cell_type_format,
                if (selectedType == OcrCellType.NUMBER) getString(R.string.ocr_cell_type_number) else getString(R.string.ocr_cell_type_text)
            )
            setPadding(16, 16, 16, 16)
            setOnClickListener {
                selectedType = if (selectedType == OcrCellType.TEXT) OcrCellType.NUMBER else OcrCellType.TEXT
                text = getString(
                    R.string.ocr_cell_type_format,
                    if (selectedType == OcrCellType.NUMBER) getString(R.string.ocr_cell_type_number) else getString(R.string.ocr_cell_type_text)
                )
            }
        }

        var dialogRef: AlertDialog? = null

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
            addView(input)
            addView(typeToggle)

            if (cell.rowSpan > 1 || cell.colSpan > 1) {
                val splitBtn = TextView(context).apply {
                    text = getString(R.string.ocr_table_split_cell)
                    setTextColor(context.getColor(R.color.primary_teal))
                    setPadding(16, 16, 16, 16)
                    setOnClickListener {
                        val newText = input.text.toString()
                        if (newText != cell.editedText) {
                            viewModel.updateTableCell(tableId, cell.cellId, newText, selectedType)
                        }
                        viewModel.splitCell(tableId, cell.cellId)
                        dialogRef?.dismiss()
                    }
                }
                addView(splitBtn)
            }

            if (table != null) {
                // Find right neighbor with matching rowSpan
                val rightCell = table.cells.find {
                    it.rowIndex == cell.rowIndex &&
                    it.colIndex == cell.colIndex + cell.colSpan &&
                    it.rowSpan == cell.rowSpan
                }
                if (rightCell != null) {
                    val mergeRightBtn = TextView(context).apply {
                        text = getString(R.string.ocr_table_merge_right, rightCell.colIndex + 1)
                        setTextColor(context.getColor(R.color.primary_teal))
                        setPadding(16, 16, 16, 16)
                        setOnClickListener {
                            val newText = input.text.toString()
                            if (newText != cell.editedText) {
                                viewModel.updateTableCell(tableId, cell.cellId, newText, selectedType)
                            }
                            viewModel.mergeCells(tableId, listOf(cell.cellId, rightCell.cellId))
                            dialogRef?.dismiss()
                        }
                    }
                    addView(mergeRightBtn)
                }

                // Find bottom neighbor with matching colSpan
                val bottomCell = table.cells.find {
                    it.colIndex == cell.colIndex &&
                    it.rowIndex == cell.rowIndex + cell.rowSpan &&
                    it.colSpan == cell.colSpan
                }
                if (bottomCell != null) {
                    val mergeBelowBtn = TextView(context).apply {
                        text = getString(R.string.ocr_table_merge_below, bottomCell.rowIndex + 1)
                        setTextColor(context.getColor(R.color.primary_teal))
                        setPadding(16, 16, 16, 16)
                        setOnClickListener {
                            val newText = input.text.toString()
                            if (newText != cell.editedText) {
                                viewModel.updateTableCell(tableId, cell.cellId, newText, selectedType)
                            }
                            viewModel.mergeCells(tableId, listOf(cell.cellId, bottomCell.cellId))
                            dialogRef?.dismiss()
                        }
                    }
                    addView(mergeBelowBtn)
                }
            }

            val deleteRowBtn = TextView(context).apply {
                text = getString(R.string.ocr_table_delete_row, cell.rowIndex + 1)
                setTextColor(context.getColor(R.color.text_secondary))
                setPadding(16, 16, 16, 16)
                setOnClickListener {
                    AlertDialog.Builder(context)
                        .setTitle(R.string.ocr_table_delete_confirm_title)
                        .setMessage(getString(R.string.ocr_table_delete_row_msg, cell.rowIndex + 1))
                        .setPositiveButton(R.string.delete) { _, _ ->
                            viewModel.deleteTableRow(tableId, cell.rowIndex)
                            dialogRef?.dismiss()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            addView(deleteRowBtn)

            val deleteColBtn = TextView(context).apply {
                text = getString(R.string.ocr_table_delete_col, cell.colIndex + 1)
                setTextColor(context.getColor(R.color.text_secondary))
                setPadding(16, 16, 16, 16)
                setOnClickListener {
                    AlertDialog.Builder(context)
                        .setTitle(R.string.ocr_table_delete_confirm_title)
                        .setMessage(getString(R.string.ocr_table_delete_col_msg, cell.colIndex + 1))
                        .setPositiveButton(R.string.delete) { _, _ ->
                            viewModel.deleteTableColumn(tableId, cell.colIndex)
                            dialogRef?.dismiss()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            addView(deleteColBtn)
        }

        dialogRef = AlertDialog.Builder(context)
            .setTitle(R.string.ocr_cell_edit_title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newText = input.text.toString()
                viewModel.updateTableCell(tableId, cell.cellId, newText, selectedType)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    class TableRowAdapter(
        private val onCellClick: (OcrTableCell, String) -> Unit,
        private val onSelectionChanged: (Int) -> Unit
    ) : RecyclerView.Adapter<TableRowAdapter.RowViewHolder>() {

        private var currentTable: OcrTable? = null
        private var rowData = listOf<List<OcrTableCell>>()
        private val selectedCellIds = mutableSetOf<String>()

        fun getCurrentTableId(): String? = currentTable?.tableId

        fun getSelectedCellIds(): Set<String> = selectedCellIds.toSet()

        fun clearSelection() {
            if (selectedCellIds.isNotEmpty()) {
                selectedCellIds.clear()
                onSelectionChanged(0)
                notifyDataSetChanged()
            }
        }

        fun setTable(table: OcrTable) {
            this.currentTable = table
            // Retain only valid selected cell ids
            val validCellIds = table.cells.map { it.cellId }.toSet()
            if (selectedCellIds.retainAll(validCellIds)) {
                onSelectionChanged(selectedCellIds.size)
            }
            // Group cells by rowIndex
            val grouped = mutableListOf<List<OcrTableCell>>()
            for (r in 0 until table.rowCount) {
                val rowCells = table.cells.filter { it.rowIndex == r }.sortedBy { it.colIndex }
                grouped.add(rowCells)
            }
            this.rowData = grouped
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
            val rowLayout = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            return RowViewHolder(rowLayout)
        }

        override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
            val table = currentTable ?: return
            val rowCells = rowData.getOrNull(position) ?: return
            holder.bind(
                cells = rowCells,
                tableId = table.tableId,
                selectedCellIds = selectedCellIds,
                onCellClick = { cell ->
                    if (selectedCellIds.isNotEmpty()) {
                        toggleSelection(cell.cellId)
                    } else {
                        onCellClick(cell, table.tableId)
                    }
                },
                onCellLongClick = { cell ->
                    toggleSelection(cell.cellId)
                }
            )
        }

        private fun toggleSelection(cellId: String) {
            if (selectedCellIds.contains(cellId)) {
                selectedCellIds.remove(cellId)
            } else {
                selectedCellIds.add(cellId)
            }
            onSelectionChanged(selectedCellIds.size)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rowData.size

        class RowViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val rowLayout = itemView as LinearLayout

            fun bind(
                cells: List<OcrTableCell>,
                tableId: String,
                selectedCellIds: Set<String>,
                onCellClick: (OcrTableCell) -> Unit,
                onCellLongClick: (OcrTableCell) -> Unit
            ) {
                val context = rowLayout.context
                val density = context.resources.displayMetrics.density
                rowLayout.removeAllViews()

                for (cell in cells) {
                    val isSelected = selectedCellIds.contains(cell.cellId)
                    val cellView = TextView(context).apply {
                        val baseWidth = (100 * cell.colSpan * density).toInt()
                        val baseHeight = (40 * cell.rowSpan * density).toInt()
                        layoutParams = LinearLayout.LayoutParams(baseWidth, baseHeight).apply {
                            setMargins(2, 2, 2, 2)
                        }
                        setBackgroundResource(
                            if (isSelected) R.drawable.bg_table_cell_selected else R.drawable.bg_table_cell
                        )
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                        text = cell.editedText
                        setTextColor(context.getColor(R.color.text_primary))
                        textSize = 12f
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            onCellClick(cell)
                        }
                        setOnLongClickListener {
                            onCellLongClick(cell)
                            true
                        }
                    }
                    rowLayout.addView(cellView)
                }
            }
        }
    }
}
