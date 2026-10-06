package com.tscanner.app.ui.dialogs

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.tscanner.app.databinding.DialogOcrLanguageSelectionBinding
import com.tscanner.app.utils.OcrLanguageMode
import com.tscanner.app.utils.OcrRoutingResolver
import com.tscanner.app.utils.TextRecognitionHelper

class OcrLanguageSelectionDialog(
    private val activity: Activity,
    private val onLanguageSelected: ((String) -> Unit)? = null
) : Dialog(activity) {

    private lateinit var binding: DialogOcrLanguageSelectionBinding
    private lateinit var adapter: OcrLanguageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogOcrLanguageSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        val displayMetrics = context.resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.92).toInt()
        window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        setupPresetModes()
        setupRecyclerView()
        setupSearch()
        setupListeners()
    }

    private fun setupPresetModes() {
        val currentMode = TextRecognitionHelper.getOcrLanguageMode(context)
        binding.ivCheckAuto.visibility = if (currentMode == OcrLanguageMode.AUTO) View.VISIBLE else View.GONE
        binding.ivCheckViEn.visibility = if (currentMode == OcrLanguageMode.VI_EN) View.VISIBLE else View.GONE

        binding.layoutOcrModeAuto.setOnClickListener {
            TextRecognitionHelper.setOcrLanguageMode(context, OcrLanguageMode.AUTO)
            onLanguageSelected?.invoke("auto")
            dismiss()
        }

        binding.layoutOcrModeViEn.setOnClickListener {
            TextRecognitionHelper.setOcrLanguageMode(context, OcrLanguageMode.VI_EN)
            onLanguageSelected?.invoke("vi+en")
            dismiss()
        }
    }

    private fun setupRecyclerView() {
        val currentMode = TextRecognitionHelper.getOcrLanguageMode(context)
        val selectedTag = if (currentMode == OcrLanguageMode.MANUAL) {
            TextRecognitionHelper.getManualOcrTag(context)
        } else {
            null
        }

        adapter = OcrLanguageAdapter(
            allLanguages = OcrRoutingResolver.SUPPORTED_OCR_DOCUMENT_LANGUAGES,
            selectedTag = selectedTag,
            onFilterChanged = { isEmpty ->
                binding.tvEmptySearch.visibility = if (isEmpty) View.VISIBLE else View.GONE
                binding.rvOcrLanguages.visibility = if (isEmpty) View.GONE else View.VISIBLE
            },
            onLanguageSelected = { selectedLang ->
                TextRecognitionHelper.setOcrLanguageMode(context, OcrLanguageMode.MANUAL, selectedLang.tag)
                onLanguageSelected?.invoke(selectedLang.tag)
                dismiss()
            }
        )

        binding.rvOcrLanguages.layoutManager = LinearLayoutManager(context)
        binding.rvOcrLanguages.adapter = adapter
    }

    private fun setupSearch() {
        binding.etSearchOcrLanguage.doAfterTextChanged { text ->
            adapter.filter(text?.toString().orEmpty(), context)
        }
    }

    private fun setupListeners() {
        binding.btnCloseOcrLanguage.setOnClickListener {
            dismiss()
        }
    }
}
