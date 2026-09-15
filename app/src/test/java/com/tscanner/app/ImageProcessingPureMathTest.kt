package com.tscanner.app

import com.tscanner.app.utils.ImageProcessingAlgorithms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageProcessingPureMathTest {

    @Test
    fun testScaledRadiusCalculation() {
        // At 1080p preview
        val rPreview = ImageProcessingAlgorithms.calculateScaledRadius(1080, 1920, baseRadiusFor1080p = 2)
        assertTrue(rPreview in 2..4)

        // At 4000p full-res
        val rFullRes = ImageProcessingAlgorithms.calculateScaledRadius(3000, 4000, baseRadiusFor1080p = 2)
        assertTrue(rFullRes in 6..8)
        assertTrue(rFullRes > rPreview)
    }

    @Test
    fun testThresholdedUnsharpMaskNoOverflow() {
        val width = 20
        val height = 20
        val pixels = IntArray(width * height)

        // Fill with gradient from 0 to 255
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = ((x + y) * 255 / (width + height)).coerceIn(0, 255)
                pixels[y * width + x] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
            }
        }

        ImageProcessingAlgorithms.applyThresholdedUnsharpMask(
            pixels = pixels,
            width = width,
            height = height,
            intensity = 100, // Maximum intensity
            radius = 2,
            noiseThreshold = 2,
            maxDiff = 60
        )

        // Verify no byte overflow or underflow
        for (p in pixels) {
            val a = (p shr 24) and 0xFF
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF

            assertEquals(0xFF, a)
            assertTrue(r in 0..255)
            assertTrue(g in 0..255)
            assertTrue(b in 0..255)
        }
    }

    @Test
    fun testPaperWhiteningClampsDarkInk() {
        val width = 4
        val height = 4
        val pixels = IntArray(width * height)

        // Pixel 0 is dark text (value 30)
        pixels[0] = (0xFF shl 24) or (30 shl 16) or (30 shl 8) or 30

        // Pixel 1 is paper background (value 180)
        pixels[1] = (0xFF shl 24) or (180 shl 16) or (180 shl 8) or 180

        ImageProcessingAlgorithms.applyPaperWhitening(
            pixels = pixels,
            width = width,
            height = height,
            shadowIntensity = 60,
            lightenIntensity = 60
        )

        val inkVal = (pixels[0] shr 16) and 0xFF
        val paperVal = (pixels[1] shr 16) and 0xFF

        // Ink stays completely untouched (at 30) because it is below the threshold of paper
        assertEquals(30, inkVal)

        // Paper is boosted higher towards 255
        assertTrue("Paper brightness should increase", paperVal > 180)
    }

    @Test
    fun testGrayscaleConversion() {
        val width = 2
        val height = 1
        val pixels = IntArray(2)

        // Pure Red: (255, 0, 0) -> (299 * 255) / 1000 = 76
        pixels[0] = (0xFF shl 24) or (255 shl 16) or (0 shl 8) or 0
        // Pure Green: (0, 255, 0) -> (587 * 255) / 1000 = 149
        pixels[1] = (0xFF shl 24) or (0 shl 16) or (255 shl 8) or 0

        ImageProcessingAlgorithms.applyGrayscale(pixels, width, height)

        val redGray = (pixels[0] shr 16) and 0xFF
        val greenGray = (pixels[1] shr 16) and 0xFF

        assertEquals(76, redGray)
        assertEquals(149, greenGray)
    }

    @Test
    fun testAdaptiveBinarizationOutputsPureBlackAndWhite() {
        val width = 16
        val height = 16
        val pixels = IntArray(width * height)

        // Create background with local dark text stroke in the middle
        for (y in 0 until height) {
            for (x in 0 until width) {
                val isText = (x in 6..9 && y in 6..9)
                val valCol = if (isText) 40 else 200
                pixels[y * width + x] = (0xFF shl 24) or (valCol shl 16) or (valCol shl 8) or valCol
            }
        }

        ImageProcessingAlgorithms.applyAdaptiveBinarization(
            pixels = pixels,
            width = width,
            height = height,
            windowSize = 7,
            cOffset = 10
        )

        // Text center should be pure black (0), background should be pure white (255)
        val textPixel = (pixels[7 * width + 7] shr 16) and 0xFF
        val bgPixel = (pixels[0] shr 16) and 0xFF

        assertEquals(0, textPixel)
        assertEquals(255, bgPixel)
    }
}
