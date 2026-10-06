package com.tscanner.app.ui.files

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tscanner.app.MainActivity
import com.tscanner.app.R
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.model.FolderItem
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.databinding.FragmentFilesBinding
import com.tscanner.app.ui.adapter.DocumentAdapter
import com.tscanner.app.ui.adapter.FolderAdapter
import com.tscanner.app.ui.dialogs.ConfirmDeleteDialog
import com.tscanner.app.ui.dialogs.CreateFolderDialog
import com.tscanner.app.ui.dialogs.OcrLanguageSelectionDialog
import com.tscanner.app.ui.dialogs.RenameDocumentDialog
import com.tscanner.app.ui.dialogs.VipUpgradeDialog
import com.tscanner.app.ui.docmanagement.DocumentManagementActivity
import com.tscanner.app.ui.ocr.OcrResultActivity
import com.tscanner.app.ui.viewer.PdfViewerActivity
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.CloudBackupManager
import com.tscanner.app.utils.FileUtils
import com.tscanner.app.utils.MultiPageOcrAggregator
import com.tscanner.app.utils.MultiPageOcrResult
import com.tscanner.app.utils.OcrResult
import com.tscanner.app.utils.PdfConverterHelper
import com.tscanner.app.utils.TextRecognitionHelper
import com.tscanner.app.utils.WatermarkHelper
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class FilesFragment : Fragment() {

    private var _binding: FragmentFilesBinding? = null
    private val binding get() = _binding!!

    private lateinit var docAdapter: DocumentAdapter
    private lateinit var folderAdapter: FolderAdapter
    private lateinit var repo: DocumentRepo

    private var currentFolderId: String? = null

    // Image picker
    private lateinit var imagePickerLauncher: ActivityResultLauncher<String>
    // File picker
    private lateinit var filePickerLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = DocumentRepo.getInstance(requireContext())

        imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                handleImportImages(uris)
            }
        }

        filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                handleImportFile(uri)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerViews()
        setupListeners()
        observeData()
    }

    private fun setupRecyclerViews() {
        folderAdapter = FolderAdapter(
            onFolderClick = { folder ->
                openFolder(folder)
            },
            onDeleteClick = { folder ->
                confirmDeleteFolder(folder)
            }
        )
        binding.rvFolders.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFolders.adapter = folderAdapter

        docAdapter = DocumentAdapter(
            onItemClick = { doc ->
                openDocument(doc)
            },
            onActionClick = { doc, action ->
                handleDocumentAction(doc, action)
            }
        )
        binding.rvFilesDocs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFilesDocs.adapter = docAdapter
    }

    private fun setupListeners() {
        // Action Card 1: Nhập tập tin
        binding.cardImportFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf("application/pdf", "image/*", "text/*"))
        }

        // Action Card 2: Nhập ảnh
        binding.cardImportImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        // Action Card 3: Tạo thư mục
        binding.cardCreateFolder.setOnClickListener {
            CreateFolderDialog(requireContext()) {
                refreshView()
            }.show()
        }

        // Action Card 4: Quản lý tài liệu
        binding.cardDocManagement.setOnClickListener {
            DocumentManagementActivity.start(requireContext())
        }

        // Empty state "Tài liệu" button
        binding.btnTabDocuments.setOnClickListener {
            (activity as? MainActivity)?.startDocumentScan()
        }

        // Breadcrumb back
        binding.btnBackToRoot.setOnClickListener {
            currentFolderId = null
            binding.layoutFolderBreadcrumb.visibility = View.GONE
            refreshView()
        }

        // Search in Files tab
        binding.etSearchFiles.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshView()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun observeData() {
        repo.documents.observe(viewLifecycleOwner) {
            refreshView()
        }
        repo.folders.observe(viewLifecycleOwner) {
            refreshView()
        }
        AppAuthManager.currentUser.observe(viewLifecycleOwner) {
            refreshView()
        }
    }

    private fun openFolder(folder: FolderItem) {
        currentFolderId = folder.id
        binding.layoutFolderBreadcrumb.visibility = View.VISIBLE
        binding.tvCurrentFolderName.text = folder.name
        refreshView()
    }

    private fun confirmDeleteFolder(folder: FolderItem) {
        ConfirmDeleteDialog(
            context = requireContext(),
            title = getString(R.string.delete_folder_title),
            message = getString(R.string.delete_folder_message, folder.name)
        ) {
            repo.deleteFolder(folder.id)
            if (currentFolderId == folder.id) {
                currentFolderId = null
                binding.layoutFolderBreadcrumb.visibility = View.GONE
            }
            refreshView()
            Toast.makeText(requireContext(), getString(R.string.folder_deleted), Toast.LENGTH_SHORT).show()
        }.show()
    }

    private fun refreshView() {
        val query = binding.etSearchFiles.text.toString().trim()
        val currentUserId = AppAuthManager.getCurrentUser()?.id
        val allFolders = repo.folders.value ?: emptyList()
        val allDocs = repo.getDocumentsForUser(currentUserId, includeGuest = (currentUserId == null))

        val filteredFolders = if (currentFolderId == null) {
            if (query.isBlank()) allFolders else allFolders.filter { it.name.contains(query, true) }
        } else {
            emptyList()
        }

        val filteredDocs = if (query.isBlank()) {
            allDocs.filter { it.folderId == currentFolderId }
        } else {
            allDocs.filter { it.title.contains(query, true) && (currentFolderId == null || it.folderId == currentFolderId) }
        }

        if (filteredFolders.isEmpty() && filteredDocs.isEmpty()) {
            binding.layoutEmptyFiles.visibility = View.VISIBLE
            binding.rvFolders.visibility = View.GONE
            binding.rvFilesDocs.visibility = View.GONE
        } else {
            binding.layoutEmptyFiles.visibility = View.GONE

            if (filteredFolders.isNotEmpty()) {
                binding.rvFolders.visibility = View.VISIBLE
                folderAdapter.submitList(filteredFolders)
            } else {
                binding.rvFolders.visibility = View.GONE
            }

            if (filteredDocs.isNotEmpty()) {
                binding.rvFilesDocs.visibility = View.VISIBLE
                docAdapter.submitList(filteredDocs)
            } else {
                binding.rvFilesDocs.visibility = View.GONE
            }
        }
    }

    private fun openDocument(doc: DocumentItem) {
        val currentUserId = AppAuthManager.getCurrentUser()?.id
        val validDoc = repo.getDocumentForUser(doc.id, currentUserId, allowGuest = (currentUserId == null))
        if (validDoc == null) {
            Toast.makeText(requireContext(), getString(R.string.document_access_denied), Toast.LENGTH_SHORT).show()
            return
        }

        if (validDoc.pdfPath != null && File(validDoc.pdfPath).exists()) {
            PdfViewerActivity.start(requireContext(), validDoc.pdfPath, validDoc.title, validDoc.pagePaths)
        } else if (!validDoc.driveFileId.isNullOrEmpty()) {
            val progress = MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.downloading_from_drive_title)
                .setMessage(getString(R.string.downloading_from_drive_message, validDoc.title))
                .setCancelable(false)
                .create()
            progress.show()

            CloudBackupManager.downloadDocument(requireContext(), validDoc) { downloadedFile ->
                progress.dismiss()
                if (downloadedFile != null && downloadedFile.exists()) {
                    PdfViewerActivity.start(requireContext(), downloadedFile.absolutePath, validDoc.title, emptyList())
                } else {
                    Toast.makeText(requireContext(), getString(R.string.download_from_drive_failed), Toast.LENGTH_SHORT).show()
                }
            }
        } else if (validDoc.pagePaths.isNotEmpty()) {
            val first = validDoc.pagePaths.first()
            val intent = Intent(Intent.ACTION_VIEW).apply {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.provider",
                    File(first)
                )
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } else {
            Toast.makeText(requireContext(), getString(R.string.pdf_not_found), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleDocumentAction(doc: DocumentItem, action: DocumentAdapter.ActionType) {
        val currentUserId = AppAuthManager.getCurrentUser()?.id
        val validDoc = repo.getDocumentForUser(doc.id, currentUserId, allowGuest = (currentUserId == null))
        if (validDoc == null) {
            Toast.makeText(requireContext(), getString(R.string.document_access_denied), Toast.LENGTH_SHORT).show()
            return
        }

        when (action) {
            DocumentAdapter.ActionType.SHARE -> {
                val path = validDoc.pdfPath ?: validDoc.pagePaths.firstOrNull()
                if (path != null) {
                    val file = File(path)
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.provider",
                        file
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = if (path.endsWith(".pdf", true)) "application/pdf" else "image/*"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
                }
            }
            DocumentAdapter.ActionType.DELETE -> {
                val deleteMsg = if (validDoc.isSynced || !validDoc.driveFileId.isNullOrEmpty()) {
                    getString(R.string.delete_document_synced_message, validDoc.title)
                } else {
                    getString(R.string.delete_document_named_message, validDoc.title)
                }
                ConfirmDeleteDialog(
                    context = requireContext(),
                    title = getString(R.string.delete_document_title),
                    message = deleteMsg
                ) {
                    repo.deleteDocument(validDoc.id, currentUserId)
                    Toast.makeText(requireContext(), getString(R.string.doc_deleted_toast), Toast.LENGTH_SHORT).show()
                }.show()
            }
            DocumentAdapter.ActionType.RENAME -> {
                RenameDocumentDialog(
                    context = requireContext(),
                    currentName = validDoc.title
                ) { newName ->
                    repo.renameDocument(validDoc.id, newName, currentUserId)
                    Toast.makeText(requireContext(), getString(R.string.doc_renamed_toast), Toast.LENGTH_SHORT).show()
                }.show()
            }
            DocumentAdapter.ActionType.OCR -> {
                processDocumentOcr(validDoc, isWordExport = false)
            }
            DocumentAdapter.ActionType.CONVERT_WORD -> {
                processDocumentOcr(validDoc, isWordExport = true)
            }
            DocumentAdapter.ActionType.MOVE_FOLDER -> {
                showMoveToFolderDialog(validDoc)
            }
            else -> {}
        }
    }

    private fun showMoveToFolderDialog(doc: DocumentItem) {
        val currentUserId = AppAuthManager.getCurrentUser()?.id
        val folders = repo.folders.value ?: emptyList()
        val folderNames = mutableListOf(getString(R.string.root_folder_default))
        folderNames.addAll(folders.map { it.name })

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.move_to_folder_title)
            .setItems(folderNames.toTypedArray()) { _, which ->
                val targetFolderId = if (which == 0) null else folders[which - 1].id
                val targetName = folderNames[which]
                repo.moveDocumentToFolder(doc.id, targetFolderId, currentUserId)
                Toast.makeText(requireContext(), getString(R.string.moved_doc_to_folder_format, doc.title, targetName), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun processDocumentOcr(doc: DocumentItem, isWordExport: Boolean) {
        val ctx = context ?: return
        if (!TextRecognitionHelper.isOcrDocumentLanguageConfigured(ctx)) {
            OcrLanguageSelectionDialog(requireActivity()) {
                processDocumentOcr(doc, isWordExport)
            }.show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            var pdfFile = doc.pdfPath?.let { File(it) }?.takeIf { it.exists() }
            if (pdfFile == null && !doc.driveFileId.isNullOrEmpty()) {
                Toast.makeText(ctx, getString(R.string.downloading_from_drive_toast), Toast.LENGTH_SHORT).show()
                val downloaded = kotlinx.coroutines.suspendCancellableCoroutine<File?> { cont ->
                    CloudBackupManager.downloadDocument(ctx, doc) { file ->
                        if (cont.isActive) cont.resumeWith(Result.success(file))
                    }
                }
                if (!isAdded || view == null) return@launch
                pdfFile = downloaded
            }

            var pages = doc.pagePaths.filter { File(it).exists() }
            if (pages.isEmpty() && pdfFile != null) {
                Toast.makeText(ctx, getString(R.string.extracting_pages_toast), Toast.LENGTH_SHORT).show()
                val conv = PdfConverterHelper.convertPdfToImagesStructured(ctx, pdfFile)
                when (conv) {
                    is PdfConverterHelper.PdfToImagesResult.Success -> {
                        pages = conv.imagePaths
                    }
                    is PdfConverterHelper.PdfToImagesResult.Failure -> {
                        val msg = if (conv.totalPages > 0) {
                            getString(R.string.ocr_page_error_format, conv.failedPage, conv.message)
                        } else {
                            getString(R.string.no_pages_for_ocr)
                        }
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                }
            }

            if (!isAdded || view == null) return@launch

            if (pages.isEmpty()) {
                Toast.makeText(ctx, getString(R.string.no_pages_for_ocr), Toast.LENGTH_SHORT).show()
                return@launch
            }

            val actionMsg = if (isWordExport) getString(R.string.extracting_and_converting_word) else getString(R.string.ocr_processing)
            Toast.makeText(ctx, actionMsg, Toast.LENGTH_SHORT).show()

            val ocrRequest = TextRecognitionHelper.getDefaultOcrRequest(ctx)
            val pageResults = mutableListOf<OcrResult>()

            for ((idx, pagePath) in pages.withIndex()) {
                if (!isAdded || view == null) return@launch
                val result = TextRecognitionHelper.recognizeTextFromFileStructured(ctx, pagePath, ocrRequest)
                pageResults.add(result)
                // Dừng xử lý các trang tiếp theo nếu gặp lỗi engine nghiêm trọng
                if (MultiPageOcrAggregator.isBlockingError(result)) {
                    break
                }
            }

            if (!isAdded || view == null) return@launch

            val aggResult = TextRecognitionHelper.aggregateMultiPageResults(ctx, pageResults)
            when (aggResult) {
                is MultiPageOcrResult.PageError -> {
                    val errorMsg = TextRecognitionHelper.formatPageErrorMessage(ctx, aggResult)
                    Toast.makeText(ctx, errorMsg, Toast.LENGTH_LONG).show()
                    return@launch
                }
                is MultiPageOcrResult.AllNoText -> {
                    Toast.makeText(ctx, getString(R.string.no_text_found), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                is MultiPageOcrResult.Success -> {
                    TextRecognitionHelper.formatPartialSuccessNotice(ctx, aggResult)?.let { notice ->
                        Toast.makeText(ctx, notice, Toast.LENGTH_SHORT).show()
                    }

                    if (isWordExport) {
                        val exportDir = FileUtils.getExportsDir(ctx)
                        val file = File(exportDir, "${doc.title}_Word.doc")
                        val success = PdfConverterHelper.exportTextToWord(
                            text = aggResult.fullText,
                            outputFile = file,
                            addWatermark = WatermarkHelper.shouldApplyWatermark(ctx)
                        )
                        if (success) {
                            FileUtils.saveFileToDownloads(ctx, file, "application/msword")
                            Toast.makeText(ctx, getString(R.string.word_saved_format, file.name), Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(ctx, getString(R.string.export_word_failed), Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val resolvedEngineLabel = if (aggResult.enginesUsed.isNotEmpty()) {
                            aggResult.enginesUsed.joinToString(", ")
                        } else {
                            TextRecognitionHelper.getPreferredEngineDisplayName(ctx)
                        }
                        OcrResultActivity.start(ctx, aggResult, resolvedEngineLabel, imagePaths = ArrayList(pages))
                    }
                }
            }
        }
    }

    private fun handleImportImages(uris: List<Uri>) {
        viewLifecycleOwner.lifecycleScope.launch {
            val imgDir = FileUtils.getImagesDir(requireContext())
            val pagePaths = mutableListOf<String>()
            val docId = UUID.randomUUID().toString()

            uris.forEachIndexed { index, uri ->
                val targetFile = File(imgDir, "imported_${docId}_${index + 1}.jpg")
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                pagePaths.add(targetFile.absolutePath)
            }

            val docDir = FileUtils.getDocumentsDir(requireContext())
            val pdfFile = File(docDir, "imported_${docId}.pdf")
            val success = PdfConverterHelper.createPdfFromImages(
                imagePaths = pagePaths,
                outputFile = pdfFile,
                addWatermark = WatermarkHelper.shouldApplyWatermark(requireContext())
            )

            val docItem = DocumentItem(
                id = docId,
                title = getString(R.string.imported_images_title) + " " + FileUtils.formatDate(System.currentTimeMillis()),
                pdfPath = pdfFile.absolutePath,
                thumbnailPath = pagePaths.firstOrNull(),
                pagePaths = pagePaths,
                pageCount = pagePaths.size,
                sizeBytes = pdfFile.length(),
                createdAt = System.currentTimeMillis(),
                folderId = currentFolderId
            )

            repo.addDocument(docItem)
            Toast.makeText(requireContext(), getString(R.string.imported_images_count_format, pagePaths.size), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleImportFile(uri: Uri) {
        val fileName = FileUtils.getFileName(requireContext(), uri)
        val docDir = FileUtils.getDocumentsDir(requireContext())
        val savedFile = FileUtils.copyUriToAppStorage(requireContext(), uri, docDir, "file")

        if (savedFile != null) {
            val docId = UUID.randomUUID().toString()
            val isPdf = savedFile.name.endsWith(".pdf", ignoreCase = true)
            var thumbPath: String? = null

            if (isPdf) {
                val thumbFile = File(FileUtils.getImagesDir(requireContext()), "thumb_${docId}.jpg")
                if (PdfConverterHelper.renderPdfFirstPage(savedFile, thumbFile)) {
                    thumbPath = thumbFile.absolutePath
                }
            } else {
                thumbPath = savedFile.absolutePath
            }

            val docItem = DocumentItem(
                id = docId,
                title = fileName,
                pdfPath = if (isPdf) savedFile.absolutePath else null,
                thumbnailPath = thumbPath,
                pagePaths = if (!isPdf) listOf(savedFile.absolutePath) else emptyList(),
                pageCount = 1,
                sizeBytes = savedFile.length(),
                createdAt = System.currentTimeMillis(),
                folderId = currentFolderId
            )
            repo.addDocument(docItem)
            Toast.makeText(requireContext(), getString(R.string.file_imported, fileName), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), getString(R.string.import_file_failed), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
