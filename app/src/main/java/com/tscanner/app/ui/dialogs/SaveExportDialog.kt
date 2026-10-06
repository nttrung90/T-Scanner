package com.tscanner.app.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.annotation.DrawableRes
import com.tscanner.app.R
import com.tscanner.app.databinding.DialogSaveExportBinding
import com.tscanner.app.utils.FileUtils

class SaveExportDialog(
    context: Context,
    private val title: String,
    private val description: String,
    @param:DrawableRes private val iconRes: Int = R.drawable.ic_folder,
    private val defaultName: String? = null,
    private val extension: String? = null,
    private val filesInfo: String? = null,
    private val onAction: (fileName: String, action: SaveAction) -> Unit
) : Dialog(context) {

    enum class SaveAction {
        CHOOSE_FOLDER,
        SAVE_TO_DOWNLOADS,
        SHARE
    }

    private lateinit var binding: DialogSaveExportBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogSaveExportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        applyDialogWidth()

        binding.ivDialogIcon.setImageResource(iconRes)
        binding.tvSaveTitle.text = title
        binding.tvSaveDesc.text = description

        val isMultiFiles = filesInfo != null
        if (isMultiFiles) {
            binding.layoutFileName.visibility = View.GONE
            binding.tvFilesInfo.visibility = View.VISIBLE
            binding.tvFilesInfo.text = filesInfo
        } else {
            binding.layoutFileName.visibility = View.VISIBLE
            binding.tvFilesInfo.visibility = View.GONE
            val rawName = defaultName ?: "Converted_${System.currentTimeMillis()}"
            val cleanName = if (extension != null) {
                FileUtils.sanitizeFileName(rawName.removeSuffix(".$extension"))
            } else {
                FileUtils.sanitizeFileName(rawName)
            }
            binding.etSaveFileName.setText(cleanName)
            binding.etSaveFileName.setSelection(cleanName.length)
        }

        binding.btnCancelSave.setOnClickListener {
            dismiss()
        }

        binding.btnChooseSaveFolder.setOnClickListener {
            handleAction(SaveAction.CHOOSE_FOLDER)
        }

        binding.btnSaveToDownloads.setOnClickListener {
            handleAction(SaveAction.SAVE_TO_DOWNLOADS)
        }

        binding.btnShareExport.setOnClickListener {
            handleAction(SaveAction.SHARE)
        }
    }

    override fun onStart() {
        super.onStart()
        applyDialogWidth()
    }

    private fun applyDialogWidth() {
        val displayMetrics = context.resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.90).toInt().coerceAtMost(
            (480 * displayMetrics.density).toInt()
        )
        window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun handleAction(action: SaveAction) {
        if (filesInfo != null) {
            dismiss()
            onAction("", action)
            return
        }

        val rawName = binding.etSaveFileName.text.toString().trim()
        val sanitized = if (extension != null) {
            FileUtils.sanitizeFileName(rawName.removeSuffix(".$extension"))
        } else {
            FileUtils.sanitizeFileName(rawName)
        }

        if (sanitized.isNotEmpty()) {
            val finalName = if (extension != null) "$sanitized.$extension" else sanitized
            dismiss()
            onAction(finalName, action)
        } else {
            Toast.makeText(context, context.getString(R.string.enter_valid_file_name_error), Toast.LENGTH_SHORT).show()
        }
    }
}
