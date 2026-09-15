package com.tscanner.app.data.model

import java.io.Serializable

data class DocumentItem(
    val id: String,
    var title: String,
    val pdfPath: String? = null,
    val thumbnailPath: String? = null,
    val pagePaths: List<String> = emptyList(),
    val pageCount: Int = 1,
    val sizeBytes: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    var folderId: String? = null,
    var isSynced: Boolean = false,
    var driveFileId: String? = null,
    var lastSyncedAt: Long? = null,
    var syncStatus: SyncStatus = if (isSynced) SyncStatus.SYNCED else SyncStatus.LOCAL_ONLY
) : Serializable
