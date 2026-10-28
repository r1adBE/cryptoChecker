package com.cryptochecker.app.domain.starter

/**
 * Auswahl der Start-Merkliste: die fünf grössten Coins nach Marktkapitalisierung,
 * ohne Stablecoins und ohne «verpackte» Doppelgänger (WBTC, stETH …).
 * Reines Kotlin (testbar); Abruf und Zwischenspeicher siehe StarterCoinsRepository.
 */
object StarterCoins {

    /**
     * Ein Eintrag der CoinGecko-Liste (`/coins/markets`).
     * [marketCap] (USD) braucht nur «Heute auffällig» ([MarketUniverse]).
     */
    data class MarketCoin(val symbol: String, val name: String, val rank: Int?, val marketCap: Double? = null)

    const val COUNT = 5

    /** Gültigkeit des Zwischenspeichers. */
    const val TTL_MILLIS = 24 * 60 * 60_000L

    /** Ausweich-Liste (Binance, …/USDT). */
    val FALLBACK = listOf(
        StarterPairs.Coin("Bitcoin", "BTC"),
        StarterPairs.Coin("Ethereum", "ETH"),
        StarterPairs.Coin("XRP", "XRP"),
        StarterPairs.Coin("BNB", "BNB"),
        StarterPairs.Coin("Solana", "SOL"),
    )

    /** Ausweich-Liste in den USA (Binance.US, …/USDT): ohne XRP, das Binance.US nicht sicher führt. */
    val FALLBACK_US = listOf(
        StarterPairs.Coin("Bitcoin", "BTC"),
        StarterPairs.Coin("Ethereum", "ETH"),
        StarterPairs.Coin("BNB", "BNB"),
        StarterPairs.Coin("Solana", "SOL"),
        StarterPairs.Coin("Dogecoin", "DOGE"),
    )

    fun fallback(regionCountry: String?): List<StarterPairs.Coin> =
        if (StarterPairs.exchangeKey(regionCountry) == StarterPairs.BINANCE_KEY) FALLBACK else FALLBACK_US

    /** Stablecoins — die Liste steht in [CoinExclusion]. */
    val STABLECOINS: Set<String> get() = CoinExclusion.STABLECOINS

    /** Doppelgänger und Börsen-Belege — die Liste steht in [CoinExclusion]. */
    val WRAPPED: Set<String> get() = CoinExclusion.WRAPPED

    /** Stablecoin, Doppelgänger oder offensichtlich an eine Währung gebunden? Regel: [CoinExclusion.isExcluded]. */
    fun isExcluded(coin: MarketCoin): Boolean = CoinExclusion.isExcluded(coin.symbol, coin.name)

    /**
     * Die ersten [COUNT] Coins nach Rang, ohne ausgeschlossene, nur solche mit
     * Paar an der Start-Börse ([available] = Basis-Symbole in Grossbuchstaben).
     * null, wenn nicht genug übrig bleiben — dann gilt die Ausweich-Liste.
     */
    fun pick(coins: List<MarketCoin>, available: Set<String>): List<StarterPairs.Coin>? {
        val picked = coins
            .sortedBy { it.rank ?: Int.MAX_VALUE }
            .filterNot { isExcluded(it) }
            .map { StarterPairs.Coin(it.name.trim().ifEmpty { it.symbol.uppercase() }, it.symbol.trim().uppercase()) }
            .distinctBy { it.symbol }
            .filter { it.symbol in available }
            .take(COUNT)
        return picked.takeIf { it.size == COUNT }
    }

    // ---- Zwischenspeicher: «BTC|Bitcoin;ETH|Ethereum;…»

    fun encode(coins: List<StarterPairs.Coin>): String =
        coins.joinToString(";") { "${clean(it.symbol)}|${clean(it.name)}" }

    /** null bei ungültigem Inhalt oder falscher Anzahl. */
    fun decode(text: String?): List<StarterPairs.Coin>? {
        if (text.isNullOrBlank()) return null
        val coins = text.split(';').map { part ->
            val symbol = part.substringBefore('|').trim().uppercase()
            val name = part.substringAfter('|', "").trim()
            if (symbol.isEmpty() || name.isEmpty()) return null
            StarterPairs.Coin(name, symbol)
        }
        return coins.takeIf { it.size == COUNT && it.map { c -> c.symbol }.toSet().size == COUNT }
    }

    fun isFresh(savedAt: Long, now: Long): Boolean = savedAt in 1..now && now - savedAt < TTL_MILLIS

    private fun clean(text: String) = text.replace(';', ' ').replace('|', ' ').trim()
}
