package com.surge.radar

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Компактное окно: по строке на тариф с коэффициентом и ожиданием.
 * Раскрывается из мини-окошка по тапу и сворачивается само.
 */
class RadarWidgetView(context: Context, private val cfg: WidgetSettings.Values) : LinearLayout(context) {

    var onClose: (() -> Unit)? = null

    private val f = cfg.scale / 100f
    private val fk = cfg.fontPct / 100f
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    private fun px(v: Float): Int = (v * resources.displayMetrics.density * f).roundToInt()

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private fun tv(t: String, size: Float, color: Int, strong: Boolean = false): TextView =
        TextView(context).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size * f * fk)
            setTextColor(color)
            typeface = weightTypeface(if (strong) min(900, cfg.fontWeight + 200) else cfg.fontWeight)
            includeFontPadding = false
            setPadding(0, px(1f), 0, px(1f))
        }

    private val zoneView = tv("", 10.5f, 0xFF93A1B0.toInt(), true).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = px(66f)
    }
    private val liveDot = tv("●", 8f, 0xFF93A1B0.toInt())
    private val rowsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val radar = RadarView(context, false)
    private val spark = SparkView(context)

    private val close = tv("✕", 13f, 0xFF93A1B0.toInt()).apply {
        setPadding(px(8f), px(2f), px(1f), px(2f))
        setOnClickListener { onClose?.invoke() }
    }

    init {
        orientation = LinearLayout.VERTICAL
        val bgA = (cfg.bgAlpha * 255 / 100).coerceIn(0, 255)
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.argb(bgA, 27, 35, 44), Color.argb(bgA, 18, 23, 29))
        ).apply {
            cornerRadius = px(16f).toFloat()
            setStroke(px(1.1f).coerceAtLeast(1), Color.argb((bgA * 0.75f).toInt(), 77, 182, 172))
        }
        setPadding(px(8f), px(6f), px(8f), px(7f))
        alpha = cfg.widgetAlpha / 100f
        minimumWidth = px(118f)

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = if (cfg.showRadar) px(24f) else px(14f)
        header.addView(radar, lp(icon, icon))
        header.addView(zoneView, lp(0, wrap, 1f).apply { leftMargin = px(5f) })
        header.addView(liveDot, lp(wrap, wrap).apply { leftMargin = px(4f) })
        header.addView(close)
        addView(header, lp(match, wrap))

        addView(rowsBox, lp(match, wrap).apply { topMargin = px(3f) })
        if (cfg.showGraph) addView(spark, lp(match, px(18f)).apply { topMargin = px(3f) })
    }

    private fun makeRow(r: TariffRow): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(1.5f), 0, px(1.5f))
        }
        row.addView(tv(r.name, 12f, 0xFFE8EDF2.toInt()), lp(0, wrap, 1f))
        if (cfg.showDemand) {
            val color = DemandModel.textColor(r.pct)
            val pill = tv(DemandModel.coefText(r.pct), 12f, color, true).apply {
                gravity = Gravity.CENTER
                minWidth = px(40f)
                setPadding(px(5f), px(1f), px(5f), px(1f))
                background = rounded(
                    withAlpha(color, 0.16f), px(8f).toFloat(),
                    withAlpha(color, 0.55f), px(1f).coerceAtLeast(1)
                )
            }
            row.addView(pill, lp(wrap, wrap).apply { leftMargin = px(5f) })
        }
        if (cfg.showWait) {
            val w = tv("${r.waitMin}м", 10.5f, 0xFF93A1B0.toInt()).apply {
                gravity = Gravity.END
                minWidth = px(16f)
            }
            row.addView(w, lp(wrap, wrap).apply { leftMargin = px(5f) })
        }
        return row
    }

    fun bind(d: WidgetData) {
        zoneView.text = d.zoneName.removePrefix("МО: ").substringBefore(" / ")
        liveDot.setTextColor(if (d.live) 0xFF6BCB9B.toInt() else 0xFF93A1B0.toInt())

        rowsBox.removeAllViews()
        if (d.rows.isEmpty()) {
            rowsBox.addView(tv("Тарифы выключены", 11f, Color.LTGRAY))
        } else {
            for (r in d.rows) rowsBox.addView(makeRow(r))
        }

        val top = d.rows.maxOfOrNull { it.pct } ?: 0
        spark.lineColor = if (top >= 15) DemandModel.heatColor(top) else 0xFF4DB6AC.toInt()
        radar.blips = if (cfg.showRadar) d.blips else emptyList()
        spark.values = d.history
    }
}
