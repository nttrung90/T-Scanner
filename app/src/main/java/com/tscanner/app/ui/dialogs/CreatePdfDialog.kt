package com.tscanner.app.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.tscanner.app.R
import com.tscanner.app.databinding.DialogCreatePdfBinding
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.FileUtils

class CreatePdfDialog(
    context: Context,
    private val defaultName: String,
    private var isWatermarkRemoved: Boolean = AppAuthManager.isUserVip(),
    private val onWatermarkToggled: ((Boolean) -> Unit)? = null,
    private val onConfirm: (String) -> Unit
) : Dialog(context) {

    constructor(
        context: Context,
        defaultName: String,
        onConfirm: (String) -> Unit
    ) : this(
        context = context,
        defaultName = defaultName,
        isWatermarkRemoved = AppAuthManager.isUserVip(),
        onWatermarkToggled = null,
        onConfirm = onConfirm
    )

    private lateinit var binding: DialogCreatePdfBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogCreatePdfBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        applyDialogWidth()

        val cleanName = FileUtils.sanitizeFileName(defaultName.removeSuffix(".pdf"))
        binding.etPdfName.setText(cleanName)
        binding.etPdfName.setSelection(cleanName.length)

        updateWatermarkStatusView()

        binding.layoutWatermarkStatus.setOnClickListener {
            val isVip = AppAuthManager.isUserVip()
            if (isVip) {
                isWatermarkRemoved = !isWatermarkRemoved
                updateWatermarkStatusView()
                onWatermarkToggled?.invoke(isWatermarkRemoved)
            } else {
                VipUpgradeDialog(
                    context = context,
                    onUpgradeSuccess = {
                        if (AppAuthManager.isUserVip()) {
                            isWatermarkRemoved = true
                            updateWatermarkStatusView()
                            onWatermarkToggled?.invoke(true)
                            Toast.makeText(context, "🎉 Đã nâng cấp VIP! Đã gỡ bỏ đóng dấu.", Toast.LENGTH_LONG).show()
                        }
                    }
                ).show()
            }
        }

        binding.btnCancelCreatePdf.setOnClickListener {
            dismiss()
        }

        binding.btnConfirmCreatePdf.setOnClickListener {
            val rawName = binding.etPdfName.text.toString().trim()
            val fileName = FileUtils.sanitizeFileName(rawName.removeSuffix(".pdf"))
            if (fileName.isNotEmpty()) {
                dismiss()
                onConfirm(fileName)
            } else {
                Toast.makeText(context, "Vui lòng nhập tên file PDF hợp lệ", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateWatermarkStatusView() {
        val isVip = AppAuthManager.isUserVip()
        if (isVip) {
            if (isWatermarkRemoved) {
                binding.ivWatermarkStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primary_teal))
                binding.tvWatermarkStatus.text = context.getString(R.string.watermark_status_vip)
                binding.tvWatermarkStatus.setTextColor(ContextCompat.getColor(context, R.color.primary_teal))
            } else {
                binding.ivWatermarkStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.vip_gold))
                binding.tvWatermarkStatus.text = "Đang bật đóng dấu (VIP)"
                binding.tvWatermarkStatus.setTextColor(ContextCompat.getColor(context, R.color.vip_gold))
            }
        } else {
            binding.ivWatermarkStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.vip_gold))
            binding.tvWatermarkStatus.text = context.getString(R.string.watermark_status_free)
            binding.tvWatermarkStatus.setTextColor(ContextCompat.getColor(context, R.color.vip_gold))
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
