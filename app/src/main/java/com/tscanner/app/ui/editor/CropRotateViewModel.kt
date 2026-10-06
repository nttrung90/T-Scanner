package com.tscanner.app.ui.editor

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class CropRotateViewModel : ViewModel() {

    var coordinator: CropSaveCoordinator = CropSaveCoordinator()

    private val _saveState = MutableLiveData<CropSaveState>(CropSaveState.Idle)
    val saveState: LiveData<CropSaveState> = _saveState

    // Giữ snapshot bitmap độc lập với lifecycle của Activity trong lúc IO đang ghi file
    @Volatile
    private var inFlightBitmap: Bitmap? = null

    // Quản lý pending crop rect qua các lần recreate khi decode chưa xong
    var pendingNormalizedCropRect: RectF? = null

    fun resolveCropRectForSaveState(isOverlayInitialized: Boolean, currentOverlayRect: RectF): RectF {
        return if (isOverlayInitialized) {
            currentOverlayRect
        } else {
            pendingNormalizedCropRect ?: currentOverlayRect
        }
    }

    fun isSaving(): Boolean = coordinator.isSaving()
    fun isCommitted(): Boolean = coordinator.isCommitted()

    fun save(
        bitmap: Bitmap,
        normalizedRect: RectF,
        imagePath: String,
        pageIndex: Int,
        cropTransform: (Bitmap, Int, Int, Int, Int) -> Bitmap = CropSaveCoordinator::defaultCropTransform,
        bitmapWriter: suspend (Bitmap, java.io.File) -> Boolean = CropSaveCoordinator::defaultBitmapWriter
    ) {
        if (coordinator.isSaving() || coordinator.isCommitted()) return

        inFlightBitmap = bitmap
        val token = System.currentTimeMillis()
        _saveState.value = CropSaveState.Saving(token)

        viewModelScope.launch {
            val result = coordinator.saveCroppedImage(
                bitmap = bitmap,
                normalizedRect = normalizedRect,
                imagePath = imagePath,
                pageIndex = pageIndex,
                cropTransform = cropTransform,
                bitmapWriter = bitmapWriter
            )
            inFlightBitmap = null
            _saveState.value = result
        }
    }

    override fun onCleared() {
        super.onCleared()
        inFlightBitmap = null
    }
}
