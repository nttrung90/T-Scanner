package com.tscanner.app.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import com.tscanner.app.databinding.DialogConfirmDeleteBinding

class ConfirmDeleteDialog(
    context: Context,
    private val title: String,
    private val message: String,
    private val onConfirm: () -> Unit
) : Dialog(context) {

    private lateinit var binding: DialogConfirmDeleteBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogConfirmDeleteBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        applyDialogWidth()

        binding.tvDeleteTitle.text = title
        binding.tvDeleteMessage.text = message

        binding.btnCancelDelete.setOnClickListener {
            dismiss()
        }

        binding.btnConfirmDelete.setOnClickListener {
            dismiss()
            onConfirm()
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
