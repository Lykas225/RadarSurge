package com.surge.radar

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class RadarView(context: Context, private val animated: Boolean = true) : View(context) {

    var blips: List<Blip> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(150, 6, 40, 48)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(120, 64, 224, 208)
        strokeWidth = density
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(90, 64, 224, 208)
    }
    private val sweepLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(220, 120, 255, 230)
        strokeWidth = 1.5f * density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var sweep = 0f

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 3500L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            sweep = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animated) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - density
        if (r <= 0f) return

        canvas.drawCircle(cx, cy, r, fillPaint)
        for (i in 1..4) canvas.drawCircle(cx, cy, r * i / 4f, ringPaint)
        canvas.drawLine(cx - r, cy, cx + r, cy, ringPaint)
        canvas.drawLine(cx, cy - r, cx, cy + r, ringPaint)

        if (animated) {
            rect.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(rect, sweep - 50f, 50f, true, sweepPaint)
            val rad = Math.toRadians(sweep.toDouble())
            canvas.drawLine(
                cx, cy,
                cx + (cos(rad) * r).toFloat(), cy + (sin(rad) * r).toFloat(),
                sweepLine
            )
        }

        val dotR = max(2f * density, r * 0.07f)
        for (b in blips) {
            dotPaint.color = b.color
            canvas.drawCircle(
                cx + (cos(b.angle) * b.dist * r).toFloat(),
                cy + (sin(b.angle) * b.dist * r).toFloat(),
                dotR, dotPaint
            )
        }
    }
}
