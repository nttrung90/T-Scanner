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
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.EdgeToEdgeInsetsHelper
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_FILTER_TYPE, currentFilterType.name)
        outState.putString(KEY_SEARCH_QUERY, searchQuery)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        binding = ActivityDocumentManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState != null) {
            val filterName = savedInstanceState.getString(KEY_FILTER_TYPE)
            if (filterName != null) {
                currentFilterType = runCatching { ManagedFileType.valueOf(filterName) }.getOrDefault(ManagedFileType.ALL)
            }
            searchQuery = savedInstanceState.getString(KEY_SEARCH_QUERY, "")
        }

        val initialToolbarPadding = EdgeToEdgeInsetsHelper.recordInitialPadding(binding.layoutDocMgmtToolbar)
        val initialContentPadding = EdgeToEdgeInsetsHelper.recordInitialPadding(binding.layoutDocMgmtContent)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysInsets = EdgeToEdgeInsetsHelper.getSystemBarAndCutoutInsets(insets)
            val effectiveBottom = EdgeToEdgeInsetsHelper.getEffectiveBottomInset(insets, includeIme = true)

            EdgeToEdgeInsetsHelper.applyTopBarInsets(
                binding.layoutDocMgmtToolbar,
                initialToolbarPadding,
                sysInsets
            )

            EdgeToEdgeInsetsHelper.applyBottomBarInsets(
                binding.layoutDocMgmtContent,
                initialContentPadding,
                sysInsets,
                effectiveBottom
            )

            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        repo = DocumentRepo.getInstance(this)

        setupToolbar()
        setupRecyclerView()
        setupFilterChips()
        updateChipsVisualState()
        setupSearch()
        if (searchQuery.isNotEmpty() && binding.etSearchManagedFiles.text.toString() != searchQuery) {
            binding.etSearchManagedFiles.setText(searchQuery)
        }
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
                getString(R.string.cleared_cache_format, FileUtils.formatFileSize(cleared)),
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
        val currentUserId = AppAuthManager.getCurrentUser()?.id
        lifecycleScope.launch(Dispatchers.IO) {
            val files = repo.getAllManagedFiles(currentUserId)
            val stats = repo.calculateManagementStats(files)

            withContext(Dispatchers.Main) {
                binding.pbLoadingMgmt.visibility = View.GONE
                allFiles = files

                // Overview card
                binding.tvStatTotalStorage.text = FileUtils.formatFileSize(stats.totalSizeBytes)
                binding.tvStatTotalCount.text = resources.getQuantityString(R.plurals.files_count_plurals, stats.totalFiles, stats.totalFiles)

                // Breakdown text
                val pdfStat = stats.typeStats.firstOrNull { it.type == ManagedFileType.PDF }
                val wordStat = stats.typeStats.firstOrNull { it.type == ManagedFileType.WORD }
                val excelStat = stats.typeStats.firstOrNull { it.type == ManagedFileType.EXCEL }
                val pptStat = stats.typeStats.firstOrNull { it.type == ManagedFileType.PPT }
                val imageStat = stats.typeStats.firstOrNull { it.type == ManagedFileType.IMAGE }
                val otherStat = stats.typeStats.firstOrNull { it.type == ManagedFileType.OTHER }

                binding.tvStatPdfDetail.text = getString(R.string.stat_type_detail_format, "PDF", pdfStat?.count ?: 0, FileUtils.formatFileSize(pdfStat?.totalSizeBytes ?: 0L))
                binding.tvStatWordDetail.text = getString(R.string.stat_type_detail_format, "Word", wordStat?.count ?: 0, FileUtils.formatFileSize(wordStat?.totalSizeBytes ?: 0L))
                binding.tvStatExcelDetail.text = getString(R.string.stat_type_detail_format, "Excel", excelStat?.count ?: 0, FileUtils.formatFileSize(excelStat?.totalSizeBytes ?: 0L))
                binding.tvStatPptDetail.text = getString(R.string.stat_type_detail_format, "PPT", pptStat?.count ?: 0, FileUtils.formatFileSize(pptStat?.totalSizeBytes ?: 0L))
                binding.tvStatImageDetail.text = getString(R.string.stat_type_detail_format, getString(R.string.filter_image), imageStat?.count ?: 0, FileUtils.formatFileSize(imageStat?.totalSizeBytes ?: 0L))
                binding.tvStatOtherDetail.text = getString(R.string.stat_type_detail_format, getString(R.string.filter_other), otherStat?.count ?: 0, FileUtils.formatFileSize(otherStat?.totalSizeBytes ?: 0L))

                // Update chip labels with count
                binding.chipAll.text = "${getString(R.string.filter_all)} (${stats.totalFiles})"
                binding.chipPdf.text = "PDF (${pdfStat?.count ?: 0})"
                binding.chipWord.text = "Word (${wordStat?.count ?: 0})"
                binding.chipExcel.text = "Excel (${excelStat?.count ?: 0})"
                binding.chipPpt.text = "PPT (${pptStat?.count ?: 0})"
                binding.chipImage.text = "${getString(R.string.filter_image)} (${imageStat?.count ?: 0})"

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
        binding.tvFilteredCount.text = resources.getQuantityString(R.plurals.files_count_plurals, filtered.size, filtered.size)

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
            startActivity(Intent.createChooser(intent, getString(R.string.open_file_with)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.no_app_to_open_file), Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyPath(item: ManagedFileItem) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("File Path", item.path)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.copied_path_format, item.name), Toast.LENGTH_SHORT).show()
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
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_file_title)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.cannot_share_file), Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(item: ManagedFileItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete_title)
            .setMessage(getString(R.string.confirm_delete_message, item.name, item.path))
            .setPositiveButton(R.string.delete) { _, _ ->
                val deleted = repo.deleteManagedFile(item.file)
                if (deleted) {
                    Toast.makeText(this, getString(R.string.deleted_file_format, item.name), Toast.LENGTH_SHORT).show()
                    loadData()
                } else {
                    Toast.makeText(this, getString(R.string.cannot_delete_file), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val KEY_FILTER_TYPE = "key_filter_type"
        private const val KEY_SEARCH_QUERY = "key_search_query"

        fun start(context: Context) {
            val intent = Intent(context, DocumentManagementActivity::class.java)
            context.startActivity(intent)
        }
    }
}
