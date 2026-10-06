package com.tscanner.app.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import com.tscanner.app.R
import com.tscanner.app.databinding.DialogAboutBinding

class AboutAppDialog(context: Context) : Dialog(context) {

    private lateinit var binding: DialogAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        applyDialogWidth()

        setupVersionInfo()

        binding.btnCloseAbout.setOnClickListener {
            dismiss()
        }
    }

    private fun setupVersionInfo() {
        val (versionName, versionCode) = getAppVersion(context)
        binding.tvAboutVersion.text = context.getString(R.string.about_version, versionName, versionCode)
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

    companion object {
        fun getAppVersion(context: Context): Pair<String, Long> {
            return try {
                val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        android.content.pm.PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode.toLong()
                }
                Pair(pInfo.versionName ?: "1.2.0", code)
            } catch (_: Throwable) {
                Pair("1.2.0", 20L)
            }
        }
    }
}
