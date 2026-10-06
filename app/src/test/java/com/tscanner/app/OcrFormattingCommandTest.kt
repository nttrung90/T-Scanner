package com.tscanner.app

import com.tscanner.app.ocr.edit.*
import com.tscanner.app.ocr.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for formatting commands (bold, italic, font size, paragraph alignment).
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S13).
 */
class OcrFormattingCommandTest {

    private fun createDocumentWithParagraph(text: String): OcrDocument {
        return OcrDocument(
            id = "doc_format_test",
            pages = listOf(
                OcrPage(
                    pageId = "p1",
                    pageIndex = 1,
                    status = OcrPageStatus.SUCCESS,
                    sourceBlocks = listOf(OcrBlock("b1", lines = listOf(OcrLine("l1", text)))),
                    editedContent = OcrEditedContent(
                        text = text,
                        paragraphs = listOf(
                            OcrParagraph(
                                paragraphId = "para_1",
                                text = text,
                                alignment = OcrTextAlignment.LEFT,
                                runs = listOf(OcrTextRun(text = text, isBold = false, isItalic = false, fontSizePt = 12.0f))
                            )
                        )
                    )
                )
            )
        )
    }

    @Test
    fun testBoldAndItalicToggleWithUndoRedo() {
        val initialDoc = createDocumentWithParagraph("HỢP ĐỒNG KINH TẾ")
        val history = OcrEditHistory(initialDoc)

        val oldRun = initialDoc.pages[0].editedContent!!.paragraphs[0].runs[0]
        val boldRun = oldRun.copy(isBold = true)
        val boldItalicRun = boldRun.copy(isItalic = true)

        // 1. Apply Bold
        val cmdBold = OcrEditCommand.UpdateRunStyle(
            pageIndex = 1,
            paragraphIndex = 0,
            runIndex = 0,
            oldRun = oldRun,
            newRun = boldRun
        )
        history.execute(cmdBold)
        val runAfterBold = history.currentDocument.pages[0].editedContent!!.paragraphs[0].runs[0]
        assertTrue(runAfterBold.isBold)
        assertFalse(runAfterBold.isItalic)

        // 2. Apply Italic
        val cmdItalic = OcrEditCommand.UpdateRunStyle(
            pageIndex = 1,
            paragraphIndex = 0,
            runIndex = 0,
            oldRun = boldRun,
            newRun = boldItalicRun
        )
        history.execute(cmdItalic)
        val runAfterItalic = history.currentDocument.pages[0].editedContent!!.paragraphs[0].runs[0]
        assertTrue(runAfterItalic.isBold)
        assertTrue(runAfterItalic.isItalic)

        // 3. Undo Italic -> restores bold only
        val undo1 = history.undo()
        val runUndo1 = undo1!!.pages[0].editedContent!!.paragraphs[0].runs[0]
        assertTrue(runUndo1.isBold)
        assertFalse(runUndo1.isItalic)

        // 4. Undo Bold -> restores original unformatted
        val undo2 = history.undo()
        val runUndo2 = undo2!!.pages[0].editedContent!!.paragraphs[0].runs[0]
        assertFalse(runUndo2.isBold)
        assertFalse(runUndo2.isItalic)

        // 5. Redo Bold
        val redo1 = history.redo()
        val runRedo1 = redo1!!.pages[0].editedContent!!.paragraphs[0].runs[0]
        assertTrue(runRedo1.isBold)

        // 6. Redo Italic
        val redo2 = history.redo()
        val runRedo2 = redo2!!.pages[0].editedContent!!.paragraphs[0].runs[0]
        assertTrue(runRedo2.isBold)
        assertTrue(runRedo2.isItalic)
    }

    @Test
    fun testParagraphAlignmentCommandUndoRedo() {
        val initialDoc = createDocumentWithParagraph("Tiêu đề căn giữa")
        val history = OcrEditHistory(initialDoc)

        assertEquals(OcrTextAlignment.LEFT, history.currentDocument.pages[0].editedContent!!.paragraphs[0].alignment)

        // Change alignment to CENTER
        val cmdCenter = OcrEditCommand.SetParagraphAlignment(
            pageIndex = 1,
            paragraphIndex = 0,
            oldAlignment = OcrTextAlignment.LEFT,
            newAlignment = OcrTextAlignment.CENTER
        )
        history.execute(cmdCenter)
        assertEquals(OcrTextAlignment.CENTER, history.currentDocument.pages[0].editedContent!!.paragraphs[0].alignment)

        // Undo -> restores LEFT
        history.undo()
        assertEquals(OcrTextAlignment.LEFT, history.currentDocument.pages[0].editedContent!!.paragraphs[0].alignment)

        // Redo -> restores CENTER
        history.redo()
        assertEquals(OcrTextAlignment.CENTER, history.currentDocument.pages[0].editedContent!!.paragraphs[0].alignment)
    }

    @Test
    fun testMultipleRunsSerializationRoundtrip() {
        val runs = listOf(
            OcrTextRun("Phần bình thường ", isBold = false, isItalic = false, fontSizePt = 11.0f),
            OcrTextRun("Phần in đậm ", isBold = true, isItalic = false, fontSizePt = 14.0f),
            OcrTextRun("Phần in nghiêng", isBold = false, isItalic = true, fontSizePt = 11.0f)
        )
        val para = OcrParagraph(
            paragraphId = "p_multirun",
            text = "Phần bình thường Phần in đậm Phần in nghiêng",
            alignment = OcrTextAlignment.CENTER,
            runs = runs
        )
        val doc = OcrDocument(
            id = "doc_multirun_test",
            pages = listOf(
                OcrPage(
                    pageId = "p1",
                    pageIndex = 1,
                    status = OcrPageStatus.SUCCESS,
                    editedContent = OcrEditedContent(text = para.text, paragraphs = listOf(para))
                )
            )
        )

        // JSON roundtrip
        val json = doc.toJsonString()
        val restored = OcrDocument.fromJsonString(json)

        val restoredPara = restored.pages[0].editedContent!!.paragraphs[0]
        assertEquals(3, restoredPara.runs.size)
        assertEquals(OcrTextAlignment.CENTER, restoredPara.alignment)

        assertTrue(restoredPara.runs[1].isBold)
        assertEquals(14.0f, restoredPara.runs[1].fontSizePt, 0.001f)

        assertTrue(restoredPara.runs[2].isItalic)
        assertEquals(11.0f, restoredPara.runs[2].fontSizePt, 0.001f)
    }

    @Test
    fun testSourceBlocksImmunityToFormatting() {
        val originalText = "Bản quét nguyên gốc"
        val doc = createDocumentWithParagraph(originalText)
        val history = OcrEditHistory(doc)

        // Change runs to bold, 18pt, alignment RIGHT
        val oldRun = doc.pages[0].editedContent!!.paragraphs[0].runs[0]
        val formattedRun = oldRun.copy(isBold = true, isItalic = true, fontSizePt = 18.0f)
        history.execute(OcrEditCommand.UpdateRunStyle(1, 0, 0, oldRun, formattedRun))
        history.execute(OcrEditCommand.SetParagraphAlignment(1, 0, OcrTextAlignment.LEFT, OcrTextAlignment.RIGHT))

        // Edited content reflects styles
        val editedPara = history.currentDocument.pages[0].editedContent!!.paragraphs[0]
        assertTrue(editedPara.runs[0].isBold)
        assertEquals(OcrTextAlignment.RIGHT, editedPara.alignment)

        // Source OCR block is completely untouched
        val sourceLine = history.currentDocument.pages[0].sourceBlocks[0].lines[0]
        assertEquals(originalText, sourceLine.text)
    }

    @Test
    fun testSelectionRangeFormattingOnlyAffectsTargetRange() {
        val para1 = OcrParagraph("p1", text = "Hello World", runs = listOf(OcrTextRun("Hello World")))
        val para2 = OcrParagraph("p2", text = "Second Paragraph", runs = listOf(OcrTextRun("Second Paragraph")))
        val doc = OcrDocument(pages = listOf(OcrPage(
            pageId = "p1", pageIndex = 1, status = OcrPageStatus.SUCCESS,
            editedContent = OcrEditedContent(text = "Hello World\nSecond Paragraph", paragraphs = listOf(para1, para2))
        )))

        val splitRunsP1 = listOf(
            OcrTextRun("Hello ", isBold = false),
            OcrTextRun("World", isBold = true)
        )
        val newParas = listOf(
            para1.copy(runs = splitRunsP1),
            para2
        )

        val history = OcrEditHistory(doc)
        history.execute(OcrEditCommand.ReplaceParagraphs(1, listOf(para1, para2), newParas))

        val current = history.currentDocument.pages[0].editedContent!!.paragraphs
        assertEquals(2, current.size)
        assertEquals(2, current[0].runs.size)
        assertFalse("Hello must not be bold", current[0].runs[0].isBold)
        assertTrue("World must be bold", current[0].runs[1].isBold)
        assertFalse("Second paragraph must remain untouched", current[1].runs[0].isBold)
    }
}
