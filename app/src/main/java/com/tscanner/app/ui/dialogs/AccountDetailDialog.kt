package com.tscanner.app.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.tscanner.app.R
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.databinding.DialogAccountDetailBinding
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.FileUtils

class AccountDetailDialog(
    context: Context,
    private val user: UserProfile,
    private val onRequestDrivePermission: (() -> Unit)? = null,
    private val onSignOut: () -> Unit
) : Dialog(context) {

    private lateinit var binding: DialogAccountDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogAccountDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        applyDialogWidth()

        setupViews()
        setupListeners()
    }

    private fun setupViews() {
        binding.tvDialogName.text = user.displayName
        binding.tvDialogEmail.text = user.email

        if (!user.photoUrl.isNullOrEmpty()) {
            Glide.with(context)
                .load(user.photoUrl)
                .transform(CircleCrop())
                .placeholder(R.drawable.ic_account_circle)
                .error(R.drawable.ic_account_circle)
                .into(binding.ivDialogAvatar)
        } else {
            binding.ivDialogAvatar.setImageResource(R.drawable.ic_account_circle)
        }

        val hasDrive = AppAuthManager.hasDrivePermission(context)

        if (user.isVip) {
            val tierTitle = when (user.tier) {
                VipTier.VIP_PRO_MAX -> "Thành viên VIP PRO MAX"
                VipTier.VIP_PRO -> "Thành viên VIP PRO"
                else -> context.getString(R.string.account_status_vip)
            }
            binding.tvDialogPlanStatus.text = tierTitle
            binding.tvDialogPlanStatus.setTextColor(ContextCompat.getColor(context, R.color.vip_gold))
            binding.tvDialogPlanDesc.text = context.getString(R.string.account_cloud_sync_desc)
            binding.ivDialogPlanIcon.setImageResource(R.drawable.ic_vip)

            if (!hasDrive && !user.email.contains("demo")) {
                binding.tvDialogCloudTag.text = "⚠️ Chưa cấp quyền Drive"
                binding.tvDialogCloudTag.setBackgroundResource(R.drawable.btn_delete_confirm)
                binding.tvDialogCloudTag.setTextColor(ContextCompat.getColor(context, R.color.badge_red))

                binding.tvDialogExpiryInfo.visibility = View.VISIBLE
                binding.tvDialogExpiryInfo.text = "⚠️ Chưa cấp quyền Google Drive. Chạm vào đây để cấp quyền và sao lưu tài liệu."
                binding.tvDialogExpiryInfo.setTextColor(ContextCompat.getColor(context, R.color.badge_red))
            } else {
                binding.tvDialogCloudTag.text = "Google Drive (15GB)"
                binding.tvDialogCloudTag.setBackgroundResource(R.drawable.btn_vip_gold)
                binding.tvDialogCloudTag.setTextColor(ContextCompat.getColor(context, R.color.vip_btn_text))

                binding.tvDialogExpiryInfo.visibility = View.VISIBLE
                val expiryDateStr = user.vipExpiresAt?.let { FileUtils.formatDate(it) } ?: "Không xác định"
                binding.tvDialogExpiryInfo.text = "⏱️ Thời hạn VIP: Còn ${user.daysRemaining} ngày (Hết hạn: $expiryDateStr)"
                binding.tvDialogExpiryInfo.setTextColor(ContextCompat.getColor(context, R.color.vip_gold))
            }

            binding.btnDialogUpgradeAction.text = "Gia hạn thêm 1 năm (20.000 đ)"
        } else {
            binding.tvDialogPlanStatus.text = context.getString(R.string.account_status_free)
            binding.tvDialogPlanStatus.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            binding.tvDialogPlanDesc.text = context.getString(R.string.account_cloud_sync_desc_free)
            binding.ivDialogPlanIcon.setImageResource(R.drawable.ic_about)
            binding.tvDialogCloudTag.text = "🔒 Chưa kích hoạt"
            binding.tvDialogCloudTag.setBackgroundResource(R.drawable.bg_card_rounded)
            binding.tvDialogCloudTag.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))

            binding.tvDialogExpiryInfo.visibility = View.VISIBLE
            binding.tvDialogExpiryInfo.text = "💡 Nâng cấp VIP để tự động sao lưu dữ liệu lên Google Drive cá nhân."
            binding.tvDialogExpiryInfo.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))

            binding.btnDialogUpgradeAction.text = "Nâng cấp VIP (20.000 đ/năm)"
        }
    }

    private fun setupListeners() {
        binding.btnDialogUpgradeAction.setOnClickListener {
            dismiss()
            VipUpgradeDialog(context, onRequestDrivePermission).show()
        }

        binding.containerMembershipStatus.setOnClickListener {
            if (!user.isVip) {
                dismiss()
                VipUpgradeDialog(context, onRequestDrivePermission).show()
            } else if (!AppAuthManager.hasDrivePermission(context) && !user.email.contains("demo")) {
                dismiss()
                onRequestDrivePermission?.invoke()
            }
        }

        binding.btnDialogClose.setOnClickListener {
            dismiss()
        }

        binding.btnDialogSignOut.setOnClickListener {
            AlertDialog.Builder(context, R.style.ThemeOverlay_TScanner_Dialog)
                .setTitle(R.string.sign_out_confirm_title)
                .setMessage(R.string.sign_out_confirm_desc)
                .setPositiveButton(R.string.btn_sign_out) { _, _ ->
                    dismiss()
                    onSignOut()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
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
