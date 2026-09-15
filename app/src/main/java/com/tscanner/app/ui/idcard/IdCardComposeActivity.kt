package com.tscanner.app.ui.idcard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tscanner.app.R
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.databinding.ActivityIdCardComposeBinding
import com.tscanner.app.ui.dialogs.VipUpgradeDialog
import com.tscanner.app.ui.editor.CropRotateActivity
import com.tscanner.app.ui.viewer.PdfViewerActivity
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.FileUtils
import com.tscanner.app.utils.IdCardComposeConfig
import com.tscanner.app.utils.IdCardComposerHelper
import com.tscanner.app.utils.IdCardLayoutMode
import com.tscanner.app.utils.IdCardScaleMode
import com.tscanner.app.utils.PdfConverterHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class IdCardComposeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIdCardComposeBinding

    private var frontImagePath: String? = null
    private var backImagePath: String? = null

    private var frontBitmap: Bitmap? = null
    private var backBitmap: Bitmap? = null

    private var currentConfig = IdCardComposeConfig()
    private var currentPreviewBitmap: Bitmap? = null

    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pageIndex = result.data?.getIntExtra(CropRotateActivity.EXTRA_PAGE_INDEX, -1) ?: -1
            lifecycleScope.launch {
                if (pageIndex == 0 && frontImagePath != null) {
                    frontBitmap?.recycle()
                    frontBitmap = withContext(Dispatchers.IO) {
                        IdCardComposerHelper.decodeSampledBitmap(frontImagePath!!)
                    }
                } else if (pageIndex == 1 && backImagePath != null) {
                    backBitmap?.recycle()
                    backBitmap = withContext(Dispatchers.IO) {
                        IdCardComposerHelper.decodeSampledBitmap(backImagePath!!)
                    }
                }
                updatePreview()
            }
        }
    }

    private val pickFrontImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            handleFrontImagePicked(uri)
        }
    }

    private val pickBackImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            handleBackImagePicked(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        binding = ActivityIdCardComposeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val baseBottomPadding = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.layoutComposeToolbar.setPadding(
                binding.layoutComposeToolbar.paddingLeft,
                statusBarInsets.top,
                binding.layoutComposeToolbar.paddingRight,
                binding.layoutComposeToolbar.paddingBottom
            )

            binding.layoutBottomControls.setPadding(
                binding.layoutBottomControls.paddingLeft,
                binding.layoutBottomControls.paddingTop,
                binding.layoutBottomControls.paddingRight,
                baseBottomPadding + navInsets.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        // Read paths from intent
        frontImagePath = intent.getStringExtra(EXTRA_FRONT_PATH)
        backImagePath = intent.getStringExtra(EXTRA_BACK_PATH)

        val pagePaths = intent.getStringArrayListExtra(EXTRA_PAGE_PATHS)
        if (!pagePaths.isNullOrEmpty()) {
            if (frontImagePath == null) frontImagePath = pagePaths.getOrNull(0)
            if (backImagePath == null && pagePaths.size > 1) backImagePath = pagePaths.getOrNull(1)
        }

        if (frontImagePath == null && backImagePath != null) {
            frontImagePath = backImagePath
            backImagePath = null
        }

        if (backImagePath == null) {
            currentConfig = currentConfig.copy(layoutMode = IdCardLayoutMode.A4_PORTRAIT_SINGLE_SIDE)
        }

        initWatermarkConfig()
        updateLayoutTabs()
        updateScaleTabs()
        updateBorderButton(currentConfig.showCutBorder)
        setupListeners()
        loadImages()
    }

    private fun handleFrontImagePicked(uri: Uri) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                FileUtils.copyUriToAppStorage(this@IdCardComposeActivity, uri, FileUtils.getImagesDir(this@IdCardComposeActivity), "idcard_front")
            }
            if (file != null) {
                frontImagePath = file.absolutePath
                frontBitmap?.recycle()
                frontBitmap = withContext(Dispatchers.IO) {
                    IdCardComposerHelper.decodeSampledBitmap(file.absolutePath)
                }
                updatePreview()
            } else {
                Toast.makeText(this@IdCardComposeActivity, "Không thể tải ảnh", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleBackImagePicked(uri: Uri) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                FileUtils.copyUriToAppStorage(this@IdCardComposeActivity, uri, FileUtils.getImagesDir(this@IdCardComposeActivity), "idcard_back")
            }
            if (file != null) {
                backImagePath = file.absolutePath
                backBitmap?.recycle()
                backBitmap = withContext(Dispatchers.IO) {
                    IdCardComposerHelper.decodeSampledBitmap(file.absolutePath)
                }
                if (currentConfig.layoutMode == IdCardLayoutMode.A4_PORTRAIT_SINGLE_SIDE) {
                    currentConfig = currentConfig.copy(layoutMode = IdCardLayoutMode.A4_PORTRAIT_TWO_SIDES)
                    updateLayoutTabs()
                }
                updatePreview()
            } else {
                Toast.makeText(this@IdCardComposeActivity, "Không thể tải ảnh", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initWatermarkConfig() {
        val isVip = AppAuthManager.isUserVip()
        currentConfig = currentConfig.copy(addWatermark = !isVip)
        updateVipWatermarkUI(isVip)
    }

    private fun updateVipWatermarkUI(isVip: Boolean) {
        if (isVip) {
            if (!currentConfig.addWatermark) {
                binding.ivVipWatermarkIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary_teal))
                binding.tvVipWatermarkLabel.text = getString(R.string.watermark_btn_removed_vip)
                binding.tvVipWatermarkLabel.setTextColor(ContextCompat.getColor(this, R.color.primary_teal))
            } else {
                binding.ivVipWatermarkIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.vip_gold))
                binding.tvVipWatermarkLabel.text = "Bật dấu (VIP)"
                binding.tvVipWatermarkLabel.setTextColor(ContextCompat.getColor(this, R.color.vip_gold))
            }
        } else {
            binding.ivVipWatermarkIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.vip_gold))
            binding.tvVipWatermarkLabel.text = getString(R.string.watermark_btn_remove_vip)
            binding.tvVipWatermarkLabel.setTextColor(ContextCompat.getColor(this, R.color.vip_gold))
        }
    }

    private fun loadImages() {
        binding.pbComposeLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                frontImagePath?.let { path ->
                    frontBitmap = IdCardComposerHelper.decodeSampledBitmap(path)
                }
                backImagePath?.let { path ->
                    backBitmap = IdCardComposerHelper.decodeSampledBitmap(path)
                }
            }
            binding.pbComposeLoading.visibility = View.GONE
            updatePreview()
        }
    }

    private fun updatePreview() {
        binding.pbComposeLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                IdCardComposerHelper.renderA4Bitmap(
                    frontBmp = frontBitmap,
                    backBmp = backBitmap,
                    config = currentConfig,
                    isHighRes = false
                )
            }
            val oldBmp = currentPreviewBitmap
            currentPreviewBitmap = bmp
            binding.ivA4Preview.setImageBitmap(bmp)
            oldBmp?.recycle()
            binding.pbComposeLoading.visibility = View.GONE
        }
    }

    private fun setupListeners() {
        binding.btnBackCompose.setOnClickListener {
            finish()
        }

        // Layout Mode
        binding.tabLayoutPortrait.setOnClickListener {
            currentConfig = currentConfig.copy(layoutMode = IdCardLayoutMode.A4_PORTRAIT_TWO_SIDES)
            updateLayoutTabs()
            updatePreview()
        }

        binding.tabLayoutLandscape.setOnClickListener {
            currentConfig = currentConfig.copy(layoutMode = IdCardLayoutMode.A4_LANDSCAPE_TWO_SIDES)
            updateLayoutTabs()
            updatePreview()
        }

        binding.tabLayoutSingle.setOnClickListener {
            currentConfig = currentConfig.copy(layoutMode = IdCardLayoutMode.A4_PORTRAIT_SINGLE_SIDE)
            updateLayoutTabs()
            updatePreview()
        }

        // Scale Mode
        binding.tabScaleActual.setOnClickListener {
            currentConfig = currentConfig.copy(scaleMode = IdCardScaleMode.ACTUAL_SIZE)
            updateScaleTabs()
            updatePreview()
        }

        binding.tabScaleFit.setOnClickListener {
            currentConfig = currentConfig.copy(scaleMode = IdCardScaleMode.FIT_PAGE)
            updateScaleTabs()
            updatePreview()
        }

        // Border Toggle
        binding.btnToggleBorder.setOnClickListener {
            val newBorder = !currentConfig.showCutBorder
            currentConfig = currentConfig.copy(showCutBorder = newBorder)
            updateBorderButton(newBorder)
            updatePreview()
        }

        // Swap Sides
        binding.btnSwapSides.setOnClickListener {
            if (frontBitmap == null || backBitmap == null) {
                Toast.makeText(this, "Cần có đủ 2 mặt thẻ để đổi vị trí", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Swap paths
            val tempPath = frontImagePath
            frontImagePath = backImagePath
            backImagePath = tempPath

            // Swap bitmaps
            val tempBmp = frontBitmap
            frontBitmap = backBitmap
            backBitmap = tempBmp

            Toast.makeText(this, "Đã đổi vị trí Mặt trước và Mặt sau", Toast.LENGTH_SHORT).show()
            updatePreview()
        }

        // Crop Front
        binding.btnCropFront.setOnClickListener {
            val path = frontImagePath
            if (path != null && File(path).exists()) {
                val options = arrayOf("✂️ Cắt góc / Xoay mặt trước", "🖼️ Chọn ảnh khác từ thư viện")
                MaterialAlertDialogBuilder(this)
                    .setTitle("Mặt trước thẻ")
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> {
                                val intent = CropRotateActivity.createIntent(this, path, 0)
                                cropLauncher.launch(intent)
                            }
                            1 -> pickFrontImageLauncher.launch("image/*")
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            } else {
                pickFrontImageLauncher.launch("image/*")
            }
        }

        // Crop Back
        binding.btnCropBack.setOnClickListener {
            val path = backImagePath
            if (path != null && File(path).exists()) {
                val options = arrayOf("✂️ Cắt góc / Xoay mặt sau", "🖼️ Chọn ảnh khác từ thư viện")
                MaterialAlertDialogBuilder(this)
                    .setTitle("Mặt sau thẻ")
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> {
                                val intent = CropRotateActivity.createIntent(this, path, 1)
                                cropLauncher.launch(intent)
                            }
                            1 -> pickBackImageLauncher.launch("image/*")
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            } else {
                pickBackImageLauncher.launch("image/*")
            }
        }

        // Print
        binding.btnPrintA4.setOnClickListener {
            val preview = currentPreviewBitmap
            if (preview != null && (frontBitmap != null || backBitmap != null)) {
                lifecycleScope.launch {
                    val highResBmp = withContext(Dispatchers.IO) {
                        IdCardComposerHelper.renderA4Bitmap(frontBitmap, backBitmap, currentConfig, isHighRes = true)
                    }
                    IdCardComposerHelper.printDocument(this@IdCardComposeActivity, highResBmp, "In_CCCD_A4")
                }
            } else {
                Toast.makeText(this, "Vui lòng chọn ít nhất một mặt thẻ để in", Toast.LENGTH_SHORT).show()
            }
        }

        // VIP Watermark button
        binding.btnVipWatermark.setOnClickListener {
            val isVip = AppAuthManager.isUserVip()
            if (isVip) {
                // VIP can toggle or keep off
                currentConfig = currentConfig.copy(addWatermark = !currentConfig.addWatermark)
                updateVipWatermarkUI(true)
                updatePreview()
                Toast.makeText(
                    this,
                    if (!currentConfig.addWatermark) getString(R.string.watermark_toggled_off) else getString(R.string.watermark_toggled_on),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                // Free user clicks -> Show VIP upgrade dialog
                VipUpgradeDialog(
                    context = this,
                    onUpgradeSuccess = {
                        val newVip = AppAuthManager.isUserVip()
                        if (newVip) {
                            currentConfig = currentConfig.copy(addWatermark = false)
                            updateVipWatermarkUI(true)
                            updatePreview()
                            Toast.makeText(this, "🎉 Đã mở khóa VIP! Đã gỡ bỏ toàn bộ đóng dấu.", Toast.LENGTH_LONG).show()
                        }
                    }
                ).show()
            }
        }

        // Save & Export
        binding.btnSaveCompose.setOnClickListener {
            showSaveOptionsDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        val isVip = AppAuthManager.isUserVip()
        if (isVip && currentConfig.addWatermark) {
            currentConfig = currentConfig.copy(addWatermark = false)
            updateVipWatermarkUI(true)
            updatePreview()
        }
    }

    private fun updateLayoutTabs() {
        val selectedBg = R.drawable.bg_chip_selected
        val unselectedBg = R.drawable.bg_chip_unselected
        val tealColor = ContextCompat.getColor(this, R.color.primary_teal)
        val mutedColor = ContextCompat.getColor(this, R.color.text_secondary)

        when (currentConfig.layoutMode) {
            IdCardLayoutMode.A4_PORTRAIT_TWO_SIDES -> {
                binding.tabLayoutPortrait.setBackgroundResource(selectedBg)
                binding.tabLayoutPortrait.setTextColor(tealColor)
                binding.tabLayoutLandscape.setBackgroundResource(unselectedBg)
                binding.tabLayoutLandscape.setTextColor(mutedColor)
                binding.tabLayoutSingle.setBackgroundResource(unselectedBg)
                binding.tabLayoutSingle.setTextColor(mutedColor)
            }
            IdCardLayoutMode.A4_LANDSCAPE_TWO_SIDES -> {
                binding.tabLayoutPortrait.setBackgroundResource(unselectedBg)
                binding.tabLayoutPortrait.setTextColor(mutedColor)
                binding.tabLayoutLandscape.setBackgroundResource(selectedBg)
                binding.tabLayoutLandscape.setTextColor(tealColor)
                binding.tabLayoutSingle.setBackgroundResource(unselectedBg)
                binding.tabLayoutSingle.setTextColor(mutedColor)
            }
            IdCardLayoutMode.A4_PORTRAIT_SINGLE_SIDE -> {
                binding.tabLayoutPortrait.setBackgroundResource(unselectedBg)
                binding.tabLayoutPortrait.setTextColor(mutedColor)
                binding.tabLayoutLandscape.setBackgroundResource(unselectedBg)
                binding.tabLayoutLandscape.setTextColor(mutedColor)
                binding.tabLayoutSingle.setBackgroundResource(selectedBg)
                binding.tabLayoutSingle.setTextColor(tealColor)
            }
        }
    }

    private fun updateScaleTabs() {
        val selectedBg = R.drawable.bg_chip_selected
        val unselectedBg = R.drawable.bg_chip_unselected
        val tealColor = ContextCompat.getColor(this, R.color.primary_teal)
        val mutedColor = ContextCompat.getColor(this, R.color.text_secondary)

        when (currentConfig.scaleMode) {
            IdCardScaleMode.ACTUAL_SIZE -> {
                binding.tabScaleActual.setBackgroundResource(selectedBg)
                binding.tabScaleActual.setTextColor(tealColor)
                binding.tabScaleFit.setBackgroundResource(unselectedBg)
                binding.tabScaleFit.setTextColor(mutedColor)
            }
            IdCardScaleMode.FIT_PAGE -> {
                binding.tabScaleActual.setBackgroundResource(unselectedBg)
                binding.tabScaleActual.setTextColor(mutedColor)
                binding.tabScaleFit.setBackgroundResource(selectedBg)
                binding.tabScaleFit.setTextColor(tealColor)
            }
        }
    }

    private fun updateBorderButton(hasBorder: Boolean) {
        if (hasBorder) {
            binding.btnToggleBorder.setBackgroundResource(R.drawable.bg_chip_selected)
            binding.btnToggleBorder.setTextColor(ContextCompat.getColor(this, R.color.primary_teal))
        } else {
            binding.btnToggleBorder.setBackgroundResource(R.drawable.bg_chip_unselected)
            binding.btnToggleBorder.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun showSaveOptionsDialog() {
        if (frontBitmap == null && backBitmap == null) {
            Toast.makeText(this, "Vui lòng chọn ít nhất một mặt thẻ để tiếp tục", Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf(
            "📄 Lưu file PDF (Trang A4 chuẩn)",
            "🖼️ Lưu ảnh A4 HD vào Bộ sưu tập",
            "↗️ Chia sẻ file PDF (Zalo, Email...)"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("Lưu tài liệu thẻ")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> saveAndOpenPdf()
                    1 -> saveHighResImageToDownloads()
                    2 -> sharePdfDirectly()
                }
            }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    private fun saveAndOpenPdf() {
        if (frontBitmap == null && backBitmap == null) {
            Toast.makeText(this, "Vui lòng chọn ít nhất một mặt thẻ để tiếp tục", Toast.LENGTH_SHORT).show()
            return
        }
        binding.pbComposeLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val docDir = FileUtils.getDocumentsDir(this@IdCardComposeActivity)
            val newDocId = UUID.randomUUID().toString()
            val timeStamp = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(Date())
            val pdfFile = File(docDir, "doc_${newDocId}.pdf")

            val success = IdCardComposerHelper.createA4Pdf(
                frontBmp = frontBitmap,
                backBmp = backBitmap,
                config = currentConfig,
                outputFile = pdfFile
            )

            binding.pbComposeLoading.visibility = View.GONE

            if (success && pdfFile.exists()) {
                val thumbsDir = FileUtils.getThumbnailsDir(this@IdCardComposeActivity)
                val thumbFile = File(thumbsDir, "thumb_${newDocId}.jpg")
                val thumbPath = if (PdfConverterHelper.renderPdfFirstPage(pdfFile, thumbFile)) {
                    thumbFile.absolutePath
                } else null

                val docItem = DocumentItem(
                    id = newDocId,
                    title = "Thẻ ID $timeStamp",
                    pdfPath = pdfFile.absolutePath,
                    thumbnailPath = thumbPath,
                    pagePaths = emptyList(), // A4 composed page is in the PDF
                    pageCount = 1,
                    sizeBytes = pdfFile.length(),
                    createdAt = System.currentTimeMillis(),
                    ownerId = AppAuthManager.getCurrentUser()?.id
                )
                DocumentRepo.getInstance(this@IdCardComposeActivity).addDocument(docItem)

                Toast.makeText(this@IdCardComposeActivity, getString(R.string.id_card_saved_success), Toast.LENGTH_SHORT).show()
                PdfViewerActivity.start(this@IdCardComposeActivity, pdfFile.absolutePath, docItem.title, emptyList())
                finish()
            } else {
                Toast.makeText(this@IdCardComposeActivity, "Không thể tạo file PDF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveHighResImageToDownloads() {
        if (frontBitmap == null && backBitmap == null) {
            Toast.makeText(this, "Vui lòng chọn ít nhất một mặt thẻ để tiếp tục", Toast.LENGTH_SHORT).show()
            return
        }
        binding.pbComposeLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val exportDir = FileUtils.getExportsDir(this@IdCardComposeActivity)
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            val tempImgFile = File(exportDir, "The_ID_A4_$timeStamp.jpg")

            val success = IdCardComposerHelper.saveA4Image(
                frontBmp = frontBitmap,
                backBmp = backBitmap,
                config = currentConfig,
                outputFile = tempImgFile
            )

            binding.pbComposeLoading.visibility = View.GONE

            if (success && tempImgFile.exists()) {
                val savedUri = FileUtils.saveFileToDownloads(
                    this@IdCardComposeActivity,
                    tempImgFile,
                    "image/jpeg",
                    customName = tempImgFile.name
                )
                if (savedUri != null) {
                    Toast.makeText(this@IdCardComposeActivity, "Đã lưu ảnh A4 HD vào mục Tải về!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@IdCardComposeActivity, "Đã tạo ảnh tại ${tempImgFile.name}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this@IdCardComposeActivity, "Lỗi khi lưu hình ảnh", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sharePdfDirectly() {
        if (frontBitmap == null && backBitmap == null) {
            Toast.makeText(this, "Vui lòng chọn ít nhất một mặt thẻ để tiếp tục", Toast.LENGTH_SHORT).show()
            return
        }
        binding.pbComposeLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val exportDir = FileUtils.getExportsDir(this@IdCardComposeActivity)
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            val pdfFile = File(exportDir, "The_ID_$timeStamp.pdf")

            val success = IdCardComposerHelper.createA4Pdf(
                frontBmp = frontBitmap,
                backBmp = backBitmap,
                config = currentConfig,
                outputFile = pdfFile
            )

            binding.pbComposeLoading.visibility = View.GONE

            if (success && pdfFile.exists()) {
                val uri = FileProvider.getUriForFile(
                    this@IdCardComposeActivity,
                    "$packageName.provider",
                    pdfFile
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Chia sẻ thẻ CCCD"))
            } else {
                Toast.makeText(this@IdCardComposeActivity, "Không thể tạo file PDF để chia sẻ", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        frontBitmap?.recycle()
        frontBitmap = null
        backBitmap?.recycle()
        backBitmap = null
        currentPreviewBitmap?.recycle()
        currentPreviewBitmap = null
    }

    companion object {
        const val EXTRA_FRONT_PATH = "extra_front_path"
        const val EXTRA_BACK_PATH = "extra_back_path"
        const val EXTRA_PAGE_PATHS = "extra_page_paths"

        fun start(context: Context, pagePaths: List<String>) {
            val intent = Intent(context, IdCardComposeActivity::class.java).apply {
                putStringArrayListExtra(EXTRA_PAGE_PATHS, ArrayList(pagePaths))
                if (pagePaths.isNotEmpty()) putExtra(EXTRA_FRONT_PATH, pagePaths[0])
                if (pagePaths.size > 1) putExtra(EXTRA_BACK_PATH, pagePaths[1])
            }
            context.startActivity(intent)
        }

        fun startWithSides(context: Context, frontPath: String?, backPath: String?) {
            val intent = Intent(context, IdCardComposeActivity::class.java).apply {
                putExtra(EXTRA_FRONT_PATH, frontPath)
                putExtra(EXTRA_BACK_PATH, backPath)
            }
            context.startActivity(intent)
        }
    }
}
