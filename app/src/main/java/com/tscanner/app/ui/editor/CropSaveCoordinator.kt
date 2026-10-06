package com.tscanner.app.ui.editor

import android.graphics.Bitmap
import android.graphics.RectF
import com.tscanner.app.R
import com.tscanner.app.utils.SafeFileWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

sealed class CropSaveState {
    object Idle : CropSaveState()
    data class Saving(val token: Long) : CropSaveState()
    data class Committed(val imagePath: String, val pageIndex: Int, val token: Long) : CropSaveState()
    data class Error(val token: Long, val messageResId: Int) : CropSaveState()
}

interface CropSaveHook {
    suspend fun onBeforeCrop(token: Long) {}
    suspend fun onBeforeCommit(token: Long) {}
    suspend fun onAfterCommit(token: Long, success: Boolean) {}
}

open class CropSaveCoordinator(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    var hook: CropSaveHook? = null
) {
    private val tokenGenerator = AtomicLong(0)

    @Volatile
    var currentState: CropSaveState = CropSaveState.Idle
        private set

    fun isSaving(): Boolean = currentState is CropSaveState.Saving
    fun isCommitted(): Boolean = currentState is CropSaveState.Committed

    open fun isBitmapRecycled(bitmap: Bitmap): Boolean = bitmap.isRecycled

    suspend fun saveCroppedImage(
        bitmap: Bitmap,
        normalizedRect: RectF,
        imagePath: String,
        pageIndex: Int,
        cropTransform: (Bitmap, Int, Int, Int, Int) -> Bitmap = ::defaultCropTransform,
        bitmapWriter: suspend (Bitmap, File) -> Boolean = ::defaultBitmapWriter
    ): CropSaveState {
        if (currentState is CropSaveState.Saving) {
            return currentState
        }

        val token = tokenGenerator.incrementAndGet()
        currentState = CropSaveState.Saving(token)

        return withContext(ioDispatcher) {
            try {
                hook?.onBeforeCrop(token)

                if (isBitmapRecycled(bitmap)) {
                    val err = CropSaveState.Error(token, R.string.crop_save_error)
                    currentState = err
                    return@withContext err
                }

                val bmpWidth = bitmap.width.coerceAtLeast(1)
                val bmpHeight = bitmap.height.coerceAtLeast(1)
                val x = (normalizedRect.left * bmpWidth).toInt().coerceIn(0, bmpWidth - 1)
                val y = (normalizedRect.top * bmpHeight).toInt().coerceIn(0, bmpHeight - 1)
                val w = (normalizedRect.width() * bmpWidth).toInt().coerceIn(1, bmpWidth - x)
                val h = (normalizedRect.height() * bmpHeight).toInt().coerceIn(1, bmpHeight - y)

                val cropped = cropTransform(bitmap, x, y, w, h)
                val destinationFile = File(imagePath)

                hook?.onBeforeCommit(token)

                val writeSuccess = bitmapWriter(cropped, destinationFile)

                if (cropped != bitmap && !cropped.isRecycled) {
                    cropped.recycle()
                }

                hook?.onAfterCommit(token, writeSuccess)

                if (writeSuccess) {
                    val committed = CropSaveState.Committed(imagePath, pageIndex, token)
                    currentState = committed
                    committed
                } else {
                    val err = CropSaveState.Error(token, R.string.crop_save_error)
                    currentState = err
                    err
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }
                e.printStackTrace()
                val err = CropSaveState.Error(token, R.string.crop_save_error)
                currentState = err
                err
            }
        }
    }

    companion object {
        fun defaultCropTransform(source: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap {
            return Bitmap.createBitmap(source, x, y, w, h)
        }

        suspend fun defaultBitmapWriter(cropped: Bitmap, destinationFile: File): Boolean {
            val writeResult = SafeFileWriter.writeSafely(
                destinationFile = destinationFile,
                validator = { SafeFileWriter.validateImage(it) }
            ) { tempFile ->
                FileOutputStream(tempFile).use { out ->
                    val ok = cropped.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    out.flush()
                    ok
                }
            }
            return writeResult is SafeFileWriter.Result.Success
        }
    }
}
