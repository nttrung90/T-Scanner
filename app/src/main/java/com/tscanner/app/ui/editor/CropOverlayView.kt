package com.tscanner.app.ui.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private enum class TouchHandle {
        NONE,
        CORNER_TOP_LEFT,
        CORNER_TOP_RIGHT,
        CORNER_BOTTOM_LEFT,
        CORNER_BOTTOM_RIGHT,
        EDGE_TOP,
        EDGE_BOTTOM,
        EDGE_LEFT,
        EDGE_RIGHT,
        PAN
    }

    private val density = context.resources.displayMetrics.density
    private val touchRadius = 36f * density
    private val minCropSize = 60f * density
    private val cornerLength = 22f * density
    private val edgeBarLength = 24f * density

    var imageBounds: RectF = RectF()
        private set

    val cropRect: RectF = RectF()

    private var activeHandle = TouchHandle.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val scrimPaint = Paint().apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint().apply {
        color = Color.parseColor("#00C28E")
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        isAntiAlias = true
    }

    private val guidelinePaint = Paint().apply {
        color = Color.parseColor("#55FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        isAntiAlias = true
    }

    private val cornerPaint = Paint().apply {
        color = Color.parseColor("#00C28E")
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val edgeBarPaint = Paint().apply {
        color = Color.parseColor("#00C28E")
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    fun setImageBounds(bounds: RectF) {
        imageBounds.set(bounds)
        // Default crop rect: 90% of image bounds centered
        val insetX = imageBounds.width() * 0.05f
        val insetY = imageBounds.height() * 0.05f
        cropRect.set(
            imageBounds.left + insetX,
            imageBounds.top + insetY,
            imageBounds.right - insetX,
            imageBounds.bottom - insetY
        )
        invalidate()
    }

    fun resetToFull() {
        if (!imageBounds.isEmpty) {
            cropRect.set(imageBounds)
            invalidate()
        }
    }

    /**
     * Returns normalized crop bounds relative to imageBounds (0.0 to 1.0)
     */
    fun getNormalizedCropRect(): RectF {
        if (imageBounds.width() <= 0 || imageBounds.height() <= 0) {
            return RectF(0f, 0f, 1f, 1f)
        }
        val left = ((cropRect.left - imageBounds.left) / imageBounds.width()).coerceIn(0f, 1f)
        val top = ((cropRect.top - imageBounds.top) / imageBounds.height()).coerceIn(0f, 1f)
        val right = ((cropRect.right - imageBounds.left) / imageBounds.width()).coerceIn(0f, 1f)
        val bottom = ((cropRect.bottom - imageBounds.top) / imageBounds.height()).coerceIn(0f, 1f)
        return RectF(left, top, right, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageBounds.isEmpty || cropRect.isEmpty) return

        // 1. Draw outer darkened scrim (top, bottom, left, right of cropRect within imageBounds)
        // Top rect
        canvas.drawRect(imageBounds.left, imageBounds.top, imageBounds.right, cropRect.top, scrimPaint)
        // Bottom rect
        canvas.drawRect(imageBounds.left, cropRect.bottom, imageBounds.right, imageBounds.bottom, scrimPaint)
        // Left rect
        canvas.drawRect(imageBounds.left, cropRect.top, cropRect.left, cropRect.bottom, scrimPaint)
        // Right rect
        canvas.drawRect(cropRect.right, cropRect.top, imageBounds.right, cropRect.bottom, scrimPaint)

        // 2. Draw 3x3 Guidelines
        val colStep = cropRect.width() / 3f
        val rowStep = cropRect.height() / 3f
        canvas.drawLine(cropRect.left + colStep, cropRect.top, cropRect.left + colStep, cropRect.bottom, guidelinePaint)
        canvas.drawLine(cropRect.left + colStep * 2, cropRect.top, cropRect.left + colStep * 2, cropRect.bottom, guidelinePaint)
        canvas.drawLine(cropRect.left, cropRect.top + rowStep, cropRect.right, cropRect.top + rowStep, guidelinePaint)
        canvas.drawLine(cropRect.left, cropRect.top + rowStep * 2, cropRect.right, cropRect.top + rowStep * 2, guidelinePaint)

        // 3. Draw Crop Rectangle Border
        canvas.drawRect(cropRect, borderPaint)

        // 4. Draw 4 Corner Handles (L-shaped brackets)
        val cl = cornerLength.coerceAtMost(cropRect.width() / 3f).coerceAtMost(cropRect.height() / 3f)
        // Top-Left
        canvas.drawLine(cropRect.left, cropRect.top, cropRect.left + cl, cropRect.top, cornerPaint)
        canvas.drawLine(cropRect.left, cropRect.top, cropRect.left, cropRect.top + cl, cornerPaint)
        // Top-Right
        canvas.drawLine(cropRect.right - cl, cropRect.top, cropRect.right, cropRect.top, cornerPaint)
        canvas.drawLine(cropRect.right, cropRect.top, cropRect.right, cropRect.top + cl, cornerPaint)
        // Bottom-Left
        canvas.drawLine(cropRect.left, cropRect.bottom, cropRect.left + cl, cropRect.bottom, cornerPaint)
        canvas.drawLine(cropRect.left, cropRect.bottom - cl, cropRect.left, cropRect.bottom, cornerPaint)
        // Bottom-Right
        canvas.drawLine(cropRect.right - cl, cropRect.bottom, cropRect.right, cropRect.bottom, cornerPaint)
        canvas.drawLine(cropRect.right, cropRect.bottom - cl, cropRect.right, cropRect.bottom, cornerPaint)

        // 5. Draw 4 Edge Midpoint Handles (Horizontal/Vertical drag indicators)
        val cx = cropRect.centerX()
        val cy = cropRect.centerY()
        val el = edgeBarLength.coerceAtMost(cropRect.width() / 4f)
        val eh = edgeBarLength.coerceAtMost(cropRect.height() / 4f)

        // Top Edge Handle (Horizontal Bar)
        canvas.drawLine(cx - el, cropRect.top, cx + el, cropRect.top, edgeBarPaint)
        // Bottom Edge Handle (Horizontal Bar)
        canvas.drawLine(cx - el, cropRect.bottom, cx + el, cropRect.bottom, edgeBarPaint)
        // Left Edge Handle (Vertical Bar)
        canvas.drawLine(cropRect.left, cy - eh, cropRect.left, cy + eh, edgeBarPaint)
        // Right Edge Handle (Vertical Bar)
        canvas.drawLine(cropRect.right, cy - eh, cropRect.right, cy + eh, edgeBarPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeHandle = getHitHandle(x, y)
                if (activeHandle != TouchHandle.NONE) {
                    lastTouchX = x
                    lastTouchY = y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeHandle != TouchHandle.NONE) {
                    val dx = x - lastTouchX
                    val dy = y - lastTouchY
                    applyMove(activeHandle, dx, dy)
                    lastTouchX = x
                    lastTouchY = y
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeHandle != TouchHandle.NONE) {
                    activeHandle = TouchHandle.NONE
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun getHitHandle(x: Float, y: Float): TouchHandle {
        // 1. Check 4 corners first (highest priority)
        if (hypot(x - cropRect.left, y - cropRect.top) < touchRadius) return TouchHandle.CORNER_TOP_LEFT
        if (hypot(x - cropRect.right, y - cropRect.top) < touchRadius) return TouchHandle.CORNER_TOP_RIGHT
        if (hypot(x - cropRect.left, y - cropRect.bottom) < touchRadius) return TouchHandle.CORNER_BOTTOM_LEFT
        if (hypot(x - cropRect.right, y - cropRect.bottom) < touchRadius) return TouchHandle.CORNER_BOTTOM_RIGHT

        // 2. Check 4 edges (allow dragging the entire edge line anywhere along the border!)
        val inXBounds = x in (cropRect.left - touchRadius)..(cropRect.right + touchRadius)
        val inYBounds = y in (cropRect.top - touchRadius)..(cropRect.bottom + touchRadius)

        if (abs(y - cropRect.top) < touchRadius && inXBounds) return TouchHandle.EDGE_TOP
        if (abs(y - cropRect.bottom) < touchRadius && inXBounds) return TouchHandle.EDGE_BOTTOM
        if (abs(x - cropRect.left) < touchRadius && inYBounds) return TouchHandle.EDGE_LEFT
        if (abs(x - cropRect.right) < touchRadius && inYBounds) return TouchHandle.EDGE_RIGHT

        // 3. Check inside for PAN (drag entire rectangle)
        if (cropRect.contains(x, y)) return TouchHandle.PAN

        return TouchHandle.NONE
    }

    private fun safeClamp(value: Float, minVal: Float, maxVal: Float): Float {
        return if (minVal <= maxVal) {
            value.coerceIn(minVal, maxVal)
        } else {
            (minVal + maxVal) / 2f
        }
    }

    private fun applyMove(handle: TouchHandle, dx: Float, dy: Float) {
        val minW = minCropSize.coerceAtMost(imageBounds.width() * 0.5f).coerceAtLeast(10f)
        val minH = minCropSize.coerceAtMost(imageBounds.height() * 0.5f).coerceAtLeast(10f)

        when (handle) {
            TouchHandle.EDGE_TOP -> {
                cropRect.top = safeClamp(cropRect.top + dy, imageBounds.top, cropRect.bottom - minH)
            }
            TouchHandle.EDGE_BOTTOM -> {
                cropRect.bottom = safeClamp(cropRect.bottom + dy, cropRect.top + minH, imageBounds.bottom)
            }
            TouchHandle.EDGE_LEFT -> {
                cropRect.left = safeClamp(cropRect.left + dx, imageBounds.left, cropRect.right - minW)
            }
            TouchHandle.EDGE_RIGHT -> {
                cropRect.right = safeClamp(cropRect.right + dx, cropRect.left + minW, imageBounds.right)
            }
            TouchHandle.CORNER_TOP_LEFT -> {
                cropRect.left = safeClamp(cropRect.left + dx, imageBounds.left, cropRect.right - minW)
                cropRect.top = safeClamp(cropRect.top + dy, imageBounds.top, cropRect.bottom - minH)
            }
            TouchHandle.CORNER_TOP_RIGHT -> {
                cropRect.right = safeClamp(cropRect.right + dx, cropRect.left + minW, imageBounds.right)
                cropRect.top = safeClamp(cropRect.top + dy, imageBounds.top, cropRect.bottom - minH)
            }
            TouchHandle.CORNER_BOTTOM_LEFT -> {
                cropRect.left = safeClamp(cropRect.left + dx, imageBounds.left, cropRect.right - minW)
                cropRect.bottom = safeClamp(cropRect.bottom + dy, cropRect.top + minH, imageBounds.bottom)
            }
            TouchHandle.CORNER_BOTTOM_RIGHT -> {
                cropRect.right = safeClamp(cropRect.right + dx, cropRect.left + minW, imageBounds.right)
                cropRect.bottom = safeClamp(cropRect.bottom + dy, cropRect.top + minH, imageBounds.bottom)
            }
            TouchHandle.PAN -> {
                var newLeft = cropRect.left + dx
                var newTop = cropRect.top + dy
                var newRight = cropRect.right + dx
                var newBottom = cropRect.bottom + dy

                // Clamp to image bounds
                if (newLeft < imageBounds.left) {
                    val shift = imageBounds.left - newLeft
                    newLeft += shift
                    newRight += shift
                }
                if (newRight > imageBounds.right) {
                    val shift = newRight - imageBounds.right
                    newLeft -= shift
                    newRight -= shift
                }
                if (newTop < imageBounds.top) {
                    val shift = imageBounds.top - newTop
                    newTop += shift
                    newBottom += shift
                }
                if (newBottom > imageBounds.bottom) {
                    val shift = newBottom - imageBounds.bottom
                    newTop -= shift
                    newBottom -= shift
                }

                cropRect.set(newLeft, newTop, newRight, newBottom)
            }
            TouchHandle.NONE -> {}
        }
    }
}
