package com.tscanner.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tscanner.app.databinding.ItemPdfPageBinding
import java.io.File

class PdfPageAdapter(
    private val pageImagePaths: List<String>,
    private val onPageCropClick: (position: Int, path: String) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<PdfPageAdapter.PdfPageViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PdfPageViewHolder {
        val binding = ItemPdfPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PdfPageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PdfPageViewHolder, position: Int) {
        holder.bind(pageImagePaths[position], position, pageImagePaths.size)
    }

    override fun getItemCount(): Int = pageImagePaths.size

    inner class PdfPageViewHolder(private val binding: ItemPdfPageBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(path: String, position: Int, total: Int) {
            binding.tvPageNumber.text = "Trang ${position + 1} / $total"

            Glide.with(binding.root.context)
                .load(File(path))
                .fitCenter()
                .into(binding.ivPageImage)

            binding.btnCropPage.setOnClickListener {
                onPageCropClick(position, path)
            }

            binding.ivPageImage.setOnClickListener {
                onPageCropClick(position, path)
            }
        }
    }
}
