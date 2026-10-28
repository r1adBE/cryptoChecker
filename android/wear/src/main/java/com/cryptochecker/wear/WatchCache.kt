package com.cryptochecker.wear

/** Gespeicherter Kurs eines Coins mit der Zeit seines Abrufs (Epoch-ms, «Stand des Abrufs»). */
data class WatchCached(val quote: WatchQuote, val time: Long)

/**
 * Zuletzt geladene Kurse der Uhr, je Coin mit eigener Zeit: ein Abruf ersetzt nur die Coins, die er liefert; die
 * anderen behalten ihren letzten Kurs samt dessen Zeit (Teil-Ausfall, z. B. Zeitüberschreitung bei einem Coin).
 * «Veraltet» (`WatchFreshness`) und «Stand» gelten deshalb je Coin. Wie iOS `WatchCache` (Paritäts-Fixture
 * `watch_quotes.json`, Abschnitt «cache»).
 */
object WatchCache {
    /**
     * [fresh] (Abruf zur Zeit [time]) in [old] übernehmen. Behalten werden nur Coins aus [keep] (beide Listen der
     * Wahl), damit alte Favoriten den Speicher nicht füllen.
     */
    fun merge(
        old: Map<String, WatchCached>,
        fresh: Map<String, WatchQuote>,
        time: Long,
        keep: Collection<String>,
    ): Map<String, WatchCached> {
        val out = LinkedHashMap<String, WatchCached>()
        old.forEach { (coin, cached) -> if (coin in keep) out[coin] = cached }
        fresh.forEach { (coin, quote) -> if (coin in keep) out[coin] = WatchCached(quote, time) }
        return out
    }

    /** Stand der Liste [coins]: Zeit des ältesten gezeigten Kurses (0 = keiner geladen). */
    fun listTime(cache: Map<String, WatchCached>, coins: List<String>): Long =
        coins.mapNotNull { cache[it]?.time }.minOrNull() ?: 0L
}

/**
 * Laufnummer der Abrufe: [start] macht jeden früheren Abruf ungültig; ein Ergebnis gilt nur, wenn seine Nummer noch
 * die neueste ist und die gezeigte Liste noch die abgefragte. Nur von einem Thread aus benutzen (Hauptthread).
 * Wie iOS `WatchFetchGate`.
 */
class WatchFetchGate {
    private var generation = 0L

    fun start(): Long = ++generation

    fun accepts(run: Long, asked: List<String>, shown: List<String>): Boolean = run == generation && asked == shown
}
