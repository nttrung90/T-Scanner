package com.tscanner.app

import com.tscanner.app.data.model.ManagedFileItem
import com.tscanner.app.data.model.ManagedFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DocumentManagementFilterLogicTest {

    private val sampleFiles = listOf(
        ManagedFileItem(
            file = File("/dummy/invoice.pdf"),
            name = "invoice.pdf",
            path = "/dummy/invoice.pdf",
            sizeBytes = 1024L,
            lastModified = 1000L,
            fileType = ManagedFileType.PDF
        ),
        ManagedFileItem(
            file = File("/dummy/report.doc"),
            name = "report.doc",
            path = "/dummy/report.doc",
            sizeBytes = 2048L,
            lastModified = 2000L,
            fileType = ManagedFileType.WORD
        ),
        ManagedFileItem(
            file = File("/dummy/budget_sheet.csv"),
            name = "budget_sheet.csv",
            path = "/dummy/budget_sheet.csv",
            sizeBytes = 512L,
            lastModified = 3000L,
            fileType = ManagedFileType.EXCEL
        ),
        ManagedFileItem(
            file = File("/dummy/scan_id_front.jpg"),
            name = "scan_id_front.jpg",
            path = "/dummy/scan_id_front.jpg",
            sizeBytes = 4096L,
            lastModified = 4000L,
            fileType = ManagedFileType.IMAGE
        )
    )

    private fun filterFiles(
        files: List<ManagedFileItem>,
        filterType: ManagedFileType,
        searchQuery: String
    ): List<ManagedFileItem> {
        return files.filter { item ->
            val matchesType = when (filterType) {
                ManagedFileType.ALL -> true
                else -> item.fileType == filterType
            }
            val matchesSearch = if (searchQuery.isBlank()) {
                true
            } else {
                item.name.contains(searchQuery, ignoreCase = true) ||
                        item.path.contains(searchQuery, ignoreCase = true)
            }
            matchesType && matchesSearch
        }
    }

    @Test
    fun testFilterByTypeAll() {
        val filtered = filterFiles(sampleFiles, ManagedFileType.ALL, "")
        assertEquals(4, filtered.size)
    }

    @Test
    fun testFilterByTypePdf() {
        val filtered = filterFiles(sampleFiles, ManagedFileType.PDF, "")
        assertEquals(1, filtered.size)
        assertEquals("invoice.pdf", filtered[0].name)
    }

    @Test
    fun testFilterWithSearchQuery() {
        val filtered = filterFiles(sampleFiles, ManagedFileType.ALL, "invoice")
        assertEquals(1, filtered.size)
        assertEquals("invoice.pdf", filtered[0].name)
    }

    @Test
    fun testFilterTypeAndSearchCombined() {
        val filtered = filterFiles(sampleFiles, ManagedFileType.PDF, "report")
        assertTrue(filtered.isEmpty())

        val matched = filterFiles(sampleFiles, ManagedFileType.WORD, "report")
        assertEquals(1, matched.size)
        assertEquals("report.doc", matched[0].name)
    }

    @Test
    fun testStateRestorationRoundTripForFilterType() {
        for (type in ManagedFileType.values()) {
            val serialized = type.name
            val restored = runCatching { ManagedFileType.valueOf(serialized) }.getOrDefault(ManagedFileType.ALL)
            assertEquals(type, restored)
        }

        // Test fallback on invalid string
        val fallback = runCatching { ManagedFileType.valueOf("INVALID_TYPE") }.getOrDefault(ManagedFileType.ALL)
        assertEquals(ManagedFileType.ALL, fallback)
    }
}
