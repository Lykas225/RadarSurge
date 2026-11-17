package com.surge.radar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Диагностика: что видит приложение на экране Яндекс Про и проверка звука и вибрации. */
class DiagActivity : AppCompatActivity() {

    private val ctx: Context get() = this
    private val matchP = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrapP = ViewGroup.LayoutParams.WRAP_CONTENT
    private lateinit var p: Palette
    private lateinit var statusView: TextView
    private lateinit var parseView: TextView
    private lateinit var textsView: TextView
    private lateinit var deviceView: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1500L)
        }
    }

    private fun dp(v: Int): Int = dpi(v)

    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun button(parent: LinearLayout, t: String, filled: Boolean, onClick: () -> Unit) {
        val b = TextView(ctx).apply {
            text = t
            textSize = 15f
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
        parent.addView(b, LinearLayout.LayoutParams(matchP, wrapP).apply { topMargin = dp(8) })
    }

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, dp(18).toFloat(), p.stroke, dp(1))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        c.addView(tv(title, 17f, p.text, true))
        parent.addView(c, LinearLayout.LayoutParams(matchP, wrapP).apply { topMargin = dp(12) })
        return c
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Palette(WidgetSettings.load(this).dark)

        val scroll = ScrollView(ctx).apply { setBackgroundColor(p.bg) }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        col.addView(tv("‹ Назад", 14f, p.accent, true).apply {
            setPadding(0, dp(4), dp(12), dp(4))
            setOnClickListener { finish() }
        })
        col.addView(tv("Диагностика", 28f, p.text, true))

        val c1 = card(col, "Связь с Яндекс Про")
        statusView = tv("", 13f, p.sub).apply { setPadding(0, dp(6), 0, 0) }
        c1.addView(statusView)

        val c2 = card(col, "Последний разбор экрана")
        parseView = tv("", 13f, p.accent).apply { setPadding(0, dp(6), 0, 0) }
        c2.addView(parseView)
        textsView = tv("", 11f, p.sub).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, 0)
        }
        c2.addView(textsView)
        button(c2, "Скопировать то, что видит приложение", false) {
            val text = ReaderDiag.summary + "

" + ReaderDiag.texts.joinToString("
")
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("surge", text))
            Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
        }

        val c3 = card(col, "Проверка звука и вибрации")
        button(c3, "Проверить сигнал роста спроса", true) {
            Feedback.beep(ctx, true)
            Toast.makeText(ctx, "Звуковой сигнал отправлен", Toast.LENGTH_SHORT).show()
        }
        deviceView = tv("", 12f, p.sub).apply { setPadding(0, dp(10), 0, 0) }
        c3.addView(deviceView)

        scroll.addView(col)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun ago(ts: Long): String =
        if (ts <= 0L) "нет" else "${(System.currentTimeMillis() - ts) / 1000} с назад"

    private fun render() {
        val cfg = WidgetSettings.load(this)
        statusView.text =
            "Доступ к экрану: " + (if (YandexLink.isAccessEnabled(this)) "включён" else "ВЫКЛЮЧЕН") + "
" +
                "Показ поверх приложений: " + (if (Settings.canDrawOverlays(this)) "разрешён" else "НЕ РАЗРЕШЁН") + "
" +
                "Виджет включён в приложении: " + (if (cfg.overlayOn) "да" else "НЕТ") + "
" +
                "События от Яндекс Про: ${ReaderDiag.events}, последнее ${ago(ReaderDiag.lastEventAt)}
" +
                "Разборов экрана: ${ReaderDiag.scans}, пустое дерево: ${ReaderDiag.nullRoots}"
        parseView.text = if (ReaderDiag.summary.isEmpty()) "Пока ничего не прочитано. Откройте Яндекс Про." else ReaderDiag.summary
        textsView.text = ReaderDiag.texts.take(60).joinToString("
") { "• " + it.take(70) }
        deviceView.text = Feedback.deviceState(this)
    }
}
