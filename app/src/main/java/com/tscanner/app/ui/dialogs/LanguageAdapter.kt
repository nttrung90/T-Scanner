package com.tscanner.app.ui.dialogs

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tscanner.app.R
import com.tscanner.app.databinding.ItemLanguageSelectionBinding
import com.tscanner.app.utils.OcrType
import com.tscanner.app.utils.SupportedLanguage
import java.text.Normalizer
import java.util.Locale

class LanguageAdapter(
    private val allLanguages: List<SupportedLanguage>,
    private var selectedCode: String?,
    private val onLanguageSelected: (SupportedLanguage) -> Unit
) : RecyclerView.Adapter<LanguageAdapter.LanguageViewHolder>() {

    private var filteredLanguages: List<SupportedLanguage> = allLanguages.toList()

    fun setSelectedCode(code: String?) {
        selectedCode = code
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        val cleanQuery = removeDiacritics(query.trim().lowercase(Locale.ROOT))
        filteredLanguages = if (cleanQuery.isEmpty()) {
            allLanguages.toList()
        } else {
            allLanguages.filter { item ->
                removeDiacritics(item.nativeName.lowercase(Locale.ROOT)).contains(cleanQuery) ||
                removeDiacritics(item.vietnameseName.lowercase(Locale.ROOT)).contains(cleanQuery) ||
                item.code.lowercase(Locale.ROOT).contains(cleanQuery)
            }
        }
        notifyDataSetChanged()
    }

    private fun removeDiacritics(str: String): String {
        val nfd = Normalizer.normalize(str, Normalizer.Form.NFD)
        return nfd.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LanguageViewHolder {
        val binding = ItemLanguageSelectionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LanguageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LanguageViewHolder, position: Int) {
        holder.bind(filteredLanguages[position])
    }

    override fun getItemCount(): Int = filteredLanguages.size

    inner class LanguageViewHolder(private val binding: ItemLanguageSelectionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SupportedLanguage) {
            binding.tvLangFlag.text = item.flag
            binding.tvLangNativeName.text = item.nativeName
            binding.tvLangSubName.text = "${item.vietnameseName} (${item.code})"

            when (item.ocrType) {
                OcrType.TESSERACT_PRIMARY -> {
                    binding.tvEngineBadge.text = "Tesseract v5 LSTM"
                    binding.tvEngineBadge.setTextColor(binding.root.context.getColor(R.color.primary_teal))
                }
                OcrType.PADDLE_OCR_V4 -> {
                    binding.tvEngineBadge.text = "PaddleOCR v4"
                    binding.tvEngineBadge.setTextColor(binding.root.context.getColor(R.color.primary_teal))
                }
                OcrType.MLKIT_LATIN -> {
                    binding.tvEngineBadge.text = "ML Kit Latin"
                    binding.tvEngineBadge.setTextColor(binding.root.context.getColor(R.color.icon_image_fg))
                }
                OcrType.PLAY_SERVICES_DEVANAGARI -> {
                    binding.tvEngineBadge.text = "Play Services OCR"
                    binding.tvEngineBadge.setTextColor(binding.root.context.getColor(R.color.icon_ppt_fg))
                }
                OcrType.UNSUPPORTED_ON_DEVICE -> {
                    binding.tvEngineBadge.text = "Chưa có OCR"
                    binding.tvEngineBadge.setTextColor(binding.root.context.getColor(R.color.text_muted))
                }
            }

            val isSelected = (selectedCode == item.code)
            binding.ivLangCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

            binding.root.setOnClickListener {
                onLanguageSelected(item)
            }
        }
    }
}
