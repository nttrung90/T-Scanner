package com.tscanner.app

import android.graphics.RectF
import com.tscanner.app.ui.editor.CropRotateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CropPendingStateRegressionTest {

    private fun createRectF(left: Float, top: Float, right: Float, bottom: Float): RectF {
        return RectF().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
    }

    @Test
    fun testPendingCropPreserved_acrossMultipleRecreations_beforeDecodeCompletes() {
        val viewModel = CropRotateViewModel()
        val customCrop = createRectF(0.12f, 0.18f, 0.82f, 0.88f)
        val uninitializedOverlayRect = createRectF(0f, 0f, 1f, 1f)

        // Giả lập trạng thái sau lần recreate thứ nhất với savedInstanceState
        viewModel.pendingNormalizedCropRect = customCrop

        // 1. Recreate lần 1 trong khi overlay chưa initialized (đang decode ảnh)
        val resolvedRectPass1 = viewModel.resolveCropRectForSaveState(
            isOverlayInitialized = false,
            currentOverlayRect = uninitializedOverlayRect
        )
        // Phải giữ được customCrop, KHÔNG bị ghi đè thành full rect
        assertEquals(0.12f, resolvedRectPass1.left, 0.001f)
        assertEquals(0.18f, resolvedRectPass1.top, 0.001f)
        assertEquals(0.82f, resolvedRectPass1.right, 0.001f)
        assertEquals(0.88f, resolvedRectPass1.bottom, 0.001f)

        // 2. Recreate lần 2 liên tiếp trước khi decode xong
        val resolvedRectPass2 = viewModel.resolveCropRectForSaveState(
            isOverlayInitialized = false,
            currentOverlayRect = uninitializedOverlayRect
        )
        assertEquals(0.12f, resolvedRectPass2.left, 0.001f)
        assertEquals(0.18f, resolvedRectPass2.top, 0.001f)
        assertEquals(0.82f, resolvedRectPass2.right, 0.001f)
        assertEquals(0.88f, resolvedRectPass2.bottom, 0.001f)

        // 3. Recreate lần 3 liên tiếp
        val resolvedRectPass3 = viewModel.resolveCropRectForSaveState(
            isOverlayInitialized = false,
            currentOverlayRect = uninitializedOverlayRect
        )
        assertEquals(0.12f, resolvedRectPass3.left, 0.001f)
        assertEquals(0.88f, resolvedRectPass3.bottom, 0.001f)

        // Trạng thái pendingNormalizedCropRect trong ViewModel vẫn nguyên vẹn
        assertNotNull(viewModel.pendingNormalizedCropRect)
    }

    @Test
    fun testPendingCropConsumed_whenOverlayBecomesInitialized() {
        val viewModel = CropRotateViewModel()
        val initialPending = createRectF(0.2f, 0.25f, 0.75f, 0.8f)
        viewModel.pendingNormalizedCropRect = initialPending

        // Overlay layout hoàn tất và tiêu thụ pendingNormalizedCropRect
        val consumed = viewModel.pendingNormalizedCropRect
        assertNotNull(consumed)
        viewModel.pendingNormalizedCropRect = null

        // Sau khi overlay đã initialized và user chỉnh crop sang vị trí mới
        val userEditedOverlayRect = createRectF(0.22f, 0.28f, 0.78f, 0.82f)
        val resolvedAfterInit = viewModel.resolveCropRectForSaveState(
            isOverlayInitialized = true,
            currentOverlayRect = userEditedOverlayRect
        )

        // Lúc này phải lấy từ overlay hiện tại
        assertEquals(0.22f, resolvedAfterInit.left, 0.001f)
        assertEquals(0.28f, resolvedAfterInit.top, 0.001f)
        assertEquals(0.78f, resolvedAfterInit.right, 0.001f)
        assertEquals(0.82f, resolvedAfterInit.bottom, 0.001f)
        assertNull(viewModel.pendingNormalizedCropRect)
    }

    @Test
    fun testSlowDecodeWithLatch_pendingCropNotLostDuringInFlightDecode() = runBlocking(Dispatchers.Default) {
        val viewModel = CropRotateViewModel()
        val pendingCrop = createRectF(0.1f, 0.15f, 0.85f, 0.9f)
        viewModel.pendingNormalizedCropRect = pendingCrop

        val decodeStartedLatch = CountDownLatch(1)
        val decodeHoldLatch = CountDownLatch(1)
        val decodeCompleted = AtomicBoolean(false)

        // Giả lập worker IO decode chậm
        launch {
            decodeStartedLatch.countDown()
            // Giữ decode bằng latch
            decodeHoldLatch.await(5, TimeUnit.SECONDS)
            decodeCompleted.set(true)
        }

        // Chờ decode bắt đầu
        decodeStartedLatch.await(2, TimeUnit.SECONDS)

        // Trong lúc decode đang bị giữ, xảy ra nhiều lần xoay thiết bị (recreation)
        for (i in 1..5) {
            val resolved = viewModel.resolveCropRectForSaveState(
                isOverlayInitialized = false,
                currentOverlayRect = createRectF(0f, 0f, 1f, 1f)
            )
            assertEquals("Iteration $i must retain custom crop left", 0.1f, resolved.left, 0.001f)
            assertEquals("Iteration $i must retain custom crop right", 0.85f, resolved.right, 0.001f)
        }

        // Giải phóng latch cho decode hoàn tất
        decodeHoldLatch.countDown()

        // Giả lập overlay layout sau decode
        val pendingToApply = viewModel.pendingNormalizedCropRect
        assertNotNull(pendingToApply)
        viewModel.pendingNormalizedCropRect = null

        assertEquals(0.1f, pendingToApply!!.left, 0.001f)
        assertEquals(0.85f, pendingToApply.right, 0.001f)
        assertNull(viewModel.pendingNormalizedCropRect)
    }

    @Test
    fun testResetClearsPendingCrop_andReturnsFullRect() {
        val viewModel = CropRotateViewModel()
        viewModel.pendingNormalizedCropRect = createRectF(0.1f, 0.2f, 0.8f, 0.9f)

        // User bấm Reset
        viewModel.pendingNormalizedCropRect = null
        val fullRect = createRectF(0f, 0f, 1f, 1f)

        val resolved = viewModel.resolveCropRectForSaveState(
            isOverlayInitialized = false,
            currentOverlayRect = fullRect
        )

        assertEquals(0f, resolved.left, 0.001f)
        assertEquals(0f, resolved.top, 0.001f)
        assertEquals(1f, resolved.right, 0.001f)
        assertEquals(1f, resolved.bottom, 0.001f)
    }
}
