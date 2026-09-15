package com.tscanner.app.data.model

enum class SyncStatus(val id: String) {
    LOCAL_ONLY("local_only"),  // Saved only on local device (Free or offline)
    SYNCING("syncing"),        // Currently being uploaded to Google Drive in background
    SYNCED("synced"),          // Successfully backed up on user's personal Google Drive
    FAILED("failed");          // Failed to backup (offline, drive full, token error)

    companion object {
        fun fromId(id: String?): SyncStatus {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: LOCAL_ONLY
        }
    }
}
