package com.surge.radar

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.Intent
import java.util.regex.Pattern

class TaximeterReaderService : AccessibilityService() {

    private val coeffRegex = Pattern.compile(
        "(?:×|x|х)\s*([1-4](?:[.,][0-9]{1,2})?)",
        Pattern.CASE_INSENSITIVE
    )
    private val loginWords = listOf("выйти на линию", "уйти с линии", "на линии", "баланс", "заказы")
    private val tripWords = listOf("начать поездку", "завершить поездку", "завершить заказ", "я на месте")

    private val handler = Handler(Looper.getMainLooper())
    private var scanPending = false
    private var lastScanAt = 0L
    private var idleScan = true
    private var lastHash = 0
    private var lastTrip = false
    private var lastHashAt = 0L
    private val pm by lazy { getSystemService(POWER_SERVICE) as PowerManager }

    override fun onServiceConnected() {
        super.onServiceConnected()
        RadarEngine.init(this)
    }

    private val scanRunnable = Runnable {
        scanPending = false
        scanNow()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        ReaderDiag.events++
        ReaderDiag.lastEventAt = System.currentTimeMillis()
        val now = SystemClock.uptimeMillis()
        val minGap = when {
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> 0L
            idleScan -> 500L
            else -> 250L
        }
        val since = now - lastScanAt
        if (since >= minGap) {
            handler.removeCallbacks(scanRunnable)
            scanPending = false
            scanNow()
        } else if (!scanPending) {
            scanPending = true
            handler.postDelayed(scanRunnable, minGap - since)
        }
    }

    private fun scanNow() {
        lastScanAt = SystemClock.uptimeMillis()
        if (!pm.isInteractive) return
        val now = System.currentTimeMillis()

        val root = (if (Build.VERSION.SDK_INT >= 33) {
            getRootInActiveWindow(
                AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID or
                    AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE
            )
        } else rootInActiveWindow) ?: run {
            ReaderDiag.nullRoots++
            return
        }
        ReaderDiag.scans++
        YandexLink.markSeen(this)
        val texts = ArrayList<String>(128)
        collectNodes(root, texts, intArrayOf(300))
        val h = texts.hashCode()
        if (h == lastHash && now - lastHashAt < 15_000L) return
        lastHash = h
        ReaderDiag.texts = texts.take(80)
        lastHashAt = now

        var coeff = 0.0
        var trip = false
        var loggedIn = false

        for (raw in texts) {
            val text = raw.trim()
            if (text.isEmpty()) continue

            if (text.indexOf('×') >= 0 || text.indexOf('x') >= 0 || text.indexOf('х') >= 0) {
                val cm = coeffRegex.matcher(text.replace(',', '.'))
                while (cm.find()) {
                    cm.group(1)?.toDoubleOrNull()?.let {
                        if (it in 1.0..5.0) coeff = maxOf(coeff, it)
                    }
                }
            }

            val low = text.lowercase()
            if (!trip && tripWords.any { low.contains(it) }) trip = true
            if (!loggedIn && loginWords.any { low.contains(it) }) loggedIn = true
        }
        idleScan = coeff <= 0.0
        if (loggedIn && !YandexLink.isLoginConfirmed(this)) YandexLink.setLoginConfirmed(this, true)

        val overlayOn = WidgetSettings.load(this).overlayOn
        if (overlayOn && (trip || lastTrip)) {
            runCatching { startService(Intent(this, OverlayWidgetService::class.java).putExtra("TRIP", trip)) }
        }
        lastTrip = trip

        if (coeff <= 0.0) {
            ReaderDiag.summary = "коэффициент на экране не найден"
            return
        }
        YandexLink.markData(this)

        ReaderDiag.summary = "коэффициент=$coeff; поездка=${if (trip) "да" else "нет"}"

        val update = Intent(this, OverlayWidgetService::class.java).apply {
            putExtra("COEFF", coeff)
        }
        if (overlayOn) {
            runCatching { startService(update) }
        } else {
            RadarEngine.record(this, coeff, null, null, WidgetSettings.load(this).zone)
        }
    }

    private fun collectNodes(node: AccessibilityNodeInfo?, out: MutableList<String>, budget: IntArray) {
        if (node == null || budget[0] <= 0) return
        budget[0]--
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        for (i in 0 until node.childCount) {
            collectNodes(node.getChild(i), out, budget)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
