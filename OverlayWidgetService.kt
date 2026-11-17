package com.surge.radar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import android.graphics.PixelFormat
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max

/**
 * Маленькое окошко поверх экрана. По умолчанию это мини-«пилюля» с лучшим спросом.
 * Одиночный тап раскрывает компактное окно на несколько секунд, двойной тап открывает
 * увеличенное выпадающее меню горячих точек (аэропорты, ж/д, автовокзалы, больницы).
 * Во время поездки окошко скрывается само, чтобы не закрывать навигацию.
 */
class OverlayWidgetService : Service() {

    companion object {
        const val CHANNEL_ID = "surge_radar"
        const val NOTIFICATION_ID = 1001
        private const val TRIP_TTL = 45_000L
        private const val EXPAND_MS = 6_000L
        private const val HOT_EXPAND_MS = 14_000L

        fun startWith(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun start(context: Context) {
            val intent = Intent(context, OverlayWidgetService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }

    private lateinit var wm: WindowManager
    private var host: WidgetHost? = null
    private var cfg = WidgetSettings.Values()
    private var overlayReady = false
    private var tone: ToneGenerator? = null

    private val handler = Handler(Looper.getMainLooper())

    private var pendingFull = false
    private val onPrefsChanged = Runnable {
        if (pendingFull) {
            pendingFull = false
            buildWidget()
        } else {
            refreshData()
        }
    }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != "zone" && key != "tariffs" && key != "overlayOn") pendingFull = true
        handler.removeCallbacks(onPrefsChanged)
        handler.postDelayed(onPrefsChanged, 80L)
    }

    private val tick = object : Runnable {
        override fun run() {
            WeatherProvider.refreshAsync { refreshData() }
            ensureTracking()
            refreshData()
            SurgeWidgetProvider.refreshAll(this@OverlayWidgetService)
            handler.postDelayed(this, cfg.refreshMin * 60_000L)
        }
    }
    private val collapse = Runnable { showBase() }
    private val visCheck = Runnable { applyVisibility() }

    // состояние окна
    private var snoozeUntil = 0L
    private var tripUntil = 0L

    // оповещения о спросе
    private var lastAlertTier = 0
    private var alertInit = false

    private var lastRecordTime = 0L
    private var lastRecordCoeff = 0.0

    // кэш точек радара
    private var blips: List<Blip> = emptyList()
    private var blipsAt = 0L
    private var blipsKey = ""

    override fun onCreate() {
        super.onCreate()
        RadarEngine.init(this)
        createChannel()
        startForegroundCompat()

        if (!Settings.canDrawOverlays(this)) {
            WidgetSettings.setOverlayOn(this, false)
            stopSelf()
            return
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        tone = null
        WidgetSettings.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        overlayReady = true
        buildWidget()
        ensureTracking()
    }

    private fun closeByUser() {
        WidgetSettings.setOverlayOn(this, false)
        stopSelf()
    }

    private fun baseMode(): WidgetHost.Mode =
        if (cfg.widgetMode == 1) WidgetHost.Mode.CARD else WidgetHost.Mode.PILL

    private fun showBase() {
        host?.show(baseMode())
        applyVisibility()
    }

    private fun buildWidget() {
        host?.let { old -> runCatching { wm.removeView(old) } }
        cfg = WidgetSettings.load(this)

        val h = WidgetHost(this, cfg)
        h.card.onClose = { closeByUser() }
        h.hot.onClose = {
            handler.removeCallbacks(collapse)
            showBase()
        }
        h.hot.onOpenSchedule = {
            handler.removeCallbacks(collapse)
            showBase()
            startActivity(
                Intent(this, ScheduleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        h.show(baseMode())

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        val (px0, py0) = WidgetSettings.loadPos(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = px0
            y = py0
        }

        h.setOnTouchListener(DragListener(params, h))
        wm.addView(h, params)
        host = h

        handler.removeCallbacks(tick)
        handler.post(tick)
        applyVisibility()
    }

    // ----- касания: перетаскивание, тап, двойной тап, долгое нажатие -----

    private inner class DragListener(
        private val lp: WindowManager.LayoutParams,
        private val target: View
    ) : View.OnTouchListener {
        private var sx = 0
        private var sy = 0
        private var tx = 0f
        private var ty = 0f
        private var downAt = 0L
        private var moved = false

        override fun onTouch(v: View?, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = lp.x
                    sy = lp.y
                    tx = e.rawX
                    ty = e.rawY
                    downAt = SystemClock.uptimeMillis()
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx
                    val dy = e.rawY - ty
                    if (abs(dx) > 10f || abs(dy) > 10f) moved = true
                    lp.x = sx + dx.toInt()
                    lp.y = sy + dy.toInt()
                    runCatching { wm.updateViewLayout(target, lp) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val held = SystemClock.uptimeMillis() - downAt
                    when {
                        moved -> WidgetSettings.savePos(this@OverlayWidgetService, lp.x, lp.y)
                        held >= 600L -> snooze()
                        else -> onTap()
                    }
                    return true
                }
            }
            return false
        }
    }

    private var lastTapAt = 0L
    private val singleTap = Runnable { doSingleTap() }

    /** Двойной тап открывает увеличенное выпадающее меню горячих мест; одиночный — компактное окно. */
    private fun onTap() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapAt < 320L) {
            handler.removeCallbacks(singleTap)
            lastTapAt = 0L
            showHot()
        } else {
            lastTapAt = now
            handler.postDelayed(singleTap, 320L)
        }
    }

    private fun showHot() {
        val h = host ?: return
        cfg = WidgetSettings.load(this)
        h.hot.bind(HotSpots.compute(this, cfg).take(8), cfg.hotMin)
        h.visibility = View.VISIBLE
        h.show(WidgetHost.Mode.HOT)
        handler.removeCallbacks(collapse)
        handler.postDelayed(collapse, HOT_EXPAND_MS)
        HotSpots.refreshAsync(this) {
            if (host?.mode == WidgetHost.Mode.HOT) h.hot.bind(HotSpots.compute(this, cfg).take(8), cfg.hotMin)
        }
    }

    private fun doSingleTap() {
        val h = host ?: return
        when (h.mode) {
            WidgetHost.Mode.HOT -> {
                handler.removeCallbacks(collapse)
                showBase()
            }
            WidgetHost.Mode.PILL -> {
                h.show(WidgetHost.Mode.CARD)
                handler.removeCallbacks(collapse)
                handler.postDelayed(collapse, EXPAND_MS)
            }
            WidgetHost.Mode.CARD -> {
                if (cfg.widgetMode == 0) {
                    handler.removeCallbacks(collapse)
                    showBase()
                } else {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }

    private fun snooze() {
        snoozeUntil = System.currentTimeMillis() + cfg.snoozeMin * 60_000L
        applyVisibility()
        Toast.makeText(this, "Окошко скрыто на ${cfg.snoozeMin} мин", Toast.LENGTH_SHORT).show()
    }

    /** Скрыто на время паузы или поездки. */
    private fun applyVisibility() {
        val h = host ?: return
        val now = System.currentTimeMillis()
        val tripHide = cfg.hideInTrip && now < tripUntil
        val hidden = (now < snoozeUntil || tripHide)
        h.visibility = if (hidden) View.GONE else View.VISIBLE
        val until = max(snoozeUntil, if (cfg.hideInTrip) tripUntil else 0L)
        handler.removeCallbacks(visCheck)
        if (until > now) handler.postDelayed(visCheck, until - now + 200L)
    }

    // ----- команды от службы чтения экрана Яндекс Про -----

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent ?: return START_STICKY
        if (!overlayReady) return START_NOT_STICKY
        val now = System.currentTimeMillis()
        var changed = false

        if (intent.hasExtra("TRIP")) {
            tripUntil = if (intent.getBooleanExtra("TRIP", false)) now + TRIP_TTL else 0L
            applyVisibility()
        }

        if (intent.hasExtra("COEFF")) {
            val c = intent.getDoubleExtra("COEFF", 0.0)
            if (c > 0.0 && (c != lastRecordCoeff || now - lastRecordTime > 10_000L)) {
                lastRecordTime = now
                lastRecordCoeff = c
                RadarEngine.record(this, c, null, null, currentZoneName())
                changed = true
            }
        }

        if (changed) {
            refreshData()
        }
        return START_STICKY
    }

    // ----- данные спроса -----

    private fun currentZoneName(): String {
        cfg = WidgetSettings.load(this)
        return pinnedZone().name
    }

    private fun pinnedZone(): SurgeZone =
        MoscowRegionGrid.zones.firstOrNull { it.name == cfg.zone } ?: MoscowRegionGrid.zones.first()

    private fun refreshData() {
        val h = host ?: return
        cfg = WidgetSettings.load(this)
        val zone = pinnedZone()
        val tariffs = cfg.enabledTariffs()
        val now = System.currentTimeMillis()

        val base = DemandModel.baseCoeff(zone, now)
        val rows = tariffs.map { t ->
            val c = DemandModel.scale(base, t)
            TariffRow(t.shortTitle, DemandModel.pct(c), DemandModel.waitMinutes(c))
        }
        val maxPct = DemandModel.maxPct(base, tariffs)

        val primary = tariffs.maxByOrNull { it.k }
        val history = primary?.let { DemandModel.forecast(zone, it, now, 13, 15 * 60_000L) } ?: emptyList()
        val label = if (tariffs.isEmpty()) "" else trendLabel(history, now)
        val live = YandexLink.isLive(this)

        h.card.bind(
            WidgetData(
                zoneName = zone.name,
                rows = rows,
                label = label,
                history = history,
                blips = currentBlips(zone, tariffs),
                orderLine = "",
                destLine = "",
                decision = "",
                decisionColor = Color.LTGRAY,
                live = live
            )
        )
        h.pill.bind(rows, label.take(1), live)

        val tier = when {
            maxPct >= 70 -> 3
            maxPct >= 40 -> 2
            maxPct >= 20 -> 1
            else -> 0
        }
        if (alertInit && tier > lastAlertTier) beep(tier >= 3)
        lastAlertTier = tier
        alertInit = true
    }

    private fun trendLabel(hist: List<Double>, now: Long): String {
        if (hist.size < 2) return ""
        val pcts = hist.map { DemandModel.pct(it) }
        val cur = pcts[0]
        var peak = 0
        for (i in pcts.indices) if (pcts[i] > pcts[peak]) peak = i
        fun at(i: Int): String = DemandModel.mskTime(now + i * 15 * 60_000L)
        return when {
            peak > 0 && pcts[peak] - cur >= 8 -> "↗ пик ${at(peak)} ${DemandModel.coefText(pcts[peak])}"
            pcts.last() <= cur - 8 -> "↘ спад с ${at(pcts.indexOfFirst { it <= cur - 8 })}"
            else -> "→ без резких перемен"
        }
    }

    private fun currentBlips(zone: SurgeZone, tariffs: List<Tariff>): List<Blip> {
        val now = SystemClock.uptimeMillis()
        val key = zone.name + "|" + tariffs.size
        if (now - blipsAt < 60_000L && blipsKey == key && blips.isNotEmpty()) return blips
        blips = computeBlips(zone, tariffs)
        blipsAt = now
        blipsKey = key
        return blips
    }

    private fun computeBlips(center: SurgeZone, tariffs: List<Tariff>): List<Blip> {
        val zones = MoscowRegionGrid.zones
        val near = zones.filter { it !== center }
            .sortedBy { MoscowRegionGrid.distanceKm(center.lat, center.lon, it.lat, it.lon) }
            .take(14)
        if (near.isEmpty()) return emptyList()
        val maxD = max(
            1.0,
            near.maxOf { MoscowRegionGrid.distanceKm(center.lat, center.lon, it.lat, it.lon) }
        )
        return near.map { z ->
            val dLat = z.lat - center.lat
            val dLon = (z.lon - center.lon) * cos(Math.toRadians(center.lat))
            val d = MoscowRegionGrid.distanceKm(center.lat, center.lon, z.lat, z.lon)
            val pct = DemandModel.maxPct(DemandModel.baseCoeff(z), tariffs)
            val color = when {
                pct >= 40 -> 0xFFE06D64.toInt()
                pct >= 15 -> 0xFFE59B5E.toInt()
                else -> 0xFF6BCB9B.toInt()
            }
            Blip(atan2(-dLat, dLon), (d / maxD).toFloat().coerceIn(0.15f, 1f), color)
        }
    }

    private fun beep(high: Boolean) {
        if (!cfg.sound) return
        Feedback.beep(this, high)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Surge Radar", NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Мини-окошко спроса поверх экрана"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private var tracker: LocationTracker? = null

    private fun ensureTracking() {
        if (!overlayReady) return
        if (WidgetSettings.load(this).useGps) {
            if (tracker == null) tracker = LocationTracker(this)
            tracker?.start()
        } else {
            tracker?.stop()
        }
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun startForegroundCompat() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Радар кэфа работает")
            .setContentText("Нажмите, чтобы открыть карту")
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    (if (hasLocationPermission()) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (overlayReady) {
            WidgetSettings.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
            host?.let { v -> runCatching { wm.removeView(v) } }
        }
        tracker?.stop()
        tone?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
