package com.surge.radar

/** Что видит служба чтения экрана Яндекс Про. Хранится в памяти и показывается на экране диагностики. */
object ReaderDiag {
    @Volatile var events = 0L
    @Volatile var lastEventAt = 0L
    @Volatile var scans = 0L
    @Volatile var nullRoots = 0L
    @Volatile var texts: List<String> = emptyList()
    @Volatile var summary = ""
    @Volatile var sentOrders = 0L
    @Volatile var lastOrderAt = 0L
}
