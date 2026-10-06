package com.tscanner.app.ui.dialogs

import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tscanner.app.databinding.ItemLanguageSelectionBinding
import com.tscanner.app.utils.UiLanguage
import java.text.Normalizer
import java.util.Locale

class LanguageAdapter(
    private val allLanguages: List<UiLanguage>,
    private var selectedCode: String?,
    private val onFilterChanged: ((isEmpty: Boolean) -> Unit)? = null,
    private val onLanguageSelected: (UiLanguage) -> Unit
) : RecyclerView.Adapter<LanguageAdapter.LanguageViewHolder>() {

    private var filteredLanguages: List<UiLanguage> = allLanguages.toList()

    fun setSelectedCode(code: String?) {
        selectedCode = code
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
                val uiClean = removeDiacritics(getLocalizedName(context, item).lowercase(Locale.ROOT))
                val tagClean = item.tag.lowercase(Locale.ROOT)
                val aliasMatch = item.aliases.any { removeDiacritics(it.lowercase(Locale.ROOT)).contains(cleanQuery) }

                nativeClean.contains(cleanQuery) ||
                vietnameseClean.contains(cleanQuery) ||
                englishClean.contains(cleanQuery) ||
                uiClean.contains(cleanQuery) ||
                tagClean.contains(cleanQuery) ||
                aliasMatch
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

        fun getLocalizedName(context: Context, item: UiLanguage): String {
            val configLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.resources.configuration.locales[0] ?: Locale.getDefault()
            } else {
                context.resources.configuration.locale ?: Locale.getDefault()
            }

            if (configLocale.language == "vi") return item.vietnameseName
            if (configLocale.language == "en") return item.englishName

            val loc = Locale.forLanguageTag(item.tag)
            val name = loc.getDisplayName(configLocale)
            return if (name.isNotBlank() && !name.equals(item.tag, ignoreCase = true)) {
                name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(configLocale) else it.toString() }
            } else {
                item.englishName.ifBlank { item.nativeName }
            }
        }
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

        fun bind(item: UiLanguage) {
            binding.tvLangFlag.text = item.flag
            binding.tvLangNativeName.text = item.nativeName

            val localizedName = getLocalizedName(binding.root.context, item)
            binding.tvLangSubName.text = "$localizedName (${item.tag})"

            val isSelected = (selectedCode == item.tag)
            binding.ivLangCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

            binding.root.setOnClickListener {
                onLanguageSelected(item)
            }
        }
    }
}
