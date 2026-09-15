package com.tscanner.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import androidx.exifinterface.media.ExifInterface
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.util.UUID

object PdfConverterHelper {

    suspend fun createPdfFromImages(
        imagePaths: List<String>,
        outputFile: File,
        addWatermark: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) {
                outputFile.delete()
            }

            val pdfDoc = PdfDocument()
            var pagesAdded = 0

            for (path in imagePaths) {
                val rawBitmap = BitmapFactory.decodeFile(path) ?: continue
                val rotationDegrees = try {
                    val exif = ExifInterface(path)
                    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } catch (_: Exception) { 0f }

                val bitmap = if (rotationDegrees != 0f) {
                    val matrix = Matrix().apply { postRotate(rotationDegrees) }
                    val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                    if (rotated != rawBitmap) rawBitmap.recycle()
                    rotated
                } else {
                    rawBitmap
                }
                val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, pagesAdded + 1).create()
                val page = pdfDoc.startPage(pageInfo)
                val canvas = page.canvas
                val paint = Paint().apply { isFilterBitmap = true }
                canvas.drawBitmap(bitmap, 0f, 0f, paint)

                if (addWatermark) {
                    WatermarkHelper.drawBottomRightWatermark(
                        canvas = canvas,
                        pageWidth = bitmap.width.toFloat(),
                        pageHeight = bitmap.height.toFloat()
                    )
                }

                pdfDoc.finishPage(page)
                bitmap.recycle()
                pagesAdded++
            }

            if (pagesAdded == 0) {
                pdfDoc.close()
                return@withContext false
            }

            FileOutputStream(outputFile).use { out ->
                pdfDoc.writeTo(out)
            }
            pdfDoc.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun convertPdfToImages(context: Context, pdfFile: File, outputDir: File): List<String> = withContext(Dispatchers.IO) {
        val imagePaths = mutableListOf<String>()
        var fileDescriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            fileDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(fileDescriptor)
            val pageCount = renderer.pageCount

            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                val width = (page.width * 2.5).toInt() // 2.5x for crisp resolution and accurate diacritic OCR
                val height = (page.height * 2.5).toInt()
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                val imgFile = File(outputDir, "pdf_page_${System.currentTimeMillis()}_${i + 1}.jpg")
                FileOutputStream(imgFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                bitmap.recycle()
                imagePaths.add(imgFile.absolutePath)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                renderer?.close()
                fileDescriptor?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        imagePaths
    }

    fun renderPdfFirstPage(pdfFile: File, outputFile: File): Boolean {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            outputFile.parentFile?.mkdirs()
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount > 0) {
                val page = renderer.openPage(0)
                val targetWidth = 240
                val targetHeight = (page.height * (targetWidth.toFloat() / page.width)).toInt()
                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                FileOutputStream(outputFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                bitmap.recycle()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try {
                renderer?.close()
                pfd?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun convertPdfToLongImage(
        context: Context,
        pdfFile: File,
        outputFile: File,
        addWatermark: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        var fileDescriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            fileDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(fileDescriptor)
            val pageCount = renderer.pageCount
            if (pageCount == 0) return@withContext false

            val bitmaps = mutableListOf<Bitmap>()
            var maxWidth = 0
            var totalHeight = 0

            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                val width = page.width
                val height = page.height
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                bitmaps.add(bmp)
                if (width > maxWidth) maxWidth = width
                totalHeight += height
            }

            if (totalHeight > 16384) {
                // Scale down if total height exceeds max Android canvas limit (typically 16k)
                val scale = 16384f / totalHeight
                maxWidth = (maxWidth * scale).toInt()
                totalHeight = 16384
            }

            val longBitmap = Bitmap.createBitmap(maxWidth, totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(longBitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)

            var currentY = 0f
            for (bmp in bitmaps) {
                val scaledBmp = if (bmp.width != maxWidth) {
                    val scale = maxWidth.toFloat() / bmp.width
                    Bitmap.createScaledBitmap(bmp, maxWidth, (bmp.height * scale).toInt(), true)
                } else bmp
                canvas.drawBitmap(scaledBmp, 0f, currentY, paint)
                currentY += scaledBmp.height
                if (scaledBmp != bmp) scaledBmp.recycle()
                bmp.recycle()
            }

            if (addWatermark) {
                WatermarkHelper.drawBottomRightWatermark(canvas, maxWidth.toFloat(), totalHeight.toFloat())
            }

            FileOutputStream(outputFile).use { out ->
                longBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            longBitmap.recycle()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try {
                renderer?.close()
                fileDescriptor?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun exportTextToWord(
        text: String,
        outputFile: File,
        addWatermark: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            outputFile.parentFile?.mkdirs()
            val watermarkSnippet = if (addWatermark) WatermarkHelper.getWordWatermarkHtml() else ""
            // Write standard formatted document / HTML that Word / WPS opens perfectly
            val htmlContent = """
                <html xmlns:o='urn:schemas-microsoft-com:office:office' xmlns:w='urn:schemas-microsoft-com:office:word' xmlns='http://www.w3.org/TR/REC-html40'>
                <head><meta charset='utf-8'><title>Document</title>
                <style>
                body { font-family: 'Calibri', 'Arial', sans-serif; font-size: 11pt; line-height: 1.5; margin: 20px; }
                p { margin-bottom: 10px; }
                </style>
                </head>
                <body>
                ${text.split("\n").joinToString("") { "<p>${it.replace("<", "&lt;").replace(">", "&gt;")}</p>" }}
                $watermarkSnippet
                </body>
                </html>
            """.trimIndent()
            outputFile.writeText(htmlContent, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun exportTextToExcel(
        text: String,
        outputFile: File,
        addWatermark: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            outputFile.parentFile?.mkdirs()
            FileWriter(outputFile).use { writer ->
                // Write UTF-8 BOM for Excel compatibility
                writer.write("\uFEFF")
                val lines = text.split("\n")
                for (line in lines) {
                    // Split on tab, comma, or semicolon if table
                    val tokens = if (line.contains("\t")) {
                        line.split("\t")
                    } else if (line.contains(";") && !line.contains(",")) {
                        line.split(";")
                    } else {
                        listOf(line)
                    }
                    val csvLine = tokens.joinToString(",") { token ->
                        "\"${token.replace("\"", "\"\"").trim()}\""
                    }
                    writer.write(csvLine + "\n")
                }
                if (addWatermark) {
                    writer.write("\n\"\",\"\",\"${WatermarkHelper.WATERMARK_TEXT}\"\n")
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun exportToPpt(
        imagePaths: List<String>,
        outputFile: File,
        addWatermark: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val watermarkSnippet = if (addWatermark) WatermarkHelper.getPptWatermarkHtml() else ""
            // Write presentation bundle containing the slides
            // HTML slide presentation compatible with PowerPoint / Presentation viewers
            val htmlContent = buildString {
                append("<!DOCTYPE html><html><head><meta charset='utf-8'><title>Presentation</title>")
                append("<style>body { margin: 0; background: #202020; font-family: sans-serif; }")
                append(".slide { width: 960px; height: 540px; margin: 20px auto; background: white; padding: 20px; box-sizing: border-box; display: flex; align-items: center; justify-content: center; position: relative; page-break-after: always; }")
                append("img { max-width: 100%; max-height: 100%; object-fit: contain; }")
                append("</style></head><body>")
                for (path in imagePaths) {
                    val file = File(path)
                    if (file.exists()) {
                        val base64 = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
                        append("<div class='slide'><img src='data:image/jpeg;base64,$base64'/>$watermarkSnippet</div>")
                    }
                }
                append("</body></html>")
            }
            outputFile.writeText(htmlContent, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
