package com.tscanner.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropClampTest {

    private fun safeClamp(value: Float, min: Float, max: Float): Float {
        val safeMin = minOf(min, max)
        val safeMax = maxOf(min, max)
        return value.coerceIn(safeMin, safeMax)
    }

    @Test
    fun testSafeClamp_normalBounds() {
        val clamped = safeClamp(50f, 0f, 100f)
        assertEquals(50f, clamped, 0.001f)
    }

    @Test
    fun testSafeClamp_lowerBound() {
        val clamped = safeClamp(-10f, 0f, 100f)
        assertEquals(0f, clamped, 0.001f)
    }

    @Test
    fun testSafeClamp_upperBound() {
        val clamped = safeClamp(150f, 0f, 100f)
        assertEquals(100f, clamped, 0.001f)
    }

    @Test
    fun testSafeClamp_invertedBounds_doesNotThrow() {
        // Reproduces B07 condition: when min > max due to narrow image bounds
        val min = 120f
        val max = 80f
        val clamped = safeClamp(100f, min, max)
        // Should clamp between 80 and 120 without throwing IllegalArgumentException
        assertTrue(clamped in 80f..120f)
        assertEquals(100f, clamped, 0.001f)
    }

    @Test
    fun testSafeClamp_zeroWidthBounds() {
        val clamped = safeClamp(50f, 40f, 40f)
        assertEquals(40f, clamped, 0.001f)
    }
}
