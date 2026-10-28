// Merkliste als Kursquelle (Teil von [PortfolioPriceSource]): Bezüge für Kerzenkurse, Kurse aus Merklisten-Paaren
// anderer Börsen und Namen je Coin. Erweiterungsfunktionen, der Zustand bleibt in der Klasse.
package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioExchangeRoute
import com.cryptochecker.app.domain.portfolio.PortfolioPriceIdentity
import com.cryptochecker.app.domain.portfolio.PortfolioPriceFallback
import com.cryptochecker.app.domain.portfolio.PortfolioWatchPair
import com.cryptochecker.app.domain.portfolio.PricePlausibility
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.FuturesContractType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource.Companion.REQUEST_TIMEOUT_MILLIS

/**
 * Unabhängige Bezüge für Kerzenkurse ([PricePlausibility.candleReference]): Kurse aktiver
 * Spot-Paare der Merkliste mit demselben Coin gegen einen USD-Stablecoin (höchstens
 * [PortfolioPriceFallback.MAX_AGE_MILLIS] alt), sonst der letzte gute Kurs (Zwischenspeicher
 * ohne ungeprüfte Kerzenkurse, dazu der gemerkte Stand). Coins ohne Bezug fehlen.
 */
suspend fun PortfolioPriceSource.candleReferences(coins: List<String>): Map<String, Double> {
    // Auch kein Binance-Kurs mit ungeprüftem Token als Bezug ([PortfolioPrices.unverified])
    val checked = currentEntries().filterKeys { it !in uncheckedCoins && it !in unverifiedCoins }
    // Ein gemerkter Kurs mit «Kurs ungeprüft» ist ebenso kein Bezug ([PortfolioPriceFallback.confirmed])
    val stored = PortfolioPriceFallback.confirmed(PortfolioPriceFallback.merge(checked, loadStored()))
    val watches = try {
        watchDao.getWatches()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.d(e, "Portfolio: Merkliste als Bezug nicht lesbar")
        emptyList()
    }
    val now = System.currentTimeMillis()
    val out = HashMap<String, Double>()
    for (coin in coins) {
        val watchPrices = watches.filter { w ->
            !w.disabled && w.contractType == FuturesContractType.NONE &&
                PortfolioCalculator.normalizeCoin(w.baseAsset) == coin &&
                PortfolioCalculator.normalizeCoin(w.quoteAsset) in CurrencyConversion.USD_STABLES &&
                now - w.lastUpdate in 0..PortfolioPriceFallback.MAX_AGE_MILLIS
        }.mapNotNull { it.lastPrice }
        PricePlausibility.candleReference(watchPrices, stored[coin]?.price)?.let { out[coin] = it }
    }
    return out
}

/**
 * Kurse aus Merklisten-Paaren für Coins, die Binance-Spot nicht führt ([PortfolioWatchPair]): aktives
 * Spot-Paar desselben Coins an einer anderen Börse, Kurs über deren Adapter, in USDT umgerechnet.
 * Abfragen gehen nur an Börsen, deren Paar der Nutzer selbst in der Merkliste hat. Ohne Treffer: leer.
 */
suspend fun PortfolioPriceSource.watchPairPrices(coins: List<String>): Map<String, Double> {
    if (coins.isEmpty()) return emptyMap()
    val watches = try {
        watchDao.getWatches()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.d(e, "Portfolio: Merkliste nicht lesbar")
        return emptyMap()
    }
    if (watches.isEmpty()) return emptyMap()
    val candidates = watches.map { w ->
        PortfolioWatchPair.Candidate(
            exchangeKey = w.exchangeKey,
            base = w.baseAsset,
            quote = w.quoteAsset,
            pairId = w.pairId,
            spot = w.contractType == FuturesContractType.NONE,
            disabled = w.disabled,
            lastUpdate = w.lastUpdate,
            // Voller Name aus der Paarliste der Börse (gleicher Token)
            name = tokenIdentityStore.pairName(w.exchangeKey, w.baseAsset),
        )
    }
    val names = watches.associate { it.exchangeKey to it.exchangeName }
    val out = ConcurrentHashMap<String, Double>()
    coroutineScope {
        coins.mapNotNull { coin -> PortfolioWatchPair.pick(coin, candidates)?.let { coin to it } }
            .map { (coin, pick) ->
                async {
                    val price = try {
                        watchPairPrice(pick, names[pick.exchangeKey] ?: pick.exchangeKey)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.d(e, "Portfolio: Kurs von %s für %s nicht verfügbar", pick.exchangeKey, coin)
                        null
                    }
                    if (price != null) out[coin] = price
                }
            }.awaitAll()
    }
    return out
}

/** Letzter Kurs des Merklisten-Paars [pick] in USDT; null = keine Antwort oder kein Devisenkurs. */
suspend fun PortfolioPriceSource.watchPairPrice(pick: PortfolioWatchPair.Candidate, exchangeName: String): Double? {
    val exchange = ExchangeInfo(key = pick.exchangeKey, name = exchangeName)
    val info = ExchangePair(pick.base, pick.quote, pick.pairId)
    val result = withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { exchangeRepository.getExchangeTicker(exchange, info) } ?: return null
    if (result.error != null) return null
    val quote = pick.quote.uppercase()
    val rate = if (quote == "USDT" || quote == "USD") null else fxSource.usdTo(quote)
    // USDT zum Marktkurs: USD-/Fiat-Kurs durch USDT/USD (ohne frische Messung 1, Karte markiert die Näherung)
    return PortfolioExchangeRoute.toUsdt(result.ticker.last, quote, depegReadings.liveUsdtUsd() ?: 1.0) { rate }
}

/** Name je Coin laut den Paaren der Merkliste ([PortfolioPriceIdentity.watchNames]); Fehler: keine Namen. */
suspend fun PortfolioPriceSource.watchNamesFor(coins: List<String>): Map<String, String?> {
    val watches = try {
        watchDao.getWatches()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.d(e, "Portfolio: Merkliste nicht lesbar")
        return emptyMap()
    }
    return PortfolioPriceIdentity.watchNames(
        coins,
        watches.map { PortfolioPriceIdentity.WatchName(it.baseAsset, tokenIdentityStore.pairName(it.exchangeKey, it.baseAsset), it.disabled) },
    )
}
