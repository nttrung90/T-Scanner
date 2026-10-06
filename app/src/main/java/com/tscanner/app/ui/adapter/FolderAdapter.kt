package com.tscanner.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tscanner.app.R
import com.tscanner.app.data.model.FolderItem
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.databinding.ItemFolderBinding
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.FileUtils

class FolderAdapter(
    private val onFolderClick: (FolderItem) -> Unit,
    private val onDeleteClick: (FolderItem) -> Unit
) : ListAdapter<FolderItem, FolderAdapter.FolderViewHolder>(FolderDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FolderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FolderViewHolder(private val binding: ItemFolderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FolderItem) {
            binding.tvFolderName.text = item.name
            val currentUserId = AppAuthManager.getCurrentUser()?.id
            val docsInFolder = DocumentRepo.getInstance(binding.root.context).getDocumentsInFolder(item.id, currentUserId)
            val dateStr = FileUtils.formatDate(item.createdAt, binding.root.context)
            binding.tvFolderInfo.text = binding.root.context.resources.getQuantityString(
                R.plurals.folder_info_documents_plurals,
                docsInFolder.size,
                docsInFolder.size,
                dateStr
            )

            binding.root.setOnClickListener {
                onFolderClick(item)
            }

            binding.btnFolderMore.setOnClickListener { v ->
                val popup = PopupMenu(v.context, v)
                popup.menu.add(0, 1, 0, v.context.getString(R.string.menu_open_folder))
                popup.menu.add(0, 2, 1, v.context.getString(R.string.delete_folder_title))

                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        1 -> onFolderClick(item)
                        2 -> onDeleteClick(item)
                    }
                    true
                }
                popup.show()
            }
        }
    }

    class FolderDiffCallback : DiffUtil.ItemCallback<FolderItem>() {
        override fun areItemsTheSame(oldItem: FolderItem, newItem: FolderItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: FolderItem, newItem: FolderItem): Boolean {
            return oldItem == newItem
        }
    }
}
