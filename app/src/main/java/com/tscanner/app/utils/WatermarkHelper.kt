package com.tscanner.app.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

object WatermarkHelper {

    const val WATERMARK_TEXT = "T-Scanner"
    const val FONT_SIZE_PT = 13f

    /**
     * Checks if watermark should be applied.
     * Free tier always applies watermark; VIP tier removes watermark.
     */
    fun shouldApplyWatermark(context: Context): Boolean {
        return !AppAuthManager.isUserVip()
    }

    /**
     * Draws the "T-Scanner" watermark at the bottom-right corner of the canvas.
     * Font size is 13pt scaled proportionally to the page dimensions (relative to standard A4 595pt).
     */
    fun drawBottomRightWatermark(
        canvas: Canvas,
        pageWidth: Float,
        pageHeight: Float,
        text: String = WATERMARK_TEXT
    ) {
        val baseDim = pageWidth.coerceAtMost(pageHeight)
        val scale = (baseDim / 595f).coerceAtLeast(1f)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(165, 115, 115, 115) // subtle, clear gray
            textSize = FONT_SIZE_PT * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
            letterSpacing = 0.03f
        }

        val marginRight = 22f * scale
        val marginBottom = 18f * scale

        val x = pageWidth - marginRight
        val y = pageHeight - marginBottom

        canvas.drawText(text, x, y, textPaint)
    }

    /**
     * HTML snippet for Word (.doc) footer with 13pt font size aligned right.
     */
    fun getWordWatermarkHtml(text: String = WATERMARK_TEXT): String {
        return "<div style='text-align: right; margin-top: 30px; font-size: 13pt; color: #888888; font-style: italic; font-family: Calibri, Arial, sans-serif;'>$text</div>"
    }

    /**
     * HTML snippet for PPT Presentation slide footer with 13pt font size at bottom-right corner.
     */
    fun getPptWatermarkHtml(text: String = WATERMARK_TEXT): String {
        return "<div style='position: absolute; right: 24px; bottom: 16px; font-size: 13pt; color: #888888; font-style: italic; font-family: sans-serif;'>$text</div>"
    }
}
