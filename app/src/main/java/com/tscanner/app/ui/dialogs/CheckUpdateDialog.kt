package com.tscanner.app.ui.dialogs

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.tscanner.app.R
import com.tscanner.app.databinding.DialogCheckUpdateBinding

class CheckUpdateDialog(
    private val activity: Activity
) : Dialog(activity) {

    private lateinit var binding: DialogCheckUpdateBinding

    companion object {
        const val REQUEST_CODE_UPDATE = 1001

        fun openPlayStore(context: Context) {
            val packageName = context.packageName
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogCheckUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Ensure reasonable dialog width on various screen sizes
        val displayMetrics = context.resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.88).toInt()
        window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        val versionName = getAppVersionName()
        binding.tvCurrentVersion.text = "Phiên bản hiện tại: v$versionName"

        binding.btnSecondaryAction.setOnClickListener {
            dismiss()
        }

        startUpdateCheck(versionName)
    }

    private fun getAppVersionName(): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "0.5.0"
        } catch (e: Exception) {
            "0.5.0"
        }
    }

    private fun startUpdateCheck(currentVersion: String) {
        // Initial Loading State
        binding.pbChecking.visibility = View.VISIBLE
        binding.ivStatusIcon.visibility = View.GONE
        binding.btnPrimaryAction.visibility = View.GONE
        binding.btnSecondaryAction.text = context.getString(R.string.cancel)
        binding.tvUpdateTitle.text = context.getString(R.string.check_update_title)
        binding.tvUpdateMessage.text = context.getString(R.string.check_update_checking)

        val appUpdateManager = AppUpdateManagerFactory.create(context)
        val appUpdateInfoTask = appUpdateManager.appUpdateInfo

        appUpdateInfoTask.addOnSuccessListener { appUpdateInfo ->
            if (!isShowing) return@addOnSuccessListener
            when (appUpdateInfo.updateAvailability()) {
                UpdateAvailability.UPDATE_AVAILABLE -> {
                    showUpdateAvailableState(appUpdateInfo, appUpdateManager)
                }
                UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                    showUpdateInProgressState(appUpdateInfo, appUpdateManager)
                }
                else -> {
                    showUpToDateState(currentVersion)
                }
            }
        }.addOnFailureListener {
            if (!isShowing) return@addOnFailureListener
            showErrorOrFallbackState()
        }
    }

    private fun showUpdateAvailableState(
        appUpdateInfo: AppUpdateInfo,
        appUpdateManager: AppUpdateManager
    ) {
        binding.pbChecking.visibility = View.GONE
        binding.ivStatusIcon.visibility = View.VISIBLE
        binding.ivStatusIcon.setImageResource(R.drawable.ic_update)
        binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.vip_gold))

        binding.tvUpdateTitle.text = context.getString(R.string.check_update_available_title)
        binding.tvUpdateMessage.text = context.getString(R.string.check_update_available_desc)

        binding.btnPrimaryAction.visibility = View.VISIBLE
        binding.btnPrimaryAction.text = context.getString(R.string.check_update_action_update)
        binding.btnPrimaryAction.setOnClickListener {
            dismiss()
            val isImmediateAllowed = appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
            val isFlexibleAllowed = appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)

            if (isImmediateAllowed || isFlexibleAllowed) {
                try {
                    val updateType = if (isImmediateAllowed) AppUpdateType.IMMEDIATE else AppUpdateType.FLEXIBLE
                    val options = AppUpdateOptions.newBuilder(updateType).build()
                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        activity,
                        options,
                        REQUEST_CODE_UPDATE
                    )
                } catch (e: Exception) {
                    openPlayStore(context)
                }
            } else {
                openPlayStore(context)
            }
        }

        binding.btnSecondaryAction.text = context.getString(R.string.check_update_action_later)
        binding.btnSecondaryAction.setOnClickListener {
            dismiss()
        }
    }

    private fun showUpdateInProgressState(
        appUpdateInfo: AppUpdateInfo,
        appUpdateManager: AppUpdateManager
    ) {
        binding.pbChecking.visibility = View.GONE
        binding.ivStatusIcon.visibility = View.VISIBLE
        binding.ivStatusIcon.setImageResource(R.drawable.ic_update)
        binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primary_teal))

        binding.tvUpdateTitle.text = context.getString(R.string.check_update_title)
        binding.tvUpdateMessage.text = "Quá trình cập nhật đang diễn ra trong nền. Bạn có thể tiếp tục cập nhật ngay bây giờ."

        binding.btnPrimaryAction.visibility = View.VISIBLE
        binding.btnPrimaryAction.text = "Tiếp tục cập nhật"
        binding.btnPrimaryAction.setOnClickListener {
            dismiss()
            try {
                val options = AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                appUpdateManager.startUpdateFlowForResult(
                    appUpdateInfo,
                    activity,
                    options,
                    REQUEST_CODE_UPDATE
                )
            } catch (e: Exception) {
                openPlayStore(context)
            }
        }

        binding.btnSecondaryAction.text = context.getString(R.string.check_update_close)
        binding.btnSecondaryAction.setOnClickListener {
            dismiss()
        }
    }

    private fun showUpToDateState(currentVersion: String) {
        binding.pbChecking.visibility = View.GONE
        binding.ivStatusIcon.visibility = View.VISIBLE
        binding.ivStatusIcon.setImageResource(R.drawable.ic_check_circle)
        binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primary_teal))

        binding.tvUpdateTitle.text = context.getString(R.string.check_update_latest_title)
        binding.tvUpdateMessage.text = context.getString(R.string.check_update_latest_desc, "v$currentVersion")

        binding.btnPrimaryAction.visibility = View.VISIBLE
        binding.btnPrimaryAction.text = context.getString(R.string.check_update_open_store)
        binding.btnPrimaryAction.setOnClickListener {
            openPlayStore(context)
        }

        binding.btnSecondaryAction.text = context.getString(R.string.check_update_close)
        binding.btnSecondaryAction.setOnClickListener {
            dismiss()
        }
    }

    private fun showErrorOrFallbackState() {
        binding.pbChecking.visibility = View.GONE
        binding.ivStatusIcon.visibility = View.VISIBLE
        binding.ivStatusIcon.setImageResource(R.drawable.ic_update)
        binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primary_teal))

        binding.tvUpdateTitle.text = context.getString(R.string.check_update_title)
        binding.tvUpdateMessage.text = context.getString(R.string.check_update_error_desc)

        binding.btnPrimaryAction.visibility = View.VISIBLE
        binding.btnPrimaryAction.text = context.getString(R.string.check_update_open_store)
        binding.btnPrimaryAction.setOnClickListener {
            openPlayStore(context)
        }

        binding.btnSecondaryAction.text = context.getString(R.string.check_update_close)
        binding.btnSecondaryAction.setOnClickListener {
            dismiss()
        }
    }
}
