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

        val sysCode = AppLanguageManager.getSystemLanguageCode()
        val sysLang = AppLanguageManager.getLanguage(sysCode)
        val isSysSupported = AppLanguageManager.isSupported(sysCode)

        val desc = if (isSysSupported) {
            "Tự động theo máy: ${sysLang?.nativeName ?: sysCode} (Hỗ trợ chuẩn)"
        } else {
            "Máy hiện tại ($sysCode) ngoài ngôn ngữ hỗ trợ -> Dùng Tiếng Anh"
        }
        binding.tvSystemDesc.text = desc
    }

    private fun setupRecyclerView() {
        val isSystemDefault = AppLanguageManager.isSystemDefaultSelected(context)
        val activeCode = if (isSystemDefault) null else AppLanguageManager.getCurrentLanguageCode(context)

        adapter = LanguageAdapter(
            allLanguages = AppLanguageManager.SUPPORTED_LANGUAGES,
            selectedCode = activeCode,
            onLanguageSelected = { selectedLang ->
                changeLanguage(selectedLang.code)
            }
        )

        binding.rvLanguages.layoutManager = LinearLayoutManager(context)
        binding.rvLanguages.adapter = adapter
    }

    private fun setupSearch() {
        binding.etSearchLanguage.doAfterTextChanged { text ->
            adapter.filter(text?.toString().orEmpty())
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
        AppLanguageManager.applyLanguage(activity, languageCode)
        onLanguageChanged?.invoke()
    }
}
