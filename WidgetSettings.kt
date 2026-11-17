package com.surge.radar

import android.content.Context
import android.content.SharedPreferences

object WidgetSettings {
    private const val PREFS = "widget_settings"
    private const val POS = "widget_pos"

    data class Values(
        val bgAlpha: Int = 85,
        val widgetAlpha: Int = 100,
        val scale: Int = 100,
        val fontPct: Int = 100,
        val fontWeight: Int = 600,
        val showRadar: Boolean = true,
        val showGraph: Boolean = true,
        val showDemand: Boolean = true,
        val showWait: Boolean = true,
        val showDecision: Boolean = true,
        val sound: Boolean = true,
        val dark: Boolean = true,
        val mapAnim: Boolean = true,
        val mapStyle: Int = 0,
        val glow: Int = 70,
        val hotMin: Int = 40,
        val useGps: Boolean = true,
        val hotCats: Int = 15,
        val schedOnline: Boolean = true,
        val widgetMode: Int = 0,
        val consumption: Int = 80,
        val wear: Int = 35,
        val targetKm: Int = 13,
        val targetHour: Int = 450,
        val netMode: Boolean = true,
        val fuelManual: Int = 0,
        val snoozeMin: Int = 10,
        val hideInTrip: Boolean = true,
        val flagSec: Int = 30,
        val refreshMin: Int = 5,
        val tariffMask: Int = 7,
        val zone: String? = null,
        val overlayOn: Boolean = false
    ) {
        fun isOn(t: Tariff): Boolean = (tariffMask and t.bit) != 0
        fun enabledTariffs(): List<Tariff> = Tariff.values().filter { isOn(it) }
    }

    fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(c: Context): Values {
        val p = prefs(c)
        val d = Values()
        return Values(
            bgAlpha = p.getInt("bg", d.bgAlpha).coerceIn(10, 100),
            widgetAlpha = p.getInt("alpha", d.widgetAlpha).coerceIn(30, 100),
            scale = p.getInt("scale", d.scale).coerceIn(70, 130),
            fontPct = p.getInt("font", d.fontPct).coerceIn(80, 150),
            fontWeight = p.getInt("weight", d.fontWeight).coerceIn(300, 900),
            showRadar = p.getBoolean("radar", d.showRadar),
            showGraph = p.getBoolean("graph", d.showGraph),
            showDemand = p.getBoolean("demand", d.showDemand),
            showWait = p.getBoolean("wait", d.showWait),
            showDecision = p.getBoolean("decision", d.showDecision),
            sound = p.getBoolean("sound", d.sound),
            dark = p.getBoolean("dark", d.dark),
            mapAnim = p.getBoolean("anim", d.mapAnim),
            mapStyle = p.getInt("mapstyle", d.mapStyle).coerceIn(0, 1),
            glow = p.getInt("glow", d.glow).coerceIn(10, 100),
            hotMin = p.getInt("hotmin", d.hotMin).coerceIn(10, 200),
            useGps = p.getBoolean("gps", d.useGps),
            hotCats = p.getInt("hotcats", d.hotCats) and 15,
            schedOnline = p.getBoolean("sched", d.schedOnline),
            widgetMode = p.getInt("mode", d.widgetMode).coerceIn(0, 1),
            consumption = p.getInt("cons", d.consumption).coerceIn(30, 200),
            wear = p.getInt("wear10", d.wear).coerceIn(0, 200),
            targetKm = p.getInt("tkm", d.targetKm).coerceIn(1, 100),
            targetHour = p.getInt("thour", d.targetHour).coerceIn(50, 5000),
            netMode = p.getBoolean("net", d.netMode),
            fuelManual = p.getInt("fuelman", d.fuelManual),
            snoozeMin = p.getInt("snooze", d.snoozeMin).coerceIn(1, 120),
            hideInTrip = p.getBoolean("trip", d.hideInTrip),
            flagSec = p.getInt("flagsec", d.flagSec).coerceIn(3, 900),
            refreshMin = p.getInt("refresh", d.refreshMin).coerceIn(1, 60),
            tariffMask = p.getInt("tariffs", d.tariffMask) and 7,
            zone = p.getString("zone", null),
            overlayOn = p.getBoolean("overlayOn", d.overlayOn)
        )
    }

    /** Сохраняет всё, кроме выбранной зоны и состояния оверлея (у них свои setZone/setOverlayOn). */
    fun save(c: Context, v: Values) {
        prefs(c).edit()
            .putInt("bg", v.bgAlpha)
            .putInt("alpha", v.widgetAlpha)
            .putInt("scale", v.scale)
            .putInt("font", v.fontPct)
            .putInt("weight", v.fontWeight)
            .putBoolean("radar", v.showRadar)
            .putBoolean("graph", v.showGraph)
            .putBoolean("demand", v.showDemand)
            .putBoolean("wait", v.showWait)
            .putBoolean("decision", v.showDecision)
            .putBoolean("sound", v.sound)
            .putBoolean("dark", v.dark)
            .putBoolean("anim", v.mapAnim)
            .putInt("mapstyle", v.mapStyle)
            .putInt("glow", v.glow)
            .putInt("hotmin", v.hotMin)
            .putBoolean("gps", v.useGps)
            .putInt("hotcats", v.hotCats)
            .putBoolean("sched", v.schedOnline)
            .putInt("mode", v.widgetMode)
            .putInt("cons", v.consumption)
            .putInt("wear10", v.wear)
            .putInt("tkm", v.targetKm)
            .putInt("thour", v.targetHour)
            .putBoolean("net", v.netMode)
            .putInt("fuelman", v.fuelManual)
            .putInt("snooze", v.snoozeMin)
            .putBoolean("trip", v.hideInTrip)
            .putInt("flagsec", v.flagSec)
            .putInt("refresh", v.refreshMin)
            .putInt("tariffs", v.tariffMask)
            .apply()
    }

    fun setZone(c: Context, name: String) {
        prefs(c).edit().putString("zone", name).apply()
    }

    fun setMask(c: Context, mask: Int) {
        prefs(c).edit().putInt("tariffs", mask and 7).apply()
    }

    fun setOverlayOn(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("overlayOn", on).apply()
    }

    fun loadPos(c: Context): Pair<Int, Int> {
        val p = c.applicationContext.getSharedPreferences(POS, Context.MODE_PRIVATE)
        return Pair(p.getInt("x", 24), p.getInt("y", 170))
    }

    fun savePos(c: Context, x: Int, y: Int) {
        c.applicationContext.getSharedPreferences(POS, Context.MODE_PRIVATE)
            .edit().putInt("x", x).putInt("y", y).apply()
    }
}
