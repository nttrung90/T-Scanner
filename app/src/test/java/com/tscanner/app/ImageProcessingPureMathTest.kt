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

    @Test
    fun testSharpnessZeroIsExactNoOp() {
        val width = 30
        val height = 30
        val original = IntArray(width * height)
        for (i in original.indices) {
            original[i] = (0xFF shl 24) or ((i * 37) and 0xFFFFFF)
        }
        val pixels = original.clone()

        ImageProcessingAlgorithms.applyThresholdedUnsharpMask(
            pixels = pixels,
            width = width,
            height = height,
            intensity = 0
        )

        for (i in original.indices) {
            assertEquals("Pixel $i must be strictly unchanged at intensity 0", original[i], pixels[i])
        }
    }

    @Test
    fun testLuminanceSharpeningPreservesChroma() {
        // Red stamp pixel (220, 50, 40) on paper background (240, 240, 235)
        val width = 8
        val height = 8
        val pixels = IntArray(width * height) { (0xFF shl 24) or (240 shl 16) or (240 shl 8) or 235 }

        // Center 2x2 red stamp
        val stampIndices = listOf(3 * width + 3, 3 * width + 4, 4 * width + 3, 4 * width + 4)
        for (idx in stampIndices) {
            pixels[idx] = (0xFF shl 24) or (220 shl 16) or (50 shl 8) or 40
        }

        val origStamp = pixels[stampIndices[0]]
        val origR = (origStamp shr 16) and 0xFF
        val origG = (origStamp shr 8) and 0xFF
        val origB = origStamp and 0xFF
        val origDiffRG = origR - origG
        val origDiffBG = origB - origG

        ImageProcessingAlgorithms.applyThresholdedUnsharpMask(
            pixels = pixels,
            width = width,
            height = height,
            intensity = 75,
            radius = 1
        )

        val sharpenedStamp = pixels[stampIndices[0]]
        val sharpR = (sharpenedStamp shr 16) and 0xFF
        val sharpG = (sharpenedStamp shr 8) and 0xFF
        val sharpB = sharpenedStamp and 0xFF

        val sharpDiffRG = sharpR - sharpG
        val sharpDiffBG = sharpB - sharpG

        // Chroma relationship must be preserved with zero color fringing!
        assertEquals("R-G color difference must be preserved", origDiffRG, sharpDiffRG)
        assertEquals("B-G color difference must be preserved", origDiffBG, sharpDiffBG)
    }

    @Test
    fun testMultiScaleFineEdgeEnhancement() {
        // Blurred vertical text stroke: background 230, blurred transition 160, center stroke 70
        val width = 16
        val height = 8
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val lum = when (x) {
                    6 -> 150 // transition
                    7 -> 70  // center stroke
                    8 -> 70  // center stroke
                    9 -> 150 // transition
                    else -> 230 // paper background
                }
                pixels[y * width + x] = (0xFF shl 24) or (lum shl 16) or (lum shl 8) or lum
            }
        }

        val origContrast = 230 - 70 // 160

        ImageProcessingAlgorithms.applyThresholdedUnsharpMask(
            pixels = pixels,
            width = width,
            height = height,
            intensity = 80,
            radius = 2
        )

        val sharpenedStroke = (pixels[4 * width + 7] shr 16) and 0xFF
        val sharpenedBg = (pixels[4 * width + 2] shr 16) and 0xFF
        val newContrast = sharpenedBg - sharpenedStroke

        // Sharpness must increase contrast between center stroke and background
        assertTrue("Sharpened contrast ($newContrast) should be greater than original ($origContrast)", newContrast > origContrast)
        // Center stroke should be darkened for better legibility
        assertTrue("Stroke center ($sharpenedStroke) should be darker than original (70)", sharpenedStroke <= 70)
    }

    @Test
    fun testPaperNoiseSuppression() {
        val width = 20
        val height = 20
        val pixels = IntArray(width * height)

        // Flat white paper background with subtle noise within [-2, +2]
        for (i in 0 until width * height) {
            val noise = (i % 5) - 2 // -2, -1, 0, 1, 2
            val lum = 235 + noise
            pixels[i] = (0xFF shl 24) or (lum shl 16) or (lum shl 8) or lum
        }

        val originalSnapshot = pixels.clone()

        ImageProcessingAlgorithms.applyThresholdedUnsharpMask(
            pixels = pixels,
            width = width,
            height = height,
            intensity = 60,
            radius = 2,
            noiseThreshold = 3
        )

        // Soft coring should suppress subtle noise on paper without amplifying it
        var maxDiff = 0
        for (i in pixels.indices) {
            val orig = originalSnapshot[i] and 0xFF
            val curr = pixels[i] and 0xFF
            val d = kotlin.math.abs(curr - orig)
            if (d > maxDiff) maxDiff = d
        }

        assertTrue("Paper noise should remain suppressed (max delta = $maxDiff)", maxDiff <= 2)
    }

    @Test
    fun testHaloSuppressionPreventsStrokeClogging() {
        // Two parallel strokes (x=4 and x=6) separated by a 1px gap (x=5)
        val width = 12
        val height = 8
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val lum = when (x) {
                    4, 6 -> 50 // parallel strokes
                    5 -> 200    // narrow 1px white gap (like loop in 'e')
                    else -> 230
                }
                pixels[y * width + x] = (0xFF shl 24) or (lum shl 16) or (lum shl 8) or lum
            }
        }

        ImageProcessingAlgorithms.applyThresholdedUnsharpMask(
            pixels = pixels,
            width = width,
            height = height,
            intensity = 100, // max intensity
            radius = 1
        )

        val gapPixel = (pixels[4 * width + 5] shr 16) and 0xFF
        val strokePixel = (pixels[4 * width + 4] shr 16) and 0xFF

        // Gap must still remain significantly brighter than stroke (not clogged/blackened)
        assertTrue("Gap pixel ($gapPixel) must remain brighter than stroke ($strokePixel)", gapPixel > strokePixel + 50)
    }

    @Test
    fun testRoiWithPaddingMatchesFullImage() {
        val fullW = 40
        val fullH = 40
        val fullImage = IntArray(fullW * fullH)

        // Generate synthetic document content
        for (y in 0 until fullH) {
            for (x in 0 until fullW) {
                val lum = ((x * 13 + y * 17) % 200 + 40).coerceIn(0, 255)
                fullImage[y * fullW + x] = (0xFF shl 24) or (lum shl 16) or (lum shl 8) or lum
            }
        }

        // Full image sharpness pass
        val fullSharpened = fullImage.clone()
        val radius = 2
        ImageProcessingAlgorithms.applyThresholdedUnsharpMask(
            pixels = fullSharpened,
            width = fullW,
            height = fullH,
            intensity = 70,
            radius = radius
        )

        // Simulate ROI with apron padding
        val roiLeft = 10
        val roiTop = 10
        val roiW = 15
        val roiH = 15
        val padding = radius * 2 // 4

        val padLeft = kotlin.math.min(roiLeft, padding)
        val padTop = kotlin.math.min(roiTop, padding)
        val padRight = kotlin.math.min(fullW - (roiLeft + roiW), padding)
        val padBottom = kotlin.math.min(fullH - (roiTop + roiH), padding)

        val paddedW = roiW + padLeft + padRight
        val paddedH = roiH + padTop + padBottom
        val paddedPixels = IntArray(paddedW * paddedH)

        for (py in 0 until paddedH) {
            val srcY = roiTop - padTop + py
            for (px in 0 until paddedW) {
                val srcX = roiLeft - padLeft + px
                paddedPixels[py * paddedW + px] = fullImage[srcY * fullW + srcX]
            }
        }

        // Run sharpening on padded ROI with same radius
        ImageProcessingAlgorithms.applyThresholdedUnsharpMask(
            pixels = paddedPixels,
            width = paddedW,
            height = paddedH,
            intensity = 70,
            radius = radius
        )

        // Crop unpadded ROI and verify against fullSharpened
        for (ry in 0 until roiH) {
            val fullY = roiTop + ry
            val padY = padTop + ry
            for (rx in 0 until roiW) {
                val fullX = roiLeft + rx
                val padX = padLeft + rx

                val expected = fullSharpened[fullY * fullW + fullX]
                val actual = paddedPixels[padY * paddedW + padX]

                assertEquals("ROI pixel at ($rx, $ry) should match full image export", expected, actual)
            }
        }
    }

    @Test
    fun testBoundaryAndExtremeCases() {
        // 1x1 image
        val p1 = intArrayOf((0xFF shl 24) or 0x808080)
        ImageProcessingAlgorithms.applyThresholdedUnsharpMask(p1, 1, 1, 100)
        assertEquals((0xFF shl 24) or 0x808080, p1[0])

        // 2x2 image
        val p4 = IntArray(4) { (0xFF shl 24) or 0x606060 }
        ImageProcessingAlgorithms.applyThresholdedUnsharpMask(p4, 2, 2, 100)
        for (p in p4) {
            assertTrue((p and 0xFF) in 0..255)
        }

        // All white
        val pWhite = IntArray(25) { (0xFF shl 24) or 0xFFFFFF }
        ImageProcessingAlgorithms.applyThresholdedUnsharpMask(pWhite, 5, 5, 100)
        for (p in pWhite) {
            assertEquals((0xFF shl 24) or 0xFFFFFF, p)
        }

        // All black
        val pBlack = IntArray(25) { (0xFF shl 24) or 0x000000 }
        ImageProcessingAlgorithms.applyThresholdedUnsharpMask(pBlack, 5, 5, 100)
        for (p in pBlack) {
            assertEquals((0xFF shl 24) or 0x000000, p)
        }
    }
}
