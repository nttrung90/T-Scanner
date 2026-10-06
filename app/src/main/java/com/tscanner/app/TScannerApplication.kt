package com.tscanner.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.AppLanguageManager
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.TesseractOcrHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TScannerApplication : Application() {

    companion object {
        private const val TAG = "TScannerApplication"
        const val FOREGROUND_SYNC_THROTTLE_MS = 30_000L

        @VisibleForTesting
        var foregroundSyncActionForTesting: (() -> Unit)? = null
    }

    private var startedActivityCount = 0
    private var lastForegroundSyncTimeMs = 0L

    override fun onCreate() {
        super.onCreate()
        // Initialize DocumentRepo singleton
        DocumentRepo.getInstance(this)

        // Initialize App Language (thực thi tự động theo ngôn ngữ chính hệ thống - E03)
        AppLanguageManager.initAppLanguage(this)

        // Đăng ký lifecycle callback để đảm bảo locale hiệu lực luôn đồng bộ theo máy và cập nhật khi đổi ngôn ngữ ở nền
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                AppLanguageManager.enforceAutoSystemLanguage(activity)
            }
            override fun onActivityStarted(activity: Activity) {
                handleActivityStarted()
            }
            override fun onActivityResumed(activity: Activity) {
                AppLanguageManager.enforceAutoSystemLanguage(activity)
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                handleActivityStopped()
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Initialize Authentication State
        com.tscanner.app.utils.AppAuthManager.init(this)

        // Bootstrap Play Purchase Verifier before BillingManager initialization (F02)
        try {
            val configuredUrl = BuildConfig.BILLING_VERIFIER_URL.ifBlank {
                try {
                    val resId = resources.getIdentifier("billing_verifier_url", "string", packageName)
                    if (resId != 0) getString(resId) else ""
                } catch (e: Exception) { "" }
            }.ifBlank {
                System.getProperty("tscanner.billing.verifier.url", "")
            }

            com.tscanner.app.utils.billing.PlayPurchaseVerifier.configure(
                backendUrl = configuredUrl.takeIf { it.isNotBlank() },
                tokenProvider = { com.tscanner.app.utils.AppAuthManager.getSessionToken() },
                sessionGenerationProvider = { com.tscanner.app.utils.AppAuthManager.getSessionGeneration() },
                ownerProvider = { com.tscanner.app.utils.AppAuthManager.getSessionOwnerId() },
                allowLocalFallback = configuredUrl.isBlank()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bootstrap PlayPurchaseVerifier", e)
        }

        // Sync Google Play Billing purchases silently on startup (F09)
        try {
            BillingManager.getInstance(this).syncPurchasesOnStart()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync billing purchases on startup", e)
        }

        // If user is VIP, trigger cross-device sync in background on app start
        if (com.tscanner.app.utils.AppAuthManager.isUserVip()) {
            com.tscanner.app.utils.CloudBackupManager.syncCatalogFromDrive(this) { count ->
                if (count > 0) {
                    Log.d(TAG, "Synced $count new documents from Google Drive")
                }
            }
        }

        // Pre-extract Tesseract traineddata in background so OCR is ready immediately
        CoroutineScope(Dispatchers.IO).launch {
            try {
                TesseractOcrHelper.prepareTessData(this@TScannerApplication)
                Log.d(TAG, "Tesseract traineddata pre-extraction completed")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to pre-extract Tesseract data: ${t.message}")
            }
        }

        // Clean orphaned temporary scan sessions and preview cache from previous sessions
        CoroutineScope(Dispatchers.IO).launch {
            try {
                com.tscanner.app.utils.FileUtils.cleanOrphanedTempScans(this@TScannerApplication)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to clean orphaned temp scans: ${t.message}")
            }
        }

        // Clean legacy PaddleOCR on-disk models safely in background (P05)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                com.tscanner.app.utils.LegacyPaddleCleanup.cleanupLegacyPaddleFiles(this@TScannerApplication)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to clean legacy Paddle data: ${t.message}")
            }
        }
    }

    fun handleActivityStarted() {
        startedActivityCount++
        if (startedActivityCount == 1) {
            val now = System.currentTimeMillis()
            if (now - lastForegroundSyncTimeMs >= FOREGROUND_SYNC_THROTTLE_MS) {
                lastForegroundSyncTimeMs = now
                triggerForegroundSync()
            }
        }
    }

    fun handleActivityStopped() {
        startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
    }

    private fun triggerForegroundSync() {
        Log.d(TAG, "App transitioned to foreground: syncing Play Billing purchases (F09)")
        val testAction = foregroundSyncActionForTesting
        if (testAction != null) {
            testAction()
            return
        }
        try {
            BillingManager.getInstance(this).syncPurchases()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync billing purchases on foreground", e)
        }
    }
}
