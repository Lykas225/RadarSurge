package com.surge.radar

import android.content.Context
import kotlin.math.max
import kotlin.math.min

class HotSpot(val poi: Poi, val pct: Int, val reason: String, val live: Boolean)

/**
 * Горячие места: аэропорты, ж/д вокзалы, автовокзалы и больницы.
 * Место попадает в список, когда его коэффициент дошёл до выбранного порога.
 * Аэропорты, ж/д вокзалы и автовокзалы усиливаются расписанием прибытий; больницы — сменой персонала.
 */
object HotSpots {

    fun refreshAsync(ctx: Context, force: Boolean = false, onDone: (() -> Unit)? = null) {
        val cfg = WidgetSettings.load(ctx)
        if (!cfg.schedOnline) return
        val transportMask = PoiKind.AIRPORT.bit or PoiKind.STATION.bit or PoiKind.BUS_STATION.bit
        if ((cfg.hotCats and transportMask) != 0) {
            ScheduleProvider.refreshAsync(ctx, PoiCatalog.byKinds(cfg.hotCats), force, onDone)
        }
    }

    fun statusLine(ctx: Context): String =
        "Расписание (аэропорты, вокзалы, автовокзалы): ${ScheduleProvider.status(ctx)}"

    fun compute(ctx: Context, cfg: WidgetSettings.Values, now: Long = System.currentTimeMillis()): List<HotSpot> {
        val tariffs = cfg.enabledTariffs()
        if (tariffs.isEmpty()) return emptyList()
        val result = ArrayList<HotSpot>()
        val min10 = 10 * 60_000L
        val min30 = 30 * 60_000L

        for (poi in PoiCatalog.byKinds(cfg.hotCats)) {
            val zone = MoscowRegionGrid.nearest(poi.lat, poi.lon)
            var base = DemandModel.baseCoeff(zone, now)
            var reason = ""
            var live = false

            when (poi.kind) {
                PoiKind.AIRPORT, PoiKind.STATION, PoiKind.BUS_STATION -> {
                    val kind = when (poi.kind) {
                        PoiKind.AIRPORT -> DemandModel.Kind.AIRPORT
                        else -> DemandModel.Kind.STATION
                    }
                    base = max(base, DemandModel.kindCoeff(kind, now))
                    val n = ScheduleProvider.arrivalsBetween(poi, now - min10, now + min30)
                    if (n != null) {
                        val per = when (poi.kind) {
                            PoiKind.AIRPORT -> 0.05
                            PoiKind.STATION -> 0.08
                            else -> 0.07
                        }
                        val word = when (poi.kind) {
                            PoiKind.AIRPORT -> "прилётов"
                            PoiKind.STATION -> "поездов"
                            else -> "автобусов"
                        }
                        base += min(0.8, n * per)
                        reason = "$word за 30 мин: $n"
                        live = true
                    } else {
                        reason = if (poi.kind == PoiKind.BUS_STATION) "поток междугородних рейсов" else "по времени суток"
                    }
                }
                PoiKind.HOSPITAL -> {
                    val m = DemandModel.mskMinutes(now)
                    when {
                        m in 450..555 || m in 1170..1260 -> {
                            base += 0.45
                            reason = "смена персонала"
                        }
                        m in 930..1140 -> {
                            base += 0.2
                            reason = "часы посещений"
                        }
                        else -> reason = "обычный поток"
                    }
                }
            }

            val pct = DemandModel.maxPct(min(3.0, base), tariffs)
            if (pct >= cfg.hotMin) result.add(HotSpot(poi, pct, reason, live))
        }
        return result.sortedByDescending { it.pct }
    }
}
