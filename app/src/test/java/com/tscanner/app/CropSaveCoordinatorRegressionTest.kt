package com.tscanner.app

import android.graphics.Bitmap
import android.graphics.RectF
import com.tscanner.app.ui.editor.CropSaveCoordinator
import com.tscanner.app.ui.editor.CropSaveHook
import com.tscanner.app.ui.editor.CropSaveState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CropSaveCoordinatorRegressionTest {

    private fun createStubBitmap(): Bitmap {
        return try {
            val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null) as sun.misc.Unsafe
            unsafe.allocateInstance(Bitmap::class.java) as Bitmap
        } catch (_: Throwable) {
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
    }

    @Test
    fun testSingleCommitAndLockDuringSave() = runBlocking {
        val beforeCommitLatch = CountDownLatch(1)
        val commitReached = AtomicBoolean(false)

        val hook = object : CropSaveHook {
            override suspend fun onBeforeCommit(token: Long) {
                commitReached.set(true)
                beforeCommitLatch.await(5, TimeUnit.SECONDS)
            }
        }

        val coordinator = CropSaveCoordinator(ioDispatcher = Dispatchers.Default, hook = hook)
        val dummyBitmap = createStubBitmap()
        val dummyFile = File("/dummy/test_image.jpg")

        var saveResult: CropSaveState? = null

        val saveJob = launch(Dispatchers.Default) {
            saveResult = coordinator.saveCroppedImage(
                bitmap = dummyBitmap,
                normalizedRect = RectF(0.1f, 0.1f, 0.9f, 0.9f),
                imagePath = dummyFile.absolutePath,
                pageIndex = 0,
                cropTransform = { src, _, _, _, _ -> src },
                bitmapWriter = { _, _ -> true }
            )
        }

        // Chờ job chạm tới hook
        while (!commitReached.get()) {
            Thread.sleep(10)
        }

        // Phải ở trạng thái Saving
        assertTrue(commitReached.get())
        assertTrue(coordinator.isSaving())

        // Cố tình gọi save lần 2 khi đang Saving -> Không được chạy duplicate
        val duplicateResult = coordinator.saveCroppedImage(
            bitmap = dummyBitmap,
            normalizedRect = RectF(0f, 0f, 1f, 1f),
            imagePath = dummyFile.absolutePath,
            pageIndex = 0,
            cropTransform = { src, _, _, _, _ -> src },
            bitmapWriter = { _, _ -> true }
        )
        assertTrue(duplicateResult is CropSaveState.Saving)

        // Mở chốt trước commit
        beforeCommitLatch.countDown()
        saveJob.join()

        // Phải committed thành công duy nhất 1 lần
        assertTrue(coordinator.isCommitted())
        assertFalse(coordinator.isSaving())
        assertTrue(saveResult is CropSaveState.Committed)
        assertEquals(dummyFile.absolutePath, (saveResult as CropSaveState.Committed).imagePath)
    }

    @Test
    fun testFullCropSameInstanceDoesNotRecycleSource() = runBlocking {
        val coordinator = CropSaveCoordinator(ioDispatcher = Dispatchers.Default)
        val stubBitmap = createStubBitmap()

        val result = coordinator.saveCroppedImage(
            bitmap = stubBitmap,
            normalizedRect = RectF(0f, 0f, 1f, 1f),
            imagePath = "/dummy/full_crop.jpg",
            pageIndex = 1,
            cropTransform = { src, _, _, _, _ -> src }, // Trả về chính source (full crop)
            bitmapWriter = { _, _ -> true }
        )

        assertTrue(result is CropSaveState.Committed)
        // Khi cropped === bitmap, coordinator không được recycle instance gốc
        assertFalse(stubBitmap.isRecycled)
    }

    @Test
    fun testWriteFailurePreservesStateAndAllowsRetry() = runBlocking {
        val coordinator = CropSaveCoordinator(ioDispatcher = Dispatchers.Default)
        val dummyBitmap = createStubBitmap()

        // Giả lập ghi thất bại
        val failedResult = coordinator.saveCroppedImage(
            bitmap = dummyBitmap,
            normalizedRect = RectF(0.2f, 0.2f, 0.8f, 0.8f),
            imagePath = "/dummy/fail_image.jpg",
            pageIndex = 0,
            cropTransform = { src, _, _, _, _ -> src },
            bitmapWriter = { _, _ -> false }
        )

        assertTrue(failedResult is CropSaveState.Error)
        assertFalse(coordinator.isSaving())
        assertFalse(coordinator.isCommitted())

        // Thử lại lần 2 thành công
        val retryResult = coordinator.saveCroppedImage(
            bitmap = dummyBitmap,
            normalizedRect = RectF(0.2f, 0.2f, 0.8f, 0.8f),
            imagePath = "/dummy/fail_image.jpg",
            pageIndex = 0,
            cropTransform = { src, _, _, _, _ -> src },
            bitmapWriter = { _, _ -> true }
        )

        assertTrue(retryResult is CropSaveState.Committed)
        assertTrue(coordinator.isCommitted())
    }

    @Test
    fun testCancellationExceptionIsNotSwallowed() = runBlocking {
        val hook = object : CropSaveHook {
            override suspend fun onBeforeCrop(token: Long) {
                throw CancellationException("Intentional cancel")
            }
        }

        val coordinator = CropSaveCoordinator(ioDispatcher = Dispatchers.Default, hook = hook)
        val dummyBitmap = createStubBitmap()

        var caughtCancellation = false
        try {
            coordinator.saveCroppedImage(
                bitmap = dummyBitmap,
                normalizedRect = RectF(0f, 0f, 1f, 1f),
                imagePath = "/dummy/cancel.jpg",
                pageIndex = 0,
                cropTransform = { src, _, _, _, _ -> src },
                bitmapWriter = { _, _ -> true }
            )
        } catch (e: CancellationException) {
            caughtCancellation = true
        }

        assertTrue("CancellationException must be rethrown and not swallowed into generic Error", caughtCancellation)
    }

    @Test
    fun testRecycledBitmapSafelyAborts() = runBlocking {
        val coordinator = object : CropSaveCoordinator(ioDispatcher = Dispatchers.Default) {
            override fun isBitmapRecycled(bitmap: Bitmap): Boolean = true
        }
        val stubBitmap = createStubBitmap()

        val result = coordinator.saveCroppedImage(
            bitmap = stubBitmap,
            normalizedRect = RectF(0f, 0f, 1f, 1f),
            imagePath = "/dummy/recycled.jpg",
            pageIndex = 0,
            cropTransform = { src, _, _, _, _ -> src },
            bitmapWriter = { _, _ -> true }
        )

        assertTrue(result is CropSaveState.Error)
        assertFalse(coordinator.isCommitted())
    }
}
