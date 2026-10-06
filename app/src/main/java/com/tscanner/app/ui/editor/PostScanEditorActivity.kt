package com.tscanner.app.ui.editor

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tscanner.app.R
import com.tscanner.app.databinding.ActivityPostScanEditorBinding
import com.tscanner.app.ui.editor.adapter.PostScanThumbnailAdapter
import com.tscanner.app.ui.editor.model.DocumentFilterType
import com.tscanner.app.ui.editor.model.NormalizedCropRect
import com.tscanner.app.ui.editor.viewmodel.ActiveEditorTool
import com.tscanner.app.ui.editor.viewmodel.ExportErrorCode
import com.tscanner.app.ui.editor.viewmodel.PostScanEditorViewModel
import com.tscanner.app.ui.viewer.PdfViewerActivity
import com.tscanner.app.utils.EdgeToEdgeInsetsHelper
import kotlinx.coroutines.launch
import java.io.File
import java.util.ArrayList

class PostScanEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPostScanEditorBinding
    private val viewModel: PostScanEditorViewModel by viewModels()

    private lateinit var thumbnailAdapter: PostScanThumbnailAdapter

    private val viewerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Khi người dùng đã lưu PDF thành công trong Viewer, kết thúc màn hình Editor
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        binding = ActivityPostScanEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWindowInsets()
        setupThumbnails()
        setupTopToolbar()
        setupCompareButton()
        setupBottomTools()
        setupCustomizationPanels()
        observeViewModel()

        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: ""
        val pagePaths = intent.getStringArrayListExtra(EXTRA_PAGE_PATHS) ?: arrayListOf()
        val docTitle = intent.getStringExtra(EXTRA_DOCUMENT_TITLE)

        if (sessionId.isEmpty() || pagePaths.isEmpty()) {
            Toast.makeText(this, R.string.post_scan_no_data, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        viewModel.initialize(sessionId, pagePaths, docTitle)
    }

    private fun setupWindowInsets() {
        val initialToolbarPadding = EdgeToEdgeInsetsHelper.recordInitialPadding(binding.layoutEditorToolbar)
        val initialToolsPadding = EdgeToEdgeInsetsHelper.recordInitialPadding(binding.layoutEditorTools)
        val initialThumbnailsPadding = EdgeToEdgeInsetsHelper.recordInitialPadding(binding.rvEditorThumbnails)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysInsets = EdgeToEdgeInsetsHelper.getSystemBarAndCutoutInsets(insets)

            EdgeToEdgeInsetsHelper.applyTopBarInsets(
                binding.layoutEditorToolbar,
                initialToolbarPadding,
                sysInsets
            )

            EdgeToEdgeInsetsHelper.applyBottomBarInsets(
                binding.layoutEditorTools,
                initialToolsPadding,
                sysInsets,
                sysInsets.bottom
            )

            EdgeToEdgeInsetsHelper.applyContentHorizontalInsets(
                binding.rvEditorThumbnails,
                initialThumbnailsPadding,
                sysInsets
            )

            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupThumbnails() {
        thumbnailAdapter = PostScanThumbnailAdapter(emptyList(), 0) { selectedIndex ->
            viewModel.selectPage(selectedIndex)
        }
        binding.rvEditorThumbnails.adapter = thumbnailAdapter
    }

    private fun setupTopToolbar() {
        binding.btnBackEditor.setOnClickListener {
            handleBackExit()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackExit()
            }
        })

        binding.tvEditorDocTitle.setOnClickListener {
            showRenameDialog()
        }

        binding.btnPreviewDoc.setOnClickListener {
            exportAndOpenViewer()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCompareButton() {
        binding.btnCompareInitial.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    viewModel.setShowingInitialComparison(true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    viewModel.setShowingInitialComparison(false)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupBottomTools() {
        binding.btnToolFilter.setOnClickListener {
            viewModel.selectTool(ActiveEditorTool.FILTER)
        }

        binding.btnToolEnhance.setOnClickListener {
            viewModel.selectTool(ActiveEditorTool.ENHANCE)
        }

        binding.btnToolCropRotate.setOnClickListener {
            viewModel.selectTool(ActiveEditorTool.CROP_ROTATE)
        }

        binding.btnToolClean.setOnClickListener {
            viewModel.selectTool(ActiveEditorTool.CLEAN)
        }
    }

    private fun setupCustomizationPanels() {
        // 1. Bộ lọc
        binding.btnFilterOriginal.setOnClickListener {
            viewModel.updateFilter(DocumentFilterType.ORIGINAL)
            updateFilterButtonsUI(DocumentFilterType.ORIGINAL)
        }
        binding.btnFilterGrayscale.setOnClickListener {
            viewModel.updateFilter(DocumentFilterType.GRAYSCALE)
            updateFilterButtonsUI(DocumentFilterType.GRAYSCALE)
        }
        binding.btnFilterBlackWhite.setOnClickListener {
            viewModel.updateFilter(DocumentFilterType.BLACK_AND_WHITE)
            updateFilterButtonsUI(DocumentFilterType.BLACK_AND_WHITE)
        }

        // 2. Tăng nét
        binding.seekSharpness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvSharpnessVal.text = progress.toString()
                    viewModel.updateSharpness(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 3. Cắt và xoay
        binding.btnRotateLeft.setOnClickListener {
            viewModel.rotatePage(-90)
        }
        binding.btnRotateRight.setOnClickListener {
            viewModel.rotatePage(90)
        }
        binding.btnResetCrop.setOnClickListener {
            binding.cropOverlayView.resetToFull()
            viewModel.resetCrop()
        }

        // 4. Làm sạch (Giảm bóng & Làm sáng nền)
        binding.seekShadow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvShadowVal.text = progress.toString()
                    viewModel.updateShadow(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.seekLighten.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvLightenVal.text = progress.toString()
                    viewModel.updateLighten(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Footer Actions: Hoàn tác, Áp dụng
        binding.btnUndoPage.setOnClickListener {
            viewModel.undoCurrentPage()
            binding.cropOverlayView.resetToFull()
            Toast.makeText(this, R.string.post_scan_undo_success, Toast.LENGTH_SHORT).show()
        }

        binding.btnApplyTool.setOnClickListener {
            if (viewModel.uiState.value.activeTool == ActiveEditorTool.CROP_ROTATE) {
                val screenCrop = binding.cropOverlayView.getNormalizedCropRect()
                val currentState = viewModel.uiState.value.currentPageState
                if (currentState != null) {
                    val sourceCrop = com.tscanner.app.ui.editor.model.PageGeometry.mapScreenCropToSource(
                        screenCrop = NormalizedCropRect(
                            left = screenCrop.left,
                            top = screenCrop.top,
                            right = screenCrop.right,
                            bottom = screenCrop.bottom
                        ),
                        currentSourceCrop = currentState.cropRect,
                        rotationDegrees = currentState.rotationDegrees
                    )
                    viewModel.updateCrop(sourceCrop)
                }
            }

            if (binding.cbApplyAllPages.isChecked) {
                viewModel.applyToAllPages()
                Toast.makeText(this, R.string.post_scan_apply_all_success, Toast.LENGTH_SHORT).show()
            }

            viewModel.closeCustomizationPanel()
        }

        binding.ivEditorTarget.setOnViewportChangedListener { viewportRect, zoomScale ->
            viewModel.onViewportChanged(viewportRect, zoomScale)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        updateUiState(state)
                    }
                }
                launch {
                    viewModel.displayBitmap.collect { bmp ->
                        if (bmp != null) {
                            binding.ivEditorTarget.setImageBitmap(bmp)
                            if (viewModel.uiState.value.activeTool == ActiveEditorTool.CROP_ROTATE) {
                                updateCropOverlayBounds()
                            }
                        }
                    }
                }
                launch {
                    viewModel.thumbnailPaths.collect { paths ->
                        thumbnailAdapter.updatePages(paths, viewModel.uiState.value.currentPageIndex)
                    }
                }
            }
        }
    }

    private fun updateUiState(state: com.tscanner.app.ui.editor.viewmodel.EditorUiState) {
        binding.tvEditorDocTitle.text = state.documentTitle
        binding.tvPageIndicator.text = getString(
            R.string.page_indicator_format,
            state.currentPageIndex + 1,
            state.totalPages.coerceAtLeast(1)
        )
        thumbnailAdapter.setSelectedIndex(state.currentPageIndex)

        binding.pbEditorLoading.visibility = if (state.isRenderingPreview) View.VISIBLE else View.GONE

        // Cập nhật công cụ đang chọn
        updateToolSelectionUI(state.activeTool)

        // Cập nhật giá trị điều khiển của trang hiện tại
        state.currentPageState?.let { pageState ->
            updateFilterButtonsUI(pageState.filterType)

            binding.seekSharpness.progress = pageState.sharpnessIntensity
            binding.tvSharpnessVal.text = pageState.sharpnessIntensity.toString()

            binding.seekShadow.progress = pageState.shadowRemovalIntensity
            binding.tvShadowVal.text = pageState.shadowRemovalIntensity.toString()

            binding.seekLighten.progress = pageState.backgroundLightenIntensity
            binding.tvLightenVal.text = pageState.backgroundLightenIntensity.toString()
        }
    }

    private fun updateToolSelectionUI(activeTool: ActiveEditorTool?) {
        val teal = ContextCompat.getColor(this, R.color.primary_teal)
        val muted = ContextCompat.getColor(this, R.color.text_secondary)
        val cardBg = ContextCompat.getColor(this, R.color.card_dark)

        binding.layoutCustomizationPanel.visibility = if (activeTool != null) View.VISIBLE else View.GONE

        // Reset all tool highlights
        binding.btnToolFilter.setBackgroundColor(Color.TRANSPARENT)
        binding.ivToolFilter.imageTintList = ColorStateList.valueOf(muted)
        binding.tvToolFilter.setTextColor(muted)

        binding.btnToolEnhance.setBackgroundColor(Color.TRANSPARENT)
        binding.ivToolEnhance.imageTintList = ColorStateList.valueOf(muted)
        binding.tvToolEnhance.setTextColor(muted)

        binding.btnToolCropRotate.setBackgroundColor(Color.TRANSPARENT)
        binding.ivToolCropRotate.imageTintList = ColorStateList.valueOf(muted)
        binding.tvToolCropRotate.setTextColor(muted)

        binding.btnToolClean.setBackgroundColor(Color.TRANSPARENT)
        binding.ivToolClean.imageTintList = ColorStateList.valueOf(muted)
        binding.tvToolClean.setTextColor(muted)

        // Hide all control panels
        binding.panelFilterControls.visibility = View.GONE
        binding.panelEnhanceControls.visibility = View.GONE
        binding.panelCropRotateControls.visibility = View.GONE
        binding.panelCleanControls.visibility = View.GONE

        // Disable crop overlay unless crop tool is active
        val isCropActive = activeTool == ActiveEditorTool.CROP_ROTATE
        binding.cropOverlayView.visibility = if (isCropActive) View.VISIBLE else View.GONE
        binding.ivEditorTarget.isZoomingEnabled = !isCropActive

        if (isCropActive) {
            updateCropOverlayBounds()
        }

        when (activeTool) {
            ActiveEditorTool.FILTER -> {
                binding.btnToolFilter.setBackgroundColor(cardBg)
                binding.ivToolFilter.imageTintList = ColorStateList.valueOf(teal)
                binding.tvToolFilter.setTextColor(teal)
                binding.panelFilterControls.visibility = View.VISIBLE
            }
            ActiveEditorTool.ENHANCE -> {
                binding.btnToolEnhance.setBackgroundColor(cardBg)
                binding.ivToolEnhance.imageTintList = ColorStateList.valueOf(teal)
                binding.tvToolEnhance.setTextColor(teal)
                binding.panelEnhanceControls.visibility = View.VISIBLE
            }
            ActiveEditorTool.CROP_ROTATE -> {
                binding.btnToolCropRotate.setBackgroundColor(cardBg)
                binding.ivToolCropRotate.imageTintList = ColorStateList.valueOf(teal)
                binding.tvToolCropRotate.setTextColor(teal)
                binding.panelCropRotateControls.visibility = View.VISIBLE
            }
            ActiveEditorTool.CLEAN -> {
                binding.btnToolClean.setBackgroundColor(cardBg)
                binding.ivToolClean.imageTintList = ColorStateList.valueOf(teal)
                binding.tvToolClean.setTextColor(teal)
                binding.panelCleanControls.visibility = View.VISIBLE
            }
            null -> {}
        }
    }

    private fun updateCropOverlayBounds() {
        binding.layoutEditorWorkspace.doOnLayout {
            val bounds = binding.ivEditorTarget.getCurrentDisplayBounds()
            if (bounds.width() > 0 && bounds.height() > 0) {
                binding.cropOverlayView.setImageBounds(bounds)
            }
        }
    }

    private fun updateFilterButtonsUI(selectedFilter: DocumentFilterType) {
        val tealBg = R.drawable.btn_solid_teal
        val outlineBg = R.drawable.btn_outline_teal
        val darkText = ContextCompat.getColor(this, R.color.bg_dark)
        val whiteText = ContextCompat.getColor(this, R.color.text_primary)

        binding.btnFilterOriginal.setBackgroundResource(if (selectedFilter == DocumentFilterType.ORIGINAL) tealBg else outlineBg)
        binding.btnFilterOriginal.setTextColor(if (selectedFilter == DocumentFilterType.ORIGINAL) darkText else whiteText)

        binding.btnFilterGrayscale.setBackgroundResource(if (selectedFilter == DocumentFilterType.GRAYSCALE) tealBg else outlineBg)
        binding.btnFilterGrayscale.setTextColor(if (selectedFilter == DocumentFilterType.GRAYSCALE) darkText else whiteText)

        binding.btnFilterBlackWhite.setBackgroundResource(if (selectedFilter == DocumentFilterType.BLACK_AND_WHITE) tealBg else outlineBg)
        binding.btnFilterBlackWhite.setTextColor(if (selectedFilter == DocumentFilterType.BLACK_AND_WHITE) darkText else whiteText)
    }

    private fun showRenameDialog() {
        val currentTitle = viewModel.uiState.value.documentTitle
        val editText = EditText(this).apply {
            setText(currentTitle)
            setSelection(currentTitle.length)
            setTextColor(ContextCompat.getColor(this@PostScanEditorActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@PostScanEditorActivity, R.color.text_hint))
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.rename_document_title))
            .setView(editText)
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    viewModel.setDocumentTitle(newName)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun handleBackExit() {
        if (viewModel.isDirty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.discard_session_title))
                .setMessage(getString(R.string.discard_session_msg))
                .setPositiveButton(getString(R.string.discard_session_btn)) { _, _ ->
                    lifecycleScope.launch {
                        viewModel.discardSession()
                        finish()
                    }
                }
                .setNeutralButton(getString(R.string.save)) { _, _ ->
                    lifecycleScope.launch {
                        val saved = viewModel.flushPendingChanges()
                        if (saved) {
                            Toast.makeText(this@PostScanEditorActivity, R.string.save_file_success, Toast.LENGTH_SHORT).show()
                            finish()
                        } else {
                            Toast.makeText(this@PostScanEditorActivity, R.string.save_file_error, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton(getString(R.string.keep_editing_btn), null)
                .show()
        } else {
            lifecycleScope.launch {
                val saved = viewModel.flushPendingChanges()
                if (saved) {
                    finish()
                } else {
                    Toast.makeText(this@PostScanEditorActivity, R.string.save_file_error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun exportAndOpenViewer() {
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.post_scan_exporting_title)
            .setMessage(getString(R.string.rendering_full_res))
            .setCancelable(false)
            .create()

        progressDialog.show()

        lifecycleScope.launch {
            val result = viewModel.exportFullResolutionPages { current, total ->
                runOnUiThread {
                    progressDialog.setMessage(getString(R.string.post_scan_processing_page_progress, current, total))
                }
            }
            progressDialog.dismiss()

            when (result) {
                is com.tscanner.app.ui.editor.viewmodel.ExportResult.Success -> {
                    val intent = PdfViewerActivity.createIntentForNewScan(
                        context = this@PostScanEditorActivity,
                        sessionId = viewModel.uiState.value.sessionId,
                        pdfPath = null,
                        pagePaths = ArrayList(result.pagePaths),
                        title = viewModel.uiState.value.documentTitle
                    )
                    viewerLauncher.launch(intent)
                }
                is com.tscanner.app.ui.editor.viewmodel.ExportResult.Failure -> {
                    val errorMsg = when (result.errorCode) {
                        ExportErrorCode.NO_PAGES -> getString(R.string.post_scan_export_no_pages)
                        ExportErrorCode.PROCESS_PAGE_FAILED -> getString(R.string.post_scan_export_page_failed_format, result.pageIndex)
                        ExportErrorCode.PAGE_COUNT_MISMATCH -> getString(
                            R.string.post_scan_export_count_mismatch_format,
                            result.errorArgs.getOrNull(0) ?: "",
                            result.errorArgs.getOrNull(1) ?: ""
                        )
                        ExportErrorCode.UNKNOWN -> result.message.ifBlank { getString(R.string.post_scan_export_failed_title) }
                    }
                    MaterialAlertDialogBuilder(this@PostScanEditorActivity)
                        .setTitle(R.string.post_scan_export_failed_title)
                        .setMessage(errorMsg)
                        .setPositiveButton(R.string.retry) { _, _ ->
                            exportAndOpenViewer()
                        }
                        .setNegativeButton(R.string.close, null)
                        .show()
                }
                is com.tscanner.app.ui.editor.viewmodel.ExportResult.Cancelled -> {}
            }
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_PAGE_PATHS = "extra_page_paths"
        const val EXTRA_DOCUMENT_TITLE = "extra_document_title"

        fun start(
            context: Context,
            sessionId: String,
            pagePaths: List<String>,
            title: String? = null
        ) {
            val intent = Intent(context, PostScanEditorActivity::class.java).apply {
                putExtra(EXTRA_SESSION_ID, sessionId)
                putStringArrayListExtra(EXTRA_PAGE_PATHS, ArrayList(pagePaths))
                putExtra(EXTRA_DOCUMENT_TITLE, title)
            }
            context.startActivity(intent)
        }
    }
}
