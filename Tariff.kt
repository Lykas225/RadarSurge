package com.surge.radar

/** k — во сколько раз коэффициент тарифа «мягче», чем у Эконома. */
enum class Tariff(val title: String, val shortTitle: String, val bit: Int, val k: Double) {
    ECONOM("Эконом", "Эко", 1, 1.0),
    COMFORT("Комфорт", "Ком", 2, 0.85),
    COMFORT_PLUS("Комфорт+", "Ком+", 4, 0.7)
}
