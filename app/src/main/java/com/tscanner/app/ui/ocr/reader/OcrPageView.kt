package com.tscanner.app.ui.ocr.reader

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.accessibility.AccessibilityEvent
import com.tscanner.app.ocr.model.OcrPage
import com.tscanner.app.ocr.model.OcrPolygon
import com.tscanner.app.ocr.model.OcrRect

/**
 * Lightweight interactive view for displaying scanned page images.
 * Supports pinch-to-zoom, pan, double-tap zoom, hit-testing, text selection,
 * search highlights, and coordinate translation between View pixels and page normalized [0..1] coordinates.
 *
 * Complies with PLAN_OCR_READER_EDITOR_SMALL_MODEL_10MB_2026-09-22.md (Packages S09, S10).
 */
class OcrPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val baseMatrix = Matrix()
    private val drawMatrix = Matrix()
    private val inverseDrawMatrix = Matrix()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9E9E9E")
        textSize = 14f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }

    // Selection & search highlight paints
    private val selectionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4D009688") // 30% teal
        style = Paint.Style.FILL
    }
    private val selectionStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#009688")
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val searchHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFEB3B") // 40% yellow
        style = Paint.Style.FILL
    }

    private val scratchPath = Path()
    private val scratchRectF = RectF()
    private val scratchPts = FloatArray(8)

    val selectionController = OcrSelectionController()
    var onSelectionChanged: ((selectedText: String) -> Unit)? = null

    private var bitmap: Bitmap? = null
    var isImageMissing: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // Zoom & Pan state
    var currentScale = 1.0f
        private set
    var currentPanX = 0f
        private set
    var currentPanY = 0f
        private set

    var onTransformChanged: ((scale: Float, panX: Float, panY: Float) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            val newScale = (currentScale * factor).coerceIn(1.0f, 5.0f)
            if (newScale != currentScale) {
                val focusX = detector.focusX
                val focusY = detector.focusY
                currentPanX = focusX - (focusX - currentPanX) * (newScale / currentScale)
                currentPanY = focusY - (focusY - currentPanY) * (newScale / currentScale)
                currentScale = newScale
                clampPan()
                updateDrawMatrix()
                invalidate()
                onTransformChanged?.invoke(currentScale, currentPanX, currentPanY)
            }
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (currentScale > 1.0f) {
                currentPanX -= distanceX
                currentPanY -= distanceY
                clampPan()
                updateDrawMatrix()
                invalidate()
                onTransformChanged?.invoke(currentScale, currentPanX, currentPanY)
                return true
            }
            return false
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val normPt = viewToPageNormalized(e.x, e.y)
            if (normPt != null) {
                val hit = selectionController.onSingleTap(normPt.first, normPt.second)
                if (hit) {
                    invalidate()
                    onSelectionChanged?.invoke(selectionController.getSelectedText())
                    return true
                }
            }
            return false
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentScale > 1.2f) {
                resetZoom()
            } else {
                zoomTo(2.5f, e.x, e.y)
            }
            return true
        }
    })

    fun setOcrPage(page: OcrPage?) {
        selectionController.setPage(page)
        invalidate()
    }

    fun setPageBitmap(bmp: Bitmap?) {
        if (this.bitmap != bmp) {
            this.bitmap = bmp
            this.isImageMissing = (bmp == null)
            updateBaseMatrix()
            updateDrawMatrix()
            invalidate()
        }
    }

    fun setTransform(scale: Float, panX: Float, panY: Float) {
        currentScale = scale.coerceIn(1.0f, 5.0f)
        currentPanX = panX
        currentPanY = panY
        clampPan()
        updateDrawMatrix()
        invalidate()
    }

    fun resetZoom() {
        currentScale = 1.0f
        currentPanX = 0f
        currentPanY = 0f
        updateDrawMatrix()
        invalidate()
        onTransformChanged?.invoke(currentScale, currentPanX, currentPanY)
    }

    fun zoomTo(targetScale: Float, focalX: Float, focalY: Float) {
        val newScale = targetScale.coerceIn(1.0f, 5.0f)
        currentPanX = focalX - (focalX - currentPanX) * (newScale / currentScale)
        currentPanY = focalY - (focalY - currentPanY) * (newScale / currentScale)
        currentScale = newScale
        clampPan()
        updateDrawMatrix()
        invalidate()
        onTransformChanged?.invoke(currentScale, currentPanX, currentPanY)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateBaseMatrix()
        updateDrawMatrix()
    }

    private fun updateBaseMatrix() {
        val bmp = bitmap ?: return
        if (width <= 0 || height <= 0) return

        val src = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        val dst = RectF(0f, 0f, width.toFloat(), height.toFloat())
        baseMatrix.setRectToRect(src, dst, Matrix.ScaleToFit.CENTER)
    }

    private fun updateDrawMatrix() {
        drawMatrix.set(baseMatrix)
        drawMatrix.postScale(currentScale, currentScale)
        drawMatrix.postTranslate(currentPanX, currentPanY)
        drawMatrix.invert(inverseDrawMatrix)
    }

    private fun clampPan() {
        val bmp = bitmap ?: return
        if (width <= 0 || height <= 0) return

        val mappedBounds = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        val tempMatrix = Matrix(baseMatrix)
        tempMatrix.postScale(currentScale, currentScale)
        tempMatrix.mapRect(mappedBounds)

        if (mappedBounds.width() <= width) {
            currentPanX = (width - mappedBounds.width()) / 2f - mappedBounds.left
        } else {
            val minX = width - mappedBounds.right
            val maxX = -mappedBounds.left
            currentPanX = currentPanX.coerceIn(minX, maxX)
        }

        if (mappedBounds.height() <= height) {
            currentPanY = (height - mappedBounds.height()) / 2f - mappedBounds.top
        } else {
            val minY = height - mappedBounds.bottom
            val maxY = -mappedBounds.top
            currentPanY = currentPanY.coerceIn(minY, maxY)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        var handled = scaleDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled
        return handled || super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled) {
            canvas.drawBitmap(bmp, drawMatrix, paint)

            val bmpW = bmp.width.toFloat()
            val bmpH = bmp.height.toFloat()

            // 1. Draw search highlight matches
            val matches = selectionController.getSearchMatches()
            if (matches.isNotEmpty()) {
                for (m in matches) {
                    drawRectHighlight(canvas, m.highlightBox, bmpW, bmpH, searchHighlightPaint)
                }
            }

            // 2. Draw user selection highlights
            val page = selectionController.page
            if (page != null && selectionController.hasSelection()) {
                val selLines = selectionController.getSelectedLineIds()
                val selTokens = selectionController.getSelectedTokenIds()

                for (block in page.sourceBlocks) {
                    for (line in block.lines) {
                        if (selLines.contains(line.lineId)) {
                            drawPolygonOrBoxHighlight(canvas, line.polygon, line.boundingBox, bmpW, bmpH)
                        } else if (line.tokens.isNotEmpty()) {
                            for (tok in line.tokens) {
                                if (selTokens.contains(tok.tokenId)) {
                                    drawPolygonOrBoxHighlight(canvas, tok.polygon, null, bmpW, bmpH)
                                }
                            }
                        }
                    }
                }
            }
        } else if (isImageMissing) {
            val centerX = width / 2f
            val centerY = height / 2f
            canvas.drawText("Không tìm thấy ảnh gốc bản quét", centerX, centerY, placeholderPaint)
        }
    }

    private fun drawRectHighlight(canvas: Canvas, box: OcrRect, bmpW: Float, bmpH: Float, fillPaint: Paint) {
        scratchRectF.set(box.left * bmpW, box.top * bmpH, box.right * bmpW, box.bottom * bmpH)
        drawMatrix.mapRect(scratchRectF)
        canvas.drawRect(scratchRectF, fillPaint)
    }

    private fun drawPolygonOrBoxHighlight(canvas: Canvas, polygon: OcrPolygon?, box: OcrRect?, bmpW: Float, bmpH: Float) {
        if (polygon != null && polygon.points.size >= 4) {
            scratchPath.reset()
            val pts = polygon.points
            val count = pts.size
            val arr = FloatArray(count * 2)
            for (i in 0 until count) {
                arr[i * 2] = pts[i].x * bmpW
                arr[i * 2 + 1] = pts[i].y * bmpH
            }
            drawMatrix.mapPoints(arr)

            scratchPath.moveTo(arr[0], arr[1])
            for (i in 1 until count) {
                scratchPath.lineTo(arr[i * 2], arr[i * 2 + 1])
            }
            scratchPath.close()

            canvas.drawPath(scratchPath, selectionFillPaint)
            canvas.drawPath(scratchPath, selectionStrokePaint)
        } else if (box != null) {
            scratchRectF.set(box.left * bmpW, box.top * bmpH, box.right * bmpW, box.bottom * bmpH)
            drawMatrix.mapRect(scratchRectF)
            canvas.drawRect(scratchRectF, selectionFillPaint)
            canvas.drawRect(scratchRectF, selectionStrokePaint)
        }
    }

    /**
     * Converts a touch coordinate in View pixel space to normalized [0..1] page coordinates.
     */
    fun viewToPageNormalized(viewX: Float, viewY: Float): Pair<Float, Float>? {
        val bmp = bitmap ?: return null
        val pts = floatArrayOf(viewX, viewY)
        inverseDrawMatrix.mapPoints(pts)
        val normX = (pts[0] / bmp.width.toFloat()).coerceIn(0f, 1f)
        val normY = (pts[1] / bmp.height.toFloat()).coerceIn(0f, 1f)
        return Pair(normX, normY)
    }

    /**
     * Converts a normalized [0..1] page coordinate to View pixel coordinates.
     */
    fun pageNormalizedToView(normX: Float, normY: Float): Pair<Float, Float>? {
        val bmp = bitmap ?: return null
        val pts = floatArrayOf(normX * bmp.width, normY * bmp.height)
        drawMatrix.mapPoints(pts)
        return Pair(pts[0], pts[1])
    }

    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent?): Boolean {
        val selectedText = selectionController.getSelectedText()
        if (selectedText.isNotBlank() && event != null) {
            event.text.add(selectedText)
            return true
        }
        return super.dispatchPopulateAccessibilityEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        bitmap = null
    }
}
