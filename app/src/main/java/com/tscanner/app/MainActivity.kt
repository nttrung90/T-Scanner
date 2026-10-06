package com.tscanner.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.tscanner.app.databinding.ActivityMainBinding
import com.tscanner.app.ui.files.FilesFragment
import com.tscanner.app.ui.home.HomeFragment
import com.tscanner.app.ui.more.MoreFragment
import com.tscanner.app.ui.tools.ToolsFragment
import com.tscanner.app.ui.camera.CameraScanActivity
import com.tscanner.app.ui.viewer.PdfViewerActivity
import com.tscanner.app.utils.DocumentScannerHelper
import com.tscanner.app.utils.EdgeToEdgeInsetsHelper
import com.tscanner.app.utils.ScanTarget
import com.tscanner.app.utils.ScanUiPolicy

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var scannerHelper: DocumentScannerHelper
    private lateinit var scannerLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var idCardScannerLauncher: ActivityResultLauncher<IntentSenderRequest>

    private var currentTabId: Int = R.id.nav_home

    fun getCurrentTabId(): Int = currentTabId

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val initialNavPadding = EdgeToEdgeInsetsHelper.recordInitialPadding(binding.customBottomNav)
        val initialContainerMargin = EdgeToEdgeInsetsHelper.recordInitialMargin(binding.fragmentContainer)
        val initialFabMargin = EdgeToEdgeInsetsHelper.recordInitialMargin(binding.fabCamera)

        // Handle window insets across all 4 edges: Status Bar, Display Cutout, and Navigation Bar
        // Guarantees header content is never overlapped by status bar/cutout, and bottom nav is never cut off
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysInsets = EdgeToEdgeInsetsHelper.getSystemBarAndCutoutInsets(insets)
            val effectiveBottom = EdgeToEdgeInsetsHelper.getEffectiveBottomInset(insets, includeIme = false)

            // Adjust bottom navigation padding so navigation buttons are above system navigation bar
            // and avoid horizontal cutouts / side nav in landscape
            EdgeToEdgeInsetsHelper.applyBottomBarInsets(
                binding.customBottomNav,
                initialNavPadding,
                sysInsets,
                effectiveBottom
            )

            // Push fragmentContainer below status bar / cutout and avoid side insets
            val containerLp = binding.fragmentContainer.layoutParams as? android.view.ViewGroup.MarginLayoutParams
            containerLp?.let {
                it.topMargin = initialContainerMargin.top + sysInsets.top
                it.leftMargin = initialContainerMargin.left + sysInsets.left
                it.rightMargin = initialContainerMargin.right + sysInsets.right
                binding.fragmentContainer.layoutParams = it
            }

            binding.customBottomNav.post {
                val navHeight = binding.customBottomNav.measuredHeight
                if (navHeight > 0) {
                    val fabLp = binding.fabCamera.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                    fabLp?.let {
                        it.bottomMargin = navHeight + initialFabMargin.bottom
                        it.rightMargin = initialFabMargin.right + sysInsets.right
                        binding.fabCamera.layoutParams = it
                    }

                    val cLp = binding.fragmentContainer.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                    cLp?.let {
                        it.topMargin = initialContainerMargin.top + sysInsets.top
                        it.bottomMargin = navHeight
                        it.leftMargin = initialContainerMargin.left + sysInsets.left
                        it.rightMargin = initialContainerMargin.right + sysInsets.right
                        binding.fragmentContainer.layoutParams = it
                    }
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        scannerHelper = DocumentScannerHelper(this)
        scannerLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            scannerHelper.handleScanResult(
                result = result,
                onSuccess = { session ->
                    if (session.tempPagePaths.isNotEmpty() && session.tempPagePaths.size == session.totalPagesExpected) {
                        com.tscanner.app.ui.editor.PostScanEditorActivity.start(
                            context = this,
                            sessionId = session.sessionId,
                            pagePaths = session.tempPagePaths
                        )
                    } else {
                        Toast.makeText(this, getString(R.string.camera_no_pages_scanned), Toast.LENGTH_SHORT).show()
                    }
                },
                onCancelled = {
                    Toast.makeText(this, getString(R.string.scanner_cancelled), Toast.LENGTH_SHORT).show()
                },
                onError = { err ->
                    Toast.makeText(this, getString(R.string.scanner_error, err), Toast.LENGTH_LONG).show()
                }
            )
        }

        idCardScannerLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            scannerHelper.handleScanResult(
                result = result,
                onSuccess = { session ->
                    if (session.tempPagePaths.isNotEmpty() && session.tempPagePaths.size == session.totalPagesExpected) {
                        com.tscanner.app.ui.idcard.IdCardComposeActivity.start(
                            context = this,
                            pagePaths = session.tempPagePaths
                        )
                    } else {
                        Toast.makeText(this, getString(R.string.camera_no_id_cards_captured), Toast.LENGTH_SHORT).show()
                    }
                },
                onCancelled = {
                    Toast.makeText(this, getString(R.string.scanner_cancelled), Toast.LENGTH_SHORT).show()
                },
                onError = { err ->
                    Toast.makeText(this, getString(R.string.scanner_error, err), Toast.LENGTH_LONG).show()
                }
            )
        }

        setupBottomNav()
        setupFab()

        if (savedInstanceState != null) {
            val restoredTabId = savedInstanceState.getInt(KEY_SELECTED_TAB_ID, R.id.nav_home)
            val validTabId = if (isValidTabId(restoredTabId)) restoredTabId else R.id.nav_home
            currentTabId = validTabId
            updateBottomNavUi(validTabId)
            val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
            if (currentFragment == null || !isMatchingFragmentForTab(validTabId, currentFragment)) {
                val targetFragment = getFragmentForTab(validTabId)
                showFragment(targetFragment, getTagForTab(validTabId))
            }
        } else {
            selectTab(R.id.nav_home)
        }
    }

    private fun setupBottomNav() {
        binding.btnNavHome.setOnClickListener {
            selectTab(R.id.nav_home)
        }
        binding.btnNavFiles.setOnClickListener {
            selectTab(R.id.nav_files)
        }
        binding.btnNavTools.setOnClickListener {
            selectTab(R.id.nav_tools)
        }
        binding.btnNavMore.setOnClickListener {
            selectTab(R.id.nav_more)
        }
    }

    private fun setupFab() {
        binding.fabCamera.setOnClickListener {
            binding.fabCamera.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            startDocumentScan()
        }
        binding.fabCamera.setOnLongClickListener {
            Toast.makeText(this, getString(R.string.camera_opening_fast_scanner), Toast.LENGTH_SHORT).show()
            startFastDocumentScan()
            true
        }
    }

    fun startDocumentScan() {
        when (ScanUiPolicy.resolveDocumentScanTarget(this)) {
            ScanTarget.GOOGLE_AI -> startGoogleAiScan()
            ScanTarget.INTERNAL_CAMERA -> startFastDocumentScan()
        }
    }

    fun startGoogleAiScan() {
        scannerHelper.startScan(scannerLauncher) { err ->
            Toast.makeText(this, getString(R.string.scanner_error, err), Toast.LENGTH_LONG).show()
        }
    }

    fun startFastDocumentScan() {
        CameraScanActivity.start(this)
    }

    fun showScanOptionsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_scan_options, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Option 1: Quét AI (Mặc định)
        dialogView.findViewById<View>(R.id.btn_option_scan_ai)?.setOnClickListener {
            dialog.dismiss()
            startGoogleAiScan()
        }

        // Option 2: Camera Siêu Tốc (Tùy chọn)
        dialogView.findViewById<View>(R.id.btn_option_scan_fast)?.setOnClickListener {
            dialog.dismiss()
            startFastDocumentScan()
        }

        dialog.show()
    }

    fun startIdCardScan() {
        when (ScanUiPolicy.resolveIdCardScanTarget(this)) {
            ScanTarget.GOOGLE_AI -> startGoogleIdCardScan()
            ScanTarget.INTERNAL_CAMERA -> startFastIdCardScan()
        }
    }

    fun startFastIdCardScan() {
        CameraScanActivity.startForIdCard(this)
    }

    fun startGoogleIdCardScan() {
        scannerHelper.startIdCardScan(idCardScannerLauncher) { err ->
            Toast.makeText(this, getString(R.string.scanner_error, err), Toast.LENGTH_LONG).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SELECTED_TAB_ID, currentTabId)
    }

    fun navigateToMoreForVipSignIn(
        action: String? = null,
        forceReauth: Boolean = false,
        authReason: String? = null,
        expectedOwnerId: String? = null,
        originGeneration: Long = com.tscanner.app.utils.AppAuthManager.getSessionGeneration(),
        processEpoch: String = com.tscanner.app.utils.AppAuthManager.getProcessEpoch(),
        operationId: String? = null
    ) {
        supportFragmentManager.setFragmentResult(
            MoreFragment.REQUEST_KEY_VIP_SIGN_IN,
            Bundle().apply {
                putBoolean(MoreFragment.EXTRA_AUTO_START_SIGN_IN, true)
                if (action != null) {
                    putString(MoreFragment.EXTRA_VIP_ACTION, action)
                }
                putBoolean(MoreFragment.EXTRA_FORCE_REAUTH, forceReauth)
                if (authReason != null) {
                    putString(MoreFragment.EXTRA_AUTH_REQUIRED_REASON, authReason)
                }
                if (expectedOwnerId != null) {
                    putString(MoreFragment.EXTRA_EXPECTED_OWNER_ID, expectedOwnerId)
                }
                putLong(MoreFragment.EXTRA_ORIGIN_GENERATION, originGeneration)
                putString(MoreFragment.EXTRA_PROCESS_EPOCH, processEpoch)
                if (operationId != null) {
                    putString(MoreFragment.EXTRA_OPERATION_ID, operationId)
                }
            }
        )
        selectTab(R.id.nav_more)
    }

    fun selectTab(tabId: Int) {
        val validTabId = if (isValidTabId(tabId)) tabId else R.id.nav_home
        val isSameTab = (currentTabId == validTabId)
        currentTabId = validTabId
        updateBottomNavUi(validTabId)

        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        // Nếu đã ở đúng tab và Fragment trong container đã là đúng loại, tránh replace làm mất trạng thái cục bộ!
        if (isSameTab && currentFragment != null && isMatchingFragmentForTab(validTabId, currentFragment)) {
            return
        }

        val targetFragment = getFragmentForTab(validTabId)
        showFragment(targetFragment, getTagForTab(validTabId))
    }

    fun isMatchingFragmentForTab(tabId: Int, fragment: Fragment): Boolean {
        return when (tabId) {
            R.id.nav_home -> fragment is HomeFragment
            R.id.nav_files -> fragment is FilesFragment
            R.id.nav_tools -> fragment is ToolsFragment
            R.id.nav_more -> fragment is MoreFragment
            else -> false
        }
    }

    fun getFragmentForTab(tabId: Int): Fragment {
        val tag = getTagForTab(tabId)
        // 1. Tái sử dụng Fragment đã có trong FragmentManager theo tag
        val existingByTag = supportFragmentManager.findFragmentByTag(tag)
        if (existingByTag != null && isMatchingFragmentForTab(tabId, existingByTag)) {
            return existingByTag
        }

        // 2. Tái sử dụng Fragment đã được phục hồi trong container
        val currentInContainer = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (currentInContainer != null && isMatchingFragmentForTab(tabId, currentInContainer)) {
            return currentInContainer
        }

        // 3. Chỉ tạo mới nếu chưa tồn tại
        return when (tabId) {
            R.id.nav_home -> HomeFragment()
            R.id.nav_files -> FilesFragment()
            R.id.nav_tools -> ToolsFragment()
            R.id.nav_more -> MoreFragment()
            else -> HomeFragment()
        }
    }

    fun updateBottomNavUi(tabId: Int) {
        val colorActive = ContextCompat.getColor(this, R.color.primary_teal)
        val colorInactive = ContextCompat.getColor(this, R.color.text_secondary)

        // Reset all tabs to inactive state
        binding.ivNavHome.imageTintList = ColorStateList.valueOf(colorInactive)
        binding.tvNavHome.setTextColor(colorInactive)
        binding.tvNavHome.typeface = Typeface.DEFAULT

        binding.ivNavFiles.imageTintList = ColorStateList.valueOf(colorInactive)
        binding.tvNavFiles.setTextColor(colorInactive)
        binding.tvNavFiles.typeface = Typeface.DEFAULT

        binding.ivNavTools.imageTintList = ColorStateList.valueOf(colorInactive)
        binding.tvNavTools.setTextColor(colorInactive)
        binding.tvNavTools.typeface = Typeface.DEFAULT

        binding.ivNavMore.imageTintList = ColorStateList.valueOf(colorInactive)
        binding.tvNavMore.setTextColor(colorInactive)
        binding.tvNavMore.typeface = Typeface.DEFAULT

        // Highlight selected tab
        when (tabId) {
            R.id.nav_home -> {
                binding.ivNavHome.imageTintList = ColorStateList.valueOf(colorActive)
                binding.tvNavHome.setTextColor(colorActive)
                binding.tvNavHome.typeface = Typeface.DEFAULT_BOLD
            }
            R.id.nav_files -> {
                binding.ivNavFiles.imageTintList = ColorStateList.valueOf(colorActive)
                binding.tvNavFiles.setTextColor(colorActive)
                binding.tvNavFiles.typeface = Typeface.DEFAULT_BOLD
            }
            R.id.nav_tools -> {
                binding.ivNavTools.imageTintList = ColorStateList.valueOf(colorActive)
                binding.tvNavTools.setTextColor(colorActive)
                binding.tvNavTools.typeface = Typeface.DEFAULT_BOLD
            }
            R.id.nav_more -> {
                binding.ivNavMore.imageTintList = ColorStateList.valueOf(colorActive)
                binding.tvNavMore.setTextColor(colorActive)
                binding.tvNavMore.typeface = Typeface.DEFAULT_BOLD
            }
        }
    }

    private fun showFragment(fragment: Fragment, tag: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment, tag)
            .commit()
    }

    companion object {
        const val KEY_SELECTED_TAB_ID = "key_selected_tab_id"
        const val TAG_HOME = "tag_nav_home"
        const val TAG_FILES = "tag_nav_files"
        const val TAG_TOOLS = "tag_nav_tools"
        const val TAG_MORE = "tag_nav_more"

        fun isValidTabId(tabId: Int): Boolean = when (tabId) {
            R.id.nav_home, R.id.nav_files, R.id.nav_tools, R.id.nav_more -> true
            else -> false
        }

        fun getTagForTab(tabId: Int): String = when (tabId) {
            R.id.nav_home -> TAG_HOME
            R.id.nav_files -> TAG_FILES
            R.id.nav_tools -> TAG_TOOLS
            R.id.nav_more -> TAG_MORE
            else -> TAG_HOME
        }
    }
}
