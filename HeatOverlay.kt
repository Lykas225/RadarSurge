package com.surge.radar

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class HeatItem(val zone: SurgeZone, val pct: Int)

/** Флажок на карте: показывает спрос в точке и исчезает через ttlMs. */
class Flag(
    val lat: Double,
    val lon: Double,
    val zone: SurgeZone,
    val createdAt: Long,
    val ttlMs: Long,
    val title: String,
    val lines: List<Pair<String, Int>>,
    val color: Int
) {
    val bounds = RectF()
}

/**
 * Тепловая карта спроса: мягкое свечение зон, расходящиеся кольца на горячих зонах,
 * подписи-«таблетки», маркер выбранной зоны и моё местоположение.
 */
class HeatOverlay : Overlay() {

    var items: List<HeatItem> = emptyList()
    var glow: Float = 0.7f
    var phase: Float = 0f
    var animated: Boolean = true
    var dark: Boolean = true
    var pinned: SurgeZone? = null
    var myLocation: GeoPoint? = null
    var flags: List<Flag> = emptyList()
    var onTap: ((Double, Double) -> Unit)? = null
    var onLongTap: ((Double, Double) -> Unit)? = null
    var onFlagTap: ((Flag) -> Unit)? = null
    var onDouble: (() -> Unit)? = null

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val rect = RectF()
    private val flagPath = Path()
    private val shaders = HashMap<Int, RadialGradient>()

    /** Единичный градиент: рисуется через масштаб canvas, поэтому не создаётся заново на каждый кадр. */
    private fun unitShader(color: Int): RadialGradient = shaders.getOrPut(color) {
        RadialGradient(
            0f, 0f, 1f,
            intArrayOf(withAlpha(color, 0.9f), withAlpha(color, 0.5f), withAlpha(color, 0f)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun drawPill(canvas: Canvas, text: String, cx: Float, cy: Float, textColor: Int, bg: Int, d: Float) {
        val tw = textPaint.measureText(text)
        val padX = 6f * d
        val h = textPaint.textSize + 7f * d
        rect.set(cx - tw / 2f - padX, cy - h / 2f, cx + tw / 2f + padX, cy + h / 2f)
        fillPaint.color = bg
        canvas.drawRoundRect(rect, h / 2f, h / 2f, fillPaint)
        textPaint.color = textColor
        canvas.drawText(text, cx, cy + textPaint.textSize * 0.35f, textPaint)
    }

    override fun draw(canvas: Canvas?, mapView: MapView?, shadow: Boolean) {
        if (shadow || canvas == null || mapView == null) return
        val d = mapView.context.resources.displayMetrics.density
        val proj = mapView.projection
        val pt = Point()
        val pt2 = Point()
        val w = mapView.width.toFloat()
        val h = mapView.height.toFloat()
        val zoom = mapView.zoomLevelDouble
        val labelFrom = when {
            zoom >= 11.5 -> 15
            zoom >= 10.5 -> 40
            zoom >= 9.5 -> 70
            else -> 101
        }
        val pillBg = if (dark) withAlpha(0xFF06121C.toInt(), 0.78f) else withAlpha(Color.WHITE, 0.9f)
        ringPaint.strokeWidth = 1.4f * d

        items.forEachIndexed { idx, item ->
            val z = item.zone
            proj.toPixels(GeoPoint(z.lat, z.lon), pt)
            proj.toPixels(GeoPoint(z.lat + z.radiusMeters / 111_320.0, z.lon), pt2)
            val r = abs(pt.y - pt2.y).toFloat()
            val x = pt.x.toFloat()
            val y = pt.y.toFloat()
            if (r < 3f || x + r < 0f || x - r > w || y + r < 0f || y - r > h) return@forEachIndexed

            if (item.pct < 10) {
                if (zoom >= 10.0) {
                    fillPaint.color = withAlpha(0xFF2DD4BF.toInt(), 0.4f)
                    canvas.drawCircle(x, y, min(r * 0.1f, 4f * d), fillPaint)
                }
                return@forEachIndexed
            }

            val color = DemandModel.heatColor(item.pct)
            val strength = glow * min(1f, 0.4f + item.pct / 100f)

            // мягкое свечение
            glowPaint.shader = unitShader(color)
            glowPaint.alpha = (255f * strength).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.translate(x, y)
            canvas.scale(r, r)
            canvas.drawCircle(0f, 0f, 1f, glowPaint)
            canvas.restore()
            glowPaint.shader = null

            // статичное кольцо
            ringPaint.color = withAlpha(color, 0.55f * strength)
            canvas.drawCircle(x, y, r * 0.62f, ringPaint)

            // расходящееся кольцо на горячих зонах
            if (animated && item.pct >= 40) {
                val k = (phase + idx * 0.137f) % 1f
                ringPaint.color = withAlpha(color, (1f - k) * 0.6f * strength)
                canvas.drawCircle(x, y, r * (0.25f + 0.75f * k), ringPaint)
            }

            if (item.pct >= labelFrom) {
                textPaint.textSize = 11f * d
                drawPill(canvas, DemandModel.coefText(item.pct), x, y, color, pillBg, d)
            }
        }

        // выбранная зона
        pinned?.let { z ->
            proj.toPixels(GeoPoint(z.lat, z.lon), pt)
            val x = pt.x.toFloat()
            val y = pt.y.toFloat()
            val k = if (animated) phase else 0.5f
            ringPaint.strokeWidth = 2f * d
            ringPaint.color = withAlpha(0xFF1FD1C0.toInt(), (1f - k) * 0.7f)
            canvas.drawCircle(x, y, (10f + 16f * k) * d, ringPaint)
            ringPaint.color = Color.WHITE
            canvas.drawCircle(x, y, 9f * d, ringPaint)
            fillPaint.color = 0xFF1FD1C0.toInt()
            canvas.drawCircle(x, y, 4.5f * d, fillPaint)
            textPaint.textSize = 11f * d
            drawPill(canvas, z.name.removePrefix("МО: "), x, y - 26f * d, Color.WHITE, withAlpha(0xFF0E8F84.toInt(), 0.95f), d)
        }

        // моё местоположение
        myLocation?.let { gp ->
            proj.toPixels(gp, pt)
            val x = pt.x.toFloat()
            val y = pt.y.toFloat()
            fillPaint.color = withAlpha(0xFF2979FF.toInt(), 0.18f)
            canvas.drawCircle(x, y, 20f * d, fillPaint)
            fillPaint.color = Color.WHITE
            canvas.drawCircle(x, y, 8f * d, fillPaint)
            fillPaint.color = 0xFF2979FF.toInt()
            canvas.drawCircle(x, y, 5.5f * d, fillPaint)
        }

        drawFlags(canvas, proj, d, w)
    }

    private fun drawFlags(canvas: Canvas, proj: org.osmdroid.views.Projection, d: Float, viewW: Float) {
        if (flags.isEmpty()) return
        val pt = Point()
        val textMain = if (dark) Color.WHITE else 0xFF14212B.toInt()
        val bubbleBg = if (dark) withAlpha(0xFF08141E.toInt(), 0.94f) else withAlpha(Color.WHITE, 0.96f)
        val nowMs = System.currentTimeMillis()

        for (fl in flags) {
            proj.toPixels(GeoPoint(fl.lat, fl.lon), pt)
            val x = pt.x.toFloat()
            val y = pt.y.toFloat()
            val frac = (1f - (nowMs - fl.createdAt).toFloat() / fl.ttlMs.toFloat()).coerceIn(0f, 1f)

            // древко и полотнище
            ringPaint.color = textMain
            ringPaint.strokeWidth = 2f * d
            canvas.drawLine(x, y, x, y - 30f * d, ringPaint)
            flagPath.reset()
            flagPath.moveTo(x, y - 30f * d)
            flagPath.lineTo(x + 18f * d, y - 24.5f * d)
            flagPath.lineTo(x, y - 19f * d)
            flagPath.close()
            fillPaint.color = fl.color
            canvas.drawPath(flagPath, fillPaint)

            // основание и обратный отсчёт
            fillPaint.color = textMain
            canvas.drawCircle(x, y, 3f * d, fillPaint)
            ringPaint.color = fl.color
            ringPaint.strokeWidth = 2.4f * d
            rect.set(x - 9f * d, y - 9f * d, x + 9f * d, y + 9f * d)
            canvas.drawArc(rect, -90f, 360f * frac, false, ringPaint)

            // облачко со спросом
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.textSize = 12f * d
            val pad = 8f * d
            val lh = 16f * d
            var maxW = textPaint.measureText(fl.title)
            for (ln in fl.lines) maxW = max(maxW, textPaint.measureText(ln.first))
            val bw = maxW + 2f * pad
            val bh = (fl.lines.size + 1) * lh + 2f * pad - 4f * d
            val left = (x - bw / 2f).coerceIn(4f * d, max(4f * d, viewW - bw - 4f * d))
            val top = y - 36f * d - bh
            rect.set(left, top, left + bw, top + bh)
            fillPaint.color = bubbleBg
            canvas.drawRoundRect(rect, 10f * d, 10f * d, fillPaint)
            ringPaint.color = withAlpha(fl.color, 0.9f)
            ringPaint.strokeWidth = 1.3f * d
            canvas.drawRoundRect(rect, 10f * d, 10f * d, ringPaint)

            textPaint.color = textMain
            canvas.drawText(fl.title, left + pad, top + pad + lh * 0.78f, textPaint)
            fl.lines.forEachIndexed { i, ln ->
                textPaint.color = ln.second
                canvas.drawText(ln.first, left + pad, top + pad + lh * (i + 1) + lh * 0.78f, textPaint)
            }
            textPaint.textAlign = Paint.Align.CENTER

            fl.bounds.set(
                min(left, x - 12f * d), top, max(left + bw, x + 12f * d), y + 12f * d
            )
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent?, mapView: MapView?): Boolean {
        if (e == null || mapView == null) return false
        for (i in flags.indices.reversed()) {
            if (flags[i].bounds.contains(e.x, e.y)) {
                onFlagTap?.invoke(flags[i])
                return true
            }
        }
        val gp = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt())
        onTap?.invoke(gp.latitude, gp.longitude)
        return true
    }

    /** Двойной тап открывает горячие места, поэтому приближение по двойному тапу отключено (щипок работает). */
    override fun onDoubleTap(e: MotionEvent?, mapView: MapView?): Boolean {
        onDouble?.invoke()
        return true
    }

    override fun onLongPress(e: MotionEvent?, mapView: MapView?): Boolean {
        if (e == null || mapView == null) return false
        val gp = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt())
        onLongTap?.invoke(gp.latitude, gp.longitude)
        return true
    }
}
