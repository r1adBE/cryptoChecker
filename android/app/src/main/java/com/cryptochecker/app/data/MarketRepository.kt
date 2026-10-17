package com.cryptochecker.app.data

import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.market.DexPool
import com.cryptochecker.app.domain.model.BulkTickers
import com.cryptochecker.app.domain.model.MarketTickerResult
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.model.MarketPairsInfo

interface MarketRepository {
    suspend fun getMarketList(): List<MarketInfo>
    /**
     * Paarliste einer Börse. [withAlpha] (Paar-Auswahl): bei Binance dazu die Alpha-Token, die Binance
     * nicht am Spot führt ([com.cryptochecker.marketdata.model.market.BinanceAlpha]); ob sie erscheinen,
     * entscheidet «inkl. Alpha-Tokens» beim Anzeigen ([com.cryptochecker.app.settings.scoped]).
     */
    suspend fun getMarketCurrencyPairsInfo(market: MarketInfo, withAlpha: Boolean = false): MarketPairsInfo
    fun isMarketSupportsUpdatePairs(market: MarketInfo): Boolean

    suspend fun getMarketTicker(market: MarketInfo, pairInfo: MarketPair): MarketTickerResult

    /** true, wenn die Börse alle Kurse in einem Rutsch liefert. */
    fun isMarketSupportsBulkTickers(market: MarketInfo): Boolean

    /**
     * Kurse einer Börse, nach Paar-Kennung. Wo die Börse es kann, nur für
     * [pairIds]. Leere Karte bei Fehlern — der Aufrufer fällt dann auf die
     * Einzelabfrage zurück.
     */
    suspend fun getBulkTickers(market: MarketInfo, pairIds: Collection<String>): BulkTickers
    suspend fun updateMarketCurrencyPairs(market: MarketInfo)

    /** DEX-Pools zu einem Suchbegriff (DexScreener). */
    suspend fun searchDexPools(query: String): List<DexPool>
}
