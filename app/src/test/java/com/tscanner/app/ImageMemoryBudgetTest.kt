package com.tscanner.app

import com.tscanner.app.ui.editor.model.DocumentFilterType
import com.tscanner.app.ui.editor.model.PageEditState
import com.tscanner.app.utils.ImageMemoryBudgetCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageMemoryBudgetTest {

    private fun createState(
        filterType: DocumentFilterType = DocumentFilterType.ORIGINAL,
        sharpness: Int = 0,
        shadow: Int = 0,
        lighten: Int = 0
    ): PageEditState {
        return PageEditState(
            pageIndex = 0,
            inputImagePath = "/dummy/test.jpg",
            filterType = filterType,
            sharpnessIntensity = sharpness,
            shadowRemovalIntensity = shadow,
            backgroundLightenIntensity = lighten
        )
    }

    @Test
    fun testBytesPerPixelPerBranch() {
        val original = createState(DocumentFilterType.ORIGINAL)
        assertEquals("Original without effects should be 8 bytes/pixel", 8, ImageMemoryBudgetCalculator.getBytesPerPixel(original))

        val whitening = createState(DocumentFilterType.ORIGINAL, shadow = 30)
        assertEquals("Whitening only should be 12 bytes/pixel", 12, ImageMemoryBudgetCalculator.getBytesPerPixel(whitening))

        val grayscale = createState(DocumentFilterType.GRAYSCALE)
        assertEquals("Grayscale should be 12 bytes/pixel", 12, ImageMemoryBudgetCalculator.getBytesPerPixel(grayscale))

        val sharpen = createState(DocumentFilterType.ORIGINAL, sharpness = 50)
        assertEquals("Sharpen should be 20 bytes/pixel", 20, ImageMemoryBudgetCalculator.getBytesPerPixel(sharpen))

        val bw = createState(DocumentFilterType.BLACK_AND_WHITE)
        assertEquals("Black and white (adaptive binarization) should be 20 bytes/pixel", 20, ImageMemoryBudgetCalculator.getBytesPerPixel(bw))

        val combined = createState(DocumentFilterType.BLACK_AND_WHITE, sharpness = 50)
        assertEquals("Combined sharpen and B&W should be 24 bytes/pixel", 24, ImageMemoryBudgetCalculator.getBytesPerPixel(combined))
    }

    @Test
    fun testCalculationsForStandardResolutions() {
        val stateOrig = createState(DocumentFilterType.ORIGINAL)
        val stateBw = createState(DocumentFilterType.BLACK_AND_WHITE)

        // 12 MP (4000 x 3000)
        val bytes12Orig = ImageMemoryBudgetCalculator.calculateRequiredBytes(4000, 3000, stateOrig)
        assertEquals(4000L * 3000L * 8L, bytes12Orig)

        val bytes12Bw = ImageMemoryBudgetCalculator.calculateRequiredBytes(4000, 3000, stateBw)
        assertTrue("12MP B&W must require significantly more memory than Original", bytes12Bw > 4000L * 3000L * 20L)

        // 24 MP (6000 x 4000)
        val bytes24Orig = ImageMemoryBudgetCalculator.calculateRequiredBytes(6000, 4000, stateOrig)
        assertEquals(6000L * 4000L * 8L, bytes24Orig)

        // 48 MP (8000 x 6000)
        val bytes48Orig = ImageMemoryBudgetCalculator.calculateRequiredBytes(8000, 6000, stateOrig)
        assertEquals(8000L * 6000L * 8L, bytes48Orig)
    }

    @Test
    fun testInvalidDimensionsAndOverflow() {
        val state = createState()

        // Negative or zero dimensions
        assertEquals(-1L, ImageMemoryBudgetCalculator.calculateRequiredBytes(-1, 1000, state))
        assertEquals(-1L, ImageMemoryBudgetCalculator.calculateRequiredBytes(1000, 0, state))
        assertEquals(-1L, ImageMemoryBudgetCalculator.calculateRequiredBytes(0, 0, state))

        // Overflow: Int.MAX_VALUE * Int.MAX_VALUE
        val overflowBytes = ImageMemoryBudgetCalculator.calculateRequiredBytes(Int.MAX_VALUE, Int.MAX_VALUE, state)
        assertEquals(-1L, overflowBytes)

        // hasSufficientMemory must return false on invalid/overflow dimensions
        assertFalse(ImageMemoryBudgetCalculator.hasSufficientMemory(Int.MAX_VALUE, Int.MAX_VALUE, state, Long.MAX_VALUE))
        assertFalse(ImageMemoryBudgetCalculator.hasSufficientMemory(-10, 100, state, 1024L * 1024L * 1024L))
    }

    @Test
    fun testBudgetThresholdExactAndShortByOneByte() {
        val state = createState(DocumentFilterType.ORIGINAL)
        val width = 1000
        val height = 1000
        val required = ImageMemoryBudgetCalculator.calculateRequiredBytes(width, height, state)
        val reserve = ImageMemoryBudgetCalculator.SAFETY_RESERVE_BYTES

        // Exact threshold: required + reserve
        val exactBudget = required + reserve
        assertTrue("Exact budget must pass", ImageMemoryBudgetCalculator.hasSufficientMemory(width, height, state, exactBudget))

        // 1 byte short: exactBudget - 1
        val shortBudget = exactBudget - 1L
        assertFalse("Budget short by 1 byte must be rejected", ImageMemoryBudgetCalculator.hasSufficientMemory(width, height, state, shortBudget))
    }

    @Test
    fun testDivergenceUnderSameMemoryBudget() {
        val width = 4000
        val height = 3000
        val stateOrig = createState(DocumentFilterType.ORIGINAL) // 8 bpp
        val stateBw = createState(DocumentFilterType.BLACK_AND_WHITE) // 20 bpp

        val requiredOrig = ImageMemoryBudgetCalculator.calculateRequiredBytes(width, height, stateOrig)
        val reserve = ImageMemoryBudgetCalculator.SAFETY_RESERVE_BYTES

        // Budget that fits Original + reserve + 10MB headroom, but CANNOT fit B&W
        val constrainedHeap = requiredOrig + reserve + 10L * 1024L * 1024L

        assertTrue("Original branch must be allowed under this budget",
            ImageMemoryBudgetCalculator.hasSufficientMemory(width, height, stateOrig, constrainedHeap))

        assertFalse("B&W branch allocating integral/lum must be rejected under the same budget",
            ImageMemoryBudgetCalculator.hasSufficientMemory(width, height, stateBw, constrainedHeap))
    }
}
