package com.tscanner.app.paddleocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Coordinate point for OCR text bounding boxes.
 */
data class OcrPoint(var x: Double, var y: Double)

/**
 * PaddleOCR v4 Mobile Engine for Android (16 KB page-size compliant).
 * Performs text detection (DBNet) and recognition (SVTR) via ONNX Runtime
 * using pure Kotlin post-processing and Android native Canvas/Matrix.
 */
object PaddleOcrEngine {

    private const val TAG = "PaddleOcrEngine"
    private const val ASSETS_DIR = "paddleocr"
    private const val DET_MODEL_NAME = "ch_PP-OCRv4_det.onnx"
    private const val REC_MODEL_NAME = "ch_PP-OCRv4_rec.onnx"
    private const val KEYS_FILE_NAME = "ppocr_keys_v1.txt"

    @Volatile
    private var isInitialized = false

    private val initLock = Any()
    private val ocrMutex = Mutex()
    private var ortEnv: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var charDictionary: List<String> = emptyList()

    /**
     * Prepares ONNX sessions from assets.
     */
    fun initialize(context: Context): Boolean {
        if (isInitialized) return true

        synchronized(initLock) {
            if (isInitialized) return true

            try {
                // 1. Prepare models in internal storage for memory-mapped access
                val modelsDir = File(context.filesDir, ASSETS_DIR).apply { if (!exists()) mkdirs() }
                val detFile = copyAssetIfNeeded(context, "$ASSETS_DIR/$DET_MODEL_NAME", File(modelsDir, DET_MODEL_NAME))
                val recFile = copyAssetIfNeeded(context, "$ASSETS_DIR/$REC_MODEL_NAME", File(modelsDir, REC_MODEL_NAME))
                val keysFile = copyAssetIfNeeded(context, "$ASSETS_DIR/$KEYS_FILE_NAME", File(modelsDir, KEYS_FILE_NAME))

                // 2. Load dictionary keys with trailing space character for PP-OCRv4 CTC decode
                val lines = keysFile.readLines(Charsets.UTF_8).toMutableList()
                if (!lines.contains(" ")) {
                    lines.add(" ")
                }
                charDictionary = lines
                Log.d(TAG, "Loaded dictionary with ${charDictionary.size} characters")

                // 3. Initialize ONNX sessions
                val env = OrtEnvironment.getEnvironment()
                ortEnv = env

                val sessionOptions = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                    setIntraOpNumThreads(2)
                }

                detSession = env.createSession(detFile.absolutePath, sessionOptions)
                recSession = env.createSession(recFile.absolutePath, sessionOptions)

                isInitialized = true
                Log.d(TAG, "PaddleOCR v4 Mobile Engine initialized successfully (Native 16 KB compliant)")
                return true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize PaddleOCR Engine: ${t.message}", t)
                return false
            }
        }
    }

    private fun copyAssetIfNeeded(context: Context, assetPath: String, destFile: File): File {
        if (destFile.exists() && destFile.length() > 0L) {
            return destFile
        }

        val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (tempFile.exists() && tempFile.length() > 0) {
                if (destFile.exists()) {
                    destFile.delete()
                }
                val renamed = tempFile.renameTo(destFile)
                if (!renamed) {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                }
                Log.d(TAG, "Extracted $assetPath to ${destFile.absolutePath} (${destFile.length()} bytes)")
            } else {
                throw IllegalStateException("Failed to extract $assetPath, temp file is empty")
            }
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
        return destFile
    }

    /**
     * Recognizes text from a full-page Bitmap.
     * Executes DBNet text detection -> Rotated crop -> SVTR recognition -> CTC decode -> Text line ordering.
     */
    suspend fun recognizeText(context: Context, bitmap: Bitmap): String = withContext(Dispatchers.Default) {
        ocrMutex.withLock {
            if (!isInitialized) {
                val ok = initialize(context)
                if (!ok) return@withLock ""
            }

            val env = ortEnv ?: return@withLock ""
            val det = detSession ?: return@withLock ""
            val rec = recSession ?: return@withLock ""

            // Ensure bitmap is in software ARGB_8888
            val safeBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }

            try {
                // Step 1: Detect text bounding boxes
                val boxes = detectText(env, det, safeBitmap)
                if (boxes.isEmpty()) {
                    Log.w(TAG, "No text boxes detected by PaddleOCR")
                    return@withLock ""
                }

                Log.d(TAG, "Detected ${boxes.size} text boxes")

                // Step 2: Crop each box and recognize text
                val textLines = mutableListOf<Pair<OcrPoint, String>>()
                for (box in boxes) {
                    val cropped = cropTextLine(safeBitmap, box) ?: continue

                    val lineText = recognizeSingleLine(env, rec, cropped)
                    if (!cropped.isRecycled && cropped != safeBitmap) {
                        cropped.recycle()
                    }

                    if (lineText.isNotBlank()) {
                        val center = OcrPoint(
                            (box[0].x + box[1].x + box[2].x + box[3].x) / 4.0,
                            (box[0].y + box[1].y + box[2].y + box[3].y) / 4.0
                        )
                        textLines.add(Pair(center, lineText.trim()))
                    }
                }

                // Step 3: Sort text lines top-to-bottom, left-to-right
                textLines.sortWith { a, b ->
                    val dy = a.first.y - b.first.y
                    if (abs(dy) > 20.0) {
                        dy.compareTo(0.0)
                    } else {
                        a.first.x.compareTo(b.first.x)
                    }
                }

                val result = textLines.joinToString("\n") { it.second }
                result
            } catch (t: Throwable) {
                Log.e(TAG, "Error in recognizeText: ${t.message}", t)
                ""
            } finally {
                if (safeBitmap != bitmap && !safeBitmap.isRecycled) {
                    safeBitmap.recycle()
                }
            }
        }
    }

    /**
     * DBNet Text Detection.
     */
    private fun detectText(
        env: OrtEnvironment,
        detSession: OrtSession,
        bitmap: Bitmap
    ): List<Array<OcrPoint>> {
        val origW = bitmap.width
        val origH = bitmap.height

        // Limit maximum dimension to 960 while maintaining aspect ratio, rounded to multiple of 32
        val maxSide = max(origW, origH).toFloat()
        val scale = if (maxSide > 960f) 960f / maxSide else 1.0f
        var targetW = (origW * scale).toInt()
        var targetH = (origH * scale).toInt()
        targetW = (targetW / 32) * 32
        targetH = (targetH / 32) * 32
        targetW = max(32, targetW)
        targetH = max(32, targetH)

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)

        // Preprocess ImageNet normalization: (x / 255.0 - mean) / std
        val floatBuffer = ByteBuffer.allocateDirect(1 * 3 * targetH * targetW * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        val pixels = IntArray(targetW * targetH)
        scaledBitmap.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)

        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)

        val area = targetW * targetH
        for (c in 0..2) {
            for (i in 0 until area) {
                val color = pixels[i]
                val channelVal = when (c) {
                    0 -> ((color shr 16) and 0xFF) / 255.0f
                    1 -> ((color shr 8) and 0xFF) / 255.0f
                    else -> (color and 0xFF) / 255.0f
                }
                floatBuffer.put((channelVal - mean[c]) / std[c])
            }
        }
        floatBuffer.flip()

        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        val inputName = detSession.inputNames.iterator().next()
        val inputTensor = OnnxTensor.createTensor(env, floatBuffer, longArrayOf(1, 3, targetH.toLong(), targetW.toLong()))
        val output = try {
            detSession.run(java.util.Collections.singletonMap(inputName, inputTensor))
        } finally {
            inputTensor.close()
        }

        val mask = BooleanArray(targetH * targetW)
        val thresh = 0.3f
        val totalPixels = targetH * targetW

        try {
            val outputTensor = output.get(0) as OnnxTensor
            val outBuffer = outputTensor.floatBuffer
            for (i in 0 until totalPixels) {
                mask[i] = outBuffer.get(i) > thresh
            }
        } finally {
            output.close()
        }

        val rx = origW.toFloat() / targetW
        val ry = origH.toFloat() / targetH

        return extractBoundingBoxes(mask, targetW, targetH, rx, ry, origW, origH)
    }

    /**
     * Connected-Components extraction for DBNet text bounding boxes with unclip expansion.
     */
    private fun extractBoundingBoxes(
        mask: BooleanArray,
        w: Int,
        h: Int,
        rx: Float,
        ry: Float,
        origW: Int,
        origH: Int
    ): List<Array<OcrPoint>> {
        val totalPixels = w * h
        val visited = BooleanArray(totalPixels)
        val queue = IntArray(totalPixels)
        val boxes = mutableListOf<Array<OcrPoint>>()

        // 4-neighborhood offsets: left, right, up, down
        val dx = intArrayOf(-1, 1, 0, 0)
        val dy = intArrayOf(0, 0, -1, 1)

        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                val idx = rowOffset + x
                if (!mask[idx] || visited[idx]) continue

                // Start BFS connected component
                var head = 0
                var tail = 0
                queue[tail++] = idx
                visited[idx] = true

                var minX = x
                var maxX = x
                var minY = y
                var maxY = y
                var count = 0

                var sumX = 0.0
                var sumY = 0.0
                var sumXX = 0.0
                var sumYY = 0.0
                var sumXY = 0.0

                while (head < tail) {
                    val curr = queue[head++]
                    val cx = curr % w
                    val cy = curr / w
                    count++

                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    val fx = cx.toDouble()
                    val fy = cy.toDouble()
                    sumX += fx
                    sumY += fy
                    sumXX += fx * fx
                    sumYY += fy * fy
                    sumXY += fx * fy

                    for (d in 0..3) {
                        val nx = cx + dx[d]
                        val ny = cy + dy[d]
                        if (nx in 0 until w && ny in 0 until h) {
                            val nIdx = ny * w + nx
                            if (mask[nIdx] && !visited[nIdx]) {
                                visited[nIdx] = true
                                queue[tail++] = nIdx
                            }
                        }
                    }
                }

                val compW = maxX - minX + 1
                val compH = maxY - minY + 1
                // Filter out tiny noise (smaller than 3x3 or less than 6 pixels)
                if (compW < 3 || compH < 3 || count < 6) continue

                // Calculate orientation angle using central moments
                val meanX = sumX / count
                val meanY = sumY / count
                val mu20 = (sumXX / count) - (meanX * meanX)
                val mu02 = (sumYY / count) - (meanY * meanY)
                val mu11 = (sumXY / count) - (meanX * meanY)

                val angle = 0.5 * atan2(2.0 * mu11, mu20 - mu02)

                // Unclip distance calculation: (area * unclipRatio) / perimeter
                val unclipRatio = 1.6
                val boxPoints: Array<OcrPoint>

                if (abs(angle) < 0.087) { // Angle within ~5 degrees: use axis-aligned unclipped box
                    val dist = (compW * compH * unclipRatio) / (2.0 * (compW + compH))
                    val x0 = ((minX - dist) * rx).coerceIn(0.0, origW.toDouble())
                    val x1 = ((maxX + dist) * rx).coerceIn(0.0, origW.toDouble())
                    val y0 = ((minY - dist) * ry).coerceIn(0.0, origH.toDouble())
                    val y1 = ((maxY + dist) * ry).coerceIn(0.0, origH.toDouble())

                    boxPoints = arrayOf(
                        OcrPoint(x0, y0),
                        OcrPoint(x1, y0),
                        OcrPoint(x1, y1),
                        OcrPoint(x0, y1)
                    )
                } else {
                    // Rotated bounding box via principal axes projection
                    val cosA = cos(angle)
                    val sinA = sin(angle)

                    var minU = Double.MAX_VALUE
                    var maxU = -Double.MAX_VALUE
                    var minV = Double.MAX_VALUE
                    var maxV = -Double.MAX_VALUE

                    // Project all visited pixels of this component onto principal axis
                    for (i in 0 until tail) {
                        val px = queue[i] % w
                        val py = queue[i] / w
                        val dxVal = px - meanX
                        val dyVal = py - meanY
                        val u = dxVal * cosA + dyVal * sinA
                        val v = -dxVal * sinA + dyVal * cosA
                        if (u < minU) minU = u
                        if (u > maxU) maxU = u
                        if (v < minV) minV = v
                        if (v > maxV) maxV = v
                    }

                    val rotW = maxU - minU
                    val rotH = maxV - minV
                    val dist = (rotW * rotH * unclipRatio) / (2.0 * (rotW + rotH))

                    val u0 = minU - dist
                    val u1 = maxU + dist
                    val v0 = minV - dist
                    val v1 = maxV + dist

                    // Reconstruct the 4 corners in image space
                    fun toImgPt(u: Double, v: Double): OcrPoint {
                        val ix = (meanX + u * cosA - v * sinA) * rx
                        val iy = (meanY + u * sinA + v * cosA) * ry
                        return OcrPoint(ix.coerceIn(0.0, origW.toDouble()), iy.coerceIn(0.0, origH.toDouble()))
                    }

                    val p1 = toImgPt(u0, v0)
                    val p2 = toImgPt(u1, v0)
                    val p3 = toImgPt(u1, v1)
                    val p4 = toImgPt(u0, v1)

                    boxPoints = orderClockwise(arrayOf(p1, p2, p3, p4))
                }

                boxes.add(boxPoints)
            }
        }

        return boxes
    }

    private fun orderClockwise(pts: Array<OcrPoint>): Array<OcrPoint> {
        val tl = pts.minByOrNull { it.x + it.y } ?: pts[0]
        val br = pts.maxByOrNull { it.x + it.y } ?: pts[2]
        val tr = pts.minByOrNull { it.y - it.x } ?: pts[1]
        val bl = pts.maxByOrNull { it.y - it.x } ?: pts[3]
        return arrayOf(tl, tr, br, bl)
    }

    /**
     * Crops and straightens a quadrilateral text region using Android's native Matrix setPolyToPoly.
     */
    private fun cropTextLine(bitmap: Bitmap, box: Array<OcrPoint>): Bitmap? {
        return try {
            val tl = box[0]
            val tr = box[1]
            val br = box[2]
            val bl = box[3]

            val w1 = hypot(tr.x - tl.x, tr.y - tl.y)
            val w2 = hypot(br.x - bl.x, br.y - bl.y)
            val cropW = max(w1, w2).toInt().coerceIn(16, 2048)

            val h1 = hypot(bl.x - tl.x, bl.y - tl.y)
            val h2 = hypot(br.x - tr.x, br.y - tr.y)
            val cropH = max(h1, h2).toInt().coerceIn(8, 2048)

            val src = floatArrayOf(
                tl.x.toFloat(), tl.y.toFloat(),
                tr.x.toFloat(), tr.y.toFloat(),
                bl.x.toFloat(), bl.y.toFloat()
            )
            val dst = floatArrayOf(
                0f, 0f,
                cropW.toFloat(), 0f,
                0f, cropH.toFloat()
            )
            val matrix = Matrix()
            matrix.setPolyToPoly(src, 0, dst, 0, 3)

            val cropped = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(cropped)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(bitmap, matrix, paint)

            // If text line is vertical (height / width >= 1.5), rotate 90 degrees clockwise
            if (cropH.toFloat() / cropW.toFloat() >= 1.5f) {
                val rotMatrix = Matrix().apply { postRotate(90f) }
                val rotated = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, rotMatrix, true)
                if (rotated != cropped) {
                    cropped.recycle()
                }
                rotated
            } else {
                cropped
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cropTextLine failed: ${t.message}")
            // Fallback axis-aligned crop
            cropTextLineFallback(bitmap, box)
        }
    }

    /**
     * Fallback crop using axis-aligned bounding box.
     */
    private fun cropTextLineFallback(bitmap: Bitmap, box: Array<OcrPoint>): Bitmap? {
        return try {
            val minX = box.minOf { it.x }.toInt().coerceIn(0, bitmap.width - 1)
            val maxX = box.maxOf { it.x }.toInt().coerceIn(minX + 1, bitmap.width)
            val minY = box.minOf { it.y }.toInt().coerceIn(0, bitmap.height - 1)
            val maxY = box.maxOf { it.y }.toInt().coerceIn(minY + 1, bitmap.height)
            val w = maxX - minX
            val h = maxY - minY
            if (w < 4 || h < 4) return null
            Bitmap.createBitmap(bitmap, minX, minY, w, h)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Recognizes text from a single cropped text line Bitmap using PP-OCRv4 Recognition model.
     */
    private fun recognizeSingleLine(
        env: OrtEnvironment,
        recSession: OrtSession,
        lineBitmap: Bitmap
    ): String {
        val targetH = 48
        val scale = targetH.toFloat() / lineBitmap.height.coerceAtLeast(1)
        var targetW = (lineBitmap.width * scale).toInt().coerceIn(48, 960)
        targetW = ((targetW + 3) / 4) * 4

        val scaled = Bitmap.createScaledBitmap(lineBitmap, targetW, targetH, true)

        val buffer = ByteBuffer.allocateDirect(1 * 3 * targetH * targetW * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        val pixels = IntArray(targetW * targetH)
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)

        val area = targetW * targetH
        for (c in 0..2) {
            for (i in 0 until area) {
                val color = pixels[i]
                val channelVal = when (c) {
                    0 -> ((color shr 16) and 0xFF) / 255.0f
                    1 -> ((color shr 8) and 0xFF) / 255.0f
                    else -> (color and 0xFF) / 255.0f
                }
                buffer.put((channelVal - 0.5f) / 0.5f)
            }
        }
        buffer.flip()

        if (scaled != lineBitmap) {
            scaled.recycle()
        }

        val inputName = recSession.inputNames.iterator().next()
        val inputTensor = OnnxTensor.createTensor(env, buffer, longArrayOf(1, 3, targetH.toLong(), targetW.toLong()))
        val output = try {
            recSession.run(java.util.Collections.singletonMap(inputName, inputTensor))
        } finally {
            inputTensor.close()
        }

        val sb = StringBuilder()
        try {
            val outputTensor = output.get(0) as OnnxTensor
            val info = outputTensor.info as TensorInfo
            val shape = info.shape // [1, seqLen, numClasses]
            val seqLen = shape[1].toInt()
            val numClasses = shape[2].toInt()
            val outBuffer = outputTensor.floatBuffer
            val totalFloats = seqLen * numClasses
            val allFloats = FloatArray(totalFloats)
            outBuffer.get(allFloats)

            // CTC greedy decode
            var prevIdx = -1

            for (step in 0 until seqLen) {
                var maxVal = Float.NEGATIVE_INFINITY
                var maxIdx = -1
                val offset = step * numClasses
                for (idx in 0 until numClasses) {
                    val v = allFloats[offset + idx]
                    if (v > maxVal) {
                        maxVal = v
                        maxIdx = idx
                    }
                }

                if (maxIdx > 0 && maxIdx != prevIdx) {
                    val charIndex = maxIdx - 1
                    if (charIndex in charDictionary.indices) {
                        sb.append(charDictionary[charIndex])
                    }
                }
                prevIdx = maxIdx
            }
        } finally {
            output.close()
        }

        return sb.toString()
    }
}
