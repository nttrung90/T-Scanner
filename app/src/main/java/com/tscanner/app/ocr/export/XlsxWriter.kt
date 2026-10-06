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
 * Lightweight, zero-dependency OpenXML SpreadsheetML (.xlsx) writer.
 * Generates standards-compliant Excel workbooks from OcrDocument models:
 * - Direct inline strings ('inlineStr') for zero-index shared string overhead
 * - Preserves leading zeros ("00123" stays pure text)
 * - Safe formula injection protection (neutralizes '=', '+', '-', '@')
 * - Merged cells ('mergeCells') and formatted numbers ('n')
 * - Pure Kotlin with standard java.util.zip (0 MB third-party library overhead)
 *
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Package S20).
 */
object XlsxWriter {

    suspend fun writeXlsx(
        document: OcrDocument,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        val result = SafeFileWriter.writeSafely(
            destinationFile = outputFile,
            validator = { tempFile ->
                tempFile.exists() && tempFile.length() > 500L
            },
            writer = { tempFile ->
                tempFile.outputStream().use { os ->
                    generateXlsxStream(document, os)
                }
                true
            }
        )
        result is SafeFileWriter.Result.Success
    }

    fun generateXlsxStream(document: OcrDocument, outStream: OutputStream) {
        val zip = ZipOutputStream(outStream, StandardCharsets.UTF_8)

        // 1. [Content_Types].xml
        zip.putNextEntry(ZipEntry("[Content_Types].xml"))
        zip.write(buildContentTypesXml().toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 2. _rels/.rels
        zip.putNextEntry(ZipEntry("_rels/.rels"))
        zip.write(buildPackageRelsXml().toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 3. xl/_rels/workbook.xml.rels
        zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
        zip.write(buildWorkbookRelsXml().toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 4. xl/styles.xml
        zip.putNextEntry(ZipEntry("xl/styles.xml"))
        zip.write(buildStylesXml().toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 5. xl/workbook.xml
        zip.putNextEntry(ZipEntry("xl/workbook.xml"))
        zip.write(buildWorkbookXml().toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 6. xl/worksheets/sheet1.xml
        zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
        zip.write(buildWorksheetXml(document).toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        zip.finish()
        zip.flush()
    }

    private fun buildContentTypesXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
            <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
            <Default Extension="xml" ContentType="application/xml"/>
            <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
            <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
            <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
        </Types>
    """.trimIndent()

    private fun buildPackageRelsXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
        </Relationships>
    """.trimIndent()

    private fun buildWorkbookRelsXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
            <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
        </Relationships>
    """.trimIndent()

    private fun buildWorkbookXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
            <sheets>
                <sheet name="Sheet1" sheetId="1" r:id="rId1"/>
            </sheets>
        </workbook>
    """.trimIndent()

    private fun buildStylesXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
            <fonts count="2">
                <font>
                    <sz val="11"/>
                    <color theme="1"/>
                    <name val="Calibri"/>
                    <family val="2"/>
                </font>
                <font>
                    <b/>
                    <sz val="11"/>
                    <color theme="1"/>
                    <name val="Calibri"/>
                    <family val="2"/>
                </font>
            </fonts>
            <fills count="2">
                <fill><patternFill patternType="none"/></fill>
                <fill><patternFill patternType="gray125"/></fill>
            </fills>
            <borders count="1">
                <border><left/><right/><top/><bottom/><diagonal/></border>
            </borders>
            <cellStyleXfs count="1">
                <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
            </cellStyleXfs>
            <cellXfs count="3">
                <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
                <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
                <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment horizontal="right"/></xf>
            </cellXfs>
        </styleSheet>
    """.trimIndent()

    private fun buildWorksheetXml(document: OcrDocument): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n")

        // 1. Column dimensions
        sb.append("<cols>\n")
        for (col in 1..26) {
            sb.append("<col min=\"$col\" max=\"$col\" width=\"18\" customWidth=\"1\"/>\n")
        }
        sb.append("</cols>\n")

        // 2. Sheet Data
        sb.append("<sheetData>\n")

        var globalRowIndex = 0
        val mergeCellsList = mutableListOf<String>()

        for (page in document.pages) {
            if (page.tables.isNotEmpty()) {
                for (table in page.tables) {
                    val baseRow = globalRowIndex

                    for (r in 0 until table.rowCount) {
                        val rowNum = baseRow + r + 1
                        sb.append("<row r=\"$rowNum\">\n")
                        val rowCells = table.cells.filter { it.rowIndex == r }.sortedBy { it.colIndex }

                        for (cell in rowCells) {
                            val cellRef = getCellRef(cell.colIndex, baseRow + cell.rowIndex)
                            val rawText = cell.editedText.trim()

                            // Check merge span
                            if (cell.rowSpan > 1 || cell.colSpan > 1) {
                                val endRef = getCellRef(
                                    cell.colIndex + cell.colSpan - 1,
                                    baseRow + cell.rowIndex + cell.rowSpan - 1
                                )
                                mergeCellsList.add("$cellRef:$endRef")
                            }

                            // Neutralize formula injection
                            val isFormulaStarter = rawText.startsWith("=") || rawText.startsWith("+") ||
                                    rawText.startsWith("-") || rawText.startsWith("@")

                            if (cell.cellType == OcrCellType.NUMBER && !isFormulaStarter && isValidNumber(rawText)) {
                                val numStr = rawText.replace(",", ".")
                                sb.append("<c r=\"$cellRef\" s=\"2\" t=\"n\"><v>$numStr</v></c>\n")
                            } else {
                                // Sanitized text as inlineStr
                                val safeText = if (isFormulaStarter) "'$rawText" else rawText
                                sb.append("<c r=\"$cellRef\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                                sb.append(escapeXml(safeText))
                                sb.append("</t></is></c>\n")
                            }
                        }
                        sb.append("</row>\n")
                    }
                    globalRowIndex += table.rowCount + 1 // Add gap row between tables
                }
            } else {
                // If no tables on page, dump resolved text lines as sequential rows
                val lines = page.resolvedText.lines().filter { it.isNotBlank() }
                for (line in lines) {
                    globalRowIndex++
                    val cellRef = getCellRef(0, globalRowIndex - 1)
                    val safeText = if (line.startsWith("=") || line.startsWith("+") || line.startsWith("-") || line.startsWith("@")) "'$line" else line
                    sb.append("<row r=\"$globalRowIndex\">\n")
                    sb.append("<c r=\"$cellRef\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    sb.append(escapeXml(safeText))
                    sb.append("</t></is></c>\n")
                    sb.append("</row>\n")
                }
                globalRowIndex++
            }
        }

        sb.append("</sheetData>\n")

        // 3. Merged Cells
        if (mergeCellsList.isNotEmpty()) {
            sb.append("<mergeCells count=\"${mergeCellsList.size}\">\n")
            for (m in mergeCellsList) {
                sb.append("<mergeCell ref=\"$m\"/>\n")
            }
            sb.append("</mergeCells>\n")
        }

        sb.append("</worksheet>")
        return sb.toString()
    }

    fun getCellRef(colIndex: Int, rowIndex: Int): String {
        var c = colIndex
        val colStr = StringBuilder()
        while (c >= 0) {
            colStr.insert(0, ('A'.code + (c % 26)).toChar())
            c = (c / 26) - 1
        }
        return "$colStr${rowIndex + 1}"
    }

    private fun isValidNumber(str: String): Boolean {
        if (str.isEmpty()) return false
        // Disallow leading zeros with length > 1 (e.g. "00123" is text, NOT number!)
        if (str.length > 1 && str.startsWith("0") && str[1].isDigit()) {
            return false
        }
        return str.matches(Regex("^[+-]?\\d+([\\.,]\\d+)?$"))
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
