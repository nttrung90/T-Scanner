package com.tscanner.app

import com.tscanner.app.ui.idcard.IdCardSessionDraft
import com.tscanner.app.utils.IdCardComposeConfig
import com.tscanner.app.utils.IdCardLayoutMode
import com.tscanner.app.utils.IdCardScaleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdCardSessionDraftTest {

    @Test
    fun testDraftPreservesAllCustomProperties() {
        val originalDraft = IdCardSessionDraft(
            frontImagePath = "/data/user/0/com.tscanner.app/files/images/front_swapped.jpg",
            backImagePath = "/data/user/0/com.tscanner.app/files/images/back_swapped.jpg",
            config = IdCardComposeConfig(
                layoutMode = IdCardLayoutMode.A4_LANDSCAPE_TWO_SIDES,
                scaleMode = IdCardScaleMode.FIT_PAGE,
                showCutBorder = false,
                addWatermark = false,
                watermarkText = "CONFIDENTIAL"
            )
        )

        val map = originalDraft.toMap()
        val restored = IdCardSessionDraft.fromValues(
            frontPath = map[IdCardSessionDraft.KEY_FRONT_PATH] as? String,
            backPath = map[IdCardSessionDraft.KEY_BACK_PATH] as? String,
            layoutModeStr = map[IdCardSessionDraft.KEY_LAYOUT_MODE] as? String,
            scaleModeStr = map[IdCardSessionDraft.KEY_SCALE_MODE] as? String,
            showCutBorder = (map[IdCardSessionDraft.KEY_SHOW_CUT_BORDER] as? Boolean) ?: true,
            addWatermark = (map[IdCardSessionDraft.KEY_ADD_WATERMARK] as? Boolean) ?: true,
            watermarkText = map[IdCardSessionDraft.KEY_WATERMARK_TEXT] as? String
        )

        assertEquals("/data/user/0/com.tscanner.app/files/images/front_swapped.jpg", restored.frontImagePath)
        assertEquals("/data/user/0/com.tscanner.app/files/images/back_swapped.jpg", restored.backImagePath)
        assertEquals(IdCardLayoutMode.A4_LANDSCAPE_TWO_SIDES, restored.config.layoutMode)
        assertEquals(IdCardScaleMode.FIT_PAGE, restored.config.scaleMode)
        assertFalse(restored.config.showCutBorder)
        assertFalse(restored.config.addWatermark)
        assertEquals("CONFIDENTIAL", restored.config.watermarkText)
    }

    @Test
    fun testDraftWithSwappedSides() {
        val originalDraft = IdCardSessionDraft(
            frontImagePath = "/path/to/card_back.jpg", // Swapped!
            backImagePath = "/path/to/card_front.jpg",
            config = IdCardComposeConfig(
                layoutMode = IdCardLayoutMode.A4_PORTRAIT_TWO_SIDES,
                scaleMode = IdCardScaleMode.ACTUAL_SIZE,
                showCutBorder = true
            )
        )

        val map = originalDraft.toMap()
        val restored = IdCardSessionDraft.fromValues(
            frontPath = map[IdCardSessionDraft.KEY_FRONT_PATH] as? String,
            backPath = map[IdCardSessionDraft.KEY_BACK_PATH] as? String,
            layoutModeStr = map[IdCardSessionDraft.KEY_LAYOUT_MODE] as? String,
            scaleModeStr = map[IdCardSessionDraft.KEY_SCALE_MODE] as? String,
            showCutBorder = (map[IdCardSessionDraft.KEY_SHOW_CUT_BORDER] as? Boolean) ?: true,
            addWatermark = (map[IdCardSessionDraft.KEY_ADD_WATERMARK] as? Boolean) ?: true,
            watermarkText = map[IdCardSessionDraft.KEY_WATERMARK_TEXT] as? String
        )

        assertEquals("/path/to/card_back.jpg", restored.frontImagePath)
        assertEquals("/path/to/card_front.jpg", restored.backImagePath)
        assertEquals(IdCardLayoutMode.A4_PORTRAIT_TWO_SIDES, restored.config.layoutMode)
    }

    @Test
    fun testDraftDefaultsOnNullInputs() {
        val restored = IdCardSessionDraft.fromValues(
            frontPath = null,
            backPath = null,
            layoutModeStr = null,
            scaleModeStr = null
        )

        assertEquals(IdCardLayoutMode.A4_PORTRAIT_TWO_SIDES, restored.config.layoutMode)
        assertEquals(IdCardScaleMode.ACTUAL_SIZE, restored.config.scaleMode)
        assertTrue(restored.config.showCutBorder)
        assertTrue(restored.config.addWatermark)
    }
}
