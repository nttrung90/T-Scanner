package com.tscanner.app.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import com.tscanner.app.R
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.databinding.DialogVipUpgradeBinding
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.CloudBackupManager

class VipUpgradeDialog(
    context: Context,
    private val onRequestDrivePermission: (() -> Unit)? = null,
    private val onUpgradeSuccess: (() -> Unit)? = null
) : Dialog(context) {

    private lateinit var binding: DialogVipUpgradeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogVipUpgradeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        applyDialogWidth()

        val currentUser = AppAuthManager.getCurrentUser()
        if (currentUser != null && currentUser.email.isNotBlank()) {
            binding.btnConfirmVipUpgrade.text = "Kích hoạt Dùng thử VIP (${currentUser.email})"
        } else {
            binding.btnConfirmVipUpgrade.text = "Đăng nhập để kích hoạt Dùng thử VIP"
        }

        setupListeners()
    }

    private fun setupListeners() {
        // Kích hoạt dùng thử gói VIP (Thử nghiệm liên kết tài khoản)
        binding.btnConfirmVipUpgrade.setOnClickListener {
            val user = AppAuthManager.getCurrentUser()
            if (user != null && user.email.isNotBlank()) {
                AppAuthManager.setUserVipTier(context, VipTier.VIP, durationDays = 365)
                Toast.makeText(
                    context,
                    "🎉 Đã kích hoạt Chế độ Dùng thử VIP (Thử nghiệm) cho tài khoản ${user.email}!",
                    Toast.LENGTH_LONG
                ).show()

                val hasDrive = AppAuthManager.hasDrivePermission(context)
                if (!hasDrive) {
                    Toast.makeText(context, "Vui lòng cấp quyền Google Drive để bắt đầu tự động sao lưu tài liệu!", Toast.LENGTH_LONG).show()
                    if (onRequestDrivePermission != null) {
                        onRequestDrivePermission.invoke()
                    } else {
                        try {
                            context.startActivity(AppAuthManager.getGoogleDriveSignInIntent(context))
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                } else {
                    // Sao lưu ngay các tài liệu chưa được sao lưu trước đó
                    val repo = DocumentRepo.getInstance(context)
                    val unsynced = repo.getUnsyncedDocuments()
                    if (unsynced.isNotEmpty()) {
                        CloudBackupManager.enqueueBatchBackup(context, unsynced)
                        Toast.makeText(
                            context,
                            "Đang tự động sao lưu ${unsynced.size} tài liệu lên Google Drive...",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    // Đồng bộ danh mục tài liệu từ Google Drive nếu có trên thiết bị khác
                    CloudBackupManager.syncCatalogFromDrive(context) { newFilesCount ->
                        if (newFilesCount > 0) {
                            Toast.makeText(context, "Đã đồng bộ $newFilesCount tài liệu từ Google Drive!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                onUpgradeSuccess?.invoke()
                dismiss()
            } else {
                Toast.makeText(
                    context,
                    "Vui lòng đăng nhập tài khoản Google tại tab Mở rộng để liên kết và kích hoạt gói VIP!",
                    Toast.LENGTH_LONG
                ).show()
                dismiss()
            }
        }

        // Luồng mở rộng: VIP PRO
        binding.cardTierVipPro.setOnClickListener {
            Toast.makeText(
                context,
                "✨ Gói VIP PRO với tính năng Trợ lý AI và Dịch thuật nâng cao sẽ sớm được mở rộng trong bản cập nhật tới!",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Luồng mở rộng: VIP PRO MAX
        binding.cardTierVipPromax.setOnClickListener {
            Toast.makeText(
                context,
                "👑 Gói VIP PRO MAX với tính năng Team Sync và Doanh nghiệp sẽ sớm được mở rộng trong bản cập nhật tới!",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnCloseVipDialog.setOnClickListener {
            dismiss()
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
}
