// Eigene Börse als Quelle des Portfolio-Verlaufs (Teil von `PortfolioHistorySource`): Wege je Coin, eigene
// Tageskerzen in USDT und das Zusammenführen mit Binance. Erweiterungsfunktionen; Speicher und Abhängigkeiten in der Klasse.
package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.app.data.remote.candles.CandlePair
import com.cryptochecker.app.domain.market.CycleCachePolicy
import com.cryptochecker.app.domain.portfolio.PortfolioCoinDaily
import com.cryptochecker.app.domain.portfolio.PortfolioDayRange
import com.cryptochecker.app.domain.portfolio.PortfolioExchangeRoute
import com.cryptochecker.app.domain.portfolio.PortfolioHistory
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryAsOf
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryFx
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.marketdata.model.FuturesContractType
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.time.LocalDate
import com.cryptochecker.app.data.portfolio.PortfolioHistorySource.Companion.MAX_OWN_DAYS
import com.cryptochecker.app.data.portfolio.PortfolioHistorySource.Companion.OWN_CODEC
import com.cryptochecker.app.data.portfolio.PortfolioHistorySource.Companion.TTL_MILLIS
import com.cryptochecker.app.data.portfolio.PortfolioHistorySource.Companion.ownCacheName

/**
 * Binance-Reihen [binance] mit den eigenen Reihen [own] zusammenführen: eigene Reihe ersetzt Binance,
 * reicht sie nicht so weit zurück, davor Binance angeglichen ([PortfolioExchangeRoute.stitch]).
 */
internal fun PortfolioHistorySource.mergeOwn(binance: Map<String, CoinCloses>, own: Map<String, CoinCloses>, routed: Set<String>): PortfolioDailyPrices {
    val closes = LinkedHashMap<String, Map<Long, Double>>()
    val ranges = LinkedHashMap<String, Map<Long, PortfolioDayRange>>()
    val before = HashMap<String, Long>()
    binance.forEach { (coin, entry) ->
        if (coin in own) return@forEach
        closes[coin] = entry.closes
        if (entry.ranges.isNotEmpty()) ranges[coin] = entry.ranges
    }
    own.forEach { (coin, entry) ->
        val stitched = PortfolioExchangeRoute.stitch(
            PortfolioCoinDaily(entry.closes, entry.ranges),
            binance[coin]?.let { PortfolioCoinDaily(it.closes, it.ranges) },
        )
        closes[coin] = stitched.daily.closes
        if (stitched.daily.ranges.isNotEmpty()) ranges[coin] = stitched.daily.ranges
        stitched.binanceBefore?.let { before[coin] = it }
    }
    val stale = own.mapNotNull { (coin, entry) -> entry.staleSince?.let { coin to it } }.toMap()
    return PortfolioDailyPrices(closes, ranges, own.keys.toSet(), before, routed, ownStale = stale)
}

/**
 * Wege der [coins] wie die Kurse ([PortfolioExchangeRoute], `PortfolioPriceSource.applyExchangeRoutes`):
 * einheitlich eingetragene Spot-Börse (nicht Binance) und ihr Paar aus der gespeicherten Paarliste —
 * ohne Netz (die Paarliste lädt die Kursabfrage). Binance und freier Text (Börsenliste gelesen, kein Markt):
 * kein Weg. Lässt sich der Weg nicht lesen, steht der Coin in [HistoryRoutes.unresolved] (Verlauf dann nur
 * Binance skaliert oder «ohne passende Tageskurse», [PortfolioExchangeRoute.historyCloses]) — nie still Binance.
 */
internal suspend fun PortfolioHistorySource.routes(coins: Collection<String>): HistoryRoutes =
    resolveHistoryRoutes(portfolioDao, marketRepository, coins)

/** Wege wie [routes], ohne die Klasse — auch für einzelne Tagesschlüsse ([HistoricPriceSource.dailyCloseUsdt]). */
internal suspend fun resolveHistoryRoutes(
    portfolioDao: PortfolioDao,
    marketRepository: com.cryptochecker.app.data.MarketRepository,
    coins: Collection<String>,
): HistoryRoutes {
    val wanted = coins.toSet()
    val byCoin = try {
        PortfolioExchangeRoute.exchangeByLedger(portfolioDao.getAll().flatMap { it.routeEntries() }).first.filterKeys { it in wanted }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Transaktionen nicht lesbar: Börsen unbekannt — kein Coin still mit Binance
        Timber.d(e, "Portfolio-Verlauf: Börsen nicht lesbar")
        return HistoryRoutes(emptyMap(), wanted)
    }
    if (byCoin.isEmpty()) return HistoryRoutes(emptyMap(), emptySet())
    val markets = try {
        marketRepository.getMarketList().associate { it.key to it.name }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.d(e, "Portfolio-Verlauf: Börsenliste nicht lesbar")
        emptyMap()
    }
    val out = HashMap<String, CandlePair>()
    val unresolved = HashSet<String>()
    for ((coin, name) in byCoin) {
        if (markets.isEmpty()) {
            // Börsenliste fehlt: nur Binance ist sicher die Standardquelle
            if (!name.trim().equals(PortfolioExchangeRoute.DEFAULT_MARKET, ignoreCase = true)) unresolved += coin
            continue
        }
        // Binance oder freier Text: Binance (wie die Kurse)
        val market = PortfolioExchangeRoute.market(name, markets) ?: continue
        val pick = try {
            val pairs = marketRepository.getMarketCurrencyPairsInfo(MarketInfo(key = market.first, name = market.second))
                .pairs.filter { it.contractType == FuturesContractType.NONE }
            PortfolioExchangeRoute.pickPair(
                coin, pairs.map { PortfolioExchangeRoute.Pair3(it.base, it.quote, it.pairId) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "Portfolio-Verlauf: Paarliste von %s nicht lesbar", market.second)
            null
        }
        if (pick == null) {
            unresolved += coin
            continue
        }
        out[coin] = CandlePair(market.first, market.second, pick.base, pick.quote, pick.pairId)
    }
    return HistoryRoutes(out, unresolved)
}

/**
 * Eigene Tageskerzen (USDT) von [pair]: Speicher, Gerät, sonst Abfrage; null = keine. Scheitert die Abfrage, gilt ein
 * älterer Stand desselben Wegs — markiert mit seiner Zeit ([CoinCloses.staleSince]), damit der Verlauf «Stand …» sagt.
 */
internal suspend fun PortfolioHistorySource.ownOf(coin: String, pair: CandlePair, span: Int): CoinCloses? {
    val now = System.currentTimeMillis()
    val route = pair.cacheKey
    ownMemory[coin]?.let { (cached, at) ->
        if (cached.route == route && cached.entry.span >= span && CycleCachePolicy.isFresh(at, now, TTL_MILLIS)) return cached.entry
    }
    val stored = cacheStore.read(ownCacheName(coin), OWN_CODEC)?.takeIf { it.value.route == route }
    if (stored != null && stored.value.entry.span >= span && CycleCachePolicy.isFresh(stored.savedAt, now, TTL_MILLIS)) {
        ownMemory[coin] = stored.value to stored.savedAt
        return stored.value.entry
    }
    val fresh = fetchOwn(pair, span, now)
    if (fresh != null) {
        val value = OwnCloses(route, fresh)
        ownMemory[coin] = value to now
        cacheStore.write(ownCacheName(coin), value, now, OWN_CODEC)
        return fresh
    }
    // Nicht erreichbar: älterer Stand desselben Wegs ist besser als keiner — aber als solcher markiert
    val memory = ownMemory[coin]?.takeIf { it.first.route == route }
    val entry = stored?.value?.entry ?: memory?.first?.entry ?: return null
    val savedAt = if (stored != null) stored.savedAt else memory?.second ?: 0L
    val since = PortfolioHistoryAsOf.staleSince(savedAt, now, TTL_MILLIS) ?: return entry
    return entry.copy(staleSince = since)
}

/** Tageskerzen der eigenen Börse holen und in USDT umrechnen; null = keine (dann Ausweich). */
internal suspend fun PortfolioHistorySource.fetchOwn(pair: CandlePair, span: Int, now: Long): CoinCloses? {
    val candles = try {
        ownCandles.candles(pair, CandleInterval.D1, span.coerceAtMost(MAX_OWN_DAYS))?.value
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    } ?: return null
    val closes = HashMap<Long, Double>()
    val ranges = HashMap<Long, PortfolioDayRange>()
    for (c in candles) {
        val day = PortfolioHistory.epochDayOfUtcMillis(c.openTime)
        closes[day] = c.close
        PortfolioDayRange(c.open, c.high, c.low).takeIf { it.valid }?.let { ranges[day] = it }
    }
    val first = closes.keys.minOrNull() ?: return null
    val quote = pair.quote.uppercase()
    // USD- und Fiat-Kerzen: zusätzlich durch USDT/USD des Tags (USDT zum Marktkurs); nicht für USDT/USD selbst
    // (Kraken USDTZUSD, Coinbase USDT-USD als Lückenfüller — sonst riefe die Reihe sich selbst auf)
    val usdtUsd = if (quote == "USDT" || pair.base.trim().equals("USDT", ignoreCase = true)) null else usdtUsdDaily(span)
    val rates = if (quote == "USDT" || quote == "USD") {
        emptyMap()
    } else {
        val today = LocalDate.ofEpochDay(PortfolioHistory.epochDayOfUtcMillis(now))
        try {
            historicSource.usdToSeries(quote, LocalDate.ofEpochDay(first - PortfolioHistoryFx.LEAD_DAYS), today)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
    }
    val usdt = PortfolioExchangeRoute.ownDailyUsdt(PortfolioCoinDaily(closes, ranges), quote, rates, usdtUsd) ?: return null
    return CoinCloses(usdt.closes, span, usdt.ranges)
}
