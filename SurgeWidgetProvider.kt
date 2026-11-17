package com.surge.radar

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

/** Виджет домашнего экрана: спрос по включённым тарифам для выбранной зоны. */
class SurgeWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.surge.radar.WIDGET_REFRESH"

        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, SurgeWidgetProvider::class.java))
            for (id in ids) mgr.updateAppWidget(id, buildViews(ctx))
        }

        private fun pending(ctx: Context): PendingIntent {
            val i = Intent(ctx, SurgeWidgetProvider::class.java).setAction(ACTION_REFRESH)
            return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun scheduleAlarm(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val interval = WidgetSettings.load(ctx).refreshMin * 60_000L
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + interval,
                interval,
                pending(ctx)
            )
        }

        fun cancelAlarm(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending(ctx))
        }

        private fun pillRes(pct: Int): Int = when {
            pct >= 100 -> R.drawable.pill_magenta
            pct >= 60 -> R.drawable.pill_orange
            pct >= 15 -> R.drawable.pill_green
            else -> R.drawable.pill_gray
        }

        private fun bold(s: String, on: Boolean): CharSequence {
            val sp = SpannableString(s)
            if (on) sp.setSpan(StyleSpan(Typeface.BOLD), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return sp
        }

        private fun buildViews(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_surge)
            val cfg = WidgetSettings.load(ctx)
            val zone = MoscowRegionGrid.zones.firstOrNull { it.name == cfg.zone } ?: MoscowRegionGrid.zones.first()
            val tariffs = cfg.enabledTariffs()
            val base = DemandModel.baseCoeff(zone)
            val fk = cfg.fontPct / 100f
            val boldName = cfg.fontWeight >= 700

            v.setTextViewText(R.id.w_zone, zone.name.removePrefix("МО: "))
            v.setTextViewTextSize(R.id.w_zone, TypedValue.COMPLEX_UNIT_SP, 11f * fk)
            v.setTextViewTextSize(R.id.w_title, TypedValue.COMPLEX_UNIT_SP, 13f * fk)

            val rows = intArrayOf(R.id.w_r1, R.id.w_r2, R.id.w_r3)
            val names = intArrayOf(R.id.w_n1, R.id.w_n2, R.id.w_n3)
            val pcts = intArrayOf(R.id.w_p1, R.id.w_p2, R.id.w_p3)
            val waits = intArrayOf(R.id.w_t1, R.id.w_t2, R.id.w_t3)

            for (i in rows.indices) {
                val t = tariffs.getOrNull(i)
                if (t == null) {
                    v.setViewVisibility(rows[i], if (i == 0) View.VISIBLE else View.GONE)
                    if (i == 0) {
                        v.setTextViewText(names[i], "Тарифы выключены")
                        v.setViewVisibility(pcts[i], View.GONE)
                        v.setViewVisibility(waits[i], View.GONE)
                    }
                    continue
                }
                val c = DemandModel.scale(base, t)
                val pct = DemandModel.pct(c)
                v.setViewVisibility(rows[i], View.VISIBLE)
                v.setTextViewText(names[i], bold(t.title, boldName))
                v.setTextViewTextSize(names[i], TypedValue.COMPLEX_UNIT_SP, 14f * fk)

                v.setViewVisibility(pcts[i], if (cfg.showDemand) View.VISIBLE else View.GONE)
                v.setTextViewText(pcts[i], bold(DemandModel.coefText(pct), true))
                v.setTextColor(pcts[i], DemandModel.textColor(pct))
                v.setTextViewTextSize(pcts[i], TypedValue.COMPLEX_UNIT_SP, 14f * fk)
                v.setInt(pcts[i], "setBackgroundResource", pillRes(pct))

                v.setViewVisibility(waits[i], if (cfg.showWait) View.VISIBLE else View.GONE)
                v.setTextViewText(waits[i], "~${DemandModel.waitMinutes(c)} мин")
                v.setTextViewTextSize(waits[i], TypedValue.COMPLEX_UNIT_SP, 12f * fk)
            }

            v.setOnClickPendingIntent(R.id.w_refresh, pending(ctx))
            val open = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.w_root, open)
            return v
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, buildViews(context))
        WeatherProvider.refreshAsync { refreshAll(context) }
        scheduleAlarm(context)
    }

    override fun onEnabled(context: Context) {
        scheduleAlarm(context)
    }

    override fun onDisabled(context: Context) {
        cancelAlarm(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            WeatherProvider.refreshAsync(true) { refreshAll(context) }
            refreshAll(context)
        }
    }
}
