package com.surge.radar

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.Vibrator

/**
 * Звук. Идёт как «будильник»: так он не пропадает, когда телефон в беззвучном режиме
 * и когда сервис работает в фоне (обычные вибрации фоновых приложений Android игнорирует).
 */
object Feedback {

    fun beep(ctx: Context, high: Boolean) {
        tone(if (high) ToneGenerator.TONE_CDMA_HIGH_L else ToneGenerator.TONE_PROP_BEEP2, if (high) 500 else 250)
    }

    private fun tone(type: Int, ms: Int) {
        try {
            val t = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            t.startTone(type, ms)
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { t.release() } }, ms + 300L)
        } catch (e: Exception) {
            // нет звука: проверьте громкость будильника
        }
    }

    /** Что на телефоне может глушить сигналы: нужно для диагностики. */
    fun deviceState(ctx: Context): String {
        val sb = StringBuilder()
        runCatching {
            val am = ctx.getSystemService(AudioManager::class.java)
            sb.append(
                "Режим звонка: " + when (am.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> "без звука"
                    AudioManager.RINGER_MODE_VIBRATE -> "вибрация"
                    else -> "звук"
                } + "\n"
            )
            sb.append("Громкость будильника: ${am.getStreamVolume(AudioManager.STREAM_ALARM)} из ${am.getStreamMaxVolume(AudioManager.STREAM_ALARM)}\n")
        }
        runCatching {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            val f = nm.currentInterruptionFilter
            sb.append("Не беспокоить: " + (if (f == android.app.NotificationManager.INTERRUPTION_FILTER_ALL) "выключено" else "ВКЛЮЧЕНО") + "\n")
        }
        runCatching {
            val vib = ctx.getSystemService(Vibrator::class.java)
            sb.append("Вибромотор: " + if (vib != null && vib.hasVibrator()) "есть" else "не найден")
        }
        return sb.toString()
    }
}
