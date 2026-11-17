package com.surge.radar

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Погода в Москве (Open-Meteo, без ключа). Осадки повышают спрос на такси. */
object WeatherProvider {
    @Volatile private var precip = 0.0
    @Volatile private var snow = 0.0
    @Volatile private var temp = 10.0
    @Volatile private var loaded = false
    @Volatile private var lastFetch = 0L

    fun boost(): Double {
        var b = when {
            precip >= 3.0 -> 0.25
            precip >= 1.0 -> 0.18
            precip >= 0.1 -> 0.10
            else -> 0.0
        }
        if (snow > 0.0) b += 0.05
        if (temp <= -15.0) b += 0.05
        return b
    }

    fun summary(): String {
        if (!loaded) return ""
        val what = when {
            snow > 0.0 -> "снег"
            precip >= 0.1 -> "осадки"
            else -> "без осадков"
        }
        val t = Math.round(temp).toInt()
        val ts = if (t > 0) "+$t°" else "$t°"
        return "$what, $ts"
    }

    fun refreshAsync(force: Boolean = false, onDone: (() -> Unit)? = null) {
        val now = System.currentTimeMillis()
        if (!force && now - lastFetch < 15 * 60_000L) return
        lastFetch = now
        Thread {
            try {
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast?latitude=55.75&longitude=37.62" +
                        "&current=temperature_2m,precipitation,snowfall&timezone=Europe%2FMoscow"
                )
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val cur = JSONObject(body).getJSONObject("current")
                precip = cur.optDouble("precipitation", 0.0)
                snow = cur.optDouble("snowfall", 0.0)
                temp = cur.optDouble("temperature_2m", 10.0)
                loaded = true
                if (onDone != null) Handler(Looper.getMainLooper()).post { onDone() }
            } catch (e: Exception) {
                lastFetch = now - 10 * 60_000L // повтор через ~5 минут
            }
        }.start()
    }
}
