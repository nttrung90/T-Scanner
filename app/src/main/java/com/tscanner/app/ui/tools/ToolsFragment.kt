package com.tscanner.app.ui.tools

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tscanner.app.MainActivity
import com.tscanner.app.R
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.databinding.FragmentToolsBinding
import com.tscanner.app.ui.dialogs.QrResultDialog
import com.tscanner.app.ui.dialogs.SaveExportDialog
import com.tscanner.app.ui.idcard.IdCardComposeActivity
import com.tscanner.app.utils.AppLanguageManager
import com.tscanner.app.utils.FileUtils
import com.tscanner.app.utils.PdfConverterHelper
import com.tscanner.app.utils.QrCodeResult
import com.tscanner.app.utils.QrScannerHelper
import com.tscanner.app.utils.TextRecognitionHelper
import com.tscanner.app.utils.WatermarkHelper
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class ToolsFragment : Fragment() {

    private var _binding: FragmentToolsBinding? = null
    private val binding get() = _binding!!

    private lateinit var repo: DocumentRepo

    // Launchers for input picking
    private lateinit var imagePickerLauncher: ActivityResultLauncher<String>
    private lateinit var idCardPickerLauncher: ActivityResultLauncher<String>
    private lateinit var qrGalleryPickerLauncher: ActivityResultLauncher<String>
    private lateinit var filePickerLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var wordPickerLauncher: ActivityResultLauncher<String>
    private lateinit var excelPickerLauncher: ActivityResultLauncher<String>
    private lateinit var pptPickerLauncher: ActivityResultLauncher<String>
    private lateinit var pdfToImgPickerLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var pdfToLongImgPickerLauncher: ActivityResultLauncher<Array<String>>

    // Launchers for saving exported files
    private var pendingSingleFile: File? = null
    private var pendingMimeType: String = "*/*"
    private var pendingFileTypeTitle: String = "Tập tin"
    private var pendingFilesList: List<File> = emptyList()

    private lateinit var createDocLauncher: ActivityResultLauncher<String>
    private lateinit var selectFolderLauncher: ActivityResultLauncher<Uri?>
    private var progressDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = DocumentRepo.getInstance(requireContext())

        // 1. Nhập ảnh
        imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                handleImportImages(uris)
            }
        }

        // Quét / chọn 2 mặt thẻ
        idCardPickerLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                handleImportIdCardImages(uris)
            }
        }

        // Quét QR từ thư viện ảnh
        qrGalleryPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                handleQrFromGallery(uri)
            }
        }

        // 2. Nhập tập tin
        filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                handleImportFile(uri)
            }
        }

        // 3. Chuyển thành Word
        wordPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                convertToWordFromUri(uri)
            }
        }

        // 4. Chuyển thành Excel
        excelPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                convertToExcelFromUri(uri)
            }
        }

        // 5. Chuyển thành PPT
        pptPickerLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                convertToPptFromUris(uris)
            }
        }

        // 6. PDF thành ảnh
        pdfToImgPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                convertPdfToImagesFromUri(uri)
            }
        }

        // 7. PDF thành ảnh dài
        pdfToLongImgPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                convertPdfToLongImageFromUri(uri)
            }
        }

        // Launcher lưu tập tin đơn (CreateDocument)
        createDocLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
            val file = pendingSingleFile
            if (uri != null && file != null && file.exists()) {
                val success = FileUtils.copyFileToUri(requireContext(), file, uri)
                if (success) {
                    showSaveSuccessDialog(
                        locationDesc = "Thư mục đã chọn",
                        file = file,
                        mimeType = pendingMimeType,
                        title = "Đã lưu $pendingFileTypeTitle thành công"
                    )
                } else {
                    Toast.makeText(requireContext(), "Lỗi khi lưu tập tin", Toast.LENGTH_SHORT).show()
                }
            } else if (uri == null) {
                Toast.makeText(requireContext(), getString(R.string.cancel_save), Toast.LENGTH_SHORT).show()
            }
            pendingSingleFile = null
        }

        // Launcher chọn thư mục lưu (OpenDocumentTree)
        selectFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
            if (treeUri != null) {
                val folderName = FileUtils.getTreeDirectoryName(requireContext(), treeUri)
                if (pendingFilesList.isNotEmpty()) {
                    val savedUris = FileUtils.saveFilesToTreeUri(requireContext(), pendingFilesList, treeUri, "image/jpeg")
                    if (savedUris.isNotEmpty()) {
                        showMultiFilesSuccessDialog(folderName, pendingFilesList, "image/jpeg")
                    } else {
                        Toast.makeText(requireContext(), "Lỗi khi lưu các ảnh vào thư mục", Toast.LENGTH_SHORT).show()
                    }
                } else if (pendingSingleFile != null) {
                    val file = pendingSingleFile!!
                    val savedUri = FileUtils.saveFileToTreeUri(requireContext(), file, treeUri, targetFileName = file.name, mimeType = pendingMimeType)
                    if (savedUri != null) {
                        showSaveSuccessDialog(
                            locationDesc = folderName,
                            file = file,
                            mimeType = pendingMimeType,
                            title = "Đã lưu $pendingFileTypeTitle thành công"
                        )
                    } else {
                        Toast.makeText(requireContext(), "Lỗi khi lưu tập tin vào thư mục", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(requireContext(), getString(R.string.cancel_save), Toast.LENGTH_SHORT).show()
            }
            pendingFilesList = emptyList()
            pendingSingleFile = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentToolsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupListeners()
    }

    private fun setupListeners() {
        // Search icon
        binding.btnSearchTools.setOnClickListener {
            Toast.makeText(requireContext(), "Tìm kiếm công cụ", Toast.LENGTH_SHORT).show()
        }

        // Subtabs
        binding.tabCategoryImport.setOnClickListener {
            binding.tabCategoryImport.setTextColor(resources.getColor(R.color.primary_teal, null))
            binding.tabCategoryConvert.setTextColor(resources.getColor(R.color.text_secondary, null))
        }

        binding.tabCategoryConvert.setOnClickListener {
            binding.tabCategoryConvert.setTextColor(resources.getColor(R.color.primary_teal, null))
            binding.tabCategoryImport.setTextColor(resources.getColor(R.color.text_secondary, null))
        }

        // Mục "Nhập"
        binding.toolImportImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        binding.toolIdCard.setOnClickListener {
            (activity as? MainActivity)?.startGoogleIdCardScan()
        }
        binding.toolIdCard.setOnLongClickListener {
            Toast.makeText(requireContext(), "Đang mở Chụp thẻ Siêu Tốc...", Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.startFastIdCardScan()
            true
        }

        binding.toolImportFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf("application/pdf", "image/*", "text/*"))
        }

        binding.toolQrScan.setOnClickListener {
            showQrOptionsDialog()
        }

        // Mục "Chuyển đổi"
        binding.toolConvertWord.setOnClickListener {
            Toast.makeText(requireContext(), "Chọn ảnh để trích xuất chữ và tạo file Word", Toast.LENGTH_SHORT).show()
            wordPickerLauncher.launch("image/*")
        }

        binding.toolConvertExcel.setOnClickListener {
            Toast.makeText(requireContext(), "Chọn ảnh hóa đơn / bảng biểu để xuất Excel", Toast.LENGTH_SHORT).show()
            excelPickerLauncher.launch("image/*")
        }

        binding.toolConvertPpt.setOnClickListener {
            Toast.makeText(requireContext(), "Chọn các ảnh để xuất thành bài trình chiếu PPT", Toast.LENGTH_SHORT).show()
            pptPickerLauncher.launch("image/*")
        }

        binding.toolConvertPdfToImages.setOnClickListener {
            Toast.makeText(requireContext(), "Chọn file PDF để tách thành các ảnh", Toast.LENGTH_SHORT).show()
            pdfToImgPickerLauncher.launch(arrayOf("application/pdf"))
        }

        binding.toolConvertPdfToLongImage.setOnClickListener {
            Toast.makeText(requireContext(), "Chọn file PDF để ghép thành 1 ảnh dài", Toast.LENGTH_SHORT).show()
            pdfToLongImgPickerLauncher.launch(arrayOf("application/pdf"))
        }
    }

    private fun handleImportImages(uris: List<Uri>) {
        showLoading("Đang nhập và tạo tài liệu từ ${uris.size} ảnh...")
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
            PdfConverterHelper.createPdfFromImages(pagePaths, pdfFile)

            val docItem = DocumentItem(
                id = docId,
                title = "Ảnh đã nhập " + FileUtils.formatDate(System.currentTimeMillis()),
                pdfPath = pdfFile.absolutePath,
                thumbnailPath = pagePaths.firstOrNull(),
                pagePaths = pagePaths,
                pageCount = pagePaths.size,
                sizeBytes = pdfFile.length(),
                createdAt = System.currentTimeMillis()
            )

            repo.addDocument(docItem)
            hideLoading()
            Toast.makeText(requireContext(), "Đã nhập ${pagePaths.size} ảnh thành công!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleImportFile(uri: Uri) {
        val fileName = FileUtils.getFileName(requireContext(), uri)
        val docDir = FileUtils.getDocumentsDir(requireContext())
        val savedFile = FileUtils.copyUriToAppStorage(requireContext(), uri, docDir, "file")

        if (savedFile != null) {
            val docId = UUID.randomUUID().toString()
            val isPdf = savedFile.name.endsWith(".pdf", ignoreCase = true)

            val docItem = DocumentItem(
                id = docId,
                title = fileName,
                pdfPath = if (isPdf) savedFile.absolutePath else null,
                thumbnailPath = if (!isPdf) savedFile.absolutePath else null,
                pagePaths = if (!isPdf) listOf(savedFile.absolutePath) else emptyList(),
                pageCount = 1,
                sizeBytes = savedFile.length(),
                createdAt = System.currentTimeMillis()
            )
            repo.addDocument(docItem)
            Toast.makeText(requireContext(), getString(R.string.file_imported, fileName), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "Không thể nhập tập tin", Toast.LENGTH_SHORT).show()
        }
    }

    private fun convertToWordFromUri(uri: Uri) {
        val langCode = AppLanguageManager.getCurrentLanguageCode(requireContext())
        val langInfo = AppLanguageManager.getLanguage(langCode)
        val langLabel = if (langInfo?.isTesseractPrimary == true) "Tiếng Việt + Tiếng Anh" else (langInfo?.nativeName ?: "Đa ngôn ngữ")
        
        showLoading("Đang nhận diện văn bản ($langLabel)...")
        TextRecognitionHelper.recognizeTextFromUri(
            requireContext(),
            uri,
            onSuccess = { text ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val exportDir = FileUtils.getExportsDir(requireContext())
                    val timeStamp = SimpleDateFormat("dd-MM-yyyy_HH-mm", Locale.getDefault()).format(Date())
                    val file = File(exportDir, "Word_$timeStamp.doc")
                    val success = PdfConverterHelper.exportTextToWord(
                        text = text,
                        outputFile = file,
                        addWatermark = WatermarkHelper.shouldApplyWatermark(requireContext())
                    )
                    hideLoading()

                    if (success && file.exists()) {
                        promptSaveSingleFile(
                            file = file,
                            mimeType = "application/msword",
                            fileTypeTitle = "Word",
                            iconRes = R.drawable.ic_word,
                            extension = "doc"
                        )
                    } else {
                        Toast.makeText(requireContext(), "Lỗi khi tạo tập tin Word", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onError = {
                hideLoading()
                Toast.makeText(requireContext(), "Không thể nhận diện văn bản", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun convertToExcelFromUri(uri: Uri) {
        val langCode = AppLanguageManager.getCurrentLanguageCode(requireContext())
        val langInfo = AppLanguageManager.getLanguage(langCode)
        val langLabel = if (langInfo?.isTesseractPrimary == true) "Tiếng Việt + Tiếng Anh" else (langInfo?.nativeName ?: "Đa ngôn ngữ")
        
        showLoading("Đang nhận diện bảng tính ($langLabel)...")
        TextRecognitionHelper.recognizeTextFromUri(
            requireContext(),
            uri,
            onSuccess = { text ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val exportDir = FileUtils.getExportsDir(requireContext())
                    val timeStamp = SimpleDateFormat("dd-MM-yyyy_HH-mm", Locale.getDefault()).format(Date())
                    val file = File(exportDir, "Excel_$timeStamp.csv")
                    val success = PdfConverterHelper.exportTextToExcel(
                        text = text,
                        outputFile = file,
                        addWatermark = WatermarkHelper.shouldApplyWatermark(requireContext())
                    )
                    hideLoading()

                    if (success && file.exists()) {
                        promptSaveSingleFile(
                            file = file,
                            mimeType = "text/csv",
                            fileTypeTitle = "Excel",
                            iconRes = R.drawable.ic_excel,
                            extension = "csv"
                        )
                    } else {
                        Toast.makeText(requireContext(), "Lỗi khi tạo tập tin Excel", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onError = {
                hideLoading()
                Toast.makeText(requireContext(), "Không thể nhận diện văn bản", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun convertToPptFromUris(uris: List<Uri>) {
        showLoading("Đang tạo bài thuyết trình PPT từ ${uris.size} ảnh...")
        viewLifecycleOwner.lifecycleScope.launch {
            val imgDir = FileUtils.getImagesDir(requireContext())
            val imagePaths = mutableListOf<String>()
            uris.forEachIndexed { i, uri ->
                val f = File(imgDir, "ppt_slide_${System.currentTimeMillis()}_${i + 1}.jpg")
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(f).use { output ->
                        input.copyTo(output)
                    }
                }
                imagePaths.add(f.absolutePath)
            }

            val exportDir = FileUtils.getExportsDir(requireContext())
            val timeStamp = SimpleDateFormat("dd-MM-yyyy_HH-mm", Locale.getDefault()).format(Date())
            val file = File(exportDir, "Presentation_$timeStamp.html")
            val success = PdfConverterHelper.exportToPpt(
                imagePaths = imagePaths,
                outputFile = file,
                addWatermark = WatermarkHelper.shouldApplyWatermark(requireContext())
            )
            hideLoading()

            if (success && file.exists()) {
                promptSaveSingleFile(
                    file = file,
                    mimeType = "text/html",
                    fileTypeTitle = "PPT",
                    iconRes = R.drawable.ic_ppt,
                    extension = "html"
                )
            } else {
                Toast.makeText(requireContext(), "Không thể tạo bài thuyết trình", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun convertPdfToImagesFromUri(uri: Uri) {
        showLoading("Đang tách các trang PDF thành ảnh...")
        viewLifecycleOwner.lifecycleScope.launch {
            val docDir = FileUtils.getDocumentsDir(requireContext())
            val pdfFile = FileUtils.copyUriToAppStorage(requireContext(), uri, docDir, "temp_pdf")
            if (pdfFile == null) {
                hideLoading()
                Toast.makeText(requireContext(), "Không thể đọc tệp PDF", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val exportDir = FileUtils.getExportsDir(requireContext())
            val images = PdfConverterHelper.convertPdfToImages(requireContext(), pdfFile, exportDir)
            hideLoading()

            if (images.isNotEmpty()) {
                val fileList = images.map { File(it) }
                promptSaveMultiFiles(
                    files = fileList,
                    mimeType = "image/jpeg",
                    fileTypeTitle = "Ảnh từ PDF",
                    iconRes = R.drawable.ic_pdf_to_img
                )
            } else {
                Toast.makeText(requireContext(), "Không thể tách ảnh từ PDF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun convertPdfToLongImageFromUri(uri: Uri) {
        showLoading("Đang ghép các trang PDF thành một ảnh dài...")
        viewLifecycleOwner.lifecycleScope.launch {
            val docDir = FileUtils.getDocumentsDir(requireContext())
            val pdfFile = FileUtils.copyUriToAppStorage(requireContext(), uri, docDir, "temp_pdf")
            if (pdfFile == null) {
                hideLoading()
                Toast.makeText(requireContext(), "Không thể đọc tệp PDF", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val exportDir = FileUtils.getExportsDir(requireContext())
            val timeStamp = SimpleDateFormat("dd-MM-yyyy_HH-mm", Locale.getDefault()).format(Date())
            val longImgFile = File(exportDir, "LongImage_$timeStamp.jpg")

            val success = PdfConverterHelper.convertPdfToLongImage(
                context = requireContext(),
                pdfFile = pdfFile,
                outputFile = longImgFile,
                addWatermark = WatermarkHelper.shouldApplyWatermark(requireContext())
            )
            hideLoading()

            if (success && longImgFile.exists()) {
                promptSaveSingleFile(
                    file = longImgFile,
                    mimeType = "image/jpeg",
                    fileTypeTitle = "Ảnh dài",
                    iconRes = R.drawable.ic_pdf_to_long_img,
                    extension = "jpg"
                )
            } else {
                Toast.makeText(requireContext(), "Không thể tạo ảnh dài từ PDF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun promptSaveSingleFile(
        file: File,
        mimeType: String,
        fileTypeTitle: String,
        iconRes: Int,
        extension: String
    ) {
        val defaultName = file.name
        SaveExportDialog(
            context = requireContext(),
            title = "Chuyển đổi $fileTypeTitle hoàn tất",
            description = "Tập tin đã sẵn sàng. Vui lòng chọn nơi lưu trữ:",
            iconRes = iconRes,
            defaultName = defaultName,
            extension = extension
        ) { finalFileName, action ->
            when (action) {
                SaveExportDialog.SaveAction.CHOOSE_FOLDER -> {
                    pendingSingleFile = file
                    pendingMimeType = mimeType
                    pendingFileTypeTitle = fileTypeTitle
                    createDocLauncher.launch(finalFileName)
                }
                SaveExportDialog.SaveAction.SAVE_TO_DOWNLOADS -> {
                    val uri = FileUtils.saveFileToDownloads(requireContext(), file, mimeType, customName = finalFileName)
                    if (uri != null) {
                        showSaveSuccessDialog(
                            locationDesc = getString(R.string.saved_to_downloads_desc),
                            file = file,
                            mimeType = mimeType,
                            title = "Đã lưu $fileTypeTitle thành công"
                        )
                    } else {
                        Toast.makeText(requireContext(), "Không thể lưu vào Tải về", Toast.LENGTH_SHORT).show()
                    }
                }
                SaveExportDialog.SaveAction.SHARE -> {
                    shareFile(file, mimeType)
                }
            }
        }.show()
    }

    private fun promptSaveMultiFiles(
        files: List<File>,
        mimeType: String,
        fileTypeTitle: String,
        iconRes: Int
    ) {
        SaveExportDialog(
            context = requireContext(),
            title = "Tách ảnh từ PDF hoàn tất",
            description = "Đã trích xuất ${files.size} hình ảnh từ PDF. Vui lòng chọn nơi lưu trữ:",
            iconRes = iconRes,
            filesInfo = "Đã tạo ${files.size} hình ảnh (.jpg)"
        ) { _, action ->
            when (action) {
                SaveExportDialog.SaveAction.CHOOSE_FOLDER -> {
                    pendingFilesList = files
                    selectFolderLauncher.launch(null)
                }
                SaveExportDialog.SaveAction.SAVE_TO_DOWNLOADS -> {
                    val savedUris = FileUtils.saveFilesToDownloads(requireContext(), files, mimeType)
                    if (savedUris.isNotEmpty()) {
                        showMultiFilesSuccessDialog(
                            folderName = getString(R.string.saved_to_downloads_desc),
                            files = files,
                            mimeType = mimeType
                        )
                    } else {
                        Toast.makeText(requireContext(), "Không thể lưu vào Tải về", Toast.LENGTH_SHORT).show()
                    }
                }
                SaveExportDialog.SaveAction.SHARE -> {
                    shareFiles(files, mimeType)
                }
            }
        }.show()
    }

    private fun showSaveSuccessDialog(
        locationDesc: String,
        file: File,
        mimeType: String,
        title: String = "Lưu tập tin thành công"
    ) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage("Tập tin đã được lưu trữ thành công:\n📁 Vị trí: $locationDesc\n📄 Tập tin: ${file.name}\n\nBạn có muốn mở xem ngay hoặc chia sẻ không?")
            .setPositiveButton(getString(R.string.open_file)) { _, _ ->
                openFile(file, mimeType)
            }
            .setNeutralButton(getString(R.string.share)) { _, _ ->
                shareFile(file, mimeType)
            }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    private fun showMultiFilesSuccessDialog(
        folderName: String,
        files: List<File>,
        mimeType: String = "image/jpeg"
    ) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.save_file_success))
            .setMessage("Đã lưu ${files.size} hình ảnh thành công vào thư mục:\n📁 $folderName\n\nBạn có muốn xem ảnh đầu tiên hoặc chia sẻ không?")
            .setPositiveButton(getString(R.string.open_file)) { _, _ ->
                files.firstOrNull()?.let { openFile(it, mimeType) }
            }
            .setNeutralButton(getString(R.string.share)) { _, _ ->
                shareFiles(files, mimeType)
            }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    private fun openFile(file: File, mimeType: String) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.provider",
                file
            )
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(viewIntent, "Mở tập tin bằng..."))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Không tìm thấy ứng dụng phù hợp để mở file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(file: File, mimeType: String) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.provider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Chia sẻ kết quả"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Không thể chia sẻ tập tin", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFiles(files: List<File>, mimeType: String) {
        try {
            val uris = ArrayList<Uri>()
            for (file in files) {
                uris.add(
                    FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.provider",
                        file
                    )
                )
            }
            val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Không thể chia sẻ tập tin", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLoading(message: String) {
        hideLoading()
        val ctx = context ?: return
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(60, 48, 60, 48)
            gravity = Gravity.CENTER_VERTICAL
            val bar = ProgressBar(ctx)
            addView(bar)
            val tv = TextView(ctx).apply {
                text = message
                textSize = 15f
                setTextColor(resources.getColor(R.color.text_primary, null))
                setPadding(40, 0, 0, 0)
            }
            addView(tv)
        }
        progressDialog = MaterialAlertDialogBuilder(ctx)
            .setView(layout)
            .setCancelable(false)
            .create().apply {
                show()
            }
    }

    private fun hideLoading() {
        try {
            progressDialog?.dismiss()
        } catch (_: Exception) {}
        progressDialog = null
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
                // Hủy quét camera
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
        hideLoading()
        super.onDestroyView()
        _binding = null
    }
}
