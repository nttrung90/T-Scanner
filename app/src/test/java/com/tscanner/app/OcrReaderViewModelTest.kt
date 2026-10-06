package com.tscanner.app

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import com.tscanner.app.ocr.data.OcrDocumentRepository
import com.tscanner.app.ocr.model.*
import com.tscanner.app.ui.ocr.reader.OcrReaderTab
import com.tscanner.app.ui.ocr.reader.OcrReaderViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for OcrReaderViewModel:
 * - Bounded memory cache (max 3 bitmaps)
 * - Tab switching
 * - Page navigation and clamping
 * - Zoom/pan persistence via SavedStateHandle
 * - Missing image detection
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S09).
 */
class OcrReaderViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class TestApplication(private val baseDir: File) : Application() {
        override fun getFilesDir(): File = baseDir
    }

    private lateinit var app: Application
    private lateinit var baseDir: File
    private lateinit var repository: OcrDocumentRepository
    private val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("ocr_vm_test")
        repository = OcrDocumentRepository(baseDir)
        app = TestApplication(baseDir)
    }

    private fun createStubBitmap(): Bitmap {
        return try {
            val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null) as sun.misc.Unsafe
            unsafe.allocateInstance(Bitmap::class.java) as Bitmap
        } catch (_: Throwable) {
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
    }

    @Test
    fun testBoundedMemoryCacheEnforcesMaxEntries() {
        val handle = SavedStateHandle()
        val vm = OcrReaderViewModel(app, handle, repository, testScope)

        val bmp1 = createStubBitmap()
        val bmp2 = createStubBitmap()
        val bmp3 = createStubBitmap()
        val bmp4 = createStubBitmap()

        vm.bitmapCache.put("page_1.jpg", bmp1)
        vm.bitmapCache.put("page_2.jpg", bmp2)
        vm.bitmapCache.put("page_3.jpg", bmp3)

        assertEquals(3, vm.bitmapCache.size())
        // Accessing page_1 moves it to most recently used, making page_2 the LRU entry
        assertNotNull(vm.bitmapCache.get("page_1.jpg"))

        // Putting 4th bitmap must evict the least recently used entry (page_2.jpg)
        vm.bitmapCache.put("page_4.jpg", bmp4)
        assertEquals(3, vm.bitmapCache.size())
        assertNull("LRU entry (page_2) must be evicted", vm.bitmapCache.get("page_2.jpg"))
        assertNotNull(vm.bitmapCache.get("page_1.jpg"))
        assertNotNull(vm.bitmapCache.get("page_3.jpg"))
        assertNotNull(vm.bitmapCache.get("page_4.jpg"))
    }

    @Test
    fun testBoundedMemoryCacheByteBudgetEviction() {
        var evictedKey: String? = null
        val cache = com.tscanner.app.ui.ocr.reader.BoundedMemoryCache<String, String>(
            maxEntries = 10,
            maxSizeBytes = 1000L,
            sizeOf = { it.length.toLong() },
            onEntryEvicted = { k, _ -> evictedKey = k }
        )

        // Put 400 bytes
        val str1 = "a".repeat(400)
        cache.put("key1", str1)
        assertEquals(400L, cache.currentBytes())

        // Put 400 bytes (total 800 <= 1000)
        val str2 = "b".repeat(400)
        cache.put("key2", str2)
        assertEquals(800L, cache.currentBytes())
        assertEquals(2, cache.size())

        // Put 400 bytes (total 1200 > 1000 -> evicts eldest key1)
        val str3 = "c".repeat(400)
        cache.put("key3", str3)
        assertEquals(2, cache.size())
        assertEquals("key1", evictedKey)
        assertNull("key1 must be evicted because 1200 > 1000 maxSizeBytes", cache.get("key1"))
        assertNotNull(cache.get("key2"))
        assertNotNull(cache.get("key3"))
        assertEquals(800L, cache.currentBytes())
    }

    @Test
    fun testDefaultTabIsScanAndSwitchingWorks() {
        val handle = SavedStateHandle()
        val vm = OcrReaderViewModel(app, handle, repository, testScope)

        // Default tab is SCAN
        assertEquals(OcrReaderTab.SCAN, vm.currentTab.value)

        // Switch to TEXT
        vm.selectTab(OcrReaderTab.TEXT)
        assertEquals(OcrReaderTab.TEXT, vm.currentTab.value)
        assertEquals(OcrReaderTab.TEXT.name, handle.get<String>("saved_reader_tab"))

        // Switch to TABLE
        vm.selectTab(OcrReaderTab.TABLE)
        assertEquals(OcrReaderTab.TABLE, vm.currentTab.value)
        assertEquals(OcrReaderTab.TABLE.name, handle.get<String>("saved_reader_tab"))
    }

    @Test
    fun testPageNavigationAndClamping() {
        val handle = SavedStateHandle()
        val vm = OcrReaderViewModel(app, handle, repository, testScope)

        val sampleDoc = OcrDocument(
            id = "doc_nav_test",
            pages = listOf(
                OcrPage("p1", 1, OcrPageStatus.SUCCESS),
                OcrPage("p2", 2, OcrPageStatus.SUCCESS),
                OcrPage("p3", 3, OcrPageStatus.SUCCESS)
            )
        )
        vm.initialize("doc_nav_test", sampleDoc)

        assertEquals(1, vm.currentPageIndex.value)

        vm.nextPage()
        assertEquals(2, vm.currentPageIndex.value)

        vm.nextPage()
        assertEquals(3, vm.currentPageIndex.value)

        // Clamps at max page 3
        vm.nextPage()
        assertEquals(3, vm.currentPageIndex.value)

        vm.previousPage()
        assertEquals(2, vm.currentPageIndex.value)

        vm.previousPage()
        assertEquals(1, vm.currentPageIndex.value)

        // Clamps at min page 1
        vm.previousPage()
        assertEquals(1, vm.currentPageIndex.value)
    }

    @Test
    fun testZoomAndPanPersistenceAcrossSavedStateHandle() {
        val handle1 = SavedStateHandle()
        val vm1 = OcrReaderViewModel(app, handle1, repository, testScope)

        vm1.setZoomAndPan(2.5f, 150f, -80f)
        assertEquals(2.5f, vm1.zoomScale.value, 0.001f)
        assertEquals(150f, vm1.panX.value, 0.001f)
        assertEquals(-80f, vm1.panY.value, 0.001f)

        // Simulate activity recreation by passing same SavedStateHandle to new ViewModel instance
        val vm2 = OcrReaderViewModel(app, handle1, repository, testScope)
        assertEquals(2.5f, vm2.zoomScale.value, 0.001f)
        assertEquals(150f, vm2.panX.value, 0.001f)
        assertEquals(-80f, vm2.panY.value, 0.001f)
    }

    @Test
    fun testMissingImageSetsFlagProperly() = runBlocking {
        val handle = SavedStateHandle()
        val vm = OcrReaderViewModel(app, handle, repository, testScope)

        val docWithoutImage = OcrDocument(
            id = "doc_no_img",
            pages = listOf(
                OcrPage("p1", 1, OcrPageStatus.SUCCESS, imageInfo = null)
            )
        )
        vm.initialize("doc_no_img", docWithoutImage)

        // Must indicate image is missing
        assertTrue(vm.isImageMissing.value)
        assertNull(vm.currentPageBitmap.value)
    }
}
