package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.CacheCodec
import com.cryptochecker.app.data.CycleCacheStore
import com.cryptochecker.app.data.MarketRepository
import com.cryptochecker.app.data.remote.BlockedSources
import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.app.data.remote.callMarket
import com.cryptochecker.app.data.remote.candles.CandlePair
import com.cryptochecker.app.data.remote.candles.OwnCandleDataSource
import com.cryptochecker.app.domain.exceptions.HttpMarketError
import com.cryptochecker.app.domain.market.CycleCachePolicy
import com.cryptochecker.app.domain.portfolio.ChainAssets
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioCoinDaily
import com.cryptochecker.app.domain.portfolio.PortfolioDayRange
import com.cryptochecker.app.domain.portfolio.PortfolioExchangeRoute
import com.cryptochecker.app.domain.portfolio.PortfolioHistory
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryFx
import com.cryptochecker.app.domain.portfolio.PortfolioStables
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.marketdata.model.FuturesContractType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tagesschlüsse eines Coins und wie viele Tage bis heute dafür abgefragt wurden ([span]);
 * [ranges] = Eröffnung/Hoch/Tief derselben Kerzen (leer bei Einträgen älterer Versionen).
 */
private data class CoinCloses(
    val closes: Map<Long, Double>,
    val span: Int,
    val ranges: Map<Long, PortfolioDayRange> = emptyMap(),
)

/**
 * Tageskerzen für den Wertverlauf: Schluss je Coin und UTC-Tag ([closes]) und, soweit vorhanden,
 * Eröffnung/Hoch/Tief derselben Kerzen ([ranges], für die Kerzen-Ansicht). Coins in [own]: Reihe von der
 * eigenen Börse (in USDT; vor [binanceBefore] Binance angeglichen); [routed] = Coins mit eigener Börse
 * (Paar gefunden) — ohne eigene Reihe skaliert sie [PortfolioExchangeRoute.historyCloses] oder lässt sie weg.
 */
data class PortfolioDailyPrices(
    val closes: Map<String, Map<Long, Double>>,
    val ranges: Map<String, Map<Long, PortfolioDayRange>> = emptyMap(),
    val own: Set<String> = emptySet(),
    val binanceBefore: Map<String, Long> = emptyMap(),
    val routed: Set<String> = emptySet(),
    /**
     * Tagesschluss USDT/USD je UTC-Tag (Kraken USDTZUSD, [PortfolioHistorySource.usdtUsdDaily]) für USDT zum Marktkurs
     * ([com.cryptochecker.app.domain.portfolio.UsdtUsdRates.daily]); leer = keiner (dann 1 als Näherung).
     */
    val usdtUsd: Map<Long, Double> = emptyMap(),
)

/** Eigene Tageskerzen eines Coins (USDT) und für welchen Weg ([route] = Schlüssel des Paars). */
private data class OwnCloses(val route: String, val entry: CoinCloses)

/**
 * Wege für den Verlauf: [pairs] = Coins mit eigener Börse und Paar; [unresolved] = Coins mit eingetragener Börse, deren
 * Weg sich nicht lesen liess (Börsenliste, Paarliste oder Transaktionen nicht lesbar, kein Paar) — nie still Binance.
 */
private class HistoryRoutes(val pairs: Map<String, CandlePair>, val unresolved: Set<String>) {
    val routed: Set<String> get() = pairs.keys + unresolved
}

/**
 * Tagesschlusskurse für den Wertverlauf im Portfolio: Binance-Tageskerzen
 * `<BASE>USDT` — mindestens [PortfolioHistory.MAX_DAYS] Tage, für «Seit 1. Kauf» so viele wie
 * nötig ([PortfolioHistory.candleDays]) in Stücken von höchstens
 * [PortfolioHistory.MAX_CANDLES_PER_REQUEST] ([PortfolioHistory.candleChunks]) —
 * data-api.binance.vision, sonst api.binance.com. Zwischenspeicher je Coin 12 h auf
 * dem Gerät ([CycleCacheStore], Eintrag «phist_<COIN>», samt abgefragter Spanne) und im
 * Speicher; ein Eintrag mit kürzerer Spanne als verlangt wird neu geholt. Ein Coin, den
 * Binance nicht führt (HTTP 400), wird als «ohne Verlauf» ebenfalls 12 h gemerkt.
 * Nur USDT braucht keine Abfrage; andere Stablecoins (z. B. USDCUSDT) werden wie jeder Coin
 * geholt — fehlt ihr Kurs, fehlen sie im Verlauf wie jeder Coin ([PortfolioStables], [PortfolioHistory]).
 * Von denselben Kerzen werden auch Eröffnung, Hoch und Tief behalten (keine zusätzliche Abfrage).
 *
 * Coins mit eigener Börse ([PortfolioExchangeRoute], Paar aus der gespeicherten Paarliste): Tageskerzen
 * dieser Börse ([OwnCandleDataSource], höchstens 1000 je Abfrage), in USDT umgerechnet (Fiat mit den
 * Devisen-Tageskursen, [HistoricPriceSource.usdToSeries]); ebenfalls 12 h gemerkt («phisto_<COIN>»).
 *
 * USDT zum Marktkurs (Owner-Entscheid Okt. 2026): Tagesschlüsse USDT/USD von Kraken ([usdtUsdDaily], Paar USDTZUSD über
 * dieselben eigenen Tageskerzen, 12 h gemerkt «phisto_USDTZUSD») — nur mit Portfolio (nur dessen Abrufe holen sie). Damit
 * rechnen USD- und Fiat-Kerzen eigener Börsen in USDT ([PortfolioExchangeRoute.ownDailyUsdt]) und die Umrechnung von
 * Verlauf und Export in Fiat ([com.cryptochecker.app.domain.portfolio.UsdtUsdRates]).
 */
@Singleton
class PortfolioHistorySource @Inject constructor(
    private val httpClient: OkHttpClient,
    private val cacheStore: CycleCacheStore,
    private val portfolioDao: PortfolioDao,
    private val marketRepository: MarketRepository,
    private val ownCandles: OwnCandleDataSource,
    private val historicSource: HistoricPriceSource,
) {
    /** Coin → (Schlüsse samt Spanne, Abfragezeit); leere Karte = kein Binance-Paar. */
    private val memory = ConcurrentHashMap<String, Pair<CoinCloses, Long>>()

    /** Coin → (eigene Tageskerzen in USDT, Abfragezeit). */
    private val ownMemory = ConcurrentHashMap<String, Pair<OwnCloses, Long>>()

    /** Schützt [usdtUsdDaily] vor parallelen Abfragen desselben Paars. */
    private val usdtUsdMutex = Mutex()

    /**
     * Schlusskurse je Coin (Grossschreibung) für mindestens die letzten [days] Tage. Coins
     * ohne Verlauf (kein Paar, Netz weg und nichts gespeichert) fehlen in der Rückgabe.
     */
    suspend fun dailyCloses(
        coins: Collection<String>,
        days: Int = PortfolioHistory.MAX_DAYS,
    ): Map<String, Map<Long, Double>> = dailyPrices(coins, days).closes

    /** Wie [dailyCloses], zusätzlich mit Eröffnung/Hoch/Tief der Tageskerzen. */
    suspend fun dailyPrices(
        coins: Collection<String>,
        days: Int = PortfolioHistory.MAX_DAYS,
    ): PortfolioDailyPrices =
        withContext(Dispatchers.IO) {
            val requested = coins.map { PortfolioCalculator.normalizeCoin(it) }.filter { PortfolioStables.needsQuote(it) }.toSet()
            // Dazu die Basis gekapselter Coins: ohne eigene Tageskerzen gilt deren Reihe ([withWrapped])
            val symbols = ChainAssets.withBases(requested)
                .filter { isAsset(it) && PortfolioStables.needsQuote(it) }
                .distinct()
            val span = days.coerceAtLeast(PortfolioHistory.MAX_DAYS)
            val limiter = Semaphore(PARALLEL)
            val loaded = coroutineScope {
                symbols.map { coin -> async { coin to limiter.withPermit { closesOf(coin, span) } } }.awaitAll()
            }.mapNotNull { (coin, entry) -> entry?.takeIf { it.closes.isNotEmpty() }?.let { coin to it } }
            // Eingetragene Börse: deren eigene Tageskerzen statt Binance
            val routes = routes(symbols)
            val own = coroutineScope {
                routes.pairs.map { (coin, pair) -> async { coin to limiter.withPermit { ownOf(coin, pair, span) } } }.awaitAll()
            }.mapNotNull { (coin, entry) -> entry?.let { coin to it } }.toMap()
            mergeOwn(loaded.toMap(), own, routes.routed).withWrapped(requested).copy(usdtUsd = usdtUsdDaily(span))
        }

    /**
     * Tagesschlüsse USDT/USD (UTC-Tag → Schluss) für mindestens die letzten [days] Tage: Kraken-Tageskerzen USDTZUSD
     * (höchstens 720 Tage, ältere Tage ohne Schluss = 1 als Näherung, [com.cryptochecker.app.domain.portfolio.UsdtUsdRates.on]).
     * Speicher, Gerät (12 h), sonst Abfrage; nicht erreichbar → älterer Stand oder leer.
     */
    suspend fun usdtUsdDaily(days: Int = PortfolioHistory.MAX_DAYS): Map<Long, Double> = withContext(Dispatchers.IO) {
        // Eine Abfrage für alle gleichzeitig ladenden Coins (die übrigen warten auf den Speicher)
        usdtUsdMutex.withLock { ownOf(USDT_USD_KEY, USDT_USD_PAIR, days.coerceAtLeast(PortfolioHistory.MAX_DAYS))?.closes.orEmpty() }
    }

    /** Wie [usdtUsdDaily], nur was schon da ist (Speicher, Gerät) — ohne Netz, ohne Altersgrenze (Widget). */
    private suspend fun cachedUsdtUsdDaily(): Map<Long, Double> {
        val route = USDT_USD_PAIR.cacheKey
        val entry = ownMemory[USDT_USD_KEY]?.first?.takeIf { it.route == route }
            ?: cacheStore.read(ownCacheName(USDT_USD_KEY), OWN_CODEC)?.takeIf { it.value.route == route }
                ?.also { ownMemory.putIfAbsent(USDT_USD_KEY, it.value to it.savedAt) }?.value
        return entry?.entry?.closes.orEmpty()
    }

    /**
     * Binance-Reihen [binance] mit den eigenen Reihen [own] zusammenführen: eigene Reihe ersetzt Binance,
     * reicht sie nicht so weit zurück, davor Binance angeglichen ([PortfolioExchangeRoute.stitch]).
     */
    private fun mergeOwn(binance: Map<String, CoinCloses>, own: Map<String, CoinCloses>, routed: Set<String>): PortfolioDailyPrices {
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
        return PortfolioDailyPrices(closes, ranges, own.keys.toSet(), before, routed)
    }

    /**
     * Wege der [coins] wie die Kurse ([PortfolioExchangeRoute], `PortfolioPriceSource.applyExchangeRoutes`):
     * einheitlich eingetragene Spot-Börse (nicht Binance) und ihr Paar aus der gespeicherten Paarliste —
     * ohne Netz (die Paarliste lädt die Kursabfrage). Binance und freier Text (Börsenliste gelesen, kein Markt):
     * kein Weg. Lässt sich der Weg nicht lesen, steht der Coin in [HistoryRoutes.unresolved] (Verlauf dann nur
     * Binance skaliert oder «ohne passende Tageskurse», [PortfolioExchangeRoute.historyCloses]) — nie still Binance.
     */
    private suspend fun routes(coins: Collection<String>): HistoryRoutes {
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

    /** Eigene Tageskerzen (USDT) von [pair]: Speicher, Gerät, sonst Abfrage; null = keine. */
    private suspend fun ownOf(coin: String, pair: CandlePair, span: Int): CoinCloses? {
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
        // Nicht erreichbar: älterer Stand desselben Wegs ist besser als keiner
        return stored?.value?.entry ?: ownMemory[coin]?.first?.takeIf { it.route == route }?.entry
    }

    /** Tageskerzen der eigenen Börse holen und in USDT umrechnen; null = keine (dann Ausweich). */
    private suspend fun fetchOwn(pair: CandlePair, span: Int, now: Long): CoinCloses? {
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
        val usdtUsd = if (quote == "USDT" || pair.cacheKey == USDT_USD_PAIR.cacheKey) null else usdtUsdDaily(span)
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

    /**
     * Nur was schon da ist (Speicher, sonst Gerät) — ohne Netz und ohne Altersgrenze: für das
     * Portfolio-Widget (7 T / 30 T / 1 J), das keine eigenen Abfragen macht. Wie alt die Kurse
     * sind, prüft der Aufrufer (jüngster Schluss, siehe `PortfolioWidgetChart.usableCloses`).
     */
    suspend fun cachedDailyPrices(coins: Collection<String>): PortfolioDailyPrices =
        withContext(Dispatchers.IO) {
            val requested = coins.map { PortfolioCalculator.normalizeCoin(it) }.filter { PortfolioStables.needsQuote(it) }.toSet()
            // Dazu die Basis gekapselter Coins: ohne eigene Tageskerzen gilt deren Reihe ([withWrapped])
            val symbols = ChainAssets.withBases(requested)
                .filter { isAsset(it) && PortfolioStables.needsQuote(it) }
                .distinct()
            val loaded = symbols.mapNotNull { coin ->
                val entry = memory[coin]?.first
                    ?: cacheStore.read(cacheName(coin), CODEC)?.also { memory.putIfAbsent(coin, it.value to it.savedAt) }?.value
                entry?.takeIf { it.closes.isNotEmpty() }?.let { coin to it }
            }
            // Eigene Tageskerzen desselben Wegs (wie [dailyPrices]), ebenfalls nur aus dem Speicher
            val routes = routes(symbols)
            val own = routes.pairs.mapNotNull { (coin, pair) ->
                val route = pair.cacheKey
                val entry = ownMemory[coin]?.first?.takeIf { it.route == route }
                    ?: cacheStore.read(ownCacheName(coin), OWN_CODEC)?.takeIf { it.value.route == route }
                        ?.also { ownMemory.putIfAbsent(coin, it.value to it.savedAt) }?.value
                entry?.entry?.takeIf { it.closes.isNotEmpty() }?.let { coin to it }
            }.toMap()
            mergeOwn(loaded.toMap(), own, routes.routed).withWrapped(requested).copy(usdtUsd = cachedUsdtUsdDaily())
        }

    /**
     * Gekapselte Coins ohne eigene Tageskerzen (z. B. BTC.b): Reihe der Basis ([ChainAssets.priceViaBase]) — wie ihr
     * Kurs; nur die verlangten Coins ([requested]) bleiben (die Basis wurde evtl. nur dafür geholt).
     */
    private fun PortfolioDailyPrices.withWrapped(requested: Set<String>): PortfolioDailyPrices {
        val via = ChainAssets.priceViaBase(requested, closes.keys)
        val c = closes.toMutableMap()
        val r = ranges.toMutableMap()
        for ((coin, base) in via) {
            closes[base]?.let { c[coin] = it }
            ranges[base]?.let { r[coin] = it }
        }
        return copy(closes = c.filterKeys { it in requested }, ranges = r.filterKeys { it in requested })
    }

    private suspend fun closesOf(coin: String, span: Int): CoinCloses? {
        val now = System.currentTimeMillis()
        memory[coin]?.let { (cached, at) ->
            if (cached.span >= span && CycleCachePolicy.isFresh(at, now, TTL_MILLIS)) return cached
        }
        val stored = cacheStore.read(cacheName(coin), CODEC)
        if (stored != null && stored.value.span >= span && CycleCachePolicy.isFresh(stored.savedAt, now, TTL_MILLIS)) {
            memory[coin] = stored.value to stored.savedAt
            return stored.value
        }
        val fresh = fetch(coin, span, now)
        if (fresh != null) {
            memory[coin] = fresh to now
            cacheStore.write(cacheName(coin), fresh, now, CODEC)
            return fresh
        }
        // Netz weg: älterer (oder kürzerer) Stand ist besser als keiner
        return stored?.value ?: memory[coin]?.first
    }

    /**
     * Kerzen der letzten [span] Tage holen, neueste Stücke zuerst; leere Karte = Binance kennt
     * das Paar nicht, null = nicht erreichbar. Beginnt ein Stück später als verlangt (Coin
     * damals noch nicht gelistet), werden die älteren nicht mehr abgefragt.
     */
    private suspend fun fetch(coin: String, span: Int, now: Long): CoinCloses? {
        val today = PortfolioHistory.epochDayOfUtcMillis(now)
        val out = HashMap<Long, Double>()
        val ranges = HashMap<Long, PortfolioDayRange>()
        for (chunk in PortfolioHistory.candleChunks(span, today).asReversed()) {
            val candles = fetchChunk(coin, chunk) ?: return null
            if (candles.isEmpty() && out.isEmpty()) return CoinCloses(emptyMap(), span)
            for ((day, c) in candles) {
                out[day] = c.close
                PortfolioDayRange(c.open, c.high, c.low).takeIf { it.valid }?.let { ranges[day] = it }
            }
            if (candles.isEmpty() || candles.keys.min() > chunk.first) break
        }
        return CoinCloses(out, span, ranges)
    }

    /** Ein Stück Tageskerzen (UTC-Tage [days]); leere Karte = kein Paar oder keine Kerzen, null = nicht erreichbar. */
    private suspend fun fetchChunk(coin: String, days: LongRange): Map<Long, DayCandle>? {
        val startMillis = days.first * DAY_MILLIS
        val endMillis = (days.last + 1) * DAY_MILLIS - 1
        val limit = (days.last - days.first + 1).toInt()
        for ((host, endpoint) in HOSTS) {
            if (BlockedSources.isBlocked(host)) continue
            val url = "$endpoint?symbol=${coin}USDT&interval=1d&startTime=$startMillis&endTime=$endMillis&limit=$limit"
            val body = try {
                withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { httpClient.callMarket(url, null) }
            } catch (e: Exception) {
                // Echten Abbruch des Aufrufers nicht verschlucken (eine Zeitgrenze darunter schon)
                currentCoroutineContext().ensureActive()
                // «Invalid symbol»: Paar gibt es nicht — der Spiegel und der Spot führen dieselben Paare
                if (e is HttpMarketError && e.httpCode == 400) return emptyMap()
                if (!BlockedSources.noteFailure(host, e)) Timber.d(e, "Portfolio-Verlauf: %s", url)
                null
            } ?: continue
            val candles = runCatching { CandleDataSource.parseBinance(body) }.getOrNull() ?: continue
            return candles.associate {
                PortfolioHistory.epochDayOfUtcMillis(it.openTime) to DayCandle(it.open, it.high, it.low, it.close)
            }
        }
        return null
    }

    /** Eine Tageskerze (USDT) aus der Antwort. */
    private data class DayCandle(val open: Double, val high: Double, val low: Double, val close: Double)

    private companion object {
        const val PARALLEL = 4
        const val REQUEST_TIMEOUT_MILLIS = 10_000L
        const val TTL_MILLIS = 12 * 60 * 60_000L
        const val DAY_MILLIS = 86_400_000L

        /** Eigene Tageskerzen höchstens so viele (Grenze der eigenen Kerzen); davor Binance angeglichen. */
        const val MAX_OWN_DAYS = 1000

        /** USDT/USD-Tagesschlüsse: Kraken USDTZUSD (dasselbe Paar wie die Messung des Depeg-Alarms). */
        const val USDT_USD_KEY = "USDTZUSD"
        val USDT_USD_PAIR = CandlePair(
            com.cryptochecker.app.domain.alarm.DepegAlarm.KRAKEN_KEY, com.cryptochecker.app.domain.alarm.DepegAlarm.KRAKEN_KEY,
            "USDT", "USD", com.cryptochecker.app.domain.alarm.DepegAlarm.KRAKEN_PAIR_ID,
        )

        val HOSTS = listOf(
            "data-api.binance.vision" to "https://data-api.binance.vision/api/v3/klines",
            "api.binance.com" to "https://api.binance.com/api/v3/klines",
        )

        fun isAsset(s: String) = s.isNotEmpty() && s.length <= 15 && s.all { it.isLetterOrDigit() }

        fun cacheName(coin: String) = "phist_" + coin.filter { it.isLetterOrDigit() }

        fun ownCacheName(coin: String) = "phisto_" + coin.filter { it.isLetterOrDigit() }

        /**
         * {"span": 366, "days":[[epochDay, close, open, high, low], …]} — leere Liste = kein Paar.
         * Ältere Einträge ohne «span» stammen aus der festen Jahres-Abfrage ([PortfolioHistory.MAX_DAYS]);
         * ältere Tage nur mit [epochDay, close] haben keine Eröffnung/Hoch/Tief (Kerzen ohne Docht,
         * bis der Eintrag nach 12 h neu geholt wird).
         */
        val CODEC = CacheCodec<CoinCloses>(
            encode = { entry ->
                val days = JSONArray()
                entry.closes.entries.sortedBy { it.key }.forEach { (day, close) ->
                    if (close.isFinite()) {
                        val row = JSONArray().put(day).put(close)
                        entry.ranges[day]?.let { row.put(it.open).put(it.high).put(it.low) }
                        days.put(row)
                    }
                }
                JSONObject().put("span", entry.span).put("days", days)
            },
            decode = { o ->
                val days = o.getJSONArray("days")
                val closes = HashMap<Long, Double>()
                val ranges = HashMap<Long, PortfolioDayRange>()
                for (i in 0 until days.length()) {
                    val row = days.getJSONArray(i)
                    val day = row.getLong(0)
                    closes[day] = row.getDouble(1)
                    if (row.length() >= 5) {
                        PortfolioDayRange(row.getDouble(2), row.getDouble(3), row.getDouble(4))
                            .takeIf { it.valid }?.let { ranges[day] = it }
                    }
                }
                CoinCloses(closes, o.optInt("span", PortfolioHistory.MAX_DAYS), ranges)
            },
        )

        /** Wie [CODEC], dazu der Weg («route»): ein anderer Weg (Börse/Paar geändert) gilt als nicht gemerkt. */
        val OWN_CODEC = CacheCodec<OwnCloses>(
            encode = { value -> CODEC.encode(value.entry).put("route", value.route) },
            decode = { o -> OwnCloses(o.getString("route"), CODEC.decode(o)) },
        )
    }
}
