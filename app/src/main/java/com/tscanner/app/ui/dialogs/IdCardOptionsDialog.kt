package com.tscanner.app.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.tscanner.app.databinding.DialogIdCardOptionsBinding

class IdCardOptionsDialog(
    context: Context,
    private val onCameraScan: () -> Unit,
    private val onFastScan: (() -> Unit)? = null,
    private val onGalleryPick: () -> Unit
) : Dialog(context) {

    private lateinit var binding: DialogIdCardOptionsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogIdCardOptionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        applyDialogWidth()

        binding.btnCloseIdCardDialog.setOnClickListener {
            dismiss()
        }

        binding.btnOptionCamera.setOnClickListener {
            dismiss()
            onCameraScan()
        }

        if (onFastScan != null) {
            binding.btnOptionIdCardFast.visibility = View.VISIBLE
            binding.btnOptionIdCardFast.setOnClickListener {
                dismiss()
                onFastScan.invoke()
            }
        } else {
            binding.btnOptionIdCardFast.visibility = View.GONE
        }

        binding.btnOptionGallery.setOnClickListener {
            dismiss()
            onGalleryPick()
        }
    }

    override fun onStart() {
        super.onStart()
        applyDialogWidth()
    }

    private fun applyDialogWidth() {
        val displayMetrics = context.resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.90).toInt().coerceAtMost(
            (460 * displayMetrics.density).toInt()
        )
        window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
