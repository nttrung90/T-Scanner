package com.tscanner.app.data.model

import com.tscanner.app.ui.editor.model.PageEditState
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bản nháp phiên làm việc sau quét lưu trữ trong bộ nhớ nội bộ an toàn (filesDir/draft_sessions/).
 * Sống qua các sự kiện xoay màn hình, Activity recreation và process death.
 */
data class PostScanSessionDraft(
    val sessionId: String,
    val documentTitle: String,
    val pageStates: List<PageEditState>,
    val schemaVersion: Int = 1,
    val revision: Long = 1L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("documentTitle", documentTitle)
            put("schemaVersion", schemaVersion)
            put("revision", revision)
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
            val array = JSONArray()
            pageStates.forEach { array.put(it.toJson()) }
            put("pageStates", array)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): PostScanSessionDraft {
            val sessionId = json.getString("sessionId")
            val documentTitle = json.optString("documentTitle", "Tài liệu mới")
            val schemaVersion = json.optInt("schemaVersion", 1)
            val revision = json.optLong("revision", 1L)
            val createdAt = json.optLong("createdAt", System.currentTimeMillis())
            val updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
            val pages = mutableListOf<PageEditState>()
            val array = json.optJSONArray("pageStates") ?: JSONArray()
            for (i in 0 until array.length()) {
                pages.add(PageEditState.fromJson(array.getJSONObject(i)))
            }
            return PostScanSessionDraft(
                sessionId = sessionId,
                documentTitle = documentTitle,
                pageStates = pages,
                schemaVersion = schemaVersion,
                revision = revision,
                createdAt = createdAt,
                updatedAt = updatedAt
            )
        }
    }
}
