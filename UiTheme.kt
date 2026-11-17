package com.surge.radar

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build

/**
 * Спокойная, не утомляющая глаза цветовая гамма:
 * - Тёмная тема: глубокий графитово-сланцевый фон (#12171D) и мягкие карточки (#1B232C) с приглушённым мятно-бирюзовым акцентом (#4DB6AC).
 * - Светлая тема: тёплый алебастрово-кремовый фон (#F4F5F2) и карточки (#FFFFFF) с глубоким шалфейно-бирюзовым акцентом (#2A8C82).
 */
class Palette(val dark: Boolean) {
    val bg = if (dark) 0xFF12171D.toInt() else 0xFFF4F5F2.toInt()
    val card = if (dark) 0xFF1B232C.toInt() else 0xFFFFFFFF.toInt()
    val cardAlt = if (dark) 0xFF232D38.toInt() else 0xFFECEEE9.toInt()
    val text = if (dark) 0xFFE8EDF2.toInt() else 0xFF1E262E.toInt()
    val sub = if (dark) 0xFF93A1B0.toInt() else 0xFF5E6C7A.toInt()
    val accent = if (dark) 0xFF4DB6AC.toInt() else 0xFF2A8C82.toInt()
    val blue = if (dark) 0xFF5C9CE6.toInt() else 0xFF3A7BD5.toInt()
    val stroke = if (dark) 0xFF2B3642.toInt() else 0xFFD9DDD6.toInt()
    val warn = if (dark) 0xFFE5A95C.toInt() else 0xFFC88228.toInt()
}

fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun withAlpha(color: Int, a: Float): Int =
    Color.argb((255f * a).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

fun rounded(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

/** Шрифт нужной жирности (100–900). До Android 9 доступны только обычный и жирный. */
fun weightTypeface(weight: Int): Typeface =
    if (Build.VERSION.SDK_INT >= 28) {
        Typeface.create(Typeface.DEFAULT, weight.coerceIn(100, 900), false)
    } else {
        Typeface.create(Typeface.DEFAULT, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
    }
