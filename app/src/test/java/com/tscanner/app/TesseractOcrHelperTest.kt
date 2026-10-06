package com.tscanner.app

import android.content.Context
import android.graphics.Bitmap
import com.tscanner.app.utils.EngineRunResult
import com.tscanner.app.utils.TesseractOcrHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class TesseractOcrHelperTest {

    private class FakeTessApiDriver(
        val supportedModels: Set<String> = emptySet(),
        val returnedText: String = "",
        val throwOnInit: Throwable? = null,
        val throwOnGetText: Throwable? = null,
        val returnedPageDocument: com.tscanner.app.ocr.model.OcrPage? = null
    ) : TesseractOcrHelper.TessApiDriver {
        val attemptedInits = mutableListOf<String>()
        var recycled = false
        var psmSet = -1
        var imageSet = false

        override fun init(dataPath: String, language: String): Boolean {
            attemptedInits.add(language)
            throwOnInit?.let { throw it }
            return supportedModels.contains(language)
        }

        override fun setPageSegMode(mode: Int) {
            psmSet = mode
        }

        override fun setImage(bitmap: Bitmap) {
            imageSet = true
        }

        override fun getUTF8Text(): String? {
            throwOnGetText?.let { throw it }
            return returnedText
        }

        override fun getPageDocument(bitmapWidth: Int, bitmapHeight: Int, language: String): com.tscanner.app.ocr.model.OcrPage? {
            return returnedPageDocument
        }

        override fun recycle() {
            recycled = true
        }
    }

    private class DummyContext : android.content.ContextWrapper(null)

    @Before
    fun setUp() {
        TesseractOcrHelper.dataPathOverride = "/dummy/tessdata/root"
    }

    @After
    fun tearDown() {
        TesseractOcrHelper.dataPathOverride = null
        TesseractOcrHelper.tessDriverFactory = { TesseractOcrHelper.RealTessApiDriver() }
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
    fun testResolveTessModel_strictMapping() {
        assertEquals("vie", TesseractOcrHelper.resolveTessModel("vi"))
        assertEquals("vie", TesseractOcrHelper.resolveTessModel("vie"))
        assertEquals("eng", TesseractOcrHelper.resolveTessModel("en"))
        assertEquals("eng", TesseractOcrHelper.resolveTessModel("eng"))
        assertEquals("vie+eng", TesseractOcrHelper.resolveTessModel("vie+eng"))
        assertEquals("vie+eng", TesseractOcrHelper.resolveTessModel("vi+en"))
        assertEquals("vie+eng", TesseractOcrHelper.resolveTessModel("auto"))
        assertEquals("spa", TesseractOcrHelper.resolveTessModel("spa"))
    }

    @Test
    fun testVieInitFailure_doesNotFallBackToEng() = runBlocking {
        // Giả lập: eng có thể init, nhưng vie thì KHÔNG có
        val fakeDriver = FakeTessApiDriver(supportedModels = setOf("eng"), returnedText = "English fallback text")
        TesseractOcrHelper.tessDriverFactory = { fakeDriver }

        val result = TesseractOcrHelper.recognizeTextFromBitmapStructured(
            context = DummyContext(),
            bitmap = createStubBitmap(),
            language = "vi"
        )

        // 1. Tuyệt đối KHÔNG được thử khởi tạo eng
        assertEquals("Chỉ được thử duy nhất model 'vie'", listOf("vie"), fakeDriver.attemptedInits)
        assertFalse("Không được gọi init eng", fakeDriver.attemptedInits.contains("eng"))

        // 2. Không được trả về Success từ model Anh
        assertTrue("Phải trả về ModelUnavailable khi vie thiếu", result is EngineRunResult.ModelUnavailable)
        assertTrue(fakeDriver.recycled)
    }

    @Test
    fun testEngInitFailure_doesNotFallBackToVie() = runBlocking {
        // Giả lập: vie có thể init, nhưng eng thì KHÔNG có
        val fakeDriver = FakeTessApiDriver(supportedModels = setOf("vie"), returnedText = "Vietnamese text")
        TesseractOcrHelper.tessDriverFactory = { fakeDriver }

        val result = TesseractOcrHelper.recognizeTextFromBitmapStructured(
            context = DummyContext(),
            bitmap = createStubBitmap(),
            language = "en"
        )

        // 1. Tuyệt đối KHÔNG được thử khởi tạo vie
        assertEquals("Chỉ được thử duy nhất model 'eng'", listOf("eng"), fakeDriver.attemptedInits)
        assertFalse("Không được gọi init vie", fakeDriver.attemptedInits.contains("vie"))

        // 2. Không được trả về Success từ model Việt
        assertTrue("Phải trả về ModelUnavailable khi eng thiếu", result is EngineRunResult.ModelUnavailable)
        assertTrue(fakeDriver.recycled)
    }

    @Test
    fun testRequestedModelSuccess_returnsSuccessWithCorrectText() = runBlocking {
        val fakeDriver = FakeTessApiDriver(
            supportedModels = setOf("vie"),
            returnedText = "Cộng hòa Xã hội Chủ nghĩa Việt Nam"
        )
        TesseractOcrHelper.tessDriverFactory = { fakeDriver }

        val result = TesseractOcrHelper.recognizeTextFromBitmapStructured(
            context = DummyContext(),
            bitmap = createStubBitmap(),
            language = "vie"
        )

        assertTrue(result is EngineRunResult.Success)
        assertEquals("Cộng hòa Xã hội Chủ nghĩa Việt Nam", (result as EngineRunResult.Success).text)
        assertEquals(listOf("vie"), fakeDriver.attemptedInits)
        assertTrue(fakeDriver.recycled)
    }

    @Test
    fun testInitSuccess_emptyText_returnsNoText() = runBlocking {
        val fakeDriver = FakeTessApiDriver(
            supportedModels = setOf("vie"),
            returnedText = "   \n  "
        )
        TesseractOcrHelper.tessDriverFactory = { fakeDriver }

        val result = TesseractOcrHelper.recognizeTextFromBitmapStructured(
            context = DummyContext(),
            bitmap = createStubBitmap(),
            language = "vie"
        )

        assertTrue(result is EngineRunResult.NoText)
        assertTrue(fakeDriver.recycled)
    }

    @Test
    fun testInitThrowsException_returnsModelUnavailable_andCleansUp() = runBlocking {
        val fakeDriver = FakeTessApiDriver(
            throwOnInit = RuntimeException("Corrupted traineddata file")
        )
        TesseractOcrHelper.tessDriverFactory = { fakeDriver }

        val result = TesseractOcrHelper.recognizeTextFromBitmapStructured(
            context = DummyContext(),
            bitmap = createStubBitmap(),
            language = "vie"
        )

        assertTrue(result is EngineRunResult.ModelUnavailable)
        assertTrue((result as EngineRunResult.ModelUnavailable).reason.contains("Corrupted traineddata file"))
        assertTrue(fakeDriver.recycled)
    }

    @Test
    fun testCancellationDuringProcessing_rethrownAndRecycled() = runBlocking {
        val fakeDriver = FakeTessApiDriver(
            throwOnInit = CancellationException("User cancelled OCR operation")
        )
        TesseractOcrHelper.tessDriverFactory = { fakeDriver }

        try {
            TesseractOcrHelper.recognizeTextFromBitmapStructured(
                context = DummyContext(),
                bitmap = createStubBitmap(),
                language = "vie"
            )
            fail("CancellationException phải được rethrow")
        } catch (c: CancellationException) {
            assertEquals("User cancelled OCR operation", c.message)
            assertTrue(fakeDriver.recycled)
        }
    }

    @Test
    fun testViePlusEng_doesNotDowngradeSilently() = runBlocking {
        // Nếu caller yêu cầu rõ ràng "vie+eng", không được tự tiện downgrade về "vie" hay "eng"
        val fakeDriver = FakeTessApiDriver(
            supportedModels = setOf("vie", "eng") // chỉ có vie và eng riêng rẽ, không có combined
        )
        TesseractOcrHelper.tessDriverFactory = { fakeDriver }

        val result = TesseractOcrHelper.recognizeTextFromBitmapStructured(
            context = DummyContext(),
            bitmap = createStubBitmap(),
            language = "vie+eng"
        )

        assertEquals(listOf("vie+eng"), fakeDriver.attemptedInits)
        assertTrue(result is EngineRunResult.ModelUnavailable)
        assertTrue(fakeDriver.recycled)
    }

    @Test
    fun testVietnameseUnicodeAndLineGeometryPreserved() = runBlocking {
        val viText = "CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM\nĐộc lập - Tự do - Hạnh phúc"
        val mockPage = com.tscanner.app.ocr.model.OcrPage(
            pageId = "page_1_tess",
            pageIndex = 1,
            status = com.tscanner.app.ocr.model.OcrPageStatus.SUCCESS,
            engineId = "tesseract",
            sourceLanguage = "vie",
            sourceBlocks = listOf(
                com.tscanner.app.ocr.model.OcrBlock(
                    blockId = "blk_1_0",
                    lines = listOf(
                        com.tscanner.app.ocr.model.OcrLine(
                            lineId = "line_1_0",
                            text = "CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM",
                            polygon = com.tscanner.app.ocr.model.OcrPolygon(
                                listOf(
                                    com.tscanner.app.ocr.model.OcrPoint(0.1f, 0.1f),
                                    com.tscanner.app.ocr.model.OcrPoint(0.9f, 0.1f),
                                    com.tscanner.app.ocr.model.OcrPoint(0.9f, 0.15f),
                                    com.tscanner.app.ocr.model.OcrPoint(0.1f, 0.15f)
                                )
                            ),
                            tokens = listOf(
                                com.tscanner.app.ocr.model.OcrToken("tok_1_0", "CỘNG"),
                                com.tscanner.app.ocr.model.OcrToken("tok_1_1", "HÒA")
                            )
                        )
                    )
                )
            )
        )

        val fakeDriver = FakeTessApiDriver(
            supportedModels = setOf("vie"),
            returnedText = viText,
            returnedPageDocument = mockPage
        )
        TesseractOcrHelper.tessDriverFactory = { fakeDriver }

        val result = TesseractOcrHelper.recognizeTextFromBitmapStructured(
            context = DummyContext(),
            bitmap = createStubBitmap(),
            language = "vie"
        )

        assertTrue(result is EngineRunResult.Success)
        val success = result as EngineRunResult.Success
        assertEquals(viText, success.text)

        val page = success.pageDocument
        assertNotNull("Must return OcrPage", page)
        assertEquals("vie", page!!.sourceLanguage)
        assertEquals(1, page.sourceBlocks.size)
        assertEquals("CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM", page.sourceBlocks[0].lines[0].text)
        assertNotNull(page.sourceBlocks[0].lines[0].polygon)
        assertEquals(2, page.sourceBlocks[0].lines[0].tokens.size)
        assertTrue(fakeDriver.recycled)
    }

    @Test
    fun testFallbackTextOnlyWhenGeometryUnavailable() = runBlocking {
        val viText = "Văn bản thuần không có hình học iterator"
        val fakeDriver = FakeTessApiDriver(
            supportedModels = setOf("vie"),
            returnedText = viText,
            returnedPageDocument = null // Geometry unavailable
        )
        TesseractOcrHelper.tessDriverFactory = { fakeDriver }

        val result = TesseractOcrHelper.recognizeTextFromBitmapStructured(
            context = DummyContext(),
            bitmap = createStubBitmap(),
            language = "vie"
        )

        assertTrue(result is EngineRunResult.Success)
        val success = result as EngineRunResult.Success
        assertEquals(viText, success.text)

        val page = success.pageDocument
        assertNotNull(page)
        assertEquals(com.tscanner.app.ocr.model.OcrPageStatus.SUCCESS, page!!.status)
        assertEquals(1, page.sourceBlocks.size)
        // In fallback mode, line polygon is null (geometryUnavailable)
        assertNull(page.sourceBlocks[0].lines[0].polygon)
        assertTrue(page.sourceBlocks[0].lines[0].tokens.isEmpty())
        assertTrue(fakeDriver.recycled)
    }
}
