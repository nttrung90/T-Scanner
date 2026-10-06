package com.tscanner.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropRotateStateAndMathTest {

    private fun normalizeRotation(angle: Float, delta: Float): Float {
        var result = (angle + delta) % 360f
        if (result < 0f) {
            result += 360f
        }
        return result
    }

    @Test
    fun testRotationAngles() {
        var angle = 0f
        angle = normalizeRotation(angle, 90f)
        assertEquals(90f, angle, 0.001f)

        angle = normalizeRotation(angle, 90f)
        assertEquals(180f, angle, 0.001f)

        angle = normalizeRotation(angle, 90f)
        assertEquals(270f, angle, 0.001f)

        angle = normalizeRotation(angle, 90f)
        assertEquals(0f, angle, 0.001f)

        // Rotate left
        angle = normalizeRotation(0f, -90f)
        assertEquals(270f, angle, 0.001f)
    }

    @Test
    fun testNormalizedCropRectRoundTrip() {
        val boundsLeft = 50f
        val boundsTop = 100f
        val boundsWidth = 800f
        val boundsHeight = 1200f
        val boundsRight = boundsLeft + boundsWidth
        val boundsBottom = boundsTop + boundsHeight

        val normLeft = 0.1f
        val normTop = 0.2f
        val normRight = 0.8f
        val normBottom = 0.9f

        // Convert to absolute
        val absLeft = boundsLeft + normLeft * boundsWidth
        val absTop = boundsTop + normTop * boundsHeight
        val absRight = boundsLeft + normRight * boundsWidth
        val absBottom = boundsTop + normBottom * boundsHeight

        // Convert back to normalized
        val reconNormLeft = (absLeft - boundsLeft) / boundsWidth
        val reconNormTop = (absTop - boundsTop) / boundsHeight
        val reconNormRight = (absRight - boundsLeft) / boundsWidth
        val reconNormBottom = (absBottom - boundsTop) / boundsHeight

        assertEquals(normLeft, reconNormLeft, 0.0001f)
        assertEquals(normTop, reconNormTop, 0.0001f)
        assertEquals(normRight, reconNormRight, 0.0001f)
        assertEquals(normBottom, reconNormBottom, 0.0001f)
    }

    @Test
    fun testNormalizedCropRectClamping() {
        val clampedLeft = (-0.5f).coerceIn(0f, 1f)
        val clampedRight = (1.5f).coerceIn(0f, 1f)
        assertEquals(0f, clampedLeft, 0.0001f)
        assertEquals(1f, clampedRight, 0.0001f)
    }
}
