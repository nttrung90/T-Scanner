package com.tscanner.app.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import com.tscanner.app.R
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.databinding.DialogDocManagementBinding
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.FileUtils

class DocumentManagementDialog(context: Context) : Dialog(context) {

    private lateinit var binding: DialogDocManagementBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogDocManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        applyDialogWidth()

        updateStats()

        binding.btnClearCache.setOnClickListener {
            val cleared = FileUtils.clearCache(context)
            Toast.makeText(
                context,
                context.getString(R.string.cleared_cache_format, FileUtils.formatFileSize(cleared)),
                Toast.LENGTH_SHORT
            ).show()
            updateStats()
        }

        binding.btnCloseManagement.setOnClickListener {
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        applyDialogWidth()
    }

    private fun applyDialogWidth() {
        val displayMetrics = context.resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.88).toInt().coerceAtMost(
            (460 * displayMetrics.density).toInt()
        )
        window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun updateStats() {
        val currentUserId = AppAuthManager.getCurrentUser()?.id
        val stats = DocumentRepo.getInstance(context).getStorageStats(currentUserId)
        binding.tvStatTotalDocs.text = context.getString(R.string.total_documents, stats.totalDocuments)
        binding.tvStatTotalPdfs.text = context.getString(R.string.total_pdfs, stats.totalPdfs)
        binding.tvStatStorageUsed.text = context.getString(R.string.storage_used, FileUtils.formatFileSize(stats.totalSizeBytes))
    }
}
