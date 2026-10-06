package com.tscanner.app.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.tscanner.app.R
import com.tscanner.app.databinding.ActivityPdfViewerBinding
import com.tscanner.app.ui.adapter.PdfPageAdapter
import com.tscanner.app.ui.editor.CropRotateActivity
import com.tscanner.app.ui.ocr.OcrResultActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.ui.dialogs.CreatePdfDialog
import com.tscanner.app.ui.dialogs.OcrLanguageSelectionDialog
import com.tscanner.app.ui.dialogs.VipUpgradeDialog
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.DriveAuthorizationAttempt
import com.tscanner.app.utils.FileUtils
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.EdgeToEdgeInsetsHelper
import com.tscanner.app.utils.GoogleLoginAttempt
import com.tscanner.app.utils.VipContinuationAction
import com.tscanner.app.utils.VipLoginContinuationHandler
import com.tscanner.app.utils.MultiPageOcrAggregator
import com.tscanner.app.utils.MultiPageOcrResult
import com.tscanner.app.utils.OcrResult
import com.tscanner.app.utils.PdfConverterHelper
import com.tscanner.app.utils.SyncCatalogResult
import com.tscanner.app.utils.SyncResultPresenter
import com.tscanner.app.utils.TextRecognitionHelper
import com.tscanner.app.utils.WatermarkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class PdfViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfViewerBinding
    private var pdfPath: String? = null
    private var renderedPagePaths = mutableListOf<String>()
    private var pageAdapter: PdfPageAdapter? = null
    private var isNewScan = false
    private var sessionId: String? = null
    private var isWatermarkRemoved = AppAuthManager.isUserVip()
    private var pendingDriveAuthAttempt: DriveAuthorizationAttempt? = null

    private val driveAuthorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val attempt = pendingDriveAuthAttempt
        pendingDriveAuthAttempt = null
        AppAuthManager.handleDrivePermissionResult(
            context = this,
            resultCode = result.resultCode,
            data = result.data,
            attempt = attempt,
            onSuccess = {
                Toast.makeText(this, getString(R.string.drive_permission_granted_toast), Toast.LENGTH_SHORT).show()
                val startUser = AppAuthManager.getCurrentUser()?.id
                val startGen = AppAuthManager.getSessionGeneration()
                AppAuthManager.runPostAuthorizationSync(this) { result ->
                    handlePostAuthSyncResult(result, startUser, startGen)
                }
            },
            onCancelled = {},
            onError = { errorMsg ->
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun requestDrivePermission() {
        try {
            val intent = AppAuthManager.getGoogleDriveSignInIntent(this) { attempt ->
                pendingDriveAuthAttempt = attempt
            }
            driveAuthorizationLauncher.launch(intent)
        } catch (e: Exception) {
            val toCancel = pendingDriveAuthAttempt
            pendingDriveAuthAttempt = null
            AppAuthManager.cancelDriveAuthorizationAttempt(toCancel)
            Toast.makeText(this, getString(R.string.error_occurred_format, e.message.orEmpty()), Toast.LENGTH_SHORT).show()
        }
    }

    private val vipContinuationHandler = VipLoginContinuationHandler()
    private var pendingSignInAttempt: GoogleLoginAttempt? = null
    private var pendingDraftPdfName: String? = null

    private val googleSignInFallbackLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val attempt = pendingSignInAttempt
        val attemptId = attempt?.requestId ?: -1L
        pendingSignInAttempt = null
        AppAuthManager.handleGoogleSignInResult(
            context = this,
            resultCode = result.resultCode,
            data = result.data,
            attempt = attempt,
            onSuccess = { profile ->
                handleSignInSuccess(profile, attemptId)
            },
            onCancelled = {
                vipContinuationHandler.onSignInCancelled(attemptId)
            },
            onError = { errorMsg ->
                vipContinuationHandler.onSignInError(attemptId)
                showSignInErrorDialog(errorMsg)
            }
        )
    }

    private fun performGoogleSignIn(): Boolean {
        if (isFinishing || isDestroyed) return false
        val currentUser = AppAuthManager.getCurrentUser()
        var invocationAttempt: GoogleLoginAttempt? = null
        val started = AppAuthManager.signInWithGoogle(
            activity = this,
            coroutineScope = lifecycleScope,
            expectedOwnerId = currentUser?.id,
            onAttemptCreated = { token ->
                invocationAttempt = token
                pendingSignInAttempt = token
                vipContinuationHandler.bindAttempt(token)
            },
            onFallbackToIntent = {
                val attemptToCancel = invocationAttempt ?: pendingSignInAttempt
                if (isFinishing || isDestroyed) {
                    AppAuthManager.cancelSignInProgress(attemptToCancel)
                    if (pendingSignInAttempt === attemptToCancel) pendingSignInAttempt = null
                    vipContinuationHandler.onSignInCancelled(attemptToCancel?.requestId ?: -1L)
                    return@signInWithGoogle
                }
                try {
                    val signInIntent = AppAuthManager.getGoogleSignInIntent(this)
                    googleSignInFallbackLauncher.launch(signInIntent)
                } catch (ex: Exception) {
                    AppAuthManager.cancelSignInProgress(attemptToCancel)
                    if (pendingSignInAttempt === attemptToCancel) pendingSignInAttempt = null
                    vipContinuationHandler.onSignInError(attemptToCancel?.requestId ?: -1L)
                    showSignInErrorDialog(getString(R.string.cannot_start_google_signin_format, ex.message.orEmpty()))
                }
            },
            onSuccess = { profile ->
                val attemptId = invocationAttempt?.requestId ?: pendingSignInAttempt?.requestId ?: -1L
                if (pendingSignInAttempt === invocationAttempt) {
                    pendingSignInAttempt = null
                }
                handleSignInSuccess(profile, attemptId)
            },
            onCancelled = {
                val attemptId = invocationAttempt?.requestId ?: pendingSignInAttempt?.requestId ?: -1L
                if (pendingSignInAttempt === invocationAttempt) {
                    pendingSignInAttempt = null
                }
                vipContinuationHandler.onSignInCancelled(attemptId)
            },
            onError = { errorMsg ->
                val attemptId = invocationAttempt?.requestId ?: pendingSignInAttempt?.requestId ?: -1L
                if (pendingSignInAttempt === invocationAttempt) {
                    pendingSignInAttempt = null
                }
                vipContinuationHandler.onSignInError(attemptId)
                showSignInErrorDialog(errorMsg)
            }
        )
        if (!started) {
            Log.d("PdfViewerActivity", "Sign-in already in progress, ignoring duplicate tap")
        }
        return started
    }

    private fun handleSignInSuccess(profile: com.tscanner.app.data.model.UserProfile, attemptId: Long = -1L) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, getString(R.string.sign_in_success), Toast.LENGTH_SHORT).show()
        val startUser = AppAuthManager.getCurrentUser()?.id
        val startGen = AppAuthManager.getSessionGeneration()
        AppAuthManager.runPostAuthorizationSync(this) { result ->
            handlePostAuthSyncResult(result, startUser, startGen)
        }
        vipContinuationHandler.onSignInSuccessWithAction(startGen, startUser, attemptId) { action, _, opContext ->
            when (action) {
                VipContinuationAction.UPGRADE -> showVipUpgradeDialog()
                VipContinuationAction.RESTORE -> executeRestorePurchases(opContext)
                VipContinuationAction.NONE -> Unit
            }
        }
    }

    private fun executeRestorePurchases(opContext: com.tscanner.app.utils.billing.BillingOperationContext? = null) {
        if (isFinishing || isDestroyed) return
        val billingManager = BillingManager.getInstance(this)
        Toast.makeText(this, getString(R.string.vip_restore_purchases_btn), Toast.LENGTH_SHORT).show()
        billingManager.restorePurchases(
            opContext = opContext,
            onAuthRequired = { authMessage ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(this, authMessage, Toast.LENGTH_LONG).show()
                }
            },
            onComplete = { success, message ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    if (success && AppAuthManager.isUserVip()) {
                        isWatermarkRemoved = true
                        updateWatermarkUI()
                        Toast.makeText(this, getString(R.string.vip_unlocked_toast), Toast.LENGTH_LONG).show()
                        val draftName = pendingDraftPdfName
                        if (draftName != null) {
                            pendingDraftPdfName = null
                            if (isNewScan) {
                                saveFinalDocument(draftName)
                            } else {
                                createNewPdf(draftName)
                            }
                        }
                    }
                }
            }
        )
    }

    fun startSignInForVipContinuation(
        action: VipContinuationAction = VipContinuationAction.UPGRADE,
        draftName: String? = null,
        opContext: com.tscanner.app.utils.billing.BillingOperationContext? = null,
        onStarted: (() -> Unit)? = null,
        onRefused: (() -> Unit)? = null
    ) {
        if (isFinishing || isDestroyed) {
            onRefused?.invoke()
            return
        }
        if (vipContinuationHandler.isPending) {
            Log.d("PdfViewerActivity", "Continuation already pending, ignoring duplicate tap")
            onRefused?.invoke()
            return
        }
        pendingDraftPdfName = draftName
        val currentGen = AppAuthManager.getSessionGeneration()
        val currentOwner = AppAuthManager.getCurrentUser()?.id
        val currentEpoch = AppAuthManager.getProcessEpoch()
        vipContinuationHandler.requestContinuation(
            action = action,
            sessionGeneration = currentGen,
            initialOwnerId = currentOwner,
            processEpoch = currentEpoch,
            originatingOperationContext = opContext
        )
        val started = performGoogleSignIn()
        if (started) {
            onStarted?.invoke()
        } else {
            vipContinuationHandler.reset()
            onRefused?.invoke()
        }
    }

    private fun showVipUpgradeDialog() {
        if (isFinishing || isDestroyed) return
        VipUpgradeDialog(
            context = this,
            onRequestDrivePermission = { requestDrivePermission() },
            onUpgradeSuccess = {
                if (AppAuthManager.isUserVip()) {
                    isWatermarkRemoved = true
                    updateWatermarkUI()
                    Toast.makeText(this, getString(R.string.vip_unlocked_toast), Toast.LENGTH_LONG).show()
                    val draftName = pendingDraftPdfName
                    if (draftName != null) {
                        pendingDraftPdfName = null
                        if (isNewScan) {
                            saveFinalDocument(draftName)
                        } else {
                            createNewPdf(draftName)
                        }
                    }
                }
            },
            onRequestSignIn = {
                startSignInForVipContinuation(VipContinuationAction.UPGRADE, pendingDraftPdfName)
            },
            onSyncResult = { result ->
                val startUser = AppAuthManager.getCurrentUser()?.id
                val startGen = AppAuthManager.getSessionGeneration()
                handlePostAuthSyncResult(result, startUser, startGen)
            },
            onRequestSignInForAction = { action ->
                startSignInForVipContinuation(action, pendingDraftPdfName)
            },
            onRequestSignInForRecovery = { action, opContext, onStarted, onRefused ->
                startSignInForVipContinuation(action, pendingDraftPdfName, opContext, onStarted, onRefused)
            }
        ).show()
    }

    private fun handlePostAuthSyncResult(result: SyncCatalogResult, originUserId: String?, originSessionGen: Long) {
        if (isFinishing || isDestroyed) return
        SyncResultPresenter.present(
            context = this,
            result = result,
            expectedSessionGeneration = originSessionGen,
            expectedUserId = originUserId,
            isHostValid = { !isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) },
            onRequestDrivePermission = { requestDrivePermission() },
            onRetry = {
                if (!isFinishing && !isDestroyed) {
                    val retryUser = AppAuthManager.getCurrentUser()?.id
                    val retryGen = AppAuthManager.getSessionGeneration()
                    AppAuthManager.runPostAuthorizationSync(this) { retryResult ->
                        handlePostAuthSyncResult(retryResult, retryUser, retryGen)
                    }
                }
            }
        )
    }

    private fun showSignInErrorDialog(errorMsg: String) {
        if (isFinishing || isDestroyed) return
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.ThemeOverlay_TScanner_Dialog)
            .setTitle(R.string.account_sign_in_title)
            .setMessage(errorMsg)
            .setPositiveButton(R.string.retry) { _, _ ->
                performGoogleSignIn()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pageIndex = result.data?.getIntExtra(CropRotateActivity.EXTRA_PAGE_INDEX, -1) ?: -1
            if (pageIndex in renderedPagePaths.indices) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        Glide.get(this@PdfViewerActivity).clearDiskCache()
                    }
                    Glide.get(this@PdfViewerActivity).clearMemory()

                    pageAdapter?.notifyItemChanged(pageIndex)

                    // Asynchronously update the PDF file with the newly cropped page
                    val currentPdf = pdfPath?.let { File(it) }
                    var updateSuccess = false
                    if (currentPdf != null && currentPdf.exists()) {
                        updateSuccess = withContext(Dispatchers.IO) {
                            val ok = PdfConverterHelper.createPdfFromImages(
                                imagePaths = renderedPagePaths,
                                outputFile = currentPdf,
                                addWatermark = WatermarkHelper.shouldApplyWatermark(userWantsRemoved = isWatermarkRemoved)
                            )
                            if (ok) {
                                // Regenerate thumbnail in .thumbnails directory
                                val thumbFile = File(FileUtils.getThumbnailsDir(this@PdfViewerActivity), "thumb_${currentPdf.nameWithoutExtension}.jpg")
                                PdfConverterHelper.renderPdfFirstPage(currentPdf, thumbFile)
                                val repo = DocumentRepo.getInstance(this@PdfViewerActivity)
                                val docId = currentPdf.nameWithoutExtension.removePrefix("doc_")
                                val currentUserId = AppAuthManager.getCurrentUser()?.id
                                repo.markDocumentModified(docId, currentPdf.length(), thumbFile.absolutePath, currentUserId)
                                true
                            } else {
                                false
                            }
                        }
                    }

                    if (updateSuccess) {
                        Toast.makeText(this@PdfViewerActivity, getString(R.string.pdf_page_updated, pageIndex + 1), Toast.LENGTH_SHORT).show()
                    } else if (currentPdf != null) {
                        Toast.makeText(this@PdfViewerActivity, getString(R.string.pdf_page_update_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        vipContinuationHandler.restoreInstanceState(savedInstanceState)
        if (savedInstanceState != null) {
            @Suppress("DEPRECATION")
            pendingDriveAuthAttempt = savedInstanceState.getSerializable(KEY_PENDING_DRIVE_AUTH_ATTEMPT) as? DriveAuthorizationAttempt
            pendingSignInAttempt = GoogleLoginAttempt.fromBundle(savedInstanceState)
            pendingDraftPdfName = savedInstanceState.getString(KEY_PENDING_DRAFT_PDF_NAME)
        }
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val initialToolbarPadding = EdgeToEdgeInsetsHelper.recordInitialPadding(binding.layoutViewerToolbar)
        val initialBottomPadding = EdgeToEdgeInsetsHelper.recordInitialPadding(binding.layoutViewerBottomActions)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysInsets = EdgeToEdgeInsetsHelper.getSystemBarAndCutoutInsets(insets)

            EdgeToEdgeInsetsHelper.applyTopBarInsets(
                binding.layoutViewerToolbar,
                initialToolbarPadding,
                sysInsets
            )

            EdgeToEdgeInsetsHelper.applyBottomBarInsets(
                binding.layoutViewerBottomActions,
                initialBottomPadding,
                sysInsets,
                sysInsets.bottom
            )

            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        isNewScan = intent.getBooleanExtra(EXTRA_IS_NEW_SCAN, false)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        pdfPath = intent.getStringExtra(EXTRA_PDF_PATH)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.pdf_default_title)
        binding.tvViewerTitle.text = title

        val currentPath = pdfPath
        if (!isNewScan && currentPath != null) {
            val currentUserId = AppAuthManager.getCurrentUser()?.id
            val matchingDoc = DocumentRepo.getInstance(this).getDocumentByPdfPath(currentPath)
            if (matchingDoc != null && matchingDoc.ownerId != null && matchingDoc.ownerId != currentUserId) {
                Toast.makeText(this, getString(R.string.document_access_denied), Toast.LENGTH_SHORT).show()
                finish()
                return
            }
        }

        if (isNewScan) {
            binding.btnSaveViewerDoc.text = getString(R.string.btn_save_pdf)
            binding.btnSaveViewerDoc.visibility = View.VISIBLE
            binding.btnShareViewer.visibility = View.GONE
        } else {
            binding.btnSaveViewerDoc.visibility = View.GONE
            binding.btnShareViewer.visibility = View.VISIBLE
        }

        binding.btnBackViewer.setOnClickListener {
            handleBackAction()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackAction()
            }
        })

        binding.btnSaveViewerDoc.setOnClickListener {
            saveFinalDocument()
        }

        binding.btnShareViewer.setOnClickListener {
            sharePdf()
        }

        binding.btnOcrFromPdf.setOnClickListener {
            performOcrOnPages()
        }

        binding.btnConvertLongImgFromPdf.setOnClickListener {
            convertToLongImage()
        }

        binding.btnCreatePdfFromViewer.setOnClickListener {
            if (isNewScan) {
                saveFinalDocument()
            } else {
                createNewPdf()
            }
        }

        binding.btnVipWatermarkViewer.setOnClickListener {
            val isVip = AppAuthManager.isUserVip()
            if (isVip) {
                isWatermarkRemoved = !isWatermarkRemoved
                updateWatermarkUI()
                Toast.makeText(
                    this,
                    if (isWatermarkRemoved) getString(R.string.watermark_toggled_off) else getString(R.string.watermark_toggled_on),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                showVipUpgradeDialog()
            }
        }
        updateWatermarkUI()

        setupRecyclerView()

        val initialPages = intent.getStringArrayListExtra(EXTRA_PAGE_PATHS)
        if (isNewScan && !initialPages.isNullOrEmpty()) {
            renderedPagePaths.clear()
            renderedPagePaths.addAll(initialPages)
            pageAdapter = PdfPageAdapter(renderedPagePaths) { position, pagePath ->
                val intent = CropRotateActivity.createIntent(this@PdfViewerActivity, pagePath, position)
                cropLauncher.launch(intent)
            }
            binding.rvPdfPages.adapter = pageAdapter
        } else {
            loadPdfPages()
        }
    }

    override fun onResume() {
        super.onResume()
        updateWatermarkUI()
    }

    private fun updateWatermarkUI() {
        val isVip = AppAuthManager.isUserVip()
        if (isVip) {
            if (isWatermarkRemoved) {
                binding.ivWatermarkCrown.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary_teal))
                binding.tvWatermarkViewerLabel.text = getString(R.string.watermark_btn_removed_vip)
                binding.tvWatermarkViewerLabel.setTextColor(ContextCompat.getColor(this, R.color.primary_teal))
            } else {
                binding.ivWatermarkCrown.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.vip_gold))
                binding.tvWatermarkViewerLabel.text = getString(R.string.watermark_toggled_on_vip)
                binding.tvWatermarkViewerLabel.setTextColor(ContextCompat.getColor(this, R.color.vip_gold))
            }
        } else {
            binding.ivWatermarkCrown.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.vip_gold))
            binding.tvWatermarkViewerLabel.text = getString(R.string.watermark_btn_remove_vip)
            binding.tvWatermarkViewerLabel.setTextColor(ContextCompat.getColor(this, R.color.vip_gold))
        }
    }

    private fun setupRecyclerView() {
        binding.rvPdfPages.layoutManager = LinearLayoutManager(this)
    }

    private fun loadPdfPages() {
        val path = pdfPath ?: return
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, getString(R.string.pdf_not_found), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.pbLoadingViewer.visibility = View.VISIBLE

        lifecycleScope.launch {
            val previewDir = FileUtils.getPdfPreviewDir(this@PdfViewerActivity)
            val pages = withContext(Dispatchers.IO) {
                PdfConverterHelper.convertPdfToImages(this@PdfViewerActivity, file, previewDir)
            }
            renderedPagePaths.clear()
            renderedPagePaths.addAll(pages)

            binding.pbLoadingViewer.visibility = View.GONE
            pageAdapter = PdfPageAdapter(renderedPagePaths) { position, pagePath ->
                val intent = CropRotateActivity.createIntent(this@PdfViewerActivity, pagePath, position)
                cropLauncher.launch(intent)
            }
            binding.rvPdfPages.adapter = pageAdapter
        }
    }

    private fun sharePdf() {
        val path = pdfPath ?: return
        val file = File(path)
        if (file.exists()) {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
        }
    }

    private fun performOcrOnPages() {
        if (!TextRecognitionHelper.isOcrDocumentLanguageConfigured(this)) {
            OcrLanguageSelectionDialog(this) {
                performOcrOnPages()
            }.show()
            return
        }

        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ocr_extracting_title)
            .setMessage(R.string.ocr_preparing_message)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            try {
                // If pages haven't finished rendering yet, or preview files were cleaned up, render them now on the fly
                val anyPageMissing = renderedPagePaths.isEmpty() || renderedPagePaths.any { !File(it).exists() }
                if (anyPageMissing) {
                    val path = pdfPath
                    if (path != null && File(path).exists()) {
                        progressDialog.setMessage(getString(R.string.ocr_rendering_pdf_pages))
                        val previewDir = FileUtils.getPdfPreviewDir(this@PdfViewerActivity)
                        val pages = withContext(Dispatchers.IO) {
                            PdfConverterHelper.convertPdfToImages(this@PdfViewerActivity, File(path), previewDir)
                        }
                        if (pages.isNotEmpty()) {
                            renderedPagePaths.clear()
                            renderedPagePaths.addAll(pages)
                            pageAdapter?.notifyDataSetChanged()
                        }
                    }
                }

                if (renderedPagePaths.isEmpty()) {
                    Toast.makeText(this@PdfViewerActivity, getString(R.string.ocr_no_pages_found), Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val totalPages = renderedPagePaths.size
                val ocrRequest = TextRecognitionHelper.getDefaultOcrRequest(this@PdfViewerActivity)
                val engineName = TextRecognitionHelper.getPreferredEngineDisplayName(this@PdfViewerActivity)

                val pageResults = mutableListOf<OcrResult>()

                for (index in 0 until totalPages) {
                    if (!isActive) return@launch
                    val pagePath = renderedPagePaths[index]
                    progressDialog.setMessage(getString(R.string.ocr_recognizing_page_progress, index + 1, totalPages, engineName))

                    val result = withContext(Dispatchers.IO) {
                        try {
                            TextRecognitionHelper.recognizeTextFromFileStructured(this@PdfViewerActivity, pagePath, ocrRequest)
                        } catch (c: kotlinx.coroutines.CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            Log.e("PdfViewerActivity", "Error recognizing page ${index + 1}: ${t.message}", t)
                            OcrResult.Failure(t.message ?: "OCR error", t)
                        }
                    }
                    pageResults.add(result)

                    // Dừng xử lý các trang tiếp theo nếu gặp lỗi engine nghiêm trọng
                    if (MultiPageOcrAggregator.isBlockingError(result)) {
                        break
                    }
                }

                if (!isActive) return@launch

                val aggResult = TextRecognitionHelper.aggregateMultiPageResults(this@PdfViewerActivity, pageResults)
                when (aggResult) {
                    is MultiPageOcrResult.PageError -> {
                        val errorMsg = TextRecognitionHelper.formatPageErrorMessage(this@PdfViewerActivity, aggResult)
                        Toast.makeText(this@PdfViewerActivity, errorMsg, Toast.LENGTH_LONG).show()
                    }
                    is MultiPageOcrResult.AllNoText -> {
                        Toast.makeText(this@PdfViewerActivity, getString(R.string.no_text_found), Toast.LENGTH_SHORT).show()
                    }
                    is MultiPageOcrResult.Success -> {
                        TextRecognitionHelper.formatPartialSuccessNotice(this@PdfViewerActivity, aggResult)?.let { notice ->
                            Toast.makeText(this@PdfViewerActivity, notice, Toast.LENGTH_SHORT).show()
                        }
                        val resolvedEngineLabel = if (aggResult.enginesUsed.isNotEmpty()) {
                            aggResult.enginesUsed.joinToString(", ")
                        } else {
                            engineName
                        }
                        OcrResultActivity.start(
                            this@PdfViewerActivity,
                            aggResult,
                            resolvedEngineLabel,
                            imagePaths = ArrayList(renderedPagePaths)
                        )
                    }
                }
            } finally {
                if (progressDialog.isShowing && !isFinishing && !isDestroyed) {
                    progressDialog.dismiss()
                }
            }
        }
    }

    private fun convertToLongImage() {
        val path = pdfPath ?: return
        val file = File(path)
        if (!file.exists()) return

        Toast.makeText(this, getString(R.string.long_image_merging), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val exportDir = FileUtils.getExportsDir(this@PdfViewerActivity)
            val longImgFile = File(exportDir, "LongImage_${System.currentTimeMillis()}.jpg")
            val success = withContext(Dispatchers.IO) {
                PdfConverterHelper.convertPdfToLongImage(
                    context = this@PdfViewerActivity,
                    pdfFile = file,
                    outputFile = longImgFile,
                    addWatermark = WatermarkHelper.shouldApplyWatermark(userWantsRemoved = isWatermarkRemoved)
                )
            }

            if (success) {
                Toast.makeText(this@PdfViewerActivity, getString(R.string.long_image_success), Toast.LENGTH_LONG).show()
                val uri = FileProvider.getUriForFile(
                    this@PdfViewerActivity,
                    "$packageName.provider",
                    longImgFile
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_long_image)))
            } else {
                Toast.makeText(this@PdfViewerActivity, getString(R.string.long_image_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createNewPdf(draftName: String? = null) {
        if (renderedPagePaths.isEmpty() && (pdfPath == null || !File(pdfPath!!).exists())) {
            Toast.makeText(this, getString(R.string.pdf_pages_not_ready), Toast.LENGTH_SHORT).show()
            return
        }

        val defaultTitle = draftName ?: binding.tvViewerTitle.text.toString().trim().ifEmpty {
            "PDF_" + FileUtils.formatDate(System.currentTimeMillis()).replace('/', '-').replace(':', '-')
        }

        CreatePdfDialog(
            context = this,
            defaultName = defaultTitle,
            isWatermarkRemoved = isWatermarkRemoved,
            onWatermarkToggled = { removed ->
                isWatermarkRemoved = removed
                updateWatermarkUI()
            },
            onRequestDrivePermission = { requestDrivePermission() },
            onRequestSignIn = { currentTypedName ->
                startSignInForVipContinuation(VipContinuationAction.UPGRADE, currentTypedName)
            },
            onRequestSignInForAction = { action, currentTypedName ->
                startSignInForVipContinuation(action, currentTypedName)
            },
            onRequestSignInForRecovery = { action, currentTypedName, opContext, onStarted, onRefused ->
                startSignInForVipContinuation(action, currentTypedName, opContext, onStarted, onRefused)
            },
            onSyncResult = { result ->
                val startUser = AppAuthManager.getCurrentUser()?.id
                val startGen = AppAuthManager.getSessionGeneration()
                handlePostAuthSyncResult(result, startUser, startGen)
            }
        ) { fileName ->
            val sanitized = FileUtils.sanitizeFileName(fileName.removeSuffix(".pdf"))
            val cleanName = "$sanitized.pdf"
            val exportDir = FileUtils.getExportsDir(this)
            exportDir.mkdirs()
            val outputFile = File(exportDir, cleanName)

            Toast.makeText(this, getString(R.string.pdf_creating), Toast.LENGTH_SHORT).show()

            lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) {
                    if (renderedPagePaths.isNotEmpty()) {
                        PdfConverterHelper.createPdfFromImages(
                            imagePaths = renderedPagePaths,
                            outputFile = outputFile,
                            addWatermark = WatermarkHelper.shouldApplyWatermark(userWantsRemoved = isWatermarkRemoved)
                        )
                    } else if (pdfPath != null && File(pdfPath!!).exists() && File(pdfPath!!).length() > 0) {
                        val sourceFile = File(pdfPath!!)
                        try {
                            outputFile.parentFile?.mkdirs()
                            sourceFile.copyTo(outputFile, overwrite = true)
                            true
                        } catch (e: Exception) {
                            e.printStackTrace()
                            false
                        }
                    } else {
                        false
                    }
                }

                if (success && outputFile.exists()) {
                    // Lưu một bản vào thư mục Tải về (Downloads) của thiết bị để người dùng dễ tìm
                    FileUtils.savePdfToDownloads(this@PdfViewerActivity, outputFile)

                    // Lưu vào DocumentRepo để quản lý trong danh sách tài liệu
                    val newDocId = UUID.randomUUID().toString()
                    val userTitle = sanitized.ifEmpty { outputFile.nameWithoutExtension }
                    val thumb = renderedPagePaths.firstOrNull() ?: run {
                        val thumbFile = File(FileUtils.getThumbnailsDir(this@PdfViewerActivity), "thumb_${newDocId}.jpg")
                        if (PdfConverterHelper.renderPdfFirstPage(outputFile, thumbFile)) thumbFile.absolutePath else null
                    }
                    val docItem = DocumentItem(
                        id = newDocId,
                        title = userTitle,
                        pdfPath = outputFile.absolutePath,
                        thumbnailPath = thumb,
                        pagePaths = emptyList(),
                        pageCount = if (renderedPagePaths.isNotEmpty()) renderedPagePaths.size else 1,
                        sizeBytes = outputFile.length(),
                        createdAt = System.currentTimeMillis(),
                        ownerId = AppAuthManager.getCurrentUser()?.id
                    )
                    val addSuccess = DocumentRepo.getInstance(this@PdfViewerActivity).addDocument(docItem)

                    if (addSuccess) {
                        val toastMsg = if (AppAuthManager.isUserVip()) {
                            getString(R.string.pdf_created_with_drive_backup, outputFile.name)
                        } else {
                            getString(R.string.pdf_created_success, outputFile.name)
                        }
                        Toast.makeText(this@PdfViewerActivity, toastMsg, Toast.LENGTH_LONG).show()

                        // Hiển thị dialog chia sẻ / mở file
                        showPdfSuccessDialog(outputFile, userTitle)
                    } else {
                        Toast.makeText(this@PdfViewerActivity, getString(R.string.pdf_save_error), Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this@PdfViewerActivity, getString(R.string.pdf_create_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }.show()
    }

    private fun handleBackAction() {
        finish()
    }

    private fun saveFinalDocument(draftName: String? = null) {
        if (renderedPagePaths.isEmpty() && (pdfPath == null || !File(pdfPath!!).exists())) {
            Toast.makeText(this, getString(R.string.pdf_save_not_ready), Toast.LENGTH_SHORT).show()
            return
        }

        val defaultTitle = draftName ?: binding.tvViewerTitle.text.toString().trim().ifEmpty {
            "${getString(R.string.document_title_prefix)} " + FileUtils.formatDate(System.currentTimeMillis()).replace('/', '-').replace(':', '-')
        }

        CreatePdfDialog(
            context = this,
            defaultName = defaultTitle,
            isWatermarkRemoved = isWatermarkRemoved,
            onWatermarkToggled = { removed ->
                isWatermarkRemoved = removed
                updateWatermarkUI()
            },
            onRequestDrivePermission = { requestDrivePermission() },
            onRequestSignIn = { currentTypedName ->
                startSignInForVipContinuation(VipContinuationAction.UPGRADE, currentTypedName)
            },
            onRequestSignInForAction = { action, currentTypedName ->
                startSignInForVipContinuation(action, currentTypedName)
            },
            onRequestSignInForRecovery = { action, currentTypedName, opContext, onStarted, onRefused ->
                startSignInForVipContinuation(action, currentTypedName, opContext, onStarted, onRefused)
            },
            onSyncResult = { result ->
                val startUser = AppAuthManager.getCurrentUser()?.id
                val startGen = AppAuthManager.getSessionGeneration()
                handlePostAuthSyncResult(result, startUser, startGen)
            }
        ) { fileName ->
            val sanitized = FileUtils.sanitizeFileName(fileName.removeSuffix(".pdf"))
            val newDocId = UUID.randomUUID().toString()
            val userEnteredTitle = sanitized.ifEmpty { "${getString(R.string.document_title_prefix)} " + FileUtils.formatDate(System.currentTimeMillis()).replace('/', '-').replace(':', '-') }
            val docDir = FileUtils.getDocumentsDir(this)
            val finalPdfFile = File(docDir, "doc_${newDocId}.pdf")

            Toast.makeText(this, getString(R.string.pdf_saving_to_device), Toast.LENGTH_SHORT).show()

            lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) {
                    if (renderedPagePaths.isNotEmpty()) {
                        PdfConverterHelper.createPdfFromImages(
                            imagePaths = renderedPagePaths,
                            outputFile = finalPdfFile,
                            addWatermark = WatermarkHelper.shouldApplyWatermark(userWantsRemoved = isWatermarkRemoved)
                        )
                    } else if (pdfPath != null && File(pdfPath!!).exists()) {
                        try {
                            File(pdfPath!!).copyTo(finalPdfFile, overwrite = true)
                            true
                        } catch (e: Exception) {
                            e.printStackTrace()
                            false
                        }
                    } else {
                        false
                    }
                }

                if (success && finalPdfFile.exists()) {
                    // Create thumbnail in .thumbnails folder
                    val thumbsDir = FileUtils.getThumbnailsDir(this@PdfViewerActivity)
                    val thumbFile = File(thumbsDir, "thumb_${newDocId}.jpg")
                    val thumbPath = withContext(Dispatchers.IO) {
                        if (PdfConverterHelper.renderPdfFirstPage(finalPdfFile, thumbFile)) {
                            thumbFile.absolutePath
                        } else null
                    }

                    // Save to DocumentRepo (Unique internal file doc_${newDocId}.pdf, user's chosen title)
                    val docItem = DocumentItem(
                        id = newDocId,
                        title = userEnteredTitle,
                        pdfPath = finalPdfFile.absolutePath,
                        thumbnailPath = thumbPath,
                        pagePaths = emptyList(), // Physical pages are stored inside the PDF
                        pageCount = if (renderedPagePaths.isNotEmpty()) renderedPagePaths.size else 1,
                        sizeBytes = finalPdfFile.length(),
                        createdAt = System.currentTimeMillis(),
                        ownerId = AppAuthManager.getCurrentUser()?.id
                    )
                    val addSuccess = DocumentRepo.getInstance(this@PdfViewerActivity).addDocument(docItem)
                    if (!addSuccess) {
                        Toast.makeText(this@PdfViewerActivity, getString(R.string.pdf_save_error), Toast.LENGTH_LONG).show()
                        return@launch
                    }

                    // Switch viewer to new saved PDF source and create dedicated preview BEFORE cleaning session (A03, S08)
                    val previewDir = FileUtils.getPdfPreviewDir(this@PdfViewerActivity)
                    val expectedPageCount = renderedPagePaths.size
                    val newPreviewResult = withContext(Dispatchers.IO) {
                        PdfConverterHelper.convertPdfToImagesStructured(this@PdfViewerActivity, finalPdfFile, previewDir)
                    }
                    if (newPreviewResult is PdfConverterHelper.PdfToImagesResult.Success &&
                        newPreviewResult.imagePaths.size == expectedPageCount
                    ) {
                        renderedPagePaths.clear()
                        renderedPagePaths.addAll(newPreviewResult.imagePaths)
                        pageAdapter = PdfPageAdapter(renderedPagePaths) { position, pagePath ->
                            val intent = CropRotateActivity.createIntent(this@PdfViewerActivity, pagePath, position)
                            cropLauncher.launch(intent)
                        }
                        binding.rvPdfPages.adapter = pageAdapter

                        // Clean up temporary camera capture session only after viewer has switched to persistent preview
                        sessionId?.let { sid ->
                            withContext(Dispatchers.IO) {
                                com.tscanner.app.data.repository.PostScanSessionRepository.getInstance(this@PdfViewerActivity).completeSession(sid)
                                FileUtils.deleteTempSession(this@PdfViewerActivity, sid)
                            }
                        }
                        sessionId = null
                    } else {
                        Log.w("PdfViewerActivity", "Preview render incomplete, keeping session files for safety")
                    }

                    setResult(Activity.RESULT_OK)

                    // Update UI state to normal viewing mode
                    isNewScan = false
                    pdfPath = finalPdfFile.absolutePath
                    binding.tvViewerTitle.text = userEnteredTitle
                    binding.btnSaveViewerDoc.visibility = View.GONE
                    binding.btnShareViewer.visibility = View.VISIBLE

                    val saveMsg = if (AppAuthManager.isUserVip()) {
                        getString(R.string.pdf_saved_with_drive_backup)
                    } else {
                        getString(R.string.pdf_saved_success)
                    }
                    Toast.makeText(this@PdfViewerActivity, saveMsg, Toast.LENGTH_SHORT).show()
                    showPdfSuccessDialog(finalPdfFile, userEnteredTitle)
                } else {
                    Toast.makeText(this@PdfViewerActivity, getString(R.string.pdf_save_error), Toast.LENGTH_SHORT).show()
                }
            }
        }.show()
    }

    private fun showPdfSuccessDialog(pdfFile: File, displayTitle: String = pdfFile.name) {
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", pdfFile)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pdf_success_dialog_title)
            .setMessage(getString(R.string.pdf_success_dialog_message, displayTitle))
            .setPositiveButton(R.string.share) { _, _ ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_pdf_title)))
            }
            .setNeutralButton(R.string.open_file) { _, _ ->
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(viewIntent)
                } catch (e: Exception) {
                    Toast.makeText(this, getString(R.string.pdf_no_reader_app), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(KEY_PENDING_DRIVE_AUTH_ATTEMPT, pendingDriveAuthAttempt)
        vipContinuationHandler.saveInstanceState(outState)
        pendingSignInAttempt?.writeToBundle(outState)
        outState.putString(KEY_PENDING_DRAFT_PDF_NAME, pendingDraftPdfName)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            val previewDir = FileUtils.getPdfPreviewDir(this)
            FileUtils.deleteDirContents(previewDir)
        }
    }

    companion object {
        const val EXTRA_PDF_PATH = "extra_pdf_path"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_PAGE_PATHS = "extra_page_paths"
        const val EXTRA_IS_NEW_SCAN = "extra_is_new_scan"
        const val EXTRA_SESSION_ID = "extra_session_id"
        private const val KEY_PENDING_DRIVE_AUTH_ATTEMPT = "key_pending_drive_auth_attempt"
        private const val KEY_PENDING_SIGN_IN_REQ_ID = "key_pending_sign_in_req_id"
        private const val KEY_PENDING_SIGN_IN_SESSION_GEN = "key_pending_sign_in_session_gen"
        private const val KEY_PENDING_DRAFT_PDF_NAME = "key_pending_draft_pdf_name"

        fun start(context: Context, pdfPath: String, title: String, pagePaths: List<String>? = null) {
            val intent = Intent(context, PdfViewerActivity::class.java).apply {
                putExtra(EXTRA_PDF_PATH, pdfPath)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_IS_NEW_SCAN, false)
                if (!pagePaths.isNullOrEmpty()) {
                    putStringArrayListExtra(EXTRA_PAGE_PATHS, ArrayList(pagePaths))
                }
            }
            context.startActivity(intent)
        }

        fun startForNewScan(context: Context, sessionId: String, pdfPath: String?, pagePaths: List<String>) {
            context.startActivity(createIntentForNewScan(context, sessionId, pdfPath, ArrayList(pagePaths)))
        }

        fun createIntentForNewScan(
            context: Context,
            sessionId: String,
            pdfPath: String?,
            pagePaths: ArrayList<String>,
            title: String? = null
        ): Intent {
            val defaultTitle = title?.ifEmpty { null } ?: ("${context.getString(R.string.document_title_prefix)} " + FileUtils.formatDate(System.currentTimeMillis()).replace('/', '-').replace(':', '-'))
            return Intent(context, PdfViewerActivity::class.java).apply {
                putExtra(EXTRA_IS_NEW_SCAN, true)
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_TITLE, defaultTitle)
                if (pdfPath != null) {
                    putExtra(EXTRA_PDF_PATH, pdfPath)
                }
                putStringArrayListExtra(EXTRA_PAGE_PATHS, pagePaths)
            }
        }
    }
}
