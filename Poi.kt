package com.surge.radar

enum class PoiKind(val emoji: String, val title: String, val bit: Int) {
    AIRPORT("✈", "Аэропорты", 1),
    STATION("🚆", "Ж/д вокзалы", 2),
    BUS_STATION("🚌", "Автовокзалы", 4),
    HOSPITAL("🏥", "Больницы", 8)
}

/** Место притяжения пассажиров. match: часть названия станции/остановки в Яндекс Расписаниях. */
class Poi(
    val name: String,
    val kind: PoiKind,
    val lat: Double,
    val lon: Double,
    val capacity: Int = 0,
    val match: String = ""
)

object PoiCatalog {
    val fixed: List<Poi> = listOf(
        // Аэропорты
        Poi("Шереметьево", PoiKind.AIRPORT, 55.9726, 37.4146, 0, "Шереметьево"),
        Poi("Домодедово", PoiKind.AIRPORT, 55.4086, 37.9063, 0, "Домодедово"),
        Poi("Внуково", PoiKind.AIRPORT, 55.5915, 37.2615, 0, "Внуково"),
        Poi("Жуковский", PoiKind.AIRPORT, 55.5533, 38.1500, 0, "Жуковск"),

        // Железнодорожные вокзалы
        Poi("Ярославский вокзал", PoiKind.STATION, 55.7777, 37.6575, 0, "Ярославск"),
        Poi("Казанский вокзал", PoiKind.STATION, 55.7731, 37.6559, 0, "Казанск"),
        Poi("Ленинградский вокзал", PoiKind.STATION, 55.7765, 37.6554, 0, "Ленинградск"),
        Poi("Белорусский вокзал", PoiKind.STATION, 55.7777, 37.5820, 0, "Белорусск"),
        Poi("Киевский вокзал", PoiKind.STATION, 55.7440, 37.5656, 0, "Киевск"),
        Poi("Курский вокзал", PoiKind.STATION, 55.7570, 37.6600, 0, "Курск"),
        Poi("Павелецкий вокзал", PoiKind.STATION, 55.7297, 37.6393, 0, "Павелецк"),
        Poi("Савёловский вокзал", PoiKind.STATION, 55.7940, 37.5870, 0, "Савеловск"),
        Poi("Рижский вокзал", PoiKind.STATION, 55.7936, 37.6360, 0, "Рижск"),

        // Автовокзалы Москвы
        Poi("Автовокзал Центральный (Щёлковский)", PoiKind.BUS_STATION, 55.8108, 37.7986, 0, "Щелковск"),
        Poi("Автовокзал Северные Ворота", PoiKind.BUS_STATION, 55.8772, 37.4795, 0, "Северные ворота"),
        Poi("Автовокзал Саларьево", PoiKind.BUS_STATION, 55.6227, 37.4240, 0, "Саларьево"),
        Poi("Автовокзал Южные Ворота", PoiKind.BUS_STATION, 55.6197, 37.7818, 0, "Южные ворота"),
        Poi("Автостанция Орехово", PoiKind.BUS_STATION, 55.6122, 37.6948, 0, "Орехово"),
        Poi("Автостанция Котельники", PoiKind.BUS_STATION, 55.6743, 37.8582, 0, "Котельники"),
        Poi("Автостанция Новоясеневская", PoiKind.BUS_STATION, 55.6012, 37.5542, 0, "Новоясеневск"),
        Poi("Автостанция Тёплый Стан", PoiKind.BUS_STATION, 55.6187, 37.5076, 0, "Теплый Стан"),

        // Больницы
        Poi("Больница им. Пирогова", PoiKind.HOSPITAL, 55.7076, 37.5868),
        Poi("Боткинская больница", PoiKind.HOSPITAL, 55.7792, 37.5608),
        Poi("НИИ Склифосовского", PoiKind.HOSPITAL, 55.7728, 37.6340),
        Poi("Госпиталь Бурденко", PoiKind.HOSPITAL, 55.7662, 37.7060),
        Poi("Сеченовский университет", PoiKind.HOSPITAL, 55.7292, 37.5705)
    )

    fun byKinds(mask: Int): List<Poi> = fixed.filter { (it.kind.bit and mask) != 0 }
}
