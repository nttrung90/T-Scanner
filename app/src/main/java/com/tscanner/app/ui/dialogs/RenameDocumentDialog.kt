package com.tscanner.app.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import com.tscanner.app.R
import com.tscanner.app.databinding.DialogRenameDocumentBinding

class RenameDocumentDialog(
    context: Context,
    private val currentName: String,
    private val onConfirm: (String) -> Unit
) : Dialog(context) {

    private lateinit var binding: DialogRenameDocumentBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogRenameDocumentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        applyDialogWidth()

        binding.etDocumentName.setText(currentName)
        binding.etDocumentName.setSelection(currentName.length)

        binding.btnCancelRename.setOnClickListener {
            dismiss()
        }

        binding.btnConfirmRename.setOnClickListener {
            val newName = binding.etDocumentName.text.toString().trim()
            if (newName.isNotEmpty()) {
                dismiss()
                onConfirm(newName)
            } else {
                Toast.makeText(context, context.getString(R.string.enter_document_name_prompt), Toast.LENGTH_SHORT).show()
            }
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
}
