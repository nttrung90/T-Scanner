package com.tscanner.app.data.model

import java.io.Serializable

data class DocumentItem(
    val id: String,
    val title: String,
    val pdfPath: String? = null,
    val thumbnailPath: String? = null,
    val pagePaths: List<String> = emptyList(),
    val pageCount: Int = 1,
    val sizeBytes: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val folderId: String? = null,
    val isSynced: Boolean = false,
    val driveFileId: String? = null,
    val lastSyncedAt: Long? = null,
    val syncStatus: SyncStatus = if (isSynced) SyncStatus.SYNCED else SyncStatus.LOCAL_ONLY,
    val ownerId: String? = null,
    val contentRevision: Long = 0L,
    val mimeType: String = "application/pdf",
    val isConflict: Boolean = false,
    val remoteModifiedTime: Long? = null
) : Serializable
