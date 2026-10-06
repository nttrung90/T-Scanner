package com.tscanner.app.ui.dialogs

import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tscanner.app.databinding.ItemOcrLanguageSelectionBinding
import com.tscanner.app.utils.OcrDocumentLanguage
import com.tscanner.app.utils.OcrRoutingResolver
import com.tscanner.app.utils.TextRecognitionHelper
import java.text.Normalizer
import java.util.Locale

class OcrLanguageAdapter(
    private val allLanguages: List<OcrDocumentLanguage>,
    private var selectedTag: String?,
    private val onFilterChanged: ((isEmpty: Boolean) -> Unit)? = null,
    private val onLanguageSelected: (OcrDocumentLanguage) -> Unit
) : RecyclerView.Adapter<OcrLanguageAdapter.OcrLanguageViewHolder>() {

    private var filteredLanguages: List<OcrDocumentLanguage> = allLanguages.toList()

    fun setSelectedTag(tag: String?) {
        selectedTag = tag
        notifyDataSetChanged()
    }

    fun filter(query: String, context: Context) {
        val cleanQuery = removeDiacritics(query.trim().lowercase(Locale.ROOT))
        filteredLanguages = if (cleanQuery.isEmpty()) {
            allLanguages.toList()
        } else {
            allLanguages.filter { item ->
                val nativeClean = removeDiacritics(item.nativeName.lowercase(Locale.ROOT))
                val vietnameseClean = removeDiacritics(item.vietnameseName.lowercase(Locale.ROOT))
                val englishClean = removeDiacritics(item.englishName.lowercase(Locale.ROOT))
                val localizedClean = removeDiacritics(getLocalizedName(context, item).lowercase(Locale.ROOT))
                val tagClean = item.tag.lowercase(Locale.ROOT)

                nativeClean.contains(cleanQuery) ||
                vietnameseClean.contains(cleanQuery) ||
                englishClean.contains(cleanQuery) ||
                localizedClean.contains(cleanQuery) ||
                tagClean.contains(cleanQuery)
            }
        }
        onFilterChanged?.invoke(filteredLanguages.isEmpty())
        notifyDataSetChanged()
    }

    companion object {
        fun removeDiacritics(str: String): String {
            val nfd = Normalizer.normalize(str, Normalizer.Form.NFD)
            return nfd.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
                .replace('đ', 'd')
                .replace('Đ', 'D')
        }

        fun getLocalizedName(context: Context, item: OcrDocumentLanguage): String {
            return OcrRoutingResolver.getLocalizedDocumentLanguageName(context, item.tag)
        }

        fun getEngineBadge(item: OcrDocumentLanguage): String {
            return when (item.tag) {
                "vi", "en" -> "Tesseract / ML Kit"
                else -> "ML Kit"
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OcrLanguageViewHolder {
        val binding = ItemOcrLanguageSelectionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return OcrLanguageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OcrLanguageViewHolder, position: Int) {
        holder.bind(filteredLanguages[position])
    }

    override fun getItemCount(): Int = filteredLanguages.size

    inner class OcrLanguageViewHolder(private val binding: ItemOcrLanguageSelectionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: OcrDocumentLanguage) {
            binding.tvLangFlag.text = item.flag
            val localized = getLocalizedName(binding.root.context, item)
            // Hiển thị tên bản địa hóa theo UI làm tiêu đề chính
            binding.tvLangNativeName.text = localized
            // Hiển thị nativeName và tag làm thông tin phụ bổ trợ
            binding.tvLangSubName.text = if (localized.equals(item.nativeName, ignoreCase = true)) {
                item.tag
            } else {
                "${item.nativeName} (${item.tag})"
            }

            binding.tvLangEngineBadge.text = getEngineBadge(item)

            val isSelected = selectedTag?.equals(item.tag, ignoreCase = true) == true
            binding.ivLangCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

            binding.root.setOnClickListener {
                onLanguageSelected(item)
            }
        }
    }
}
