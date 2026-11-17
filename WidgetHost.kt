package com.surge.radar

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/** Одно окно, три вида: мини-окошко, компактное окно, выпадающий список горячих точек. */
class WidgetHost(context: Context, cfg: WidgetSettings.Values) : FrameLayout(context) {

    enum class Mode { PILL, CARD, HOT }

    val pill = PillView(context, cfg)
    val card = RadarWidgetView(context, cfg)
    val hot = HotListView(context, cfg)

    var mode: Mode = Mode.PILL
        private set

    init {
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        addView(pill, FrameLayout.LayoutParams(wrap, wrap))
        addView(card, FrameLayout.LayoutParams(wrap, wrap))
        addView(hot, FrameLayout.LayoutParams(wrap, wrap))
        show(Mode.PILL)
    }

    fun show(m: Mode) {
        mode = m
        pill.visibility = if (m == Mode.PILL) View.VISIBLE else View.GONE
        card.visibility = if (m == Mode.CARD) View.VISIBLE else View.GONE
        hot.visibility = if (m == Mode.HOT) View.VISIBLE else View.GONE
    }
}
