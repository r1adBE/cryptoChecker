package com.cryptochecker.app.domain.market

import com.cryptochecker.app.domain.starter.StarterCoins

/** Ein Coin der CoinGecko-Rangliste für «Gewinner und Verlierer 24 h». */
data class MoverCoin(
    /** Kürzel in Grossbuchstaben (Logo und Tipp gehen über das Kürzel, wie bei «Heute auffällig»). */
    val symbol: String,
    val name: String,
    /** Rang nach Marktkapitalisierung; null = unbekannt. */
    val rank: Int?,
    /** 24-h-Veränderung in %; null = fehlt. */
    val change24h: Double?,
)

/** Die stärksten Gewinner (absteigend) und Verlierer (die stärksten Verluste zuerst). */
data class MarketMoversResult(val gainers: List<MoverCoin>, val losers: List<MoverCoin>) {
    val isEmpty: Boolean get() = gainers.isEmpty() && losers.isEmpty()
}

/**
 * «Gewinner und Verlierer 24 h» unter dem Pulse im Markt-Tab — reine Auswahl, gleich wie `MarketMovers.swift`.
 * Daten: dieselbe CoinGecko-Rangliste wie die Marktbreite des Pulse (eine Anfrage, gleicher Zwischenspeicher).
 *
 * Regeln:
 *  1. Grundmenge: die ersten [UNIVERSE] Coins nach Rang (ohne Rang hinten, sonst in der gelieferten Reihenfolge) —
 *     «Top 100 nach Marktkapitalisierung».
 *  2. Davon weg: Stablecoins und Doppelgänger (verpackt, gestakt — dieselbe Regel wie Marktbreite und Start-Coins,
 *     [StarterCoins.isExcluded]), Einträge ohne gültige 24-h-Veränderung und doppelte Kürzel (der besser platzierte
 *     bleibt: Logo und Tipp hängen am Kürzel).
 *  3. Weniger als [MIN_COINS] gültige: nichts (eine Rangliste aus einer Handvoll Coins sagt nichts).
 *  4. Gewinner: Veränderung > 0, grösste zuerst; Verlierer: < 0, grösster Verlust zuerst; je höchstens [COUNT].
 *     Genau 0 ist weder noch. Gleichstand: besserer Rang zuerst, dann Kürzel (stabil auf beiden Plattformen).
 */
object MarketMovers {
    /** So viele der grössten Coins bilden die Grundmenge. */
    const val UNIVERSE = 100

    /** Je Liste höchstens so viele. */
    const val COUNT = 5

    /** Weniger gültige Coins: keine Liste. */
    const val MIN_COINS = 10

    fun select(coins: List<MoverCoin>): MarketMoversResult {
        val ranked = coins.withIndex()
            .sortedWith(compareBy<IndexedValue<MoverCoin>> { it.value.rank ?: Int.MAX_VALUE }.thenBy { it.index })
            .map { it.value }
            .take(UNIVERSE)
        val seen = HashSet<String>()
        val valid = ranked.mapNotNull { c ->
            val symbol = c.symbol.trim().uppercase()
            val change = c.change24h?.takeIf { it.isFinite() } ?: return@mapNotNull null
            if (symbol.isEmpty()) return@mapNotNull null
            if (StarterCoins.isExcluded(StarterCoins.MarketCoin(symbol = symbol, name = c.name.trim(), rank = c.rank, marketCap = null))) {
                return@mapNotNull null
            }
            if (!seen.add(symbol)) return@mapNotNull null
            c.copy(symbol = symbol, name = c.name.trim(), change24h = change)
        }
        if (valid.size < MIN_COINS) return MarketMoversResult(emptyList(), emptyList())
        val tie = compareBy<MoverCoin> { it.rank ?: Int.MAX_VALUE }.thenBy { it.symbol }
        val gainers = valid.filter { it.change24h!! > 0.0 }
            .sortedWith(compareByDescending<MoverCoin> { it.change24h!! }.then(tie))
            .take(COUNT)
        val losers = valid.filter { it.change24h!! < 0.0 }
            .sortedWith(compareBy<MoverCoin> { it.change24h!! }.then(tie))
            .take(COUNT)
        return MarketMoversResult(gainers, losers)
    }
}
