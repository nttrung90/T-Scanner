package com.tscanner.app.ui.more

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.tscanner.app.R
import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.databinding.FragmentMoreBinding
import com.tscanner.app.ui.dialogs.AboutAppDialog
import com.tscanner.app.ui.dialogs.AccountDetailDialog
import com.tscanner.app.ui.dialogs.CheckUpdateDialog
import com.tscanner.app.ui.dialogs.LanguageSelectionDialog
import com.tscanner.app.ui.dialogs.OcrEngineSelectionDialog
import com.tscanner.app.ui.dialogs.OcrLanguageSelectionDialog
import com.tscanner.app.ui.dialogs.VipUpgradeDialog
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.AppLanguageManager
import com.tscanner.app.utils.AvatarViewBinder
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.CloudBackupManager
import com.tscanner.app.utils.DriveAuthorizationAttempt
import com.tscanner.app.utils.GoogleLoginAttempt
import com.tscanner.app.utils.SyncCatalogResult
import com.tscanner.app.utils.SyncResultPresenter
import com.tscanner.app.utils.TextRecognitionHelper
import com.tscanner.app.utils.VipLoginContinuationHandler
import com.tscanner.app.utils.VipContinuationAction

class MoreFragment : Fragment() {

    private var _binding: FragmentMoreBinding? = null
    private val binding get() = _binding!!

    private lateinit var googleSignInLauncher: ActivityResultLauncher<Intent>
    private lateinit var driveAuthorizationLauncher: ActivityResultLauncher<Intent>
    private val vipContinuationHandler = VipLoginContinuationHandler()
    private var pendingSignInAttempt: GoogleLoginAttempt? = null
    private var pendingDriveAuthAttempt: DriveAuthorizationAttempt? = null
    private val billingPurchaseCallback = BillingManager.PurchaseCallback { success, _, _ ->
        if (success && isAdded && !isDetached && view != null) {
            activity?.runOnUiThread {
                if (isAdded && !isDetached && view != null) {
                    updateAccountUi(AppAuthManager.getCurrentUser())
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vipContinuationHandler.restoreInstanceState(savedInstanceState)
        if (savedInstanceState != null) {
            pendingSignInAttempt = GoogleLoginAttempt.fromBundle(savedInstanceState)
            @Suppress("DEPRECATION")
            pendingDriveAuthAttempt = savedInstanceState.getSerializable(KEY_PENDING_DRIVE_AUTH_ATTEMPT) as? DriveAuthorizationAttempt
        }
        parentFragmentManager.setFragmentResultListener(REQUEST_KEY_VIP_SIGN_IN, this) { _, bundle ->
            if (bundle.getBoolean(EXTRA_AUTO_START_SIGN_IN, false)) {
                val actionStr = bundle.getString(EXTRA_VIP_ACTION)
                val targetAction = if (actionStr == "RESTORE") VipContinuationAction.RESTORE else VipContinuationAction.UPGRADE
                val forceReauth = bundle.getBoolean(EXTRA_FORCE_REAUTH, false)
                val authReason = bundle.getString(EXTRA_AUTH_REQUIRED_REASON)
                val expectedOwnerId = bundle.getString(EXTRA_EXPECTED_OWNER_ID)
                val operationId = bundle.getString(EXTRA_OPERATION_ID)
                val originGen = bundle.getLong(EXTRA_ORIGIN_GENERATION, -1L)
                val originEpoch = bundle.getString(EXTRA_PROCESS_EPOCH)
                val user = AppAuthManager.getCurrentUser()
                val currentGen = AppAuthManager.getSessionGeneration()
                val currentEpoch = AppAuthManager.getProcessEpoch()

                val isConsumed = if (operationId != null) consumedOperationIds.contains(operationId) else false
                val decision = VipNavigationValidator.validateNavigation(
                    originOwnerId = expectedOwnerId,
                    currentOwnerId = user?.id,
                    originGeneration = originGen,
                    currentGeneration = currentGen,
                    originEpoch = originEpoch,
                    currentEpoch = currentEpoch,
                    operationId = operationId,
                    isOperationConsumed = isConsumed
                )

                val recoveryRequest = if (operationId != null) {
                    com.tscanner.app.utils.billing.VipRecoveryRegistry.consume(operationId)
                } else null

                when (decision) {
                    is NavigationDecision.Discard -> {
                        android.util.Log.w("MoreFragment", "Discarding navigation request: ${decision.reason}")
                        recoveryRequest?.onRefused?.invoke()
                        return@setFragmentResultListener
                    }
                    is NavigationDecision.Accept -> {
                        if (operationId != null) {
                            consumedOperationIds.add(operationId)
                        }
                    }
                }

                val isExpired = user == null || com.tscanner.app.utils.billing.PlayPurchaseVerifier.isTokenExpired(user.idToken)
                val needsSignIn = isExpired || forceReauth

                if (needsSignIn) {
                    if (recoveryRequest != null) {
                        startSignInForVipContinuation(
                            action = targetAction,
                            opContext = recoveryRequest.operationContext,
                            onStarted = { recoveryRequest.onStarted() },
                            onRefused = { recoveryRequest.onRefused() }
                        )
                    } else {
                        startSignInForVipContinuation(targetAction)
                    }
                } else {
                    executeVipContinuation(targetAction, opContext = recoveryRequest?.operationContext)
                }
            }
        }
        googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val ctx = context ?: return@registerForActivityResult
            val attempt = pendingSignInAttempt
            pendingSignInAttempt = null
            val attemptId = attempt?.requestId ?: -1L
            AppAuthManager.handleGoogleSignInResult(
                context = ctx,
                resultCode = result.resultCode,
                data = result.data,
                attempt = attempt,
                onSuccess = { profile ->
                    if (!isAdded) return@handleGoogleSignInResult
                    Toast.makeText(ctx, getString(R.string.sign_in_success), Toast.LENGTH_SHORT).show()
                    val startUser = AppAuthManager.getCurrentUser()?.id
                    val startGen = AppAuthManager.getSessionGeneration()
                    AppAuthManager.runPostAuthorizationSync(ctx) { handlePostAuthSyncResult(it, startUser, startGen) }
                    vipContinuationHandler.onSignInSuccessWithAction(startGen, startUser, attemptId) { action, product, opCtx ->
                        executeVipContinuation(action, product, opCtx)
                    }
                },
                onCancelled = {
                    vipContinuationHandler.onSignInCancelled(attemptId)
                },
                onError = { errorMsg ->
                    vipContinuationHandler.onSignInError(attemptId)
                    if (!isAdded) return@handleGoogleSignInResult
                    showSignInErrorDialog(errorMsg)
                }
            )
        }

        driveAuthorizationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val ctx = context ?: return@registerForActivityResult
            val attempt = pendingDriveAuthAttempt
            pendingDriveAuthAttempt = null
            AppAuthManager.handleDrivePermissionResult(
                context = ctx,
                resultCode = result.resultCode,
                data = result.data,
                attempt = attempt,
                onSuccess = {
                    if (!isAdded) return@handleDrivePermissionResult
                    Toast.makeText(ctx, getString(R.string.drive_permission_granted_toast), Toast.LENGTH_SHORT).show()
                    val startUser = AppAuthManager.getCurrentUser()?.id
                    val startGen = AppAuthManager.getSessionGeneration()
                    AppAuthManager.runPostAuthorizationSync(ctx) { handlePostAuthSyncResult(it, startUser, startGen) }
                },
                onCancelled = {
                    // User cancelled consent prompt; quiet finish
                },
                onError = { errorMsg ->
                    if (!isAdded) return@handleDrivePermissionResult
                    Toast.makeText(ctx, errorMsg, Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMoreBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupVersionBadge()
        setupLanguageBadge()
        setupOcrLanguageBadge()
        setupOcrEngineBadge()
        setupAccountObserver()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        BillingManager.getInstance(requireContext()).addPurchaseCallback(billingPurchaseCallback)
        val expired = AppAuthManager.checkAndEnforceVipExpiration(requireContext())
        if (expired) {
            showVipExpiredNoticeDialog()
        }
        setupLanguageBadge()
        setupOcrLanguageBadge()
        setupOcrEngineBadge()
        updateAccountUi(AppAuthManager.getCurrentUser())
    }

    override fun onPause() {
        super.onPause()
        if (context != null) {
            BillingManager.getInstance(requireContext()).removePurchaseCallback(billingPurchaseCallback)
        }
    }

    private fun showVipExpiredNoticeDialog() {
        if (!isAdded) return
        AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_TScanner_Dialog)
            .setTitle(R.string.vip_expired_dialog_title)
            .setMessage(R.string.vip_expired_dialog_message)
            .setPositiveButton(R.string.vip_renew_now_btn) { _, _ ->
                showVipUpgradeDialog()
            }
            .setNegativeButton(R.string.check_update_action_later, null)
            .show()
    }

    private fun setupLanguageBadge() {
        val isSystem = AppLanguageManager.isSystemDefaultSelected(requireContext())
        val currentCode = AppLanguageManager.getCurrentLanguageCode(requireContext())
        val autoLang = AppLanguageManager.getAutoUiLanguage(currentCode)
        val nativeName = autoLang?.nativeName ?: "English"

        binding.tvCurrentLanguageBadge.text = if (isSystem) {
            val systemPrefix = getString(R.string.language_system_default)
            "$systemPrefix · $nativeName"
        } else {
            nativeName
        }
    }

    private fun setupOcrLanguageBadge() {
        binding.tvCurrentOcrLanguageBadge.text = TextRecognitionHelper.getOcrDocumentLanguageDisplayName(requireContext())
    }

    private fun setupOcrEngineBadge() {
        binding.tvCurrentOcrEngineBadge.text = TextRecognitionHelper.getPreferredEngineDisplayName(requireContext())
    }

    private fun setupVersionBadge() {
        val versionName = try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            pInfo.versionName ?: "1.2.0"
        } catch (e: Exception) {
            "1.2.0"
        }
        binding.tvCurrentVersionBadge.text = "v$versionName"
    }

    private fun setupAccountObserver() {
        AppAuthManager.currentUser.observe(viewLifecycleOwner) { user ->
            updateAccountUi(user)
        }
    }

    private fun updateAccountUi(user: UserProfile?) {
        if (!isAdded || _binding == null) return

        val isVip = user?.isVipActive == true
        binding.cardVipBanner.visibility = if (isVip) View.GONE else View.VISIBLE
        binding.itemVip.visibility = if (isVip) View.GONE else View.VISIBLE
        binding.dividerVip.visibility = if (isVip) View.GONE else View.VISIBLE

        if (user == null) {
            // State: Not logged in
            AvatarViewBinder.bindAvatar(
                imageView = binding.ivAccountIcon,
                photoUrl = null,
                fallbackRes = R.drawable.ic_google,
                sizePx = 128
            )
            binding.tvAccountTitle.text = getString(R.string.account_sign_in_title)
            binding.tvAccountSubtitle.text = getString(R.string.account_sign_in_desc)
            binding.tvAccountActionBadge.visibility = View.VISIBLE
            binding.tvAccountActionBadge.text = getString(R.string.btn_sign_in)
            binding.tvAccountActionBadge.setBackgroundResource(R.drawable.btn_outline_teal)
            binding.tvAccountActionBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary_teal))
        } else {
            // State: Logged in
            AvatarViewBinder.bindAvatar(
                imageView = binding.ivAccountIcon,
                photoUrl = user.photoUrl,
                fallbackRes = R.drawable.ic_account_circle,
                sizePx = 128
            )

            binding.tvAccountTitle.text = user.displayName
            binding.tvAccountSubtitle.text = user.email

            if (user.isVipActive) {
                binding.tvAccountActionBadge.visibility = View.VISIBLE
                val badgeText = when (user.tier) {
                    com.tscanner.app.data.model.VipTier.VIP_PRO_MAX -> "PRO MAX"
                    com.tscanner.app.data.model.VipTier.VIP_PRO -> "PRO"
                    else -> "VIP"
                }
                binding.tvAccountActionBadge.text = badgeText
                binding.tvAccountActionBadge.setBackgroundResource(R.drawable.btn_vip_gold)
                binding.tvAccountActionBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.vip_btn_text))
            } else {
                binding.tvAccountActionBadge.visibility = View.GONE
            }
        }
    }

    private fun handleAccountClick() {
        val user = AppAuthManager.getCurrentUser()
        if (user != null) {
            // Show account detail dialog
            AccountDetailDialog(
                context = requireContext(),
                user = user,
                onRequestDrivePermission = {
                    try {
                        val intent = AppAuthManager.getGoogleDriveSignInIntent(requireContext()) { attempt ->
                            pendingDriveAuthAttempt = attempt
                        }
                        driveAuthorizationLauncher.launch(intent)
                    } catch (e: Exception) {
                        val toCancel = pendingDriveAuthAttempt
                        pendingDriveAuthAttempt = null
                        AppAuthManager.cancelDriveAuthorizationAttempt(toCancel)
                        Toast.makeText(requireContext(), getString(R.string.error_occurred_format, e.message.orEmpty()), Toast.LENGTH_SHORT).show()
                    }
                },
                onRequestSignIn = {
                    startSignInForVipContinuation(VipContinuationAction.UPGRADE)
                },
                onRequestSignInForAction = { action ->
                    startSignInForVipContinuation(action)
                },
                onRequestSignInForRecovery = { action, opContext, onStarted, onRefused ->
                    startSignInForVipContinuation(
                        action = action,
                        opContext = opContext,
                        onStarted = onStarted,
                        onRefused = onRefused
                    )
                },
                onUpgradeSuccess = {
                    updateAccountUi(AppAuthManager.getCurrentUser())
                },
                onSyncResult = { result ->
                    val startUser = AppAuthManager.getCurrentUser()?.id
                    val startGen = AppAuthManager.getSessionGeneration()
                    handlePostAuthSyncResult(result, startUser, startGen)
                },
                onSignOut = {
                    performSignOut()
                }
            ).show()
        } else {
            // Trigger Google Sign-In
            performGoogleSignIn()
        }
    }

    private fun performGoogleSignIn(): Boolean {
        if (!isAdded) return false
        val act = activity ?: return false
        if (act.isFinishing || act.isDestroyed) return false

        val currentUser = AppAuthManager.getCurrentUser()
        var invocationAttempt: GoogleLoginAttempt? = null
        val started = AppAuthManager.signInWithGoogle(
            activity = act,
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            expectedOwnerId = currentUser?.id,
            onAttemptCreated = { token ->
                invocationAttempt = token
                pendingSignInAttempt = token
                vipContinuationHandler.bindAttempt(token)
            },
            onFallbackToIntent = {
                val attemptToCancel = invocationAttempt ?: pendingSignInAttempt
                if (!isAdded) {
                    AppAuthManager.cancelSignInProgress(attemptToCancel)
                    if (pendingSignInAttempt === attemptToCancel) pendingSignInAttempt = null
                    vipContinuationHandler.onSignInCancelled(attemptToCancel?.requestId ?: -1L)
                    return@signInWithGoogle
                }
                try {
                    val intent = AppAuthManager.getGoogleSignInIntent(requireContext())
                    googleSignInLauncher.launch(intent)
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
                if (!isAdded) return@signInWithGoogle
                Toast.makeText(requireContext(), getString(R.string.sign_in_success), Toast.LENGTH_SHORT).show()
                val startUser = AppAuthManager.getCurrentUser()?.id
                val startGen = AppAuthManager.getSessionGeneration()
                AppAuthManager.runPostAuthorizationSync(requireContext()) { handlePostAuthSyncResult(it, startUser, startGen) }
                vipContinuationHandler.onSignInSuccessWithAction(startGen, startUser, attemptId) { action, product, opContext ->
                    executeVipContinuation(action, product, opContext)
                }
            },
            onCancelled = {
                android.util.Log.i("MoreFragment", "[AuthLifecycle] stage=UI status=MORE_FRAGMENT_ON_CANCELLED")
                val attemptId = invocationAttempt?.requestId ?: pendingSignInAttempt?.requestId ?: -1L
                if (pendingSignInAttempt === invocationAttempt) {
                    pendingSignInAttempt = null
                }
                vipContinuationHandler.onSignInCancelled(attemptId)
            },
            onError = { errorMsg ->
                android.util.Log.w("MoreFragment", "[AuthLifecycle] stage=UI status=MORE_FRAGMENT_ON_ERROR error=${AppAuthManager.sanitizeForLog(errorMsg)}")
                val attemptId = invocationAttempt?.requestId ?: pendingSignInAttempt?.requestId ?: -1L
                if (pendingSignInAttempt === invocationAttempt) {
                    pendingSignInAttempt = null
                }
                vipContinuationHandler.onSignInError(attemptId)
                if (!isAdded) return@signInWithGoogle
                showSignInErrorDialog(errorMsg)
            }
        )
        if (!started) {
            android.util.Log.d("MoreFragment", "Sign-in already in progress, ignoring duplicate tap")
        }
        return started
    }

    fun startSignInForVipContinuation(
        action: VipContinuationAction = VipContinuationAction.UPGRADE,
        targetProductId: String? = null,
        opContext: com.tscanner.app.utils.billing.BillingOperationContext? = null,
        onStarted: (() -> Unit)? = null,
        onRefused: (() -> Unit)? = null
    ) {
        if (!isAdded) {
            onRefused?.invoke()
            return
        }
        if (vipContinuationHandler.isPending) {
            android.util.Log.d("MoreFragment", "Continuation already pending, ignoring duplicate start")
            onRefused?.invoke()
            return
        }
        val currentGen = AppAuthManager.getSessionGeneration()
        val currentOwner = AppAuthManager.getCurrentUser()?.id
        val currentEpoch = AppAuthManager.getProcessEpoch()
        vipContinuationHandler.requestContinuation(
            action = action,
            sessionGeneration = currentGen,
            targetProductId = targetProductId,
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

    private fun executeVipContinuation(
        action: VipContinuationAction,
        targetProductId: String? = null,
        opContext: com.tscanner.app.utils.billing.BillingOperationContext? = null
    ) {
        if (!isAdded) return
        when (action) {
            VipContinuationAction.UPGRADE -> showVipUpgradeDialog()
            VipContinuationAction.RESTORE -> {
                val ctx = context ?: return
                val billingManager = BillingManager.getInstance(ctx)
                Toast.makeText(ctx, getString(R.string.vip_restore_purchases_btn), Toast.LENGTH_SHORT).show()
                billingManager.restorePurchases(
                    opContext = opContext,
                    onAuthRequired = { authMessage ->
                        activity?.runOnUiThread {
                            if (isAdded) {
                                Toast.makeText(ctx, authMessage, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onComplete = { success, message ->
                        activity?.runOnUiThread {
                            if (isAdded) {
                                Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
                                if (success) {
                                    updateAccountUi(AppAuthManager.getCurrentUser())
                                }
                            }
                        }
                    }
                )
            }
            VipContinuationAction.NONE -> {
                // No continuation required
            }
        }
    }

    private fun requestDrivePermission() {
        if (!isAdded) return
        try {
            val intent = AppAuthManager.getGoogleDriveSignInIntent(requireContext()) { attempt ->
                pendingDriveAuthAttempt = attempt
            }
            driveAuthorizationLauncher.launch(intent)
        } catch (e: Exception) {
            val toCancel = pendingDriveAuthAttempt
            pendingDriveAuthAttempt = null
            AppAuthManager.cancelDriveAuthorizationAttempt(toCancel)
            Toast.makeText(requireContext(), getString(R.string.error_occurred_format, e.message.orEmpty()), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showVipUpgradeDialog() {
        if (!isAdded) return
        VipUpgradeDialog(
            context = requireContext(),
            onRequestDrivePermission = { requestDrivePermission() },
            onRequestSignIn = {
                startSignInForVipContinuation(VipContinuationAction.UPGRADE)
            },
            onRequestSignInForAction = { action ->
                startSignInForVipContinuation(action)
            },
            onRequestSignInForRecovery = { action, opContext, onStarted, onRefused ->
                startSignInForVipContinuation(action, null, opContext, onStarted, onRefused)
            },
            onSyncResult = { result ->
                val startUser = AppAuthManager.getCurrentUser()?.id
                val startGen = AppAuthManager.getSessionGeneration()
                handlePostAuthSyncResult(result, startUser, startGen)
            }
        ).show()
    }

    private fun handlePostAuthSyncResult(result: SyncCatalogResult, originUserId: String?, originSessionGen: Long) {
        if (!isAdded || _binding == null) return
        SyncResultPresenter.present(
            context = requireContext(),
            result = result,
            expectedSessionGeneration = originSessionGen,
            expectedUserId = originUserId,
            isHostValid = { isAdded && _binding != null && viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) },
            onRequestDrivePermission = {
                requestDrivePermission()
            },
            onRetry = {
                if (isAdded && _binding != null) {
                    val retryUser = AppAuthManager.getCurrentUser()?.id
                    val retryGen = AppAuthManager.getSessionGeneration()
                    AppAuthManager.runPostAuthorizationSync(requireContext()) { retryResult ->
                        handlePostAuthSyncResult(retryResult, retryUser, retryGen)
                    }
                }
            }
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        vipContinuationHandler.saveInstanceState(outState)
        pendingSignInAttempt?.writeToBundle(outState)
        outState.putSerializable(KEY_PENDING_DRIVE_AUTH_ATTEMPT, pendingDriveAuthAttempt)
    }

    private fun showSignInErrorDialog(errorMsg: String) {
        if (!isAdded) return

        AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_TScanner_Dialog)
            .setTitle(R.string.account_sign_in_title)
            .setMessage(errorMsg)
            .setPositiveButton(R.string.retry) { _, _ ->
                performGoogleSignIn()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun performSignOut() {
        AppAuthManager.signOut(
            context = requireContext(),
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            onComplete = {
                Toast.makeText(requireContext(), getString(R.string.sign_out_success), Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun setupListeners() {
        // VIP Banner click & "Nâng cấp ngay" button
        binding.cardVipBanner.setOnClickListener { showVipUpgradeDialog() }
        binding.btnVipUpgradeNow.setOnClickListener { showVipUpgradeDialog() }

        // Item 1: Nâng cấp Vip
        binding.itemVip.setOnClickListener { showVipUpgradeDialog() }

        // Item 2: Tài khoản Google (Ngay dưới Nâng cấp Vip)
        binding.itemAccount.setOnClickListener {
            handleAccountClick()
        }

        // Item 4: Ngôn ngữ ứng dụng (Gói U02)
        binding.itemLanguage.setOnClickListener {
            LanguageSelectionDialog(requireActivity()) {
                setupLanguageBadge()
            }.show()
        }

        // Item: Ngôn ngữ tài liệu OCR
        binding.itemOcrLanguage.setOnClickListener {
            OcrLanguageSelectionDialog(requireActivity()) {
                setupOcrLanguageBadge()
                setupOcrEngineBadge()
            }.show()
        }

        // Item: Cài đặt (Động cơ OCR)
        binding.itemSettings.setOnClickListener {
            OcrEngineSelectionDialog(requireActivity()) {
                setupOcrEngineBadge()
            }.show()
        }

        // Item 5: Kiểm tra cập nhật
        binding.itemCheckUpdate.setOnClickListener {
            CheckUpdateDialog(requireActivity()).show()
        }

        // Item 6: Giới thiệu
        binding.itemAbout.setOnClickListener {
            AboutAppDialog(requireContext()).show()
        }
    }

    override fun onDestroyView() {
        _binding?.ivAccountIcon?.let {
            AvatarViewBinder.clearAvatar(it)
        }
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val REQUEST_KEY_VIP_SIGN_IN = "request_key_vip_sign_in"
        const val EXTRA_AUTO_START_SIGN_IN = "extra_auto_start_sign_in"
        const val EXTRA_VIP_ACTION = "extra_vip_action"
        const val EXTRA_FORCE_REAUTH = "extra_force_reauth"
        const val EXTRA_AUTH_REQUIRED_REASON = "extra_auth_required_reason"
        const val EXTRA_EXPECTED_OWNER_ID = "extra_expected_owner_id"
        const val EXTRA_OPERATION_ID = "extra_operation_id"
        const val EXTRA_ORIGIN_GENERATION = "extra_origin_generation"
        const val EXTRA_PROCESS_EPOCH = "extra_process_epoch"
        private const val KEY_PENDING_SIGN_IN_REQ_ID = "key_pending_sign_in_req_id"
        private const val KEY_PENDING_SIGN_IN_SESSION_GEN = "key_pending_sign_in_session_gen"
        private const val KEY_PENDING_DRIVE_AUTH_ATTEMPT = "key_pending_drive_auth_attempt"

        private val consumedOperationIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        @VisibleForTesting
        fun isOperationConsumedForTesting(opId: String): Boolean = consumedOperationIds.contains(opId)

        @VisibleForTesting
        fun resetConsumedOperationsForTesting() {
            consumedOperationIds.clear()
        }
    }
}

sealed class NavigationDecision {
    object Accept : NavigationDecision()
    data class Discard(val reason: String) : NavigationDecision()
}

object VipNavigationValidator {
    fun validateNavigation(
        originOwnerId: String?,
        currentOwnerId: String?,
        originGeneration: Long,
        currentGeneration: Long,
        originEpoch: String?,
        currentEpoch: String,
        operationId: String?,
        isOperationConsumed: Boolean
    ): NavigationDecision {
        // 1. Process epoch check: reject stale navigation across process restart
        if (!originEpoch.isNullOrEmpty() && currentEpoch.isNotEmpty() && originEpoch != currentEpoch) {
            return NavigationDecision.Discard("Process epoch mismatch (origin=$originEpoch, current=$currentEpoch)")
        }

        // 2. Replay check: reject already consumed operation
        if (!operationId.isNullOrEmpty() && isOperationConsumed) {
            return NavigationDecision.Discard("Operation $operationId was already consumed")
        }

        // 3. Logged-out check: If origin was for owner A, but current user is null (logged out), DISCARD!
        if (originOwnerId != null && currentOwnerId == null) {
            return NavigationDecision.Discard("Request originated for owner $originOwnerId but user logged out")
        }

        // 4. Owner switch check: If origin was for owner A, but current is owner B, DISCARD!
        if (originOwnerId != null && originOwnerId != currentOwnerId) {
            return NavigationDecision.Discard("Owner mismatch (origin=$originOwnerId, current=$currentOwnerId)")
        }

        // 5. Guest to authenticated user transition before consume:
        // Guest G/E -> A ở phiên khác trước consumer: Discard request cũ, không tự gắn action cho A
        if (originOwnerId == null && currentOwnerId != null) {
            return NavigationDecision.Discard("Request originated for guest but current user is authenticated ($currentOwnerId)")
        }

        // 6. Generation check for both guest and existing user:
        // Guest G/E -> guest G+2/E sau login/logout: Discard
        // A G/E -> A G/E: Accept
        if (originGeneration != -1L && originGeneration != currentGeneration) {
            return NavigationDecision.Discard("Session generation mismatch (origin=$originGeneration, current=$currentGeneration)")
        }

        return NavigationDecision.Accept
    }
}
