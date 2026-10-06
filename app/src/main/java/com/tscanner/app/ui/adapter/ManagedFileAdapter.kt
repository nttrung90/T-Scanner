package com.tscanner.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tscanner.app.R
import com.tscanner.app.data.model.ManagedFileItem
import com.tscanner.app.data.model.ManagedFileType
import com.tscanner.app.databinding.ItemManagedFileBinding
import com.tscanner.app.utils.FileUtils

class ManagedFileAdapter(
    private val onOpenFile: (ManagedFileItem) -> Unit,
    private val onCopyPath: (ManagedFileItem) -> Unit,
    private val onShareFile: (ManagedFileItem) -> Unit,
    private val onDeleteFile: (ManagedFileItem) -> Unit
) : ListAdapter<ManagedFileItem, ManagedFileAdapter.ManagedFileViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ManagedFileViewHolder {
        val binding = ItemManagedFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ManagedFileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ManagedFileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ManagedFileViewHolder(private val binding: ItemManagedFileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ManagedFileItem) {
            binding.tvFileName.text = item.name
            binding.tvFilePath.text = item.path
            binding.tvFileSize.text = FileUtils.formatFileSize(item.sizeBytes)
            binding.tvFileDate.text = FileUtils.formatDate(item.lastModified)
            binding.tvFileBadge.text = binding.root.context.getString(item.fileType.displayNameRes)

            // Icon according to file type
            val iconRes = when (item.fileType) {
                ManagedFileType.PDF -> R.drawable.ic_pdf
                ManagedFileType.WORD -> R.drawable.ic_word
                ManagedFileType.EXCEL -> R.drawable.ic_excel
                ManagedFileType.PPT -> R.drawable.ic_ppt
                ManagedFileType.IMAGE -> R.drawable.ic_image
                else -> R.drawable.ic_file
            }
            binding.ivFileIcon.setImageResource(iconRes)

            // Listeners
            binding.root.setOnClickListener { onOpenFile(item) }
            binding.btnOpenFile.setOnClickListener { onOpenFile(item) }
            binding.btnCopyPath.setOnClickListener { onCopyPath(item) }
            binding.btnShareFile.setOnClickListener { onShareFile(item) }
            binding.btnDeleteFile.setOnClickListener { onDeleteFile(item) }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<ManagedFileItem>() {
        override fun areItemsTheSame(oldItem: ManagedFileItem, newItem: ManagedFileItem): Boolean {
            return oldItem.path == newItem.path
        }

        override fun areContentsTheSame(oldItem: ManagedFileItem, newItem: ManagedFileItem): Boolean {
            return oldItem == newItem
        }
    }
}
