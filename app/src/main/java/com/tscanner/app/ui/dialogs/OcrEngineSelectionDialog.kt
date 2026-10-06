package com.tscanner.app.ui.dialogs

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.tscanner.app.databinding.DialogOcrEngineSelectionBinding
import com.tscanner.app.utils.OcrRoutingResolver
import com.tscanner.app.utils.TextRecognitionHelper

class OcrEngineSelectionDialog(
    private val activity: Activity,
    private val onEngineChanged: (() -> Unit)? = null
) : Dialog(activity) {

    private lateinit var binding: DialogOcrEngineSelectionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogOcrEngineSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        val displayMetrics = context.resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.92).toInt()
        window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        updateSelectionUi()
        setupListeners()
    }

    private fun updateSelectionUi() {
        val currentEngine = TextRecognitionHelper.getPreferredEngine(context)
        val docLang = TextRecognitionHelper.getOcrDocumentLanguage(context)
        val ocrType = OcrRoutingResolver.getOcrTypeForLanguage(docLang)

        binding.ivCheckAuto.visibility = if (currentEngine == TextRecognitionHelper.ENGINE_MODE_AUTO) View.VISIBLE else View.GONE
        binding.ivCheckTesseract.visibility = if (currentEngine == TextRecognitionHelper.ENGINE_MODE_TESSERACT) View.VISIBLE else View.GONE
        binding.ivCheckMlkit.visibility = if (currentEngine == TextRecognitionHelper.ENGINE_MODE_MLKIT) View.VISIBLE else View.GONE

        val isTessCompat = OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_TESSERACT, ocrType)
        val isMlkitCompat = OcrRoutingResolver.isEngineCompatible(TextRecognitionHelper.ENGINE_MODE_MLKIT, ocrType)

        binding.layoutEngineTesseract.alpha = if (isTessCompat) 1.0f else 0.45f
        binding.layoutEngineMlkit.alpha = if (isMlkitCompat) 1.0f else 0.45f
    }

    private fun selectEngine(engineMode: String, engineNameRes: Int) {
        val docLang = TextRecognitionHelper.getOcrDocumentLanguage(context)
        val ocrType = OcrRoutingResolver.getOcrTypeForLanguage(docLang)
        if (engineMode != TextRecognitionHelper.ENGINE_MODE_AUTO &&
            !OcrRoutingResolver.isEngineCompatible(engineMode, ocrType)
        ) {
            val engineName = context.getString(engineNameRes)
            val langName = TextRecognitionHelper.getOcrDocumentLanguageDisplayName(context)
            android.widget.Toast.makeText(
                context,
                context.getString(com.tscanner.app.R.string.ocr_engine_incompatible, engineName, langName),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        TextRecognitionHelper.setPreferredEngine(context, engineMode)
        updateSelectionUi()
        onEngineChanged?.invoke()
        dismiss()
    }

    private fun setupListeners() {
        binding.layoutEngineAuto.setOnClickListener {
            selectEngine(TextRecognitionHelper.ENGINE_MODE_AUTO, com.tscanner.app.R.string.ocr_engine_auto)
        }
        binding.layoutEngineTesseract.setOnClickListener {
            selectEngine(TextRecognitionHelper.ENGINE_MODE_TESSERACT, com.tscanner.app.R.string.ocr_engine_tesseract)
        }
        binding.layoutEngineMlkit.setOnClickListener {
            selectEngine(TextRecognitionHelper.ENGINE_MODE_MLKIT, com.tscanner.app.R.string.ocr_engine_mlkit)
        }
        binding.btnCloseOcrEngine.setOnClickListener {
            dismiss()
        }
    }
}
