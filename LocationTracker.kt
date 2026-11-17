package com.surge.radar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

/**
 * Следит за местоположением и сама переключает рабочую зону на ту, где вы находитесь.
 * Лёгкий режим: обновления сети раз в 30 с или 100 м, плюс бесплатные обновления от других приложений.
 */
class LocationTracker(
    context: Context,
    private val onFix: ((Location) -> Unit)? = null,
    private val onZone: ((SurgeZone, Location) -> Unit)? = null
) {
    private val app = context.applicationContext
    private val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var started = false
    private var lastZone: String? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handle(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun hasPermission(): Boolean =
        app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (started) return true
        if (!hasPermission()) return false
        started = true
        try {
            val main = Looper.getMainLooper()
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30_000L, 100f, listener, main)
            } else if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 30_000L, 100f, listener, main)
            }
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 0f, listener, main)

            // быстрый старт по последнему известному месту
            val last = listOf(
                LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER
            ).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            if (last != null && System.currentTimeMillis() - last.time < 30 * 60_000L) handle(last)
        } catch (e: SecurityException) {
            started = false
            return false
        }
        return true
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { lm.removeUpdates(listener) }
    }

    private fun handle(l: Location) {
        onFix?.invoke(l)
        val z = MoscowRegionGrid.nearest(l.latitude, l.longitude)
        // далеко от всех зон (например, в другом городе): зону не меняем
        if (MoscowRegionGrid.distanceKm(l.latitude, l.longitude, z.lat, z.lon) > 60.0) return
        if (z.name != lastZone) {
            lastZone = z.name
            WidgetSettings.setZone(app, z.name)
            onZone?.invoke(z, l)
        }
    }
}
