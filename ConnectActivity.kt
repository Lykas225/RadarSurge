package com.surge.radar

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Обязательное подключение к Яндекс Про: без него главный экран не открывается. */
class ConnectActivity : AppCompatActivity() {

    private val ctx: Context get() = this
    private val matchP = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrapP = ViewGroup.LayoutParams.WRAP_CONTENT
    private lateinit var p: Palette

    private fun dp(v: Int): Int = dpi(v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Palette(WidgetSettings.load(this).dark)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun button(parent: LinearLayout, t: String, filled: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
        val b = TextView(ctx).apply {
            text = t
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(if (filled && enabled) Color.WHITE else if (enabled) p.text else p.sub)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = if (filled && enabled) {
                GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0xFF2A8C82.toInt(), 0xFF3A7BD5.toInt())
                ).apply { cornerRadius = dp(14).toFloat() }
            } else rounded(if (filled) p.stroke else p.cardAlt, dp(14).toFloat(), p.stroke, dp(1))
            setOnClickListener { onClick() }
        }
        parent.addView(b, LinearLayout.LayoutParams(matchP, wrapP).apply { topMargin = dp(8) })
    }

    private fun step(parent: LinearLayout, done: Boolean, title: String, desc: String): LinearLayout {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, dp(18).toFloat(), if (done) p.accent else p.stroke, dp(1))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(tv(if (done) "✔" else "○", 20f, if (done) p.accent else p.sub, true))
        head.addView(
            tv(title, 17f, p.text, true),
            LinearLayout.LayoutParams(wrapP, wrapP).apply { leftMargin = dp(10) }
        )
        c.addView(head)
        c.addView(tv(desc, 13f, p.sub).apply { setPadding(0, dp(6), 0, 0) })
        parent.addView(c, LinearLayout.LayoutParams(matchP, wrapP).apply { topMargin = dp(12) })
        return c
    }

    private fun fmt(ts: Long): String =
        if (ts <= 0L) "ещё не получены" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

    private fun render() {
        val installed = YandexLink.isInstalled(this)
        val login = YandexLink.isLoginConfirmed(this)
        val access = YandexLink.isAccessEnabled(this)
        val ready = installed && login && access

        val scroll = ScrollView(ctx).apply { setBackgroundColor(p.bg) }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        col.addView(tv("Подключение Яндекс Про", 26f, p.text, true))
        col.addView(
            tv(
                "Чтобы радар видел реальное состояние спроса, войдите в аккаунт в Яндекс Про " +
                    "и разрешите приложению читать его экран. Пароль мы не видим и не храним.",
                14f, p.sub
            ).apply { setPadding(0, dp(8), 0, 0) }
        )

        val gain = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, dp(18).toFloat(), p.stroke, dp(1))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        gain.addView(tv("Что это даёт", 17f, p.text, true))
        gain.addView(
            tv(
                "• Реальный коэффициент с экрана уточняет карту и окошко спроса (зелёная точка)
" +
                    "• Окошко само прячется во время поездки и не закрывает навигацию
" +
                    "• Расписания прибытия самолётов, поездов и автобусов на автовокзалы",
                13f, p.sub
            ).apply { setPadding(0, dp(6), 0, 0) }
        )
        col.addView(gain, LinearLayout.LayoutParams(matchP, wrapP).apply { topMargin = dp(12) })

        val s1 = step(col, installed, "1. Яндекс Про установлен", if (installed) "Приложение найдено." else "Приложение не найдено на телефоне.")
        if (!installed) button(s1, "Установить Яндекс Про", true) { YandexLink.openStore(this) }

        val s2 = step(
            col, login, "2. Вход в аккаунт Яндекс",
            if (login) "Вход в Яндекс Про подтверждён."
            else "Откройте Яндекс Про и войдите в свой аккаунт. После включения доступа (шаг 3) вход подтвердится сам."
        )
        if (installed) button(s2, "Открыть Яндекс Про и войти", !login) { YandexLink.openYandexPro(this) }
        if (!login) {
            button(s2, "Я вошёл в аккаунт", true, installed) {
                YandexLink.setLoginConfirmed(this, true)
                render()
            }
        } else {
            button(s2, "Сбросить подтверждение", false) {
                YandexLink.setLoginConfirmed(this, false)
                render()
            }
        }

        val s3 = step(
            col, access, "3. Доступ к экрану Яндекс Про",
            if (access) "Служба доступа включена." else
                "Включите «Радар Кэфа» в разделе специальных возможностей для чтения коэффициента спроса."
        )
        if (!access) {
            button(s3, "Включить доступ", true) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            s3.addView(
                tv(
                    "Если пункт серый: Настройки → Приложения → Радар Кэфа → ⋮ → «Разрешить ограниченные настройки».",
                    12f, p.sub
                ).apply { setPadding(0, dp(8), 0, 0) }
            )
            button(s3, "Открыть настройки приложения", false) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        }

        val live = YandexLink.isLive(this)
        col.addView(
            tv(
                "Яндекс Про замечен: ${fmt(YandexLink.lastSeen(this))}
" +
                    "Данные коэффициента получены: ${fmt(YandexLink.lastData(this))}" +
                    if (live) "
Реальные данные учитываются в расчёте спроса" else "",
                13f, if (live) p.accent else p.sub
            ).apply { setPadding(0, dp(16), 0, 0) }
        )

        button(col, if (ready) "Продолжить" else "Выполните все шаги", true, ready) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
        }

        scroll.addView(col)
        setContentView(scroll)
    }
}
