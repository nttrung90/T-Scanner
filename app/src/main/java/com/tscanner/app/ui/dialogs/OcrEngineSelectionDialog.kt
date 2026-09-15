package com.tscanner.app.ui.dialogs

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.tscanner.app.databinding.DialogOcrEngineSelectionBinding
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

        binding.ivCheckAuto.visibility = if (currentEngine == TextRecognitionHelper.ENGINE_MODE_AUTO) View.VISIBLE else View.GONE
        binding.ivCheckPaddle.visibility = if (currentEngine == TextRecognitionHelper.ENGINE_MODE_PADDLE) View.VISIBLE else View.GONE
        binding.ivCheckTesseract.visibility = if (currentEngine == TextRecognitionHelper.ENGINE_MODE_TESSERACT) View.VISIBLE else View.GONE
        binding.ivCheckMlkit.visibility = if (currentEngine == TextRecognitionHelper.ENGINE_MODE_MLKIT) View.VISIBLE else View.GONE
    }

    private fun selectEngine(engineMode: String) {
        TextRecognitionHelper.setPreferredEngine(context, engineMode)
        updateSelectionUi()
        onEngineChanged?.invoke()
        dismiss()
    }

    private fun setupListeners() {
        binding.layoutEngineAuto.setOnClickListener {
            selectEngine(TextRecognitionHelper.ENGINE_MODE_AUTO)
        }
        binding.layoutEnginePaddle.setOnClickListener {
            selectEngine(TextRecognitionHelper.ENGINE_MODE_PADDLE)
        }
        binding.layoutEngineTesseract.setOnClickListener {
            selectEngine(TextRecognitionHelper.ENGINE_MODE_TESSERACT)
        }
        binding.layoutEngineMlkit.setOnClickListener {
            selectEngine(TextRecognitionHelper.ENGINE_MODE_MLKIT)
        }
        binding.btnCloseOcrEngine.setOnClickListener {
            dismiss()
        }
    }
}
