package com.surge.radar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.max

class SparkView(context: Context) : View(context) {

    var values: List<Double> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(230, 80, 220, 205)
        strokeWidth = 1.6f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(50, 64, 224, 208)
    }
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(120, 64, 224, 208)
        strokeWidth = density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    var lineColor: Int = 0xFF50DCCD.toInt()
        set(value) {
            field = value
            linePaint.color = value
            fillPaint.color = withAlpha(value, 0.2f)
            invalidate()
        }

    private val path = Path()
    private val fillPath = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val pad = 2f * density

        if (values.size < 2) {
            canvas.drawLine(pad, h * 0.7f, w - pad, h * 0.7f, basePaint)
            return
        }

        val mn = values.min()
        val mx = values.max()
        val range = max(0.2, mx - mn)

        path.reset()
        var x0 = 0f
        var y0 = 0f
        values.forEachIndexed { i, v ->
            val x = pad + (w - 2 * pad) * i / (values.size - 1)
            val k = ((v - mn) / range).toFloat()
            val y = h - pad - k * (h - 2 * pad)
            if (i == 0) {
                path.moveTo(x, y)
                x0 = x
                y0 = y
            } else path.lineTo(x, y)
        }
        fillPath.set(path)
        fillPath.lineTo(w - pad, h)
        fillPath.lineTo(pad, h)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
        canvas.drawCircle(x0, y0, 2.4f * density, dotPaint)
    }
}
