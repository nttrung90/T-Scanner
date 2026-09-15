package com.tscanner.app

import com.tscanner.app.ui.editor.model.NormalizedCropRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizedCropRectTest {

    @Test
    fun testDefaultIsFull() {
        val rect = NormalizedCropRect()
        assertTrue(rect.isFull)
        assertEquals(0f, rect.left, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
        assertEquals(1f, rect.right, 0.001f)
        assertEquals(1f, rect.bottom, 0.001f)
    }

    @Test
    fun testCustomNotFull() {
        val rect = NormalizedCropRect(0.1f, 0.2f, 0.8f, 0.9f)
        assertFalse(rect.isFull)
        assertEquals(0.7f, rect.width(), 0.001f)
        assertEquals(0.7f, rect.height(), 0.001f)
    }

    @Test
    fun testRotate90Clockwise() {
        // Point (x, y) becomes (1 - y, x)
        // Rect with left=0.1, top=0.2, right=0.8, bottom=0.9
        // newLeft = 1 - bottom = 1 - 0.9 = 0.1
        // newTop = left = 0.1
        // newRight = 1 - top = 1 - 0.2 = 0.8
        // newBottom = right = 0.8
        val rect = NormalizedCropRect(left = 0.1f, top = 0.2f, right = 0.8f, bottom = 0.9f)
        val rotated = rect.rotate90Clockwise()

        assertEquals(0.1f, rotated.left, 0.001f)
        assertEquals(0.1f, rotated.top, 0.001f)
        assertEquals(0.8f, rotated.right, 0.001f)
        assertEquals(0.8f, rotated.bottom, 0.001f)
    }

    @Test
    fun testRotateFourTimesReturnsOriginal() {
        val rect = NormalizedCropRect(left = 0.15f, top = 0.25f, right = 0.75f, bottom = 0.85f)
        val r1 = rect.rotate90Clockwise()
        val r2 = r1.rotate90Clockwise()
        val r3 = r2.rotate90Clockwise()
        val r4 = r3.rotate90Clockwise()

        assertEquals(rect.left, r4.left, 0.001f)
        assertEquals(rect.top, r4.top, 0.001f)
        assertEquals(rect.right, r4.right, 0.001f)
        assertEquals(rect.bottom, r4.bottom, 0.001f)
    }

    @Test
    fun testRotateCounterClockwiseReversesClockwise() {
        val rect = NormalizedCropRect(left = 0.12f, top = 0.18f, right = 0.82f, bottom = 0.88f)
        val rotated = rect.rotate90Clockwise()
        val restored = rotated.rotate90CounterClockwise()

        assertEquals(rect.left, restored.left, 0.001f)
        assertEquals(rect.top, restored.top, 0.001f)
        assertEquals(rect.right, restored.right, 0.001f)
        assertEquals(rect.bottom, restored.bottom, 0.001f)
    }

    @Test
    fun testSafeNormalizedClampsInvalidCoordinates() {
        val invalidRect = NormalizedCropRect(left = -0.5f, top = 1.5f, right = 0.4f, bottom = 0.2f)
        val safe = invalidRect.safeNormalized()

        assertTrue(safe.left >= 0f)
        assertTrue(safe.right <= 1f)
        assertTrue(safe.left < safe.right)
        assertTrue(safe.top >= 0f)
        assertTrue(safe.bottom <= 1f)
        assertTrue(safe.top < safe.bottom)
    }
}
