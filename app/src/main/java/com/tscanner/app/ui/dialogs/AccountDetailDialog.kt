package com.tscanner.app.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.tscanner.app.R
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.databinding.DialogAccountDetailBinding
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.AvatarViewBinder
import com.tscanner.app.utils.FileUtils

class AccountDetailDialog(
    context: Context,
    private val user: UserProfile,
    private val onRequestDrivePermission: (() -> Unit)? = null,
    private val onRequestSignIn: (() -> Unit)? = null,
    private val onRequestSignInForAction: ((com.tscanner.app.utils.VipContinuationAction) -> Unit)? = null,
    private val onRequestSignInForRecovery: ((com.tscanner.app.utils.VipContinuationAction, com.tscanner.app.utils.billing.BillingOperationContext?, () -> Unit, () -> Unit) -> Unit)? = null,
    private val onUpgradeSuccess: (() -> Unit)? = null,
    private val onSyncResult: ((com.tscanner.app.utils.SyncCatalogResult) -> Unit)? = null,
    private val onSignOut: () -> Unit
) : Dialog(context) {

    private val hostContext: Context = context
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

        AvatarViewBinder.bindAvatar(
            imageView = binding.ivDialogAvatar,
            photoUrl = user.photoUrl,
            fallbackRes = R.drawable.ic_account_circle,
            sizePx = 160
        )

        val hasDrive = AppAuthManager.hasDrivePermission(context)

        if (user.isVipActive) {
            val tierTitle = when (user.tier) {
                VipTier.VIP_PRO_MAX -> context.getString(R.string.account_status_vip_pro_max)
                VipTier.VIP_PRO -> context.getString(R.string.account_status_vip_pro)
                else -> context.getString(R.string.account_status_vip)
            }
            binding.tvDialogPlanStatus.text = tierTitle
            binding.tvDialogPlanStatus.setTextColor(ContextCompat.getColor(context, R.color.vip_gold))
            binding.tvDialogPlanDesc.text = context.getString(R.string.account_cloud_sync_desc)
            binding.ivDialogPlanIcon.setImageResource(R.drawable.ic_vip)

            if (!hasDrive && !user.email.contains("demo")) {
                binding.tvDialogCloudTag.text = context.getString(R.string.drive_permission_not_granted_tag)
                binding.tvDialogCloudTag.setBackgroundResource(R.drawable.btn_delete_confirm)
                binding.tvDialogCloudTag.setTextColor(ContextCompat.getColor(context, R.color.badge_red))

                binding.tvDialogExpiryInfo.visibility = View.VISIBLE
                binding.tvDialogExpiryInfo.text = context.getString(R.string.drive_permission_not_granted_desc)
                binding.tvDialogExpiryInfo.setTextColor(ContextCompat.getColor(context, R.color.badge_red))
            } else {
                binding.tvDialogCloudTag.text = "Google Drive (15GB)"
                binding.tvDialogCloudTag.setBackgroundResource(R.drawable.btn_vip_gold)
                binding.tvDialogCloudTag.setTextColor(ContextCompat.getColor(context, R.color.vip_btn_text))

                binding.tvDialogExpiryInfo.visibility = View.VISIBLE
                val expiryDateStr = user.vipExpiresAt?.let { FileUtils.formatDate(it) } ?: context.getString(R.string.unknown)
                binding.tvDialogExpiryInfo.text = context.getString(R.string.vip_expiry_remaining_format, user.daysRemaining, expiryDateStr)
                binding.tvDialogExpiryInfo.setTextColor(ContextCompat.getColor(context, R.color.vip_gold))
            }

            binding.btnDialogUpgradeAction.text = context.getString(R.string.vip_extend_one_year_btn)
        } else {
            binding.tvDialogPlanStatus.text = context.getString(R.string.account_status_free)
            binding.tvDialogPlanStatus.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            binding.tvDialogPlanDesc.text = context.getString(R.string.account_cloud_sync_desc_free)
            binding.ivDialogPlanIcon.setImageResource(R.drawable.ic_about)
            binding.tvDialogCloudTag.text = context.getString(R.string.cloud_tag_not_activated)
            binding.tvDialogCloudTag.setBackgroundResource(R.drawable.bg_card_rounded)
            binding.tvDialogCloudTag.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))

            binding.tvDialogExpiryInfo.visibility = View.VISIBLE
            binding.tvDialogExpiryInfo.text = context.getString(R.string.vip_upgrade_prompt_desc)
            binding.tvDialogExpiryInfo.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))

            binding.btnDialogUpgradeAction.text = context.getString(R.string.vip_upgrade_btn_price)
        }
    }

    @VisibleForTesting
    internal var vipUpgradeDialogFactory: (Context, (() -> Unit)?, (() -> Unit)?, (() -> Unit)?, ((com.tscanner.app.utils.SyncCatalogResult) -> Unit)?, ((com.tscanner.app.utils.VipContinuationAction) -> Unit)?) -> VipUpgradeDialog =
        { ctx, drive, upgrade, signIn, sync, signInAction ->
            val dialog = VipUpgradeDialog(
                context = ctx,
                onRequestDrivePermission = drive,
                onUpgradeSuccess = upgrade,
                onRequestSignIn = signIn,
                onSyncResult = sync,
                onRequestSignInForAction = signInAction
            )
            dialog.onRequestSignInForRecovery = onRequestSignInForRecovery
            dialog
        }

    private fun openVipUpgradeDialog() {
        dismiss()
        val dialog = vipUpgradeDialogFactory(
            hostContext,
            onRequestDrivePermission,
            onUpgradeSuccess,
            onRequestSignIn,
            onSyncResult,
            onRequestSignInForAction
        )
        if (dialog.onRequestSignInForRecovery == null && onRequestSignInForRecovery != null) {
            dialog.onRequestSignInForRecovery = onRequestSignInForRecovery
        }
        dialog.show()
    }

    @VisibleForTesting
    internal fun performUpgradeButtonClickForTesting() {
        openVipUpgradeDialog()
    }

    @VisibleForTesting
    internal fun performMembershipStatusClickForTesting() {
        if (!user.isVipActive) {
            openVipUpgradeDialog()
        } else if (!AppAuthManager.hasDrivePermission(hostContext) && !user.email.contains("demo")) {
            dismiss()
            onRequestDrivePermission?.invoke()
        }
    }

    private fun setupListeners() {
        binding.btnDialogUpgradeAction.setOnClickListener {
            openVipUpgradeDialog()
        }

        binding.containerMembershipStatus.setOnClickListener {
            if (!user.isVipActive) {
                openVipUpgradeDialog()
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

    override fun onStop() {
        if (::binding.isInitialized) {
            AvatarViewBinder.clearAvatar(binding.ivDialogAvatar)
        }
        super.onStop()
    }

    private fun applyDialogWidth() {
        val displayMetrics = context.resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.88).toInt().coerceAtMost(
            (460 * displayMetrics.density).toInt()
        )
        window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
