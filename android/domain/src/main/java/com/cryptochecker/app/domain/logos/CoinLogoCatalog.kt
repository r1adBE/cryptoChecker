package com.cryptochecker.app.domain.logos

import com.cryptochecker.marketdata.model.exchange.BinanceAlpha
import kotlinx.coroutines.flow.StateFlow

/** Namen, TradFi- und Alpha-Kennungen der Coins für die Oberfläche (Umsetzung: `CoinLogoRepository`). */
interface CoinLogoCatalog {
    val names: StateFlow<Map<String, String>>

    /** Paare der Merkliste, die TradFi sind. */
    val tradFiPairs: StateFlow<Set<String>>
    val alphaSymbols: StateFlow<Set<String>>
    val alphaTokens: StateFlow<List<BinanceAlpha.Token>>
    val alphaNames: StateFlow<Map<String, String>>

    fun isTradFi(exchangeKey: String, base: String, quote: String, contractType: String): Boolean

    /** true, wenn danach alle Logos da sind. */
    suspend fun ensureStarterLogos(symbols: Collection<String>): Boolean

    suspend fun loadSavedNames()

    fun logoDiagnostics(): LogoDiagnostics
}
