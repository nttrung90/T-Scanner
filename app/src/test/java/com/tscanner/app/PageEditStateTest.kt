package com.tscanner.app

import com.tscanner.app.ui.editor.model.DocumentFilterType
import com.tscanner.app.ui.editor.model.NormalizedCropRect
import com.tscanner.app.ui.editor.model.PageEditState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageEditStateTest {

    @Test
    fun testInitialStateNotModified() {
        val state = PageEditState(
            pageIndex = 0,
            inputImagePath = "/path/to/raw_page_1.jpg"
        )
        assertFalse(state.isModified)
        assertEquals(0, state.sharpnessIntensity)
        assertEquals(DocumentFilterType.ORIGINAL, state.filterType)
        assertEquals(0, state.rotationDegrees)
        assertTrue(state.cropRect.isFull)
    }

    @Test
    fun testModifiedWhenFilterOrSharpnessChanges() {
        val state1 = PageEditState(0, "/path/raw.jpg", filterType = DocumentFilterType.GRAYSCALE)
        assertTrue(state1.isModified)

        val state2 = PageEditState(0, "/path/raw.jpg", sharpnessIntensity = 25)
        assertTrue(state2.isModified)

        val state3 = PageEditState(0, "/path/raw.jpg", rotationDegrees = 90)
        assertTrue(state3.isModified)

        val state4 = PageEditState(0, "/path/raw.jpg", cropRect = NormalizedCropRect(0.1f, 0.1f, 0.9f, 0.9f))
        assertTrue(state4.isModified)
    }

    @Test
    fun testApplyGlobalStylePreservesCropAndRotation() {
        val sourceState = PageEditState(
            pageIndex = 0,
            inputImagePath = "/path/page1.jpg",
            filterType = DocumentFilterType.BLACK_AND_WHITE,
            sharpnessIntensity = 45,
            shadowRemovalIntensity = 30,
            backgroundLightenIntensity = 20,
            rotationDegrees = 90,
            cropRect = NormalizedCropRect(0.1f, 0.2f, 0.8f, 0.9f)
        )

        val targetState = PageEditState(
            pageIndex = 1,
            inputImagePath = "/path/page2.jpg",
            filterType = DocumentFilterType.ORIGINAL,
            sharpnessIntensity = 0,
            shadowRemovalIntensity = 0,
            backgroundLightenIntensity = 0,
            rotationDegrees = 180,
            cropRect = NormalizedCropRect(0.05f, 0.05f, 0.95f, 0.95f)
        )

        val updatedTarget = targetState.applyGlobalStyleFrom(sourceState)

        // Verifying global styling applied
        assertEquals(DocumentFilterType.BLACK_AND_WHITE, updatedTarget.filterType)
        assertEquals(45, updatedTarget.sharpnessIntensity)
        assertEquals(30, updatedTarget.shadowRemovalIntensity)
        assertEquals(20, updatedTarget.backgroundLightenIntensity)

        // Verifying target's individual crop and rotation are strictly preserved!
        assertEquals(1, updatedTarget.pageIndex)
        assertEquals("/path/page2.jpg", updatedTarget.inputImagePath)
        assertEquals(180, updatedTarget.rotationDegrees)
        assertEquals(0.05f, updatedTarget.cropRect.left, 0.001f)
        assertEquals(0.95f, updatedTarget.cropRect.right, 0.001f)
    }

    @Test
    fun testResetToOriginal() {
        val state = PageEditState(
            pageIndex = 2,
            inputImagePath = "/path/page3.jpg",
            filterType = DocumentFilterType.BLACK_AND_WHITE,
            sharpnessIntensity = 50,
            shadowRemovalIntensity = 40,
            rotationDegrees = 270,
            cropRect = NormalizedCropRect(0.2f, 0.2f, 0.8f, 0.8f)
        )
        assertTrue(state.isModified)

        val reset = state.resetToOriginal()
        assertFalse(reset.isModified)
        assertEquals(2, reset.pageIndex)
        assertEquals("/path/page3.jpg", reset.inputImagePath)
        assertEquals(0, reset.sharpnessIntensity)
        assertEquals(0, reset.rotationDegrees)
        assertTrue(reset.cropRect.isFull)
    }
}
