package com.cryptochecker.app.data

import android.content.res.Resources
import com.cryptochecker.marketdata.config.MarketsConfig
import com.cryptochecker.marketdata.model.CheckerInfo
import com.cryptochecker.marketdata.model.CurrencyPairInfo
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.market.DexPool
import com.cryptochecker.marketdata.model.currency.CurrencyPairsMap
import com.cryptochecker.app.data.local.MarketLocalDataSource
import com.cryptochecker.app.data.remote.MarketRemoteDataSource
import com.cryptochecker.app.domain.exceptions.UserFriendlyMarketError
import com.cryptochecker.app.domain.exceptions.rethrowIfCritical
import com.cryptochecker.app.domain.model.BulkTickers
import com.cryptochecker.app.domain.model.MarketTickerResult
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.model.MarketPairsInfo
import javax.inject.Inject

class MarketRepositoryImpl @Inject constructor(
//    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
    private val marketLocalDataSource: MarketLocalDataSource,
    private val marketRemoteDataSource: MarketRemoteDataSource
    ) : MarketRepository {

    override suspend fun getMarketList(): List<MarketInfo> {
        return MarketsConfig.MARKETS.values.toList()
            .map{ it.mapToMarketInfo() }
    }

    override fun isMarketSupportsUpdatePairs(market: MarketInfo): Boolean =
        findSourceMarket(market)?.getCurrencyPairsUrl(0).isNullOrEmpty().not()


    override suspend fun getMarketCurrencyPairsInfo(market: MarketInfo): MarketPairsInfo {
        marketLocalDataSource.getMarketData(market.key)?.let { return it }

        val sourceMarket = findSourceMarket(market) ?: return MarketPairsInfo()
        return MarketPairsInfo(
            lastSyncDate = 0,
            pairs = sourceMarket.currencyPairs?.let { convertPairsMapToPairList(it) } ?: emptyList()
        )
    }

    override suspend fun getMarketTicker(
        market: MarketInfo,
        pairInfo: CurrencyPairInfo
    ): MarketTickerResult {

        val checkerInfo = pairInfo.toCheckerInfo()

        // Eine entfernte Börse darf nur diesen einen Eintrag betreffen,
        // nicht den ganzen Durchlauf abbrechen.
        val sourceMarket = findSourceMarket(market)
            ?: return MarketTickerResult(TickerImpl(), checkerInfo, UserFriendlyMarketError.MARKET_UNAVAILABLE)

        return try {
            val ticker = marketRemoteDataSource.fetchMarketTicker(
                sourceMarket,
                checkerInfo
            )
            MarketTickerResult(ticker, checkerInfo)
        } catch (ex: Exception) {
            ex.rethrowIfCritical()

            MarketTickerResult(TickerImpl(), checkerInfo, ex.message ?: ex.javaClass.simpleName)
        }
    }

    override fun isMarketSupportsBulkTickers(market: MarketInfo): Boolean =
        (findSourceMarket(market)?.bulkTickersNumOfRequests ?: 0) > 0

    override suspend fun getBulkTickers(market: MarketInfo, pairIds: Collection<String>): BulkTickers =
        try {
            val sourceMarket = findSourceMarket(market) ?: return BulkTickers()
            marketRemoteDataSource.fetchBulkTickers(sourceMarket, pairIds)
        } catch (ex: Exception) {
            ex.rethrowIfCritical()
            BulkTickers()
        }

    override suspend fun searchDexPools(query: String): List<DexPool> =
        marketRemoteDataSource.searchDexPools(query)

    override suspend fun updateMarketCurrencyPairs(market: MarketInfo) {
        val marketData =
            marketRemoteDataSource.fetchMarketCurrencyPairsInfo(getSourceMarketByKey(market))

        if(marketData.size > 0) {
            marketLocalDataSource.saveMarketData(market.key, marketData)
        }
    }
}

private fun CurrencyPairInfo.toCheckerInfo(): CheckerInfo =
    CheckerInfo(
        this.currencyBase,
        this.currencyCounter,
        this.currencyPairId,
        this.contractType
    )

private fun getSourceMarketByKey(market: MarketInfo): Market =
    getSourceMarketByKey(market.key)

private fun getSourceMarketByKey(marketKey: String): Market =
    MarketsConfig.MARKETS[marketKey]
        ?: throw Resources.NotFoundException("Market not found (key=${marketKey})")

/** Wie oben, nur ohne Ausnahme — für Aufrufer, die weiterarbeiten sollen. */
private fun findSourceMarket(market: MarketInfo): Market? =
    MarketsConfig.MARKETS[market.key]

private fun Market.mapToMarketInfo(): MarketInfo =
    MarketInfo(key = key, name = name)

private fun convertPairsMapToPairList(currencyMap: CurrencyPairsMap): List<CurrencyPairInfo> {
    return currencyMap.flatMap { item ->
        item.value.map { quoteAsset ->
            CurrencyPairInfo(item.key, quoteAsset, null)
        }
    }
}
