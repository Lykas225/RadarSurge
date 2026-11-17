package com.surge.radar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class RadarObservation(val timestamp: Long, val coeff: Double, val price: Double?, val durationMin: Int?, val zoneName: String?)

data class RadarPrediction(
    val probability: Int,
    val forecast5: Double,
    val forecast10: Double,
    val trendPerMin: Double,
    val confidence: Int,
    val label: String,
    val burstScore: Int,
    val decisionScore: Int,
    val decision: String,
    val reasons: List<String>
)

object RadarEngine {
    private const val PREFS = "radar_history"
    private const val KEY = "observations"
    private const val MAX_POINTS = 1200
    private val observations = ArrayDeque<RadarObservation>()

    @Synchronized fun init(context: Context) {
        if (observations.isNotEmpty()) return
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                observations.addLast(RadarObservation(o.getLong("t"), o.getDouble("c"), if (o.isNull("p")) null else o.getDouble("p"), if (o.isNull("d")) null else o.getInt("d"), if (o.isNull("z")) null else o.getString("z")))
            }
        }
    }

    @Synchronized fun record(context: Context, coeff: Double, price: Double?, durationMin: Int?, zoneName: String?): RadarPrediction {
        init(context)
        observations.addLast(RadarObservation(System.currentTimeMillis(), coeff, price, durationMin, zoneName))
        while (observations.size > MAX_POINTS) observations.removeFirst()
        if (observations.size % 10 == 0) persist(context)
        return predict(coeff, zoneName, price, durationMin)
    }

    @Synchronized fun predict(coeff: Double, zoneName: String? = null, price: Double? = null, durationMin: Int? = null): RadarPrediction {
        val now = System.currentTimeMillis()
        val relevant = observations.filter { now - it.timestamp <= 6 * 60 * 60 * 1000L && (zoneName == null || it.zoneName == null || it.zoneName == zoneName) }.takeLast(100)
        if (relevant.size < 4) {
            val risk = when { coeff >= 2.0 -> 65; coeff >= 1.5 -> 45; else -> 20 }
            return RadarPrediction(risk, coeff, coeff, 0.0, 10, "Мало истории", risk, risk, if (coeff >= 1.5) "Осторожно" else "Наблюдать", listOf("Нужно больше реальных наблюдений"))
        }

        val recent = relevant.takeLast(min(15, relevant.size))
        val first = recent.first()
        val last = recent.last()
        val minutes = max(0.5, (last.timestamp - first.timestamp) / 60000.0)
        val slope = (last.coeff - first.coeff) / minutes
        val shortSlope = if (recent.size >= 3) {
            val a = recent[recent.size - 3]; val b = recent.last()
            (b.coeff - a.coeff) / max(0.5, (b.timestamp - a.timestamp) / 60000.0)
        } else slope
        val previous = if (relevant.size >= 8) relevant.takeLast(8).dropLast(3) else emptyList()
        val previousMean = if (previous.isNotEmpty()) previous.map { it.coeff }.average() else recent.first().coeff
        val recentMean = recent.map { it.coeff }.average()
        val acceleration = (recentMean - previousMean) / max(0.1, minutes / 2.0)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val rush = if (hour in 7..10 || hour in 17..21) 1.0 else 0.0
        val baseline = historicalBaseline(hour, zoneName)
        val anomaly = max(0.0, recentMean - baseline)
        val persistence = recent.count { it.coeff >= 1.5 }.toDouble() / recent.size
        val rising = recent.zipWithNext().count { it.second.coeff > it.first.coeff }.toDouble() / max(1, recent.size - 1)

        val levelSignal = ((coeff - 1.15) / 1.35).coerceIn(0.0, 1.0)
        val slopeSignal = (shortSlope * 10.0 + 0.5).coerceIn(0.0, 1.0)
        val accelerationSignal = (acceleration * 8.0 + 0.5).coerceIn(0.0, 1.0)
        val anomalySignal = (anomaly / 0.8).coerceIn(0.0, 1.0)
        val logit = -2.15 + 2.0 * levelSignal + 1.8 * slopeSignal + 1.2 * accelerationSignal + 1.2 * anomalySignal + 0.9 * persistence + 0.7 * rising + 0.3 * rush
        val probability = (100.0 / (1.0 + exp(-logit))).toInt().coerceIn(1, 99)
        val forecast5 = (coeff + shortSlope * 5.0).coerceIn(1.0, 5.0)
        val forecast10 = (coeff + shortSlope * 10.0).coerceIn(1.0, 5.0)
        val confidence = min(95, 15 + relevant.size + (minutes / 2.0).toInt())
        val reasons = mutableListOf<String>()
        if (shortSlope > 0.02) reasons += "кэф быстро растёт"
        if (acceleration > 0.01) reasons += "рост ускоряется"
        if (anomaly > 0.25) reasons += "выше обычного уровня"
        if (persistence > 0.55) reasons += "повышенный кэф держится"
        if (rising > 0.65) reasons += "много последовательных повышений"
        if (rush > 0.0) reasons += "час пик"
        val label = when { probability >= 80 -> "ВЫСОКИЙ РИСК ВСПЛЕСКА"; probability >= 65 -> "Вероятен всплеск"; probability >= 45 -> "Возможен рост"; else -> "Рост не ожидается" }

        val pricePerMin = if (price != null && durationMin != null && durationMin > 0) price / durationMin else 0.0
        val coefficientValue = ((coeff - 1.0) * 38.0).coerceIn(0.0, 55.0)
        val trendValue = (shortSlope * 700.0).coerceIn(-15.0, 25.0)
        val burstValue = (probability * 0.20).coerceIn(0.0, 20.0)
        val economicsValue = if (pricePerMin > 0) ((pricePerMin - 20.0) * 0.7).coerceIn(-15.0, 25.0) else 0.0
        val decisionScore = (coefficientValue + trendValue + burstValue + economicsValue).roundToInt().coerceIn(0, 100)
        val decision = when { decisionScore >= 75 && probability >= 65 -> "ЕХАТЬ / СИЛЬНЫЙ СИГНАЛ"; decisionScore >= 55 -> "МОЖНО БРАТЬ"; decisionScore >= 35 -> "НАБЛЮДАТЬ"; else -> "НЕ СПЕШИТЬ" }

        return RadarPrediction(probability, forecast5, forecast10, shortSlope, confidence, label, probability, decisionScore, decision, reasons)
    }

    @Synchronized fun history(limit: Int, zoneName: String? = null): List<Double> =
        observations.filter { zoneName == null || it.zoneName == null || it.zoneName == zoneName }
            .takeLast(limit).map { it.coeff }

    @Synchronized fun latestCoeff(zoneName: String): Double? =
        observations.lastOrNull { it.zoneName == zoneName }?.coeff

    @Synchronized fun freshCoeff(zoneName: String, maxAgeMs: Long): Double? {
        val now = System.currentTimeMillis()
        // наблюдения идут по времени: как только дошли до старых, дальше смотреть не нужно
        for (i in observations.indices.reversed()) {
            val o = observations[i]
            if (now - o.timestamp > maxAgeMs) return null
            if (o.zoneName == zoneName) return o.coeff
        }
        return null
    }

    @Synchronized fun historicalBaseline(hour: Int, zoneName: String?): Double {
        val candidates = observations.filter { zoneName == null || it.zoneName == null || it.zoneName == zoneName }.filter {
            Calendar.getInstance().apply { timeInMillis = it.timestamp }.get(Calendar.HOUR_OF_DAY) == hour
        }
        return if (candidates.size >= 5) candidates.map { it.coeff }.average() else 1.15
    }

    @Synchronized private fun persist(context: Context) {
        val arr = JSONArray()
        observations.forEach { o ->
            arr.put(JSONObject().apply {
                put("t", o.timestamp); put("c", o.coeff)
                if (o.price == null) put("p", JSONObject.NULL) else put("p", o.price)
                if (o.durationMin == null) put("d", JSONObject.NULL) else put("d", o.durationMin)
                if (o.zoneName == null) put("z", JSONObject.NULL) else put("z", o.zoneName)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
