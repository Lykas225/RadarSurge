package com.surge.radar

import android.appwidget.AppWidgetManager
import android.content.ComponentName
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private val ctx: Context get() = this
    private val matchP = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrapP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var p: Palette
    private lateinit var previewBox: FrameLayout
    private var values = WidgetSettings.Values()
    private var previewMode = -1

    private fun dp(v: Int): Int = dpi(v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        values = WidgetSettings.load(this)
        p = Palette(values.dark)
        build()
    }

    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun card(parent: LinearLayout, title: String, subtitle: String? = null): LinearLayout {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, dp(18).toFloat(), p.stroke, dp(1))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        c.addView(tv(title, 17f, p.text, true))
        if (subtitle != null) c.addView(tv(subtitle, 12f, p.sub))
        parent.addView(c, LinearLayout.LayoutParams(matchP, wrapP).apply { topMargin = dp(12) })
        return c
    }

    private fun toggle(parent: LinearLayout, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val s = Switch(ctx).apply {
            text = title
            textSize = 15f
            isChecked = checked
            setTextColor(p.text)
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        parent.addView(s, LinearLayout.LayoutParams(matchP, wrapP))
    }

    private fun slider(
        parent: LinearLayout,
        title: String,
        lo: Int,
        hi: Int,
        start: Int,
        fmt: (Int) -> String = { "$it%" },
        onChange: (Int) -> Unit
    ) {
        val caption = tv("", 15f, p.text).apply { setPadding(0, dp(12), 0, 0) }
        fun show(v: Int) {
            caption.text = "$title: ${fmt(v)}"
        }
        show(start)
        val bar = SeekBar(ctx).apply {
            max = hi - lo
            progress = (start - lo).coerceIn(0, hi - lo)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, pr: Int, fromUser: Boolean) {
                    val v = pr + lo
                    show(v)
                    if (fromUser) onChange(v)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        parent.addView(caption)
        parent.addView(bar)
    }

    private fun radios(
        parent: LinearLayout,
        idBase: Int,
        names: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit
    ) {
        val group = RadioGroup(ctx).apply { orientation = RadioGroup.VERTICAL }
        names.forEachIndexed { i, n ->
            val rb = RadioButton(ctx).apply {
                id = idBase + i
                text = n
                textSize = 15f
                setTextColor(p.text)
            }
            group.addView(rb)
        }
        group.check(idBase + selected)
        group.setOnCheckedChangeListener { _, id -> onSelect(id - idBase) }
        parent.addView(group)
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

    private fun build() {
        val scroll = ScrollView(ctx).apply { setBackgroundColor(p.bg) }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        val back = tv("‹ Назад", 14f, p.accent, true).apply {
            setPadding(0, dp(4), dp(12), dp(4))
            setOnClickListener { finish() }
        }
        col.addView(back)
        col.addView(tv("Настройки", 28f, p.text, true))

        val themeCard = card(col, "Тема оформления")
        toggle(themeCard, "Тёмная тема (мягкий графит)", values.dark) { update(values.copy(dark = it)) }

        val tariffCard = card(col, "Тарифы", "Показываются на карте, в виджете и в меню")
        for (t in Tariff.values()) {
            toggle(tariffCard, t.title, values.isOn(t)) { on ->
                val mask = if (on) values.tariffMask or t.bit else values.tariffMask and t.bit.inv()
                update(values.copy(tariffMask = mask))
            }
        }

        val gpsCard = card(col, "Местоположение")
        toggle(gpsCard, "Коэффициент по моему месту (GPS)", values.useGps) { on ->
            update(values.copy(useGps = on))
            if (on) {
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    31
                )
            }
        }
        gpsCard.addView(
            tv(
                "Зона для окошка, виджета и меню сама переключается на ту, где вы находитесь. " +
                    "Вручную зону можно закрепить долгим нажатием на карте, пока вы не переедете в другую.",
                12f, p.sub
            ).apply { setPadding(0, dp(4), 0, 0) }
        )

        val mapCard = card(col, "Интерактивная карта", "вся Москва и все города Подмосковья")
        toggle(mapCard, "Анимация всплесков спроса", values.mapAnim) { update(values.copy(mapAnim = it)) }
        slider(mapCard, "Интенсивность свечения зон", 10, 100, values.glow) { update(values.copy(glow = it)) }
        val flagOpts = listOf(5, 10, 15, 30, 60, 120, 180, 300, 600)
        val flagIdx = flagOpts.indexOfFirst { it >= values.flagSec }.let { if (it < 0) flagOpts.size - 1 else it }
        slider(
            mapCard, "Время флажков на карте", 0, flagOpts.size - 1, flagIdx,
            { i -> val sec = flagOpts[i]; if (sec < 60) "$sec с" else "${sec / 60} мин" }
        ) { update(values.copy(flagSec = flagOpts[it])) }
        mapCard.addView(tv("Стиль карты", 15f, p.sub).apply { setPadding(0, dp(12), 0, 0) })
        radios(mapCard, 3000, listOf("Спокойная (CARTO)", "Стандартная (OpenStreetMap)"), values.mapStyle) {
            update(values.copy(mapStyle = it))
        }

        val wCard = card(col, "Виджет поверх экрана", "компактный, настраиваемый")
        previewBox = FrameLayout(ctx).apply {
            setBackgroundColor(Color.rgb(44, 53, 64))
            setPadding(dp(8), dp(14), dp(8), dp(14))
        }
        wCard.addView(previewBox, LinearLayout.LayoutParams(matchP, wrapP).apply { topMargin = dp(10) })

        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val chipDefs = listOf("Мини" to 0, "Окно" to 1, "🔥 Список (2× тап)" to 2)
        for ((label, mode) in chipDefs) {
            val chip = tv(label, 12f, p.accent, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(7), dp(8), dp(7))
                background = rounded(0, dp(12).toFloat(), p.stroke, dp(1))
                setOnClickListener {
                    previewMode = mode
                    refreshPreview()
                }
            }
            chips.addView(chip, LinearLayout.LayoutParams(0, wrapP, 1f).apply { rightMargin = dp(4) })
        }
        wCard.addView(chips, LinearLayout.LayoutParams(matchP, wrapP).apply { topMargin = dp(8) })

        wCard.addView(tv("Вид виджета", 15f, p.sub).apply { setPadding(0, dp(12), 0, 0) })
        radios(
            wCard, 4000,
            listOf("Мини-окошко: тап раскрывает на 6 с", "Компактное окно всегда на экране"),
            values.widgetMode
        ) { update(values.copy(widgetMode = it)) }
        slider(
            wCard, "Скрытие по долгому нажатию", 1, 60, values.snoozeMin,
            { "$it мин" }
        ) { update(values.copy(snoozeMin = it)) }
        toggle(wCard, "Скрывать окошко во время поездки", values.hideInTrip) { update(values.copy(hideInTrip = it)) }

        slider(wCard, "Размер шрифта", 80, 150, values.fontPct) { update(values.copy(fontPct = it)) }
        slider(
            wCard, "Жирность шрифта", 3, 9, (values.fontWeight / 100).coerceIn(3, 9),
            { "${it * 100}" }
        ) { update(values.copy(fontWeight = it * 100)) }
        slider(wCard, "Размер виджета", 70, 130, values.scale) { update(values.copy(scale = it)) }
        slider(wCard, "Прозрачность фона", 10, 100, values.bgAlpha) { update(values.copy(bgAlpha = it)) }
        slider(wCard, "Прозрачность всего виджета", 30, 100, values.widgetAlpha) { update(values.copy(widgetAlpha = it)) }

        wCard.addView(tv("Что показывать", 15f, p.sub).apply { setPadding(0, dp(14), 0, 0) })
        toggle(wCard, "Коэффициент спроса (×1.5)", values.showDemand) { update(values.copy(showDemand = it)) }
        toggle(wCard, "Время ожидания", values.showWait) { update(values.copy(showWait = it)) }
        toggle(wCard, "Радар", values.showRadar) { update(values.copy(showRadar = it)) }
        toggle(wCard, "График прогноза", values.showGraph) { update(values.copy(showGraph = it)) }
        toggle(wCard, "Звуковой сигнал при росте спроса", values.sound) { update(values.copy(sound = it)) }

        wCard.addView(tv("Частота обновления", 15f, p.sub).apply { setPadding(0, dp(14), 0, 0) })
        val freqs = listOf(1, 5, 15, 30)
        val freqNames = listOf("Каждую минуту", "Каждые 5 минут", "Каждые 15 минут", "Каждые 30 минут")
        val cur = freqs.indexOf(values.refreshMin).let { if (it < 0) 1 else it }
        radios(wCard, 2000, freqNames, cur) { i -> update(values.copy(refreshMin = freqs[i])) }

        val dataCard = card(col, "Откуда данные")
        dataCard.addView(
            tv(
                "Спрос считается по времени суток, дню недели, типу района и погоде в Москве. " +
                    "Когда на экране открыт Яндекс Про, реальные коэффициенты уточняют расчёт.",
                13f, p.sub
            ).apply { setPadding(0, dp(6), 0, 0) }
        )

        val hotCard = card(col, "Горячие места и расписания", "двойной тап по карте или по виджету")
        slider(
            hotCard, "Показывать от коэффициента", 11, 25, (values.hotMin / 10 + 10).coerceIn(11, 25),
            { String.format(java.util.Locale.US, "×%.1f", it / 10.0) }
        ) { update(values.copy(hotMin = (it - 10) * 10)) }
        for (k in PoiKind.values()) {
            toggle(hotCard, "${k.emoji} ${k.title}", (values.hotCats and k.bit) != 0) { on ->
                val m = if (on) values.hotCats or k.bit else values.hotCats and k.bit.inv()
                update(values.copy(hotCats = m))
            }
        }
        toggle(hotCard, "Загружать расписания онлайн", values.schedOnline) { update(values.copy(schedOnline = it)) }
        val keyBox = EditText(ctx).apply {
            hint = "Ключ API Яндекс Расписаний"
            setText(ScheduleProvider.key(ctx))
            setSingleLine()
            textSize = 14f
            setTextColor(p.text)
            setHintTextColor(p.sub)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0, dp(12).toFloat(), p.stroke, dp(1))
        }
        hotCard.addView(keyBox, LinearLayout.LayoutParams(matchP, wrapP).apply { topMargin = dp(10) })
        val hotStatus = tv(HotSpots.statusLine(this), 12f, p.sub).apply { setPadding(0, dp(8), 0, 0) }
        button(hotCard, "Сохранить ключ и обновить расписания", false) {
            ScheduleProvider.setKey(ctx, keyBox.text.toString())
            HotSpots.refreshAsync(ctx, true) { hotStatus.text = HotSpots.statusLine(ctx) }
            hotStatus.text = HotSpots.statusLine(ctx)
            Toast.makeText(ctx, "Запрос отправлен, расписания обновятся через несколько секунд", Toast.LENGTH_SHORT).show()
        }
        hotCard.addView(hotStatus)
        hotCard.addView(
            tv(
                "Без ключа аэропорты, ж/д вокзалы и автовокзалы считаются по времени суток. Бесплатный ключ выдаёт кабинет " +
                    "разработчика Яндекса для API Яндекс Расписаний (раздел «Доступ» в документации).",
                11f, p.sub
            ).apply { setPadding(0, dp(6), 0, 0) }
        )

        val diagCard = card(col, "Диагностика")
        diagCard.addView(
            tv(
                "Что видит приложение на экране Яндекс Про, проверка звука и вибрации.",
                12f, p.sub
            ).apply { setPadding(0, dp(6), 0, 0) }
        )
        button(diagCard, "Открыть диагностику", false) { startActivity(Intent(ctx, DiagActivity::class.java)) }

        val yaCard = card(col, "Подключение Яндекс Про")
        val ok = YandexLink.ready(this)
        yaCard.addView(
            tv(
                (if (ok) "Подключено" else "Не подключено") +
                    (if (YandexLink.isLive(this)) ", реальные данные поступают" else ""),
                13f, if (ok) p.accent else p.sub
            ).apply { setPadding(0, dp(6), 0, 0) }
        )
        button(yaCard, "Проверить подключение", false) {
            startActivity(Intent(ctx, ConnectActivity::class.java))
        }

        val permCard = card(col, "Разрешения и виджет на экран")
        button(permCard, "Показ поверх других приложений", false) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button(permCard, "Добавить виджет на главный экран", false) { pinHomeWidget() }

        button(col, "Сохранить настройки", true) {
            Toast.makeText(ctx, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            finish()
        }

        scroll.addView(col)
        setContentView(scroll)
        refreshPreview()
    }

    private fun update(v: WidgetSettings.Values) {
        val themeChanged = v.dark != values.dark
        values = v
        WidgetSettings.save(this, v)
        SurgeWidgetProvider.refreshAll(this)
        if (themeChanged) {
            recreate()
            return
        }
        refreshPreview()
    }

    private fun refreshPreview() {
        previewBox.removeAllViews()
        val h = WidgetHost(ctx, values)
        h.card.bind(WidgetData.sample(values.enabledTariffs()))
        h.pill.bind(WidgetData.sample(values.enabledTariffs()).rows, "↗", true)
        when (previewMode) {
            0 -> h.show(WidgetHost.Mode.PILL)
            1 -> h.show(WidgetHost.Mode.CARD)
            2 -> {
                h.show(WidgetHost.Mode.HOT)
                h.hot.bind(HotSpots.compute(ctx, values).take(5), values.hotMin)
            }
            else -> h.show(if (values.widgetMode == 1) WidgetHost.Mode.CARD else WidgetHost.Mode.PILL)
        }
        previewBox.addView(h, FrameLayout.LayoutParams(wrapP, wrapP, Gravity.CENTER))
    }

    private fun pinHomeWidget() {
        val mgr = AppWidgetManager.getInstance(this)
        if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, SurgeWidgetProvider::class.java), null, null)
        } else {
            Toast.makeText(
                this,
                "Долгое нажатие на рабочем столе → Виджеты → SurgeRadar",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
