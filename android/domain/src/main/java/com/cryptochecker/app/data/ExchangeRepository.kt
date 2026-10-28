package com.cryptochecker.app.data

import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.exchange.DexPool
import com.cryptochecker.app.domain.model.BulkTickers
import com.cryptochecker.app.domain.model.ExchangeTickerResult
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.model.ExchangePairsInfo

interface ExchangeRepository {
    suspend fun getExchangeList(): List<ExchangeInfo>
    /**
     * Paarliste einer Börse. [withAlpha] (Paar-Auswahl): bei Binance dazu die Alpha-Token, die Binance
     * nicht am Spot führt ([com.cryptochecker.marketdata.model.exchange.BinanceAlpha]); ob sie erscheinen,
     * entscheidet «inkl. Alpha-Tokens» beim Anzeigen ([com.cryptochecker.app.settings.scoped]).
     */
    suspend fun getExchangeCurrencyPairsInfo(exchange: ExchangeInfo, withAlpha: Boolean = false): ExchangePairsInfo
    fun isExchangeSupportsUpdatePairs(exchange: ExchangeInfo): Boolean

    suspend fun getExchangeTicker(exchange: ExchangeInfo, pairInfo: ExchangePair): ExchangeTickerResult

    /** true, wenn die Börse alle Kurse in einem Rutsch liefert. */
    fun isExchangeSupportsBulkTickers(exchange: ExchangeInfo): Boolean

    /**
     * Kurse einer Börse, nach Paar-Kennung. Wo die Börse es kann, nur für
     * [pairIds]. Leere Karte bei Fehlern — der Aufrufer fällt dann auf die
     * Einzelabfrage zurück.
     */
    suspend fun getBulkTickers(exchange: ExchangeInfo, pairIds: Collection<String>): BulkTickers
    suspend fun updateExchangeCurrencyPairs(exchange: ExchangeInfo)

    /** DEX-Pools zu einem Suchbegriff (DexScreener). */
    suspend fun searchDexPools(query: String): List<DexPool>
}
