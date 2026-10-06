package com.tscanner.app.data.model

import com.tscanner.app.R
import java.io.File
import java.io.Serializable

enum class ManagedFileType(val displayNameRes: Int, val extension: String) {
    ALL(R.string.filter_all, ""),
    PDF(R.string.filter_pdf, "pdf"),
    WORD(R.string.filter_word, "doc"),
    EXCEL(R.string.filter_excel, "csv"),
    PPT(R.string.filter_ppt, "html"),
    IMAGE(R.string.filter_image, "jpg"),
    OTHER(R.string.filter_other, "")
}

data class ManagedFileItem(
    val file: File,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val fileType: ManagedFileType
) : Serializable

data class FileTypeStat(
    val type: ManagedFileType,
    val count: Int,
    val totalSizeBytes: Long
) : Serializable

data class DocumentManagementStats(
    val totalFiles: Int,
    val totalSizeBytes: Long,
    val typeStats: List<FileTypeStat>
) : Serializable
