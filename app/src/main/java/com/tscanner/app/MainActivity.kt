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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var scannerHelper: DocumentScannerHelper
    private lateinit var scannerLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var idCardScannerLauncher: ActivityResultLauncher<IntentSenderRequest>

    private val homeFragment = HomeFragment()
    private val filesFragment = FilesFragment()
    private val toolsFragment = ToolsFragment()
    private val moreFragment = MoreFragment()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val basePaddingBottom = (8 * resources.displayMetrics.density).toInt()
        val baseFabMarginBottom = (16 * resources.displayMetrics.density).toInt()

        // Handle window insets for both Status Bar (top) and Navigation Bar (bottom)
        // Guarantees header content is never overlapped by status bar/cutout, and bottom nav is never cut off
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInsets = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            // Push fragmentContainer below status bar to completely prevent overlap
            val containerLp = binding.fragmentContainer.layoutParams as? android.view.ViewGroup.MarginLayoutParams
            containerLp?.let {
                it.topMargin = statusBarInsets.top
                binding.fragmentContainer.layoutParams = it
            }

            // Adjust bottom navigation padding so navigation buttons are above system navigation bar
            val totalBottomPadding = basePaddingBottom + navInsets.bottom
            binding.customBottomNav.setPadding(
                binding.customBottomNav.paddingLeft,
                binding.customBottomNav.paddingTop,
                binding.customBottomNav.paddingRight,
                totalBottomPadding
            )

            binding.customBottomNav.post {
                val navHeight = binding.customBottomNav.measuredHeight
                if (navHeight > 0) {
                    val fabLp = binding.fabCamera.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                    fabLp?.let {
                        it.bottomMargin = navHeight + baseFabMarginBottom
                        binding.fabCamera.layoutParams = it
                    }

                    val cLp = binding.fragmentContainer.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                    cLp?.let {
                        it.topMargin = statusBarInsets.top
                        it.bottomMargin = navHeight
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
                    if (session.tempPagePaths.isNotEmpty()) {
                        com.tscanner.app.ui.editor.PostScanEditorActivity.start(
                            context = this,
                            sessionId = session.sessionId,
                            pagePaths = session.tempPagePaths
                        )
                    } else {
                        Toast.makeText(this, "Không có trang nào được quét", Toast.LENGTH_SHORT).show()
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
                    if (session.tempPagePaths.isNotEmpty()) {
                        com.tscanner.app.ui.idcard.IdCardComposeActivity.start(
                            context = this,
                            pagePaths = session.tempPagePaths
                        )
                    } else {
                        Toast.makeText(this, "Không có ảnh thẻ nào được chụp", Toast.LENGTH_SHORT).show()
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

        // Set default tab to Home
        if (savedInstanceState == null) {
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
            startGoogleAiScan()
        }
        binding.fabCamera.setOnLongClickListener {
            Toast.makeText(this, "Đang mở Camera Siêu Tốc...", Toast.LENGTH_SHORT).show()
            startFastDocumentScan()
            true
        }
    }

    fun startDocumentScan() {
        startGoogleAiScan()
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
        startGoogleIdCardScan()
    }

    fun startFastIdCardScan() {
        CameraScanActivity.startForIdCard(this)
    }

    fun startGoogleIdCardScan() {
        scannerHelper.startIdCardScan(idCardScannerLauncher) { err ->
            Toast.makeText(this, getString(R.string.scanner_error, err), Toast.LENGTH_LONG).show()
        }
    }

    fun selectTab(tabId: Int) {
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

        // Highlight selected tab & switch fragment
        when (tabId) {
            R.id.nav_home -> {
                binding.ivNavHome.imageTintList = ColorStateList.valueOf(colorActive)
                binding.tvNavHome.setTextColor(colorActive)
                binding.tvNavHome.typeface = Typeface.DEFAULT_BOLD
                showFragment(homeFragment)
            }
            R.id.nav_files -> {
                binding.ivNavFiles.imageTintList = ColorStateList.valueOf(colorActive)
                binding.tvNavFiles.setTextColor(colorActive)
                binding.tvNavFiles.typeface = Typeface.DEFAULT_BOLD
                showFragment(filesFragment)
            }
            R.id.nav_tools -> {
                binding.ivNavTools.imageTintList = ColorStateList.valueOf(colorActive)
                binding.tvNavTools.setTextColor(colorActive)
                binding.tvNavTools.typeface = Typeface.DEFAULT_BOLD
                showFragment(toolsFragment)
            }
            R.id.nav_more -> {
                binding.ivNavMore.imageTintList = ColorStateList.valueOf(colorActive)
                binding.tvNavMore.setTextColor(colorActive)
                binding.tvNavMore.typeface = Typeface.DEFAULT_BOLD
                showFragment(moreFragment)
            }
        }
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}
