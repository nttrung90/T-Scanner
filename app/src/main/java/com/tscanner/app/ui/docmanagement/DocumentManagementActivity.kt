package com.tscanner.app.ui.docmanagement

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tscanner.app.R
import com.tscanner.app.data.model.ManagedFileItem
import com.tscanner.app.data.model.ManagedFileType
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.databinding.ActivityDocumentManagementBinding
import com.tscanner.app.ui.adapter.ManagedFileAdapter
import com.tscanner.app.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DocumentManagementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentManagementBinding
    private lateinit var adapter: ManagedFileAdapter
    private lateinit var repo: DocumentRepo

    private var allFiles: List<ManagedFileItem> = emptyList()
    private var currentFilterType: ManagedFileType = ManagedFileType.ALL
    private var searchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        binding = ActivityDocumentManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val baseBottomPadding = (24 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.layoutDocMgmtToolbar.setPadding(
                binding.layoutDocMgmtToolbar.paddingLeft,
                statusBarInsets.top,
                binding.layoutDocMgmtToolbar.paddingRight,
                binding.layoutDocMgmtToolbar.paddingBottom
            )

            binding.layoutDocMgmtContent.setPadding(
                binding.layoutDocMgmtContent.paddingLeft,
                binding.layoutDocMgmtContent.paddingTop,
                binding.layoutDocMgmtContent.paddingRight,
                baseBottomPadding + navInsets.bottom
            )

            insets
        }

        repo = DocumentRepo.getInstance(this)

        setupToolbar()
        setupRecyclerView()
        setupFilterChips()
        setupSearch()
        loadData()
    }

    private fun setupToolbar() {
        binding.btnBackDocMgmt.setOnClickListener {
            finish()
        }

        binding.btnClearCacheTop.setOnClickListener {
            val cleared = FileUtils.clearCache(this)
            Toast.makeText(
                this,
                "Đã dọn dẹp ${FileUtils.formatFileSize(cleared)} bộ nhớ đệm!",
                Toast.LENGTH_SHORT
            ).show()
            loadData()
        }
    }

    private fun setupRecyclerView() {
        adapter = ManagedFileAdapter(
            onOpenFile = { openFile(it) },
            onCopyPath = { copyPath(it) },
            onShareFile = { shareFile(it) },
            onDeleteFile = { confirmDelete(it) }
        )
        binding.rvManagedFiles.layoutManager = LinearLayoutManager(this)
        binding.rvManagedFiles.adapter = adapter
    }

    private fun setupFilterChips() {
        val chips = listOf(
            binding.chipAll to ManagedFileType.ALL,
            binding.chipPdf to ManagedFileType.PDF,
            binding.chipWord to ManagedFileType.WORD,
            binding.chipExcel to ManagedFileType.EXCEL,
            binding.chipPpt to ManagedFileType.PPT,
            binding.chipImage to ManagedFileType.IMAGE
        )

        for ((chipView, type) in chips) {
            chipView.setOnClickListener {
                currentFilterType = type
                updateChipsVisualState()
                applyFilterAndSearch()
            }
        }
    }

    private fun updateChipsVisualState() {
        val chips = listOf(
            binding.chipAll to ManagedFileType.ALL,
            binding.chipPdf to ManagedFileType.PDF,
            binding.chipWord to ManagedFileType.WORD,
            binding.chipExcel to ManagedFileType.EXCEL,
            binding.chipPpt to ManagedFileType.PPT,
            binding.chipImage to ManagedFileType.IMAGE
        )

        for ((chipView, type) in chips) {
            if (type == currentFilterType) {
                chipView.setBackgroundResource(R.drawable.btn_solid_teal)
                chipView.setTextColor(resources.getColor(R.color.bg_dark, null))
            } else {
                chipView.setBackgroundResource(R.drawable.btn_outline_teal)
                chipView.setTextColor(resources.getColor(R.color.text_secondary, null))
            }
        }
    }

    private fun setupSearch() {
        binding.etSearchManagedFiles.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                searchQuery = s?.toString()?.trim() ?: ""
                applyFilterAndSearch()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun loadData() {
        binding.pbLoadingMgmt.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val files = repo.getAllManagedFiles()
            val stats = repo.calculateManagementStats(files)

            withContext(Dispatchers.Main) {
                binding.pbLoadingMgmt.visibility = View.GONE
                allFiles = files

                // Overview card
                binding.tvStatTotalStorage.text = FileUtils.formatFileSize(stats.totalSizeBytes)
                binding.tvStatTotalCount.text = "${stats.totalFiles} tệp"

                // Breakdown text
                val pdfStat = stats.typeStats.firstOrNull { it.type == ManagedFileType.PDF }
                val wordStat = stats.typeStats.firstOrNull { it.type == ManagedFileType.WORD }
                val excelStat = stats.typeStats.firstOrNull { it.type == ManagedFileType.EXCEL }
                val pptStat = stats.typeStats.firstOrNull { it.type == ManagedFileType.PPT }
                val imageStat = stats.typeStats.firstOrNull { it.type == ManagedFileType.IMAGE }
                val otherStat = stats.typeStats.firstOrNull { it.type == ManagedFileType.OTHER }

                binding.tvStatPdfDetail.text = "PDF: ${pdfStat?.count ?: 0} tệp (${FileUtils.formatFileSize(pdfStat?.totalSizeBytes ?: 0L)})"
                binding.tvStatWordDetail.text = "Word: ${wordStat?.count ?: 0} tệp (${FileUtils.formatFileSize(wordStat?.totalSizeBytes ?: 0L)})"
                binding.tvStatExcelDetail.text = "Excel: ${excelStat?.count ?: 0} tệp (${FileUtils.formatFileSize(excelStat?.totalSizeBytes ?: 0L)})"
                binding.tvStatPptDetail.text = "PPT: ${pptStat?.count ?: 0} tệp (${FileUtils.formatFileSize(pptStat?.totalSizeBytes ?: 0L)})"
                binding.tvStatImageDetail.text = "Ảnh: ${imageStat?.count ?: 0} tệp (${FileUtils.formatFileSize(imageStat?.totalSizeBytes ?: 0L)})"
                binding.tvStatOtherDetail.text = "Khác: ${otherStat?.count ?: 0} tệp (${FileUtils.formatFileSize(otherStat?.totalSizeBytes ?: 0L)})"

                // Update chip labels with count
                binding.chipAll.text = "Tất cả (${stats.totalFiles})"
                binding.chipPdf.text = "PDF (${pdfStat?.count ?: 0})"
                binding.chipWord.text = "Word (${wordStat?.count ?: 0})"
                binding.chipExcel.text = "Excel (${excelStat?.count ?: 0})"
                binding.chipPpt.text = "PPT (${pptStat?.count ?: 0})"
                binding.chipImage.text = "Hình ảnh (${imageStat?.count ?: 0})"

                applyFilterAndSearch()
            }
        }
    }

    private fun applyFilterAndSearch() {
        val filtered = allFiles.filter { item ->
            val matchesType = when (currentFilterType) {
                ManagedFileType.ALL -> true
                else -> item.fileType == currentFilterType
            }

            val matchesSearch = if (searchQuery.isBlank()) {
                true
            } else {
                item.name.contains(searchQuery, ignoreCase = true) ||
                        item.path.contains(searchQuery, ignoreCase = true)
            }

            matchesType && matchesSearch
        }

        adapter.submitList(filtered)
        binding.tvFilteredCount.text = "${filtered.size} tệp"

        if (filtered.isEmpty()) {
            binding.layoutEmptyMgmt.visibility = View.VISIBLE
            binding.rvManagedFiles.visibility = View.GONE
        } else {
            binding.layoutEmptyMgmt.visibility = View.GONE
            binding.rvManagedFiles.visibility = View.VISIBLE
        }
    }

    private fun openFile(item: ManagedFileItem) {
        try {
            val mimeType = when (item.fileType) {
                ManagedFileType.PDF -> "application/pdf"
                ManagedFileType.WORD -> "application/msword"
                ManagedFileType.EXCEL -> "text/csv"
                ManagedFileType.PPT -> "text/html"
                ManagedFileType.IMAGE -> "image/jpeg"
                else -> "*/*"
            }

            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.provider",
                item.file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Mở tập tin bằng..."))
        } catch (e: Exception) {
            Toast.makeText(this, "Không tìm thấy ứng dụng phù hợp để mở file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyPath(item: ManagedFileItem) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("File Path", item.path)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Đã sao chép đường dẫn:\n${item.name}", Toast.LENGTH_SHORT).show()
    }

    private fun shareFile(item: ManagedFileItem) {
        try {
            val mimeType = when (item.fileType) {
                ManagedFileType.PDF -> "application/pdf"
                ManagedFileType.WORD -> "application/msword"
                ManagedFileType.EXCEL -> "text/csv"
                ManagedFileType.PPT -> "text/html"
                ManagedFileType.IMAGE -> "image/jpeg"
                else -> "*/*"
            }

            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.provider",
                item.file
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

    private fun confirmDelete(item: ManagedFileItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Xác nhận xóa tệp")
            .setMessage("Bạn có chắc chắn muốn xóa tệp này vĩnh viễn không?\n\n📄 ${item.name}\n📍 ${item.path}")
            .setPositiveButton("Xóa") { _, _ ->
                val deleted = repo.deleteManagedFile(item.file)
                if (deleted) {
                    Toast.makeText(this, "Đã xóa ${item.name}", Toast.LENGTH_SHORT).show()
                    loadData()
                } else {
                    Toast.makeText(this, "Không thể xóa tệp", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, DocumentManagementActivity::class.java)
            context.startActivity(intent)
        }
    }
}
