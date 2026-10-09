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

    /** Ausweich-Liste in den USA (Coinbase, …/USD) — BNB gibt es dort nicht. */
    val FALLBACK_US = listOf(
        StarterPairs.Coin("Bitcoin", "BTC"),
        StarterPairs.Coin("Ethereum", "ETH"),
        StarterPairs.Coin("XRP", "XRP"),
        StarterPairs.Coin("Solana", "SOL"),
        StarterPairs.Coin("Dogecoin", "DOGE"),
    )

    fun fallback(regionCountry: String?): List<StarterPairs.Coin> =
        if (StarterPairs.isUs(regionCountry)) FALLBACK_US else FALLBACK

    /** Stablecoins (an Dollar, Euro oder Gold gebunden). */
    val STABLECOINS = setOf(
        "USDT", "USDC", "DAI", "USDE", "USDS", "FDUSD", "TUSD", "PYUSD", "USD1", "BUSD", "USDD",
        "FRAX", "GUSD", "USDP", "EURC", "EURT", "RLUSD", "USDG", "USD0", "USDTB", "BFUSD",
        "SUSDS", "SUSDE", "USDX", "USDB", "LUSD", "CRVUSD", "GHO", "USDY", "USDF", "USDAI",
        "USDT0", "BUIDL", "USYC", "OUSG", "EURS", "EURE", "XSGD", "XAUT", "PAXG",
    )

    /** Verpackte, gestakte oder überbrückte Doppelgänger und Börsen-Quittungen. */
    val WRAPPED = setOf(
        "WBTC", "WETH", "STETH", "WSTETH", "WEETH", "EETH", "CBBTC", "CBETH", "RETH", "METH",
        "WBETH", "BETH", "BNSOL", "JITOSOL", "MSOL", "JUPSOL", "BBSOL", "LBTC", "SOLVBTC",
        "TBTC", "BTCB", "EZETH", "RSETH", "OSETH", "SWETH", "LSETH", "WBNB", "WSOL", "WTRX",
        "WHYPE", "KHYPE", "STHYPE", "CLBTC", "SAVAX", "WAVAX", "BSC-USD",
    )

    private val WORD_DOLLAR = Regex("\\bdollar\\b", RegexOption.IGNORE_CASE)
    private val WORD_EURO = Regex("\\beuro\\b", RegexOption.IGNORE_CASE)

    /** Stablecoin, Doppelgänger oder offensichtlich an eine Währung gebunden? */
    fun isExcluded(coin: MarketCoin): Boolean {
        val symbol = coin.symbol.trim().uppercase()
        if (symbol.isEmpty()) return true
        if (symbol in STABLECOINS || symbol in WRAPPED) return true
        if (symbol.startsWith("USD") || symbol.endsWith("USD")) return true
        val name = coin.name
        return name.contains("USD") || WORD_DOLLAR.containsMatchIn(name) || WORD_EURO.containsMatchIn(name)
    }

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
