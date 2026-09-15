package com.tscanner.app.ui.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tscanner.app.R
import com.tscanner.app.databinding.ActivityCameraScanBinding
import com.tscanner.app.ui.idcard.IdCardComposeActivity
import com.tscanner.app.ui.viewer.PdfViewerActivity
import com.tscanner.app.utils.DocumentEdgeDetector
import com.tscanner.app.utils.DocumentScannerHelper
import com.tscanner.app.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraScanBinding
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private val sessionId = UUID.randomUUID().toString()
    private val capturedPagePaths = mutableListOf<String>()

    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var isAutoCropEnabled = true
    private var isCapturing = false
    private var isIdCardMode = false

    // Scanner helper for switching to Google ML Kit AI Scanner
    private lateinit var scannerHelper: DocumentScannerHelper
    private lateinit var aiScannerLauncher: ActivityResultLauncher<IntentSenderRequest>

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Cần cấp quyền máy ảnh để thực hiện quét tài liệu", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        binding = ActivityCameraScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isIdCardMode = intent.getBooleanExtra(EXTRA_IS_ID_CARD_MODE, false)
        cameraExecutor = Executors.newSingleThreadExecutor()

        setupWindowInsets()
        setupListeners()
        setupAiScannerFallback()

        if (isIdCardMode) {
            binding.focusOverlayView.setIdCardMode(true)
            binding.tvCameraHint.text = "🪪 Chụp MẶT TRƯỚC của thẻ (đặt thẻ khớp khung)"
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleExitAttempt()
            }
        })
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.layoutCameraTopBar.setPadding(
                binding.layoutCameraTopBar.paddingLeft,
                statusBarInsets.top + (8 * resources.displayMetrics.density).toInt(),
                binding.layoutCameraTopBar.paddingRight,
                binding.layoutCameraTopBar.paddingBottom
            )

            binding.layoutCameraBottomBar.setPadding(
                binding.layoutCameraBottomBar.paddingLeft,
                binding.layoutCameraBottomBar.paddingTop,
                binding.layoutCameraBottomBar.paddingRight,
                navInsets.bottom + (16 * resources.displayMetrics.density).toInt()
            )

            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupListeners() {
        // Đóng / Hủy
        binding.btnCloseCamera.setOnClickListener {
            handleExitAttempt()
        }

        // Đổi đèn Flash
        binding.btnFlashToggle.setOnClickListener {
            cycleFlashMode()
        }

        // Bật / Tắt tự động cắt viền
        binding.btnAutoCropToggle.setOnClickListener {
            isAutoCropEnabled = !isAutoCropEnabled
            updateAutoCropUI()
        }

        // Chuyển sang chế độ Quét AI (Google ML Kit)
        binding.btnSwitchAiScan.setOnClickListener {
            startGoogleAiScan()
        }

        // Chụp siêu tốc (Shutter)
        binding.btnShutterCapture.setOnClickListener {
            takeSuperFastPhoto()
        }

        // Hoàn tất quét
        binding.btnDoneScan.setOnClickListener {
            finishScanningSession()
        }

        // Chạm vào màn hình để lấy nét (Tap-to-Focus)
        binding.previewView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                focusAtPoint(event.x, event.y)
                view.performClick()
                true
            } else {
                true
            }
        }
    }

    private fun focusAtPoint(x: Float, y: Float) {
        val cam = camera ?: return
        val factory = binding.previewView.meteringPointFactory
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()

        cam.cameraControl.startFocusAndMetering(action)
        binding.focusOverlayView.showFocusAt(x, y)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(binding.previewView.surfaceProvider)
                    }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setFlashMode(flashMode)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Không thể khởi động máy ảnh: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takeSuperFastPhoto() {
        val capture = imageCapture ?: return
        if (isCapturing) return
        isCapturing = true

        // 1. Phản hồi tức thì bằng âm rung haptic + chớp màn hình (Zero Shutter Lag UX)
        triggerShutterFeedback()

        val tempDir = FileUtils.getTempScanSessionDir(this, sessionId)
        val photoIndex = capturedPagePaths.size + 1
        val rawFile = File(tempDir, "raw_${System.currentTimeMillis()}_$photoIndex.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(rawFile).build()

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    // Mở khóa shutter ngay khi ảnh đã được lưu vào buffer để người dùng có thể chụp tiếp
                    isCapturing = false

                    lifecycleScope.launch {
                        val finalPageFile = File(tempDir, "page_${photoIndex}.jpg")

                        // 2. Tự động nhận diện & cắt viền ngầm trong luồng nền
                        withContext(Dispatchers.IO) {
                            if (isAutoCropEnabled) {
                                DocumentEdgeDetector.detectAndCrop(rawFile, finalPageFile)
                            } else {
                                DocumentEdgeDetector.normalizeImageOrientation(rawFile, finalPageFile)
                            }
                            try { rawFile.delete() } catch (_: Exception) {}
                        }

                        capturedPagePaths.add(finalPageFile.absolutePath)
                        updateCapturedPagesUI(finalPageFile.absolutePath)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                    isCapturing = false
                    lifecycleScope.launch {
                        Toast.makeText(this@CameraScanActivity, "Lỗi khi chụp: ${exception.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun triggerShutterFeedback() {
        // Haptic feedback
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(40)
                }
            }
        } catch (_: Exception) {}

        // Visual flash effect
        binding.vShutterFlash.visibility = View.VISIBLE
        binding.vShutterFlash.alpha = 0.65f
        binding.vShutterFlash.animate()
            .alpha(0f)
            .setDuration(120)
            .withEndAction {
                binding.vShutterFlash.visibility = View.GONE
            }
            .start()
    }

    private fun updateCapturedPagesUI(latestImagePath: String) {
        val totalPages = capturedPagePaths.size

        // Cập nhật ảnh đại diện thumbnail
        binding.cardLastThumbnail.visibility = View.VISIBLE
        Glide.with(this)
            .load(File(latestImagePath))
            .centerCrop()
            .into(binding.ivLastThumbnail)

        // Cập nhật nút Hoàn tất và huy hiệu số trang
        binding.layoutDoneContainer.visibility = View.VISIBLE
        binding.tvDonePageCount.text = totalPages.toString()
        binding.btnDoneScan.text = "Hoàn tất ($totalPages)"

        if (isIdCardMode) {
            if (totalPages == 1) {
                binding.tvCameraHint.text = "🪪 Đã chụp mặt trước. Hãy lật thẻ và chụp MẶT SAU"
            } else if (totalPages >= 2) {
                // Tự động chuyển thẳng vào màn hình ghép thẻ khi đủ 2 mặt
                finishScanningSession()
            }
        } else {
            binding.tvCameraHint.text = "Đã chụp $totalPages trang • Tiếp tục chụp hoặc bấm Hoàn tất"
        }
    }

    private fun cycleFlashMode() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }

        imageCapture?.flashMode = flashMode

        val (iconRes, toastMsg) = when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> Pair(R.drawable.ic_flash_on, "Đèn Flash: Bật")
            ImageCapture.FLASH_MODE_AUTO -> Pair(R.drawable.ic_flash_auto, "Đèn Flash: Tự động")
            else -> Pair(R.drawable.ic_flash_off, "Đèn Flash: Tắt")
        }

        binding.btnFlashToggle.setImageResource(iconRes)
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
    }

    private fun updateAutoCropUI() {
        if (isAutoCropEnabled) {
            binding.ivAutoCropIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#00C28E"))
            binding.tvAutoCropLabel.setTextColor(Color.parseColor("#00C28E"))
            binding.focusOverlayView.setGuideFrameVisible(true)
            Toast.makeText(this, "Bật tự động nhận diện & cắt viền tài liệu", Toast.LENGTH_SHORT).show()
        } else {
            binding.ivAutoCropIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#8E909A"))
            binding.tvAutoCropLabel.setTextColor(Color.parseColor("#8E909A"))
            binding.focusOverlayView.setGuideFrameVisible(false)
            Toast.makeText(this, "Tắt tự động cắt viền (Chụp toàn khung)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupAiScannerFallback() {
        scannerHelper = DocumentScannerHelper(this)
        aiScannerLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            scannerHelper.handleScanResult(
                result = result,
                onSuccess = { session ->
                    if (session.tempPagePaths.isNotEmpty()) {
                        if (isIdCardMode) {
                            IdCardComposeActivity.start(
                                context = this@CameraScanActivity,
                                pagePaths = session.tempPagePaths
                            )
                        } else {
                            PdfViewerActivity.startForNewScan(
                                context = this@CameraScanActivity,
                                sessionId = session.sessionId,
                                pdfPath = session.tempPdfPath,
                                pagePaths = session.tempPagePaths
                            )
                        }
                        finish()
                    } else {
                        Toast.makeText(this@CameraScanActivity, "Không có trang nào được quét", Toast.LENGTH_SHORT).show()
                    }
                },
                onCancelled = {},
                onError = { err ->
                    Toast.makeText(this@CameraScanActivity, "Lỗi quét AI: $err", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun startGoogleAiScan() {
        if (isIdCardMode) {
            scannerHelper.startIdCardScan(aiScannerLauncher) { err ->
                Toast.makeText(this, "Không thể mở trình Quét AI thẻ: $err", Toast.LENGTH_LONG).show()
            }
        } else {
            scannerHelper.startScan(aiScannerLauncher) { err ->
                Toast.makeText(this, "Không thể mở trình Quét AI: $err", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun finishScanningSession() {
        if (capturedPagePaths.isEmpty()) {
            Toast.makeText(this, "Chưa có trang tài liệu nào được chụp", Toast.LENGTH_SHORT).show()
            return
        }

        if (isIdCardMode) {
            IdCardComposeActivity.start(
                context = this,
                pagePaths = capturedPagePaths
            )
        } else {
            PdfViewerActivity.startForNewScan(
                context = this,
                sessionId = sessionId,
                pdfPath = null,
                pagePaths = capturedPagePaths
            )
        }
        finish()
    }

    private fun handleExitAttempt() {
        if (capturedPagePaths.isNotEmpty()) {
            val message = if (isIdCardMode) {
                "Bạn đang chụp dở thẻ ID. Thoát ra sẽ hủy ảnh chụp thẻ vừa rồi."
            } else {
                "Bạn đã chụp ${capturedPagePaths.size} trang tài liệu. Thoát ra sẽ hủy các trang vừa chụp."
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("Hủy phiên quét?")
                .setMessage(message)
                .setPositiveButton("Hủy & Thoát") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        FileUtils.deleteTempSession(this@CameraScanActivity, sessionId)
                    }
                    finish()
                }
                .setNegativeButton("Tiếp tục chụp", null)
                .show()
        } else {
            finish()
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        baseContext, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        const val EXTRA_IS_ID_CARD_MODE = "extra_is_id_card_mode"

        fun start(context: Context) {
            val intent = Intent(context, CameraScanActivity::class.java).apply {
                putExtra(EXTRA_IS_ID_CARD_MODE, false)
            }
            context.startActivity(intent)
        }

        fun startForIdCard(context: Context) {
            val intent = Intent(context, CameraScanActivity::class.java).apply {
                putExtra(EXTRA_IS_ID_CARD_MODE, true)
            }
            context.startActivity(intent)
        }
    }
}
