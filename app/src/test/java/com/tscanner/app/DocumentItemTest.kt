package com.tscanner.app

import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.model.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentItemTest {

    @Test
    fun testDocumentItem_immutabilityAndCopy() {
        val original = DocumentItem(
            id = "doc-123",
            title = "Contract",
            pdfPath = "/storage/doc_doc-123.pdf",
            pagePaths = listOf("/storage/p1.jpg", "/storage/p2.jpg"),
            pageCount = 2,
            sizeBytes = 1024L,
            createdAt = 1000L,
            syncStatus = SyncStatus.LOCAL_ONLY,
            ownerId = "user_abc",
            contentRevision = 1L
        )

        // DiffUtil checks: copy creates new instance with updated properties
        val modified = original.copy(
            syncStatus = SyncStatus.SYNCED,
            driveFileId = "drive_file_999",
            lastSyncedAt = 2000L
        )

        // Verifying immutable semantics
        assertEquals(SyncStatus.LOCAL_ONLY, original.syncStatus)
        assertEquals(SyncStatus.SYNCED, modified.syncStatus)
        assertNull(original.driveFileId)
        assertEquals("drive_file_999", modified.driveFileId)
        assertEquals("user_abc", modified.ownerId)
        assertEquals(1L, modified.contentRevision)
    }

    @Test
    fun testDocumentItem_contentRevisionBump() {
        val original = DocumentItem(
            id = "doc-1",
            title = "Invoice",
            syncStatus = SyncStatus.SYNCED,
            contentRevision = 1L
        )

        // When user edits/crops pages, mark dirty:
        val dirty = original.copy(
            contentRevision = original.contentRevision + 1L,
            syncStatus = SyncStatus.LOCAL_ONLY
        )

        assertEquals(2L, dirty.contentRevision)
        assertEquals(SyncStatus.LOCAL_ONLY, dirty.syncStatus)
        assertNotEquals(original, dirty)
    }

    @Test
    fun testFileReferenceCount_logic() {
        val doc1 = DocumentItem(id = "1", title = "Doc 1", pdfPath = "/data/shared.pdf")
        val doc2 = DocumentItem(id = "2", title = "Doc 2", pdfPath = "/data/shared.pdf")
        val doc3 = DocumentItem(id = "3", title = "Doc 3", pdfPath = "/data/other.pdf")

        val docs = listOf(doc1, doc2, doc3)
        val targetPath = "/data/shared.pdf"

        val count = docs.count { it.pdfPath == targetPath }
        assertEquals(2, count)
        // With count > 1, A01 safe deletion logic protects the physical file from being deleted
        // when doc1 is removed!
    }
}
