package com.tscanner.app.ui.idcard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tscanner.app.R
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.databinding.ActivityIdCardComposeBinding
import com.tscanner.app.ui.dialogs.VipUpgradeDialog
import com.tscanner.app.ui.editor.CropRotateActivity
import com.tscanner.app.ui.viewer.PdfViewerActivity
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.DriveAuthorizationAttempt
import com.tscanner.app.utils.EdgeToEdgeInsetsHelper
import com.tscanner.app.utils.FileUtils
import com.tscanner.app.utils.GoogleLoginAttempt
import com.tscanner.app.utils.VipLoginContinuationHandler
import com.tscanner.app.utils.VipContinuationAction
import com.tscanner.app.utils.IdCardComposeConfig
import com.tscanner.app.utils.IdCardComposerHelper
import com.tscanner.app.utils.IdCardLayoutMode
import com.tscanner.app.utils.IdCardScaleMode
import com.tscanner.app.utils.PdfConverterHelper
import com.tscanner.app.utils.SyncCatalogResult
import com.tscanner.app.utils.SyncResultPresenter
import com.tscanner.app.utils.WatermarkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class IdCardComposeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIdCardComposeBinding

    private var frontImagePath: String? = null
    private var backImagePath: String? = null

    private var frontBitmap: Bitmap? = null
    private var backBitmap: Bitmap? = null

    private var currentConfig = IdCardComposeConfig()
    private var currentPreviewBitmap: Bitmap? = null
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

    private val googleSignInFallbackLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val attempt = pendingSignInAttempt
        pendingSignInAttempt = null
        val attemptId = attempt?.requestId ?: -1L
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
            android.util.Log.d("IdCardComposeActivity", "Sign-in already in progress, ignoring duplicate tap")
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
                        currentConfig = currentConfig.copy(addWatermark = false)
                        updateVipWatermarkUI(true)
                        updatePreview()
                        Toast.makeText(this, getString(R.string.vip_all_watermarks_removed_toast), Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    fun startSignInForVipContinuation(
        action: VipContinuationAction = VipContinuationAction.UPGRADE,
        opContext: com.tscanner.app.utils.billing.BillingOperationContext? = null,
        onStarted: (() -> Unit)? = null,
        onRefused: (() -> Unit)? = null
    ) {
        if (isFinishing || isDestroyed) {
            onRefused?.invoke()
            return
        }
        if (vipContinuationHandler.isPending) {
            android.util.Log.d("IdCardComposeActivity", "Continuation already pending, ignoring duplicate tap")
            onRefused?.invoke()
            return
        }
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
                val newVip = AppAuthManager.isUserVip()
                if (newVip) {
                    currentConfig = currentConfig.copy(addWatermark = false)
                    updateVipWatermarkUI(true)
                    updatePreview()
                    Toast.makeText(this, getString(R.string.vip_all_watermarks_removed_toast), Toast.LENGTH_LONG).show()
                }
            },
            onRequestSignIn = {
                startSignInForVipContinuation(VipContinuationAction.UPGRADE)
            },
            onRequestSignInForAction = { action ->
                startSignInForVipContinuation(action)
            },
            onRequestSignInForRecovery = { action, opContext, onStarted, onRefused ->
                startSignInForVipContinuation(action, opContext, onStarted, onRefused)
            },
            onSyncResult = { result ->
                val startUser = AppAuthManager.getCurrentUser()?.id
                val startGen = AppAuthManager.getSessionGeneration()
                handlePostAuthSyncResult(result, startUser, startGen)
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
            lifecycleScope.launch {
                if (pageIndex == 0 && frontImagePath != null) {
                    frontBitmap?.recycle()
                    frontBitmap = withContext(Dispatchers.IO) {
                        IdCardComposerHelper.decodeSampledBitmap(frontImagePath!!)
                    }
                } else if (pageIndex == 1 && backImagePath != null) {
                    backBitmap?.recycle()
                    backBitmap = withContext(Dispatchers.IO) {
                        IdCardComposerHelper.decodeSampledBitmap(backImagePath!!)
                    }
                }
                updatePreview()
            }
        }
    }

    private val pickFrontImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            handleFrontImagePicked(uri)
        }
    }

    private val pickBackImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            handleBackImagePicked(uri)
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
        }
        binding = ActivityIdCardComposeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val initialToolbarPadding = EdgeToEdgeInsetsHelper.recordInitialPadding(binding.layoutComposeToolbar)
        val initialBottomPadding = EdgeToEdgeInsetsHelper.recordInitialPadding(binding.layoutBottomControls)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysInsets = EdgeToEdgeInsetsHelper.getSystemBarAndCutoutInsets(insets)

            EdgeToEdgeInsetsHelper.applyTopBarInsets(
                binding.layoutComposeToolbar,
                initialToolbarPadding,
                sysInsets
            )

            EdgeToEdgeInsetsHelper.applyBottomBarInsets(
                binding.layoutBottomControls,
                initialBottomPadding,
                sysInsets,
                sysInsets.bottom
            )

            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        val restoredDraft = IdCardSessionDraft.fromBundle(
            savedInstanceState?.getBundle(IdCardSessionDraft.EXTRA_SESSION_DRAFT)
        )
        if (restoredDraft != null) {
            frontImagePath = restoredDraft.frontImagePath
            backImagePath = restoredDraft.backImagePath
            currentConfig = restoredDraft.config
            updateVipWatermarkUI(AppAuthManager.isUserVip())
        } else {
            // Read paths from intent
            frontImagePath = intent.getStringExtra(EXTRA_FRONT_PATH)
            backImagePath = intent.getStringExtra(EXTRA_BACK_PATH)

            val pagePaths = intent.getStringArrayListExtra(EXTRA_PAGE_PATHS)
            if (!pagePaths.isNullOrEmpty()) {
                if (frontImagePath == null) frontImagePath = pagePaths.getOrNull(0)
                if (backImagePath == null && pagePaths.size > 1) backImagePath = pagePaths.getOrNull(1)
            }

            if (frontImagePath == null && backImagePath != null) {
                frontImagePath = backImagePath
                backImagePath = null
            }

            if (backImagePath == null) {
                currentConfig = currentConfig.copy(layoutMode = IdCardLayoutMode.A4_PORTRAIT_SINGLE_SIDE)
            }

            initWatermarkConfig()
        }

        updateLayoutTabs()
        updateScaleTabs()
        updateBorderButton(currentConfig.showCutBorder)
        setupListeners()
        loadImages()
    }

    private fun handleFrontImagePicked(uri: Uri) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                FileUtils.copyUriToAppStorage(this@IdCardComposeActivity, uri, FileUtils.getImagesDir(this@IdCardComposeActivity), "idcard_front")
            }
            if (file != null) {
                frontImagePath = file.absolutePath
                frontBitmap?.recycle()
                frontBitmap = withContext(Dispatchers.IO) {
                    IdCardComposerHelper.decodeSampledBitmap(file.absolutePath)
                }
                updatePreview()
            } else {
                Toast.makeText(this@IdCardComposeActivity, getString(R.string.cannot_load_image), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleBackImagePicked(uri: Uri) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                FileUtils.copyUriToAppStorage(this@IdCardComposeActivity, uri, FileUtils.getImagesDir(this@IdCardComposeActivity), "idcard_back")
            }
            if (file != null) {
                backImagePath = file.absolutePath
                backBitmap?.recycle()
                backBitmap = withContext(Dispatchers.IO) {
                    IdCardComposerHelper.decodeSampledBitmap(file.absolutePath)
                }
                if (currentConfig.layoutMode == IdCardLayoutMode.A4_PORTRAIT_SINGLE_SIDE) {
                    currentConfig = currentConfig.copy(layoutMode = IdCardLayoutMode.A4_PORTRAIT_TWO_SIDES)
                    updateLayoutTabs()
                }
                updatePreview()
            } else {
                Toast.makeText(this@IdCardComposeActivity, getString(R.string.cannot_load_image), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initWatermarkConfig() {
        val isVip = AppAuthManager.isUserVip()
        currentConfig = currentConfig.copy(addWatermark = !isVip)
        updateVipWatermarkUI(isVip)
    }

    private fun updateVipWatermarkUI(isVip: Boolean) {
        if (isVip) {
            if (!currentConfig.addWatermark) {
                binding.ivVipWatermarkIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary_teal))
                binding.tvVipWatermarkLabel.text = getString(R.string.watermark_btn_removed_vip)
                binding.tvVipWatermarkLabel.setTextColor(ContextCompat.getColor(this, R.color.primary_teal))
            } else {
                binding.ivVipWatermarkIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.vip_gold))
                binding.tvVipWatermarkLabel.text = getString(R.string.watermark_btn_enable_vip)
                binding.tvVipWatermarkLabel.setTextColor(ContextCompat.getColor(this, R.color.vip_gold))
            }
        } else {
            binding.ivVipWatermarkIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.vip_gold))
            binding.tvVipWatermarkLabel.text = getString(R.string.watermark_btn_remove_vip)
            binding.tvVipWatermarkLabel.setTextColor(ContextCompat.getColor(this, R.color.vip_gold))
        }
    }

    private fun loadImages() {
        binding.pbComposeLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                frontImagePath?.let { path ->
                    frontBitmap = IdCardComposerHelper.decodeSampledBitmap(path)
                }
                backImagePath?.let { path ->
                    backBitmap = IdCardComposerHelper.decodeSampledBitmap(path)
                }
            }
            binding.pbComposeLoading.visibility = View.GONE
            updatePreview()
        }
    }

    private fun updatePreview() {
        binding.pbComposeLoading.visibility = View.VISIBLE
        val effectiveWatermark = WatermarkHelper.shouldApplyWatermark(userWantsRemoved = !currentConfig.addWatermark)
        val previewConfig = currentConfig.copy(addWatermark = effectiveWatermark)
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                IdCardComposerHelper.renderA4Bitmap(
                    frontBmp = frontBitmap,
                    backBmp = backBitmap,
                    config = previewConfig,
                    isHighRes = false
                )
            }
            val oldBmp = currentPreviewBitmap
            currentPreviewBitmap = bmp
            binding.ivA4Preview.setImageBitmap(bmp)
            oldBmp?.recycle()
            binding.pbComposeLoading.visibility = View.GONE
        }
    }

    private fun setupListeners() {
        binding.btnBackCompose.setOnClickListener {
            finish()
        }

        // Layout Mode
        binding.tabLayoutPortrait.setOnClickListener {
            currentConfig = currentConfig.copy(layoutMode = IdCardLayoutMode.A4_PORTRAIT_TWO_SIDES)
            updateLayoutTabs()
            updatePreview()
        }

        binding.tabLayoutLandscape.setOnClickListener {
            currentConfig = currentConfig.copy(layoutMode = IdCardLayoutMode.A4_LANDSCAPE_TWO_SIDES)
            updateLayoutTabs()
            updatePreview()
        }

        binding.tabLayoutSingle.setOnClickListener {
            currentConfig = currentConfig.copy(layoutMode = IdCardLayoutMode.A4_PORTRAIT_SINGLE_SIDE)
            updateLayoutTabs()
            updatePreview()
        }

        // Scale Mode
        binding.tabScaleActual.setOnClickListener {
            currentConfig = currentConfig.copy(scaleMode = IdCardScaleMode.ACTUAL_SIZE)
            updateScaleTabs()
            updatePreview()
        }

        binding.tabScaleFit.setOnClickListener {
            currentConfig = currentConfig.copy(scaleMode = IdCardScaleMode.FIT_PAGE)
            updateScaleTabs()
            updatePreview()
        }

        // Border Toggle
        binding.btnToggleBorder.setOnClickListener {
            val newBorder = !currentConfig.showCutBorder
            currentConfig = currentConfig.copy(showCutBorder = newBorder)
            updateBorderButton(newBorder)
            updatePreview()
        }

        // Swap Sides
        binding.btnSwapSides.setOnClickListener {
            if (frontBitmap == null || backBitmap == null) {
                Toast.makeText(this, getString(R.string.id_card_swap_need_both_sides), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Swap paths
            val tempPath = frontImagePath
            frontImagePath = backImagePath
            backImagePath = tempPath

            // Swap bitmaps
            val tempBmp = frontBitmap
            frontBitmap = backBitmap
            backBitmap = tempBmp

            Toast.makeText(this, getString(R.string.id_card_swapped_toast), Toast.LENGTH_SHORT).show()
            updatePreview()
        }

        // Crop Front
        binding.btnCropFront.setOnClickListener {
            val path = frontImagePath
            if (path != null && File(path).exists()) {
                val options = arrayOf(
                    getString(R.string.id_card_crop_rotate_front_option),
                    getString(R.string.id_card_pick_other_front_option)
                )
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.id_card_front_title))
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> {
                                val intent = CropRotateActivity.createIntent(this, path, 0)
                                cropLauncher.launch(intent)
                            }
                            1 -> pickFrontImageLauncher.launch("image/*")
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            } else {
                pickFrontImageLauncher.launch("image/*")
            }
        }

        // Crop Back
        binding.btnCropBack.setOnClickListener {
            val path = backImagePath
            if (path != null && File(path).exists()) {
                val options = arrayOf(
                    getString(R.string.id_card_crop_rotate_back_option),
                    getString(R.string.id_card_pick_other_back_option)
                )
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.id_card_back_title))
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> {
                                val intent = CropRotateActivity.createIntent(this, path, 1)
                                cropLauncher.launch(intent)
                            }
                            1 -> pickBackImageLauncher.launch("image/*")
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            } else {
                pickBackImageLauncher.launch("image/*")
            }
        }

        // Print
        binding.btnPrintA4.setOnClickListener {
            val preview = currentPreviewBitmap
            if (preview != null && (frontBitmap != null || backBitmap != null)) {
                lifecycleScope.launch {
                    val highResBmp = withContext(Dispatchers.IO) {
                        IdCardComposerHelper.renderA4Bitmap(frontBitmap, backBitmap, currentConfig, isHighRes = true)
                    }
                    IdCardComposerHelper.printDocument(this@IdCardComposeActivity, highResBmp, "In_CCCD_A4")
                }
            } else {
                Toast.makeText(this, getString(R.string.id_card_print_need_at_least_one), Toast.LENGTH_SHORT).show()
            }
        }

        // VIP Watermark button
        binding.btnVipWatermark.setOnClickListener {
            val isVip = AppAuthManager.isUserVip()
            if (isVip) {
                // VIP can toggle or keep off
                currentConfig = currentConfig.copy(addWatermark = !currentConfig.addWatermark)
                updateVipWatermarkUI(true)
                updatePreview()
                Toast.makeText(
                    this,
                    if (!currentConfig.addWatermark) getString(R.string.watermark_toggled_off) else getString(R.string.watermark_toggled_on),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                // Free user clicks -> Show VIP upgrade dialog
                showVipUpgradeDialog()
            }
        }

        // Save & Export
        binding.btnSaveCompose.setOnClickListener {
            showSaveOptionsDialog()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val draft = IdCardSessionDraft(
            frontImagePath = frontImagePath,
            backImagePath = backImagePath,
            config = currentConfig
        )
        outState.putBundle(IdCardSessionDraft.EXTRA_SESSION_DRAFT, draft.toBundle())
        outState.putSerializable(KEY_PENDING_DRIVE_AUTH_ATTEMPT, pendingDriveAuthAttempt)
        vipContinuationHandler.saveInstanceState(outState)
        pendingSignInAttempt?.writeToBundle(outState)
    }

    override fun onResume() {
        super.onResume()
        val isVip = AppAuthManager.isUserVip()
        updateVipWatermarkUI(isVip)
        updatePreview()
    }

    private fun updateLayoutTabs() {
        val selectedBg = R.drawable.bg_chip_selected
        val unselectedBg = R.drawable.bg_chip_unselected
        val tealColor = ContextCompat.getColor(this, R.color.primary_teal)
        val mutedColor = ContextCompat.getColor(this, R.color.text_secondary)

        when (currentConfig.layoutMode) {
            IdCardLayoutMode.A4_PORTRAIT_TWO_SIDES -> {
                binding.tabLayoutPortrait.setBackgroundResource(selectedBg)
                binding.tabLayoutPortrait.setTextColor(tealColor)
                binding.tabLayoutLandscape.setBackgroundResource(unselectedBg)
                binding.tabLayoutLandscape.setTextColor(mutedColor)
                binding.tabLayoutSingle.setBackgroundResource(unselectedBg)
                binding.tabLayoutSingle.setTextColor(mutedColor)
            }
            IdCardLayoutMode.A4_LANDSCAPE_TWO_SIDES -> {
                binding.tabLayoutPortrait.setBackgroundResource(unselectedBg)
                binding.tabLayoutPortrait.setTextColor(mutedColor)
                binding.tabLayoutLandscape.setBackgroundResource(selectedBg)
                binding.tabLayoutLandscape.setTextColor(tealColor)
                binding.tabLayoutSingle.setBackgroundResource(unselectedBg)
                binding.tabLayoutSingle.setTextColor(mutedColor)
            }
            IdCardLayoutMode.A4_PORTRAIT_SINGLE_SIDE -> {
                binding.tabLayoutPortrait.setBackgroundResource(unselectedBg)
                binding.tabLayoutPortrait.setTextColor(mutedColor)
                binding.tabLayoutLandscape.setBackgroundResource(unselectedBg)
                binding.tabLayoutLandscape.setTextColor(mutedColor)
                binding.tabLayoutSingle.setBackgroundResource(selectedBg)
                binding.tabLayoutSingle.setTextColor(tealColor)
            }
        }
    }

    private fun updateScaleTabs() {
        val selectedBg = R.drawable.bg_chip_selected
        val unselectedBg = R.drawable.bg_chip_unselected
        val tealColor = ContextCompat.getColor(this, R.color.primary_teal)
        val mutedColor = ContextCompat.getColor(this, R.color.text_secondary)

        when (currentConfig.scaleMode) {
            IdCardScaleMode.ACTUAL_SIZE -> {
                binding.tabScaleActual.setBackgroundResource(selectedBg)
                binding.tabScaleActual.setTextColor(tealColor)
                binding.tabScaleFit.setBackgroundResource(unselectedBg)
                binding.tabScaleFit.setTextColor(mutedColor)
            }
            IdCardScaleMode.FIT_PAGE -> {
                binding.tabScaleActual.setBackgroundResource(unselectedBg)
                binding.tabScaleActual.setTextColor(mutedColor)
                binding.tabScaleFit.setBackgroundResource(selectedBg)
                binding.tabScaleFit.setTextColor(tealColor)
            }
        }
    }

    private fun updateBorderButton(hasBorder: Boolean) {
        if (hasBorder) {
            binding.btnToggleBorder.setBackgroundResource(R.drawable.bg_chip_selected)
            binding.btnToggleBorder.setTextColor(ContextCompat.getColor(this, R.color.primary_teal))
        } else {
            binding.btnToggleBorder.setBackgroundResource(R.drawable.bg_chip_unselected)
            binding.btnToggleBorder.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun showSaveOptionsDialog() {
        if (frontBitmap == null && backBitmap == null) {
            Toast.makeText(this, getString(R.string.id_card_need_at_least_one), Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf(
            getString(R.string.id_card_save_pdf_option),
            getString(R.string.id_card_save_hd_image_option),
            getString(R.string.id_card_share_pdf_option)
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.id_card_save_dialog_title))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> saveAndOpenPdf()
                    1 -> saveHighResImageToDownloads()
                    2 -> sharePdfDirectly()
                }
            }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    private fun saveAndOpenPdf() {
        if (frontBitmap == null && backBitmap == null) {
            Toast.makeText(this, getString(R.string.id_card_need_at_least_one), Toast.LENGTH_SHORT).show()
            return
        }
        binding.pbComposeLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val docDir = FileUtils.getDocumentsDir(this@IdCardComposeActivity)
            val newDocId = UUID.randomUUID().toString()
            val timeStamp = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(Date())
            val pdfFile = File(docDir, "doc_${newDocId}.pdf")

            val effectiveWatermark = WatermarkHelper.shouldApplyWatermark(userWantsRemoved = !currentConfig.addWatermark)
            val effectiveConfig = currentConfig.copy(addWatermark = effectiveWatermark)

            val success = IdCardComposerHelper.createA4Pdf(
                frontBmp = frontBitmap,
                backBmp = backBitmap,
                config = effectiveConfig,
                outputFile = pdfFile
            )

            binding.pbComposeLoading.visibility = View.GONE

            if (success && pdfFile.exists()) {
                val thumbsDir = FileUtils.getThumbnailsDir(this@IdCardComposeActivity)
                val thumbFile = File(thumbsDir, "thumb_${newDocId}.jpg")
                val thumbPath = if (PdfConverterHelper.renderPdfFirstPage(pdfFile, thumbFile)) {
                    thumbFile.absolutePath
                } else null

                val docItem = DocumentItem(
                    id = newDocId,
                    title = getString(R.string.id_card_doc_title_format, timeStamp),
                    pdfPath = pdfFile.absolutePath,
                    thumbnailPath = thumbPath,
                    pagePaths = emptyList(), // A4 composed page is in the PDF
                    pageCount = 1,
                    sizeBytes = pdfFile.length(),
                    createdAt = System.currentTimeMillis(),
                    ownerId = AppAuthManager.getCurrentUser()?.id
                )
                DocumentRepo.getInstance(this@IdCardComposeActivity).addDocument(docItem)

                Toast.makeText(this@IdCardComposeActivity, getString(R.string.id_card_saved_success), Toast.LENGTH_SHORT).show()
                PdfViewerActivity.start(this@IdCardComposeActivity, pdfFile.absolutePath, docItem.title, emptyList())
                finish()
            } else {
                Toast.makeText(this@IdCardComposeActivity, getString(R.string.cannot_create_pdf), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveHighResImageToDownloads() {
        if (frontBitmap == null && backBitmap == null) {
            Toast.makeText(this, getString(R.string.id_card_need_at_least_one), Toast.LENGTH_SHORT).show()
            return
        }
        binding.pbComposeLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val exportDir = FileUtils.getExportsDir(this@IdCardComposeActivity)
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date())
            val tempImgFile = File(exportDir, "${getString(R.string.default_id_card_img_prefix)}$timeStamp.jpg")

            val effectiveWatermark = WatermarkHelper.shouldApplyWatermark(userWantsRemoved = !currentConfig.addWatermark)
            val effectiveConfig = currentConfig.copy(addWatermark = effectiveWatermark)

            val success = IdCardComposerHelper.saveA4Image(
                frontBmp = frontBitmap,
                backBmp = backBitmap,
                config = effectiveConfig,
                outputFile = tempImgFile
            )

            binding.pbComposeLoading.visibility = View.GONE

            if (success && tempImgFile.exists()) {
                val savedUri = FileUtils.saveFileToDownloads(
                    this@IdCardComposeActivity,
                    tempImgFile,
                    "image/jpeg",
                    customName = tempImgFile.name
                )
                if (savedUri != null) {
                    Toast.makeText(this@IdCardComposeActivity, getString(R.string.id_card_saved_hd_to_downloads), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@IdCardComposeActivity, getString(R.string.id_card_created_image_at_format, tempImgFile.name), Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this@IdCardComposeActivity, getString(R.string.id_card_save_image_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sharePdfDirectly() {
        if (frontBitmap == null && backBitmap == null) {
            Toast.makeText(this, getString(R.string.id_card_need_at_least_one), Toast.LENGTH_SHORT).show()
            return
        }
        binding.pbComposeLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val exportDir = FileUtils.getExportsDir(this@IdCardComposeActivity)
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date())
            val pdfFile = File(exportDir, "${getString(R.string.default_id_card_pdf_prefix)}$timeStamp.pdf")

            val effectiveWatermark = WatermarkHelper.shouldApplyWatermark(userWantsRemoved = !currentConfig.addWatermark)
            val effectiveConfig = currentConfig.copy(addWatermark = effectiveWatermark)

            val success = IdCardComposerHelper.createA4Pdf(
                frontBmp = frontBitmap,
                backBmp = backBitmap,
                config = effectiveConfig,
                outputFile = pdfFile
            )

            binding.pbComposeLoading.visibility = View.GONE

            if (success && pdfFile.exists()) {
                val uri = FileProvider.getUriForFile(
                    this@IdCardComposeActivity,
                    "$packageName.provider",
                    pdfFile
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_id_card_title)))
            } else {
                Toast.makeText(this@IdCardComposeActivity, getString(R.string.cannot_create_pdf_to_share), Toast.LENGTH_SHORT).show()
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        frontBitmap?.recycle()
        frontBitmap = null
        backBitmap?.recycle()
        backBitmap = null
        currentPreviewBitmap?.recycle()
        currentPreviewBitmap = null
    }

    companion object {
        const val EXTRA_FRONT_PATH = "extra_front_path"
        const val EXTRA_BACK_PATH = "extra_back_path"
        const val EXTRA_PAGE_PATHS = "extra_page_paths"
        private const val KEY_PENDING_DRIVE_AUTH_ATTEMPT = "key_pending_drive_auth_attempt"
        private const val KEY_PENDING_SIGN_IN_REQ_ID = "key_pending_sign_in_req_id"
        private const val KEY_PENDING_SIGN_IN_SESSION_GEN = "key_pending_sign_in_session_gen"

        fun start(context: Context, pagePaths: List<String>) {
            val intent = Intent(context, IdCardComposeActivity::class.java).apply {
                putStringArrayListExtra(EXTRA_PAGE_PATHS, ArrayList(pagePaths))
                if (pagePaths.isNotEmpty()) putExtra(EXTRA_FRONT_PATH, pagePaths[0])
                if (pagePaths.size > 1) putExtra(EXTRA_BACK_PATH, pagePaths[1])
            }
            context.startActivity(intent)
        }

        fun startWithSides(context: Context, frontPath: String?, backPath: String?) {
            val intent = Intent(context, IdCardComposeActivity::class.java).apply {
                putExtra(EXTRA_FRONT_PATH, frontPath)
                putExtra(EXTRA_BACK_PATH, backPath)
            }
            context.startActivity(intent)
        }
    }
}
