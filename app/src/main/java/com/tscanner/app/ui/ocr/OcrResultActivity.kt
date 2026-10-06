package com.tscanner.app.ui.ocr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.tscanner.app.R
import com.tscanner.app.databinding.ActivityOcrResultBinding
import com.tscanner.app.ui.dialogs.ExportDocDialog
import com.tscanner.app.ui.dialogs.OcrLanguageSelectionDialog
import com.tscanner.app.ui.ocr.reader.OcrPageView
import com.tscanner.app.ui.ocr.reader.OcrReaderTab
import com.tscanner.app.ui.ocr.reader.OcrReaderViewModel
import com.tscanner.app.ui.ocr.editor.OcrTextEditorFragment
import com.tscanner.app.ocr.export.DocxWriter
import com.tscanner.app.ocr.export.XlsxWriter
import com.tscanner.app.ocr.model.*
import com.tscanner.app.utils.SafeFileWriter
import com.tscanner.app.utils.DocumentScannerHelper
import com.tscanner.app.utils.EdgeToEdgeInsetsHelper
import com.tscanner.app.utils.FileUtils
import com.tscanner.app.utils.MultiPageOcrAggregator
import com.tscanner.app.utils.MultiPageOcrResult
import com.tscanner.app.utils.OcrDetectionStatus
import com.tscanner.app.utils.OcrLanguageMode
import com.tscanner.app.utils.OcrRequest
import com.tscanner.app.utils.OcrResult
import com.tscanner.app.utils.OcrRoutingResolver
import com.tscanner.app.utils.PdfConverterHelper
import com.tscanner.app.utils.TextRecognitionHelper
import android.util.Log
import com.tscanner.app.utils.WatermarkHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OcrResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOcrResultBinding
    private var extractedText: String = ""
    private var currentEngineLabel: String = ""
    private var currentDetectionStatus: String? = null
    private var currentDetectedLanguages: ArrayList<String> = arrayListOf()
    private var currentDocLanguage: String? = null
    private var currentLanguageMode: String? = null
    private var currentImagePath: String? = null
    private var currentImagePaths: ArrayList<String> = arrayListOf()
    private var currentDocumentId: String? = null
    private var currentRevision: Long = 1L
    private var hasUserEdits: Boolean = false
    private var currentDocument: com.tscanner.app.ocr.model.OcrDocument? = null

    private val viewModel: OcrReaderViewModel by viewModels()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SAVED_TEXT, extractedText)
        outState.putString(KEY_SAVED_ENGINE_LABEL, currentEngineLabel)
        outState.putString(KEY_SAVED_DETECTION_STATUS, currentDetectionStatus)
        outState.putStringArrayList(KEY_SAVED_DETECTED_LANGS, currentDetectedLanguages)
        outState.putString(KEY_SAVED_DOC_LANG, currentDocLanguage)
        outState.putString(KEY_SAVED_LANG_MODE, currentLanguageMode)
        outState.putString(KEY_SAVED_IMAGE_PATH, currentImagePath)
        outState.putStringArrayList(KEY_SAVED_IMAGE_PATHS, currentImagePaths)
        outState.putString(KEY_SAVED_DOC_ID, currentDocumentId)
        outState.putLong(KEY_SAVED_DOC_REVISION, currentRevision)
        outState.putBoolean(KEY_SAVED_HAS_USER_EDITS, hasUserEdits)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        binding = ActivityOcrResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val initialToolbarPadding = EdgeToEdgeInsetsHelper.recordInitialPadding(binding.layoutOcrToolbar)
        val initialSearchBarPadding = EdgeToEdgeInsetsHelper.recordInitialPadding(binding.layoutSearchBar)
        val initialDetectedLanguagePadding = EdgeToEdgeInsetsHelper.recordInitialPadding(binding.layoutDetectedLanguage)
        val initialTabsPadding = EdgeToEdgeInsetsHelper.recordInitialPadding(binding.tabLayoutOcr)
        val initialContentContainerPadding = EdgeToEdgeInsetsHelper.recordInitialPadding(binding.layoutOcrContentContainer)
        val initialBottomPadding = EdgeToEdgeInsetsHelper.recordInitialPadding(binding.layoutOcrBottomActions)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysInsets = EdgeToEdgeInsetsHelper.getSystemBarAndCutoutInsets(insets)
            val effectiveBottom = EdgeToEdgeInsetsHelper.getEffectiveBottomInset(insets, includeIme = true)

            EdgeToEdgeInsetsHelper.applyTopBarInsets(
                binding.layoutOcrToolbar,
                initialToolbarPadding,
                sysInsets
            )

            EdgeToEdgeInsetsHelper.applyContentHorizontalInsets(
                binding.layoutSearchBar,
                initialSearchBarPadding,
                sysInsets
            )

            EdgeToEdgeInsetsHelper.applyContentHorizontalInsets(
                binding.layoutDetectedLanguage,
                initialDetectedLanguagePadding,
                sysInsets
            )

            EdgeToEdgeInsetsHelper.applyContentHorizontalInsets(
                binding.tabLayoutOcr,
                initialTabsPadding,
                sysInsets
            )

            EdgeToEdgeInsetsHelper.applyContentHorizontalInsets(
                binding.layoutOcrContentContainer,
                initialContentContainerPadding,
                sysInsets
            )

            EdgeToEdgeInsetsHelper.applyBottomBarInsets(
                binding.layoutOcrBottomActions,
                initialBottomPadding,
                sysInsets,
                effectiveBottom
            )

            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        if (savedInstanceState != null) {
            extractedText = savedInstanceState.getString(KEY_SAVED_TEXT, "")
            currentEngineLabel = savedInstanceState.getString(KEY_SAVED_ENGINE_LABEL, "")
            currentDetectionStatus = savedInstanceState.getString(KEY_SAVED_DETECTION_STATUS)
            currentDetectedLanguages = savedInstanceState.getStringArrayList(KEY_SAVED_DETECTED_LANGS) ?: arrayListOf()
            currentDocLanguage = savedInstanceState.getString(KEY_SAVED_DOC_LANG)
            currentLanguageMode = savedInstanceState.getString(KEY_SAVED_LANG_MODE)
            currentImagePath = savedInstanceState.getString(KEY_SAVED_IMAGE_PATH)
            currentImagePaths = savedInstanceState.getStringArrayList(KEY_SAVED_IMAGE_PATHS) ?: arrayListOf()
        } else {
            val filePath = intent.getStringExtra(EXTRA_TEXT_FILE)
            extractedText = if (filePath != null && File(filePath).exists()) {
                try {
                    File(filePath).readText()
                } catch (e: Exception) {
                    intent.getStringExtra(EXTRA_TEXT) ?: ""
                }
            } else {
                intent.getStringExtra(EXTRA_TEXT) ?: ""
            }

            val engineIds = intent.getStringArrayListExtra(EXTRA_ENGINE_IDS)
            val engineFallbacks = intent.getBooleanArrayExtra(EXTRA_ENGINE_FALLBACKS)
            val engineId = intent.getStringExtra(EXTRA_ENGINE_ID)
            val docLang = intent.getStringExtra(EXTRA_DOC_LANGUAGE)
            val fallbackUsed = intent.getBooleanExtra(EXTRA_FALLBACK_USED, false)
            val legacyEngine = intent.getStringExtra(EXTRA_ENGINE)?.takeIf { it.isNotBlank() }

            currentEngineLabel = if (!engineIds.isNullOrEmpty()) {
                engineIds.mapIndexed { index, id ->
                    val isFallback = engineFallbacks?.getOrNull(index) ?: fallbackUsed
                    TextRecognitionHelper.formatEngineMetadata(this, id, docLang ?: "", isFallback)
                }.joinToString(", ")
            } else if (!engineId.isNullOrBlank()) {
                TextRecognitionHelper.formatEngineMetadata(this, engineId, docLang ?: "", fallbackUsed)
            } else if (legacyEngine != null) {
                legacyEngine
            } else {
                TextRecognitionHelper.getPreferredEngineDisplayName(this)
            }

            currentDetectionStatus = intent.getStringExtra(EXTRA_DETECTION_STATUS)
            currentDetectedLanguages = intent.getStringArrayListExtra(EXTRA_DETECTED_LANGUAGES) ?: arrayListOf()
            currentDocLanguage = docLang
            currentLanguageMode = intent.getStringExtra(EXTRA_LANGUAGE_MODE)
            currentImagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
            currentImagePaths = intent.getStringArrayListExtra(EXTRA_IMAGE_PATHS) ?: arrayListOf()
            currentDocumentId = intent.getStringExtra(EXTRA_DOCUMENT_ID)
            currentRevision = intent.getLongExtra(EXTRA_DOCUMENT_REVISION, 1L)
        }

        if (savedInstanceState != null) {
            currentDocumentId = savedInstanceState.getString(KEY_SAVED_DOC_ID)
            currentRevision = savedInstanceState.getLong(KEY_SAVED_DOC_REVISION, 1L)
            hasUserEdits = savedInstanceState.getBoolean(KEY_SAVED_HAS_USER_EDITS, false)
        }

        if (!currentDocumentId.isNullOrBlank()) {
            lifecycleScope.launch {
                val repo = com.tscanner.app.ocr.data.OcrDocumentRepository.getInstance(this@OcrResultActivity)
                val loadRes = repo.loadDocument(currentDocumentId!!)
                if (loadRes is com.tscanner.app.ocr.data.RepositoryResult.Success) {
                    val doc = loadRes.value
                    currentDocument = doc
                    currentRevision = doc.revision
                    val resolved = doc.fullText
                    if (resolved.isNotBlank()) {
                        extractedText = resolved
                        binding.tvOcrContent.text = resolved
                    }
                }
            }
        }

        if (extractedText.isBlank()) {
            binding.tvOcrContent.text = getString(R.string.no_text_found)
        } else {
            binding.tvOcrContent.text = extractedText
        }

        setupReaderTabs()
        setupPageNavigation()
        observeViewModel()

        if (!currentDocumentId.isNullOrBlank()) {
            viewModel.initialize(currentDocumentId!!, currentDocument)
        } else if (!currentImagePath.isNullOrBlank() || currentImagePaths.isNotEmpty()) {
            // Construct in-memory document from paths if documentId not provided
            val paths = if (currentImagePaths.isNotEmpty()) currentImagePaths else listOf(currentImagePath!!)
            val fallbackDoc = com.tscanner.app.ocr.model.OcrDocument(
                title = "Scanned Document",
                sourceLanguage = currentDocLanguage,
                pages = paths.mapIndexed { idx, p ->
                    com.tscanner.app.ocr.model.OcrPage(
                        pageId = "page_${idx + 1}",
                        pageIndex = idx + 1,
                        status = com.tscanner.app.ocr.model.OcrPageStatus.SUCCESS,
                        imageInfo = com.tscanner.app.ocr.model.OcrImageInfo(localUri = p, widthPx = 1000, heightPx = 1414),
                        engineId = currentEngineLabel
                    )
                }
            )
            viewModel.initialize(fallbackDoc.id, fallbackDoc)
        }

        binding.tvOcrEngine.text = getString(R.string.ocr_engine_label_format, currentEngineLabel)
        updateDetectionBanner(currentLanguageMode, currentDetectionStatus, currentDetectedLanguages, currentDocLanguage)

        binding.btnReRecognizeLanguage.setOnClickListener {
            OcrLanguageSelectionDialog(this) { selectedTag ->
                handleReRecognize(selectedTag)
            }.show()
        }

        binding.btnBackOcr.setOnClickListener {
            finish()
        }

        binding.btnCopyOcr.setOnClickListener {
            (supportFragmentManager.findFragmentById(R.id.fragment_text_editor) as? OcrTextEditorFragment)?.flushPendingTextEdit()
            val currentFullText = viewModel.document.value?.fullText ?: extractedText
            val textToCopy = if (binding.ocrPageView.selectionController.hasSelection()) {
                binding.ocrPageView.selectionController.getSelectedText()
            } else {
                currentFullText
            }
            if (textToCopy.isBlank()) {
                Toast.makeText(this, getString(R.string.ocr_no_text_to_copy), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("T-Scanner OCR", textToCopy)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
        }

        binding.btnShareOcr.setOnClickListener {
            (supportFragmentManager.findFragmentById(R.id.fragment_text_editor) as? OcrTextEditorFragment)?.flushPendingTextEdit()
            val currentFullText = viewModel.document.value?.fullText ?: extractedText
            if (currentFullText.isBlank()) {
                Toast.makeText(this, getString(R.string.ocr_no_text_to_share), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, currentFullText)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
        }

        binding.btnExportWordFromOcr.setOnClickListener {
            val doc = viewModel.document.value
            val hasContent = (doc?.fullText?.isNotBlank() == true) || extractedText.isNotBlank()
            if (!hasContent) {
                Toast.makeText(this, getString(R.string.ocr_no_text_to_export), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showExportWordDialog()
        }

        binding.btnExportExcelFromOcr.setOnClickListener {
            val doc = viewModel.document.value
            val hasContent = (doc?.fullText?.isNotBlank() == true) || 
                    extractedText.isNotBlank() || 
                    (doc?.pages?.any { it.tables.isNotEmpty() } == true)
            if (!hasContent) {
                Toast.makeText(this, getString(R.string.ocr_no_text_to_export), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showExportExcelDialog()
        }
    }

    private fun setupReaderTabs() {
        binding.tabLayoutOcr.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> viewModel.selectTab(OcrReaderTab.SCAN)
                    1 -> viewModel.selectTab(OcrReaderTab.TEXT)
                    2 -> viewModel.selectTab(OcrReaderTab.TABLE)
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupPageNavigation() {
        binding.btnPrevPage.setOnClickListener {
            (supportFragmentManager.findFragmentById(R.id.fragment_text_editor) as? OcrTextEditorFragment)?.flushPendingTextEdit()
            viewModel.previousPage()
        }
        binding.btnNextPage.setOnClickListener {
            (supportFragmentManager.findFragmentById(R.id.fragment_text_editor) as? OcrTextEditorFragment)?.flushPendingTextEdit()
            viewModel.nextPage()
        }

        binding.ocrPageView.onTransformChanged = { scale, pX, pY ->
            viewModel.setZoomAndPan(scale, pX, pY)
        }

        binding.btnSearchOcr.setOnClickListener {
            val isVisible = binding.layoutSearchBar.visibility == View.VISIBLE
            if (isVisible) {
                binding.layoutSearchBar.visibility = View.GONE
                binding.etSearchOcr.text.clear()
                binding.ocrPageView.selectionController.clearSearch()
                binding.ocrPageView.invalidate()
            } else {
                binding.layoutSearchBar.visibility = View.VISIBLE
                binding.etSearchOcr.requestFocus()
            }
        }

        binding.btnCloseSearch.setOnClickListener {
            binding.layoutSearchBar.visibility = View.GONE
            binding.etSearchOcr.text.clear()
            binding.ocrPageView.selectionController.clearSearch()
            binding.ocrPageView.invalidate()
        }

        binding.etSearchOcr.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString() ?: ""
                binding.ocrPageView.selectionController.search(query)
                binding.ocrPageView.invalidate()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.currentTab.collect { tab ->
                        binding.layoutScanContainer.visibility = if (tab == OcrReaderTab.SCAN) View.VISIBLE else View.GONE
                        binding.layoutTextContainer.visibility = if (tab == OcrReaderTab.TEXT) View.VISIBLE else View.GONE
                        binding.layoutTableContainer.visibility = if (tab == OcrReaderTab.TABLE) View.VISIBLE else View.GONE

                        val tabIdx = when (tab) {
                            OcrReaderTab.SCAN -> 0
                            OcrReaderTab.TEXT -> 1
                            OcrReaderTab.TABLE -> 2
                        }
                        if (binding.tabLayoutOcr.selectedTabPosition != tabIdx) {
                            binding.tabLayoutOcr.getTabAt(tabIdx)?.select()
                        }
                    }
                }

                launch {
                    viewModel.document.collect { doc ->
                        if (doc != null) {
                            currentDocument = doc
                            currentRevision = doc.revision
                            val totalPages = doc.totalPages
                            binding.layoutPageNavigation.visibility = if (totalPages > 1) View.VISIBLE else View.GONE
                            val fullText = doc.fullText
                            extractedText = fullText
                            binding.tvOcrContent.text = fullText
                            val curPage = doc.pages.getOrNull(viewModel.currentPageIndex.value - 1)
                            binding.ocrPageView.setOcrPage(curPage)
                        }
                    }
                }

                launch {
                    viewModel.hasUserEdits.collect { edited ->
                        if (edited) {
                            hasUserEdits = true
                        }
                    }
                }

                launch {
                    viewModel.currentPageIndex.collect { pageIdx ->
                        val doc = viewModel.document.value
                        val totalPages = doc?.totalPages ?: 1
                        binding.tvPageIndicator.text = getString(R.string.ocr_page_indicator, pageIdx, totalPages)
                        binding.btnPrevPage.isEnabled = pageIdx > 1
                        binding.btnPrevPage.alpha = if (pageIdx > 1) 1.0f else 0.4f
                        binding.btnNextPage.isEnabled = pageIdx < totalPages
                        binding.btnNextPage.alpha = if (pageIdx < totalPages) 1.0f else 0.4f

                        val curPage = doc?.pages?.getOrNull(pageIdx - 1)
                        binding.ocrPageView.setOcrPage(curPage)
                    }
                }

                launch {
                    viewModel.currentPageBitmap.collect { bmp ->
                        binding.ocrPageView.setPageBitmap(bmp)
                    }
                }

                launch {
                    viewModel.isImageMissing.collect { missing ->
                        binding.ocrPageView.isImageMissing = missing
                    }
                }

                launch {
                    viewModel.zoomScale.collect { scale ->
                        binding.ocrPageView.setTransform(scale, viewModel.panX.value, viewModel.panY.value)
                    }
                }
            }
        }
    }

    private fun showExportWordDialog() {
        val defaultName = getString(R.string.default_doc_filename_prefix) + SimpleDateFormat("dd-MM-yyyy_HH-mm", Locale.ROOT).format(Date())
        ExportDocDialog(
            this,
            title = getString(R.string.export_word_title),
            description = getString(R.string.export_word_desc),
            defaultName = defaultName,
            extension = "docx",
            supportedExtensions = listOf("docx", "doc", "txt")
        ) { fileName, action ->
            performExportWord(fileName, action)
        }.show()
    }

    private fun performExportWord(fileName: String, action: ExportDocDialog.ExportAction) {
        lifecycleScope.launch {
            // 1. Flush any pending text edit from editor fragment
            (supportFragmentManager.findFragmentById(R.id.fragment_text_editor) as? OcrTextEditorFragment)?.flushPendingTextEdit()

            // 2. Prepare latest document snapshot with flushed pending saves
            val exportDoc = viewModel.prepareDocumentForExport()
            val exportDir = FileUtils.getExportsDir(this@OcrResultActivity)
            val file = File(exportDir, fileName)
            val shouldWatermark = WatermarkHelper.shouldApplyWatermark(this@OcrResultActivity)

            val mimeType = when {
                fileName.endsWith(".docx", ignoreCase = true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                fileName.endsWith(".doc", ignoreCase = true) -> "application/msword"
                fileName.endsWith(".txt", ignoreCase = true) -> "text/plain"
                else -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            }

            val success = when {
                fileName.endsWith(".docx", ignoreCase = true) -> {
                    val rawText = exportDoc?.fullText ?: extractedText
                    val finalDoc = exportDoc ?: OcrDocument(
                        id = "doc_${System.currentTimeMillis()}",
                        pages = listOf(
                            OcrPage(
                                pageId = "p1",
                                pageIndex = 1,
                                status = OcrPageStatus.SUCCESS,
                                editedContent = OcrEditedContent(
                                    text = rawText,
                                    paragraphs = rawText.split("\n")
                                        .filter { it.isNotBlank() }
                                        .mapIndexed { idx, pText ->
                                            OcrParagraph(
                                                paragraphId = "para_$idx",
                                                text = pText,
                                                runs = listOf(OcrTextRun(text = pText))
                                            )
                                        }
                                )
                            )
                        )
                    )
                    DocxWriter.writeDocx(finalDoc, file, shouldWatermark)
                }
                fileName.endsWith(".txt", ignoreCase = true) -> {
                    val textToExport = exportDoc?.fullText ?: extractedText
                    SafeFileWriter.writeSafely(file) { tempFile ->
                        tempFile.writeText(textToExport, Charsets.UTF_8)
                        true
                    } is SafeFileWriter.Result.Success
                }
                else -> {
                    // Legacy .doc (HTML)
                    val textToExport = exportDoc?.fullText ?: extractedText
                    PdfConverterHelper.exportTextToWord(
                        text = textToExport,
                        outputFile = file,
                        addWatermark = shouldWatermark
                    )
                }
            }

            if (!success || !file.exists() || file.length() == 0L) {
                Toast.makeText(this@OcrResultActivity, getString(R.string.export_word_failed), Toast.LENGTH_SHORT).show()
                return@launch
            }

            // Luôn lưu một bản vào thư mục Downloads của thiết bị
            val saveUri = FileUtils.saveFileToDownloads(this@OcrResultActivity, file, mimeType)

            when (action) {
                ExportDocDialog.ExportAction.SAVE_TO_DEVICE -> {
                    if (saveUri != null) {
                        Toast.makeText(this@OcrResultActivity, getString(R.string.saved_to_downloads_file, file.name), Toast.LENGTH_LONG).show()
                        showExportSuccessDialog(file, mimeType, "Word")
                    } else {
                        Toast.makeText(this@OcrResultActivity, getString(R.string.cannot_save_to_downloads), Toast.LENGTH_LONG).show()
                    }
                }
                ExportDocDialog.ExportAction.SHARE -> {
                    if (saveUri != null) {
                        Toast.makeText(this@OcrResultActivity, getString(R.string.saved_to_downloads_and_sharing), Toast.LENGTH_SHORT).show()
                    }
                    shareExportedFile(file, mimeType)
                }
            }
        }
    }

    private fun showExportExcelDialog() {
        val defaultName = getString(R.string.default_sheet_filename_prefix) + SimpleDateFormat("dd-MM-yyyy_HH-mm", Locale.ROOT).format(Date())
        ExportDocDialog(
            this,
            title = getString(R.string.export_excel_title),
            description = getString(R.string.export_excel_desc),
            defaultName = defaultName,
            extension = "xlsx",
            supportedExtensions = listOf("xlsx", "csv")
        ) { fileName, action ->
            performExportExcel(fileName, action)
        }.show()
    }

    private fun performExportExcel(fileName: String, action: ExportDocDialog.ExportAction) {
        lifecycleScope.launch {
            // 1. Flush any pending text edit from editor fragment
            (supportFragmentManager.findFragmentById(R.id.fragment_text_editor) as? OcrTextEditorFragment)?.flushPendingTextEdit()

            // 2. Prepare latest document snapshot with flushed pending saves
            val exportDoc = viewModel.prepareDocumentForExport()
            val exportDir = FileUtils.getExportsDir(this@OcrResultActivity)
            val file = File(exportDir, fileName)

            val mimeType = when {
                fileName.endsWith(".xlsx", ignoreCase = true) -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                fileName.endsWith(".csv", ignoreCase = true) -> "text/csv"
                else -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            }

            val success = when {
                fileName.endsWith(".xlsx", ignoreCase = true) -> {
                    val rawText = exportDoc?.fullText ?: extractedText
                    val finalDoc = exportDoc ?: OcrDocument(
                        id = "doc_${System.currentTimeMillis()}",
                        pages = listOf(
                            OcrPage(
                                pageId = "p1",
                                pageIndex = 1,
                                status = OcrPageStatus.SUCCESS,
                                editedContent = OcrEditedContent(
                                    text = rawText,
                                    paragraphs = rawText.split("\n")
                                        .filter { it.isNotBlank() }
                                        .mapIndexed { idx, pText ->
                                            OcrParagraph(
                                                paragraphId = "para_$idx",
                                                text = pText,
                                                runs = listOf(OcrTextRun(text = pText))
                                            )
                                        }
                                )
                            )
                        )
                    )
                    XlsxWriter.writeXlsx(finalDoc, file)
                }
                else -> {
                    // CSV fallback
                    val textToExport = exportDoc?.fullText ?: extractedText
                    PdfConverterHelper.exportTextToExcel(
                        text = textToExport,
                        outputFile = file,
                        addWatermark = WatermarkHelper.shouldApplyWatermark(this@OcrResultActivity)
                    )
                }
            }

            if (!success || !file.exists() || file.length() == 0L) {
                Toast.makeText(this@OcrResultActivity, getString(R.string.export_excel_failed), Toast.LENGTH_SHORT).show()
                return@launch
            }

            // Luôn lưu một bản vào thư mục Downloads của thiết bị
            val saveUri = FileUtils.saveFileToDownloads(this@OcrResultActivity, file, mimeType)

            when (action) {
                ExportDocDialog.ExportAction.SAVE_TO_DEVICE -> {
                    if (saveUri != null) {
                        Toast.makeText(this@OcrResultActivity, getString(R.string.saved_to_downloads_file, file.name), Toast.LENGTH_LONG).show()
                        showExportSuccessDialog(file, mimeType, "Excel")
                    } else {
                        Toast.makeText(this@OcrResultActivity, getString(R.string.cannot_save_to_downloads), Toast.LENGTH_LONG).show()
                    }
                }
                ExportDocDialog.ExportAction.SHARE -> {
                    if (saveUri != null) {
                        Toast.makeText(this@OcrResultActivity, getString(R.string.saved_to_downloads_and_sharing), Toast.LENGTH_SHORT).show()
                    }
                    shareExportedFile(file, mimeType)
                }
            }
        }
    }

    private fun showExportSuccessDialog(file: File, mimeType: String, docType: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.export_success_title, docType))
            .setMessage(getString(R.string.export_success_message, file.name))
            .setPositiveButton(R.string.open_file) { _, _ ->
                openExportedFile(file, mimeType)
            }
            .setNeutralButton(R.string.share) { _, _ ->
                shareExportedFile(file, mimeType)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun openExportedFile(file: File, mimeType: String) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.provider",
                file
            )
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(viewIntent, getString(R.string.open_file_with)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.no_app_to_open_file), Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareExportedFile(file: File, mimeType: String) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.provider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_file_title)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.cannot_share_file), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateDetectionBanner(modeStr: String?, statusStr: String?, detectedLangs: List<String>?, docLang: String?) {
        val label = if (modeStr == OcrLanguageMode.MANUAL.name) {
            val tag = docLang ?: "vi"
            val langName = OcrRoutingResolver.getLocalizedDocumentLanguageName(this, tag)
            getString(R.string.ocr_selected_format, langName)
        } else {
            when (statusStr) {
                OcrDetectionStatus.MIXED_BILINGUAL.name -> getString(R.string.ocr_detected_vi_en)
                OcrDetectionStatus.CONFIDENT.name -> {
                    val tag = detectedLangs?.firstOrNull() ?: docLang ?: "vi"
                    val langName = OcrRoutingResolver.getLocalizedDocumentLanguageName(this, tag)
                    getString(R.string.ocr_detected_format, langName)
                }
                else -> getString(R.string.ocr_detected_uncertain)
            }
        }
        binding.tvDetectedLanguage.text = label
    }

    private fun handleReRecognize(selectedTag: String) {
        (supportFragmentManager.findFragmentById(R.id.fragment_text_editor) as? OcrTextEditorFragment)?.flushPendingTextEdit()
        if (hasUserEdits || viewModel.hasUserEdits.value || viewModel.isDirty.value) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ocr_confirm_overwrite_title)
                .setMessage(R.string.ocr_confirm_overwrite_desc)
                .setPositiveButton(R.string.ocr_confirm_overwrite_replace) { _, _ ->
                    performReRecognize(selectedTag)
                }
                .setNegativeButton(R.string.ocr_confirm_overwrite_keep, null)
                .show()
        } else {
            performReRecognize(selectedTag)
        }
    }

    private fun performReRecognize(selectedTag: String) {
        val path = currentImagePath
        val paths = currentImagePaths

        if (path.isNullOrBlank() && paths.isEmpty()) {
            Toast.makeText(this, getString(R.string.ocr_source_unavailable), Toast.LENGTH_SHORT).show()
            return
        }

        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ocr_processing)
            .setMessage(R.string.ocr_preparing_message)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            try {
                // Flush editor input and autosave before freezing the revision OCR may replace.
                val recognitionBase = viewModel.captureRecognitionBase()
                if (recognitionBase == null) {
                    Toast.makeText(this@OcrResultActivity, getString(R.string.ocr_error_generic), Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val mode = when (selectedTag) {
                    "auto" -> OcrLanguageMode.AUTO
                    "vi+en" -> OcrLanguageMode.VI_EN
                    else -> OcrLanguageMode.MANUAL
                }
                val request = OcrRequest(
                    languageMode = mode,
                    languageTag = if (mode == OcrLanguageMode.MANUAL) selectedTag else "vi",
                    engineMode = TextRecognitionHelper.getPreferredEngine(this@OcrResultActivity)
                )

                if (paths.isNotEmpty()) {
                    val pageResults = mutableListOf<OcrResult>()
                    for ((idx, pagePath) in paths.withIndex()) {
                        if (!isActive) break
                        progressDialog.setMessage(
                            getString(
                                R.string.ocr_recognizing_page_progress,
                                idx + 1,
                                paths.size,
                                TextRecognitionHelper.getPreferredEngineDisplayName(this@OcrResultActivity)
                            )
                        )
                        val pageResult = TextRecognitionHelper.recognizeTextFromFileStructured(
                            this@OcrResultActivity,
                            pagePath,
                            request
                        )
                        pageResults.add(pageResult)
                        if (MultiPageOcrAggregator.isBlockingError(pageResult)) {
                            break
                        }
                    }

                    if (!isActive) return@launch

                    val aggResult = TextRecognitionHelper.aggregateMultiPageResults(this@OcrResultActivity, pageResults)
                    when (aggResult) {
                        is MultiPageOcrResult.PageError -> {
                            val errorMsg = TextRecognitionHelper.formatPageErrorMessage(this@OcrResultActivity, aggResult)
                            Toast.makeText(this@OcrResultActivity, errorMsg, Toast.LENGTH_LONG).show()
                        }
                        is MultiPageOcrResult.AllNoText -> {
                            Toast.makeText(this@OcrResultActivity, getString(R.string.no_text_found), Toast.LENGTH_SHORT).show()
                        }
                        is MultiPageOcrResult.Success -> {
                            val recognizedText = aggResult.fullText

                            val ids = if (aggResult.engineUsages.isNotEmpty()) {
                                aggResult.engineUsages.map { it.engineId }
                            } else {
                                aggResult.engineIds.toList()
                            }
                            val fallbacks = if (aggResult.engineUsages.isNotEmpty()) {
                                aggResult.engineUsages.map { it.fallbackUsed }.toBooleanArray()
                            } else {
                                BooleanArray(ids.size) { false }
                            }
                            val engineDisplay = ids.mapIndexed { index, id ->
                                val isFallback = fallbacks.getOrNull(index) ?: false
                                TextRecognitionHelper.formatEngineMetadata(this@OcrResultActivity, id, aggResult.documentLanguage ?: "", isFallback)
                            }.joinToString(", ")

                            currentEngineLabel = engineDisplay
                            binding.tvOcrEngine.text = getString(R.string.ocr_engine_label_format, engineDisplay)

                            val resolvedStatus = if (aggResult.detectedLanguages.contains("vi") && aggResult.detectedLanguages.contains("en")) {
                                OcrDetectionStatus.MIXED_BILINGUAL.name
                            } else if (aggResult.detectedLanguages.size == 1) {
                                OcrDetectionStatus.CONFIDENT.name
                            } else if (aggResult.pageDetections.any { it.status == OcrDetectionStatus.MIXED_BILINGUAL }) {
                                OcrDetectionStatus.MIXED_BILINGUAL.name
                            } else if (aggResult.pageDetections.any { it.status == OcrDetectionStatus.CONFIDENT }) {
                                OcrDetectionStatus.CONFIDENT.name
                            } else {
                                OcrDetectionStatus.UNCERTAIN.name
                            }

                            // Commit revision update to repository preserving stable document ID
                            val repo = com.tscanner.app.ocr.data.OcrDocumentRepository.getInstance(this@OcrResultActivity)
                            val stableDocId = recognitionBase.document.id
                            val baseDoc = (aggResult.document?.copy(id = stableDocId, sourceLanguage = aggResult.documentLanguage)
                                ?: recognitionBase.document.copy(sourceLanguage = aggResult.documentLanguage))
                            val docToSave = if (paths.isNotEmpty()) {
                                baseDoc.copy(
                                    pages = baseDoc.pages.mapIndexed { idx, page ->
                                        val rawPath = paths.getOrNull(idx) ?: page.imageInfo?.localUri?.takeIf { it.isNotBlank() }
                                        if (!rawPath.isNullOrBlank()) {
                                            val file = File(rawPath)
                                            val persistentUri = if (file.exists()) {
                                                try {
                                                    repo.importPageImage(stableDocId, page.pageId, file).absolutePath
                                                } catch (_: Throwable) {
                                                    rawPath
                                                }
                                            } else {
                                                rawPath
                                            }
                                            page.copy(imageInfo = (page.imageInfo ?: com.tscanner.app.ocr.model.OcrImageInfo(widthPx = 1000, heightPx = 1414)).copy(localUri = persistentUri))
                                        } else page
                                    }
                                )
                            } else baseDoc

                            when (val result = viewModel.commitRecognitionResult(recognitionBase, docToSave)) {
                                is OcrReaderViewModel.RecognitionCommitResult.Success -> publishRecognitionSuccess(
                                    result.document, recognizedText, engineDisplay, resolvedStatus,
                                    aggResult.detectedLanguages.toList(), aggResult.documentLanguage, mode.name
                                )
                                is OcrReaderViewModel.RecognitionCommitResult.Conflict -> {
                                    Log.w(TAG, "Re-recognize commit conflict: ${result.message}")
                                    Toast.makeText(this@OcrResultActivity, getString(R.string.ocr_error_generic), Toast.LENGTH_SHORT).show()
                                }
                                is OcrReaderViewModel.RecognitionCommitResult.Stale -> {
                                    Log.w(TAG, "Discarding stale re-recognition result for ${recognitionBase.document.id}")
                                    Toast.makeText(this@OcrResultActivity, getString(R.string.ocr_error_generic), Toast.LENGTH_SHORT).show()
                                }
                                is OcrReaderViewModel.RecognitionCommitResult.Failure -> {
                                    Log.w(TAG, "Re-recognize commit failed: ${result.message}")
                                    Toast.makeText(this@OcrResultActivity, getString(R.string.ocr_error_generic), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                } else if (!path.isNullOrBlank()) {
                    val newResult = TextRecognitionHelper.recognizeTextFromFileStructured(
                        this@OcrResultActivity,
                        path,
                        request
                    )
                    if (newResult is OcrResult.Success && newResult.text.isNotBlank()) {
                        val recognizedText = newResult.text

                        val engineName = TextRecognitionHelper.formatEngineMetadata(
                            this@OcrResultActivity,
                            newResult.engineId,
                            newResult.documentLanguage,
                            newResult.fallbackUsed
                        )
                        currentEngineLabel = engineName
                        binding.tvOcrEngine.text = getString(R.string.ocr_engine_label_format, engineName)

                        // Commit revision update to repository
                        val repo = com.tscanner.app.ocr.data.OcrDocumentRepository.getInstance(this@OcrResultActivity)
                        val stableDocId = recognitionBase.document.id
                        val rawPageDoc = newResult.pageDocument ?: com.tscanner.app.ocr.model.OcrPage(
                            pageId = "page_1_${java.util.UUID.randomUUID().toString().take(8)}",
                            pageIndex = 1,
                            status = com.tscanner.app.ocr.model.OcrPageStatus.SUCCESS,
                            engineId = newResult.engineId,
                            sourceLanguage = newResult.documentLanguage,
                            sourceBlocks = listOf(
                                com.tscanner.app.ocr.model.OcrBlock(
                                    blockId = "blk_1_0",
                                    lines = newResult.text.lines().mapIndexed { idx, lineText ->
                                        com.tscanner.app.ocr.model.OcrLine(lineId = "line_1_$idx", text = lineText)
                                    }
                                )
                            )
                        )
                        val persistentUri = if (File(path).exists()) {
                            try {
                                repo.importPageImage(stableDocId, rawPageDoc.pageId, File(path)).absolutePath
                            } catch (_: Throwable) {
                                path
                            }
                        } else {
                            path
                        }
                        val pageDoc = rawPageDoc.copy(
                            imageInfo = (rawPageDoc.imageInfo ?: com.tscanner.app.ocr.model.OcrImageInfo(widthPx = 1000, heightPx = 1414)).copy(localUri = persistentUri)
                        )
                        val docToSave = com.tscanner.app.ocr.model.OcrDocument(
                            id = stableDocId,
                            sourceLanguage = newResult.documentLanguage,
                            pages = listOf(pageDoc)
                        )

                        when (val result = viewModel.commitRecognitionResult(recognitionBase, docToSave)) {
                            is OcrReaderViewModel.RecognitionCommitResult.Success -> publishRecognitionSuccess(
                                result.document, recognizedText, engineName,
                                newResult.detectionResult.status.name,
                                newResult.detectionResult.detectedLanguages.toList(),
                                newResult.documentLanguage, mode.name
                            )
                            is OcrReaderViewModel.RecognitionCommitResult.Conflict -> {
                                Log.w(TAG, "Single-page re-recognize commit conflict: ${result.message}")
                                Toast.makeText(this@OcrResultActivity, getString(R.string.ocr_error_generic), Toast.LENGTH_SHORT).show()
                            }
                            is OcrReaderViewModel.RecognitionCommitResult.Stale -> {
                                Log.w(TAG, "Discarding stale single-page result for ${recognitionBase.document.id}")
                                Toast.makeText(this@OcrResultActivity, getString(R.string.ocr_error_generic), Toast.LENGTH_SHORT).show()
                            }
                            is OcrReaderViewModel.RecognitionCommitResult.Failure -> {
                                Log.w(TAG, "Single-page re-recognize commit failed: ${result.message}")
                                Toast.makeText(this@OcrResultActivity, getString(R.string.ocr_error_generic), Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        TextRecognitionHelper.showOcrErrorToast(this@OcrResultActivity, newResult)
                    }
                }
            } catch (c: CancellationException) {
                // Ignore cancellation
            } catch (t: Throwable) {
                Log.e(TAG, "Re-recognize error: ${t.message}", t)
                Toast.makeText(this@OcrResultActivity, getString(R.string.ocr_error_generic), Toast.LENGTH_SHORT).show()
            } finally {
                if (progressDialog.isShowing && !isFinishing && !isDestroyed) {
                    progressDialog.dismiss()
                }
            }
        }
    }

    private fun publishRecognitionSuccess(
        committed: com.tscanner.app.ocr.model.OcrDocument,
        recognizedText: String,
        engineLabel: String,
        detectionStatus: String,
        detectedLanguages: List<String>,
        documentLanguage: String?,
        languageMode: String
    ) {
        currentDocument = committed
        currentRevision = committed.revision
        currentDocumentId = committed.id
        extractedText = recognizedText
        currentEngineLabel = engineLabel
        currentDetectionStatus = detectionStatus
        currentDetectedLanguages = ArrayList(detectedLanguages)
        currentDocLanguage = documentLanguage
        currentLanguageMode = languageMode
        hasUserEdits = false

        binding.tvOcrContent.text = recognizedText
        binding.tvOcrEngine.text = getString(R.string.ocr_engine_label_format, engineLabel)
        updateDetectionBanner(currentLanguageMode, currentDetectionStatus, currentDetectedLanguages, currentDocLanguage)

        intent.getStringExtra(EXTRA_TEXT_FILE)?.let { filePath ->
            try { File(filePath).writeText(recognizedText) } catch (_: Exception) {}
        }
        Toast.makeText(this, getString(R.string.convert_success), Toast.LENGTH_SHORT).show()
    }

    override fun onStop() {
        super.onStop()
        lifecycleScope.launch {
            viewModel.flushPendingSaves()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            val filePath = intent.getStringExtra(EXTRA_TEXT_FILE)
            if (filePath != null) {
                try {
                    File(filePath).delete()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    companion object {
        private const val TAG = "OcrResultActivity"
        const val EXTRA_TEXT = "extra_ocr_text"
        const val EXTRA_TEXT_FILE = "extra_ocr_text_file"
        const val EXTRA_ENGINE = "extra_ocr_engine"
        const val EXTRA_ENGINE_ID = "extra_ocr_engine_id"
        const val EXTRA_ENGINE_IDS = "extra_ocr_engine_ids"
        const val EXTRA_ENGINE_FALLBACKS = "extra_ocr_engine_fallbacks"
        const val EXTRA_DOC_LANGUAGE = "extra_ocr_doc_language"
        const val EXTRA_FALLBACK_USED = "extra_ocr_fallback_used"
        const val EXTRA_DETECTION_STATUS = "extra_ocr_detection_status"
        const val EXTRA_DETECTED_LANGUAGES = "extra_ocr_detected_languages"
        const val EXTRA_LANGUAGE_MODE = "extra_ocr_language_mode"
        const val EXTRA_IMAGE_PATH = "extra_ocr_image_path"
        const val EXTRA_IMAGE_PATHS = "extra_ocr_image_paths"
        const val EXTRA_DOCUMENT_ID = "extra_document_id"
        const val EXTRA_DOCUMENT_REVISION = "extra_document_revision"

        const val KEY_SAVED_TEXT = "saved_ocr_text"
        const val KEY_SAVED_ENGINE_LABEL = "saved_ocr_engine_label"
        const val KEY_SAVED_DETECTION_STATUS = "saved_ocr_detection_status"
        const val KEY_SAVED_DETECTED_LANGS = "saved_ocr_detected_langs"
        const val KEY_SAVED_DOC_LANG = "saved_ocr_doc_lang"
        const val KEY_SAVED_LANG_MODE = "saved_ocr_lang_mode"
        const val KEY_SAVED_IMAGE_PATH = "saved_ocr_image_path"
        const val KEY_SAVED_IMAGE_PATHS = "saved_ocr_image_paths"
        const val KEY_SAVED_DOC_ID = "saved_ocr_doc_id"
        const val KEY_SAVED_DOC_REVISION = "saved_ocr_doc_revision"
        const val KEY_SAVED_HAS_USER_EDITS = "saved_ocr_has_user_edits"

        fun start(
            context: Context,
            text: String,
            engine: String? = null,
            engineId: String? = null,
            engineIds: List<String>? = null,
            engineFallbacks: BooleanArray? = null,
            documentLanguage: String? = null,
            fallbackUsed: Boolean = false,
            detectionStatus: String? = null,
            detectedLanguages: List<String>? = null,
            imagePath: String? = null,
            imagePaths: List<String>? = null,
            languageMode: String? = null,
            documentId: String? = null,
            documentRevision: Long = 1L
        ) {
            val resolvedEngine = engine?.takeIf { it.isNotBlank() }
                ?: if (engineId.isNullOrBlank() && engineIds.isNullOrEmpty()) {
                    TextRecognitionHelper.getPreferredEngineDisplayName(context)
                } else null

            val intent = Intent(context, OcrResultActivity::class.java).apply {
                if (text.length > 10_000) {
                    try {
                        val tempFile = File(context.cacheDir, "ocr_res_${System.currentTimeMillis()}.txt")
                        tempFile.writeText(text)
                        putExtra(EXTRA_TEXT_FILE, tempFile.absolutePath)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        putExtra(EXTRA_TEXT, text)
                    }
                } else {
                    putExtra(EXTRA_TEXT, text)
                }
                if (!resolvedEngine.isNullOrBlank()) putExtra(EXTRA_ENGINE, resolvedEngine)
                if (!engineId.isNullOrBlank()) putExtra(EXTRA_ENGINE_ID, engineId)
                if (!engineIds.isNullOrEmpty()) putStringArrayListExtra(EXTRA_ENGINE_IDS, ArrayList(engineIds))
                if (engineFallbacks != null) putExtra(EXTRA_ENGINE_FALLBACKS, engineFallbacks)
                if (!documentLanguage.isNullOrBlank()) putExtra(EXTRA_DOC_LANGUAGE, documentLanguage)
                putExtra(EXTRA_FALLBACK_USED, fallbackUsed)
                if (!detectionStatus.isNullOrBlank()) putExtra(EXTRA_DETECTION_STATUS, detectionStatus)
                if (!detectedLanguages.isNullOrEmpty()) putStringArrayListExtra(EXTRA_DETECTED_LANGUAGES, ArrayList(detectedLanguages))
                if (!languageMode.isNullOrBlank()) putExtra(EXTRA_LANGUAGE_MODE, languageMode)
                if (!imagePath.isNullOrBlank()) putExtra(EXTRA_IMAGE_PATH, imagePath)
                if (!imagePaths.isNullOrEmpty()) putStringArrayListExtra(EXTRA_IMAGE_PATHS, ArrayList(imagePaths))
                if (!documentId.isNullOrBlank()) putExtra(EXTRA_DOCUMENT_ID, documentId)
                putExtra(EXTRA_DOCUMENT_REVISION, documentRevision)
            }
            context.startActivity(intent)
        }

        fun start(context: Context, result: OcrResult.Success, imagePath: String? = null, languageMode: String? = null) {
            val resolvedMode = languageMode ?: if (result.detectionResult.candidateLanguages.size == 1 && result.detectionResult.detectedLanguages.isEmpty()) {
                OcrLanguageMode.MANUAL.name
            } else {
                null
            }

            val repo = com.tscanner.app.ocr.data.OcrDocumentRepository.getInstance(context)
            val effectiveImagePath = imagePath ?: result.pageDocument?.imageInfo?.localUri?.takeIf { it.isNotBlank() }

            val rawPageDoc = result.pageDocument ?: com.tscanner.app.ocr.model.OcrPage(
                pageId = "page_1_${java.util.UUID.randomUUID().toString().take(8)}",
                pageIndex = 1,
                status = if (result.text.isBlank()) com.tscanner.app.ocr.model.OcrPageStatus.NO_TEXT else com.tscanner.app.ocr.model.OcrPageStatus.SUCCESS,
                engineId = result.engineId,
                sourceLanguage = result.documentLanguage,
                sourceBlocks = listOf(
                    com.tscanner.app.ocr.model.OcrBlock(
                        blockId = "blk_1_0",
                        lines = result.text.lines().mapIndexed { idx, lineText ->
                            com.tscanner.app.ocr.model.OcrLine(lineId = "line_1_$idx", text = lineText)
                        }
                    )
                )
            )

            val baseDocId = "doc_" + java.util.UUID.randomUUID().toString()
            val persistentUri = if (!effectiveImagePath.isNullOrBlank() && File(effectiveImagePath).exists()) {
                try {
                    kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                        repo.importPageImage(baseDocId, rawPageDoc.pageId, File(effectiveImagePath)).absolutePath
                    }
                } catch (_: Throwable) {
                    effectiveImagePath
                }
            } else {
                effectiveImagePath ?: ""
            }

            val finalImageInfo = (rawPageDoc.imageInfo ?: com.tscanner.app.ocr.model.OcrImageInfo(widthPx = 1000, heightPx = 1414)).copy(
                localUri = persistentUri
            )
            val finalPageDoc = com.tscanner.app.utils.MultiPageOcrAggregator.enrichPageWithLayoutAndTables(
                rawPageDoc.copy(imageInfo = finalImageInfo)
            )

            val baseDoc = com.tscanner.app.ocr.model.OcrDocument(
                id = baseDocId,
                title = "Scanned Document",
                sourceLanguage = result.documentLanguage,
                pages = listOf(finalPageDoc)
            )

            val savedDoc = try {
                kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    val saveRes = repo.saveDocument(baseDoc)
                    (saveRes as? com.tscanner.app.ocr.data.RepositoryResult.Success)?.value
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to persist initial OCR document: ${t.message}")
                null
            }

            start(
                context = context,
                text = result.text,
                engineId = result.engineId,
                documentLanguage = result.documentLanguage,
                fallbackUsed = result.fallbackUsed,
                detectionStatus = result.detectionResult.status.name,
                detectedLanguages = result.detectionResult.detectedLanguages,
                imagePath = imagePath,
                languageMode = resolvedMode,
                documentId = savedDoc?.id,
                documentRevision = savedDoc?.revision ?: 1L
            )
        }

        fun start(context: Context, result: MultiPageOcrResult.Success, fallbackEngineLabel: String? = null, imagePaths: List<String>? = null, languageMode: String? = null) {
            val ids = if (result.engineUsages.isNotEmpty()) {
                result.engineUsages.map { it.engineId }
            } else {
                result.engineIds.toList()
            }
            val fallbacks = if (result.engineUsages.isNotEmpty()) {
                result.engineUsages.map { it.fallbackUsed }.toBooleanArray()
            } else {
                BooleanArray(ids.size) { false }
            }

            val resolvedStatus = if (result.detectedLanguages.contains("vi") && result.detectedLanguages.contains("en")) {
                OcrDetectionStatus.MIXED_BILINGUAL.name
            } else if (result.detectedLanguages.size == 1) {
                OcrDetectionStatus.CONFIDENT.name
            } else if (result.pageDetections.any { it.status == OcrDetectionStatus.MIXED_BILINGUAL }) {
                OcrDetectionStatus.MIXED_BILINGUAL.name
            } else if (result.pageDetections.any { it.status == OcrDetectionStatus.CONFIDENT }) {
                OcrDetectionStatus.CONFIDENT.name
            } else {
                OcrDetectionStatus.UNCERTAIN.name
            }

            val baseDoc = result.document ?: com.tscanner.app.ocr.model.OcrDocument(
                title = "Scanned Document",
                sourceLanguage = result.documentLanguage,
                pages = imagePaths?.mapIndexed { idx, path ->
                    com.tscanner.app.ocr.model.OcrPage(
                        pageId = "page_${idx + 1}_${java.util.UUID.randomUUID().toString().take(8)}",
                        pageIndex = idx + 1,
                        status = com.tscanner.app.ocr.model.OcrPageStatus.SUCCESS,
                        imageInfo = com.tscanner.app.ocr.model.OcrImageInfo(localUri = path, widthPx = 1000, heightPx = 1414),
                        engineId = result.engineIds.firstOrNull() ?: "tesseract",
                        sourceBlocks = listOf(
                            com.tscanner.app.ocr.model.OcrBlock("blk_${idx + 1}_0", lines = listOf(com.tscanner.app.ocr.model.OcrLine("line_${idx + 1}_0", text = result.fullText)))
                        )
                    )
                } ?: emptyList()
            )

            val repo = com.tscanner.app.ocr.data.OcrDocumentRepository.getInstance(context)
            val docWithImages = baseDoc.copy(
                pages = baseDoc.pages.mapIndexed { idx, page ->
                    val rawPath = imagePaths?.getOrNull(idx) ?: page.imageInfo?.localUri?.takeIf { it.isNotBlank() }
                    if (!rawPath.isNullOrBlank()) {
                        val file = File(rawPath)
                        val persistentUri = if (file.exists()) {
                            try {
                                kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                                    repo.importPageImage(baseDoc.id, page.pageId, file).absolutePath
                                }
                            } catch (_: Throwable) {
                                rawPath
                            }
                        } else {
                            rawPath
                        }
                        val img = (page.imageInfo ?: com.tscanner.app.ocr.model.OcrImageInfo(widthPx = 1000, heightPx = 1414)).copy(localUri = persistentUri)
                        com.tscanner.app.utils.MultiPageOcrAggregator.enrichPageWithLayoutAndTables(page.copy(imageInfo = img))
                    } else {
                        com.tscanner.app.utils.MultiPageOcrAggregator.enrichPageWithLayoutAndTables(page)
                    }
                }
            )

            val savedDoc = try {
                kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    val saveRes = repo.saveDocument(docWithImages)
                    (saveRes as? com.tscanner.app.ocr.data.RepositoryResult.Success)?.value
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to persist initial multi-page document: ${t.message}")
                null
            }

            start(
                context = context,
                text = result.fullText,
                engine = fallbackEngineLabel,
                engineIds = ids,
                engineFallbacks = fallbacks,
                documentLanguage = result.documentLanguage,
                detectionStatus = resolvedStatus,
                detectedLanguages = result.detectedLanguages.toList(),
                imagePaths = imagePaths,
                languageMode = languageMode,
                documentId = savedDoc?.id,
                documentRevision = savedDoc?.revision ?: 1L
            )
        }
    }
}
