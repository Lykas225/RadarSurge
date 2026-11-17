package com.surge.radar

import android.graphics.Color
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

data class Blip(val angle: Double, val dist: Float, val color: Int)

data class TariffRow(val name: String, val pct: Int, val waitMin: Int)

data class WidgetData(
    val zoneName: String,
    val rows: List<TariffRow>,
    val label: String,
    val history: List<Double>,
    val blips: List<Blip>,
    val orderLine: String,
    val destLine: String,
    val decision: String,
    val decisionColor: Int,
    val live: Boolean = false
) {
    companion object {
        fun empty() = WidgetData(
            "", emptyList(), "Загрузка…", emptyList(), emptyList(), "", "", "", Color.LTGRAY
        )

        fun sample(tariffs: List<Tariff>): WidgetData {
            val rnd = Random(7)
            val pcts = intArrayOf(45, 38, 30)
            val waits = intArrayOf(4, 5, 6)
            val colors = listOf(0xFFFF5252.toInt(), 0xFFFFA726.toInt(), 0xFF4CAF50.toInt())
            return WidgetData(
                zoneName = "Центр / Кремль",
                rows = tariffs.map { TariffRow(it.shortTitle, pcts[it.ordinal], waits[it.ordinal]) },
                label = "↗ пик 19:15 ×2.0",
                history = List(13) { 1.3 + 0.3 * sin(it / 2.0) },
                blips = List(12) {
                    Blip(rnd.nextDouble() * 2 * PI, 0.2f + rnd.nextFloat() * 0.75f, colors.random(rnd))
                },
                orderLine = "450 ₽ · 12 мин\n→ Жулебино ×1.6",
                destLine = "",
                decision = "МОЖНО БРАТЬ 62",
                decisionColor = 0xFFFFD740.toInt(),
                live = true
            )
        }
    }
}
