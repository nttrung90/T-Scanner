package com.tscanner.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tscanner.app.R
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.model.SyncStatus
import com.tscanner.app.databinding.ItemDocumentBinding
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.CloudBackupManager
import com.tscanner.app.utils.FileUtils
import com.tscanner.app.utils.PdfConverterHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DocumentAdapter(
    private val onItemClick: (DocumentItem) -> Unit,
    private val onActionClick: (DocumentItem, ActionType) -> Unit
) : ListAdapter<DocumentItem, DocumentAdapter.DocumentViewHolder>(DocDiffCallback()) {

    enum class ActionType {
        SHARE, DELETE, RENAME, OCR, CONVERT_WORD, CONVERT_EXCEL, MOVE_FOLDER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocumentViewHolder {
        val binding = ItemDocumentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DocumentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DocumentViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DocumentViewHolder(private val binding: ItemDocumentBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DocumentItem) {
            binding.tvDocTitle.text = item.title
            binding.tvDocDate.text = FileUtils.formatDate(item.createdAt, binding.root.context)
            binding.tvDocPages.text = if (item.pageCount > 0) {
                binding.root.context.resources.getQuantityString(
                    R.plurals.pages_count_plurals,
                    item.pageCount,
                    item.pageCount
                )
            } else {
                binding.root.context.getString(R.string.page_count_unknown)
            }
            binding.tvDocSize.text = FileUtils.formatFileSize(item.sizeBytes)

            // Bind cloud sync status icon
            when (item.syncStatus) {
                SyncStatus.SYNCED -> {
                    binding.ivDocSyncStatus.visibility = View.VISIBLE
                    binding.ivDocSyncStatus.setImageResource(R.drawable.ic_cloud_done)
                    binding.ivDocSyncStatus.setOnClickListener {
                        Toast.makeText(binding.root.context, binding.root.context.getString(R.string.sync_status_synced), Toast.LENGTH_SHORT).show()
                    }
                }
                SyncStatus.SYNCING -> {
                    binding.ivDocSyncStatus.visibility = View.VISIBLE
                    binding.ivDocSyncStatus.setImageResource(R.drawable.ic_cloud_sync)
                    binding.ivDocSyncStatus.setOnClickListener {
                        Toast.makeText(binding.root.context, binding.root.context.getString(R.string.sync_status_syncing), Toast.LENGTH_SHORT).show()
                    }
                }
                SyncStatus.FAILED -> {
                    binding.ivDocSyncStatus.visibility = View.VISIBLE
                    binding.ivDocSyncStatus.setImageResource(R.drawable.ic_cloud_alert)
                    binding.ivDocSyncStatus.setOnClickListener {
                        val errMsg = com.tscanner.app.utils.GoogleDriveService.lastSyncError
                        if (!errMsg.isNullOrEmpty()) {
                            Toast.makeText(binding.root.context, errMsg, Toast.LENGTH_LONG).show()
                        } else if (AppAuthManager.isUserVip()) {
                            CloudBackupManager.enqueueBackupAsync(binding.root.context, item)
                            Toast.makeText(binding.root.context, binding.root.context.getString(R.string.sync_status_retrying), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(binding.root.context, binding.root.context.getString(R.string.sync_status_vip_required), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                SyncStatus.LOCAL_ONLY -> {
                    binding.ivDocSyncStatus.visibility = View.GONE
                    binding.ivDocSyncStatus.setOnClickListener(null)
                }
            }

            // Load thumbnail (clear any previous tint so the photo displays in full natural color)
            binding.ivDocThumb.imageTintList = null
            binding.ivDocThumb.tag = item.id

            val thumb = item.thumbnailPath ?: item.pagePaths.firstOrNull()
            if (thumb != null && File(thumb).exists()) {
                binding.ivDocThumb.setPadding(0, 0, 0, 0)
                binding.ivDocThumb.scaleType = ImageView.ScaleType.CENTER_CROP
                Glide.with(binding.root.context)
                    .load(File(thumb))
                    .centerCrop()
                    .into(binding.ivDocThumb)
            } else if (item.pdfPath != null && File(item.pdfPath).exists()) {
                val cachedThumb = File(FileUtils.getImagesDir(binding.root.context), "thumb_${item.id}.jpg")
                if (cachedThumb.exists() && cachedThumb.length() > 0) {
                    binding.ivDocThumb.setPadding(0, 0, 0, 0)
                    binding.ivDocThumb.scaleType = ImageView.ScaleType.CENTER_CROP
                    Glide.with(binding.root.context)
                        .load(cachedThumb)
                        .centerCrop()
                        .into(binding.ivDocThumb)
                } else {
                    // Show PDF placeholder while generating thumbnail in background
                    binding.ivDocThumb.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    val pad = (14 * binding.root.context.resources.displayMetrics.density).toInt()
                    binding.ivDocThumb.setPadding(pad, pad, pad, pad)
                    binding.ivDocThumb.setImageResource(R.drawable.ic_pdf)

                    CoroutineScope(Dispatchers.IO).launch {
                        val success = PdfConverterHelper.renderPdfFirstPage(File(item.pdfPath), cachedThumb)
                        if (success) {
                            withContext(Dispatchers.Main) {
                                if (binding.ivDocThumb.tag == item.id) {
                                    binding.ivDocThumb.setPadding(0, 0, 0, 0)
                                    binding.ivDocThumb.scaleType = ImageView.ScaleType.CENTER_CROP
                                    Glide.with(binding.root.context)
                                        .load(cachedThumb)
                                        .centerCrop()
                                        .into(binding.ivDocThumb)
                                }
                            }
                        }
                    }
                }
            } else {
                binding.ivDocThumb.scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = (14 * binding.root.context.resources.displayMetrics.density).toInt()
                binding.ivDocThumb.setPadding(pad, pad, pad, pad)
                binding.ivDocThumb.setImageResource(R.drawable.ic_pdf)
            }

            binding.root.setOnClickListener {
                onItemClick(item)
            }

            binding.btnDocMore.setOnClickListener { v ->
                val popup = PopupMenu(v.context, v)
                popup.menu.add(0, 1, 0, v.context.getString(R.string.menu_open_view_pdf))
                popup.menu.add(0, 2, 1, v.context.getString(R.string.menu_ocr_extract))
                popup.menu.add(0, 3, 2, v.context.getString(R.string.menu_rename))
                popup.menu.add(0, 4, 3, v.context.getString(R.string.share))
                popup.menu.add(0, 5, 4, v.context.getString(R.string.menu_convert_word))
                popup.menu.add(0, 6, 5, v.context.getString(R.string.menu_move_to_folder))
                popup.menu.add(0, 7, 6, v.context.getString(R.string.delete))

                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        1 -> onItemClick(item)
                        2 -> onActionClick(item, ActionType.OCR)
                        3 -> onActionClick(item, ActionType.RENAME)
                        4 -> onActionClick(item, ActionType.SHARE)
                        5 -> onActionClick(item, ActionType.CONVERT_WORD)
                        6 -> onActionClick(item, ActionType.MOVE_FOLDER)
                        7 -> onActionClick(item, ActionType.DELETE)
                    }
                    true
                }
                popup.show()
            }
        }
    }

    class DocDiffCallback : DiffUtil.ItemCallback<DocumentItem>() {
        override fun areItemsTheSame(oldItem: DocumentItem, newItem: DocumentItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: DocumentItem, newItem: DocumentItem): Boolean {
            return oldItem == newItem
        }
    }
}
