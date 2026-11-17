package com.surge.radar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Подключение к Яндекс Про. У Яндекса нет открытого API заказов и спроса, поэтому приложение
 * читает экран самого Яндекс Про через службу доступности. Для этого нужно: Яндекс Про установлен,
 * водитель вошёл в аккаунт в нём, а наша служба доступности включена.
 */
object YandexLink {
    const val PKG = "ru.yandex.taximeter"
    private const val PREFS = "yandex_link"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isInstalled(c: Context): Boolean =
        runCatching { c.packageManager.getLaunchIntentForPackage(PKG) != null }.getOrDefault(false)

    fun isAccessEnabled(c: Context): Boolean {
        val enabled = Settings.Secure.getString(
            c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val cn = ComponentName(c, TaximeterReaderService::class.java)
        val full = cn.flattenToString()
        val short = cn.flattenToShortString()
        return enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }

    fun isLoginConfirmed(c: Context): Boolean = prefs(c).getBoolean("login", false)

    fun setLoginConfirmed(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean("login", v).apply()
    }

    /** Все три условия выполнены: без этого главный экран не открывается. */
    fun ready(c: Context): Boolean = isInstalled(c) && isLoginConfirmed(c) && isAccessEnabled(c)

    /** Кнопка «Принять» уже встречалась рядом с ценой заказа: тогда предложением считается только экран с ней. */
    fun acceptLearned(c: Context): Boolean = prefs(c).getBoolean("accept", false)

    fun setAcceptLearned(c: Context) {
        if (!acceptLearned(c)) prefs(c).edit().putBoolean("accept", true).apply()
    }

    fun markSeen(c: Context) {
        val now = System.currentTimeMillis()
        val p = prefs(c)
        if (now - p.getLong("seen", 0L) > 20_000L) p.edit().putLong("seen", now).apply()
    }

    fun markData(c: Context) {
        val now = System.currentTimeMillis()
        val p = prefs(c)
        if (now - p.getLong("data", 0L) > 5_000L) p.edit().putLong("data", now).apply()
    }

    fun lastSeen(c: Context): Long = prefs(c).getLong("seen", 0L)

    fun lastData(c: Context): Long = prefs(c).getLong("data", 0L)

    /** Реальные данные Яндекс Про получены недавно. */
    fun isLive(c: Context): Boolean {
        val t = lastData(c)
        return t > 0L && System.currentTimeMillis() - t < 30 * 60_000L
    }

    fun openYandexPro(c: Context) {
        val i = c.packageManager.getLaunchIntentForPackage(PKG) ?: return
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openStore(c: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PKG"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            c.startActivity(market)
        } catch (e: Exception) {
            c.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PKG"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
