package com.cryptochecker.app.domain.starter

/**
 * Start-Merkliste: die fünf grössten Coins (siehe [StarterCoins]) mit einem Tipp.
 * Reines Kotlin (testbar).
 *
 * Paar: Binance Spot «<COIN>/USDT» (Paar-Id wie in der Binance-Paarliste: «BTCUSDT»), in allen Regionen
 * ausser den USA (auch Schweiz/Liechtenstein, Entscheid 15. Oktober 2026); in den USA Binance.US
 * «<COIN>/USDT» (gleiche Schnittstelle, `api.binance.us`). Die Börsen-Schlüssel entsprechen Market.key in
 * MarketsConfig («Binance», «BinanceUs»). Binance liefert eine gleitende 24-h-Änderung.
 */
object StarterPairs {

    /** Coin-Name (nicht übersetzt) und Symbol. */
    data class Coin(val name: String, val symbol: String)

    /** Ein anzulegendes Paar. */
    data class Pair(
        val marketKey: String,
        val marketName: String,
        val base: String,
        val quote: String,
        val pairId: String,
    )

    const val BINANCE_KEY = "Binance"
    const val BINANCE_US_KEY = "BinanceUs"

    /** Quote der Start-Paare (Binance und Binance.US). */
    const val START_QUOTE = "USDT"

    private fun region(regionCountry: String?) = regionCountry?.trim()?.uppercase()

    fun isUs(regionCountry: String?): Boolean = region(regionCountry) == "US"

    // Standort-Sperre: Binance antwortet z. B. mit Geräte-Region Schweiz im US-Netz mit HTTP 451

    /** Kleine Anfrage an denselben Host wie die Binance-Kurse (der Spiegel data-api.binance.vision ist nie gesperrt). */
    const val BINANCE_PROBE_URL = "https://api.binance.com/api/v3/ping"

    /** HTTP 451 («restricted location») bzw. 403 (Geo-Sperre) von Binance. */
    fun isGeoBlocked(httpCode: Int?): Boolean = httpCode == 451 || httpCode == 403

    /** Region mit Sperre: ausserhalb der USA, aber Binance gesperrt → «US» (Binance.US). Nur für den Startschritt. */
    fun effectiveRegion(regionCountry: String?, binanceBlocked: Boolean): String? =
        if (binanceBlocked && !isUs(regionCountry)) "US" else regionCountry

    /** Nur ausserhalb der Geräte-Region USA prüfen (dort gilt ohnehin Binance.US). */
    fun shouldProbe(deviceRegion: String?): Boolean = !isUs(deviceRegion)

    /**
     * Neuer Stand der Sperre nach der Prüfung: [httpCode] null = kein Netz/Zeit um (Stand bleibt), 2xx = erreichbar,
     * 451/403 = gesperrt, anderer Fehler = Stand bleibt. Wie iOS.
     */
    fun blockedAfterProbe(previous: Boolean, httpCode: Int?): Boolean = when {
        httpCode == null -> previous
        httpCode in 200..299 -> false
        isGeoBlocked(httpCode) -> true
        else -> previous
    }

    /** Start-Börse der Region (Schlüssel wie Market.key). */
    fun marketKey(regionCountry: String?): String = if (isUs(regionCountry)) BINANCE_US_KEY else BINANCE_KEY

    /**
     * Anzeigename der Start-Börse der Region («Binance», bei Standort-Sperre bzw. in den USA «Binance.US»): die
     * Start-Auswahl sagt laut, woher die Kurse kommen («Kurse von Binance in USDT»).
     * @param marketName Anzeigename aus der Registry; null = Schlüssel
     */
    fun marketName(regionCountry: String?, marketName: (String) -> String? = { null }): String {
        val key = marketKey(regionCountry)
        return marketName(key) ?: key
    }

    /** Anzeige-Quote für Kurse ohne eigene Quote. */
    @Suppress("UNUSED_PARAMETER")
    fun startQuote(regionCountry: String?): String = START_QUOTE

    /**
     * @param regionCountry Ländercode der Geräte-Region (z. B. «US», «CH»)
     * @param marketName Anzeigename der Börse aus der Registry; null = Schlüssel
     */
    fun pairFor(
        symbol: String,
        regionCountry: String?,
        marketName: (String) -> String? = { null },
    ): Pair {
        val base = symbol.trim().uppercase()
        val key = marketKey(regionCountry)
        return Pair(key, marketName(key) ?: key, base, START_QUOTE, base + START_QUOTE)
    }

    /**
     * Welche der gewünschten Paare noch fehlen — bereits vorhandene (gleiche Börse,
     * Basis und Quote, Spot) werden nicht doppelt angelegt.
     * @param existing vorhandene Einträge als (Börsen-Schlüssel, Basis, Quote)
     */
    fun missing(wanted: List<Pair>, existing: Collection<Triple<String, String, String>>): List<Pair> {
        val have = existing.map { (m, b, q) -> Triple(m, b.uppercase(), q.uppercase()) }.toSet()
        return wanted
            .distinctBy { Triple(it.marketKey, it.base, it.quote) }
            .filter { Triple(it.marketKey, it.base.uppercase(), it.quote.uppercase()) !in have }
    }
}
