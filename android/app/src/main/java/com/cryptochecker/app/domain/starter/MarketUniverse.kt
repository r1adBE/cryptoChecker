package com.cryptochecker.app.domain.starter

/**
 * Coins für «Heute auffällig»: die rund 30 grössten nach Marktkapitalisierung, ohne
 * Stablecoins und Doppelgänger (gleiche Regeln wie [StarterCoins.isExcluded]), nur mit
 * Spot-Paar «<COIN>USDT» auf Binance. Die Start-Merkliste (fünf Coins) bleibt davon unberührt.
 * Reines Kotlin (testbar); Abruf und Zwischenspeicher (24 h) siehe StarterCoinsRepository.
 */
object MarketUniverse {

    /** Ein Coin mit Marktkapitalisierung (USD; null = unbekannt). */
    data class Coin(val symbol: String, val name: String, val marketCap: Double?)

    const val COUNT = 30

    /** Weniger Coins taugen nicht für einen Vergleich untereinander. */
    const val MIN_COUNT = 8

    /** Gültigkeit des Zwischenspeichers (wie die Start-Coins). */
    const val TTL_MILLIS = StarterCoins.TTL_MILLIS

    /**
     * Die ersten [COUNT] Coins nach Rang, ohne ausgeschlossene, ohne doppelte Symbole,
     * nur mit Paar auf Binance ([available] = Basis-Symbole in Grossbuchstaben).
     * null, wenn weniger als [MIN_COUNT] übrig bleiben.
     */
    fun pick(coins: List<StarterCoins.MarketCoin>, available: Set<String>): List<Coin>? {
        val picked = coins
            .sortedBy { it.rank ?: Int.MAX_VALUE }
            .filterNot { StarterCoins.isExcluded(it) }
            .map { c ->
                val symbol = c.symbol.trim().uppercase()
                Coin(symbol, c.name.trim().ifEmpty { symbol }, c.marketCap?.takeIf { it.isFinite() && it > 0.0 })
            }
            .filter { it.symbol.all { ch -> ch in 'A'..'Z' || ch in '0'..'9' } }
            .distinctBy { it.symbol }
            .filter { it.symbol in available }
            .take(COUNT)
        return picked.takeIf { it.size >= MIN_COUNT }
    }

    // ---- Zwischenspeicher: «BTC|Bitcoin|1.2E12;ETH|Ethereum|4.1E11;…»

    fun encode(coins: List<Coin>): String =
        coins.joinToString(";") { "${clean(it.symbol)}|${clean(it.name)}|${it.marketCap ?: ""}" }

    /** null bei ungültigem Inhalt oder zu wenigen Coins. */
    fun decode(text: String?): List<Coin>? {
        if (text.isNullOrBlank()) return null
        val coins = text.split(';').map { part ->
            val fields = part.split('|')
            val symbol = fields.getOrNull(0)?.trim()?.uppercase().orEmpty()
            val name = fields.getOrNull(1)?.trim().orEmpty()
            if (symbol.isEmpty() || name.isEmpty()) return null
            Coin(symbol, name, fields.getOrNull(2)?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 })
        }
        return coins.distinctBy { it.symbol }.takeIf { it.size == coins.size && it.size >= MIN_COUNT }
    }

    fun isFresh(savedAt: Long, now: Long): Boolean = savedAt in 1..now && now - savedAt < TTL_MILLIS

    /** Binance-Abfrage `symbols=["BTCUSDT","ETHUSDT",…]` (noch nicht URL-codiert). */
    fun binanceSymbols(coins: List<Coin>): String =
        coins.joinToString(",", "[", "]") { "\"${it.symbol}USDT\"" }

    private fun clean(text: String) = text.replace(';', ' ').replace('|', ' ').trim()
}
