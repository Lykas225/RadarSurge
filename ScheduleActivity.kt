package com.surge.radar

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Отдельное меню «Все расписания»: прибытие самолётов в аэропорты, поездов на ж/д вокзалы
 * и междугородних автобусов на автовокзалы Москвы.
 */
class ScheduleActivity : AppCompatActivity() {

    private val ctx: Context get() = this
    private val matchP = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrapP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var p: Palette
    private lateinit var listBox: LinearLayout
    private lateinit var statusView: TextView
    private val tabViews = mutableListOf<Pair<Int, TextView>>()

    private var selectedMask = PoiKind.AIRPORT.bit or PoiKind.STATION.bit or PoiKind.BUS_STATION.bit

    private fun dp(v: Int): Int = dpi(v)

    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Palette(WidgetSettings.load(this).dark)
        buildUi()
        refreshSchedules(false)
    }

    private fun buildUi() {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // Верхняя навигация
        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = tv("‹ Назад", 15f, p.accent, true).apply {
            setPadding(0, dp(4), dp(16), dp(4))
            setOnClickListener { finish() }
        }
        val refreshBtn = tv("↻ Обновить", 14f, p.accent, true).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(withAlpha(p.accent, 0.14f), dp(12).toFloat(), withAlpha(p.accent, 0.45f), dp(1))
            setOnClickListener { refreshSchedules(true) }
        }
        topBar.addView(back, LinearLayout.LayoutParams(0, wrapP, 1f))
        topBar.addView(refreshBtn)
        root.addView(topBar)

        root.addView(tv("Все расписания прибытия", 24f, p.text, true).apply {
            setPadding(0, dp(8), 0, dp(2))
        })
        statusView = tv(ScheduleProvider.status(this), 12f, p.sub).apply {
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(statusView)

        // Вкладки фильтра по виду транспорта
        val tabsRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val allMask = PoiKind.AIRPORT.bit or PoiKind.STATION.bit or PoiKind.BUS_STATION.bit
        val tabs = listOf(
            "Все" to allMask,
            "✈ Аэропорты" to PoiKind.AIRPORT.bit,
            "🚆 Вокзалы" to PoiKind.STATION.bit,
            "🚌 Автовокзалы" to PoiKind.BUS_STATION.bit
        )
        tabViews.clear()
        tabs.forEachIndexed { idx, (label, mask) ->
            val chip = tv(label, 12.5f, p.text, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener {
                    selectedMask = mask
                    updateTabs()
                    renderList()
                }
            }
            tabViews.add(mask to chip)
            tabsRow.addView(
                chip,
                LinearLayout.LayoutParams(0, wrapP, 1f).apply {
                    if (idx < tabs.size - 1) rightMargin = dp(6)
                }
            )
        }
        root.addView(tabsRow, LinearLayout.LayoutParams(matchP, wrapP).apply { bottomMargin = dp(12) })
        updateTabs()

        val scroll = ScrollView(ctx)
        listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listBox, ViewGroup.LayoutParams(matchP, wrapP))
        root.addView(scroll, LinearLayout.LayoutParams(matchP, 0, 1f))

        setContentView(root)
    }

    private fun updateTabs() {
        for ((mask, view) in tabViews) {
            val active = mask == selectedMask
            view.setTextColor(if (active) Color.WHITE else p.text)
            view.background = if (active) {
                GradientDrawable().apply {
                    setColor(p.accent)
                    cornerRadius = dp(12).toFloat()
                }
            } else {
                rounded(p.card, dp(12).toFloat(), p.stroke, dp(1))
            }
        }
    }

    private fun refreshSchedules(force: Boolean) {
        val transportPois = PoiCatalog.byKinds(PoiKind.AIRPORT.bit or PoiKind.STATION.bit or PoiKind.BUS_STATION.bit)
        if (force) Toast.makeText(this, "Обновляем расписание прибытия…", Toast.LENGTH_SHORT).show()
        ScheduleProvider.refreshAsync(this, transportPois, force) {
            statusView.text = ScheduleProvider.status(this)
            renderList()
        }
        renderList()
    }

    private fun renderList() {
        listBox.removeAllViews()
        val now = System.currentTimeMillis()
        val entries = ScheduleProvider.upcomingArrivals(selectedMask, now - 10 * 60_000L, now + 6 * 3_600_000L)

        // 1. Сводка по узлам (аэропорты, ж/д вокзалы, автовокзалы) с нагрузкой на ближайшие 30 минут
        val hubs = PoiCatalog.byKinds(selectedMask)
        if (hubs.isNotEmpty()) {
            listBox.addView(tv("Транспортные узлы (ближайшие 30 мин)", 14f, p.sub, true).apply {
                setPadding(0, 0, 0, dp(6))
            })
            val tariffs = WidgetSettings.load(this).enabledTariffs()
            for (poi in hubs) {
                val count30 = ScheduleProvider.arrivalsBetween(poi, now - 10 * 60_000L, now + 30 * 60_000L)
                val zone = MoscowRegionGrid.nearest(poi.lat, poi.lon)
                val kind = if (poi.kind == PoiKind.AIRPORT) DemandModel.Kind.AIRPORT else DemandModel.Kind.STATION
                var base = maxOf(DemandModel.baseCoeff(zone, now), DemandModel.kindCoeff(kind, now))
                if (count30 != null) {
                    val per = when (poi.kind) {
                        PoiKind.AIRPORT -> 0.05
                        PoiKind.STATION -> 0.08
                        else -> 0.07
                    }
                    base += minOf(0.8, count30 * per)
                }
                val pct = DemandModel.maxPct(minOf(3.0, base), tariffs)
                val color = DemandModel.textColor(pct)

                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(p.card, dp(14).toFloat(), p.stroke, dp(1))
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }
                val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                info.addView(tv("${poi.kind.emoji} ${poi.name}", 15f, p.text, true))
                val subText = if (count30 != null) {
                    val unit = when (poi.kind) {
                        PoiKind.AIRPORT -> "прилётов"
                        PoiKind.STATION -> "поездов"
                        else -> "автобусов"
                    }
                    "Прибывает $unit за 30 мин: $count30"
                } else {
                    "Оценка потока по времени суток"
                }
                info.addView(tv(subText, 12f, if (count30 != null && count30 > 0) p.accent else p.sub).apply {
                    setPadding(0, dp(2), 0, 0)
                })
                card.addView(info, LinearLayout.LayoutParams(0, wrapP, 1f))

                val pill = tv(DemandModel.coefText(pct), 14f, color, true).apply {
                    gravity = Gravity.CENTER
                    minWidth = dp(52)
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    background = rounded(withAlpha(color, 0.15f), dp(10).toFloat(), withAlpha(color, 0.5f), dp(1))
                }
                card.addView(pill, LinearLayout.LayoutParams(wrapP, wrapP).apply { leftMargin = dp(8) })
                listBox.addView(card, LinearLayout.LayoutParams(matchP, wrapP).apply { bottomMargin = dp(6) })
            }
        }

        // 2. Поминутная лента рейсов прибытия (если задан ключ API Яндекс Расписаний)
        listBox.addView(tv("Ближайшие рейсы прибытия", 14f, p.sub, true).apply {
            setPadding(0, dp(10), 0, dp(6))
        })

        if (entries.isEmpty()) {
            val emptyMsg = if (ScheduleProvider.key(this).isBlank()) {
                "Чтобы видеть точное время каждого рейса (самолёты, поезда и автобусы), укажите бесплатный ключ API Яндекс Расписаний в Настройках."
            } else {
                "Нет рейсов в ближайшие часы или данные ещё загружаются."
            }
            val emptyCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(p.card, dp(14).toFloat(), p.stroke, dp(1))
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }
            emptyCard.addView(tv(emptyMsg, 13f, p.sub))
            listBox.addView(emptyCard, LinearLayout.LayoutParams(matchP, wrapP))
            return
        }

        for (item in entries.take(80)) {
            val diffMin = ((item.timeMs - now) / 60_000L).toInt()
            val badge = when {
                diffMin < 0 -> "прибыл ${-diffMin} м назад"
                diffMin == 0 -> "сейчас"
                diffMin < 60 -> "через $diffMin мин"
                else -> "через ${diffMin / 60} ч ${diffMin % 60} м"
            }

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(p.card, dp(14).toFloat(), p.stroke, dp(1))
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }

            // Время прибытия слева
            val timeCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            timeCol.addView(tv(DemandModel.mskTime(item.timeMs), 16f, p.accent, true))
            timeCol.addView(tv(badge, 10.5f, p.sub))
            row.addView(timeCol, LinearLayout.LayoutParams(dp(82), wrapP))

            // Название маршрута и вокзал/аэропорт
            val midCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val routeLabel = if (item.number.isNotEmpty()) "${item.number} · ${item.title}" else item.title
            midCol.addView(tv(routeLabel, 14f, p.text, true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            midCol.addView(tv("${item.poi.kind.emoji} ${item.poi.name}", 12f, p.sub).apply {
                setPadding(0, dp(2), 0, 0)
            })
            row.addView(midCol, LinearLayout.LayoutParams(0, wrapP, 1f).apply { leftMargin = dp(10) })

            listBox.addView(row, LinearLayout.LayoutParams(matchP, wrapP).apply { bottomMargin = dp(6) })
        }
    }
}
