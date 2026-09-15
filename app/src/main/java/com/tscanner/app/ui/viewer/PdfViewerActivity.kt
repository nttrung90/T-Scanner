package com.tscanner.app.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.tscanner.app.R
import com.tscanner.app.databinding.ActivityPdfViewerBinding
import com.tscanner.app.ui.adapter.PdfPageAdapter
import com.tscanner.app.ui.editor.CropRotateActivity
import com.tscanner.app.ui.ocr.OcrResultActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.ui.dialogs.CreatePdfDialog
import com.tscanner.app.ui.dialogs.VipUpgradeDialog
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.FileUtils
import com.tscanner.app.utils.PdfConverterHelper
import com.tscanner.app.utils.TextRecognitionHelper
import com.tscanner.app.utils.WatermarkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class PdfViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfViewerBinding
    private var pdfPath: String? = null
    private var renderedPagePaths = mutableListOf<String>()
    private var pageAdapter: PdfPageAdapter? = null
    private var isNewScan = false
    private var sessionId: String? = null
    private var isWatermarkRemoved = AppAuthManager.isUserVip()

    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pageIndex = result.data?.getIntExtra(CropRotateActivity.EXTRA_PAGE_INDEX, -1) ?: -1
            if (pageIndex in renderedPagePaths.indices) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        Glide.get(this@PdfViewerActivity).clearDiskCache()
                    }
                    Glide.get(this@PdfViewerActivity).clearMemory()

                    pageAdapter?.notifyItemChanged(pageIndex)

                    // Asynchronously update the PDF file with the newly cropped page
                    val currentPdf = pdfPath?.let { File(it) }
                    if (currentPdf != null && currentPdf.exists()) {
                        withContext(Dispatchers.IO) {
                            PdfConverterHelper.createPdfFromImages(
                                imagePaths = renderedPagePaths,
                                outputFile = currentPdf,
                                addWatermark = !isWatermarkRemoved
                            )
                            // Regenerate thumbnail in .thumbnails directory
                            val thumbFile = File(FileUtils.getThumbnailsDir(this@PdfViewerActivity), "thumb_${currentPdf.nameWithoutExtension}.jpg")
                            PdfConverterHelper.renderPdfFirstPage(currentPdf, thumbFile)
                        }
                    }
                    Toast.makeText(this@PdfViewerActivity, "Đã cập nhật trang ${pageIndex + 1}!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val baseBottomPadding = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.layoutViewerToolbar.setPadding(
                binding.layoutViewerToolbar.paddingLeft,
                statusBarInsets.top,
                binding.layoutViewerToolbar.paddingRight,
                binding.layoutViewerToolbar.paddingBottom
            )

            binding.layoutViewerBottomActions.setPadding(
                binding.layoutViewerBottomActions.paddingLeft,
                binding.layoutViewerBottomActions.paddingTop,
                binding.layoutViewerBottomActions.paddingRight,
                baseBottomPadding + navInsets.bottom
            )

            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        isNewScan = intent.getBooleanExtra(EXTRA_IS_NEW_SCAN, false)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        pdfPath = intent.getStringExtra(EXTRA_PDF_PATH)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Tài liệu PDF"
        binding.tvViewerTitle.text = title

        if (isNewScan) {
            binding.btnSaveViewerDoc.visibility = View.VISIBLE
            binding.btnShareViewer.visibility = View.GONE
        } else {
            binding.btnSaveViewerDoc.visibility = View.GONE
            binding.btnShareViewer.visibility = View.VISIBLE
        }

        binding.btnBackViewer.setOnClickListener {
            handleBackAction()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackAction()
            }
        })

        binding.btnSaveViewerDoc.setOnClickListener {
            saveFinalDocument()
        }

        binding.btnShareViewer.setOnClickListener {
            sharePdf()
        }

        binding.btnOcrFromPdf.setOnClickListener {
            performOcrOnPages()
        }

        binding.btnConvertLongImgFromPdf.setOnClickListener {
            convertToLongImage()
        }

        binding.btnCreatePdfFromViewer.setOnClickListener {
            if (isNewScan) {
                saveFinalDocument()
            } else {
                createNewPdf()
            }
        }

        binding.btnVipWatermarkViewer.setOnClickListener {
            val isVip = AppAuthManager.isUserVip()
            if (isVip) {
                isWatermarkRemoved = !isWatermarkRemoved
                updateWatermarkUI()
                Toast.makeText(
                    this,
                    if (isWatermarkRemoved) getString(R.string.watermark_toggled_off) else getString(R.string.watermark_toggled_on),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                VipUpgradeDialog(
                    context = this,
                    onUpgradeSuccess = {
                        if (AppAuthManager.isUserVip()) {
                            isWatermarkRemoved = true
                            updateWatermarkUI()
                            Toast.makeText(this, "🎉 Đã mở khóa VIP! Tự động gỡ đóng dấu.", Toast.LENGTH_LONG).show()
                        }
                    }
                ).show()
            }
        }
        updateWatermarkUI()

        setupRecyclerView()

        val initialPages = intent.getStringArrayListExtra(EXTRA_PAGE_PATHS)
        if (!initialPages.isNullOrEmpty()) {
            renderedPagePaths.clear()
            renderedPagePaths.addAll(initialPages)
            pageAdapter = PdfPageAdapter(renderedPagePaths) { position, pagePath ->
                val intent = CropRotateActivity.createIntent(this@PdfViewerActivity, pagePath, position)
                cropLauncher.launch(intent)
            }
            binding.rvPdfPages.adapter = pageAdapter
        } else {
            loadPdfPages()
        }
    }

    override fun onResume() {
        super.onResume()
        if (AppAuthManager.isUserVip() && !isWatermarkRemoved) {
            isWatermarkRemoved = true
        }
        updateWatermarkUI()
    }

    private fun updateWatermarkUI() {
        val isVip = AppAuthManager.isUserVip()
        if (isVip) {
            if (isWatermarkRemoved) {
                binding.ivWatermarkCrown.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary_teal))
                binding.tvWatermarkViewerLabel.text = getString(R.string.watermark_btn_removed_vip)
                binding.tvWatermarkViewerLabel.setTextColor(ContextCompat.getColor(this, R.color.primary_teal))
            } else {
                binding.ivWatermarkCrown.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.vip_gold))
                binding.tvWatermarkViewerLabel.text = "Bật dấu (VIP)"
                binding.tvWatermarkViewerLabel.setTextColor(ContextCompat.getColor(this, R.color.vip_gold))
            }
        } else {
            binding.ivWatermarkCrown.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.vip_gold))
            binding.tvWatermarkViewerLabel.text = getString(R.string.watermark_btn_remove_vip)
            binding.tvWatermarkViewerLabel.setTextColor(ContextCompat.getColor(this, R.color.vip_gold))
        }
    }

    private fun setupRecyclerView() {
        binding.rvPdfPages.layoutManager = LinearLayoutManager(this)
    }

    private fun loadPdfPages() {
        val path = pdfPath ?: return
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "Không tìm thấy file PDF", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.pbLoadingViewer.visibility = View.VISIBLE

        lifecycleScope.launch {
            val previewDir = FileUtils.getPdfPreviewDir(this@PdfViewerActivity)
            val pages = withContext(Dispatchers.IO) {
                PdfConverterHelper.convertPdfToImages(this@PdfViewerActivity, file, previewDir)
            }
            renderedPagePaths.clear()
            renderedPagePaths.addAll(pages)

            binding.pbLoadingViewer.visibility = View.GONE
            pageAdapter = PdfPageAdapter(renderedPagePaths) { position, pagePath ->
                val intent = CropRotateActivity.createIntent(this@PdfViewerActivity, pagePath, position)
                cropLauncher.launch(intent)
            }
            binding.rvPdfPages.adapter = pageAdapter
        }
    }

    private fun sharePdf() {
        val path = pdfPath ?: return
        val file = File(path)
        if (file.exists()) {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
        }
    }

    private fun performOcrOnPages() {
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Đang trích xuất văn bản (OCR)")
            .setMessage("Đang chuẩn bị nhận diện...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val fullTextBuilder = StringBuilder()

            try {
                // If pages haven't finished rendering yet, render them now on the fly
                if (renderedPagePaths.isEmpty()) {
                    val path = pdfPath
                    if (path != null && File(path).exists()) {
                        progressDialog.setMessage("Đang kết xuất trang từ tài liệu PDF...")
                        val previewDir = FileUtils.getPdfPreviewDir(this@PdfViewerActivity)
                        val pages = withContext(Dispatchers.IO) {
                            PdfConverterHelper.convertPdfToImages(this@PdfViewerActivity, File(path), previewDir)
                        }
                        if (pages.isNotEmpty()) {
                            renderedPagePaths.clear()
                            renderedPagePaths.addAll(pages)
                            pageAdapter?.notifyDataSetChanged()
                        }
                    }
                }

                if (renderedPagePaths.isEmpty()) {
                    Toast.makeText(this@PdfViewerActivity, "Không tìm thấy trang tài liệu để trích xuất", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val totalPages = renderedPagePaths.size
                for (index in 0 until totalPages) {
                    val pagePath = renderedPagePaths[index]
                    val engineName = TextRecognitionHelper.getPreferredEngineDisplayName(this@PdfViewerActivity)
                    progressDialog.setMessage("Đang nhận diện trang ${index + 1}/$totalPages ($engineName)...")

                    val pageText = withContext(Dispatchers.IO) {
                        try {
                            TextRecognitionHelper.recognizeTextFromFileSync(this@PdfViewerActivity, pagePath)
                        } catch (t: Throwable) {
                            Log.e("PdfViewerActivity", "Error recognizing page ${index + 1}: ${t.message}", t)
                            ""
                        }
                    }

                    if (pageText.isNotBlank()) {
                        if (totalPages > 1) {
                            fullTextBuilder.append("--- TRANG ${index + 1} ---\n\n")
                        }
                        fullTextBuilder.append(pageText.trim())
                        fullTextBuilder.append("\n\n")
                    }
                }

                val extractedResult = fullTextBuilder.toString().trim()
                if (extractedResult.isNotEmpty()) {
                    OcrResultActivity.start(
                        this@PdfViewerActivity,
                        extractedResult,
                        TextRecognitionHelper.lastEngineUsed
                    )
                } else {
                    Toast.makeText(
                        this@PdfViewerActivity,
                        getString(R.string.no_text_found),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                if (progressDialog.isShowing && !isFinishing && !isDestroyed) {
                    progressDialog.dismiss()
                }
            }
        }
    }

    private fun convertToLongImage() {
        val path = pdfPath ?: return
        val file = File(path)
        if (!file.exists()) return

        Toast.makeText(this, "Đang ghép các trang thành ảnh dài...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val exportDir = FileUtils.getExportsDir(this@PdfViewerActivity)
            val longImgFile = File(exportDir, "LongImage_${System.currentTimeMillis()}.jpg")
            val success = withContext(Dispatchers.IO) {
                PdfConverterHelper.convertPdfToLongImage(
                    context = this@PdfViewerActivity,
                    pdfFile = file,
                    outputFile = longImgFile,
                    addWatermark = !isWatermarkRemoved
                )
            }

            if (success) {
                Toast.makeText(this@PdfViewerActivity, "Ghép ảnh dài thành công!", Toast.LENGTH_LONG).show()
                val uri = FileProvider.getUriForFile(
                    this@PdfViewerActivity,
                    "$packageName.provider",
                    longImgFile
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Chia sẻ ảnh dài"))
            } else {
                Toast.makeText(this@PdfViewerActivity, "Không thể tạo ảnh dài", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createNewPdf() {
        if (renderedPagePaths.isEmpty() && (pdfPath == null || !File(pdfPath!!).exists())) {
            Toast.makeText(this, "Chưa tải xong trang tài liệu để tạo PDF", Toast.LENGTH_SHORT).show()
            return
        }

        val defaultTitle = binding.tvViewerTitle.text.toString().trim().ifEmpty {
            "PDF_" + FileUtils.formatDate(System.currentTimeMillis()).replace('/', '-').replace(':', '-')
        }

        CreatePdfDialog(
            context = this,
            defaultName = defaultTitle,
            isWatermarkRemoved = isWatermarkRemoved,
            onWatermarkToggled = { removed ->
                isWatermarkRemoved = removed
                updateWatermarkUI()
            }
        ) { fileName ->
            val sanitized = FileUtils.sanitizeFileName(fileName.removeSuffix(".pdf"))
            val cleanName = "$sanitized.pdf"
            val exportDir = FileUtils.getExportsDir(this)
            exportDir.mkdirs()
            val outputFile = File(exportDir, cleanName)

            Toast.makeText(this, "Đang tạo file PDF...", Toast.LENGTH_SHORT).show()

            lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) {
                    if (renderedPagePaths.isNotEmpty()) {
                        PdfConverterHelper.createPdfFromImages(
                            imagePaths = renderedPagePaths,
                            outputFile = outputFile,
                            addWatermark = !isWatermarkRemoved
                        )
                    } else if (pdfPath != null && File(pdfPath!!).exists() && File(pdfPath!!).length() > 0) {
                        val sourceFile = File(pdfPath!!)
                        try {
                            outputFile.parentFile?.mkdirs()
                            sourceFile.copyTo(outputFile, overwrite = true)
                            true
                        } catch (e: Exception) {
                            e.printStackTrace()
                            false
                        }
                    } else {
                        false
                    }
                }

                if (success && outputFile.exists()) {
                    // Lưu một bản vào thư mục Tải về (Downloads) của thiết bị để người dùng dễ tìm
                    FileUtils.savePdfToDownloads(this@PdfViewerActivity, outputFile)

                    val toastMsg = if (AppAuthManager.isUserVip()) {
                        "Đã tạo file PDF: ${outputFile.name} (Đang tự động sao lưu lên Google Drive)"
                    } else {
                        "Đã tạo file PDF: ${outputFile.name}"
                    }
                    Toast.makeText(this@PdfViewerActivity, toastMsg, Toast.LENGTH_LONG).show()

                    // Lưu vào DocumentRepo để quản lý trong danh sách tài liệu
                    val newDocId = UUID.randomUUID().toString()
                    val thumb = renderedPagePaths.firstOrNull() ?: run {
                        val thumbFile = File(FileUtils.getThumbnailsDir(this@PdfViewerActivity), "thumb_${newDocId}.jpg")
                        if (PdfConverterHelper.renderPdfFirstPage(outputFile, thumbFile)) thumbFile.absolutePath else null
                    }
                    val docItem = DocumentItem(
                        id = newDocId,
                        title = outputFile.nameWithoutExtension,
                        pdfPath = outputFile.absolutePath,
                        thumbnailPath = thumb,
                        pagePaths = renderedPagePaths.toList(),
                        pageCount = if (renderedPagePaths.isNotEmpty()) renderedPagePaths.size else 1,
                        sizeBytes = outputFile.length(),
                        createdAt = System.currentTimeMillis()
                    )
                    DocumentRepo.getInstance(this@PdfViewerActivity).addDocument(docItem)

                    // Hiển thị dialog chia sẻ / mở file
                    showPdfSuccessDialog(outputFile)
                } else {
                    Toast.makeText(this@PdfViewerActivity, "Không thể tạo file PDF", Toast.LENGTH_SHORT).show()
                }
            }
        }.show()
    }

    private fun handleBackAction() {
        if (isNewScan) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Hủy tài liệu vừa quét?")
                .setMessage("Tài liệu chưa được lưu vào máy. Bạn có chắc chắn muốn hủy và xóa các trang đã quét không?")
                .setPositiveButton("Hủy tài liệu") { _, _ ->
                    sessionId?.let { FileUtils.deleteTempSession(this, it) }
                    finish()
                }
                .setNegativeButton("Tiếp tục xem", null)
                .show()
        } else {
            finish()
        }
    }

    private fun saveFinalDocument() {
        if (renderedPagePaths.isEmpty() && (pdfPath == null || !File(pdfPath!!).exists())) {
            Toast.makeText(this, "Chưa tải xong trang tài liệu để lưu", Toast.LENGTH_SHORT).show()
            return
        }

        val defaultTitle = binding.tvViewerTitle.text.toString().trim().ifEmpty {
            "Tài liệu " + FileUtils.formatDate(System.currentTimeMillis()).replace('/', '-').replace(':', '-')
        }

        CreatePdfDialog(
            context = this,
            defaultName = defaultTitle,
            isWatermarkRemoved = isWatermarkRemoved,
            onWatermarkToggled = { removed ->
                isWatermarkRemoved = removed
                updateWatermarkUI()
            }
        ) { fileName ->
            val sanitized = FileUtils.sanitizeFileName(fileName.removeSuffix(".pdf"))
            val cleanName = "$sanitized.pdf"
            val docDir = FileUtils.getDocumentsDir(this)
            val finalPdfFile = File(docDir, cleanName)

            Toast.makeText(this, "Đang lưu tài liệu vào máy...", Toast.LENGTH_SHORT).show()

            lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) {
                    if (renderedPagePaths.isNotEmpty()) {
                        PdfConverterHelper.createPdfFromImages(
                            imagePaths = renderedPagePaths,
                            outputFile = finalPdfFile,
                            addWatermark = !isWatermarkRemoved
                        )
                    } else if (pdfPath != null && File(pdfPath!!).exists()) {
                        try {
                            File(pdfPath!!).copyTo(finalPdfFile, overwrite = true)
                            true
                        } catch (e: Exception) {
                            e.printStackTrace()
                            false
                        }
                    } else {
                        false
                    }
                }

                if (success && finalPdfFile.exists()) {
                    // Create thumbnail in .thumbnails folder
                    val newDocId = UUID.randomUUID().toString()
                    val thumbsDir = FileUtils.getThumbnailsDir(this@PdfViewerActivity)
                    val thumbFile = File(thumbsDir, "thumb_${newDocId}.jpg")
                    val thumbPath = withContext(Dispatchers.IO) {
                        if (PdfConverterHelper.renderPdfFirstPage(finalPdfFile, thumbFile)) {
                            thumbFile.absolutePath
                        } else null
                    }

                    // Save to DocumentRepo (Only the final PDF file is saved!)
                    val docItem = DocumentItem(
                        id = newDocId,
                        title = finalPdfFile.nameWithoutExtension,
                        pdfPath = finalPdfFile.absolutePath,
                        thumbnailPath = thumbPath,
                        pagePaths = emptyList(), // Page images were temporary in cache, not kept
                        pageCount = if (renderedPagePaths.isNotEmpty()) renderedPagePaths.size else 1,
                        sizeBytes = finalPdfFile.length(),
                        createdAt = System.currentTimeMillis()
                    )
                    DocumentRepo.getInstance(this@PdfViewerActivity).addDocument(docItem)

                    // Clean up temporary scan session!
                    sessionId?.let { sid ->
                        withContext(Dispatchers.IO) {
                            FileUtils.deleteTempSession(this@PdfViewerActivity, sid)
                        }
                    }

                    // Update UI state to normal viewing mode
                    isNewScan = false
                    pdfPath = finalPdfFile.absolutePath
                    binding.tvViewerTitle.text = finalPdfFile.nameWithoutExtension
                    binding.btnSaveViewerDoc.visibility = View.GONE
                    binding.btnShareViewer.visibility = View.VISIBLE

                    val saveMsg = if (AppAuthManager.isUserVip()) {
                        "Đã lưu vào Quản lý tài liệu! (Đang tự động sao lưu lên Google Drive cá nhân)"
                    } else {
                        "Đã lưu vào Quản lý tài liệu!"
                    }
                    Toast.makeText(this@PdfViewerActivity, saveMsg, Toast.LENGTH_SHORT).show()
                    showPdfSuccessDialog(finalPdfFile)
                } else {
                    Toast.makeText(this@PdfViewerActivity, "Lỗi khi lưu tài liệu", Toast.LENGTH_SHORT).show()
                }
            }
        }.show()
    }

    private fun showPdfSuccessDialog(pdfFile: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", pdfFile)
        MaterialAlertDialogBuilder(this)
            .setTitle("Tạo file PDF thành công")
            .setMessage("Tập tin đã được lưu:\n${pdfFile.name}\n\nBạn có muốn chia sẻ hoặc mở tập tin không?")
            .setPositiveButton("Chia sẻ") { _, _ ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Chia sẻ file PDF"))
            }
            .setNeutralButton("Mở file") { _, _ ->
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(viewIntent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Không tìm thấy ứng dụng đọc PDF", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isNewScan && sessionId != null) {
            FileUtils.deleteTempSession(this, sessionId!!)
        }
        val previewDir = FileUtils.getPdfPreviewDir(this)
        FileUtils.deleteDirContents(previewDir)
    }

    companion object {
        const val EXTRA_PDF_PATH = "extra_pdf_path"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_PAGE_PATHS = "extra_page_paths"
        const val EXTRA_IS_NEW_SCAN = "extra_is_new_scan"
        const val EXTRA_SESSION_ID = "extra_session_id"

        fun start(context: Context, pdfPath: String, title: String, pagePaths: List<String>? = null) {
            val intent = Intent(context, PdfViewerActivity::class.java).apply {
                putExtra(EXTRA_PDF_PATH, pdfPath)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_IS_NEW_SCAN, false)
                if (!pagePaths.isNullOrEmpty()) {
                    putStringArrayListExtra(EXTRA_PAGE_PATHS, ArrayList(pagePaths))
                }
            }
            context.startActivity(intent)
        }

        fun startForNewScan(context: Context, sessionId: String, pdfPath: String?, pagePaths: List<String>) {
            val defaultTitle = "Tài liệu " + FileUtils.formatDate(System.currentTimeMillis()).replace('/', '-').replace(':', '-')
            val intent = Intent(context, PdfViewerActivity::class.java).apply {
                putExtra(EXTRA_IS_NEW_SCAN, true)
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_TITLE, defaultTitle)
                if (pdfPath != null) {
                    putExtra(EXTRA_PDF_PATH, pdfPath)
                }
                putStringArrayListExtra(EXTRA_PAGE_PATHS, ArrayList(pagePaths))
            }
            context.startActivity(intent)
        }
    }
}
