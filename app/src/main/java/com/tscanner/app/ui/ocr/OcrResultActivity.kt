package com.tscanner.app.ui.ocr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tscanner.app.R
import com.tscanner.app.databinding.ActivityOcrResultBinding
import com.tscanner.app.ui.dialogs.ExportDocDialog
import com.tscanner.app.utils.FileUtils
import com.tscanner.app.utils.PdfConverterHelper
import com.tscanner.app.utils.TextRecognitionHelper
import com.tscanner.app.utils.WatermarkHelper
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OcrResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOcrResultBinding
    private var extractedText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        binding = ActivityOcrResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val baseBottomPadding = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.layoutOcrToolbar.setPadding(
                binding.layoutOcrToolbar.paddingLeft,
                statusBarInsets.top,
                binding.layoutOcrToolbar.paddingRight,
                binding.layoutOcrToolbar.paddingBottom
            )

            binding.layoutOcrBottomActions.setPadding(
                binding.layoutOcrBottomActions.paddingLeft,
                binding.layoutOcrBottomActions.paddingTop,
                binding.layoutOcrBottomActions.paddingRight,
                baseBottomPadding + navInsets.bottom
            )

            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

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
        if (extractedText.isEmpty()) {
            extractedText = getString(R.string.no_text_found)
        }
        binding.tvOcrContent.text = extractedText

        val engine = intent.getStringExtra(EXTRA_ENGINE) ?: TextRecognitionHelper.lastEngineUsed
        binding.tvOcrEngine.text = "Động cơ: $engine"

        binding.btnBackOcr.setOnClickListener {
            finish()
        }

        binding.btnCopyOcr.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("T-Scanner OCR", extractedText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
        }

        binding.btnShareOcr.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, extractedText)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
        }

        binding.btnExportWordFromOcr.setOnClickListener {
            showExportWordDialog()
        }

        binding.btnExportExcelFromOcr.setOnClickListener {
            showExportExcelDialog()
        }
    }

    private fun showExportWordDialog() {
        val defaultName = "VanBan_" + SimpleDateFormat("dd-MM-yyyy_HH-mm", Locale.getDefault()).format(Date())
        ExportDocDialog(
            this,
            title = "Xuất tập tin Word",
            description = "Chọn lưu trữ vào máy hoặc chia sẻ tập tin Word (.doc)",
            defaultName = defaultName,
            extension = "doc"
        ) { fileName, action ->
            performExportWord(fileName, action)
        }.show()
    }

    private fun performExportWord(fileName: String, action: ExportDocDialog.ExportAction) {
        lifecycleScope.launch {
            val exportDir = FileUtils.getExportsDir(this@OcrResultActivity)
            val file = File(exportDir, fileName)
            val success = PdfConverterHelper.exportTextToWord(
                text = extractedText,
                outputFile = file,
                addWatermark = WatermarkHelper.shouldApplyWatermark(this@OcrResultActivity)
            )
            if (success) {
                // Luôn lưu trữ một bản vào thư mục Downloads của thiết bị
                FileUtils.saveFileToDownloads(this@OcrResultActivity, file, "application/msword")

                when (action) {
                    ExportDocDialog.ExportAction.SAVE_TO_DEVICE -> {
                        Toast.makeText(this@OcrResultActivity, "Đã lưu vào thư mục Tải về (Downloads): ${file.name}", Toast.LENGTH_LONG).show()
                        showExportSuccessDialog(file, "application/msword", "Word")
                    }
                    ExportDocDialog.ExportAction.SHARE -> {
                        Toast.makeText(this@OcrResultActivity, "Đã lưu vào Downloads và đang mở chia sẻ...", Toast.LENGTH_SHORT).show()
                        shareExportedFile(file, "application/msword")
                    }
                }
            } else {
                Toast.makeText(this@OcrResultActivity, "Lỗi khi xuất Word", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showExportExcelDialog() {
        val defaultName = "BangTinh_" + SimpleDateFormat("dd-MM-yyyy_HH-mm", Locale.getDefault()).format(Date())
        ExportDocDialog(
            this,
            title = "Xuất tập tin Excel",
            description = "Chọn lưu trữ vào máy hoặc chia sẻ bảng tính Excel (.csv)",
            defaultName = defaultName,
            extension = "csv"
        ) { fileName, action ->
            performExportExcel(fileName, action)
        }.show()
    }

    private fun performExportExcel(fileName: String, action: ExportDocDialog.ExportAction) {
        lifecycleScope.launch {
            val exportDir = FileUtils.getExportsDir(this@OcrResultActivity)
            val file = File(exportDir, fileName)
            val success = PdfConverterHelper.exportTextToExcel(
                text = extractedText,
                outputFile = file,
                addWatermark = WatermarkHelper.shouldApplyWatermark(this@OcrResultActivity)
            )
            if (success) {
                // Luôn lưu trữ một bản vào thư mục Downloads của thiết bị
                FileUtils.saveFileToDownloads(this@OcrResultActivity, file, "text/csv")

                when (action) {
                    ExportDocDialog.ExportAction.SAVE_TO_DEVICE -> {
                        Toast.makeText(this@OcrResultActivity, "Đã lưu vào thư mục Tải về (Downloads): ${file.name}", Toast.LENGTH_LONG).show()
                        showExportSuccessDialog(file, "text/csv", "Excel")
                    }
                    ExportDocDialog.ExportAction.SHARE -> {
                        Toast.makeText(this@OcrResultActivity, "Đã lưu vào Downloads và đang mở chia sẻ...", Toast.LENGTH_SHORT).show()
                        shareExportedFile(file, "text/csv")
                    }
                }
            } else {
                Toast.makeText(this@OcrResultActivity, "Lỗi khi xuất Excel", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showExportSuccessDialog(file: File, mimeType: String, docType: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Xuất $docType thành công")
            .setMessage("Tập tin đã được lưu trữ vào thư mục Tải về (Downloads):\n${file.name}\n\nBạn có muốn mở xem ngay hoặc chia sẻ tập tin không?")
            .setPositiveButton("Mở file") { _, _ ->
                openExportedFile(file, mimeType)
            }
            .setNeutralButton("Chia sẻ") { _, _ ->
                shareExportedFile(file, mimeType)
            }
            .setNegativeButton("Đóng", null)
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
            startActivity(Intent.createChooser(viewIntent, "Mở tập tin bằng..."))
        } catch (e: Exception) {
            Toast.makeText(this, "Không tìm thấy ứng dụng phù hợp để mở file", Toast.LENGTH_SHORT).show()
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
            startActivity(Intent.createChooser(shareIntent, "Chia sẻ tập tin"))
        } catch (e: Exception) {
            Toast.makeText(this, "Không thể chia sẻ tập tin", Toast.LENGTH_SHORT).show()
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
        const val EXTRA_TEXT = "extra_ocr_text"
        const val EXTRA_TEXT_FILE = "extra_ocr_text_file"
        const val EXTRA_ENGINE = "extra_ocr_engine"

        fun start(context: Context, text: String, engine: String = com.tscanner.app.utils.TextRecognitionHelper.lastEngineUsed) {
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
                putExtra(EXTRA_ENGINE, engine)
            }
            context.startActivity(intent)
        }
    }
}
