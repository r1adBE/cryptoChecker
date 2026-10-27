package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.MEMORY_CACHE_ENTRIES
import com.cryptochecker.app.data.BoundedCache
import com.cryptochecker.app.data.CycleCacheStore
import com.cryptochecker.app.data.MarketRepository
import com.cryptochecker.app.data.TokenIdentityStore
import com.cryptochecker.app.data.remote.BlockedSources
import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.data.remote.callMarket
import com.cryptochecker.app.data.remote.candles.CandlePair
import com.cryptochecker.app.data.remote.candles.OwnCandleDataSource
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.exceptions.HttpMarketError
import com.cryptochecker.app.domain.market.CycleCachePolicy
import com.cryptochecker.app.domain.portfolio.ChainAssets
import com.cryptochecker.app.domain.portfolio.HistoricCloseChain
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioDayRange
import com.cryptochecker.app.domain.portfolio.PortfolioExchangeRoute
import com.cryptochecker.app.domain.portfolio.PortfolioHistory
import com.cryptochecker.app.domain.portfolio.PortfolioStables
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
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import com.cryptochecker.app.domain.portfolio.candleChunks
import com.cryptochecker.app.domain.portfolio.candleDays

/**
 * Tagesschlüsse eines Coins und wie viele Tage bis heute dafür abgefragt wurden ([span]);
 * [ranges] = Eröffnung/Hoch/Tief derselben Kerzen (leer bei Einträgen älterer Versionen).
 */
data class CoinCloses(
    val closes: Map<Long, Double>,
    val span: Int,
    val ranges: Map<Long, PortfolioDayRange> = emptyMap(),
    /**
     * Gemerkter Stand statt frischer Abfrage (Börse nicht erreichbar): Zeit dieses Stands; null = frisch.
     * Nur im Ablauf, nicht gespeichert ([PortfolioHistorySource.CODEC] kennt das Feld nicht).
     */
    val staleSince: Long? = null,
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
    /**
     * Coins, deren eigene Reihe ein älterer Stand ist (Börse nicht erreichbar) → Zeit dieses Stands; die Karte zeigt
     * «Stand …» ([com.cryptochecker.app.domain.portfolio.PortfolioHistoryAsOf]) statt eines scheinbar heutigen Verlaufs.
     */
    val ownStale: Map<String, Long> = emptyMap(),
    /**
     * Coins mit Reihe aus der Ausweich-Kette (Binance führt sie nicht oder ist nicht erreichbar, keine eigene Börse) →
     * Anbieter, z. B. «Kraken» ([HistoricCloseChain.SERIES_CHAIN]); die Karte nennt ihn («Tageskurse von …»).
     */
    val chainSources: Map<String, String> = emptyMap(),
)

/** Eigene Tageskerzen eines Coins (USDT) und für welchen Weg ([route] = Schlüssel des Paars). */
data class OwnCloses(val route: String, val entry: CoinCloses)

/**
 * Wege für den Verlauf: [pairs] = Coins mit eigener Börse und Paar; [unresolved] = Coins mit eingetragener Börse, deren
 * Weg sich nicht lesen liess (Börsenliste, Paarliste oder Transaktionen nicht lesbar, kein Paar) — nie still Binance.
 */
class HistoryRoutes(val pairs: Map<String, CandlePair>, val unresolved: Set<String>) {
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
 * Verlauf und Export in Fiat ([com.cryptochecker.app.domain.portfolio.UsdtUsdRates]). Fehlen Kraken Tage (nicht erreichbar,
 * Lücke), füllt Coinbase USDT-USD sie ([HistoricCloseChain.fillUsdtUsd], «phisto_USDTUSDCB») — erst danach «USDT = 1».
 *
 * Ausweich-Kette (PortfolioHistorySourceChain.kt): Coins ohne Binance-Reihe und ohne eigene Börse bekommen die Tageskerzen
 * von Coinbase → Kraken → OKX → Bybit, nur mit passendem Namen und ±25 % gegen den aktuellen Kurs (12 h, «phistc_<COIN>»).
 */
@Singleton
class PortfolioHistorySource @Inject constructor(
    private val httpClient: OkHttpClient,
    val cacheStore: CycleCacheStore,
    val portfolioDao: PortfolioDao,
    val marketRepository: MarketRepository,
    val ownCandles: OwnCandleDataSource,
    val historicSource: HistoricPriceSource,
    val tokenIdentityStore: TokenIdentityStore,
    val priceSource: PortfolioPriceSource,
) {
    /** Coin → (Reihe der Ausweich-Kette samt Weg, Abfragezeit). Begrenzt (sonst blieb jeder je abgefragte Coin bis zum Prozessende). */
    val chainMemory = BoundedCache<String, Pair<OwnCloses, Long>>(MEMORY_CACHE_ENTRIES)

    /** Coin → Zeit der letzten Ausweich-Abfrage ohne Treffer (nicht bei jedem Öffnen alle Börsen erneut). */
    val chainMisses = BoundedCache<String, Long>(MEMORY_CACHE_ENTRIES)

    /** Coin → (Schlüsse samt Spanne, Abfragezeit); leere Karte = kein Binance-Paar. */
    private val memory = BoundedCache<String, Pair<CoinCloses, Long>>(MEMORY_CACHE_ENTRIES)

    /** Coin → (eigene Tageskerzen in USDT, Abfragezeit). */
    val ownMemory = BoundedCache<String, Pair<OwnCloses, Long>>(MEMORY_CACHE_ENTRIES)

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
            }.mapNotNull { (coin, entry) -> entry?.takeIf { it.closes.isNotEmpty() }?.let { coin to it } }.toMap()
            // Eingetragene Börse: deren eigene Tageskerzen statt Binance
            val routes = routes(symbols)
            val own = coroutineScope {
                routes.pairs.map { (coin, pair) -> async { coin to limiter.withPermit { ownOf(coin, pair, span) } } }.awaitAll()
            }.mapNotNull { (coin, entry) -> entry?.let { coin to it } }.toMap()
            // Weder Binance-Reihe noch eingetragene Börse: Ausweich-Kette (Coinbase → Kraken → OKX → Bybit)
            val chain = coroutineScope {
                symbols.filter { it !in loaded && it !in routes.routed }
                    .map { coin -> async { coin to limiter.withPermit { chainOf(coin, span) } } }.awaitAll()
            }.mapNotNull { (coin, entry) -> entry?.takeIf { it.entry.closes.isNotEmpty() }?.let { coin to it } }.toMap()
            mergeOwn(loaded + chain.mapValues { it.value.entry }, own, routes.routed).withWrapped(requested)
                .withChain(chain).copy(usdtUsd = usdtUsdDaily(span))
        }

    /**
     * Tagesschlüsse USDT/USD (UTC-Tag → Schluss) für mindestens die letzten [days] Tage: Kraken-Tageskerzen USDTZUSD
     * (höchstens 720 Tage, ältere Tage ohne Schluss = 1 als Näherung, [com.cryptochecker.app.domain.portfolio.UsdtUsdRates.on]).
     * Speicher, Gerät (12 h), sonst Abfrage; nicht erreichbar → älterer Stand oder leer.
     */
    suspend fun usdtUsdDaily(days: Int = PortfolioHistory.MAX_DAYS): Map<Long, Double> = withContext(Dispatchers.IO) {
        // Eine Abfrage für alle gleichzeitig ladenden Coins (die übrigen warten auf den Speicher)
        usdtUsdMutex.withLock {
            val span = days.coerceAtLeast(PortfolioHistory.MAX_DAYS)
            val kraken = ownOf(USDT_USD_KEY, USDT_USD_PAIR, span)?.closes.orEmpty()
            // Lücken bei Kraken (nicht erreichbar, Tag fehlt): Coinbase USDT-USD — erst danach «USDT = 1 angenommen»
            val today = PortfolioHistory.epochDayOfUtcMillis(System.currentTimeMillis())
            val gap = (today - minOf(span, USDT_USD_FILL_DAYS) + 1 until today).any { it !in kraken }
            val coinbase = if (gap) ownOf(USDT_USD_FILL_KEY, USDT_USD_FILL_PAIR, span)?.closes.orEmpty() else emptyMap()
            HistoricCloseChain.fillUsdtUsd(kraken, coinbase)
        }
    }

    /** Wie [usdtUsdDaily], nur was schon da ist (Speicher, Gerät) — ohne Netz, ohne Altersgrenze (Widget). */
    private suspend fun cachedUsdtUsdDaily(): Map<Long, Double> {
        suspend fun cachedOwn(key: String, pair: CandlePair): Map<Long, Double> {
            val route = pair.cacheKey
            val entry = ownMemory[key]?.first?.takeIf { it.route == route }
                ?: cacheStore.read(ownCacheName(key), OWN_CODEC)?.takeIf { it.value.route == route }
                    ?.also { ownMemory.putIfAbsent(key, it.value to it.savedAt) }?.value
            return entry?.entry?.closes.orEmpty()
        }
        return HistoricCloseChain.fillUsdtUsd(cachedOwn(USDT_USD_KEY, USDT_USD_PAIR), cachedOwn(USDT_USD_FILL_KEY, USDT_USD_FILL_PAIR))
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
            // Reihen der Ausweich-Kette, ebenfalls nur aus dem Speicher
            val have = loaded.map { it.first }.toSet()
            val chain = symbols.filter { it !in have && it !in routes.routed }
                .mapNotNull { coin -> cachedChain(coin)?.takeIf { it.entry.closes.isNotEmpty() }?.let { coin to it } }.toMap()
            mergeOwn(loaded.toMap() + chain.mapValues { it.value.entry }, own, routes.routed).withWrapped(requested)
                .withChain(chain).copy(usdtUsd = cachedUsdtUsdDaily())
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
            val candles = runCatchingCancellable { CandleDataSource.parseBinance(body) }.getOrNull() ?: continue
            return candles.associate {
                PortfolioHistory.epochDayOfUtcMillis(it.openTime) to DayCandle(it.open, it.high, it.low, it.close)
            }
        }
        return null
    }

    /** Eine Tageskerze (USDT) aus der Antwort. */
    private data class DayCandle(val open: Double, val high: Double, val low: Double, val close: Double)

    companion object {
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

        /** Lückenfüller USDT/USD: Coinbase USDT-USD (eigene Tageskerzen, etwa 600 Tage). */
        const val USDT_USD_FILL_KEY = "USDTUSDCB"
        const val USDT_USD_FILL_DAYS = 600
        val USDT_USD_FILL_PAIR = CandlePair(HistoricCloseChain.COINBASE, HistoricCloseChain.COINBASE, "USDT", "USD", "USDT-USD")

        val HOSTS = listOf(
            "data-api.binance.vision" to "https://data-api.binance.vision/api/v3/klines",
            "api.binance.com" to "https://api.binance.com/api/v3/klines",
        )

        fun isAsset(s: String) = s.isNotEmpty() && s.length <= 15 && s.all { it.isLetterOrDigit() }

        fun cacheName(coin: String) = "phist_" + coin.filter { it.isLetterOrDigit() }

        fun ownCacheName(coin: String) = "phisto_" + coin.filter { it.isLetterOrDigit() }

        fun chainCacheName(coin: String) = "phistc_" + coin.filter { it.isLetterOrDigit() }

        /** Eintrag «phist_<COIN>» ([PortfolioHistoryCodecs.closes]). */
        val CODEC = PortfolioHistoryCodecs.closes

        /** Eintrag «phisto_…»/«phistc_…» mit Weg ([PortfolioHistoryCodecs.own]). */
        val OWN_CODEC = PortfolioHistoryCodecs.own
    }
}
