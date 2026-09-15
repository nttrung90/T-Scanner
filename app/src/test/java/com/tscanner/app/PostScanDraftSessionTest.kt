package com.tscanner.app

import com.tscanner.app.data.model.PostScanSessionDraft
import com.tscanner.app.ui.editor.model.DocumentFilterType
import com.tscanner.app.ui.editor.model.NormalizedCropRect
import com.tscanner.app.ui.editor.model.PageEditState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostScanDraftSessionTest {

    @Test
    fun testDraftSerializationDeserializationRoundtrip() {
        val page1 = PageEditState(
            pageIndex = 0,
            inputImagePath = "/storage/emulated/0/Android/data/com.tscanner.app/files/draft_sessions/s1/raw_pages/page_1.jpg",
            filterType = DocumentFilterType.BLACK_AND_WHITE,
            sharpnessIntensity = 45,
            shadowRemovalIntensity = 30,
            backgroundLightenIntensity = 25,
            rotationDegrees = 90,
            cropRect = NormalizedCropRect(0.05f, 0.08f, 0.92f, 0.95f)
        )

        val page2 = PageEditState(
            pageIndex = 1,
            inputImagePath = "/storage/emulated/0/Android/data/com.tscanner.app/files/draft_sessions/s1/raw_pages/page_2.jpg",
            filterType = DocumentFilterType.GRAYSCALE,
            sharpnessIntensity = 20,
            shadowRemovalIntensity = 10,
            backgroundLightenIntensity = 0,
            rotationDegrees = 0,
            cropRect = NormalizedCropRect()
        )

        val originalDraft = PostScanSessionDraft(
            sessionId = "session-uuid-12345",
            documentTitle = "Hóa đơn VAT tháng 9",
            pageStates = listOf(page1, page2),
            schemaVersion = 2,
            revision = 42L,
            createdAt = 1700000000000L,
            updatedAt = 1700000050000L
        )

        val json = originalDraft.toJson()
        assertNotNull(json)

        val restoredDraft = PostScanSessionDraft.fromJson(json)

        assertEquals(originalDraft.sessionId, restoredDraft.sessionId)
        assertEquals(originalDraft.documentTitle, restoredDraft.documentTitle)
        assertEquals(2, restoredDraft.schemaVersion)
        assertEquals(42L, restoredDraft.revision)
        assertEquals(originalDraft.createdAt, restoredDraft.createdAt)
        assertEquals(originalDraft.updatedAt, restoredDraft.updatedAt)
        assertEquals(2, restoredDraft.pageStates.size)

        // Verify Page 1
        val rPage1 = restoredDraft.pageStates[0]
        assertEquals(0, rPage1.pageIndex)
        assertEquals(page1.inputImagePath, rPage1.inputImagePath)
        assertEquals(DocumentFilterType.BLACK_AND_WHITE, rPage1.filterType)
        assertEquals(45, rPage1.sharpnessIntensity)
        assertEquals(30, rPage1.shadowRemovalIntensity)
        assertEquals(25, rPage1.backgroundLightenIntensity)
        assertEquals(90, rPage1.rotationDegrees)
        assertEquals(0.05f, rPage1.cropRect.left, 0.001f)
        assertEquals(0.08f, rPage1.cropRect.top, 0.001f)
        assertEquals(0.92f, rPage1.cropRect.right, 0.001f)
        assertEquals(0.95f, rPage1.cropRect.bottom, 0.001f)

        // Verify Page 2
        val rPage2 = restoredDraft.pageStates[1]
        assertEquals(1, rPage2.pageIndex)
        assertEquals(DocumentFilterType.GRAYSCALE, rPage2.filterType)
        assertEquals(20, rPage2.sharpnessIntensity)
        assertTrue(rPage2.cropRect.isFull)
    }

    @Test
    fun testCorruptJsonFallback() {
        val malformedJson = JSONObject().apply {
            put("sessionId", "session-corrupt")
            // documentTitle is missing
            // pageStates is missing
        }

        val recovered = PostScanSessionDraft.fromJson(malformedJson)
        assertEquals("session-corrupt", recovered.sessionId)
        assertEquals("Tài liệu mới", recovered.documentTitle)
        assertEquals(1, recovered.schemaVersion)
        assertEquals(1L, recovered.revision)
        assertTrue(recovered.pageStates.isEmpty())
    }

    @Test
    fun testDraftBackwardCompatibilityDefaults() {
        val legacyJson = JSONObject().apply {
            put("sessionId", "legacy-session-001")
            put("documentTitle", "Old Draft Without Revision")
            // schemaVersion and revision are intentionally absent
        }

        val restored = PostScanSessionDraft.fromJson(legacyJson)
        assertEquals("legacy-session-001", restored.sessionId)
        assertEquals("Old Draft Without Revision", restored.documentTitle)
        assertEquals(1, restored.schemaVersion)
        assertEquals(1L, restored.revision)
    }

    @Test
    fun testApplyGlobalStylePreservesIndividualPageCropsAcrossMultiplePages() {
        val master = PageEditState(
            pageIndex = 0,
            inputImagePath = "/path/p0.jpg",
            filterType = DocumentFilterType.BLACK_AND_WHITE,
            sharpnessIntensity = 60,
            shadowRemovalIntensity = 40,
            backgroundLightenIntensity = 30,
            rotationDegrees = 270,
            cropRect = NormalizedCropRect(0.2f, 0.2f, 0.8f, 0.8f)
        )

        val pages = (0..4).map { i ->
            PageEditState(
                pageIndex = i,
                inputImagePath = "/path/p$i.jpg",
                filterType = DocumentFilterType.ORIGINAL,
                sharpnessIntensity = 0,
                shadowRemovalIntensity = 0,
                backgroundLightenIntensity = 0,
                rotationDegrees = (i * 90) % 360,
                cropRect = NormalizedCropRect(0.01f * i, 0.01f * i, 0.99f, 0.99f)
            )
        }.toMutableList()

        // Apply master style to all pages except master itself
        for (i in pages.indices) {
            if (i != 0) {
                pages[i] = pages[i].applyGlobalStyleFrom(master)
            }
        }

        // Each page now has master's filter and enhancement, but its own rotation and crop!
        for (i in 1..4) {
            val p = pages[i]
            assertEquals(DocumentFilterType.BLACK_AND_WHITE, p.filterType)
            assertEquals(60, p.sharpnessIntensity)
            assertEquals(40, p.shadowRemovalIntensity)
            assertEquals(30, p.backgroundLightenIntensity)

            // Individual orientation and framing are strictly preserved
            assertEquals((i * 90) % 360, p.rotationDegrees)
            assertEquals(0.01f * i, p.cropRect.left, 0.001f)
        }
    }
}
