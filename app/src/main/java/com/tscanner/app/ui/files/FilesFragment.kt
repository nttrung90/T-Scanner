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
import com.tscanner.app.ui.dialogs.RenameDocumentDialog
import com.tscanner.app.ui.dialogs.VipUpgradeDialog
import com.tscanner.app.ui.ocr.OcrResultActivity
import com.tscanner.app.ui.viewer.PdfViewerActivity
import com.tscanner.app.utils.CloudBackupManager
import com.tscanner.app.utils.FileUtils
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
            title = "Xóa thư mục",
            message = "Bạn có chắc chắn muốn xóa thư mục '${folder.name}'? Các tài liệu bên trong sẽ được chuyển ra ngoài."
        ) {
            repo.deleteFolder(folder.id)
            if (currentFolderId == folder.id) {
                currentFolderId = null
                binding.layoutFolderBreadcrumb.visibility = View.GONE
            }
            refreshView()
            Toast.makeText(requireContext(), "Đã xóa thư mục", Toast.LENGTH_SHORT).show()
        }.show()
    }

    private fun refreshView() {
        val query = binding.etSearchFiles.text.toString().trim()
        val allFolders = repo.folders.value ?: emptyList()
        val allDocs = repo.documents.value ?: emptyList()

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
        if (doc.pdfPath != null && File(doc.pdfPath).exists()) {
            PdfViewerActivity.start(requireContext(), doc.pdfPath, doc.title, doc.pagePaths)
        } else if (!doc.driveFileId.isNullOrEmpty()) {
            val progress = MaterialAlertDialogBuilder(requireContext())
                .setTitle("Tải tài liệu từ Google Drive")
                .setMessage("Đang tải ${doc.title} từ đám mây xuống thiết bị...")
                .setCancelable(false)
                .create()
            progress.show()

            CloudBackupManager.downloadDocument(requireContext(), doc) { downloadedFile ->
                progress.dismiss()
                if (downloadedFile != null && downloadedFile.exists()) {
                    PdfViewerActivity.start(requireContext(), downloadedFile.absolutePath, doc.title, emptyList())
                } else {
                    Toast.makeText(requireContext(), "Không thể tải tài liệu từ Google Drive. Vui lòng kiểm tra kết nối mạng.", Toast.LENGTH_SHORT).show()
                }
            }
        } else if (doc.pagePaths.isNotEmpty()) {
            val first = doc.pagePaths.first()
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
            Toast.makeText(requireContext(), "Không tìm thấy tập tin", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleDocumentAction(doc: DocumentItem, action: DocumentAdapter.ActionType) {
        when (action) {
            DocumentAdapter.ActionType.SHARE -> {
                val path = doc.pdfPath ?: doc.pagePaths.firstOrNull()
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
                ConfirmDeleteDialog(
                    context = requireContext(),
                    title = "Xóa tài liệu",
                    message = "Bạn có chắc chắn muốn xóa tài liệu '${doc.title}'?"
                ) {
                    repo.deleteDocument(doc.id)
                    Toast.makeText(requireContext(), "Đã xóa tài liệu", Toast.LENGTH_SHORT).show()
                }.show()
            }
            DocumentAdapter.ActionType.RENAME -> {
                RenameDocumentDialog(
                    context = requireContext(),
                    currentName = doc.title
                ) { newName ->
                    repo.renameDocument(doc.id, newName)
                    Toast.makeText(requireContext(), "Đã đổi tên", Toast.LENGTH_SHORT).show()
                }.show()
            }
            DocumentAdapter.ActionType.OCR -> {
                processDocumentOcr(doc, isWordExport = false)
            }
            DocumentAdapter.ActionType.CONVERT_WORD -> {
                processDocumentOcr(doc, isWordExport = true)
            }
            DocumentAdapter.ActionType.MOVE_FOLDER -> {
                showMoveToFolderDialog(doc)
            }
            else -> {}
        }
    }

    private fun showMoveToFolderDialog(doc: DocumentItem) {
        val folders = repo.folders.value ?: emptyList()
        val folderNames = mutableListOf("Thư mục gốc (Mặc định)")
        folderNames.addAll(folders.map { it.name })

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Chuyển vào thư mục")
            .setItems(folderNames.toTypedArray()) { _, which ->
                val targetFolderId = if (which == 0) null else folders[which - 1].id
                val targetName = folderNames[which]
                repo.moveDocumentToFolder(doc.id, targetFolderId)
                Toast.makeText(requireContext(), "Đã chuyển '${doc.title}' vào $targetName", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun processDocumentOcr(doc: DocumentItem, isWordExport: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            var pdfFile = doc.pdfPath?.let { File(it) }?.takeIf { it.exists() }
            if (pdfFile == null && !doc.driveFileId.isNullOrEmpty()) {
                Toast.makeText(ctx, "Đang tải tài liệu từ Google Drive...", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(ctx, "Đang trích xuất các trang...", Toast.LENGTH_SHORT).show()
                pages = PdfConverterHelper.convertPdfToImages(ctx, pdfFile)
            }

            if (!isAdded || view == null) return@launch

            if (pages.isEmpty()) {
                Toast.makeText(ctx, "Không tìm thấy nội dung để nhận diện", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val actionMsg = if (isWordExport) "Đang trích xuất và chuyển sang Word..." else getString(R.string.ocr_processing)
            Toast.makeText(ctx, actionMsg, Toast.LENGTH_SHORT).show()

            val fullText = StringBuilder()
            for ((idx, pagePath) in pages.withIndex()) {
                val pageText = TextRecognitionHelper.recognizeTextFromFileSync(ctx, pagePath)
                if (pages.size > 1) {
                    fullText.append("--- TRANG ${idx + 1} ---\n")
                }
                fullText.append(pageText).append("\n\n")
            }

            if (!isAdded || view == null) return@launch

            val resultText = fullText.toString().trim()
            if (resultText.isEmpty()) {
                Toast.makeText(ctx, getString(R.string.no_text_found), Toast.LENGTH_SHORT).show()
                return@launch
            }

            if (isWordExport) {
                val exportDir = FileUtils.getExportsDir(ctx)
                val file = File(exportDir, "${doc.title}_Word.doc")
                val success = PdfConverterHelper.exportTextToWord(
                    text = resultText,
                    outputFile = file,
                    addWatermark = WatermarkHelper.shouldApplyWatermark(ctx)
                )
                if (success) {
                    FileUtils.saveFileToDownloads(ctx, file, "application/msword")
                    Toast.makeText(ctx, "Đã tạo và lưu file Word: ${file.name}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(ctx, "Lỗi khi lưu tập tin Word", Toast.LENGTH_SHORT).show()
                }
            } else {
                OcrResultActivity.start(ctx, resultText)
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
                title = "Ảnh đã nhập " + FileUtils.formatDate(System.currentTimeMillis()),
                pdfPath = pdfFile.absolutePath,
                thumbnailPath = pagePaths.firstOrNull(),
                pagePaths = pagePaths,
                pageCount = pagePaths.size,
                sizeBytes = pdfFile.length(),
                createdAt = System.currentTimeMillis(),
                folderId = currentFolderId
            )

            repo.addDocument(docItem)
            Toast.makeText(requireContext(), "Đã nhập ${pagePaths.size} ảnh vào thư mục!", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(requireContext(), "Không thể nhập tập tin", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
