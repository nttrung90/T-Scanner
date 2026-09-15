package com.tscanner.app.ui.home

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
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.databinding.FragmentHomeBinding
import com.tscanner.app.ui.adapter.DocumentAdapter
import com.tscanner.app.ui.dialogs.ConfirmDeleteDialog
import com.tscanner.app.ui.dialogs.QrResultDialog
import com.tscanner.app.ui.dialogs.RenameDocumentDialog
import com.tscanner.app.ui.dialogs.VipUpgradeDialog
import com.tscanner.app.ui.idcard.IdCardComposeActivity
import com.tscanner.app.ui.ocr.OcrResultActivity
import com.tscanner.app.ui.viewer.PdfViewerActivity
import com.tscanner.app.utils.CloudBackupManager
import com.tscanner.app.utils.FileUtils
import com.tscanner.app.utils.PdfConverterHelper
import com.tscanner.app.utils.QrCodeResult
import com.tscanner.app.utils.QrScannerHelper
import com.tscanner.app.utils.TextRecognitionHelper
import com.tscanner.app.utils.WatermarkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var docAdapter: DocumentAdapter
    private lateinit var repo: DocumentRepo

    // Image picker for "Nhập ảnh"
    private lateinit var imagePickerLauncher: ActivityResultLauncher<String>
    // File picker for "Nhập tập tin"
    private lateinit var filePickerLauncher: ActivityResultLauncher<Array<String>>
    // Image picker for OCR
    private lateinit var ocrImagePickerLauncher: ActivityResultLauncher<String>
    // Image picker for "Quét thẻ" (chọn 2 mặt từ bộ sưu tập)
    private lateinit var idCardPickerLauncher: ActivityResultLauncher<String>
    // Image picker for "Quét QR" từ bộ sưu tập
    private lateinit var qrGalleryPickerLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = DocumentRepo.getInstance(requireContext())

        // Image picker
        imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                handleImportImages(uris)
            }
        }

        // File picker
        filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                handleImportFile(uri)
            }
        }

        // OCR Image Picker
        ocrImagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                handleOcrFromUri(uri)
            }
        }

        // ID Card Image Picker (2 mặt thẻ)
        idCardPickerLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                handleImportIdCardImages(uris)
            }
        }

        // QR Gallery Image Picker
        qrGalleryPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                handleQrFromGallery(uri)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupListeners()
        observeData()
    }

    private fun setupRecyclerView() {
        docAdapter = DocumentAdapter(
            onItemClick = { doc ->
                openDocument(doc)
            },
            onActionClick = { doc, action ->
                handleDocumentAction(doc, action)
            }
        )

        binding.rvRecentDocs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRecentDocs.adapter = docAdapter
    }

    private fun setupListeners() {
        // Quick Action 1: Quét (Vào thẳng Quét AI, nhấn giữ để mở Camera Siêu Tốc)
        binding.btnActionScan.setOnClickListener {
            (activity as? MainActivity)?.startGoogleAiScan()
        }
        binding.btnActionScan.setOnLongClickListener {
            Toast.makeText(requireContext(), "Đang mở Camera Siêu Tốc...", Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.startFastDocumentScan()
            true
        }

        // Quick Action 2: Quét thẻ (ID Card) (Vào thẳng Quét AI thẻ, nhấn giữ để mở Camera Siêu Tốc)
        binding.btnActionIdCard.setOnClickListener {
            (activity as? MainActivity)?.startGoogleIdCardScan()
        }
        binding.btnActionIdCard.setOnLongClickListener {
            Toast.makeText(requireContext(), "Đang mở Chụp thẻ Siêu Tốc...", Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.startFastIdCardScan()
            true
        }

        // Quick Action 3: Quét QR
        binding.btnActionQr.setOnClickListener {
            showQrOptionsDialog()
        }

        // Quick Action 4: Công cụ PDF
        binding.btnActionPdfTools.setOnClickListener {
            showPdfToolsDialog()
        }

        // Quick Action 5: Nhập ảnh
        binding.btnActionImportImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        // Quick Action 6: Nhập tập tin
        binding.btnActionImportFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf("application/pdf", "image/*", "text/*"))
        }

        // Quick Action 7: Trích xuất văn bản (OCR)
        binding.btnActionOcr.setOnClickListener {
            ocrImagePickerLauncher.launch("image/*")
        }

        // Quick Action 8: Tạo file PDF
        binding.btnActionCreatePdf.setOnClickListener {
            imagePickerLauncher.launch("image/*")
            Toast.makeText(requireContext(), "Chọn ảnh từ bộ sưu tập để tạo file PDF", Toast.LENGTH_SHORT).show()
        }

        // Empty state scan button
        binding.btnScanNewDocument.setOnClickListener {
            (activity as? MainActivity)?.startDocumentScan()
        }

        // VIP icon
        binding.btnVipHome.setOnClickListener {
            VipUpgradeDialog(requireContext()).show()
        }

        // See all recent files -> switch to Files tab
        binding.tvSeeAllRecent.setOnClickListener {
            (activity as? MainActivity)?.selectTab(R.id.nav_files)
        }

        // Search text change
        binding.etSearchHome.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty()
                val filtered = repo.searchDocuments(query)
                updateListUI(filtered)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun observeData() {
        repo.documents.observe(viewLifecycleOwner) { docs ->
            val query = binding.etSearchHome.text.toString()
            val filtered = if (query.isBlank()) docs.take(10) else repo.searchDocuments(query)
            updateListUI(filtered)
        }
    }

    private fun updateListUI(docs: List<DocumentItem>) {
        if (docs.isEmpty()) {
            binding.layoutEmptyRecent.visibility = View.VISIBLE
            binding.rvRecentDocs.visibility = View.GONE
        } else {
            binding.layoutEmptyRecent.visibility = View.GONE
            binding.rvRecentDocs.visibility = View.VISIBLE
            docAdapter.submitList(docs)
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
            Toast.makeText(requireContext(), "Không tìm thấy tập tin tài liệu", Toast.LENGTH_SHORT).show()
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
            else -> {}
        }
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
            val docId = UUID.randomUUID().toString()
            val tempDir = FileUtils.getTempScanSessionDir(requireContext(), docId)
            val tempPagePaths = mutableListOf<String>()

            uris.forEachIndexed { index, uri ->
                val targetFile = File(tempDir, "imported_${index + 1}.jpg")
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                tempPagePaths.add(targetFile.absolutePath)
            }

            // Convert images to PDF
            val docDir = FileUtils.getDocumentsDir(requireContext())
            val pdfFile = File(docDir, "Imported_${System.currentTimeMillis()}.pdf")
            val success = PdfConverterHelper.createPdfFromImages(
                imagePaths = tempPagePaths,
                outputFile = pdfFile,
                addWatermark = WatermarkHelper.shouldApplyWatermark(requireContext())
            )

            if (success && pdfFile.exists()) {
                val thumbsDir = FileUtils.getThumbnailsDir(requireContext())
                val thumbFile = File(thumbsDir, "thumb_${docId}.jpg")
                val thumbPath = if (PdfConverterHelper.renderPdfFirstPage(pdfFile, thumbFile)) {
                    thumbFile.absolutePath
                } else null

                // Clean up temp images
                FileUtils.deleteTempSession(requireContext(), docId)

                val docItem = DocumentItem(
                    id = docId,
                    title = "Ảnh đã nhập " + FileUtils.formatDate(System.currentTimeMillis()),
                    pdfPath = pdfFile.absolutePath,
                    thumbnailPath = thumbPath,
                    pagePaths = emptyList(),
                    pageCount = uris.size,
                    sizeBytes = pdfFile.length(),
                    createdAt = System.currentTimeMillis()
                )

                repo.addDocument(docItem)
                Toast.makeText(requireContext(), "Đã nhập ${uris.size} ảnh thành công!", Toast.LENGTH_SHORT).show()
                PdfViewerActivity.start(requireContext(), pdfFile.absolutePath, docItem.title)
            }
        }
    }

    private fun handleImportFile(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val (savedFile, fileName) = withContext(Dispatchers.IO) {
                val fName = FileUtils.getFileName(requireContext(), uri)
                val docDir = FileUtils.getDocumentsDir(requireContext())
                val sFile = FileUtils.copyUriToAppStorage(requireContext(), uri, docDir, "file")
                Pair(sFile, fName)
            }

            if (savedFile != null) {
                val docId = UUID.randomUUID().toString()
                val isPdf = savedFile.name.endsWith(".pdf", ignoreCase = true)
                val thumbPath = withContext(Dispatchers.IO) {
                    if (isPdf) {
                        val thumbsDir = FileUtils.getThumbnailsDir(requireContext())
                        val thumbFile = File(thumbsDir, "thumb_${docId}.jpg")
                        if (PdfConverterHelper.renderPdfFirstPage(savedFile, thumbFile)) {
                            thumbFile.absolutePath
                        } else null
                    } else {
                        savedFile.absolutePath
                    }
                }

                val docItem = DocumentItem(
                    id = docId,
                    title = fileName,
                    pdfPath = if (isPdf) savedFile.absolutePath else null,
                    thumbnailPath = thumbPath,
                    pagePaths = if (!isPdf) listOf(savedFile.absolutePath) else emptyList(),
                    pageCount = 1,
                    sizeBytes = savedFile.length(),
                    createdAt = System.currentTimeMillis()
                )
                repo.addDocument(docItem)
                if (isAdded && context != null) {
                    Toast.makeText(requireContext(), getString(R.string.file_imported, fileName), Toast.LENGTH_SHORT).show()
                    if (isPdf) {
                        PdfViewerActivity.start(requireContext(), savedFile.absolutePath, docItem.title)
                    }
                }
            } else {
                if (isAdded && context != null) {
                    Toast.makeText(requireContext(), "Không thể nhập tập tin", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleOcrFromUri(uri: Uri) {
        Toast.makeText(requireContext(), getString(R.string.ocr_processing), Toast.LENGTH_SHORT).show()
        TextRecognitionHelper.recognizeTextFromUri(
            requireContext(),
            uri,
            onSuccess = { text ->
                if (isAdded && context != null) {
                    if (text.isNotBlank()) {
                        OcrResultActivity.start(requireContext(), text)
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.no_text_found), Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onError = {
                if (isAdded && context != null) {
                    Toast.makeText(requireContext(), getString(R.string.no_text_found), Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun showIdCardOptionsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_id_card_options, null)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Option 1: Quét thẻ bằng AI (Mặc định)
        dialogView.findViewById<View>(R.id.btn_option_camera)?.setOnClickListener {
            dialog.dismiss()
            (activity as? MainActivity)?.startGoogleIdCardScan()
        }

        // Option 2: Chụp thẻ Siêu Tốc (CameraX)
        dialogView.findViewById<View>(R.id.btn_option_id_card_fast)?.setOnClickListener {
            dialog.dismiss()
            (activity as? MainActivity)?.startFastIdCardScan()
        }

        // Option 3: Chọn từ Bộ sưu tập
        dialogView.findViewById<View>(R.id.btn_option_gallery)?.setOnClickListener {
            dialog.dismiss()
            idCardPickerLauncher.launch("image/*")
        }

        dialog.show()
    }

    private fun handleImportIdCardImages(uris: List<Uri>) {
        viewLifecycleOwner.lifecycleScope.launch {
            val sessionId = UUID.randomUUID().toString()
            val tempDir = FileUtils.getTempScanSessionDir(requireContext(), sessionId)

            val frontFile = uris.getOrNull(0)?.let { uri ->
                val f = File(tempDir, "id_front.jpg")
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(f).use { output -> input.copyTo(output) }
                }
                f.absolutePath
            }

            val backFile = uris.getOrNull(1)?.let { uri ->
                val f = File(tempDir, "id_back.jpg")
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(f).use { output -> input.copyTo(output) }
                }
                f.absolutePath
            }

            if (frontFile != null || backFile != null) {
                IdCardComposeActivity.startWithSides(
                    context = requireContext(),
                    frontPath = frontFile,
                    backPath = backFile
                )
            } else {
                Toast.makeText(requireContext(), "Không thể đọc ảnh thẻ đã chọn", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPdfToolsDialog() {
        val options = arrayOf("Tạo PDF từ ảnh bộ sưu tập", "Nhập & Xem file PDF", "Ghép nối tài liệu PDF")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.action_pdf_tools))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> imagePickerLauncher.launch("image/*")
                    1 -> filePickerLauncher.launch(arrayOf("application/pdf"))
                    2 -> {
                        imagePickerLauncher.launch("image/*")
                        Toast.makeText(requireContext(), "Chọn các ảnh để nối thành 1 file PDF", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showQrOptionsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_qr_options, null)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<View>(R.id.btn_option_qr_camera)?.setOnClickListener {
            dialog.dismiss()
            startQrCameraScan()
        }

        dialogView.findViewById<View>(R.id.btn_option_qr_gallery)?.setOnClickListener {
            dialog.dismiss()
            qrGalleryPickerLauncher.launch("image/*")
        }

        dialog.show()
    }

    private fun startQrCameraScan() {
        val act = activity ?: return
        QrScannerHelper.startCameraScan(
            activity = act,
            onSuccess = { result ->
                if (isAdded && context != null) {
                    showQrResult(result)
                }
            },
            onCancel = {
                // Người dùng hủy quét camera
            },
            onError = { err ->
                if (isAdded && context != null) {
                    Toast.makeText(requireContext(), err, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun handleQrFromGallery(uri: Uri) {
        val ctx = context ?: return
        Toast.makeText(ctx, "Đang đọc mã QR...", Toast.LENGTH_SHORT).show()
        QrScannerHelper.scanFromUri(
            context = ctx,
            uri = uri,
            onSuccess = { result ->
                if (isAdded && context != null) {
                    showQrResult(result)
                }
            },
            onError = { err ->
                if (isAdded && context != null) {
                    Toast.makeText(requireContext(), err, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun showQrResult(result: QrCodeResult) {
        if (!isAdded || context == null) return
        QrResultDialog(
            context = requireContext(),
            result = result,
            onRescan = {
                if (isAdded && context != null) {
                    showQrOptionsDialog()
                }
            }
        ).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
