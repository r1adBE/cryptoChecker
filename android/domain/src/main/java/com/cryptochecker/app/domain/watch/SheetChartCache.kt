package com.cryptochecker.app.domain.watch

/**
 * Kerzen des Blatts je Paar und Zeitraum im Speicher: 5 Minuten (24 h),
 * 30 Minuten (7 und 30 Tage) bzw. 60 Minuten (1 Jahr), siehe [SheetChartRange.cacheMillis]. Nur Erfolge.
 * Zusätzlich verworfen: von Hand aktualisiert ([invalidate], [invalidateAll]) oder der Live-Kurs
 * ist seit dem Speichern um mehr als [SheetChart.PRICE_JUMP] gesprungen ([SheetChart.priceJumped]).
 */
class SheetChartCache(private val now: () -> Long = System::currentTimeMillis) {
    private class Entry(val time: Long, val value: SheetChartResult.Ready, val price: Double?)

    /** Begrenzt (sonst blieb jedes je geprüfte Paar bis zum Prozessende). */
    private val entries = com.cryptochecker.app.domain.BoundedCache<String, Entry>(com.cryptochecker.app.domain.MEMORY_CACHE_ENTRIES)

    /**
     * [price] = aktueller Kurs des Paars; null = Sprung nicht prüfen. [exchange] = Börse des Paars
     * (eigene Kerzen je Börse, z. B. `CandlePair.cacheKey`); leer = nur Kürzel.
     */
    fun get(base: String, quote: String, range: SheetChartRange, price: Double? = null, exchange: String = ""): SheetChartResult.Ready? {
        val k = key(base, quote, range, exchange)
        val entry = entries[k] ?: return null
        val age = now() - entry.time
        // Bezug: Kurs beim Speichern (Kerzen und Kurs dürfen je nach Börse leicht auseinanderliegen)
        val reference = entry.price ?: entry.value.candles.lastOrNull()?.close
        if (age in 0 until range.cacheMillis && !SheetChart.priceJumped(reference, price)) return entry.value
        entries.remove(k)
        return null
    }

    /** [price] = Kurs des Paars beim Laden (Bezug für den Sprung). */
    fun put(
        base: String, quote: String, range: SheetChartRange, value: SheetChartResult.Ready, price: Double? = null,
        exchange: String = "",
    ) {
        entries[key(base, quote, range, exchange)] = Entry(now(), value, price?.takeIf { it.isFinite() && it > 0.0 })
    }

    /** Alle Zeiträume eines Paars verwerfen (Aktualisieren im Blatt). */
    fun invalidate(base: String, quote: String, exchange: String = "") {
        val prefix = prefix(base, quote, exchange)
        entries.removeKeysIf { it.startsWith(prefix) }
    }

    /** Alles verwerfen (nach unten ziehen in der Merkliste). */
    fun invalidateAll() {
        entries.clear()
    }

    private fun prefix(base: String, quote: String, exchange: String): String =
        (if (exchange.isEmpty()) "" else "$exchange#") + "${base.trim().uppercase()}|${quote.trim().uppercase()}|"

    private fun key(base: String, quote: String, range: SheetChartRange, exchange: String): String =
        prefix(base, quote, exchange) + range.name
}
