package com.tscanner.app

import com.tscanner.app.ui.editor.model.NormalizedCropRect
import com.tscanner.app.ui.editor.model.PageGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageGeometryTest {

    @Test
    fun testNormalizeRotation() {
        assertEquals(0, PageGeometry.normalizeRotation(0))
        assertEquals(90, PageGeometry.normalizeRotation(90))
        assertEquals(180, PageGeometry.normalizeRotation(180))
        assertEquals(270, PageGeometry.normalizeRotation(270))
        assertEquals(0, PageGeometry.normalizeRotation(360))
        assertEquals(90, PageGeometry.normalizeRotation(450))
        assertEquals(270, PageGeometry.normalizeRotation(-90))
        assertEquals(180, PageGeometry.normalizeRotation(-180))
    }

    @Test
    fun testMapScreenCropToSource_0Degrees() {
        val base = NormalizedCropRect(0f, 0f, 1f, 1f)
        val screen = NormalizedCropRect(0.1f, 0.2f, 0.8f, 0.9f)
        val mapped = PageGeometry.mapScreenCropToSource(screen, base, 0)

        assertEquals(0.1f, mapped.left, 0.001f)
        assertEquals(0.2f, mapped.top, 0.001f)
        assertEquals(0.8f, mapped.right, 0.001f)
        assertEquals(0.9f, mapped.bottom, 0.001f)
    }

    @Test
    fun testMapScreenCropToSource_90Degrees() {
        // R01 scenario: Image is rotated 90° clockwise.
        // On screen, top-half corresponds to left-half of unrotated source image.
        val base = NormalizedCropRect(0f, 0f, 1f, 1f)
        val screenTopHalf = NormalizedCropRect(0f, 0f, 1f, 0.5f)
        val mapped = PageGeometry.mapScreenCropToSource(screenTopHalf, base, 90)

        // Must map to left-half in source space: left=0, right=0.5, top=0, bottom=1
        assertEquals(0f, mapped.left, 0.001f)
        assertEquals(0.5f, mapped.right, 0.001f)
        assertEquals(0f, mapped.top, 0.001f)
        assertEquals(1f, mapped.bottom, 0.001f)
    }

    @Test
    fun testMapScreenCropToSource_180Degrees() {
        // Rotated 180°: top-half on screen corresponds to bottom-half of source
        val base = NormalizedCropRect(0f, 0f, 1f, 1f)
        val screenTopHalf = NormalizedCropRect(0f, 0f, 1f, 0.5f)
        val mapped = PageGeometry.mapScreenCropToSource(screenTopHalf, base, 180)

        assertEquals(0f, mapped.left, 0.001f)
        assertEquals(1f, mapped.right, 0.001f)
        assertEquals(0.5f, mapped.top, 0.001f)
        assertEquals(1f, mapped.bottom, 0.001f)
    }

    @Test
    fun testMapScreenCropToSource_270Degrees() {
        // Rotated 270°: top-half on screen corresponds to right-half of source
        val base = NormalizedCropRect(0f, 0f, 1f, 1f)
        val screenTopHalf = NormalizedCropRect(0f, 0f, 1f, 0.5f)
        val mapped = PageGeometry.mapScreenCropToSource(screenTopHalf, base, 270)

        assertEquals(0.5f, mapped.left, 0.001f)
        assertEquals(1f, mapped.right, 0.001f)
        assertEquals(0f, mapped.top, 0.001f)
        assertEquals(1f, mapped.bottom, 0.001f)
    }

    @Test
    fun testCompoundCrop_sequentialCropping() {
        // Already cropped to [0.2, 0.2, 0.8, 0.8] (width=0.6, height=0.6)
        val base = NormalizedCropRect(0.2f, 0.2f, 0.8f, 0.8f)
        // User selects top-left quadrant of the cropped view: [0, 0, 0.5, 0.5]
        val screen = NormalizedCropRect(0f, 0f, 0.5f, 0.5f)
        val mapped = PageGeometry.mapScreenCropToSource(screen, base, 0)

        // New left = 0.2 + 0*0.6 = 0.2
        // New right = 0.2 + 0.5*0.6 = 0.5
        // New top = 0.2 + 0*0.6 = 0.2
        // New bottom = 0.2 + 0.5*0.6 = 0.5
        assertEquals(0.2f, mapped.left, 0.001f)
        assertEquals(0.5f, mapped.right, 0.001f)
        assertEquals(0.2f, mapped.top, 0.001f)
        assertEquals(0.5f, mapped.bottom, 0.001f)
    }
}
