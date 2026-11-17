package com.surge.radar

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/** Конкретный рейс прибытия (самолёт, поезд или автобус) для отображения в меню расписания. */
data class ArrivalEntry(
    val poi: Poi,
    val timeMs: Long,
    val number: String,
    val title: String
)

/**
 * Прилёты самолётов, прибытия поездов и автобусов на автовокзалы по расписанию Яндекс Расписаний
 * (нужен бесплатный ключ API).
 */
object ScheduleProvider {
    private val BASES = listOf("https://api.rasp.yandex-net.ru/v3.0", "https://api.rasp.yandex.net/v3.0")
    private const val PREFS = "rasp"
    private const val DAILY_LIMIT = 400
    private val MSK = ZoneId.of("Europe/Moscow")

    private val arrivals = ConcurrentHashMap<String, LongArray>()
    private val detailedArrivals = ConcurrentHashMap<String, List<ArrivalEntry>>()

    @Volatile private var fetchedAt = 0L
    @Volatile private var fetchedDay = ""
    @Volatile private var fetching = false
    @Volatile private var baseIdx = 0
    @Volatile var lastError: String = ""
        private set

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun key(c: Context): String = prefs(c).getString("key", "") ?: ""

    fun setKey(c: Context, k: String) {
        prefs(c).edit().putString("key", k.trim()).remove("codes").apply()
        arrivals.clear()
        detailedArrivals.clear()
        fetchedAt = 0L
        fetchedDay = ""
        lastError = ""
    }

    fun arrivalsBetween(poi: Poi, fromMs: Long, toMs: Long): Int? {
        val a = arrivals[poi.name] ?: return null
        return a.count { it in fromMs..toMs }
    }

    /** Ближайшие рейсы прибытия по выбранным категориям (аэропорты, ж/д вокзалы, автовокзалы). */
    fun upcomingArrivals(
        kindMask: Int = (PoiKind.AIRPORT.bit or PoiKind.STATION.bit or PoiKind.BUS_STATION.bit),
        fromMs: Long = System.currentTimeMillis() - 10 * 60_000L,
        toMs: Long = System.currentTimeMillis() + 6 * 3_600_000L
    ): List<ArrivalEntry> {
        val out = ArrayList<ArrivalEntry>()
        for ((_, list) in detailedArrivals) {
            for (item in list) {
                if ((item.poi.kind.bit and kindMask) != 0 && item.timeMs in fromMs..toMs) {
                    out.add(item)
                }
            }
        }
        return out.sortedBy { it.timeMs }
    }

    fun hasLoadedData(): Boolean = detailedArrivals.values.any { it.isNotEmpty() }

    fun status(c: Context): String = when {
        key(c).isBlank() -> "ключ API не задан (работает расчёт по времени суток)"
        lastError.isNotEmpty() -> "ошибка: $lastError"
        arrivals.isEmpty() -> "ещё не загружено"
        else -> "Яндекс Расписания • запросов сегодня: ${used(c)}"
    }

    private fun dateOf(ms: Long): String = Instant.ofEpochMilli(ms).atZone(MSK).toLocalDate().toString()

    private fun today(): String = LocalDate.now(MSK).toString()

    private fun used(c: Context): Int {
        val p = prefs(c)
        return if (p.getString("day", "") == today()) p.getInt("count", 0) else 0
    }

    private fun spend(c: Context) {
        val p = prefs(c)
        val d = today()
        val n = if (p.getString("day", "") == d) p.getInt("count", 0) else 0
        p.edit().putString("day", d).putInt("count", n + 1).apply()
    }

    private fun friendly(e: Exception): String = when (e) {
        is SocketTimeoutException -> "нет ответа сервера Яндекса (проверьте интернет или VPN)"
        is UnknownHostException -> "нет сети"
        is IOException -> "ошибка сети"
        else -> e.message?.take(50) ?: "ошибка"
    }

    /** Запрос с запасным адресом: если один домен Яндекса не отвечает, пробуем второй. */
    private fun get(c: Context, path: String): JSONObject {
        if (used(c) >= DAILY_LIMIT) throw Exception("лимит запросов на сегодня")
        spend(c)
        var last: IOException? = null
        for (k in BASES.indices) {
            val idx = (baseIdx + k) % BASES.size
            try {
                val conn = URL(BASES[idx] + path).openConnection() as HttpURLConnection
                conn.connectTimeout = 12000
                conn.readTimeout = 12000
                try {
                    val code = conn.responseCode
                    if (code == 401 || code == 403) throw Exception("ключ не принят")
                    if (code != 200) throw Exception("HTTP $code")
                    val json = JSONObject(conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
                    baseIdx = idx
                    return json
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("нет ответа")
    }

    private fun norm(s: String): String = s.lowercase().replace('ё', 'е')

    private fun stationTypeFor(kind: PoiKind): String = when (kind) {
        PoiKind.AIRPORT -> "airport"
        PoiKind.BUS_STATION -> "bus_station"
        else -> "train_station"
    }

    private fun transportTypeFor(kind: PoiKind): String = when (kind) {
        PoiKind.AIRPORT -> "plane"
        PoiKind.BUS_STATION -> "bus"
        else -> "train"
    }

    private fun codeFor(c: Context, poi: Poi, key: String): String? {
        val p = prefs(c)
        val codes = JSONObject(p.getString("codes", "{}") ?: "{}")
        codes.optString(poi.name, "").takeIf { it.isNotEmpty() }?.let { return it }

        val stType = stationTypeFor(poi.kind)
        val trType = transportTypeFor(poi.kind)
        val dist = if (poi.kind == PoiKind.BUS_STATION) 5 else 3
        val path = "/nearest_stations/?apikey=$key&lat=${poi.lat}&lng=${poi.lon}&distance=$dist&limit=15" +
            "&station_types=$stType&transport_types=$trType&lang=ru_RU&format=json"
        val arr = get(c, path).optJSONArray("stations") ?: return null
        var best: String? = null
        for (i in 0 until arr.length()) {
            val st = arr.optJSONObject(i) ?: continue
            val code = st.optString("code", "")
            if (code.isEmpty()) continue
            if (best == null) best = code
            val title = norm(st.optString("title", "") + " " + st.optString("popular_title", ""))
            if (poi.match.isNotEmpty() && title.contains(norm(poi.match))) {
                best = code
                break
            }
        }
        if (best != null) {
            codes.put(poi.name, best)
            p.edit().putString("codes", codes.toString()).apply()
        }
        return best
    }

    private fun fetchDate(
        c: Context,
        code: String,
        poi: Poi,
        key: String,
        date: String,
        now: Long,
        outTimes: MutableList<Long>,
        outEntries: MutableList<ArrivalEntry>
    ) {
        val tt = transportTypeFor(poi.kind)
        var offset = 0
        var prevFirst = Long.MIN_VALUE
        for (page in 0 until 8) {
            val path = "/schedule/?apikey=$key&station=$code&event=arrival&date=$date" +
                "&transport_types=$tt&limit=100&offset=$offset&lang=ru_RU&format=json"
            val arr = get(c, path).optJSONArray("schedule") ?: break
            if (arr.length() == 0) break
            var first = Long.MIN_VALUE
            var last = 0L
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val s = item.optString("arrival", "")
                if (s.isEmpty() || s == "null") continue
                val t = runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull() ?: continue
                if (first == Long.MIN_VALUE) first = t
                last = max(last, t)
                outTimes.add(t)

                val thread = item.optJSONObject("thread")
                val num = thread?.optString("number", "")?.trim().orEmpty()
                val title = thread?.optString("title", "")?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: thread?.optString("short_title", "")?.trim()
                    ?: "Рейс прибытия"
                outEntries.add(ArrivalEntry(poi, t, num, title))
            }
            if (first == prevFirst) break
            prevFirst = first
            if (arr.length() < 100 || last > now + 4 * 3_600_000L) break
            offset += 100
        }
    }

    /** Берём расписание на сегодня, а возле полуночи ещё и на соседние сутки. */
    private fun fetchArrivals(c: Context, code: String, poi: Poi, key: String, now: Long): Pair<LongArray, List<ArrivalEntry>> {
        val outTimes = ArrayList<Long>()
        val outEntries = ArrayList<ArrivalEntry>()
        val dates = linkedSetOf(dateOf(now - 15 * 60_000L), dateOf(now + 4 * 3_600_000L))
        for (d in dates) fetchDate(c, code, poi, key, d, now, outTimes, outEntries)
        return outTimes.toLongArray() to outEntries.sortedBy { it.timeMs }
    }

    fun refreshAsync(c: Context, pois: List<Poi>, force: Boolean = false, onDone: (() -> Unit)? = null) {
        val app = c.applicationContext
        val key = key(app)
        val now = System.currentTimeMillis()
        if (key.isBlank() || fetching) return
        if (!force && now - fetchedAt < 90 * 60_000L && fetchedDay == dateOf(now)) return
        fetching = true
        Thread {
            try {
                for (poi in pois) {
                    if (poi.kind != PoiKind.AIRPORT &&
                        poi.kind != PoiKind.STATION &&
                        poi.kind != PoiKind.BUS_STATION
                    ) continue
                    val code = codeFor(app, poi, key) ?: continue
                    val (times, entries) = fetchArrivals(app, code, poi, key, now)
                    arrivals[poi.name] = times
                    detailedArrivals[poi.name] = entries
                }
                fetchedAt = now
                fetchedDay = dateOf(now)
                lastError = ""
            } catch (e: Exception) {
                lastError = friendly(e)
                fetchedAt = now - 50 * 60_000L
            } finally {
                fetching = false
                onDone?.let { cb -> android.os.Handler(android.os.Looper.getMainLooper()).post { cb() } }
            }
        }.start()
    }
}
