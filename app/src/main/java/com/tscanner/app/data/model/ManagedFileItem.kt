package com.tscanner.app.data.model

import java.io.File
import java.io.Serializable

enum class ManagedFileType(val displayName: String, val extension: String) {
    ALL("Tất cả", ""),
    PDF("PDF", "pdf"),
    WORD("Word", "doc"),
    EXCEL("Excel", "csv"),
    PPT("PPT", "html"),
    IMAGE("Hình ảnh", "jpg"),
    OTHER("Khác", "")
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
