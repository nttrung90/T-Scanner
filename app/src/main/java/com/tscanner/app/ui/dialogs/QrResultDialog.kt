package com.tscanner.app.ui.dialogs

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.tscanner.app.R
import com.tscanner.app.databinding.DialogQrResultBinding
import com.tscanner.app.utils.QrCodeResult
import com.tscanner.app.utils.QrType

class QrResultDialog(
    context: Context,
    private val result: QrCodeResult,
    private val onRescan: () -> Unit
) : Dialog(context) {

    private lateinit var binding: DialogQrResultBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogQrResultBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        applyDialogWidth()

        setupViews()
        setupListeners()
    }

    private fun setupViews() {
        binding.tvQrTypeBadge.text = result.typeLabel

        if (result.type == QrType.WIFI) {
            binding.tvQrContent.visibility = View.GONE
            binding.layoutWifiDetails.visibility = View.VISIBLE
            binding.tvWifiSsid.text = "Tên mạng (SSID): ${result.wifiSsid.orEmpty()} (${result.wifiEncryptionType.orEmpty()})"
            binding.tvWifiPassword.text = "Mật khẩu: ${if (result.wifiPassword.isNullOrEmpty()) "(Không có mật khẩu)" else result.wifiPassword}"
        } else {
            binding.tvQrContent.visibility = View.VISIBLE
            binding.tvQrContent.text = result.displayContent ?: result.rawValue
            binding.layoutWifiDetails.visibility = View.GONE
        }

        if (!result.primaryActionLabel.isNullOrEmpty()) {
            binding.btnQrPrimaryAction.visibility = View.VISIBLE
            binding.btnQrPrimaryAction.text = result.primaryActionLabel
        } else {
            binding.btnQrPrimaryAction.visibility = View.GONE
        }

        val maxScrollHeight = (240 * context.resources.displayMetrics.density).toInt()
        binding.scrollQrContent.post {
            if (binding.scrollQrContent.height > maxScrollHeight) {
                binding.scrollQrContent.layoutParams = binding.scrollQrContent.layoutParams.apply {
                    height = maxScrollHeight
                }
            }
        }
    }

    private fun setupListeners() {
        binding.btnCloseQrResult.setOnClickListener {
            dismiss()
        }

        binding.btnQrCopy.setOnClickListener {
            val textToCopy = when (result.type) {
                QrType.WIFI -> if (!result.wifiPassword.isNullOrEmpty()) result.wifiPassword else result.wifiSsid.orEmpty()
                QrType.CCCD -> result.displayContent ?: result.rawValue
                else -> result.rawValue
            }
            val msg = when (result.type) {
                QrType.WIFI -> "Đã sao chép mật khẩu Wi-Fi!"
                QrType.CCCD -> "Đã sao chép thông tin thẻ CCCD!"
                else -> context.getString(R.string.copied)
            }
            copyToClipboard(textToCopy, msg)
        }

        binding.btnQrShare.setOnClickListener {
            shareText(result.displayContent ?: result.rawValue)
        }

        binding.btnQrRescan.setOnClickListener {
            dismiss()
            onRescan()
        }

        binding.btnQrPrimaryAction.setOnClickListener {
            handlePrimaryAction()
        }
    }

    private fun handlePrimaryAction() {
        when (result.type) {
            QrType.URL -> {
                val url = result.primaryActionUrl ?: result.rawValue
                val fixedUrl = if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                    "https://$url"
                } else url
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fixedUrl))
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Không thể mở liên kết: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            QrType.WIFI -> {
                val password = result.wifiPassword.orEmpty()
                if (password.isNotEmpty()) {
                    copyToClipboard(password, "Đã sao chép mật khẩu Wi-Fi!")
                } else {
                    Toast.makeText(context, "Mạng Wi-Fi này không có mật khẩu", Toast.LENGTH_SHORT).show()
                }
            }
            QrType.PHONE -> {
                try {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse(result.primaryActionUrl ?: "tel:${result.rawValue}"))
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Không thể gọi điện", Toast.LENGTH_SHORT).show()
                }
            }
            QrType.EMAIL -> {
                try {
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(result.primaryActionUrl ?: "mailto:${result.rawValue}"))
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Không thể mở ứng dụng gửi Email", Toast.LENGTH_SHORT).show()
                }
            }
            QrType.SMS -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.primaryActionUrl ?: "smsto:${result.rawValue}"))
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Không thể mở ứng dụng tin nhắn SMS", Toast.LENGTH_SHORT).show()
                }
            }
            QrType.CCCD -> {
                copyToClipboard(result.displayContent ?: result.rawValue, "Đã sao chép thông tin thẻ CCCD!")
            }
            QrType.VIETQR -> {
                copyToClipboard(result.rawValue, "Đã sao chép mã VietQR!")
            }
            else -> {
                copyToClipboard(result.rawValue, context.getString(R.string.copied))
            }
        }
    }

    private fun copyToClipboard(text: String, successMessage: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("QR Code", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, successMessage, Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share)))
        } catch (e: Exception) {
            Toast.makeText(context, "Không thể chia sẻ", Toast.LENGTH_SHORT).show()
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
