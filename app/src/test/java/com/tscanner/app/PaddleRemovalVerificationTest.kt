package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import com.tscanner.app.ocr.model.OcrBlock
import com.tscanner.app.ocr.model.OcrDocument
import com.tscanner.app.ocr.model.OcrEditedContent
import com.tscanner.app.ocr.model.OcrImageInfo
import com.tscanner.app.ocr.model.OcrLine
import com.tscanner.app.ocr.model.OcrPage
import com.tscanner.app.ocr.model.OcrPageStatus
import com.tscanner.app.ocr.model.OcrParagraph
import com.tscanner.app.ocr.model.OcrPoint
import com.tscanner.app.ocr.model.OcrPolygon
import com.tscanner.app.ocr.model.OcrRect
import com.tscanner.app.ocr.model.OcrTable
import com.tscanner.app.ocr.model.OcrTableCell
import com.tscanner.app.ui.ocr.reader.OcrSelectionController
import com.tscanner.app.utils.EngineRunResult
import com.tscanner.app.utils.MultiPageOcrAggregator
import com.tscanner.app.utils.MultiPageOcrResult
import com.tscanner.app.utils.OcrFailureCode
import com.tscanner.app.utils.OcrLanguageMode
import com.tscanner.app.utils.OcrModelUnavailableType
import com.tscanner.app.utils.OcrRequest
import com.tscanner.app.utils.OcrResult
import com.tscanner.app.utils.TextRecognitionHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P06: Bộ kiểm thử hồi quy tích hợp, tương thích dữ liệu và quy trình hoàn chỉnh sau khi loại bỏ Paddle.
 * Kiểm tra tài liệu JSON legacy có engineId="paddle", dòng không có tokens, bảng biểu, chỉnh sửa,
 * quy trình chọn ngôn ngữ tiếng Trung và bảo vệ không tạo trang trắng khi thiếu model.
 */
class PaddleRemovalVerificationTest {

    private lateinit var dummyContext: DummyContext
    private lateinit var stubBitmap: Bitmap

    @Before
    fun setUp() {
        dummyContext = DummyContext()
        stubBitmap = createStubBitmap()
        TextRecognitionHelper.mlKitChineseRunner = null
    }

    @After
    fun tearDown() {
        TextRecognitionHelper.mlKitChineseRunner = null
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

    // -------------------------------------------------------------------------
    // 1. Dữ liệu tài liệu Legacy có engineId="paddle", line-only, bảng biểu, edits
    // -------------------------------------------------------------------------
    @Test
    fun testLegacyPaddleDocument_lifecyclePreservesPaddleEngineIdAndData() {
        val legacyDoc = OcrDocument(
            id = "doc_legacy_paddle_001",
            title = "Document Legacy Paddle",
            createdAt = 1727000000000L,
            updatedAt = 1727000500000L,
            pages = listOf(
                OcrPage(
                    pageId = "page_paddle_1",
                    pageIndex = 1,
                    status = OcrPageStatus.SUCCESS,
                    imageInfo = OcrImageInfo(
                        localUri = "/storage/emulated/0/TScanner/doc_001_p1.jpg",
                        widthPx = 1080,
                        heightPx = 1920
                    ),
                    engineId = "paddle", // Metadata lịch sử quan trọng
                    sourceLanguage = "zh-Hans",
                    sourceBlocks = listOf(
                        OcrBlock(
                            blockId = "block_1",
                            boundingBox = OcrRect(50f, 50f, 950f, 250f),
                            lines = listOf(
                                OcrLine(
                                    lineId = "line_p1_1",
                                    text = "第一行历史文本",
                                    boundingBox = OcrRect(50f, 50f, 950f, 130f),
                                    polygon = OcrPolygon(listOf(
                                        OcrPoint(50f, 50f),
                                        OcrPoint(950f, 50f),
                                        OcrPoint(950f, 130f),
                                        OcrPoint(50f, 130f)
                                    )),
                                    tokens = emptyList() // Line-only: không tạo fake word tokens
                                ),
                                OcrLine(
                                    lineId = "line_p1_2",
                                    text = "第二行原始文本",
                                    boundingBox = OcrRect(50f, 150f, 950f, 230f),
                                    tokens = emptyList()
                                )
                            )
                        )
                    ),
                    tables = listOf(
                        OcrTable(
                            tableId = "tbl_1",
                            rowCount = 2,
                            columnCount = 2,
                            boundingBox = OcrRect(50f, 300f, 850f, 700f),
                            cells = listOf(
                                OcrTableCell(cellId = "c_0_0", rowIndex = 0, colIndex = 0, rawText = "项目"),
                                OcrTableCell(cellId = "c_0_1", rowIndex = 0, colIndex = 1, rawText = "数值"),
                                OcrTableCell(cellId = "c_1_0", rowIndex = 1, colIndex = 0, rawText = "A1"),
                                OcrTableCell(cellId = "c_1_1", rowIndex = 1, colIndex = 1, rawText = "100")
                            )
                        )
                    ),
                    editedContent = OcrEditedContent(
                        text = "第一行历史文本\n第二行编辑文本",
                        paragraphs = listOf(
                            OcrParagraph(
                                paragraphId = "p_1",
                                sourceAnchorLineId = "line_p1_1",
                                text = "第一行历史文本"
                            ),
                            OcrParagraph(
                                paragraphId = "p_2",
                                sourceAnchorLineId = "line_p1_2",
                                text = "第二行编辑文本"
                            )
                        )
                    )
                )
            )
        )

        // 1. Serialization sang JSON String
        val jsonString = legacyDoc.toJsonString()
        assertTrue("JSON string phải chứa '\"engineId\":\"paddle\"'", jsonString.contains("\"engineId\":\"paddle\""))

        // 2. Deserialization từ JSON String
        val parsedDoc = OcrDocument.fromJsonString(jsonString)
        assertEquals("doc_legacy_paddle_001", parsedDoc.id)
        assertEquals(1, parsedDoc.pages.size)

        val parsedPage = parsedDoc.pages[0]
        assertEquals("engineId phải giữ nguyên là 'paddle'", "paddle", parsedPage.engineId)
        assertEquals("zh-Hans", parsedPage.sourceLanguage)
        assertEquals(1, parsedPage.sourceBlocks.size)
        assertEquals(2, parsedPage.sourceBlocks[0].lines.size)

        // Dòng 1: line-only tokens rỗng
        val line1 = parsedPage.sourceBlocks[0].lines[0]
        assertEquals("第一行历史文本", line1.text)
        assertTrue("Tokens của line-only phải rỗng", line1.tokens.isEmpty())
        assertNotNull(line1.polygon)
        assertEquals(4, line1.polygon!!.points.size)

        // Bảng biểu
        assertEquals(1, parsedPage.tables.size)
        assertEquals(2, parsedPage.tables[0].rowCount)
        assertEquals("项目", parsedPage.tables[0].cells[0].rawText)

        // Dữ liệu resolvedText / editedContent
        assertEquals("第一行历史文本\n第二行编辑文本", parsedPage.resolvedText)
        assertEquals(2, parsedPage.editedContent?.paragraphs?.size)

        // 3. Thực hiện chỉnh sửa thêm và serialize lại
        val updatedPage = parsedPage.copy(
            editedContent = OcrEditedContent(text = "第一行历史文本\n第二行编辑文本\n新增第三行")
        )
        val updatedDoc = parsedDoc.copy(pages = listOf(updatedPage), updatedAt = System.currentTimeMillis())
        val reSerializedJson = updatedDoc.toJsonString()

        // Sau khi chỉnh sửa và lưu lại, engineId lịch sử vẫn là "paddle"
        assertTrue("Sau khi sửa đổi, engineId vẫn là 'paddle'", reSerializedJson.contains("\"engineId\":\"paddle\""))
    }

    // -------------------------------------------------------------------------
    // 2. Preference legacy "paddle" -> migration Auto -> MANUAL zh-Hant -> ML Kit Chinese
    // -------------------------------------------------------------------------
    @Test
    fun testCompleteUserFlow_legacyPreferenceToManualZhHant_runsMlKitChinese() = runBlocking {
        // 1. Preference cũ chứa "paddle"
        val prefs = dummyContext.getSharedPreferences("tscanner_ocr_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("key_preferred_ocr_engine", "paddle").apply()

        // 2. Đọc preference: tự chuẩn hóa sang "auto"
        val effectiveEngine = TextRecognitionHelper.getPreferredEngine(prefs)
        assertEquals(TextRecognitionHelper.ENGINE_MODE_AUTO, effectiveEngine)

        // 3. Giả lập caller gửi request với engineMode="paddle" và languageTag="zh-Hant"
        var runnerCalls = 0
        TextRecognitionHelper.mlKitChineseRunner = {
            runnerCalls++
            val mockPage = OcrPage(
                pageId = "p_zh_hant",
                pageIndex = 1,
                status = OcrPageStatus.SUCCESS,
                engineId = "mlkit_chinese",
                sourceLanguage = "zh-Hant",
                sourceBlocks = listOf(
                    OcrBlock(
                        blockId = "b1",
                        lines = listOf(
                            OcrLine(lineId = "l1", text = "繁體中文成功識別")
                        )
                    )
                )
            )
            EngineRunResult.Success("繁體中文成功識別", mockPage)
        }

        val legacyRequest = OcrRequest(
            languageMode = OcrLanguageMode.MANUAL,
            languageTag = "zh-Hant",
            engineMode = "paddle" // Caller cũ truyền "paddle"
        )

        val result = TextRecognitionHelper.recognizeInternalStructured(dummyContext, stubBitmap, legacyRequest)

        assertEquals("ML Kit Chinese runner phải được gọi chính xác 1 lần", 1, runnerCalls)
        assertTrue("Kết quả phải là OcrResult.Success", result is OcrResult.Success)
        val success = result as OcrResult.Success
        assertEquals("繁體中文成功識別", success.text)
        assertEquals("mlkit_chinese", success.engineId)
        assertEquals("zh-Hant", success.documentLanguage)
        assertFalse("fallbackUsed phải là false vì đây là primary engine cho tiếng Trung", success.fallbackUsed)
        assertNotNull("pageDocument phải tồn tại", success.pageDocument)
        assertEquals("p_zh_hant", success.pageDocument?.pageId)
    }

    // -------------------------------------------------------------------------
    // 3. Thiếu Model Chinese trong batch nhiều trang -> Không tạo trang trắng
    // -------------------------------------------------------------------------
    @Test
    fun testMultiPageBatch_onePageModelUnavailable_failsBatchSafelyWithoutWhitePage() = runBlocking {
        // Trang 1 thành công
        val page1Result = OcrResult.Success(
            text = "Trang 1 chữ Trung thành công",
            engineId = "mlkit_chinese",
            documentLanguage = "zh-Hans"
        )

        // Trang 2 bị lỗi đang tải model
        val page2Result = OcrResult.ModelUnavailable(
            reason = "Waiting for the text recognition module to be downloaded",
            languageTag = "zh-Hans",
            type = OcrModelUnavailableType.DOWNLOADING
        )

        // Trang 3 thành công
        val page3Result = OcrResult.Success(
            text = "Trang 3 chữ Trung thành công",
            engineId = "mlkit_chinese",
            documentLanguage = "zh-Hans"
        )

        val batch = listOf(page1Result, page2Result, page3Result)
        val aggregated = MultiPageOcrAggregator.aggregate(batch)

        // R04/R08: TUYỆT ĐỐI không được đánh dấu batch là Success đầy đủ hoặc bỏ qua trang 2!
        assertTrue("Khi có trang bị lỗi model, batch phải trả về PageError", aggregated is MultiPageOcrResult.PageError)
        val pageError = aggregated as MultiPageOcrResult.PageError
        assertEquals("Trang lỗi đầu tiên phải là trang 2", 2, pageError.failedPageNumber)
        assertEquals(3, pageError.totalPages)
        assertTrue("errorResult phải là ModelUnavailable", pageError.errorResult is OcrResult.ModelUnavailable)
        val unavailable = pageError.errorResult as OcrResult.ModelUnavailable
        assertTrue("Lỗi phải chứa thông báo model unavailable", unavailable.reason.contains("Waiting for the text recognition module"))
    }

    // -------------------------------------------------------------------------
    // 4. Lỗi nhận diện không được ghi đè kết quả cũ bằng nội dung rỗng
    // -------------------------------------------------------------------------
    @Test
    fun testChineseInferenceFailure_doesNotReturnBlankContentOrNoText() = runBlocking {
        TextRecognitionHelper.mlKitChineseRunner = {
            EngineRunResult.Failure(
                message = "ML Kit inference timeout",
                code = OcrFailureCode.EXECUTION_FAILED
            )
        }

        val request = OcrRequest(
            languageMode = OcrLanguageMode.MANUAL,
            languageTag = "zh-Hans",
            engineMode = TextRecognitionHelper.ENGINE_MODE_AUTO
        )

        val result = TextRecognitionHelper.recognizeInternalStructured(dummyContext, stubBitmap, request)

        assertTrue("Kết quả phải là Failure khi gặp lỗi suy luận", result is OcrResult.Failure)
        assertFalse("TUYỆT ĐỐI không được coi lỗi kỹ thuật là trang trắng NoText", result is OcrResult.NoText)
        val failure = result as OcrResult.Failure
        assertEquals("ML Kit inference timeout", failure.error)
        assertNull("textOrNull của Failure phải là null, không phải chuỗi rỗng", result.textOrNull)
    }

    // -------------------------------------------------------------------------
    // 5. Line-only selection fallback hoạt động tốt cho tài liệu Paddle cũ
    // -------------------------------------------------------------------------
    @Test
    fun testLineOnlySelectionFallback_supportedForPaddleLegacyDocuments() {
        val line = OcrLine(
            lineId = "line_paddle_old_1",
            text = "历史扫描行内容",
            polygon = OcrPolygon(
                listOf(
                    OcrPoint(0.1f, 0.3f),
                    OcrPoint(0.9f, 0.3f),
                    OcrPoint(0.9f, 0.4f),
                    OcrPoint(0.1f, 0.4f)
                )
            ),
            tokens = emptyList() // Line-only: no fake word boxes
        )
        val block = OcrBlock(blockId = "b1", lines = listOf(line))
        val page = OcrPage(
            pageId = "p1",
            pageIndex = 1,
            status = OcrPageStatus.SUCCESS,
            engineId = "paddle",
            sourceLanguage = "zh-Hans",
            sourceBlocks = listOf(block)
        )

        val controller = OcrSelectionController()
        controller.setPage(page)

        // Nhấn vào tọa độ của dòng (0.5, 0.35)
        val hit = controller.onSingleTap(0.5f, 0.35f)
        assertTrue("Hit-test trên dòng line-only phải thành công", hit)
        assertTrue("Dòng paddle lịch sử phải nằm trong selectedLineIds", controller.getSelectedLineIds().contains("line_paddle_old_1"))
        assertEquals("历史扫描行内容", controller.getSelectedText())

        // Tài liệu line-only không có tokens, nên selectedTokenIds phải rỗng
        assertTrue(controller.getSelectedTokenIds().isEmpty())
    }

    // -------------------------------------------------------------------------
    private class DummyContext(private val prefs: SharedPreferences = FakeSharedPreferences()) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? Set<*>)?.mapNotNull { it?.toString() }?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removes = mutableSetOf<String>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) {
                    if (value != null) pending[key] = value else removes.add(key)
                }
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) {
                    if (values != null) pending[key] = values else removes.add(key)
                }
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) removes.add(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearFlag = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearFlag) {
                    prefs.map.clear()
                    clearFlag = false
                }
                removes.forEach { prefs.map.remove(it) }
                removes.clear()
                pending.forEach { (k, v) ->
                    if (v != null) prefs.map[k] = v else prefs.map.remove(k)
                }
                pending.clear()
            }
        }
    }
}
