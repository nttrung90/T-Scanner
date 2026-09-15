package com.tscanner.app.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import com.tscanner.app.R
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.databinding.DialogCreateFolderBinding

class CreateFolderDialog(
    context: Context,
    private val onFolderCreated: () -> Unit
) : Dialog(context) {

    private lateinit var binding: DialogCreateFolderBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogCreateFolderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        applyDialogWidth()

        binding.btnCancelFolder.setOnClickListener {
            dismiss()
        }

        binding.btnConfirmFolder.setOnClickListener {
            val name = binding.etFolderName.text.toString().trim()
            if (name.isNotEmpty()) {
                DocumentRepo.getInstance(context).addFolder(name)
                Toast.makeText(context, context.getString(R.string.folder_created), Toast.LENGTH_SHORT).show()
                onFolderCreated()
                dismiss()
            } else {
                Toast.makeText(context, "Vui lòng nhập tên thư mục", Toast.LENGTH_SHORT).show()
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
