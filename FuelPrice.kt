package com.surge.radar

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Цена бензина АИ-95 для расчёта выгодности заказа.
 * Берётся онлайн (раз в 12 часов), при неудаче используется последняя сохранённая цена,
 * а если её нет, то значение по умолчанию. Можно задать вручную в настройках.
 */
object FuelPrice {
    /** Ориентир по крупным сетям АЗС Москвы, осень 2026 (АИ-95). */
    const val DEFAULT = 73.0

    private const val PREFS = "fuel_price"
    private const val SOURCE = "https://driff.ru/"
    private val priceRegex = Regex("(?:АИ|Аи|аи)[-\\s]?95[^0-9]{0,60}?(\\d{2,3}[.,]\\d{1,2})")
    private val tagRegex = Regex("<[^>]*>")

    @Volatile private var fetching = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun cached(c: Context): Double? {
        val v = prefs(c).getFloat("p", 0f)
        return if (v in 40f..200f) v.toDouble() else null
    }

    fun cachedAt(c: Context): Long = prefs(c).getLong("t", 0L)

    fun current(c: Context, cfg: WidgetSettings.Values): Double =
        if (cfg.fuelManual > 0) cfg.fuelManual / 10.0 else (cached(c) ?: DEFAULT)

    fun statusText(c: Context, cfg: WidgetSettings.Values): String {
        if (cfg.fuelManual > 0) {
            return String.format(Locale.US, "Задана вручную: %.1f ₽/л", cfg.fuelManual / 10.0)
        }
        val v = cached(c)
        return if (v != null) {
            val time = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(cachedAt(c)))
            String.format(Locale.US, "Онлайн: %.1f ₽/л (АИ-95), обновлено %s", v, time)
        } else {
            String.format(
                Locale.US,
                "Онлайн-цену получить не удалось. Взято %.0f ₽/л (АИ-95, крупные сети Москвы)",
                DEFAULT
            )
        }
    }

    fun refreshAsync(c: Context, force: Boolean = false) {
        val app = c.applicationContext
        val now = System.currentTimeMillis()
        if (fetching) return
        val p = prefs(app)
        if (!force) {
            if (cached(app) != null && now - cachedAt(app) < 12 * 3_600_000L) return
            if (now - p.getLong("try", 0L) < 30 * 60_000L) return
        }
        fetching = true
        p.edit().putLong("try", now).apply()
        Thread {
            try {
                val conn = URL(SOURCE).openConnection() as HttpURLConnection
                conn.connectTimeout = 7000
                conn.readTimeout = 7000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) SurgeRadar")
                val body = conn.inputStream.use { it.readBytes() }.let { String(it, Charsets.UTF_8) }
                conn.disconnect()
                val text = body.take(600_000).replace(tagRegex, " ").replace("&nbsp;", " ")
                val value = priceRegex.findAll(text)
                    .mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }
                    .firstOrNull { it in 40.0..150.0 }
                if (value != null) {
                    p.edit().putFloat("p", value.toFloat()).putLong("t", System.currentTimeMillis()).apply()
                }
            } catch (e: Exception) {
                // оставляем прошлую цену или значение по умолчанию
            } finally {
                fetching = false
            }
        }.start()
    }
}
