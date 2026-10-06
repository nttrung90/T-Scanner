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
import com.tscanner.app.utils.EdgeToEdgeInsetsHelper
import com.tscanner.app.utils.FileUtils
import com.tscanner.app.utils.SafeFileWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CameraScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraScanBinding
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private var sessionId = UUID.randomUUID().toString()
    private val capturedPagePaths = mutableListOf<String>()
    private val captureSequence = AtomicInteger(0)
    private val inFlightCaptureCount = AtomicInteger(0)
    private val activeCropJobs = ConcurrentHashMap<Int, Job>()
    private val orderedPageMap = ConcurrentSkipListMap<Int, String>()
    private val pendingRawCaptures = ConcurrentHashMap<Int, String>()
    private val failedCaptures = ConcurrentHashMap<Int, String>()
    private lateinit var sessionManager: CameraScanSessionManager

    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var isAutoCropEnabled = true
    private var isCapturing = false
    private var isFinishingSession = false
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
            Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show()
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

        if (savedInstanceState != null) {
            savedInstanceState.getString(KEY_SESSION_ID)?.let { sessionId = it }
            captureSequence.set(savedInstanceState.getInt(KEY_CAPTURE_SEQ, 0))
            isAutoCropEnabled = savedInstanceState.getBoolean(KEY_AUTO_CROP, true)
            flashMode = savedInstanceState.getInt(KEY_FLASH_MODE, ImageCapture.FLASH_MODE_OFF)

            val pageIndices = savedInstanceState.getIntArray(KEY_PAGE_MAP_INDICES)
            val pagePaths = savedInstanceState.getStringArrayList(KEY_PAGE_MAP_PATHS)
            var maxIdx = captureSequence.get()

            if (pageIndices != null && pagePaths != null && pageIndices.size == pagePaths.size) {
                for (i in pageIndices.indices) {
                    val idx = pageIndices[i]
                    val path = pagePaths[i]
                    val f = File(path)
                    if (f.exists() && f.length() > 0L) {
                        orderedPageMap[idx] = path
                        maxIdx = maxOf(maxIdx, idx)
                    }
                }
            } else {
                val restored = savedInstanceState.getStringArrayList(KEY_CAPTURED_PAGES)
                if (!restored.isNullOrEmpty()) {
                    restored.forEachIndexed { index, path ->
                        val f = File(path)
                        if (f.exists() && f.length() > 0L) {
                            val pIdx = index + 1
                            orderedPageMap[pIdx] = path
                            maxIdx = maxOf(maxIdx, pIdx)
                        }
                    }
                }
            }

            val pendingIndices = savedInstanceState.getIntArray(KEY_PENDING_RAW_INDICES)
            val pendingPaths = savedInstanceState.getStringArrayList(KEY_PENDING_RAW_PATHS)
            if (pendingIndices != null && pendingPaths != null) {
                for (i in pendingIndices.indices) {
                    val idx = pendingIndices[i]
                    val path = pendingPaths[i]
                    val f = File(path)
                    if (f.exists() && f.length() > 0L) {
                        pendingRawCaptures[idx] = path
                        maxIdx = maxOf(maxIdx, idx)
                    }
                }
            }

            val failedIndices = savedInstanceState.getIntArray(KEY_FAILED_CAPTURE_INDICES)
            val failedPaths = savedInstanceState.getStringArrayList(KEY_FAILED_CAPTURE_PATHS)
            if (failedIndices != null && failedPaths != null) {
                for (i in failedIndices.indices) {
                    val idx = failedIndices[i]
                    val path = failedPaths[i]
                    val f = File(path)
                    if (f.exists() && f.length() > 0L) {
                        failedCaptures[idx] = path
                        maxIdx = maxOf(maxIdx, idx)
                    }
                }
            }
            captureSequence.set(maxIdx)
        }

        val tempDir = FileUtils.getTempScanSessionDir(this, sessionId)
        sessionManager = CameraScanSessionManager(tempDir)

        // C01: Reconcile manifest with disk files to recover any completed or pending pages across process death
        lifecycleScope.launch {
            val reconciled = sessionManager.reconcileSession()
            withContext(Dispatchers.Main) {
                reconciled.committedPages.forEach { (idx, path) ->
                    orderedPageMap[idx] = path
                }
                reconciled.pendingRawFiles.forEach { (idx, path) ->
                    if (!orderedPageMap.containsKey(idx)) {
                        pendingRawCaptures[idx] = path
                    }
                }
                reconciled.failedPages.forEach { (idx, path) ->
                    if (!orderedPageMap.containsKey(idx) && !pendingRawCaptures.containsKey(idx)) {
                        failedCaptures[idx] = path ?: ""
                    }
                }
                if (reconciled.maxSequence > captureSequence.get()) {
                    captureSequence.set(reconciled.maxSequence)
                }

                synchronized(capturedPagePaths) {
                    capturedPagePaths.clear()
                    capturedPagePaths.addAll(orderedPageMap.values)
                }
                if (capturedPagePaths.isNotEmpty()) {
                    updateCapturedPagesUI(capturedPagePaths.last())
                }

                // Resume processing for any pending raw captures discovered
                if (pendingRawCaptures.isNotEmpty()) {
                    val pendingEntries = pendingRawCaptures.entries.toList()
                    for ((idx, rawPath) in pendingEntries) {
                        val rawFile = File(rawPath)
                        if (rawFile.exists() && rawFile.length() > 0L) {
                            inFlightCaptureCount.incrementAndGet()
                            val job = lifecycleScope.launch {
                                try {
                                    processCapturedRawFile(idx, rawFile, tempDir)
                                } finally {
                                    activeCropJobs.remove(idx)
                                    inFlightCaptureCount.decrementAndGet()
                                }
                            }
                            activeCropJobs[idx] = job
                        } else {
                            pendingRawCaptures.remove(idx)
                            failedCaptures[idx] = ""
                        }
                    }
                }
            }
        }

        setupWindowInsets()
        setupListeners()
        setupAiScannerFallback()

        if (capturedPagePaths.isNotEmpty()) {
            updateCapturedPagesUI(capturedPagePaths.last())
        }

        if (isIdCardMode) {
            binding.focusOverlayView.setIdCardMode(true)
            binding.tvCameraHint.setText(R.string.camera_hint_id_card_front)
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SESSION_ID, sessionId)
        outState.putInt(KEY_CAPTURE_SEQ, captureSequence.get())
        outState.putBoolean(KEY_AUTO_CROP, isAutoCropEnabled)
        outState.putInt(KEY_FLASH_MODE, flashMode)

        // Completed pages with indices
        val pageIndices = orderedPageMap.keys.toIntArray()
        val pagePaths = ArrayList(orderedPageMap.values)
        outState.putIntArray(KEY_PAGE_MAP_INDICES, pageIndices)
        outState.putStringArrayList(KEY_PAGE_MAP_PATHS, pagePaths)
        outState.putStringArrayList(KEY_CAPTURED_PAGES, pagePaths)

        // Pending raw captures
        val pendingIndices = pendingRawCaptures.keys.toIntArray()
        val pendingPaths = ArrayList(pendingRawCaptures.values)
        outState.putIntArray(KEY_PENDING_RAW_INDICES, pendingIndices)
        outState.putStringArrayList(KEY_PENDING_RAW_PATHS, pendingPaths)

        // Failed captures
        val failedIndices = failedCaptures.keys.toIntArray()
        val failedPaths = ArrayList(failedCaptures.values)
        outState.putIntArray(KEY_FAILED_CAPTURE_INDICES, failedIndices)
        outState.putStringArrayList(KEY_FAILED_CAPTURE_PATHS, failedPaths)
    }

    private fun setupWindowInsets() {
        val initialTopPadding = EdgeToEdgeInsetsHelper.recordInitialPadding(binding.layoutCameraTopBar)
        val initialBottomPadding = EdgeToEdgeInsetsHelper.recordInitialPadding(binding.layoutCameraBottomBar)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysInsets = EdgeToEdgeInsetsHelper.getSystemBarAndCutoutInsets(insets)

            EdgeToEdgeInsetsHelper.applyTopBarInsets(
                binding.layoutCameraTopBar,
                initialTopPadding,
                sysInsets
            )

            EdgeToEdgeInsetsHelper.applyBottomBarInsets(
                binding.layoutCameraBottomBar,
                initialBottomPadding,
                sysInsets,
                sysInsets.bottom
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
                Toast.makeText(this, getString(R.string.camera_start_error, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takeSuperFastPhoto() {
        val capture = imageCapture ?: return
        if (isCapturing || isFinishingSession) return
        isCapturing = true
        inFlightCaptureCount.incrementAndGet()

        // 1. Phản hồi tức thì bằng âm rung haptic + chớp màn hình (Zero Shutter Lag UX)
        triggerShutterFeedback()

        val tempDir = FileUtils.getTempScanSessionDir(this, sessionId)
        val photoIndex = captureSequence.incrementAndGet()
        val rawFile = File(tempDir, "raw_${System.currentTimeMillis()}_$photoIndex.jpg")
        pendingRawCaptures[photoIndex] = rawFile.absolutePath
        lifecycleScope.launch {
            sessionManager.recordRawCapture(photoIndex, rawFile)
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(rawFile).build()

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    // Mở khóa shutter ngay khi ảnh đã được lưu vào buffer để người dùng có thể chụp tiếp
                    isCapturing = false

                    val job = lifecycleScope.launch {
                        try {
                            processCapturedRawFile(photoIndex, rawFile, tempDir)
                        } finally {
                            activeCropJobs.remove(photoIndex)
                            inFlightCaptureCount.decrementAndGet()
                        }
                    }
                    activeCropJobs[photoIndex] = job
                }

                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                    isCapturing = false
                    pendingRawCaptures.remove(photoIndex)
                    inFlightCaptureCount.decrementAndGet()
                    lifecycleScope.launch {
                        sessionManager.removePage(photoIndex)
                        Toast.makeText(this@CameraScanActivity, getString(R.string.camera_capture_error, exception.message ?: ""), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private suspend fun processCapturedRawFile(photoIndex: Int, rawFile: File, tempDir: File) {
        val finalPageFile = File(tempDir, "page_${photoIndex}_${System.currentTimeMillis()}.jpg")
        sessionManager.recordProcessing(photoIndex, rawFile, finalPageFile)
        val processedOk = withContext(Dispatchers.IO) {
            var ok = false
            try {
                if (isAutoCropEnabled) {
                    val cropped = DocumentEdgeDetector.detectAndCrop(rawFile, finalPageFile)
                    if (cropped && finalPageFile.exists() && finalPageFile.length() > 0L) {
                        ok = true
                    } else {
                        val normalized = DocumentEdgeDetector.normalizeImageOrientation(rawFile, finalPageFile)
                        if (normalized && finalPageFile.exists() && finalPageFile.length() > 0L) {
                            ok = true
                        }
                    }
                } else {
                    val normalized = DocumentEdgeDetector.normalizeImageOrientation(rawFile, finalPageFile)
                    if (normalized && finalPageFile.exists() && finalPageFile.length() > 0L) {
                        ok = true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            if (!ok) {
                try {
                    rawFile.copyTo(finalPageFile, overwrite = true)
                    if (finalPageFile.exists() && finalPageFile.length() > 0L) {
                        ok = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            ok && SafeFileWriter.validateImage(finalPageFile)
        }

        // C01 Two-Phase Commit Protocol:
        // Step 1: Document processing produces and validates finalPageFile (done above)
        // Step 2: sessionManager.commitPageOutput persists COMMITTED status to manifest on disk
        // Step 3: ONLY after commitPageOutput returns true, delete rawFile and update orderedPageMap
        val committed = if (processedOk) {
            sessionManager.commitPageOutput(photoIndex, finalPageFile)
        } else {
            false
        }

        if (committed) {
            pendingRawCaptures.remove(photoIndex)
            failedCaptures.remove(photoIndex)
            // C01: Safely delete rawFile only AFTER finalPageFile is validated AND manifest is committed
            withContext(Dispatchers.IO) {
                try { rawFile.delete() } catch (_: Exception) {}
            }
            orderedPageMap[photoIndex] = finalPageFile.absolutePath
            synchronized(capturedPagePaths) {
                capturedPagePaths.clear()
                capturedPagePaths.addAll(orderedPageMap.values)
            }
            updateCapturedPagesUI(finalPageFile.absolutePath)
        } else {
            // C01: Preserve rawFile on failure for retry/user recovery and record failure in session manifest
            sessionManager.recordFailure(photoIndex, if (rawFile.exists()) rawFile else null)
            pendingRawCaptures.remove(photoIndex)
            failedCaptures[photoIndex] = if (rawFile.exists()) rawFile.absolutePath else ""
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@CameraScanActivity,
                    getString(R.string.camera_capture_error, "Page $photoIndex"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
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
        binding.btnDoneScan.text = getString(R.string.camera_done_with_count, totalPages)

        if (isIdCardMode) {
            if (totalPages == 1) {
                binding.tvCameraHint.setText(R.string.camera_hint_id_card_back)
            } else if (totalPages >= 2) {
                // Tự động chuyển thẳng vào màn hình ghép thẻ khi đủ 2 mặt
                finishScanningSession()
            }
        } else {
            binding.tvCameraHint.text = getString(R.string.camera_hint_pages_captured, totalPages)
        }
    }

    private fun cycleFlashMode() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }

        imageCapture?.flashMode = flashMode

        val (iconRes, toastMsgRes) = when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> Pair(R.drawable.ic_flash_on, R.string.camera_flash_on)
            ImageCapture.FLASH_MODE_AUTO -> Pair(R.drawable.ic_flash_auto, R.string.camera_flash_auto)
            else -> Pair(R.drawable.ic_flash_off, R.string.camera_flash_off)
        }

        binding.btnFlashToggle.setImageResource(iconRes)
        Toast.makeText(this, toastMsgRes, Toast.LENGTH_SHORT).show()
    }

    private fun updateAutoCropUI() {
        if (isAutoCropEnabled) {
            binding.ivAutoCropIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#00C28E"))
            binding.tvAutoCropLabel.setTextColor(Color.parseColor("#00C28E"))
            binding.focusOverlayView.setGuideFrameVisible(true)
            Toast.makeText(this, R.string.camera_auto_crop_enabled, Toast.LENGTH_SHORT).show()
        } else {
            binding.ivAutoCropIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#8E909A"))
            binding.tvAutoCropLabel.setTextColor(Color.parseColor("#8E909A"))
            binding.focusOverlayView.setGuideFrameVisible(false)
            Toast.makeText(this, R.string.camera_auto_crop_disabled, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupAiScannerFallback() {
        scannerHelper = DocumentScannerHelper(this)
        aiScannerLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            scannerHelper.handleScanResult(
                result = result,
                onSuccess = { session ->
                    if (session.tempPagePaths.isNotEmpty() && session.tempPagePaths.size == session.totalPagesExpected) {
                        if (isIdCardMode) {
                            IdCardComposeActivity.start(
                                context = this@CameraScanActivity,
                                pagePaths = session.tempPagePaths
                            )
                        } else {
                            com.tscanner.app.ui.editor.PostScanEditorActivity.start(
                                context = this@CameraScanActivity,
                                sessionId = session.sessionId,
                                pagePaths = session.tempPagePaths
                            )
                        }
                        finish()
                    } else {
                        Toast.makeText(this@CameraScanActivity, R.string.camera_no_pages_scanned, Toast.LENGTH_SHORT).show()
                    }
                },
                onCancelled = {},
                onError = { err ->
                    Toast.makeText(this@CameraScanActivity, getString(R.string.camera_ai_scan_error, err), Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun startGoogleAiScan() {
        if (isIdCardMode) {
            scannerHelper.startIdCardScan(aiScannerLauncher) { err ->
                Toast.makeText(this, getString(R.string.camera_open_ai_id_card_error, err), Toast.LENGTH_LONG).show()
            }
        } else {
            scannerHelper.startScan(aiScannerLauncher) { err ->
                Toast.makeText(this, getString(R.string.camera_open_ai_scanner_error, err), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun finishScanningSession() {
        if (isFinishingSession) return
        isFinishingSession = true
        binding.btnShutterCapture.isEnabled = false

        val needsWaiting = inFlightCaptureCount.get() > 0 || activeCropJobs.isNotEmpty() || isCapturing
        if (needsWaiting) {
            val progressDialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.camera_processing_title)
                .setMessage(R.string.camera_processing_message)
                .setCancelable(false)
                .create()
            progressDialog.show()

            lifecycleScope.launch {
                while (inFlightCaptureCount.get() > 0 || activeCropJobs.isNotEmpty() || isCapturing) {
                    activeCropJobs.values.toList().joinAll()
                    delay(50)
                }
                try {
                    progressDialog.dismiss()
                } catch (_: Exception) {}
                checkFailedCapturesAndProceed()
            }
        } else {
            checkFailedCapturesAndProceed()
        }
    }

    private fun checkFailedCapturesAndProceed() {
        if (failedCaptures.isNotEmpty()) {
            val failedPageListStr = failedCaptures.keys.sorted().joinToString(", ")
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.camera_failed_captures_title)
                .setMessage(getString(R.string.camera_failed_captures_msg, failedPageListStr))
                .setCancelable(false)
                .setPositiveButton(R.string.camera_retry_btn) { _, _ ->
                    retryFailedCaptures()
                }
                .setNegativeButton(R.string.camera_skip_btn) { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        failedCaptures.forEach { (idx, rawPath) ->
                            sessionManager.removePage(idx)
                            if (rawPath.isNotEmpty()) {
                                try { File(rawPath).delete() } catch (_: Exception) {}
                            }
                        }
                    }
                    failedCaptures.clear()
                    proceedToNextScreen()
                }
                .setNeutralButton(R.string.cancel) { _, _ ->
                    isFinishingSession = false
                    binding.btnShutterCapture.isEnabled = true
                }
                .show()
        } else {
            proceedToNextScreen()
        }
    }

    private fun retryFailedCaptures() {
        val tempDir = FileUtils.getTempScanSessionDir(this, sessionId)
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.camera_processing_title)
            .setMessage(R.string.camera_processing_message)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val entries = failedCaptures.entries.toList()
            for ((photoIndex, rawPath) in entries) {
                val rawFile = File(rawPath)
                if (rawFile.exists() && rawFile.length() > 0L) {
                    processCapturedRawFile(photoIndex, rawFile, tempDir)
                } else {
                    sessionManager.removePage(photoIndex)
                    failedCaptures.remove(photoIndex)
                }
            }
            try {
                progressDialog.dismiss()
            } catch (_: Exception) {}
            checkFailedCapturesAndProceed()
        }
    }

    private fun proceedToNextScreen() {
        synchronized(capturedPagePaths) {
            capturedPagePaths.clear()
            capturedPagePaths.addAll(orderedPageMap.values)
        }

        if (capturedPagePaths.isEmpty()) {
            Toast.makeText(this, R.string.camera_no_pages_captured, Toast.LENGTH_SHORT).show()
            isFinishingSession = false
            binding.btnShutterCapture.isEnabled = true
            return
        }

        if (isIdCardMode) {
            IdCardComposeActivity.start(
                context = this,
                pagePaths = ArrayList(capturedPagePaths)
            )
        } else {
            com.tscanner.app.ui.editor.PostScanEditorActivity.start(
                context = this,
                sessionId = sessionId,
                pagePaths = ArrayList(capturedPagePaths)
            )
        }
        finish()
    }

    private fun handleExitAttempt() {
        if (capturedPagePaths.isNotEmpty()) {
            val message = if (isIdCardMode) {
                getString(R.string.camera_exit_dialog_msg_id_card)
            } else {
                getString(R.string.camera_exit_dialog_msg_docs, capturedPagePaths.size)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.camera_exit_dialog_title)
                .setMessage(message)
                .setPositiveButton(R.string.camera_exit_dialog_positive) { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        FileUtils.deleteTempSession(this@CameraScanActivity, sessionId)
                    }
                    finish()
                }
                .setNegativeButton(R.string.camera_exit_dialog_negative, null)
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

        private const val KEY_SESSION_ID = "camera_session_id"
        private const val KEY_CAPTURED_PAGES = "camera_captured_pages"
        private const val KEY_CAPTURE_SEQ = "camera_capture_seq"
        private const val KEY_AUTO_CROP = "camera_auto_crop"
        private const val KEY_FLASH_MODE = "camera_flash_mode"
        private const val KEY_PAGE_MAP_INDICES = "camera_page_map_indices"
        private const val KEY_PAGE_MAP_PATHS = "camera_page_map_paths"
        private const val KEY_PENDING_RAW_INDICES = "camera_pending_raw_indices"
        private const val KEY_PENDING_RAW_PATHS = "camera_pending_raw_paths"
        private const val KEY_FAILED_CAPTURE_INDICES = "camera_failed_capture_indices"
        private const val KEY_FAILED_CAPTURE_PATHS = "camera_failed_capture_paths"

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
