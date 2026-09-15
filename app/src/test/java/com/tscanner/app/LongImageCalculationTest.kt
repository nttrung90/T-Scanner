package com.tscanner.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LongImageCalculationTest {

    data class PageDimension(val width: Int, val height: Int)

    private fun calculateCanvasDimensions(pages: List<PageDimension>): Pair<Int, Int> {
        val targetWidth = pages.maxOf { it.width }
        val scaledHeights = pages.map { page ->
            if (page.width <= 0) 0
            else (page.height.toLong() * targetWidth / page.width).toInt()
        }
        var totalHeight = scaledHeights.sum()
        var finalWidth = targetWidth

        val maxAllowedHeight = 16384
        if (totalHeight > maxAllowedHeight) {
            val scaleFactor = maxAllowedHeight.toFloat() / totalHeight.toFloat()
            totalHeight = maxAllowedHeight
            finalWidth = (targetWidth * scaleFactor).toInt().coerceAtLeast(1)
        }

        return Pair(finalWidth, totalHeight)
    }

    @Test
    fun testCanvasDimensions_differentPageWidths_noTruncation() {
        // Reproduces B06 scenario: 600x800 and 300x800
        // Without scaling: canvas was 800+800 = 1600, but page 2 scaled to targetWidth 600 becomes 600x1600 -> total 2400 (bottom was cut off)
        // With B06 formula: page 1 is 600x800, page 2 scaled to width 600 is height 1600. Total height = 2400.
        val pages = listOf(
            PageDimension(600, 800),
            PageDimension(300, 800)
        )
        val (width, height) = calculateCanvasDimensions(pages)
        assertEquals(600, width)
        assertEquals(2400, height)
    }

    @Test
    fun testCanvasDimensions_uniformPages() {
        val pages = listOf(
            PageDimension(1000, 1500),
            PageDimension(1000, 1500),
            PageDimension(1000, 1500)
        )
        val (width, height) = calculateCanvasDimensions(pages)
        assertEquals(1000, width)
        assertEquals(4500, height)
    }

    @Test
    fun testCanvasDimensions_exceedsMaxHeight_clampsAndScales() {
        // 20 pages of 1000x1000 -> total height 20,000 > 16384
        val pages = List(20) { PageDimension(1000, 1000) }
        val (width, height) = calculateCanvasDimensions(pages)
        assertEquals(16384, height)
        assertTrue(width < 1000)
        assertEquals((1000 * (16384f / 20000f)).toInt(), width)
    }
}
