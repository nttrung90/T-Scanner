package com.tscanner.app.ocr.export

import com.tscanner.app.ocr.model.*
import com.tscanner.app.utils.SafeFileWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Lightweight, zero-dependency OpenXML (.docx) writer.
 * Generates standards-compliant Word documents from OcrDocument models:
 * - Formatted paragraphs (bold, italic, font size, alignment)
 * - Multi-page documents with native page breaks
 * - Tables with row/column spans and borders
 * - Watermark protection policy
 * - Pure Kotlin with standard java.util.zip (0 MB third-party library overhead)
 *
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S19).
 */
object DocxWriter {

    suspend fun writeDocx(
        document: OcrDocument,
        outputFile: File,
        addWatermark: Boolean = false,
        watermarkText: String = "T-Scanner"
    ): Boolean = withContext(Dispatchers.IO) {
        val result = SafeFileWriter.writeSafely(
            destinationFile = outputFile,
            validator = { tempFile ->
                tempFile.exists() && tempFile.length() > 500L
            },
            writer = { tempFile ->
                tempFile.outputStream().use { os ->
                    generateDocxStream(document, os, addWatermark, watermarkText)
                }
                true
            }
        )
        result is SafeFileWriter.Result.Success
    }

    fun generateDocxStream(
        document: OcrDocument,
        outStream: OutputStream,
        addWatermark: Boolean,
        watermarkText: String
    ) {
        val zip = ZipOutputStream(outStream, StandardCharsets.UTF_8)

        // 1. [Content_Types].xml
        zip.putNextEntry(ZipEntry("[Content_Types].xml"))
        zip.write(buildContentTypesXml().toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 2. _rels/.rels
        zip.putNextEntry(ZipEntry("_rels/.rels"))
        zip.write(buildPackageRelsXml().toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 3. word/_rels/document.xml.rels
        zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
        zip.write(buildDocumentRelsXml().toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 4. word/styles.xml
        zip.putNextEntry(ZipEntry("word/styles.xml"))
        zip.write(buildStylesXml().toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 5. word/document.xml
        zip.putNextEntry(ZipEntry("word/document.xml"))
        zip.write(buildDocumentXml(document, addWatermark, watermarkText).toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        zip.finish()
        zip.flush()
    }

    private fun buildContentTypesXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
            <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
            <Default Extension="xml" ContentType="application/xml"/>
            <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
        </Types>
    """.trimIndent()

    private fun buildPackageRelsXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
        </Relationships>
    """.trimIndent()

    private fun buildDocumentRelsXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
        </Relationships>
    """.trimIndent()

    private fun buildStylesXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
            <w:docDefaults>
                <w:rPrDefault>
                    <w:rPr>
                        <w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/>
                        <w:sz w:val="22"/>
                        <w:szCs w:val="22"/>
                        <w:lang w:val="vi-VN"/>
                    </w:rPr>
                </w:rPrDefault>
                <w:pPrDefault>
                    <w:pPr>
                        <w:spacing w:after="160" w:line="240" w:lineRule="auto"/>
                    </w:pPr>
                </w:pPrDefault>
            </w:docDefaults>
            <w:style w:type="paragraph" w:default="1" w:styleId="Normal">
                <w:name w:val="Normal"/>
            </w:style>
        </w:styles>
    """.trimIndent()

    private fun buildDocumentXml(
        document: OcrDocument,
        addWatermark: Boolean,
        watermarkText: String
    ): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" ")
        sb.append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">\n")
        sb.append("<w:body>\n")

        for ((pIdx, page) in document.pages.withIndex()) {
            if (pIdx > 0) {
                // Page Break between pages
                sb.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>\n")
            }

            // 1. Output Tables if present on page
            for (table in page.tables) {
                sb.append(buildTableXml(table))
            }

            // 2. Output Paragraphs
            val paragraphs = page.editedContent?.paragraphs
            if (!paragraphs.isNullOrEmpty()) {
                for (para in paragraphs) {
                    sb.append(buildParagraphXml(para))
                }
            } else {
                // Fallback to resolvedText lines
                val lines = page.resolvedText.lines()
                for (line in lines) {
                    sb.append("<w:p><w:r><w:t xml:space=\"preserve\">")
                    sb.append(escapeXml(line))
                    sb.append("</w:t></w:r></w:p>\n")
                }
            }
        }

        // Watermark paragraph if requested
        if (addWatermark) {
            sb.append("<w:p><w:pPr><w:jc w:val=\"right\"/></w:pPr>")
            sb.append("<w:r><w:rPr><w:color w:val=\"888888\"/><w:sz w:val=\"18\"/><w:i/></w:rPr>")
            sb.append("<w:t xml:space=\"preserve\">")
            sb.append(escapeXml(watermarkText))
            sb.append("</w:t></w:r></w:p>\n")
        }

        // Section properties (A4, 1-inch margins)
        sb.append("<w:sectPr>\n")
        sb.append("<w:pgSz w:w=\"11906\" w:h=\"16838\"/>\n")
        sb.append("<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/>\n")
        sb.append("</w:sectPr>\n")

        sb.append("</w:body>\n")
        sb.append("</w:document>")
        return sb.toString()
    }

    private fun buildParagraphXml(para: OcrParagraph): String {
        val sb = StringBuilder()
        sb.append("<w:p>")

        // Paragraph properties (alignment)
        val jcVal = when (para.alignment) {
            OcrTextAlignment.CENTER -> "center"
            OcrTextAlignment.RIGHT -> "right"
            OcrTextAlignment.JUSTIFY -> "both"
            else -> null
        }
        if (jcVal != null) {
            sb.append("<w:pPr><w:jc w:val=\"$jcVal\"/></w:pPr>")
        }

        if (para.runs.isNotEmpty()) {
            for (run in para.runs) {
                sb.append("<w:r>")
                val hasStyle = run.isBold || run.isItalic || run.fontSizePt != 11.0f
                if (hasStyle) {
                    sb.append("<w:rPr>")
                    if (run.isBold) sb.append("<w:b/>")
                    if (run.isItalic) sb.append("<w:i/>")
                    if (run.fontSizePt > 0f) {
                        val szVal = (run.fontSizePt * 2).toInt()
                        sb.append("<w:sz w:val=\"$szVal\"/>")
                        sb.append("<w:szCs w:val=\"$szVal\"/>")
                    }
                    sb.append("</w:rPr>")
                }
                val lines = run.text.split("\n")
                lines.forEachIndexed { lIdx, linePart ->
                    if (lIdx > 0) sb.append("<w:br/>")
                    if (linePart.isNotEmpty()) {
                        sb.append("<w:t xml:space=\"preserve\">")
                        sb.append(escapeXml(linePart))
                        sb.append("</w:t>")
                    }
                }
                sb.append("</w:r>")
            }
        } else {
            sb.append("<w:r>")
            val lines = para.text.split("\n")
            lines.forEachIndexed { lIdx, linePart ->
                if (lIdx > 0) sb.append("<w:br/>")
                if (linePart.isNotEmpty()) {
                    sb.append("<w:t xml:space=\"preserve\">")
                    sb.append(escapeXml(linePart))
                    sb.append("</w:t>")
                }
            }
            sb.append("</w:r>")
        }

        sb.append("</w:p>\n")
        return sb.toString()
    }

    private fun buildTableXml(table: OcrTable): String {
        val sb = StringBuilder()
        sb.append("<w:tbl>\n")

        // Table properties
        sb.append("<w:tblPr>")
        sb.append("<w:tblW w:w=\"5000\" w:type=\"pct\"/>")
        sb.append("<w:tblBorders>")
        sb.append("<w:top w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"CCCCCC\"/>")
        sb.append("<w:left w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"CCCCCC\"/>")
        sb.append("<w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"CCCCCC\"/>")
        sb.append("<w:right w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"CCCCCC\"/>")
        sb.append("<w:insideH w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"EEEEEE\"/>")
        sb.append("<w:insideV w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"EEEEEE\"/>")
        sb.append("</w:tblBorders>")
        sb.append("</w:tblPr>\n")

        // Iterate grid cells row by row
        for (r in 0 until table.rowCount) {
            sb.append("<w:tr>\n")
            var c = 0
            while (c < table.columnCount) {
                val originCell = table.cells.firstOrNull {
                    r in it.rowIndex until (it.rowIndex + it.rowSpan) &&
                            c in it.colIndex until (it.colIndex + it.colSpan)
                }

                if (originCell == null) {
                    sb.append("<w:tc><w:p/></w:tc>\n")
                    c++
                } else if (c == originCell.colIndex) {
                    sb.append("<w:tc>")
                    sb.append("<w:tcPr>")
                    if (originCell.colSpan > 1) {
                        sb.append("<w:gridSpan w:val=\"${originCell.colSpan}\"/>")
                    }
                    if (originCell.rowSpan > 1) {
                        if (r == originCell.rowIndex) {
                            sb.append("<w:vMerge w:val=\"restart\"/>")
                        } else {
                            sb.append("<w:vMerge/>")
                        }
                    }
                    sb.append("</w:tcPr>")

                    if (r == originCell.rowIndex) {
                        sb.append("<w:p>")
                        if (originCell.cellType == OcrCellType.NUMBER) {
                            sb.append("<w:pPr><w:jc w:val=\"right\"/></w:pPr>")
                        }
                        sb.append("<w:r><w:t xml:space=\"preserve\">")
                        sb.append(escapeXml(originCell.editedText))
                        sb.append("</w:t></w:r></w:p>")
                    } else {
                        sb.append("<w:p/>")
                    }
                    sb.append("</w:tc>\n")
                    c += originCell.colSpan
                } else {
                    c++
                }
            }
            sb.append("</w:tr>\n")
        }

        sb.append("</w:tbl>\n")
        return sb.toString()
    }

    fun escapeXml(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&apos;")
                else -> {
                    // Filter non-printable ASCII control characters except tab, LF, CR
                    val code = ch.code
                    if (code in 0x20..0xD7FF || code == 0x9 || code == 0xA || code == 0xD || code in 0xE000..0xFFFD) {
                        out.append(ch)
                    }
                }
            }
        }
        return out.toString()
    }
}
