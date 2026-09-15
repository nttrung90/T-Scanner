package com.tscanner.app

import android.app.Application
import android.util.Log
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.TesseractOcrHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TScannerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize DocumentRepo singleton
        DocumentRepo.getInstance(this)

        // Initialize App Language (default to device language, fallback to English if outside 31 languages)
        com.tscanner.app.utils.AppLanguageManager.initAppLanguage(this)

        // Initialize Authentication State
        com.tscanner.app.utils.AppAuthManager.init(this)

        // If user is VIP, trigger cross-device sync in background on app start
        if (com.tscanner.app.utils.AppAuthManager.isUserVip()) {
            com.tscanner.app.utils.CloudBackupManager.syncCatalogFromDrive(this) { count ->
                if (count > 0) {
                    Log.d("TScannerApplication", "Synced $count new documents from Google Drive")
                }
            }
        }

        // Pre-extract Tesseract traineddata in background so OCR is ready immediately
        CoroutineScope(Dispatchers.IO).launch {
            try {
                TesseractOcrHelper.prepareTessData(this@TScannerApplication)
                Log.d("TScannerApplication", "Tesseract traineddata pre-extraction completed")
            } catch (t: Throwable) {
                Log.e("TScannerApplication", "Failed to pre-extract Tesseract data: ${t.message}")
            }
        }

        // Pre-initialize PaddleOCR v4 Mobile engine in background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                com.tscanner.app.paddleocr.PaddleOcrEngine.initialize(this@TScannerApplication)
            } catch (t: Throwable) {
                Log.e("TScannerApplication", "Failed to pre-initialize PaddleOCR: ${t.message}")
            }
        }

        // Clean orphaned temporary scan sessions and preview cache from previous sessions
        CoroutineScope(Dispatchers.IO).launch {
            try {
                com.tscanner.app.utils.FileUtils.cleanOrphanedTempScans(this@TScannerApplication)
            } catch (t: Throwable) {
                Log.e("TScannerApplication", "Failed to clean orphaned temp scans: ${t.message}")
            }
        }
    }
}
