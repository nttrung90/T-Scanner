package com.tscanner.app.ui.more

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
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
import com.tscanner.app.ui.docmanagement.DocumentManagementActivity
import com.tscanner.app.ui.dialogs.LanguageSelectionDialog
import com.tscanner.app.ui.dialogs.OcrEngineSelectionDialog
import com.tscanner.app.ui.dialogs.VipUpgradeDialog
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.CloudBackupManager
import com.tscanner.app.utils.TextRecognitionHelper

class MoreFragment : Fragment() {

    private var _binding: FragmentMoreBinding? = null
    private val binding get() = _binding!!

    private lateinit var googleSignInLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                AppAuthManager.handleGoogleSignInResult(
                    context = requireContext(),
                    data = result.data,
                    onSuccess = { profile ->
                        Toast.makeText(requireContext(), getString(R.string.sign_in_success), Toast.LENGTH_SHORT).show()
                        if (profile.isVipActive) {
                            val repo = DocumentRepo.getInstance(requireContext())
                            val unsynced = repo.getUnsyncedDocuments()
                            if (unsynced.isNotEmpty()) {
                                CloudBackupManager.enqueueBatchBackup(requireContext(), unsynced)
                            }
                            CloudBackupManager.syncCatalogFromDrive(requireContext()) {}
                        }
                    },
                    onError = { errorMsg ->
                        showSignInErrorDialog(errorMsg)
                    }
                )
            } else if (result.resultCode != Activity.RESULT_CANCELED) {
                AppAuthManager.handleGoogleSignInResult(
                    context = requireContext(),
                    data = result.data,
                    onSuccess = { profile ->
                        Toast.makeText(requireContext(), getString(R.string.sign_in_success), Toast.LENGTH_SHORT).show()
                        if (profile.isVipActive) {
                            val repo = DocumentRepo.getInstance(requireContext())
                            val unsynced = repo.getUnsyncedDocuments()
                            if (unsynced.isNotEmpty()) {
                                CloudBackupManager.enqueueBatchBackup(requireContext(), unsynced)
                            }
                            CloudBackupManager.syncCatalogFromDrive(requireContext()) {}
                        }
                    },
                    onError = { errorMsg ->
                        showSignInErrorDialog(errorMsg)
                    }
                )
            }
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
        setupOcrEngineBadge()
        setupAccountObserver()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        val expired = AppAuthManager.checkAndEnforceVipExpiration(requireContext())
        if (expired) {
            showVipExpiredNoticeDialog()
        }
        updateAccountUi(AppAuthManager.getCurrentUser())
    }

    private fun showVipExpiredNoticeDialog() {
        if (!isAdded) return
        AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_TScanner_Dialog)
            .setTitle("Thông báo hết hạn gói VIP")
            .setMessage(
                "Thời hạn gói VIP 1 năm (365 ngày) của bạn đã kết thúc. Tài khoản hiện đã được chuyển về gói FREE.\n\n" +
                "✓ Toàn bộ tài liệu bạn đã sao lưu trên Google Drive vẫn được lưu trữ an toàn tuyệt đối 100%.\n" +
                "✓ Tài liệu mới quét sẽ được lưu trữ trực tiếp trên thiết bị.\n\n" +
                "Bạn có muốn gia hạn gói VIP (chỉ 20.000 đ/năm) để tiếp tục tự động sao lưu tài liệu mới lên Google Drive không?"
            )
            .setPositiveButton("Gia hạn ngay (20.000 đ)") { _, _ ->
                VipUpgradeDialog(requireContext()).show()
            }
            .setNegativeButton("Để sau", null)
            .show()
    }

    private fun setupLanguageBadge() {
        binding.tvCurrentLanguageBadge.text = LanguageSelectionDialog.getCurrentLanguageDisplayName(requireContext())
    }

    private fun setupOcrEngineBadge() {
        binding.tvCurrentOcrEngineBadge.text = TextRecognitionHelper.getPreferredEngineDisplayName(requireContext())
    }

    private fun setupVersionBadge() {
        val versionName = try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            pInfo.versionName ?: "0.6.0"
        } catch (e: Exception) {
            "0.6.0"
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

        val isVip = user?.isVip == true
        binding.cardVipBanner.visibility = if (isVip) View.GONE else View.VISIBLE
        binding.itemVip.visibility = if (isVip) View.GONE else View.VISIBLE
        binding.dividerVip.visibility = if (isVip) View.GONE else View.VISIBLE

        if (user == null) {
            // State: Not logged in
            binding.ivAccountIcon.setImageResource(R.drawable.ic_google)
            binding.tvAccountTitle.text = getString(R.string.account_sign_in_title)
            binding.tvAccountSubtitle.text = getString(R.string.account_sign_in_desc)
            binding.tvAccountActionBadge.visibility = View.VISIBLE
            binding.tvAccountActionBadge.text = getString(R.string.btn_sign_in)
            binding.tvAccountActionBadge.setBackgroundResource(R.drawable.btn_outline_teal)
            binding.tvAccountActionBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary_teal))
        } else {
            // State: Logged in
            if (!user.photoUrl.isNullOrEmpty()) {
                Glide.with(this)
                    .load(user.photoUrl)
                    .transform(CircleCrop())
                    .placeholder(R.drawable.ic_account_circle)
                    .error(R.drawable.ic_account_circle)
                    .into(binding.ivAccountIcon)
            } else {
                binding.ivAccountIcon.setImageResource(R.drawable.ic_account_circle)
            }

            binding.tvAccountTitle.text = user.displayName
            binding.tvAccountSubtitle.text = user.email

            if (user.isVip) {
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
                        val intent = AppAuthManager.getGoogleDriveSignInIntent(requireContext())
                        googleSignInLauncher.launch(intent)
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
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

    private fun performGoogleSignIn() {
        try {
            val intent = AppAuthManager.getGoogleSignInIntent(requireContext())
            googleSignInLauncher.launch(intent)
        } catch (e: Exception) {
            AppAuthManager.signInWithGoogle(
                activity = requireActivity(),
                coroutineScope = viewLifecycleOwner.lifecycleScope,
                onFallbackToIntent = {
                    try {
                        val intent = AppAuthManager.getGoogleSignInIntent(requireContext())
                        googleSignInLauncher.launch(intent)
                    } catch (ex: Exception) {
                        showSignInErrorDialog("Không thể khởi động trình đăng nhập Google: ${ex.message}")
                    }
                },
                onSuccess = {
                    Toast.makeText(requireContext(), getString(R.string.sign_in_success), Toast.LENGTH_SHORT).show()
                },
                onError = { errorMsg ->
                    showSignInErrorDialog(errorMsg)
                }
            )
        }
    }

    private fun showSignInErrorDialog(errorMsg: String) {
        if (!isAdded) return

        AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_TScanner_Dialog)
            .setTitle(R.string.account_sign_in_title)
            .setMessage("$errorMsg\n\nBạn có muốn đăng nhập bằng tài khoản thử nghiệm (Demo Account) để trải nghiệm toàn bộ tính năng và giao diện không?")
            .setPositiveButton("Đăng nhập Demo") { _, _ ->
                AppAuthManager.signInWithDemoAccount(requireContext()) {
                    Toast.makeText(requireContext(), getString(R.string.sign_in_success), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
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
        val openVipDialog = {
            VipUpgradeDialog(
                context = requireContext(),
                onRequestDrivePermission = {
                    try {
                        val intent = AppAuthManager.getGoogleDriveSignInIntent(requireContext())
                        googleSignInLauncher.launch(intent)
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            ).show()
        }

        // VIP Banner click & "Nâng cấp ngay" button
        binding.cardVipBanner.setOnClickListener { openVipDialog() }
        binding.btnVipUpgradeNow.setOnClickListener { openVipDialog() }

        // Item 1: Nâng cấp Vip
        binding.itemVip.setOnClickListener { openVipDialog() }

        // Item 2: Tài khoản Google (Ngay dưới Nâng cấp Vip)
        binding.itemAccount.setOnClickListener {
            handleAccountClick()
        }

        // Item 3: Quản lý tài liệu
        binding.itemDocManagement.setOnClickListener {
            DocumentManagementActivity.start(requireContext())
        }

        // Item 4: Ngôn ngữ
        binding.itemLanguage.setOnClickListener {
            LanguageSelectionDialog(requireActivity()) {
                setupLanguageBadge()
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
        super.onDestroyView()
        _binding = null
    }
}
