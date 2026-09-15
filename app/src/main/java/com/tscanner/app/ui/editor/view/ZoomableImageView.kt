package com.tscanner.app.ui.editor.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView hỗ trợ cử chỉ zoom 2 ngón (pinch-to-zoom 1.0x - 4.0x), kéo lướt (pan)
 * và double-tap phóng nhanh để kiểm tra nét chữ và dấu thanh tiếng Việt.
 * Hỗ trợ callback phát hiện vùng nhìn thấy (viewport) phục vụ render chi tiết Level 2.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val currentMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    private var minScale = 1.0f
    private var maxScale = 4.0f
    private var currentScale = 1.0f

    var isZoomingEnabled: Boolean = true

    private var onViewportChanged: ((RectF, Float) -> Unit)? = null
    private val debounceHandler = Handler(Looper.getMainLooper())
    private val debounceRunnable = Runnable {
        notifyViewportChanged()
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!isZoomingEnabled) return false

            val factor = detector.scaleFactor
            val targetScale = currentScale * factor
            val clampedScale = targetScale.coerceIn(minScale, maxScale)
            val actualFactor = clampedScale / currentScale

            currentScale = clampedScale
            currentMatrix.postScale(actualFactor, actualFactor, detector.focusX, detector.focusY)
            clampMatrixTranslation()
            imageMatrix = currentMatrix
            scheduleViewportNotification()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (!isZoomingEnabled || currentScale <= 1.05f) return false

            currentMatrix.postTranslate(-distanceX, -distanceY)
            clampMatrixTranslation()
            imageMatrix = currentMatrix
            scheduleViewportNotification()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!isZoomingEnabled) return false

            if (currentScale > 1.2f) {
                // Thu về 1.0x
                resetZoom()
            } else {
                // Phóng to 2.5x tại điểm chạm
                zoomTo(2.5f, e.x, e.y)
            }
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    fun setOnViewportChangedListener(listener: (RectF, Float) -> Unit) {
        this.onViewportChanged = listener
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        post {
            fitImageToView()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitImageToView()
    }

    fun fitImageToView() {
        val d = drawable ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0 || viewH <= 0) return

        val imgW = d.intrinsicWidth.toFloat()
        val imgH = d.intrinsicHeight.toFloat()
        if (imgW <= 0 || imgH <= 0) return

        currentMatrix.reset()
        val scale = minOf(viewW / imgW, viewH / imgH)
        currentMatrix.postScale(scale, scale)

        val dx = (viewW - imgW * scale) / 2f
        val dy = (viewH - imgH * scale) / 2f
        currentMatrix.postTranslate(dx, dy)

        currentScale = 1.0f
        imageMatrix = currentMatrix
        notifyViewportChanged()
    }

    fun resetZoom() {
        fitImageToView()
    }

    private fun zoomTo(targetScale: Float, focusX: Float, focusY: Float) {
        val factor = targetScale / currentScale
        currentScale = targetScale
        currentMatrix.postScale(factor, factor, focusX, focusY)
        clampMatrixTranslation()
        imageMatrix = currentMatrix
        scheduleViewportNotification()
    }

    private fun clampMatrixTranslation() {
        val d = drawable ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0 || viewH <= 0) return

        currentMatrix.getValues(matrixValues)
        val scaleX = matrixValues[Matrix.MSCALE_X]
        val scaleY = matrixValues[Matrix.MSCALE_Y]
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]

        val drawnW = d.intrinsicWidth * scaleX
        val drawnH = d.intrinsicHeight * scaleY

        var newTransX = transX
        var newTransY = transY

        if (drawnW <= viewW) {
            newTransX = (viewW - drawnW) / 2f
        } else {
            val minX = viewW - drawnW
            val maxX = 0f
            newTransX = transX.coerceIn(minX, maxX)
        }

        if (drawnH <= viewH) {
            newTransY = (viewH - drawnH) / 2f
        } else {
            val minY = viewH - drawnH
            val maxY = 0f
            newTransY = transY.coerceIn(minY, maxY)
        }

        matrixValues[Matrix.MTRANS_X] = newTransX
        matrixValues[Matrix.MTRANS_Y] = newTransY
        currentMatrix.setValues(matrixValues)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isZoomingEnabled) return super.onTouchEvent(event)

        var handled = scaleDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled
        return handled || super.onTouchEvent(event)
    }

    private fun scheduleViewportNotification() {
        debounceHandler.removeCallbacks(debounceRunnable)
        debounceHandler.postDelayed(debounceRunnable, 150)
    }

    private fun notifyViewportChanged() {
        val d = drawable ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0 || viewH <= 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return

        currentMatrix.getValues(matrixValues)
        val scaleX = matrixValues[Matrix.MSCALE_X]
        val scaleY = matrixValues[Matrix.MSCALE_Y]
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]

        val imgW = d.intrinsicWidth.toFloat()
        val imgH = d.intrinsicHeight.toFloat()

        // Tính toán vùng chữ nhật hiển thị chuẩn hóa [0..1]
        val visibleLeft = ((-transX) / (imgW * scaleX)).coerceIn(0f, 1f)
        val visibleTop = ((-transY) / (imgH * scaleY)).coerceIn(0f, 1f)
        val visibleRight = ((viewW - transX) / (imgW * scaleX)).coerceIn(0f, 1f)
        val visibleBottom = ((viewH - transY) / (imgH * scaleY)).coerceIn(0f, 1f)

        val normRect = RectF(visibleLeft, visibleTop, visibleRight, visibleBottom)
        onViewportChanged?.invoke(normRect, currentScale)
    }

    fun getCurrentDisplayBounds(): RectF {
        val d = drawable ?: return RectF(0f, 0f, width.toFloat(), height.toFloat())
        currentMatrix.getValues(matrixValues)
        val scaleX = matrixValues[Matrix.MSCALE_X]
        val scaleY = matrixValues[Matrix.MSCALE_Y]
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]
        val w = d.intrinsicWidth * scaleX
        val h = d.intrinsicHeight * scaleY
        return RectF(transX, transY, transX + w, transY + h)
    }
}
