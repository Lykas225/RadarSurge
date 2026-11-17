package com.surge.radar

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Оценка спроса без Яндекс Про: профиль по часам и дню недели, тип района, погода.
 * Если Яндекс Про открыт и недавно прислал реальный коэффициент, он подмешивается в расчёт.
 */
object DemandModel {
    enum class Kind { CENTER, RESIDENTIAL, SUBURB, STATION, AIRPORT }

    private val centerWd = doubleArrayOf(
        0.35, 0.25, 0.15, 0.10, 0.10, 0.15, 0.25, 0.40, 0.55, 0.65, 0.55, 0.55,
        0.60, 0.60, 0.55, 0.60, 0.75, 0.95, 1.00, 0.95, 0.80, 0.70, 0.65, 0.50
    )
    private val centerWe = doubleArrayOf(
        0.85, 0.90, 0.80, 0.55, 0.30, 0.15, 0.10, 0.10, 0.15, 0.20, 0.30, 0.40,
        0.50, 0.55, 0.55, 0.60, 0.65, 0.70, 0.80, 0.85, 0.85, 0.85, 0.90, 0.90
    )
    private val residentialWd = doubleArrayOf(
        0.25, 0.15, 0.10, 0.10, 0.15, 0.40, 0.75, 0.95, 1.00, 0.80, 0.50, 0.40,
        0.40, 0.40, 0.40, 0.45, 0.50, 0.55, 0.60, 0.55, 0.50, 0.45, 0.40, 0.30
    )
    private val residentialWe = doubleArrayOf(
        0.45, 0.35, 0.25, 0.20, 0.15, 0.15, 0.15, 0.20, 0.25, 0.30, 0.40, 0.50,
        0.55, 0.55, 0.50, 0.50, 0.50, 0.55, 0.60, 0.65, 0.60, 0.60, 0.60, 0.55
    )
    private val station = doubleArrayOf(
        0.40, 0.30, 0.25, 0.20, 0.25, 0.40, 0.60, 0.80, 0.85, 0.75, 0.60, 0.55,
        0.55, 0.55, 0.55, 0.60, 0.70, 0.85, 0.90, 0.80, 0.70, 0.60, 0.50, 0.45
    )
    private val airport = doubleArrayOf(
        0.60, 0.50, 0.45, 0.50, 0.60, 0.70, 0.75, 0.70, 0.60, 0.55, 0.50, 0.50,
        0.50, 0.55, 0.55, 0.60, 0.65, 0.70, 0.75, 0.70, 0.70, 0.70, 0.65, 0.60
    )

    fun kindOf(zone: SurgeZone): Kind {
        val n = zone.name
        return when {
            n.startsWith("Аэропорт") -> Kind.AIRPORT
            n.startsWith("Комсомольская") || n.startsWith("Белорусская") -> Kind.STATION
            n.startsWith("Центр") || n.startsWith("Сити") || n.startsWith("Таганка") ||
                n.startsWith("Парк Культуры") || n.startsWith("Фили") || n.startsWith("Красносельская") ||
                n.startsWith("Даниловский") || n.startsWith("Лефортово") -> Kind.CENTER
            n.startsWith("МО:") || n == "Зеленоград" || n == "Троицк" ||
                n == "Щербинка" || n == "Московский" -> Kind.SUBURB
            else -> Kind.RESIDENTIAL
        }
    }

    private const val MSK_OFFSET_MS = 3 * 3_600_000L
    private const val DAY_MS = 86_400_000L
    private val remoteCache = ConcurrentHashMap<String, Double>()

    /** Время по Москве (UTC+3, без перехода на летнее) в виде ЧЧ:ММ. */
    fun mskTime(timeMs: Long): String {
        val m = Math.floorMod((timeMs + MSK_OFFSET_MS) / 60_000L, 1440L).toInt()
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
    }

    private fun zoneFactor(zone: SurgeZone): Double =
        0.9 + ((zone.name.hashCode() and 0x7fffffff) % 100) / 500.0

    private fun remoteFactor(zone: SurgeZone): Double = remoteCache.getOrPut(zone.name) {
        val dist = MoscowRegionGrid.distanceKm(55.7558, 37.6173, zone.lat, zone.lon)
        if (dist > 70) 0.7 else if (dist > 40) 0.8 else 0.9
    }

    /** Минуты от начала суток по Москве. */
    fun mskMinutes(timeMs: Long): Int =
        Math.floorMod((timeMs + MSK_OFFSET_MS) / 60_000L, 1440L).toInt()

    fun kindIntensity(kind: Kind, timeMs: Long): Double {
        val local = timeMs + MSK_OFFSET_MS
        val days = Math.floorDiv(local, DAY_MS)
        val msOfDay = Math.floorMod(local, DAY_MS)
        val dow = Math.floorMod(days + 3L, 7L).toInt() // 0 = понедельник
        val hourF = msOfDay / 3_600_000.0
        val hour = hourF.toInt()
        val weekend = dow >= 5 || (dow == 4 && hour >= 20) || (dow == 0 && hour < 5)

        val arr = when (kind) {
            Kind.CENTER -> if (weekend) centerWe else centerWd
            Kind.RESIDENTIAL, Kind.SUBURB -> if (weekend) residentialWe else residentialWd
            Kind.STATION -> station
            Kind.AIRPORT -> airport
        }
        val i0 = hour % 24
        val i1 = (i0 + 1) % 24
        val frac = hourF - hour
        return arr[i0] * (1.0 - frac) + arr[i1] * frac
    }

    /** Коэффициент места «по времени суток» (для аэропортов, вокзалов и автовокзалов), без расписаний. */
    fun kindCoeff(kind: Kind, timeMs: Long): Double {
        val x = (kindIntensity(kind, timeMs) + WeatherProvider.boost()).coerceIn(0.0, 1.3)
        return (1.0 + 1.6 * max(0.0, x - 0.35)).coerceIn(1.0, 3.0)
    }

    fun intensity(zone: SurgeZone, timeMs: Long): Double {
        val kind = kindOf(zone)
        val v = kindIntensity(kind, timeMs)
        return if (kind == Kind.SUBURB) v * remoteFactor(zone) else v
    }

    /** Коэффициент уровня Эконом (1.0 = нет надбавки). */
    fun baseCoeff(
        zone: SurgeZone,
        timeMs: Long = System.currentTimeMillis(),
        useObserved: Boolean = true
    ): Double {
        val x = (intensity(zone, timeMs) * zoneFactor(zone) + WeatherProvider.boost()).coerceIn(0.0, 1.3)
        var c = 1.0 + 1.6 * max(0.0, x - 0.35)
        if (useObserved) {
            RadarEngine.freshCoeff(zone.name, 30 * 60_000L)?.let { c = 0.35 * c + 0.65 * it }
        }
        return c.coerceIn(1.0, 3.0)
    }

    fun scale(base: Double, t: Tariff): Double = 1.0 + (base - 1.0) * t.k

    private val coefTexts = Array(401) { String.format(Locale.US, "×%.1f", 1.0 + it / 100.0) }

    /** Коэффициент для показа: 87% надбавки это ×1.9. */
    fun coefText(pct: Int): String = coefTexts[pct.coerceIn(0, 400)]

    fun pct(coeff: Double): Int = ((coeff - 1.0) * 100.0).roundToInt().coerceAtLeast(0)

    fun maxPct(base: Double, tariffs: List<Tariff>): Int =
        if (tariffs.isEmpty()) 0 else pct(tariffs.maxOf { scale(base, it) })

    fun waitMinutes(coeff: Double): Int = (8.0 / coeff.pow(1.6)).roundToInt().coerceIn(2, 12)

    fun forecast(zone: SurgeZone, tariff: Tariff, startMs: Long, steps: Int, stepMs: Long): List<Double> =
        List(steps) { i -> scale(baseCoeff(zone, startMs + i * stepMs, false), tariff) }

    fun label(pct: Int): String = when {
        pct >= 70 -> "Пиковая активность"
        pct >= 40 -> "Высокий спрос"
        pct >= 15 -> "Растущий спрос"
        else -> "Спокойно"
    }

    /** Мягкие, не режущие глаз цвета для индикаторов спроса. */
    fun textColor(pct: Int): Int = when {
        pct >= 100 -> 0xFFC792EA.toInt() // мягкий лавандово-сливовый
        pct >= 60 -> 0xFFEB986C.toInt()  // тёплый терракотово-персиковый
        pct >= 15 -> 0xFF6BCB9B.toInt()  // спокойный мятно-шалфейный
        else -> 0xFF9AA8B6.toInt()       // нейтральный серо-голубой
    }

    fun heatColor(pct: Int): Int = when {
        pct >= 70 -> 0xFFE06D64.toInt()  // приглушённый коралловый
        pct >= 40 -> 0xFFE59B5E.toInt()  // мягкий янтарный
        pct >= 15 -> 0xFFD9B85B.toInt()  // тёплый песочно-золотой
        else -> 0xFF4DB6AC.toInt()       // спокойный бирюзовый
    }
}
