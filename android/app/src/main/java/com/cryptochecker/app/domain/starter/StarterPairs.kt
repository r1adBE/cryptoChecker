package com.cryptochecker.app.domain.starter

/**
 * Start-Merkliste: die fünf grössten Coins (siehe [StarterCoins]) mit einem Tipp.
 * Reines Kotlin (testbar).
 *
 * Paar: Binance Spot «<COIN>/USDT» (Paar-Id wie in der Binance-Paarliste: «BTCUSDT»),
 * ausser die Region des Geräts ist die USA: dort Coinbase «<COIN>/USD»
 * (Produkt-Id wie bei Coinbase: «BTC-USD»). Die Börsen-Schlüssel entsprechen
 * Market.key in MarketsConfig (Klassenname: «Binance», «Coinbase»).
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
    const val COINBASE_KEY = "Coinbase"

    fun isUs(regionCountry: String?): Boolean = regionCountry?.trim()?.uppercase() == "US"

    /**
     * @param regionCountry Ländercode der Geräte-Region (z. B. «US», «CH»)
     * @param marketName Anzeigename der Börse aus der Registry; null = Schlüssel
     */
    fun pairFor(symbol: String, regionCountry: String?, marketName: (String) -> String? = { null }): Pair {
        val base = symbol.trim().uppercase()
        return if (isUs(regionCountry)) {
            Pair(COINBASE_KEY, marketName(COINBASE_KEY) ?: COINBASE_KEY, base, "USD", "$base-USD")
        } else {
            Pair(BINANCE_KEY, marketName(BINANCE_KEY) ?: BINANCE_KEY, base, "USDT", "${base}USDT")
        }
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
