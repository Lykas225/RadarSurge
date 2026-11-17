package com.surge.radar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val ctx: Context get() = this
    private val matchP = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrapP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var map: MapView
    private lateinit var heat: HeatOverlay
    private lateinit var p: Palette
    private lateinit var chip: TextView
    private lateinit var panel: LinearLayout
    private lateinit var rowsBox: LinearLayout
    private lateinit var zoneTitle: TextView
    private lateinit var weatherText: TextView
    private lateinit var labelText: TextView
    private lateinit var hotText: TextView
    private lateinit var overlayBtn: TextView
    private lateinit var menuFab: TextView
    private lateinit var hotPanel: LinearLayout
    private lateinit var hotRows: LinearLayout
    private lateinit var hotTitle: TextView
    private lateinit var hotNote: TextView
    private var hotOpen = false
    private var tracker: LocationTracker? = null

    private var values = WidgetSettings.Values()
    private var builtDark = true
    private var builtStyle = 0
    private var panelOpen = false
    private var phase = 0f
    private var myLocation: GeoPoint? = null
    private var hotZone: SurgeZone? = null
    private val flags = mutableListOf<Flag>()
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, values.refreshMin * 60_000L)
        }
    }

    private val animTick = object : Runnable {
        override fun run() {
            phase = (phase + 0.06f) % 1f
            heat.phase = phase
            map.invalidate()
            handler.postDelayed(this, 110L)
        }
    }

    private val flagTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val before = flags.size
            flags.removeAll { now - it.createdAt >= it.ttlMs }
            if (flags.isNotEmpty() || before != flags.size) map.invalidate()
            if (flags.isNotEmpty()) handler.postDelayed(this, 250L)
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onLocation(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!YandexLink.ready(this)) {
            startActivity(Intent(this, ConnectActivity::class.java))
            finish()
            return
        }
        Configuration.getInstance().apply {
            load(this@MainActivity, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
            tileFileSystemCacheMaxBytes = 100L * 1024L * 1024L
            tileFileSystemCacheTrimBytes = 80L * 1024L * 1024L
        }
        RadarEngine.init(this)
        values = WidgetSettings.load(this)
        builtDark = values.dark
        builtStyle = values.mapStyle
        p = Palette(values.dark)
        buildUi()
    }

    private fun dp(v: Int): Int = dpi(v)

    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun glass(radiusDp: Int): GradientDrawable =
        rounded(withAlpha(p.card, 0.94f), dp(radiusDp).toFloat(), p.stroke, dp(1))

    private fun button(t: String, filled: Boolean, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = t
            textSize = 14.5f
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(if (filled) Color.WHITE else p.text)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = if (filled) {
                GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0xFF2A8C82.toInt(), 0xFF3A7BD5.toInt())
                ).apply { cornerRadius = dp(14).toFloat() }
            } else rounded(p.cardAlt, dp(14).toFloat(), p.stroke, dp(1))
            setOnClickListener { onClick() }
        }

    private fun fab(symbol: String, color: Int, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = symbol
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(color)
            background = rounded(withAlpha(p.card, 0.95f), dp(26).toFloat(), p.stroke, dp(1))
            elevation = dp(6).toFloat()
            setOnClickListener { onClick() }
        }

    private fun darkOsmFilter(): ColorMatrixColorFilter {
        val m = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val sat = ColorMatrix().apply { setSaturation(0.25f) }
        val tint = ColorMatrix(
            floatArrayOf(
                0.85f, 0f, 0f, 0f, 0f,
                0f, 0.95f, 0f, 0f, 5f,
                0f, 0f, 1.05f, 0f, 14f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        m.postConcat(sat)
        m.postConcat(tint)
        return ColorMatrixColorFilter(m)
    }

    private fun applyTiles() {
        if (values.mapStyle == 0) {
            val kind = if (p.dark) "dark_all" else "light_all"
            val urls = arrayOf("a", "b", "c", "d").map { "https://$it.basemaps.cartocdn.com/$kind/" }.toTypedArray()
            map.setTileSource(
                XYTileSource(if (p.dark) "CartoDark" else "CartoLight", 0, 19, 256, ".png", urls)
            )
            map.overlayManager.tilesOverlay.setColorFilter(null)
        } else {
            map.setTileSource(TileSourceFactory.MAPNIK)
            map.overlayManager.tilesOverlay.setColorFilter(if (p.dark) darkOsmFilter() else null)
        }
    }

    private fun buildUi() {
        val root = FrameLayout(ctx)

        map = MapView(ctx).apply {
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            minZoomLevel = 7.0
            maxZoomLevel = 18.0
            controller.setZoom(10.0)
            val z = pinnedZone()
            controller.setCenter(GeoPoint(z.lat, z.lon))
        }
        applyTiles()
        heat = HeatOverlay()
        heat.dark = p.dark
        heat.flags = flags
        heat.onTap = { lat, lon -> placeFlag(lat, lon) }
        heat.onLongTap = { lat, lon ->
            val z = MoscowRegionGrid.nearest(lat, lon)
            pin(z, false)
            Toast.makeText(this, "Зона закреплена для виджета: ${z.name.removePrefix("МО: ")}", Toast.LENGTH_SHORT).show()
        }
        heat.onFlagTap = { fl ->
            pin(fl.zone, false)
            Toast.makeText(this, "Зона закреплена для виджета: ${fl.zone.name.removePrefix("МО: ")}", Toast.LENGTH_SHORT).show()
        }
        map.overlays.add(heat)
        heat.onDouble = { showHotPanel() }
        root.addView(map, FrameLayout.LayoutParams(matchP, matchP))

        // Легенда и указание источника карты
        val legendSpan = SpannableStringBuilder()
        val legend = listOf(15 to "×1.2+", 40 to "×1.4+", 70 to "×1.7+")
        for ((pct, label) in legend) {
            val st = legendSpan.length
            legendSpan.append("●")
            legendSpan.setSpan(ForegroundColorSpan(DemandModel.heatColor(pct)), st, legendSpan.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            legendSpan.append(" $label   ")
        }
        val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        info.addView(tv("", 11f, p.sub).apply {
            text = legendSpan
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded(withAlpha(p.card, 0.88f), dp(10).toFloat(), p.stroke, dp(1))
        })
        info.addView(
            tv(if (values.mapStyle == 0) "© OpenStreetMap © CARTO" else "© OpenStreetMap", 9f, p.sub).apply {
                setPadding(dp(8), dp(2), dp(8), dp(2))
            }
        )
        root.addView(
            info,
            FrameLayout.LayoutParams(wrapP, wrapP, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) }
        )

        // Кнопки справа: местоположение, расписания и меню
        val fabs = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val locFab = fab("◎", p.accent) { locate() }
        val schedFab = fab("📅", p.text) { openScheduleMenu() }
        menuFab = fab("☰", p.text) { setPanelOpen(!panelOpen) }
        fabs.addView(locFab, LinearLayout.LayoutParams(dp(52), dp(52)))
        fabs.addView(schedFab, LinearLayout.LayoutParams(dp(52), dp(52)).apply { topMargin = dp(10) })
        fabs.addView(menuFab, LinearLayout.LayoutParams(dp(52), dp(52)).apply { topMargin = dp(10) })
        root.addView(
            fabs,
            FrameLayout.LayoutParams(wrapP, wrapP, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(12), dp(12), 0) }
        )

        // Компактная плашка внизу: зона и спрос
        chip = tv("", 14f, p.text, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            background = glass(18)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            elevation = dp(6).toFloat()
            setOnClickListener { setPanelOpen(true) }
        }
        root.addView(
            chip,
            FrameLayout.LayoutParams(wrapP, wrapP, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(dp(12), 0, dp(12), dp(18))
            }
        )

        // Переработанное выдвижное меню
        panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val r = dp(24).toFloat()
            background = GradientDrawable().apply {
                setColor(withAlpha(p.card, 0.98f))
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setStroke(dp(1), p.stroke)
            }
            setPadding(dp(16), 0, dp(16), dp(18))
            elevation = dp(12).toFloat()
            visibility = View.GONE
        }

        val handleBox = FrameLayout(ctx).apply { setOnClickListener { setPanelOpen(false) } }
        handleBox.addView(
            View(ctx).apply { background = rounded(p.stroke, dp(2).toFloat()) },
            FrameLayout.LayoutParams(dp(44), dp(4), Gravity.CENTER)
        )
        panel.addView(handleBox, LinearLayout.LayoutParams(matchP, dp(26)))

        // Шапка текущей зоны и погоды
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        zoneTitle = tv("", 18f, p.text, true)
        weatherText = tv("", 12f, p.sub).apply { gravity = Gravity.END }
        header.addView(zoneTitle, LinearLayout.LayoutParams(0, wrapP, 1f))
        header.addView(weatherText, LinearLayout.LayoutParams(wrapP, wrapP).apply { leftMargin = dp(8) })
        panel.addView(header)

        // Карточка коэффициентов по тарифам
        rowsBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.cardAlt, dp(16).toFloat(), p.stroke, dp(1))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        panel.addView(rowsBox, LinearLayout.LayoutParams(matchP, wrapP).apply { topMargin = dp(6) })

        labelText = tv("", 12.5f, p.sub).apply { setPadding(dp(2), dp(8), dp(2), dp(2)) }
        panel.addView(labelText)

        hotText = tv("", 13.5f, p.accent, true).apply {
            setPadding(dp(2), dp(4), dp(2), dp(10))
            setOnClickListener { hotZone?.let { z -> pin(z, true) } }
        }
        panel.addView(hotText)

        // Быстрые разделы меню: «Все расписания» и «Горячие места»
        val navRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val allSchedBtn = button("📅 Все расписания", false) {
            setPanelOpen(false)
            openScheduleMenu()
        }
        val hotPlacesBtn = button("🔥 Горячие точки", false) {
            showHotPanel()
        }
        navRow.addView(allSchedBtn, LinearLayout.LayoutParams(0, wrapP, 1f).apply { rightMargin = dp(8) })
        navRow.addView(hotPlacesBtn, LinearLayout.LayoutParams(0, wrapP, 1f))
        panel.addView(navRow, LinearLayout.LayoutParams(matchP, wrapP))

        // Основная кнопка обновления
        panel.addView(
            button("↻ Обновить радар", true) { forceRefresh() },
            LinearLayout.LayoutParams(matchP, wrapP).apply { topMargin = dp(8) }
        )

        // Нижний ряд управления: Виджет и Настройки
        val bottomRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        overlayBtn = button("", false) { toggleOverlay() }
        val settingsBtn = button("⚙ Настройки", false) { startActivity(Intent(ctx, SettingsActivity::class.java)) }
        bottomRow.addView(overlayBtn, LinearLayout.LayoutParams(0, wrapP, 1f).apply { rightMargin = dp(8) })
        bottomRow.addView(settingsBtn, LinearLayout.LayoutParams(0, wrapP, 1f))
        panel.addView(bottomRow, LinearLayout.LayoutParams(matchP, wrapP).apply { topMargin = dp(8) })

        root.addView(panel, FrameLayout.LayoutParams(matchP, wrapP, Gravity.BOTTOM))

        // Горячие места: выезжают по двойному тапу
        hotPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val r = dp(24).toFloat()
            background = GradientDrawable().apply {
                setColor(withAlpha(p.card, 0.98f))
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setStroke(dp(1), p.stroke)
            }
            setPadding(dp(16), 0, dp(16), dp(16))
            elevation = dp(12).toFloat()
            visibility = View.GONE
        }
        val hotHandle = FrameLayout(ctx).apply { setOnClickListener { setHotOpen(false) } }
        hotHandle.addView(
            View(ctx).apply { background = rounded(p.stroke, dp(2).toFloat()) },
            FrameLayout.LayoutParams(dp(44), dp(4), Gravity.CENTER)
        )
        hotPanel.addView(hotHandle, LinearLayout.LayoutParams(matchP, dp(26)))

        val hotHeader = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hotTitle = tv("", 17f, p.text, true)
        val openFullSched = tv("Все расписания →", 13f, p.accent, true).apply {
            setPadding(dp(8), dp(4), 0, dp(4))
            setOnClickListener {
                setHotOpen(false)
                openScheduleMenu()
            }
        }
        hotHeader.addView(hotTitle, LinearLayout.LayoutParams(0, wrapP, 1f))
        hotHeader.addView(openFullSched)
        hotPanel.addView(hotHeader)

        hotRows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        hotPanel.addView(hotRows, LinearLayout.LayoutParams(matchP, wrapP).apply { topMargin = dp(6) })
        hotNote = tv("", 11f, p.sub).apply { setPadding(0, dp(8), 0, 0) }
        hotPanel.addView(hotNote)
        root.addView(hotPanel, FrameLayout.LayoutParams(matchP, wrapP, Gravity.BOTTOM))
        setContentView(root)

        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        if (!ui.getBoolean("hint_flags", false)) {
            ui.edit().putBoolean("hint_flags", true).apply()
            Toast.makeText(
                this,
                "Коснитесь карты: флажок покажет спрос. Удержание закрепляет зону для виджета.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openScheduleMenu() {
        startActivity(Intent(ctx, ScheduleActivity::class.java))
    }

    private fun setPanelOpen(open: Boolean) {
        panelOpen = open
        menuFab.text = if (open) "✕" else "☰"
        if (open) {
            chip.visibility = View.GONE
            panel.visibility = View.VISIBLE
            panel.post {
                panel.translationY = panel.height.toFloat()
                panel.animate().translationY(0f).setDuration(220L).start()
            }
        } else {
            panel.animate().translationY(panel.height.toFloat()).setDuration(200L).withEndAction {
                if (!panelOpen) {
                    panel.visibility = View.GONE
                    if (!hotOpen) chip.visibility = View.VISIBLE
                }
            }.start()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (hotOpen) setHotOpen(false) else if (panelOpen) setPanelOpen(false) else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (!::map.isInitialized) return
        if (!YandexLink.ready(this)) {
            startActivity(Intent(this, ConnectActivity::class.java))
            finish()
            return
        }
        val v = WidgetSettings.load(this)
        if (v.dark != builtDark || v.mapStyle != builtStyle) {
            recreate()
            return
        }
        values = v
        map.onResume()
        startTracking()
        updateOverlayButton()
        WeatherProvider.refreshAsync { refresh() }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        handler.removeCallbacks(animTick)
        heat.animated = values.mapAnim
        if (values.mapAnim) handler.post(animTick)
        handler.removeCallbacks(flagTick)
        handler.post(flagTick)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(animTick)
        handler.removeCallbacks(flagTick)
        tracker?.stop()
        if (::map.isInitialized) map.onPause()
        runCatching {
            (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(locationListener)
        }
        super.onPause()
    }

    private fun pinnedZone(): SurgeZone =
        MoscowRegionGrid.zones.firstOrNull { it.name == values.zone } ?: MoscowRegionGrid.zones.first()

    private fun rebuildRows(base: Double, tariffs: List<Tariff>) {
        rowsBox.removeAllViews()
        if (tariffs.isEmpty()) {
            rowsBox.addView(tv("Тарифы выключены. Включите их в настройках.", 14f, p.sub))
            return
        }
        for (t in tariffs) {
            val c = DemandModel.scale(base, t)
            val pct = DemandModel.pct(c)
            val color = DemandModel.textColor(pct)
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            row.addView(tv(t.title, 16f, p.text, true), LinearLayout.LayoutParams(0, wrapP, 1f))
            val pill = tv(DemandModel.coefText(pct), 15.5f, color, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(4), dp(12), dp(4))
                background = rounded(withAlpha(color, 0.16f), dp(12).toFloat(), withAlpha(color, 0.55f), dp(1))
            }
            row.addView(pill, LinearLayout.LayoutParams(dp(84), wrapP))
            row.addView(
                tv("~${DemandModel.waitMinutes(c)} мин", 13.5f, p.sub).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(dp(72), wrapP).apply { leftMargin = dp(8) }
            )
            rowsBox.addView(row)
        }
    }

    private fun refresh() {
        values = WidgetSettings.load(this)
        val tariffs = values.enabledTariffs()
        val zone = pinnedZone()
        val now = System.currentTimeMillis()

        val items = MoscowRegionGrid.zones.map { z ->
            HeatItem(z, DemandModel.maxPct(DemandModel.baseCoeff(z, now), tariffs))
        }
        heat.items = items
        heat.pinned = zone
        heat.glow = values.glow / 100f
        heat.animated = values.mapAnim
        heat.myLocation = myLocation
        map.invalidate()

        val base = DemandModel.baseCoeff(zone, now)
        val zonePct = DemandModel.maxPct(base, tariffs)

        zoneTitle.text = "📍 ${zone.name.removePrefix("МО: ")}"
        weatherText.text = WeatherProvider.summary()
        rebuildRows(base, tariffs)

        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
        if (tariffs.isEmpty()) {
            labelText.text = "Включите хотя бы один тариф в настройках"
        } else {
            val next = DemandModel.maxPct(DemandModel.baseCoeff(zone, now + 30 * 60_000L, false), tariffs)
            val arrow = if (next - zonePct >= 8) " ↗" else if (zonePct - next >= 8) " ↘" else ""
            val source = if (YandexLink.isLive(this)) "реальные данные Яндекс Про" else "расчётная оценка"
            labelText.text = "${DemandModel.label(zonePct)}$arrow • через 30 мин ${DemandModel.coefText(next)}
Источник: $source • $time"
        }

        val top = items.maxByOrNull { it.pct }
        hotZone = top?.zone
        hotText.text = if (top != null && top.pct >= 10)
            "🔥 Горячее всего: ${top.zone.name.removePrefix("МО: ")} ${DemandModel.coefText(top.pct)}"
        else "Сейчас везде спокойно"

        val s = SpannableStringBuilder("📍 ${zone.name.removePrefix("МО: ")}")
        if (tariffs.isNotEmpty()) {
            s.append("   ")
            val st = s.length
            s.append(DemandModel.coefText(zonePct))
            s.setSpan(ForegroundColorSpan(DemandModel.textColor(zonePct)), st, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(StyleSpan(Typeface.BOLD), st, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val dot = s.length
        s.append("  ●")
        s.setSpan(
            ForegroundColorSpan(if (YandexLink.isLive(this)) 0xFF6BCB9B.toInt() else 0xFF93A1B0.toInt()),
            dot, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        chip.text = s
    }

    private fun forceRefresh() {
        WeatherProvider.refreshAsync(true) { refresh() }
        HotSpots.refreshAsync(this, true)
        refresh()
        SurgeWidgetProvider.refreshAll(this)
        Toast.makeText(this, "Радар обновлён", Toast.LENGTH_SHORT).show()
    }

    private fun pin(z: SurgeZone, animate: Boolean) {
        WidgetSettings.setZone(this, z.name)
        refresh()
        SurgeWidgetProvider.refreshAll(this)
        if (animate) map.controller.animateTo(GeoPoint(z.lat, z.lon))
    }

    // ----- Горячие места -----

    private fun showHotPanel() {
        if (panelOpen) setPanelOpen(false)
        renderHot()
        setHotOpen(true)
        HotSpots.refreshAsync(this) { if (hotOpen) renderHot() }
    }

    private fun setHotOpen(open: Boolean) {
        hotOpen = open
        if (open) {
            chip.visibility = View.GONE
            hotPanel.visibility = View.VISIBLE
            hotPanel.post {
                hotPanel.translationY = hotPanel.height.toFloat()
                hotPanel.animate().translationY(0f).setDuration(220L).start()
            }
        } else {
            hotPanel.animate().translationY(hotPanel.height.toFloat()).setDuration(200L).withEndAction {
                if (!hotOpen) {
                    hotPanel.visibility = View.GONE
                    if (!panelOpen) chip.visibility = View.VISIBLE
                }
            }.start()
        }
    }

    private fun renderHot() {
        val cfg = WidgetSettings.load(this)
        val list = HotSpots.compute(this, cfg).take(7)
        hotTitle.text = "🔥 Горячие места от ${DemandModel.coefText(cfg.hotMin)}"
        hotRows.removeAllViews()
        if (list.isEmpty()) {
            val msg = if (cfg.enabledTariffs().isEmpty()) "Включите тариф в настройках"
            else "Сейчас таких мест нет. Порог можно снизить в настройках."
            hotRows.addView(tv(msg, 14f, p.sub).apply { setPadding(0, dp(8), 0, dp(8)) })
        }
        for (h in list) {
            val color = DemandModel.textColor(h.pct)
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { focusPoi(h.poi) }
            }
            row.addView(tv(h.poi.kind.emoji, 22f, p.text), LinearLayout.LayoutParams(dp(36), wrapP))
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tv(h.poi.name, 15f, p.text, true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            col.addView(tv(h.reason, 12f, if (h.live) p.accent else p.sub).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(col, LinearLayout.LayoutParams(0, wrapP, 1f))
            val pill = tv(DemandModel.coefText(h.pct), 15f, color, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(3), dp(10), dp(3))
                background = rounded(withAlpha(color, 0.16f), dp(12).toFloat(), withAlpha(color, 0.55f), dp(1))
            }
            row.addView(pill, LinearLayout.LayoutParams(wrapP, wrapP).apply { leftMargin = dp(8) })
            hotRows.addView(row)
        }
        hotNote.text = HotSpots.statusLine(this)
    }

    private fun focusPoi(poi: Poi) {
        setHotOpen(false)
        map.controller.animateTo(GeoPoint(poi.lat, poi.lon))
        map.controller.setZoom(14.0)
        placeFlag(poi.lat, poi.lon)
    }

    // ----- Флажки -----

    private fun placeFlag(lat: Double, lon: Double) {
        if (panelOpen) setPanelOpen(false)
        if (hotOpen) setHotOpen(false)
        val tariffs = values.enabledTariffs()
        val zone = MoscowRegionGrid.nearest(lat, lon)
        val dist = MoscowRegionGrid.distanceKm(lat, lon, zone.lat, zone.lon)
        val now = System.currentTimeMillis()

        val title: String
        val lines: List<Pair<String, Int>>
        val color: Int
        if (dist > 30.0) {
            title = "Вне зоны покрытия"
            lines = listOf("Нет данных" to Color.LTGRAY)
            color = 0xFF93A1B0.toInt()
        } else {
            val base = DemandModel.baseCoeff(zone, now)
            val maxPct = DemandModel.maxPct(base, tariffs)
            title = zone.name.removePrefix("МО: ")
            lines = if (tariffs.isEmpty()) listOf("Тарифы выключены" to Color.LTGRAY)
            else tariffs.map { t ->
                val c = DemandModel.scale(base, t)
                val pct = DemandModel.pct(c)
                "${t.title}  ${DemandModel.coefText(pct)}  ~${DemandModel.waitMinutes(c)} мин" to DemandModel.textColor(pct)
            }
            color = if (maxPct >= 15) DemandModel.heatColor(maxPct) else 0xFF4DB6AC.toInt()
        }

        flags.add(Flag(lat, lon, zone, now, values.flagSec * 1000L, title, lines, color))
        handler.removeCallbacks(flagTick)
        handler.post(flagTick)
        while (flags.size > 8) flags.removeAt(0)
        map.invalidate()
    }

    // ----- Местоположение -----

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun locate() {
        if (!hasLocationPermission()) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                21
            )
            return
        }
        startLocation()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 21 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            startLocation()
            startTracking()
            if (WidgetSettings.load(this).overlayOn) {
                stopService(Intent(this, OverlayWidgetService::class.java))
                OverlayWidgetService.start(this)
            }
        }
    }

    /** Пока приложение открыто, зона сама следует за вами; при первом запуске просим разрешение. */
    private fun startTracking() {
        if (!values.useGps) {
            tracker?.stop()
            return
        }
        if (!hasLocationPermission()) {
            val ui = getSharedPreferences("ui", MODE_PRIVATE)
            if (!ui.getBoolean("asked_loc", false)) {
                ui.edit().putBoolean("asked_loc", true).apply()
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    21
                )
            }
            return
        }
        if (tracker == null) {
            tracker = LocationTracker(
                this,
                onFix = { loc ->
                    myLocation = GeoPoint(loc.latitude, loc.longitude)
                    heat.myLocation = myLocation
                    map.invalidate()
                },
                onZone = { _, _ ->
                    refresh()
                    SurgeWidgetProvider.refreshAll(this)
                }
            )
        }
        tracker?.start()

        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        if (!ui.getBoolean("svc_loc", false) && WidgetSettings.load(this).overlayOn) {
            ui.edit().putBoolean("svc_loc", true).apply()
            stopService(Intent(this, OverlayWidgetService::class.java))
            OverlayWidgetService.start(this)
        }
    }

    private fun startLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        if (providers.isEmpty()) {
            Toast.makeText(this, "Включите геолокацию в настройках телефона", Toast.LENGTH_LONG).show()
            return
        }
        var best: Location? = null
        for (name in providers) {
            val l = runCatching { lm.getLastKnownLocation(name) }.getOrNull()
            if (l != null && (best == null || l.time > best.time)) best = l
        }
        best?.let { onLocation(it) }
        for (name in providers) {
            runCatching {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(name, locationListener, Looper.getMainLooper())
            }
        }
    }

    private fun onLocation(loc: Location) {
        val gp = GeoPoint(loc.latitude, loc.longitude)
        myLocation = gp
        pin(MoscowRegionGrid.nearest(loc.latitude, loc.longitude), false)
        map.controller.animateTo(gp)
        map.controller.setZoom(12.0)
    }

    // ----- Виджет поверх экрана -----

    private fun updateOverlayButton() {
        val on = WidgetSettings.load(this).overlayOn
        overlayBtn.text = if (on) "Виджет: ВКЛ" else "Виджет: ВЫКЛ"
        overlayBtn.setTextColor(if (on) p.accent else p.text)
    }

    private fun toggleOverlay() {
        val on = WidgetSettings.load(this).overlayOn
        if (on) {
            stopService(Intent(this, OverlayWidgetService::class.java))
            WidgetSettings.setOverlayOn(this, false)
        } else {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Разрешите показ поверх других приложений", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
                return
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 22)
            }
            WidgetSettings.setOverlayOn(this, true)
            OverlayWidgetService.start(this)
        }
        updateOverlayButton()
    }
}
