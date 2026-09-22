package com.dramaflow.app.presentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * A non-animated cooldown ring. The dark track is always visible and the gold
 * arc grows in direct proportion to elapsed cooldown time; it never uses the
 * platform indeterminate spinner.
 */
internal class ChargeCooldownRingView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        color = 0xFF3B3630.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        color = COLOR_SUNSET
    }

    var chargeProgress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = trackPaint.strokeWidth / 2f + density
        val oval = RectF(inset, inset, width - inset, height - inset)
        canvas.drawOval(oval, trackPaint)
        if (chargeProgress > 0f) {
            canvas.drawArc(oval, -90f, 360f * chargeProgress, false, fillPaint)
        }
    }
}
