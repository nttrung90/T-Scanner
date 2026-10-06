package com.tscanner.app.ui.dialogs

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.tscanner.app.databinding.DialogLanguageSelectionBinding
import com.tscanner.app.utils.AppLanguageManager
import com.tscanner.app.utils.UiLanguageMode
import java.util.Locale

class LanguageSelectionDialog(
    private val activity: Activity,
    private val onLanguageChanged: (() -> Unit)? = null
) : Dialog(activity) {

    private lateinit var binding: DialogLanguageSelectionBinding
    private lateinit var adapter: LanguageAdapter

    companion object {
        fun getCurrentLanguageDisplayName(context: Context): String {
            return AppLanguageManager.getCurrentLanguageDisplayName(context)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogLanguageSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        val displayMetrics = context.resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.92).toInt()
        window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        setupSystemDefaultOption()
        setupRecyclerView()
        setupSearch()
        setupListeners()
    }

    private fun setupSystemDefaultOption() {
        val isSystemDefault = AppLanguageManager.isSystemDefaultSelected(context)
        binding.ivCheckSystem.visibility = if (isSystemDefault) View.VISIBLE else View.GONE

        val trueSystemLocales = AppLanguageManager.getTrueSystemLocales(context)
        val targetAutoTag = AppLanguageManager.resolvePrimaryAutoUiLanguage(trueSystemLocales)
        val autoLang = AppLanguageManager.getAutoUiLanguage(targetAutoTag)
        val resolvedNativeName = autoLang?.nativeName ?: "English"

        val primaryLocale = trueSystemLocales.firstOrNull() ?: Locale.getDefault()
        val primaryNormalized = AppLanguageManager.normalizeTag(primaryLocale.language)

        val desc = if (primaryNormalized != null) {
            context.getString(com.tscanner.app.R.string.language_system_supported_format, resolvedNativeName)
        } else {
            val rawName = primaryLocale.getDisplayLanguage(primaryLocale).ifBlank { primaryLocale.language }
            context.getString(com.tscanner.app.R.string.language_system_unsupported_format, rawName)
        }
        binding.tvSystemDesc.text = desc
    }

    private fun setupRecyclerView() {
        val isSystemDefault = AppLanguageManager.isSystemDefaultSelected(context)
        val activeCode = if (isSystemDefault) null else AppLanguageManager.getManualUiTag(context)

        adapter = LanguageAdapter(
            allLanguages = AppLanguageManager.SUPPORTED_LANGUAGES,
            selectedCode = activeCode,
            onFilterChanged = { isEmpty ->
                binding.tvEmptySearch.visibility = if (isEmpty) View.VISIBLE else View.GONE
                binding.rvLanguages.visibility = if (isEmpty) View.GONE else View.VISIBLE
            },
            onLanguageSelected = { selectedLang ->
                changeLanguage(selectedLang.tag)
            }
        )

        binding.rvLanguages.layoutManager = LinearLayoutManager(context)
        binding.rvLanguages.adapter = adapter
    }

    private fun setupSearch() {
        binding.etSearchLanguage.doAfterTextChanged { text ->
            adapter.filter(text?.toString().orEmpty(), context)
        }
    }

    private fun setupListeners() {
        binding.layoutLangSystem.setOnClickListener {
            changeLanguage(null)
        }

        binding.btnCloseLanguage.setOnClickListener {
            dismiss()
        }
    }

    private fun changeLanguage(languageCode: String?) {
        dismiss()
        if (languageCode == null || languageCode == AppLanguageManager.CODE_SYSTEM) {
            AppLanguageManager.setUiLanguage(activity, UiLanguageMode.SYSTEM)
        } else {
            AppLanguageManager.setUiLanguage(activity, UiLanguageMode.MANUAL, languageCode)
        }
        onLanguageChanged?.invoke()
    }
}
