package com.tscanner.app.utils

import android.app.Activity
import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.tscanner.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class QrType {
    URL,
    WIFI,
    PHONE,
    EMAIL,
    SMS,
    CONTACT,
    CCCD,
    VIETQR,
    TEXT
}

data class QrCodeResult(
    val rawValue: String,
    val type: QrType,
    val typeLabel: String,
    val displayContent: String? = null,
    val primaryActionUrl: String? = null,
    val primaryActionLabel: String? = null,
    val wifiSsid: String? = null,
    val wifiPassword: String? = null,
    val wifiEncryptionType: String? = null
)

object QrScannerHelper {

    fun startCameraScan(
        activity: Activity,
        onSuccess: (QrCodeResult) -> Unit,
        onCancel: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_AZTEC
            )
            .enableAutoZoom()
            .build()

        val scanner = GmsBarcodeScanning.getClient(activity, options)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val result = parseBarcode(activity, barcode)
                onSuccess(result)
            }
            .addOnCanceledListener {
                onCancel()
            }
            .addOnFailureListener { e ->
                val errorMsg = when {
                    e.message?.contains("module", ignoreCase = true) == true ||
                    e.message?.contains("download", ignoreCase = true) == true ->
                        "Google Play Services đang tải dữ liệu quét mã. Vui lòng thử lại sau giây lát hoặc chọn ảnh từ bộ sưu tập."
                    e.message?.contains("cancel", ignoreCase = true) == true ->
                        "Đã hủy quét"
                    else ->
                        e.localizedMessage ?: activity.getString(R.string.scanner_error, "QR")
                }
                onError(errorMsg)
            }
    }

    fun scanFromUri(
        context: Context,
        uri: Uri,
        onSuccess: (QrCodeResult) -> Unit,
        onError: (String) -> Unit
    ) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val image = InputImage.fromFilePath(context, uri)
                val options = BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(
                        Barcode.FORMAT_QR_CODE,
                        Barcode.FORMAT_DATA_MATRIX,
                        Barcode.FORMAT_PDF417,
                        Barcode.FORMAT_AZTEC
                    )
                    .build()
                val scanner = BarcodeScanning.getClient(options)
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        if (barcodes.isNotEmpty()) {
                            val result = parseBarcode(context, barcodes[0])
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                onSuccess(result)
                            }
                        } else {
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                onError(context.getString(R.string.qr_no_code_found))
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            onError(e.localizedMessage ?: context.getString(R.string.qr_no_code_found))
                        }
                    }
                    .addOnCompleteListener {
                        try {
                            scanner.close()
                        } catch (_: Exception) {}
                    }
            } catch (e: Exception) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    onError(e.localizedMessage ?: context.getString(R.string.qr_no_code_found))
                }
            }
        }
    }

    private fun formatCccdDate(rawDate: String): String {
        return if (rawDate.length == 8) {
            "${rawDate.substring(0, 2)}/${rawDate.substring(2, 4)}/${rawDate.substring(4)}"
        } else {
            rawDate
        }
    }

    private fun parseBarcode(context: Context, barcode: Barcode): QrCodeResult {
        val raw = barcode.rawValue.orEmpty().trim()
        return when (barcode.valueType) {
            Barcode.TYPE_URL -> {
                val url = barcode.url?.url ?: raw
                QrCodeResult(
                    rawValue = raw,
                    type = QrType.URL,
                    typeLabel = context.getString(R.string.qr_type_url),
                    primaryActionUrl = url,
                    primaryActionLabel = context.getString(R.string.qr_open_url)
                )
            }
            Barcode.TYPE_WIFI -> {
                val ssid = barcode.wifi?.ssid.orEmpty()
                val password = barcode.wifi?.password.orEmpty()
                val encType = when (barcode.wifi?.encryptionType) {
                    Barcode.WiFi.TYPE_OPEN -> "Mạng mở"
                    Barcode.WiFi.TYPE_WPA -> "WPA/WPA2"
                    Barcode.WiFi.TYPE_WEP -> "WEP"
                    else -> "Bảo mật"
                }
                QrCodeResult(
                    rawValue = raw,
                    type = QrType.WIFI,
                    typeLabel = context.getString(R.string.qr_type_wifi),
                    wifiSsid = ssid,
                    wifiPassword = password,
                    wifiEncryptionType = encType,
                    primaryActionUrl = if (password.isNotEmpty()) password else null,
                    primaryActionLabel = context.getString(R.string.qr_copy_wifi_password)
                )
            }
            Barcode.TYPE_PHONE -> {
                val phone = barcode.phone?.number ?: raw
                QrCodeResult(
                    rawValue = raw,
                    type = QrType.PHONE,
                    typeLabel = context.getString(R.string.qr_type_phone),
                    primaryActionUrl = "tel:$phone",
                    primaryActionLabel = context.getString(R.string.qr_call_phone)
                )
            }
            Barcode.TYPE_EMAIL -> {
                val email = barcode.email?.address ?: raw
                QrCodeResult(
                    rawValue = raw,
                    type = QrType.EMAIL,
                    typeLabel = context.getString(R.string.qr_type_email),
                    primaryActionUrl = "mailto:$email",
                    primaryActionLabel = context.getString(R.string.qr_send_email)
                )
            }
            Barcode.TYPE_SMS -> {
                val phone = barcode.sms?.phoneNumber ?: raw
                QrCodeResult(
                    rawValue = raw,
                    type = QrType.SMS,
                    typeLabel = context.getString(R.string.qr_type_sms),
                    primaryActionUrl = "smsto:$phone",
                    primaryActionLabel = "Gửi SMS"
                )
            }
            Barcode.TYPE_CONTACT_INFO -> {
                QrCodeResult(
                    rawValue = raw,
                    type = QrType.CONTACT,
                    typeLabel = context.getString(R.string.qr_type_contact),
                    primaryActionUrl = null
                )
            }
            else -> {
                if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
                    QrCodeResult(
                        rawValue = raw,
                        type = QrType.URL,
                        typeLabel = context.getString(R.string.qr_type_url),
                        primaryActionUrl = raw,
                        primaryActionLabel = context.getString(R.string.qr_open_url)
                    )
                } else if (raw.contains("|") && raw.split("|").let { it.size >= 6 && it[0].length == 12 && it[0].all { c -> c.isDigit() } }) {
                    val parts = raw.split("|")
                    val cccdNumber = parts[0]
                    val oldCmnd = parts.getOrNull(1).orEmpty()
                    val fullName = parts.getOrNull(2).orEmpty()
                    val dob = formatCccdDate(parts.getOrNull(3).orEmpty())
                    val gender = parts.getOrNull(4).orEmpty()
                    val address = parts.getOrNull(5).orEmpty()
                    val issueDate = parts.getOrNull(6)?.let { formatCccdDate(it) }.orEmpty()

                    val formatted = buildString {
                        append("🪪 THÔNG TIN THẺ CĂN CƯỚC\n")
                        append("• Số CCCD: $cccdNumber\n")
                        if (oldCmnd.isNotBlank()) append("• Số CMND cũ: $oldCmnd\n")
                        append("• Họ và tên: $fullName\n")
                        append("• Ngày sinh: $dob\n")
                        append("• Giới tính: $gender\n")
                        append("• Nơi thường trú: $address")
                        if (issueDate.isNotBlank()) append("\n• Ngày cấp: $issueDate")
                    }

                    QrCodeResult(
                        rawValue = raw,
                        type = QrType.CCCD,
                        typeLabel = "Thẻ CCCD gắn chip",
                        displayContent = formatted,
                        primaryActionLabel = "Sao chép thông tin CCCD"
                    )
                } else if (raw.startsWith("000201") && (raw.contains("A000000727") || raw.contains("vietqr", ignoreCase = true) || raw.contains("QRPUSH", ignoreCase = true))) {
                    QrCodeResult(
                        rawValue = raw,
                        type = QrType.VIETQR,
                        typeLabel = "Mã thanh toán VietQR",
                        displayContent = "Mã thanh toán chuyển khoản ngân hàng VietQR / EMVCo\n\nNội dung chuỗi mã:\n$raw",
                        primaryActionLabel = "Sao chép mã VietQR"
                    )
                } else {
                    QrCodeResult(
                        rawValue = raw,
                        type = QrType.TEXT,
                        typeLabel = context.getString(R.string.qr_type_text)
                    )
                }
            }
        }
    }
}
