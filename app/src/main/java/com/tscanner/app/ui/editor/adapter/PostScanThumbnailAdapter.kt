package com.tscanner.app.ui.editor.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.tscanner.app.R
import com.tscanner.app.databinding.ItemPostScanThumbnailBinding
import java.io.File

class PostScanThumbnailAdapter(
    private var pagePaths: List<String>,
    private var selectedIndex: Int = 0,
    private val onPageSelected: (Int) -> Unit
) : RecyclerView.Adapter<PostScanThumbnailAdapter.ThumbnailViewHolder>() {

    fun updatePages(newPaths: List<String>, newSelectedIndex: Int = selectedIndex) {
        this.pagePaths = newPaths
        this.selectedIndex = newSelectedIndex.coerceIn(0, (newPaths.size - 1).coerceAtLeast(0))
        notifyDataSetChanged()
    }

    fun setSelectedIndex(index: Int) {
        if (index == selectedIndex || index !in pagePaths.indices) return
        val prev = selectedIndex
        selectedIndex = index
        notifyItemChanged(prev)
        notifyItemChanged(selectedIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbnailViewHolder {
        val binding = ItemPostScanThumbnailBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ThumbnailViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ThumbnailViewHolder, position: Int) {
        holder.bind(pagePaths[position], position, position == selectedIndex)
    }

    override fun getItemCount(): Int = pagePaths.size

    inner class ThumbnailViewHolder(
        private val binding: ItemPostScanThumbnailBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(path: String, position: Int, isSelected: Boolean) {
            val context = binding.root.context
            binding.tvThumbPageBadge.text = (position + 1).toString()

            val file = File(path)
            if (file.exists()) {
                Glide.with(context)
                    .load(file)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .centerCrop()
                    .into(binding.ivThumbImage)
            } else {
                binding.ivThumbImage.setImageDrawable(null)
            }

            if (isSelected) {
                binding.cardThumbContainer.strokeColor = ContextCompat.getColor(context, R.color.primary_teal)
                binding.cardThumbContainer.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
            } else {
                binding.cardThumbContainer.strokeColor = Color.TRANSPARENT
                binding.cardThumbContainer.strokeWidth = 0
            }

            binding.root.setOnClickListener {
                if (position != selectedIndex) {
                    setSelectedIndex(position)
                    onPageSelected(position)
                }
            }
        }
    }
}
