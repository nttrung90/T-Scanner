package com.tscanner.app.ui.editor.model

/**
 * Các bộ lọc tài liệu hỗ trợ trong T-Scanner
 */
enum class DocumentFilterType(val id: String) {
    ORIGINAL("original"),
    GRAYSCALE("grayscale"),
    BLACK_AND_WHITE("black_and_white");

    companion object {
        fun fromId(id: String?): DocumentFilterType {
            return values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: ORIGINAL
        }
    }
}
