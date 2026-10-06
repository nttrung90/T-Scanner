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
import com.tscanner.app.utils.SyncCatalogResult

class CreatePdfDialog(
    context: Context,
    private val defaultName: String,
    private var isWatermarkRemoved: Boolean = AppAuthManager.isUserVip(),
    private val onWatermarkToggled: ((Boolean) -> Unit)? = null,
    private val onRequestDrivePermission: (() -> Unit)? = null,
    private val onRequestSignIn: ((currentName: String) -> Unit)? = null,
    private val onSyncResult: ((SyncCatalogResult) -> Unit)? = null,
    private val onRequestSignInForAction: ((com.tscanner.app.utils.VipContinuationAction, currentName: String) -> Unit)? = null,
    private val onRequestSignInForRecovery: ((com.tscanner.app.utils.VipContinuationAction, currentName: String, com.tscanner.app.utils.billing.BillingOperationContext?, () -> Unit, () -> Unit) -> Unit)? = null,
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
        onRequestDrivePermission = null,
        onRequestSignIn = null,
        onSyncResult = null,
        onRequestSignInForAction = null,
        onRequestSignInForRecovery = null,
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
                val vipDialog = VipUpgradeDialog(
                    context = context,
                    onRequestDrivePermission = onRequestDrivePermission,
                    onUpgradeSuccess = {
                        if (AppAuthManager.isUserVip()) {
                            isWatermarkRemoved = true
                            updateWatermarkStatusView()
                            onWatermarkToggled?.invoke(true)
                            Toast.makeText(context, context.getString(R.string.vip_watermark_removed_toast), Toast.LENGTH_LONG).show()
                        }
                    },
                    onRequestSignIn = if (onRequestSignIn != null) {
                        {
                            val currentName = binding.etPdfName.text.toString().trim()
                            dismiss()
                            onRequestSignIn.invoke(currentName)
                        }
                    } else null,
                    onSyncResult = onSyncResult,
                    onRequestSignInForAction = if (onRequestSignInForAction != null) {
                        { action ->
                            val currentName = binding.etPdfName.text.toString().trim()
                            dismiss()
                            onRequestSignInForAction.invoke(action, currentName)
                        }
                    } else if (onRequestSignIn != null) {
                        { action ->
                            val currentName = binding.etPdfName.text.toString().trim()
                            dismiss()
                            onRequestSignIn.invoke(currentName)
                        }
                    } else null
                )
                if (onRequestSignInForRecovery != null) {
                    vipDialog.onRequestSignInForRecovery = { action, opContext, onStarted, onRefused ->
                        val currentName = binding.etPdfName.text.toString().trim()
                        dismiss()
                        onRequestSignInForRecovery.invoke(action, currentName, opContext, onStarted, onRefused)
                    }
                }
                vipDialog.show()
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
                Toast.makeText(context, context.getString(R.string.enter_valid_pdf_name_error), Toast.LENGTH_SHORT).show()
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
                binding.tvWatermarkStatus.text = context.getString(R.string.watermark_status_enabled_vip)
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

    override fun dismiss() {
        try {
            if (isShowing) {
                super.dismiss()
            }
        } catch (e: Exception) {
            android.util.Log.w("CreatePdfDialog", "Error dismissing dialog safely", e)
        }
    }
}
