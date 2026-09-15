package com.tscanner.app.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import com.tscanner.app.databinding.DialogAboutBinding

class AboutAppDialog(context: Context) : Dialog(context) {

    private lateinit var binding: DialogAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        applyDialogWidth()

        binding.btnCloseAbout.setOnClickListener {
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
}
