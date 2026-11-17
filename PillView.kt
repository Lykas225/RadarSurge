package com.surge.radar

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Мини-окошко: по строке на каждый выбранный тариф (название и коэффициент) для места, где вы находитесь.
 */
class PillView(context: Context, private val cfg: WidgetSettings.Values) : LinearLayout(context) {

    private val f = cfg.scale / 100f
    private val fk = cfg.fontPct / 100f
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val bgAlpha255 = (cfg.bgAlpha * 255 / 100).coerceIn(0, 255)

    private fun px(v: Float): Int = (v * resources.displayMetrics.density * f).roundToInt()

    private fun tv(t: String, size: Float, color: Int, strong: Boolean): TextView =
        TextView(context).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size * f * fk)
            setTextColor(color)
            typeface = weightTypeface(if (strong) min(900, cfg.fontWeight + 200) else cfg.fontWeight)
            includeFontPadding = false
        }

    private val rowsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val dot = tv("●", 8f, 0xFF93A1B0.toInt(), false)
    private val arrowView = tv("", 11f, 0xFF7AD1C8.toInt(), true)
    private val frame = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.argb(bgAlpha255, 27, 35, 44), Color.argb(bgAlpha255, 18, 23, 29))
    ).apply { cornerRadius = px(13f).toFloat() }

    init {
        orientation = LinearLayout.HORIZONTAL
        background = frame
        setPadding(px(8f), px(5f), px(8f), px(5f))
        alpha = cfg.widgetAlpha / 100f
        addView(rowsBox, LinearLayout.LayoutParams(wrap, wrap))

        val side = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        side.addView(dot)
        side.addView(arrowView, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = px(3f) })
        addView(side, LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = px(6f) })
        setBorder(0xFF4DB6AC.toInt())
    }

    private fun setBorder(color: Int) {
        val a = (bgAlpha255 * 0.85f).toInt().coerceIn(0, 255)
        frame.setStroke(px(1.2f).coerceAtLeast(1), Color.argb(a, Color.red(color), Color.green(color), Color.blue(color)))
    }

    private fun makeRow(r: TariffRow): LinearLayout {
        val color = DemandModel.textColor(r.pct)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(1f), 0, px(1f))
        }
        row.addView(tv(r.name, 11f, 0xFFE8EDF2.toInt(), false).apply { minWidth = px(24f) })
        row.addView(
            tv(DemandModel.coefText(r.pct), 12.5f, color, true),
            LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = px(5f) }
        )
        return row
    }

    fun bind(rows: List<TariffRow>, arrow: String, live: Boolean) {
        dot.setTextColor(if (live) 0xFF6BCB9B.toInt() else 0xFF93A1B0.toInt())
        arrowView.text = arrow
        rowsBox.removeAllViews()
        if (rows.isEmpty()) {
            rowsBox.addView(tv("—", 13f, Color.LTGRAY, true))
            setBorder(0xFF4DB6AC.toInt())
            return
        }
        for (r in rows) rowsBox.addView(makeRow(r))
        val top = rows.maxOf { it.pct }
        setBorder(if (top >= 15) DemandModel.heatColor(top) else 0xFF4DB6AC.toInt())
    }
}
