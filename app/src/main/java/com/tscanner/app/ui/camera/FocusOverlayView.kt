package com.tscanner.app.ui.camera

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

class FocusOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density
    private var focusX = 0f
    private var focusY = 0f
    private var focusRadius = 0f
    private var focusAlpha = 0
    private var isFocusing = false

    private val baseRadius = 38f * density
    private var animator: ValueAnimator? = null

    // Vòng tròn lấy nét
    private val focusPaint = Paint().apply {
        color = Color.parseColor("#00C28E")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        isAntiAlias = true
    }

    // Khung hướng dẫn căn tài liệu A4 trên màn hình
    private var showGuideFrame = true
    private val guideFrameRect = RectF()
    private val guidePaint = Paint().apply {
        color = Color.parseColor("#44FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(12f * density, 8f * density), 0f)
        isAntiAlias = true
    }

    private val cornerPaint = Paint().apply {
        color = Color.parseColor("#00C28E")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    fun showFocusAt(x: Float, y: Float) {
        animator?.cancel()
        focusX = x
        focusY = y
        isFocusing = true
        focusAlpha = 255

        animator = ValueAnimator.ofFloat(1.35f, 0.9f).apply {
            duration = 320
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { va ->
                val scale = va.animatedValue as Float
                focusRadius = baseRadius * scale
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    postDelayed({
                        animate().alpha(0f).setDuration(400).withEndAction {
                            isFocusing = false
                            alpha = 1f
                            invalidate()
                        }.start()
                    }, 400)
                }
            })
            start()
        }
    }

    private var isIdCardAspectRatio = false

    fun setIdCardMode(enabled: Boolean) {
        isIdCardAspectRatio = enabled
        updateGuideFrame(width, height)
        invalidate()
    }

    fun setGuideFrameVisible(visible: Boolean) {
        showGuideFrame = visible
        invalidate()
    }

    private fun updateGuideFrame(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (isIdCardAspectRatio) {
            // Standard ID-1 card aspect ratio (85.60 mm / 53.98 mm = 1.586)
            val cardW = w * 0.88f
            val cardH = cardW / 1.586f
            val left = (w - cardW) / 2f
            val top = (h - cardH) / 2f
            guideFrameRect.set(left, top, left + cardW, top + cardH)
        } else {
            val marginH = w * 0.08f
            val marginV = h * 0.16f
            guideFrameRect.set(marginH, marginV, w - marginH, h - marginV)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGuideFrame(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Vẽ khung căn tài liệu (Guide Frame)
        if (showGuideFrame && !guideFrameRect.isEmpty) {
            canvas.drawRoundRect(guideFrameRect, 16f * density, 16f * density, guidePaint)

            // Vẽ 4 góc định vị màu xanh ngọc
            val cl = 28f * density
            val r = guideFrameRect

            // Góc trên-trái
            canvas.drawLine(r.left, r.top + cl, r.left, r.top + 8f * density, cornerPaint)
            canvas.drawLine(r.left + 8f * density, r.top, r.left + cl, r.top, cornerPaint)

            // Góc trên-phải
            canvas.drawLine(r.right - cl, r.top, r.right - 8f * density, r.top, cornerPaint)
            canvas.drawLine(r.right, r.top + 8f * density, r.right, r.top + cl, cornerPaint)

            // Góc dưới-trái
            canvas.drawLine(r.left, r.bottom - cl, r.left, r.bottom - 8f * density, cornerPaint)
            canvas.drawLine(r.left + 8f * density, r.bottom, r.left + cl, r.bottom, cornerPaint)

            // Góc dưới-phải
            canvas.drawLine(r.right - cl, r.bottom, r.right - 8f * density, r.bottom, cornerPaint)
            canvas.drawLine(r.right, r.bottom - 8f * density, r.right, r.bottom - cl, cornerPaint)
        }

        // 2. Vẽ vòng tròn lấy nét khi người dùng chạm
        if (isFocusing) {
            focusPaint.alpha = focusAlpha
            canvas.drawCircle(focusX, focusY, focusRadius, focusPaint)
        }
    }
}
