package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.portfolio.PortfolioExchangeRoute
import com.cryptochecker.marketdata.model.FuturesContractType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource.Companion.STABLE
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource.Companion.PAIR_LIST_RETRY_MILLIS
import com.cryptochecker.app.data.portfolio.PortfolioPriceSource.Companion.REQUEST_TIMEOUT_MILLIS

// Kurse eingetragener Börsen (Teil von [PortfolioPriceSource]): Börsen-Route je Coin, Kurs an einer Spot-Börse,
// Namen der Spot-Börsen. Erweiterungsfunktionen; der Zustand bleibt in der Klasse.

/**
 * Kurs der eingetragenen Börse für [coins] ([PortfolioExchangeRoute]): Börse aus den Transaktionen, Paar
 * aus der Paarliste der Börse (fehlt sie, höchstens alle 6 Std. einmal laden), Kurs über den Adapter der
 * Börse, umgerechnet in USDT. Ersetzt in [fetched] den Binance-Kurs; scheitert es, bleibt Binance und der
 * Coin steht in [failedOf]. Abfragen gehen nur an die Börse, die der Nutzer selbst eingetragen hat.
 */
suspend fun PortfolioPriceSource.applyExchangeRoutes(coins: List<String>, fetched: MutableMap<String, Double>) {
    val txs = runCatchingCancellable { portfolioDao.getAll() }.getOrNull() ?: return
    val (byCoin, mixed) = PortfolioExchangeRoute.exchangeByLedger(txs.flatMap { it.routeEntries() })
    mixedCoins = mixed
    val wanted = coins.filter { it != STABLE }
    // Coins ohne (einheitliche) Börse: Binance
    wanted.filter { it !in byCoin }.forEach { coin ->
        sourceOf.remove(coin)
        failedOf.remove(coin)
        binanceOf.remove(coin)
    }
    val routed = wanted.mapNotNull { coin -> byCoin[coin]?.let { coin to it } }
    if (routed.isEmpty()) return
    val exchanges = runCatchingCancellable { exchangeRepository.getExchangeList() }.getOrNull()
        ?.associate { it.key to it.name }.orEmpty()
    coroutineScope {
        routed.map { (coin, name) ->
            async {
                val exchange = PortfolioExchangeRoute.exchange(name, exchanges)
                if (exchange == null) {
                    // Binance oder freier Text: Binance, ohne Hinweis
                    sourceOf.remove(coin); failedOf.remove(coin); binanceOf.remove(coin)
                    return@async
                }
                val price = try {
                    exchangePrice(coin, ExchangeInfo(key = exchange.first, name = exchange.second))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.d(e, "Portfolio: Kurs von %s für %s nicht verfügbar", exchange.second, coin)
                    null
                }
                if (price != null) {
                    fetched[coin]?.let { binanceOf[coin] = it } ?: binanceOf.remove(coin)
                    fetched[coin] = price
                    sourceOf[coin] = exchange.second
                    failedOf.remove(coin)
                } else {
                    sourceOf.remove(coin)
                    binanceOf.remove(coin)
                    failedOf[coin] = exchange.second
                }
            }
        }.awaitAll()
    }
}

/** Namen der Spot-Börsen der App (alphabetisch) für das Feld «Börse» einer Transaktion. */
suspend fun PortfolioPriceSource.spotExchangeNames(): List<String> =
    exchangeRepository.getExchangeList()
        .filter { PortfolioExchangeRoute.isSpot(it.key) }
        .map { it.name }
        .distinct()
        .sortedWith(String.CASE_INSENSITIVE_ORDER)

/** Letzter Kurs von [coin] an [exchange] in USDT; null = kein Paar, keine Antwort oder kein Devisenkurs. */
private suspend fun PortfolioPriceSource.exchangePrice(coin: String, exchange: ExchangeInfo): Double? {
    var pairs = spotPairs(exchange)
    if (pairs.isEmpty() && exchangeRepository.isExchangeSupportsUpdatePairs(exchange)) {
        val now = System.currentTimeMillis()
        val last = pairListAttempts[exchange.key] ?: 0L
        if (now - last !in 0 until PAIR_LIST_RETRY_MILLIS) {
            pairListAttempts[exchange.key] = now
            runCatchingCancellable { exchangeRepository.updateExchangeCurrencyPairs(exchange) }
            pairs = spotPairs(exchange)
        }
    }
    val pick = PortfolioExchangeRoute.pickPair(coin, pairs.map { PortfolioExchangeRoute.Pair3(it.base, it.quote, it.pairId) })
        ?: return null
    val info = pairs.first { it.base == pick.base && it.quote == pick.quote && it.pairId == pick.pairId }
    val result = withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { exchangeRepository.getExchangeTicker(exchange, info) } ?: return null
    if (result.error != null) return null
    val last = result.ticker.last
    val quote = pick.quote.uppercase()
    val rate = if (quote == "USDT" || quote == "USD") null else fxSource.usdTo(quote)
    return PortfolioExchangeRoute.toUsdt(last, quote, depegReadings.liveUsdtUsd() ?: 1.0) { rate }
}

private suspend fun PortfolioPriceSource.spotPairs(exchange: ExchangeInfo) =
    exchangeRepository.getExchangeCurrencyPairsInfo(exchange).pairs.filter { it.contractType == FuturesContractType.NONE }
