package com.surge.radar

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Расширенное выпадающее меню горячих мест, открывающееся по двойному тапу на виджете.
 * Увеличенный размер, спокойная цветовая гамма, удобная читаемость и прокрутка.
 */
class HotListView(context: Context, private val cfg: WidgetSettings.Values) : LinearLayout(context) {

    var onOpenSchedule: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private val f = cfg.scale / 100f
    private val fk = cfg.fontPct / 100f
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    private fun px(v: Float): Int = (v * resources.displayMetrics.density * f).roundToInt()

    private fun tv(t: String, size: Float, color: Int, strong: Boolean = false): TextView =
        TextView(context).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size * f * fk)
            setTextColor(color)
            typeface = weightTypeface(if (strong) min(900, cfg.fontWeight + 200) else cfg.fontWeight)
            includeFontPadding = false
        }

    private val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val subtitleView = tv("", 10.5f, 0xFF93A1B0.toInt(), false)

    init {
        orientation = LinearLayout.VERTICAL
        val a = (cfg.bgAlpha * 255 / 100).coerceIn(210, 255)
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                android.graphics.Color.argb(a, 24, 32, 41),
                android.graphics.Color.argb(a, 18, 23, 29)
            )
        ).apply {
            cornerRadius = px(18f).toFloat()
            setStroke(px(1.2f).coerceAtLeast(1), android.graphics.Color.argb(a, 77, 182, 172))
        }
        setPadding(px(14f), px(12f), px(14f), px(12f))
        minimumWidth = px(256f)

        // Шапка меню с кнопкой закрытия
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            tv("🔥 Горячие точки", 14.5f, 0xFFE8EDF2.toInt(), true),
            LinearLayout.LayoutParams(0, wrap, 1f)
        )
        val closeBtn = tv("✕", 14f, 0xFF93A1B0.toInt(), true).apply {
            setPadding(px(8f), px(2f), px(2f), px(2f))
            setOnClickListener { onClose?.invoke() }
        }
        header.addView(closeBtn)
        addView(header, LinearLayout.LayoutParams(match, wrap))

        subtitleView.setPadding(0, px(2f), 0, px(6f))
        addView(subtitleView)

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            addView(box, ViewGroup.LayoutParams(match, wrap))
        }
        addView(scroll, LinearLayout.LayoutParams(match, px(220f)))

        // Кнопка перехода к отдельному меню «Все расписания»
        val schedBtn = tv("📅 Все расписания прибытия →", 12f, 0xFF4DB6AC.toInt(), true).apply {
            gravity = Gravity.CENTER
            setPadding(px(10f), px(8f), px(10f), px(8f))
            background = rounded(
                withAlpha(0xFF4DB6AC.toInt(), 0.14f),
                px(12f).toFloat(),
                withAlpha(0xFF4DB6AC.toInt(), 0.45f),
                px(1f).coerceAtLeast(1)
            )
            setOnClickListener { onOpenSchedule?.invoke() }
        }
        addView(schedBtn, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(8f) })
    }

    fun bind(list: List<HotSpot>, minPct: Int) {
        subtitleView.text = "Аэропорты, ж/д, автовокзалы и больницы от ${DemandModel.coefText(minPct)}"
        box.removeAllViews()
        if (list.isEmpty()) {
            box.addView(
                tv("Сейчас нет точек от ${DemandModel.coefText(minPct)}", 12.5f, 0xFF93A1B0.toInt()).apply {
                    setPadding(0, px(12f), 0, px(12f))
                }
            )
            return
        }
        for (h in list) {
            val color = DemandModel.textColor(h.pct)
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(8f), px(6f), px(8f), px(6f))
                background = rounded(withAlpha(0xFF2B3642.toInt(), 0.55f), px(11f).toFloat())
            }
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tv("${h.poi.kind.emoji} ${h.poi.name}", 13f, 0xFFE8EDF2.toInt(), true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = px(165f)
            })
            col.addView(
                tv(
                    h.reason,
                    11f,
                    if (h.live) 0xFF7AD1C8.toInt() else 0xFF93A1B0.toInt()
                ).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    maxWidth = px(165f)
                    setPadding(0, px(2f), 0, 0)
                }
            )
            row.addView(col, LinearLayout.LayoutParams(0, wrap, 1f))
            val pill = tv(DemandModel.coefText(h.pct), 13.5f, color, true).apply {
                gravity = Gravity.CENTER
                minWidth = px(48f)
                setPadding(px(7f), px(3f), px(7f), px(3f))
                background = rounded(
                    withAlpha(color, 0.16f),
                    px(9f).toFloat(),
                    withAlpha(color, 0.55f),
                    px(1f).coerceAtLeast(1)
                )
            }
            row.addView(pill, LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = px(8f) })
            box.addView(row, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = px(5f) })
        }
    }
}
