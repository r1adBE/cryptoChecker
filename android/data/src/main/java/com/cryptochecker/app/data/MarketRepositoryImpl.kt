package com.cryptochecker.app.data

import android.content.res.Resources
import com.cryptochecker.app.data.local.MarketLocalDataSource
import com.cryptochecker.app.data.remote.MarketRemoteDataSource
import com.cryptochecker.app.domain.exceptions.UserFriendlyMarketError
import com.cryptochecker.app.domain.exceptions.rethrowIfCritical
import com.cryptochecker.app.domain.exceptions.toMarketFailure
import com.cryptochecker.app.domain.model.BulkTickers
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.model.MarketPairsInfo
import com.cryptochecker.app.domain.model.MarketTickerResult
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.marketdata.config.MarketsConfig
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.market.BinanceAlpha
import com.cryptochecker.marketdata.model.market.DexPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Börsen, Paarlisten und Kurse: gespeicherte Listen aus Room, alles Übrige über das Netz. */
class MarketRepositoryImpl @Inject constructor(
    private val local: MarketLocalDataSource,
    private val remote: MarketRemoteDataSource,
    private val settings: SettingsRepository,
    private val tokenIdentityStore: TokenIdentityStore,
    private val coinLogos: CoinLogoRepository,
) : MarketRepository {

    override suspend fun getMarketList(): List<MarketInfo> =
        MarketsConfig.MARKETS.values.map { MarketInfo(key = it.key, name = it.name) }

    override fun isMarketSupportsUpdatePairs(market: MarketInfo): Boolean {
        val source = marketOrNull(market.key) ?: return false
        return !source.pairListAddress(0).isNullOrEmpty()
    }

    /**
     * Paarliste einer Börse. TradFi-Futures (Aktien, Rohstoffe, Devisen, Pre-IPO, [MarketPair.tradFi])
     * nur, wenn «Token & TradFi» (Einstellungen › Merkliste) sie erlaubt; gespeichert bleibt stets die
     * ganze Liste, damit ein Wechsel ohne neuen Abruf wirkt. Krypto-Paare bleiben hier immer drin
     * (Portfolio, Start-Auswahl, Info); «Nur TradFi» blendet sie erst in der Paar-Auswahl aus
     * ([com.cryptochecker.app.settings.scoped]).
     */
    override suspend fun getMarketCurrencyPairsInfo(market: MarketInfo, withAlpha: Boolean): MarketPairsInfo {
        val stored = local.getMarketData(market.key)
        if (stored == null) {
            // Noch nie abgeglichen: fest eingebaute Paare der Börse, falls sie welche hat
            val builtIn = marketOrNull(market.key)?.currencyPairs ?: return MarketPairsInfo()
            return MarketPairsInfo(lastSyncDate = 0, pairs = builtInPairs(builtIn))
        }

        // Listen älterer Versionen: inverse Perpetuals wie heute als INVERSE_PERPETUAL
        val normalized = stored.withInversePerpetuals(market.key)
        val visible = when {
            normalized.pairs.none { it.tradFi } -> normalized
            settings.current().assetScope.allowsTradFi -> normalized
            else -> normalized.copy(pairs = normalized.pairs.filterNot { it.tradFi })
        }
        return if (withAlpha) addAlphaPairs(market.key, visible) else visible
    }

    /**
     * Binance mit abgeglichener Spot-Liste: zusätzlich die Alpha-Token aus der gespeicherten Alpha-Liste
     * (kein eigener Abruf) als «SYMBOL/USDT» mit Kennung «ALPHA_<id>USDT». Spot-Paare bleiben, wie sie
     * sind; ein schon vergebenes Kürzel heisst beim Alpha-Token «SYMBOL_<Nummer>» ([BinanceAlpha.pairs]).
     */
    private suspend fun addAlphaPairs(marketKey: String, info: MarketPairsInfo): MarketPairsInfo {
        if (marketKey != BinanceAlpha.MARKET_KEY || info.lastSyncDate <= 0L) return info
        val alphaPairs = BinanceAlpha.pairs(coinLogos.loadAlphaTokens(), info.pairs)
        if (alphaPairs.isEmpty()) return info
        return info.copy(pairs = (info.pairs + alphaPairs).sorted())
    }

    override suspend fun getMarketTicker(market: MarketInfo, pairInfo: MarketPair): MarketTickerResult {
        val info = pairInfo.asPairQuery()
        // Eine entfernte Börse betrifft nur diesen Eintrag, nicht den ganzen Durchlauf
        val source = marketOrNull(market.key)
            ?: return MarketTickerResult(MutableTicker(), info, UserFriendlyMarketError.MARKET_UNAVAILABLE)

        return try {
            MarketTickerResult(remote.fetchMarketTicker(source, info), info)
        } catch (ex: Exception) {
            ex.rethrowIfCritical()
            MarketTickerResult(MutableTicker(), info, ex.textForUser(), ex.toMarketFailure())
        }
    }

    override fun isMarketSupportsBulkTickers(market: MarketInfo): Boolean {
        val source = marketOrNull(market.key) ?: return false
        return source.bulkTickersNumOfRequests > 0
    }

    override suspend fun getBulkTickers(market: MarketInfo, pairIds: Collection<String>): BulkTickers {
        return try {
            val source = marketOrNull(market.key) ?: return BulkTickers()
            remote.fetchBulkTickers(source, pairIds)
        } catch (ex: Exception) {
            ex.rethrowIfCritical()
            // Text weiterreichen: «zu viele Anfragen» pausiert die Börse (ExchangeBackoff)
            BulkTickers(error = ex.textForUser(), failure = ex.toMarketFailure())
        }
    }

    override suspend fun searchDexPools(query: String): List<DexPool> = remote.searchDexPools(query)

    override suspend fun updateMarketCurrencyPairs(market: MarketInfo) {
        val fresh = remote.fetchMarketCurrencyPairsInfo(requireMarket(market.key))
        if (fresh.size <= 0) return
        // Paarliste und volle Coin-Namen («gleicher Token», aus derselben Paarliste) gehören zusammen: nach dem Abruf
        // beide speichern, auch wenn der Aufrufer gerade abbricht — sonst Paare neu, Namen alt. Die Namen schreiben
        // eine Datei, darum nicht im Kontext des Aufrufers (Explorer: Haupt-Thread).
        withContext(NonCancellable + Dispatchers.IO) {
            local.saveMarketData(market.key, fresh)
            tokenIdentityStore.saveMarket(market.key, fresh.baseNames)
        }
    }
}

/** Börse zur Kennung; null, wenn diese App-Version sie nicht (mehr) kennt. */
private fun marketOrNull(marketKey: String): Market? = MarketsConfig.MARKETS[marketKey]

/** Wie [marketOrNull], aber eine unbekannte Börse ist ein Fehler. */
private fun requireMarket(marketKey: String): Market =
    marketOrNull(marketKey) ?: throw Resources.NotFoundException("Market not found (key=$marketKey)")

private fun Exception.textForUser(): String = message ?: javaClass.simpleName

private fun MarketPair.asPairQuery(): PairQuery =
    PairQuery(base, quote, pairId, contractType)

/** Fest eingebaute Paare (Basis → Gegenwährungen) als Liste ohne Paar-Kennung. */
private fun builtInPairs(byBase: Map<String, Array<String>>): List<MarketPair> {
    val result = ArrayList<MarketPair>()
    for ((base, quotes) in byBase) {
        quotes.forEach { quote -> result += MarketPair(base, quote, null) }
    }
    return result
}

/** Gespeicherte Liste mit inversen Perpetuals als INVERSE_PERPETUAL ([FuturesContractType.normalized]). */
private fun MarketPairsInfo.withInversePerpetuals(marketKey: String): MarketPairsInfo {
    val types = pairs.map { FuturesContractType.normalized(marketKey, it.pairId, it.contractType) }
    if (pairs.indices.all { types[it] == pairs[it].contractType }) return this
    return copy(pairs = pairs.mapIndexed { i, pair ->
        val type = types[i]
        if (type == pair.contractType) pair
        else MarketPair(pair.base, pair.quote, pair.pairId, type, pair.tradFi)
    })
}
