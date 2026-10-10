package com.dramaflow.app.presentation

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * A complete white loading ring. The dim full circle guarantees the loader never reads as a
 * clipped or incomplete arc, while the rotating bright segment preserves a clear loading cue.
 */
internal class WhiteLoadingRingView(context: Context) : View(context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        alpha = 88
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bounds = RectF()
    private val rotationAnimator = ObjectAnimator.ofFloat(this, View.ROTATION, 0f, 360f).apply {
        duration = 950L
        repeatCount = ObjectAnimator.INFINITE
        interpolator = LinearInterpolator()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!rotationAnimator.isStarted) rotationAnimator.start()
    }

    override fun onDetachedFromWindow() {
        rotationAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = (width.coerceAtMost(height) * 0.095f).coerceAtLeast(1f)
        trackPaint.strokeWidth = stroke
        activePaint.strokeWidth = stroke
        val inset = stroke / 2f + 1f
        bounds.set(inset, inset, width - inset, height - inset)
        canvas.drawOval(bounds, trackPaint)
        canvas.drawArc(bounds, -92f, 76f, false, activePaint)
    }
}
