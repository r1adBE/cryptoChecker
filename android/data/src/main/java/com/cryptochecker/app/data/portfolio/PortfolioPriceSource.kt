package com.cryptochecker.app.data.portfolio

import android.content.Context
import com.cryptochecker.app.data.ExchangeRepository
import com.cryptochecker.app.data.TokenIdentityStore
import com.cryptochecker.app.data.local.WatchDao
import com.cryptochecker.app.data.remote.BlockedSources
import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.app.data.remote.callExchange
import com.cryptochecker.app.domain.portfolio.ChainAssets
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioExchangeRoute
import com.cryptochecker.app.domain.portfolio.PortfolioPriceFallback
import com.cryptochecker.app.domain.portfolio.PortfolioPriceIdentity
import com.cryptochecker.app.domain.portfolio.PortfolioStables
import com.cryptochecker.app.domain.portfolio.PortfolioWatchPair
import com.cryptochecker.app.domain.portfolio.PricePlausibility
import com.cryptochecker.app.domain.portfolio.StoredPrice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kurse und Coin-Liste für das Portfolio, immer in USDT.
 *
 * Kurse: Sammelabfrage beim Binance-Spiegel (`ticker/price?symbols=[…]`), für
 * fehlende Coins einzeln der letzte Schlusskurs der 1-h-Kerzen aus der
 * [CandleDataSource]-Kette (Binance → Binance.US → Coinbase). USDT = 1; andere Stablecoins
 * (USDC, DAI …) werden wie jeder Coin geholt — fehlt ihr Kurs, haben sie keinen Wert
 * ([PortfolioStables]). Zwischenspeicher 60 s.
 *
 * Identitätsprüfung des Kerzenkurses: Er gilt nur, wenn er zum selben Token passt wie ein
 * unabhängiger Bezug ([PricePlausibility.candleReference]: Merklisten-Kurs desselben Coins gegen
 * einen USD-Stablecoin, sonst der letzte gute gemerkte Kurs) — Verhältnis 1/3…3
 * ([PricePlausibility.sameToken]). Passt er nicht (gleiches Kürzel, anderer Token), gibt es keinen
 * neuen Kurs («—») statt einer sicher wirkenden falschen Summe. Gibt es gar keinen Bezug (noch nie
 * ein Kurs, keine passende Merkliste), zählt der Kerzenkurs NICHT als Geld (wie der Stichtag-Export):
 * kein Kurs, der Coin heisst «ungeprüft» ([PortfolioPrices.unchecked]). Vor der Kerze gilt der Kurs
 * eines Merklisten-Paars desselben Coins an einer anderen Spot-Börse ([PortfolioWatchPair]).
 *
 * Coin-Liste für die Suche: Binance-Spot-Paare gegen USDT (exchangeInfo), sonst
 * der Paar-Zwischenspeicher der Börse «Binance», sonst Coinbase-USD-Produkte.
 * Zwischenspeicher 1 Tag (auch über einen Neustart).
 *
 * Hier Zustand und Abruf der Kurse; als Erweiterungsfunktionen in eigenen Dateien: gemerkter Stand
 * (`PortfolioPriceSourceStore.kt`), eingetragene Börsen (`PortfolioPriceSourceExchanges.kt`) und Coin-Liste
 * (`PortfolioPriceSourceCoins.kt`); Ergebnis-Typen in `PortfolioPrices.kt`.
 */
@Singleton
class PortfolioPriceSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
    internal val httpClient: OkHttpClient,
    private val candleDataSource: CandleDataSource,
    val exchangeRepository: ExchangeRepository,
    val watchDao: WatchDao,
    val portfolioDao: PortfolioDao,
    val fxSource: FxRateSource,
    val tokenIdentityStore: TokenIdentityStore,
    /** Live-Kurs USDT/USD: USD- und Fiat-Paare eigener Börsen in USDT ([PortfolioExchangeRoute.toUsdt]). */
    val depegReadings: com.cryptochecker.app.domain.alarm.DepegReadingStore,
) {
    /** Coin → Börse, deren Kurs gerade im Zwischenspeicher steht (sonst Binance). */
    val sourceOf = ConcurrentHashMap<String, String>()

    /** Coin → eingetragene Börse, deren Abfrage zuletzt scheiterte. */
    val failedOf = ConcurrentHashMap<String, String>()

    /** Coin → Binance-Kurs zum Zeitpunkt des Börsenkurses. */
    val binanceOf = ConcurrentHashMap<String, Double>()

    @Volatile
    var mixedCoins: Set<String> = emptySet()

    /** Paarliste einer Börse höchstens alle 6 Std. nachladen (Schlüssel → Zeitpunkt). */
    val pairListAttempts = ConcurrentHashMap<String, Long>()

    /** Coin → (Kurs, Abfragezeit). */
    val cache = ConcurrentHashMap<String, Pair<Double, Long>>()

    /** Coins ohne Kurs, weil nur ein ungeprüfter Kerzenkurs da war (kein Bezug vorhanden). */
    val uncheckedCoins: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Coins mit Binance-Kurs, dessen Token nicht bestätigt ist ([PortfolioPrices.unverified]). */
    val unverifiedCoins: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Coins ohne Binance-Namen, für die keine Quelle einen Kurs führt ([PortfolioPrices.noMarket]). */
    val noMarketCoins: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Coin → Zeitpunkt, an dem Binance ihn einzeln mit 400 ablehnte ([binanceBatch], nur im Arbeitsspeicher). */
    val binanceUnlisted = ConcurrentHashMap<String, Long>()

    /** Coins ohne Kurs, weil die letzte Abfrage scheiterte ([PortfolioPriceIssue.UNAVAILABLE]). */
    val unavailableCoins: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Die letzte Sammelabfrage scheiterte am Netz bzw. war gesperrt (für [PortfolioPriceIssue.of]). */
    @Volatile
    private var batchFailed = false
    private val priceMutex = Mutex()
    val coinMutex = Mutex()

    /**
     * Letzter Stand der Kurse auf dem Gerät ([stored], [persist]) — getrennt vom Zwischenspeicher:
     * [cached] und [prices] (auch Widget und Hintergrund) sehen nur Kurse dieses Prozesses.
     */
    val storeMutex = Mutex()
    val storeFile: File get() = File(context.noBackupFilesDir, STORE_FILE)

    /** Gemerkte Kurse; null = Datei noch nicht gelesen. */
    @Volatile
    var storedEntries: Map<String, StoredPrice>? = null

    /**
     * Zähler von «Portfolio leeren» ([clearStored], unter [storeMutex] erhöht): eine Abfrage, die davor begann, übernimmt
     * ihre Kurse nicht mehr und schreibt die Datei nicht neu ([persist]) — sonst kämen die gelöschten Coins zurück.
     */
    @Volatile
    internal var clearGeneration = 0

    @Volatile
    var coinList: List<String>? = null

    /** Grosse Antworten (exchangeInfo) brauchen etwas mehr Zeit. */
    val bulkClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .callTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** Kurse aus dem Zwischenspeicher, ohne Netz (für den ersten Aufbau). */
    fun cached(coins: Collection<String>): PortfolioPrices = collect(normalized(coins))

    /**
     * Kurse für [coins]; Einträge jünger als 60 s werden nicht neu geholt
     * (ausser mit [force]). Schlägt eine Abfrage fehl, bleibt der letzte
     * bekannte Kurs stehen.
     */
    suspend fun prices(coins: Collection<String>, force: Boolean = false): PortfolioPrices =
        withContext(Dispatchers.IO) {
            val wanted = normalized(coins)
            priceMutex.withLock {
                val generation = clearGeneration
                val now = System.currentTimeMillis()
                // Dazu die Basis gekapselter Coins (Ersatzkurs, [ChainAssets.priceViaBase]); nur abfragbare Kürzel
                val toFetch = ChainAssets.withBases(wanted).filter { coin ->
                    isSymbol(coin) && coin != STABLE &&
                        (force || cache[coin]?.let { now - it.second in 0 until PRICE_TTL_MILLIS } != true)
                }
                if (toFetch.isNotEmpty()) {
                    // Binance-Ticker nur für denselben Token (Namen der Merkliste); ohne Namen nur bei eindeutigem
                    // Kürzel, sonst Merklisten-Paar/Kerzen und ohne anderen Kurs «ungeprüft»; mehrdeutig laut anderen
                    // Paarlisten: Binance-Kurs, aber «Kurs ungeprüft»
                    val split = PortfolioPriceIdentity.binanceSplit(
                        toFetch, watchNamesFor(toFetch), tokenIdentityStore::binanceName, tokenIdentityStore::otherNames,
                    )
                    val batch = binanceBatch(split.use)
                    batchFailed = batch.failed
                    val fetched = batch.prices.toMutableMap()
                    val fromBinance = fetched.keys.toSet()
                    // Nicht bei Binance: zuerst ein Merklisten-Paar an einer anderen Börse, dann die Kerze
                    if (toFetch.any { it !in fetched }) fetched += watchPairPrices(toFetch.filter { it !in fetched })
                    val missing = toFetch.filter { it !in fetched }
                    val unchecked = HashSet<String>()
                    val rejected = HashSet<String>()
                    val unavailable = HashSet<String>()
                    val noPrice = HashSet<String>()
                    if (missing.isNotEmpty()) {
                        // Gekapselter Coin ohne eigenen Bezug: der Kurs der Basis prüft seinen Kerzenkurs (WETH ≈ ETH)
                        val references = candleReferences(missing).toMutableMap()
                        for (coin in missing) {
                            if (coin in references) continue
                            val base = ChainAssets.wrappedBase(coin) ?: continue
                            (fetched[base] ?: cache[base]?.first)?.let { references[coin] = it }
                        }
                        // Höchstens [CANDLE_PARALLEL] Kerzen-Abfragen zugleich (wie HistoricPriceSource), auch bei vielen Coins
                        val limiter = Semaphore(CANDLE_PARALLEL)
                        coroutineScope {
                            missing.map { coin -> async { coin to limiter.withPermit { candleOutcome(coin) } } }.awaitAll()
                        }.forEach { (coin, outcome) ->
                            val price = outcome.first
                            // Kein Kurs: Fehler (Netz/Sperre) von «gibt es nicht» unterscheiden
                            if (price == null) {
                                val issue = PortfolioPriceIssue.of(outcome.second, candleSourcesBlocked(), batchFailed)
                                if (issue == PortfolioPriceIssue.UNAVAILABLE) unavailable += coin else noPrice += coin
                            }
                            val reference = references[coin]
                            when {
                                PricePlausibility.acceptCandle(price, reference) -> fetched[coin] = price!!
                                // Kein Bezug: ungeprüft — zählt nicht als Geld (kein Kurs, «ungeprüft»)
                                PricePlausibility.candleUnchecked(price, reference) -> unchecked += coin
                                price != null -> {
                                    // Gleiches Kürzel, anderer Token: kein neuer Kurs statt einer falschen Summe
                                    rejected += coin
                                    Timber.d("Portfolio: Kerzenkurs %s für %s passt nicht zum Bezug %s", price, coin, reference)
                                }
                            }
                        }
                    }
                    // Binance ohne prüfbare Identität ausgelassen und kein anderer Kurs: «ungeprüft» (nicht abgelehnte); lieferte
                    // auch keine Quelle einen Kurs (nicht gescheitert): «kein Markt»
                    val skipped = PortfolioPriceIssue.nameUnchecked(split.unchecked, rejected, noPrice, unavailable)
                    unchecked += skipped.unchecked
                    // Eingetragene Börse: deren Kurs gilt, sonst bleibt Binance (siehe PortfolioExchangeRoute)
                    applyExchangeRoutes(toFetch, fetched)
                    // «Portfolio leeren» lief während der Abfrage: nichts übernehmen (auch nicht die Börsen-Herkunft)
                    val kept = storeMutex.withLock {
                        if (generation != clearGeneration) {
                            toFetch.forEach { sourceOf.remove(it); failedOf.remove(it); binanceOf.remove(it) }
                            return@withLock false
                        }
                        keep(fetched, toFetch, split.unverified, fromBinance, unavailable, rejected, unchecked, skipped.noMarket)
                        true
                    }
                    // Letzten guten Stand merken (für ohne Netz und nach einem Neustart)
                    if (kept && fetched.isNotEmpty()) persist(generation)
                }
            }
            collect(wanted)
        }

    /** Ergebnis einer Abfrage in den Zwischenspeicher übernehmen ([prices], unter [storeMutex]). */
    private fun keep(
        fetched: Map<String, Double>,
        toFetch: List<String>,
        unverified: Set<String>,
        fromBinance: Set<String>,
        unavailable: Set<String>,
        rejected: Set<String>,
        unchecked: Set<String>,
        noMarket: Set<String>,
    ) {
        val time = System.currentTimeMillis()
        fetched.forEach { (coin, price) ->
            cache[coin] = price to time
            uncheckedCoins -= coin
            unavailableCoins -= coin
            noMarketCoins -= coin
            // Ungeprüft nur, solange der Kurs von Binance stammt (eine eingetragene Börse nennt ihren Token)
            if (coin in unverified && coin in fromBinance && sourceOf[coin] == null) {
                unverifiedCoins += coin
            } else {
                unverifiedCoins -= coin
            }
        }
        // Abfrage gescheitert: nur Coins ohne jeden Kurs heissen «nicht abrufbar»; die übrigen ohne Kurs nicht
        toFetch.filter { it !in fetched }.forEach { coin ->
            if (coin in unavailable) unavailableCoins += coin else unavailableCoins -= coin
            if (coin !in noMarket) noMarketCoins -= coin
            // Gescheitert: «nicht abrufbar» statt eines alten «ungeprüft»
            if (coin in unavailable) uncheckedCoins -= coin
        }
        rejected.forEach { uncheckedCoins -= it }
        // Ungeprüft und auch sonst kein Kurs: kein Wert im Zwischenspeicher, Coin «ungeprüft»
        unchecked.filter { it !in fetched }.forEach { coin ->
            cache.remove(coin)
            uncheckedCoins += coin
        }
        // Kein Binance-Name und keine Quelle mit Kurs: kein Wert, Coin «kein Markt» (zählt nicht als Geld)
        noMarket.filter { it !in fetched }.forEach { coin ->
            cache.remove(coin)
            uncheckedCoins -= coin
            noMarketCoins += coin
        }
    }

    /** Ein einzelner Kurs, z. B. zum Vorbelegen im Erfassen-Blatt. */
    suspend fun price(coin: String): Double? {
        val symbol = PortfolioCalculator.normalizeCoin(coin)
        if (!PortfolioStables.needsQuote(symbol)) return 1.0
        // Stablecoin ohne Marktkurs: kein Wert (gleiche Regel wie Kopf, Verlauf und Widget)
        return PortfolioStables.price(symbol, prices(listOf(symbol)).prices[symbol])
    }

    /** Abfragbare Kürzel; gekapselte Coins ([ChainAssets.WRAPPED], z. B. «BTC.B») auch ohne — Kurs dann über die Basis. */
    fun normalized(coins: Collection<String>): List<String> =
        coins.map { PortfolioCalculator.normalizeCoin(it) }
            .filter { (isSymbol(it) || ChainAssets.wrappedBase(it) != null) && it != STABLE }
            .distinct()

    private fun collect(coins: List<String>): PortfolioPrices {
        // Gekapselte Coins ohne eigenen Kurs: Kurs (und Zeit) der Basis, markiert ([PortfolioPrices.viaBase])
        val via = ChainAssets.priceViaBase(coins, cache.keys)
        val hits = coins.mapNotNull { coin -> (cache[coin] ?: via[coin]?.let { cache[it] })?.let { coin to it } }
        return PortfolioPrices(
            prices = hits.associate { (coin, entry) -> coin to entry.first } + (STABLE to 1.0),
            updatedAt = hits.minOfOrNull { it.second.second } ?: 0L,
            // Ungeprüft = ohne Kurs (zählt nicht als Geld)
            unchecked = coins.filterTo(HashSet()) { it in uncheckedCoins && cache[it] == null && it !in via },
            noMarket = coins.filterTo(HashSet()) { it in noMarketCoins && cache[it] == null && it !in via },
            times = hits.associate { (coin, entry) -> coin to entry.second },
            sources = hits.mapNotNull { (coin, _) -> sourceOf[coin]?.let { coin to it } }.toMap(),
            exchangeFailed = hits.mapNotNull { (coin, _) -> failedOf[coin]?.let { coin to it } }.toMap(),
            // Abfrage gescheitert und kein Kurs im Zwischenspeicher: «Kurs gerade nicht abrufbar»
            unavailable = coins.filterTo(HashSet()) { it in unavailableCoins && cache[it] == null && it !in uncheckedCoins && it !in via },
            mixedExchange = mixedCoins.filterTo(HashSet()) { coin -> hits.any { it.first == coin } },
            binance = hits.mapNotNull { (coin, _) -> binanceOf[coin]?.let { coin to it } }.toMap(),
            viaBase = via,
            // Gekapselte Coins über die Basis erben deren «Kurs ungeprüft»
            unverified = PortfolioPriceFallback.unverified(coins, currentEntries(), via),
        )
    }

    /**
     * Letzter Kerzenkurs und ob die Abfrage scheiterte (Ausnahme) — statt Fehler still als «kein Kurs» zu schlucken
     * ([PortfolioPriceIssue]).
     */
    private suspend fun candleOutcome(coin: String): Pair<Double?, Boolean> = try {
        // Binance nur ohne klaren Namens-Widerspruch zu den Paaren der Merkliste (gleicher Token)
        candleDataSource.candles(coin, STABLE, CandleInterval.H1, 2, identityName = tokenIdentityStore.watchName(coin))
            ?.lastOrNull()?.close?.takeIf { it > 0.0 } to false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.d(e, "Portfolio: Kerzen für %s nicht abrufbar", coin)
        null to true
    }

    /** Alle Quellen der Kerzen-Kette gesperrt oder pausiert (451/403, Backoff): ein fehlender Kurs ist dann kein Beleg. */
    private fun candleSourcesBlocked(): Boolean = CANDLE_HOSTS.all { BlockedSources.isBlocked(it) }

    /** GET mit eigener Zeitgrenze; null bei Fehler. 451/403 sperrt die Quelle vorübergehend. */
    suspend fun get(url: String, host: String, client: OkHttpClient = httpClient): String? = try {
        withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { client.callExchange(url, null) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!BlockedSources.noteFailure(host, e)) Timber.d(e, "Portfolio: Abfrage fehlgeschlagen: %s", url)
        null
    }

    companion object {
        const val STABLE = "USDT"
        const val BINANCE_HOST = "data-api.binance.vision"
        /** Quellen der Kerzen-Kette ([CandleDataSource]) — Binance, Binance.US, Coinbase. */
        val CANDLE_HOSTS = listOf("data-api.binance.vision", "api.binance.com", "api.binance.us", "api.exchange.coinbase.com")
        const val COINBASE_HOST = "api.exchange.coinbase.com"

        /** Gleichzeitige Kerzen-Abfragen für Coins ohne Binance-Kurs (wie [HistoricPriceSource.PARALLEL]). */
        const val CANDLE_PARALLEL = 4
        const val PRICE_TTL_MILLIS = 60_000L
        const val PAIR_LIST_RETRY_MILLIS = 6 * 60 * 60_000L

        /** Von Binance einzeln mit 400 abgelehnte Coins so lange nicht wieder in die Sammelabfrage. */
        const val UNLISTED_RETRY_MILLIS = 6 * 60 * 60_000L
        const val COINS_TTL_MILLIS = 24 * 60 * 60_000L
        const val REQUEST_TIMEOUT_MILLIS = 25_000L
        const val PREFS = "portfolio"
        const val KEY_COINS = "coin_list"
        const val KEY_COINS_AT = "coin_list_at"

        /** Herkunft der gemerkten Coin-Liste: [SOURCE_BINANCE] (exchangeInfo, Paarliste «Binance») oder [SOURCE_OTHER]. */
        const val KEY_COINS_SOURCE = "coin_list_source"
        const val SOURCE_BINANCE = "binance"
        const val SOURCE_OTHER = "other"
        const val STORE_FILE = "portfolio_last_prices.txt"

        fun isSymbol(s: String) = s.length in 1..15 && s.all { it.isLetterOrDigit() }
    }
}
