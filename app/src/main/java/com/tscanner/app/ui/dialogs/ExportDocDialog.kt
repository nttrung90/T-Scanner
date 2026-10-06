package com.tscanner.app.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import com.tscanner.app.R
import com.tscanner.app.databinding.DialogExportDocBinding
import com.tscanner.app.utils.FileUtils

class ExportDocDialog(
    context: Context,
    private val title: String,
    private val description: String,
    private val defaultName: String,
    private val extension: String,
    private val supportedExtensions: List<String> = listOf(extension),
    private val onAction: (fileName: String, action: ExportAction) -> Unit
) : Dialog(context) {

    enum class ExportAction {
        SAVE_TO_DEVICE,
        SHARE
    }

    private lateinit var binding: DialogExportDocBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogExportDocBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        applyDialogWidth()

        binding.tvExportTitle.text = title
        binding.tvExportDesc.text = description

        val cleanName = FileUtils.sanitizeFileName(defaultName.removeSuffix(".$extension"))
        binding.etExportFileName.setText(cleanName)
        binding.etExportFileName.setSelection(cleanName.length)

        binding.btnCancelExport.setOnClickListener {
            dismiss()
        }

        binding.btnSaveExport.setOnClickListener {
            handleAction(ExportAction.SAVE_TO_DEVICE)
        }

        binding.btnShareExport.setOnClickListener {
            handleAction(ExportAction.SHARE)
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

    private fun handleAction(action: ExportAction) {
        val rawName = binding.etExportFileName.text.toString().trim()
        val extensionToUse = supportedExtensions.firstOrNull {
            rawName.endsWith(".$it", ignoreCase = true)
        } ?: extension

        val sanitized = FileUtils.sanitizeFileName(rawName.removeSuffix(".$extensionToUse"))
        if (sanitized.isNotEmpty()) {
            val finalName = "$sanitized.$extensionToUse"
            dismiss()
            onAction(finalName, action)
        } else {
            Toast.makeText(context, context.getString(R.string.enter_valid_file_name_error), Toast.LENGTH_SHORT).show()
        }
    }
}
