package com.tscanner.app.ui.editor.model

import org.json.JSONObject

/**
 * Trạng thái chỉnh sửa bất biến của một trang tài liệu trong T-Scanner.
 * Mọi thao tác thay đổi giá trị đều trả về bản sao mới qua hàm copy().
 */
data class PageEditState(
    val pageIndex: Int,
    val inputImagePath: String, // Đường dẫn ảnh nhận từ ML Kit (bất biến, read-only)
    val filterType: DocumentFilterType = DocumentFilterType.ORIGINAL,
    val sharpnessIntensity: Int = 0, // 0..100, mặc định 0
    val shadowRemovalIntensity: Int = 0, // 0..100, mặc định 0
    val backgroundLightenIntensity: Int = 0, // 0..100, mặc định 0
    val rotationDegrees: Int = 0, // 0, 90, 180, 270
    val cropRect: NormalizedCropRect = NormalizedCropRect()
) {
    /**
     * Kiểm tra xem trang có đang áp dụng bất kỳ hiệu ứng chỉnh sửa nào không
     */
    val isModified: Boolean
        get() = filterType != DocumentFilterType.ORIGINAL ||
                sharpnessIntensity != 0 ||
                shadowRemovalIntensity != 0 ||
                backgroundLightenIntensity != 0 ||
                rotationDegrees % 360 != 0 ||
                !cropRect.isFull

    /**
     * Sao chép các thuộc tính bộ lọc, tăng nét và làm sạch từ một trang mẫu.
     * Quy tắc an toàn: Không bao giờ sao chép cropRect và rotationDegrees sang trang khác.
     */
    fun applyGlobalStyleFrom(source: PageEditState): PageEditState {
        return this.copy(
            filterType = source.filterType,
            sharpnessIntensity = source.sharpnessIntensity,
            shadowRemovalIntensity = source.shadowRemovalIntensity,
            backgroundLightenIntensity = source.backgroundLightenIntensity
        )
    }

    /**
     * Đặt lại trạng thái về ban đầu (như khi nhận từ ML Kit)
     */
    fun resetToOriginal(): PageEditState {
        return PageEditState(
            pageIndex = pageIndex,
            inputImagePath = inputImagePath
        )
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("pageIndex", pageIndex)
            put("inputImagePath", inputImagePath)
            put("filterType", filterType.id)
            put("sharpnessIntensity", sharpnessIntensity)
            put("shadowRemovalIntensity", shadowRemovalIntensity)
            put("backgroundLightenIntensity", backgroundLightenIntensity)
            put("rotationDegrees", rotationDegrees)
            put("cropRect", cropRect.toJson())
        }
    }

    companion object {
        fun fromJson(json: JSONObject): PageEditState {
            return PageEditState(
                pageIndex = json.optInt("pageIndex", 0),
                inputImagePath = json.optString("inputImagePath", ""),
                filterType = DocumentFilterType.fromId(json.optString("filterType")),
                sharpnessIntensity = json.optInt("sharpnessIntensity", 0).coerceIn(0, 100),
                shadowRemovalIntensity = json.optInt("shadowRemovalIntensity", 0).coerceIn(0, 100),
                backgroundLightenIntensity = json.optInt("backgroundLightenIntensity", 0).coerceIn(0, 100),
                rotationDegrees = json.optInt("rotationDegrees", 0),
                cropRect = NormalizedCropRect.fromJson(json.optJSONObject("cropRect"))
            )
        }
    }
}
