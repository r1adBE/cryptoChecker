package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import androidx.core.content.edit
import android.content.Context
import com.cryptochecker.app.data.MarketRepository
import com.cryptochecker.app.data.TokenIdentityStore
import com.cryptochecker.app.data.local.WatchDao
import com.cryptochecker.app.data.remote.BlockedSources
import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.app.data.remote.callMarket
import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.portfolio.ChainAssets
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioExchangeRoute
import com.cryptochecker.app.domain.portfolio.PortfolioPriceIdentity
import com.cryptochecker.app.domain.portfolio.PortfolioPriceFallback
import com.cryptochecker.app.domain.portfolio.PortfolioStables
import com.cryptochecker.app.domain.portfolio.PortfolioWatchPair
import com.cryptochecker.app.domain.portfolio.PricePlausibility
import com.cryptochecker.app.domain.portfolio.StoredPrice
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.FuturesContractType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Aktuelle USDT-Kurse je Coin und der Zeitpunkt der ältesten verwendeten Abfrage. */
data class PortfolioPrices(
    val prices: Map<String, Double> = emptyMap(),
    /** 0 = noch keine Kurse. */
    val updatedAt: Long = 0,
    /**
     * Coins OHNE Kurs, weil nur die letzte Stundenkerze einen hatte und er mangels unabhängigem Bezug
     * nicht geprüft werden konnte ([PricePlausibility.candleUnchecked]): Sie stehen nicht in [prices],
     * zählen also nirgends als Geld; die Zeile nennt sie «ungeprüft».
     */
    val unchecked: Set<String> = emptySet(),
    /** Abfragezeit je Coin (Epoch-ms) — für die Frische je Coin ([PortfolioPriceFallback.freshShare]). */
    val times: Map<String, Long> = emptyMap(),
    /** Coin → Börse, deren Kurs gilt (eingetragene Börse, [PortfolioExchangeRoute]); fehlt = Binance. */
    val sources: Map<String, String> = emptyMap(),
    /** Coin → eingetragene Börse, deren Kurs diesmal nicht ging (dann Binance). */
    val exchangeFailed: Map<String, String> = emptyMap(),
    /** Coins mit mehreren eingetragenen Börsen — bewertet mit Binance. */
    val mixedExchange: Set<String> = emptySet(),
    /** Binance-Kurs der Coins mit eigener Börse — zum Skalieren der Tageskurse ([PortfolioExchangeRoute.scaleCloses]). */
    val binance: Map<String, Double> = emptyMap(),
    /**
     * Coins OHNE Kurs, weil die Abfrage gerade scheiterte (Netz, Sperre/Backoff der Quellen) — nicht, weil es keinen Kurs
     * gibt ([PortfolioPriceIssue]). Zeile: «Kurs gerade nicht abrufbar» statt «kein Kurs». Mit gemerktem Kurs fehlt der
     * Coin hier (er zeigt den letzten Stand, veraltet).
     */
    val unavailable: Set<String> = emptySet(),
    /**
     * Gekapselte/gebrückte Coins ohne eigenen Börsenkurs, bewertet mit dem Kurs ihrer Basis ([ChainAssets.priceViaBase]):
     * Coin → Basis. Die Zeile zeigt dann «≈ BTC-Kurs (gebrückt/verpackt)» — nie als eigener Kurs ausgegeben.
     */
    val viaBase: Map<String, String> = emptyMap(),
)

/** Warum ein Coin keinen Kurs hat: [NO_PRICE] = keine Quelle führt ihn; [UNAVAILABLE] = Abfrage gerade gescheitert. */
enum class PortfolioPriceIssue {
    NO_PRICE, UNAVAILABLE;

    companion object {
        /**
         * Kerzen-Kette ohne Kurs für einen Coin: [UNAVAILABLE], wenn die Abfrage scheiterte ([failed]: Ausnahme), alle
         * Kerzen-Quellen gesperrt/pausiert sind ([allSourcesBlocked]) oder schon die Sammelabfrage am Netz scheiterte
         * ([networkFailed]) — dann ist «kein Kurs» nicht belegt. Sonst [NO_PRICE].
         */
        fun of(failed: Boolean, allSourcesBlocked: Boolean, networkFailed: Boolean): PortfolioPriceIssue =
            if (failed || allSourcesBlocked || networkFailed) UNAVAILABLE else NO_PRICE
    }
}

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
 */
@Singleton
class PortfolioPriceSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val candleDataSource: CandleDataSource,
    private val marketRepository: MarketRepository,
    private val watchDao: WatchDao,
    private val portfolioDao: PortfolioDao,
    private val fxSource: FxRateSource,
    private val tokenIdentityStore: TokenIdentityStore,
    /** Live-Kurs USDT/USD: USD- und Fiat-Paare eigener Börsen in USDT ([PortfolioExchangeRoute.toUsdt]). */
    private val depegReadings: com.cryptochecker.app.domain.alarm.DepegReadingStore,
) {
    /** Coin → Börse, deren Kurs gerade im Zwischenspeicher steht (sonst Binance). */
    private val sourceOf = ConcurrentHashMap<String, String>()

    /** Coin → eingetragene Börse, deren Abfrage zuletzt scheiterte. */
    private val failedOf = ConcurrentHashMap<String, String>()

    /** Coin → Binance-Kurs zum Zeitpunkt des Börsenkurses. */
    private val binanceOf = ConcurrentHashMap<String, Double>()

    @Volatile
    private var mixedCoins: Set<String> = emptySet()

    /** Paarliste einer Börse höchstens alle 6 Std. nachladen (Schlüssel → Zeitpunkt). */
    private val pairListAttempts = ConcurrentHashMap<String, Long>()

    /** Coin → (Kurs, Abfragezeit). */
    private val cache = ConcurrentHashMap<String, Pair<Double, Long>>()

    /** Coins ohne Kurs, weil nur ein ungeprüfter Kerzenkurs da war (kein Bezug vorhanden). */
    private val uncheckedCoins: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Coins ohne Kurs, weil die letzte Abfrage scheiterte ([PortfolioPriceIssue.UNAVAILABLE]). */
    private val unavailableCoins: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Die letzte Sammelabfrage scheiterte am Netz bzw. war gesperrt (für [PortfolioPriceIssue.of]). */
    @Volatile
    private var batchFailed = false
    private val priceMutex = Mutex()
    private val coinMutex = Mutex()

    /**
     * Letzter Stand der Kurse auf dem Gerät ([stored], [persist]) — getrennt vom Zwischenspeicher:
     * [cached] und [prices] (auch Widget und Hintergrund) sehen nur Kurse dieses Prozesses, wie bisher.
     */
    private val storeMutex = Mutex()
    private val storeFile: File get() = File(context.noBackupFilesDir, STORE_FILE)

    /** Gemerkte Kurse; null = Datei noch nicht gelesen. */
    @Volatile
    private var storedEntries: Map<String, StoredPrice>? = null

    @Volatile
    private var coinList: List<String>? = null

    /** Grosse Antworten (exchangeInfo) brauchen etwas mehr Zeit. */
    private val bulkClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .callTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** Kurse aus dem Zwischenspeicher, ohne Netz (für den ersten Aufbau). */
    fun cached(coins: Collection<String>): PortfolioPrices = collect(normalized(coins))

    /**
     * Wie [cached], ergänzt um den letzten gemerkten Stand auf dem Gerät (einmal je Prozess
     * gelesen; je Coin gewinnt der neuere Kurs) — nur für die Anzeige im Portfolio-Tab nach einem
     * Neustart oder ohne Netz: lieber der letzte Wert mit «Stand …» als «0.00». `updatedAt` ist
     * dann alt; ob der Stand veraltet ist, entscheidet [PortfolioPriceFallback.state].
     */
    suspend fun stored(coins: Collection<String>): PortfolioPrices = withContext(Dispatchers.IO) {
        val merged = PortfolioPriceFallback.merge(currentEntries(), loadStored())
        // Gekapselte Coins ohne eigenen Kurs: Kurs der Basis, markiert ([PortfolioPrices.viaBase])
        val via = ChainAssets.priceViaBase(normalized(coins), merged.keys)
        val hits = normalized(coins).mapNotNull { coin -> (merged[coin] ?: via[coin]?.let { merged[it] })?.let { coin to it } }
        PortfolioPrices(
            prices = hits.associate { (coin, entry) -> coin to entry.price } + (STABLE to 1.0),
            updatedAt = hits.minOfOrNull { it.second.at } ?: 0L,
            unchecked = normalized(coins).filterTo(HashSet()) { it in uncheckedCoins && it !in merged && it !in via },
            unavailable = normalized(coins).filterTo(HashSet()) { it in unavailableCoins && it !in merged && it !in uncheckedCoins && it !in via },
            viaBase = via,
            times = hits.associate { (coin, entry) -> coin to entry.at },
            sources = hits.mapNotNull { (coin, _) -> sourceOf[coin]?.let { coin to it } }.toMap(),
            exchangeFailed = hits.mapNotNull { (coin, _) -> failedOf[coin]?.let { coin to it } }.toMap(),
            mixedExchange = mixedCoins.filterTo(HashSet()) { coin -> hits.any { it.first == coin } },
            binance = hits.mapNotNull { (coin, _) -> binanceOf[coin]?.let { coin to it } }.toMap(),
        )
    }

    /** «Portfolio leeren»: gemerkte Kurse (nennen die gehaltenen Coins) und Zwischenspeicher weg. */
    suspend fun clearStored() = withContext(Dispatchers.IO) {
        storeMutex.withLock {
            cache.clear()
            uncheckedCoins.clear()
            runCatchingCancellable { storeFile.delete() }
            storedEntries = emptyMap()
        }
    }

    /** Gemerkter Stand (einmal je Prozess aus der Datei gelesen). */
    private suspend fun loadStored(): Map<String, StoredPrice> {
        storedEntries?.let { return it }
        return storeMutex.withLock {
            storedEntries ?: run {
                val text = runCatchingCancellable { if (storeFile.isFile) storeFile.readText() else null }
                    .onFailure { Timber.d(it, "Portfolio: gemerkte Kurse unlesbar") }
                    .getOrNull()
                PortfolioPriceFallback.prune(PortfolioPriceFallback.decode(text), System.currentTimeMillis())
                    .also { storedEntries = it }
            }
        }
    }

    /**
     * Nach jedem erfolgreichen Abruf: gemerkter Stand und Zwischenspeicher (je Coin der neuere) als
     * letzten Stand schreiben. Datei in `noBackupFilesDir` — nie in einer Sicherung (wie die
     * Momentaufnahme des Portfolio-Widgets), denn sie nennt die gehaltenen Coins. Über eine
     * Hilfsdatei und Umbenennen geschrieben.
     */
    private suspend fun persist() {
        val stored = loadStored()
        storeMutex.withLock {
            val entries = PortfolioPriceFallback.prune(
                PortfolioPriceFallback.merge(currentEntries(), storedEntries ?: stored),
                System.currentTimeMillis(),
            )
            storedEntries = entries
            runCatchingCancellable {
                val tmp = File(storeFile.parentFile, "$STORE_FILE.tmp")
                tmp.writeText(PortfolioPriceFallback.encode(entries))
                if (!tmp.renameTo(storeFile)) {
                    storeFile.delete()
                    tmp.renameTo(storeFile)
                }
            }.onFailure { Timber.d(it, "Portfolio: Kurse nicht gemerkt") }
        }
    }

    private fun currentEntries(): Map<String, StoredPrice> =
        cache.entries.associate { (coin, entry) -> coin to StoredPrice(entry.first, entry.second) }

    /**
     * Kurse für [coins]; Einträge jünger als 60 s werden nicht neu geholt
     * (ausser mit [force]). Schlägt eine Abfrage fehl, bleibt der letzte
     * bekannte Kurs stehen.
     */
    suspend fun prices(coins: Collection<String>, force: Boolean = false): PortfolioPrices =
        withContext(Dispatchers.IO) {
            val wanted = normalized(coins)
            priceMutex.withLock {
                val now = System.currentTimeMillis()
                // Dazu die Basis gekapselter Coins (Ersatzkurs, [ChainAssets.priceViaBase]); nur abfragbare Kürzel
                val toFetch = ChainAssets.withBases(wanted).filter { coin ->
                    isSymbol(coin) && coin != STABLE &&
                        (force || cache[coin]?.let { now - it.second in 0 until PRICE_TTL_MILLIS } != true)
                }
                if (toFetch.isNotEmpty()) {
                    // Binance-Ticker nur für denselben Token (Namen der Merkliste, K-8); sonst Merklisten-Paar/Kerzen
                    val binanceCoins = PortfolioPriceIdentity.binanceTickerCoins(
                        toFetch, watchNamesFor(toFetch), tokenIdentityStore::binanceName,
                    )
                    val fetched = binanceBatch(binanceCoins).toMutableMap()
                    // Nicht bei Binance: zuerst ein Merklisten-Paar an einer anderen Börse, dann die Kerze
                    if (toFetch.any { it !in fetched }) fetched += watchPairPrices(toFetch.filter { it !in fetched })
                    val missing = toFetch.filter { it !in fetched }
                    val unchecked = HashSet<String>()
                    val rejected = HashSet<String>()
                    val unavailable = HashSet<String>()
                    if (missing.isNotEmpty()) {
                        // Gekapselter Coin ohne eigenen Bezug: der Kurs der Basis prüft seinen Kerzenkurs (WETH ≈ ETH)
                        val references = candleReferences(missing).toMutableMap()
                        for (coin in missing) {
                            if (coin in references) continue
                            val base = ChainAssets.wrappedBase(coin) ?: continue
                            (fetched[base] ?: cache[base]?.first)?.let { references[coin] = it }
                        }
                        coroutineScope {
                            missing.map { coin -> async { coin to candleOutcome(coin) } }.awaitAll()
                        }.forEach { (coin, outcome) ->
                            val price = outcome.first
                            // Kein Kurs: Fehler (Netz/Sperre) von «gibt es nicht» unterscheiden
                            if (price == null) {
                                val issue = PortfolioPriceIssue.of(outcome.second, candleSourcesBlocked(), batchFailed)
                                if (issue == PortfolioPriceIssue.UNAVAILABLE) unavailable += coin
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
                    // Eingetragene Börse: deren Kurs gilt, sonst bleibt Binance (siehe PortfolioExchangeRoute)
                    applyExchangeRoutes(toFetch, fetched)
                    val time = System.currentTimeMillis()
                    fetched.forEach { (coin, price) ->
                        cache[coin] = price to time
                        uncheckedCoins -= coin
                        unavailableCoins -= coin
                    }
                    // Abfrage gescheitert: nur Coins ohne jeden Kurs heissen «nicht abrufbar»; die übrigen ohne Kurs nicht
                    toFetch.filter { it !in fetched }.forEach { coin ->
                        if (coin in unavailable) unavailableCoins += coin else unavailableCoins -= coin
                    }
                    rejected.forEach { uncheckedCoins -= it }
                    // Ungeprüft und auch sonst kein Kurs: kein Wert im Zwischenspeicher, Coin «ungeprüft»
                    unchecked.filter { it !in fetched }.forEach { coin ->
                        cache.remove(coin)
                        uncheckedCoins += coin
                    }
                    // Letzten guten Stand merken (für ohne Netz und nach einem Neustart)
                    if (fetched.isNotEmpty()) persist()
                }
            }
            collect(wanted)
        }

    /** Ein einzelner Kurs, z. B. zum Vorbelegen im Erfassen-Blatt. */
    suspend fun price(coin: String): Double? {
        val symbol = PortfolioCalculator.normalizeCoin(coin)
        if (!PortfolioStables.needsQuote(symbol)) return 1.0
        // Stablecoin ohne Marktkurs: kein Wert (gleiche Regel wie Kopf, Verlauf und Widget)
        return PortfolioStables.price(symbol, prices(listOf(symbol)).prices[symbol])
    }

    /** Abfragbare Kürzel; gekapselte Coins ([ChainAssets.WRAPPED], z. B. «BTC.B») auch ohne — Kurs dann über die Basis. */
    private fun normalized(coins: Collection<String>): List<String> =
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
            times = hits.associate { (coin, entry) -> coin to entry.second },
            sources = hits.mapNotNull { (coin, _) -> sourceOf[coin]?.let { coin to it } }.toMap(),
            exchangeFailed = hits.mapNotNull { (coin, _) -> failedOf[coin]?.let { coin to it } }.toMap(),
            // Abfrage gescheitert und kein Kurs im Zwischenspeicher: «Kurs gerade nicht abrufbar»
            unavailable = coins.filterTo(HashSet()) { it in unavailableCoins && cache[it] == null && it !in uncheckedCoins && it !in via },
            mixedExchange = mixedCoins.filterTo(HashSet()) { coin -> hits.any { it.first == coin } },
            binance = hits.mapNotNull { (coin, _) -> binanceOf[coin]?.let { coin to it } }.toMap(),
            viaBase = via,
        )
    }

    /**
     * Unabhängige Bezüge für Kerzenkurse ([PricePlausibility.candleReference]): Kurse aktiver
     * Spot-Paare der Merkliste mit demselben Coin gegen einen USD-Stablecoin (höchstens
     * [PortfolioPriceFallback.MAX_AGE_MILLIS] alt), sonst der letzte gute Kurs (Zwischenspeicher
     * ohne ungeprüfte Kerzenkurse, dazu der gemerkte Stand). Coins ohne Bezug fehlen.
     */
    private suspend fun candleReferences(coins: List<String>): Map<String, Double> {
        val checked = currentEntries().filterKeys { it !in uncheckedCoins }
        val stored = PortfolioPriceFallback.merge(checked, loadStored())
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
    private suspend fun watchPairPrices(coins: List<String>): Map<String, Double> {
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
                marketKey = w.marketKey,
                base = w.baseAsset,
                quote = w.quoteAsset,
                pairId = w.pairId,
                spot = w.contractType == FuturesContractType.NONE,
                disabled = w.disabled,
                lastUpdate = w.lastUpdate,
                // Voller Name aus der Paarliste der Börse (gleicher Token, K-8)
                name = tokenIdentityStore.pairName(w.marketKey, w.baseAsset),
            )
        }
        val names = watches.associate { it.marketKey to it.marketName }
        val out = ConcurrentHashMap<String, Double>()
        coroutineScope {
            coins.mapNotNull { coin -> PortfolioWatchPair.pick(coin, candidates)?.let { coin to it } }
                .map { (coin, pick) ->
                    async {
                        val price = try {
                            watchPairPrice(pick, names[pick.marketKey] ?: pick.marketKey)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.d(e, "Portfolio: Kurs von %s für %s nicht verfügbar", pick.marketKey, coin)
                            null
                        }
                        if (price != null) out[coin] = price
                    }
                }.awaitAll()
        }
        return out
    }

    /** Letzter Kurs des Merklisten-Paars [pick] in USDT; null = keine Antwort oder kein Devisenkurs. */
    private suspend fun watchPairPrice(pick: PortfolioWatchPair.Candidate, marketName: String): Double? {
        val market = MarketInfo(key = pick.marketKey, name = marketName)
        val info = MarketPair(pick.base, pick.quote, pick.pairId)
        val result = withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { marketRepository.getMarketTicker(market, info) } ?: return null
        if (result.error != null) return null
        val quote = pick.quote.uppercase()
        val rate = if (quote == "USDT" || quote == "USD") null else fxSource.usdTo(quote)
        // USDT zum Marktkurs: USD-/Fiat-Kurs durch USDT/USD (ohne frische Messung 1, Karte markiert die Näherung)
        return PortfolioExchangeRoute.toUsdt(result.ticker.last, quote, depegReadings.liveUsdtUsd() ?: 1.0) { rate }
    }

    /** Name je Coin laut den Paaren der Merkliste ([PortfolioPriceIdentity.watchNames]); Fehler: keine Namen. */
    private suspend fun watchNamesFor(coins: List<String>): Map<String, String?> {
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
            watches.map { PortfolioPriceIdentity.WatchName(it.baseAsset, tokenIdentityStore.pairName(it.marketKey, it.baseAsset), it.disabled) },
        )
    }

    /** Sammelabfrage; Binance lehnt die ganze Anfrage ab, wenn ein Symbol unbekannt ist. */
    private suspend fun binanceBatch(coins: List<String>): Map<String, Double> {
        batchFailed = false
        if (BlockedSources.isBlocked(BINANCE_HOST)) {
            batchFailed = true
            return emptyMap()
        }
        // Bekannte Coins zuerst — unbekannte gehen direkt über die Kerzen-Kette.
        val known = coinList?.toHashSet()
        val candidates = if (known.isNullOrEmpty()) coins else coins.filter { it in known }
        val result = HashMap<String, Double>()
        for (chunk in candidates.chunked(MAX_SYMBOLS)) {
            val bySymbol = chunk.associateBy { it + STABLE }
            val symbols = bySymbol.keys.joinToString(",", "[", "]") { "\"$it\"" }
            val url = "https://$BINANCE_HOST/api/v3/ticker/price?symbols=" + URLEncoder.encode(symbols, "UTF-8")
            val body = get(url, BINANCE_HOST)
            if (body == null) {
                batchFailed = true
                continue
            }
            runCatchingCancellable {
                val array = JSONArray(body)
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val coin = bySymbol[o.optString("symbol")] ?: continue
                    val price = o.optString("price").toDoubleOrNull()
                    if (price != null && price > 0.0) result[coin] = price
                }
            }.onFailure { Timber.d(it, "Portfolio: Kurse nicht lesbar") }
        }
        return result
    }

    /**
     * Kurs der eingetragenen Börse für [coins] ([PortfolioExchangeRoute]): Börse aus den Transaktionen, Paar
     * aus der Paarliste der Börse (fehlt sie, höchstens alle 6 Std. einmal laden), Kurs über den Adapter der
     * Börse, umgerechnet in USDT. Ersetzt in [fetched] den Binance-Kurs; scheitert es, bleibt Binance und der
     * Coin steht in [failedOf]. Abfragen gehen nur an die Börse, die der Nutzer selbst eingetragen hat.
     */
    private suspend fun applyExchangeRoutes(coins: List<String>, fetched: MutableMap<String, Double>) {
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
        val markets = runCatchingCancellable { marketRepository.getMarketList() }.getOrNull()
            ?.associate { it.key to it.name }.orEmpty()
        coroutineScope {
            routed.map { (coin, name) ->
                async {
                    val market = PortfolioExchangeRoute.market(name, markets)
                    if (market == null) {
                        // Binance oder freier Text: Binance, ohne Hinweis
                        sourceOf.remove(coin); failedOf.remove(coin); binanceOf.remove(coin)
                        return@async
                    }
                    val price = try {
                        exchangePrice(coin, MarketInfo(key = market.first, name = market.second))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.d(e, "Portfolio: Kurs von %s für %s nicht verfügbar", market.second, coin)
                        null
                    }
                    if (price != null) {
                        fetched[coin]?.let { binanceOf[coin] = it } ?: binanceOf.remove(coin)
                        fetched[coin] = price
                        sourceOf[coin] = market.second
                        failedOf.remove(coin)
                    } else {
                        sourceOf.remove(coin)
                        binanceOf.remove(coin)
                        failedOf[coin] = market.second
                    }
                }
            }.awaitAll()
        }
    }

    /** Namen der Spot-Börsen der App (alphabetisch) für das Feld «Börse» einer Transaktion. */
    suspend fun spotMarketNames(): List<String> =
        marketRepository.getMarketList()
            .filter { PortfolioExchangeRoute.isSpot(it.key) }
            .map { it.name }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

    /** Letzter Kurs von [coin] an [market] in USDT; null = kein Paar, keine Antwort oder kein Devisenkurs. */
    private suspend fun exchangePrice(coin: String, market: MarketInfo): Double? {
        var pairs = spotPairs(market)
        if (pairs.isEmpty() && marketRepository.isMarketSupportsUpdatePairs(market)) {
            val now = System.currentTimeMillis()
            val last = pairListAttempts[market.key] ?: 0L
            if (now - last !in 0 until PAIR_LIST_RETRY_MILLIS) {
                pairListAttempts[market.key] = now
                runCatchingCancellable { marketRepository.updateMarketCurrencyPairs(market) }
                pairs = spotPairs(market)
            }
        }
        val pick = PortfolioExchangeRoute.pickPair(coin, pairs.map { PortfolioExchangeRoute.Pair3(it.base, it.quote, it.pairId) })
            ?: return null
        val info = pairs.first { it.base == pick.base && it.quote == pick.quote && it.pairId == pick.pairId }
        val result = withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { marketRepository.getMarketTicker(market, info) } ?: return null
        if (result.error != null) return null
        val last = result.ticker.last
        val quote = pick.quote.uppercase()
        val rate = if (quote == "USDT" || quote == "USD") null else fxSource.usdTo(quote)
        return PortfolioExchangeRoute.toUsdt(last, quote, depegReadings.liveUsdtUsd() ?: 1.0) { rate }
    }

    private suspend fun spotPairs(market: MarketInfo) =
        marketRepository.getMarketCurrencyPairsInfo(market).pairs.filter { it.contractType == FuturesContractType.NONE }

    /**
     * Letzter Kerzenkurs und ob die Abfrage scheiterte (Ausnahme) — statt Fehler still als «kein Kurs» zu schlucken
     * ([PortfolioPriceIssue]).
     */
    private suspend fun candleOutcome(coin: String): Pair<Double?, Boolean> = try {
        // Binance nur ohne klaren Namens-Widerspruch zu den Paaren der Merkliste (gleicher Token, K-8)
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

    // ---------------- Coin-Liste ----------------

    /** Alle wählbaren Coins (alphabetisch); leer, wenn keine Quelle antwortet. */
    suspend fun coins(): List<String> = withContext(Dispatchers.IO) {
        coinMutex.withLock {
            coinList?.let { list ->
                if (System.currentTimeMillis() - prefs.getLong(KEY_COINS_AT, 0L) in 0 until COINS_TTL_MILLIS) {
                    return@withLock list
                }
            }
            val stored = prefs.getString(KEY_COINS, null)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
            val storedAt = prefs.getLong(KEY_COINS_AT, 0L)
            if (stored.isNotEmpty() && System.currentTimeMillis() - storedAt in 0 until COINS_TTL_MILLIS) {
                coinList = stored
                return@withLock stored
            }

            val fresh = binanceCoins() ?: pairCacheCoins() ?: coinbaseCoins()
            if (!fresh.isNullOrEmpty()) {
                val list = (fresh + STABLE).distinct().sorted()
                prefs.edit {
                    putString(KEY_COINS, list.joinToString(","))
                    putLong(KEY_COINS_AT, System.currentTimeMillis())
                }
                coinList = list
                list
            } else {
                // Lieber eine ältere Liste als gar keine
                stored.also { if (it.isNotEmpty()) coinList = it }
            }
        }
    }

    private suspend fun binanceCoins(): List<String>? {
        val url = "https://$BINANCE_HOST/api/v3/exchangeInfo?permissions=SPOT&showPermissionSets=false"
        val body = get(url, BINANCE_HOST, bulkClient) ?: return null
        return runCatchingCancellable {
            val symbols = JSONObject(body).getJSONArray("symbols")
            (0 until symbols.length()).mapNotNull { i ->
                val s = symbols.getJSONObject(i)
                s.optString("baseAsset").takeIf {
                    s.optString("quoteAsset") == STABLE && s.optString("status") == "TRADING"
                }
            }.map { it.uppercase() }.filter { isSymbol(it) }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Paarliste der Börse «Binance» aus der App (lokal gespeichert oder mitgeliefert). */
    private suspend fun pairCacheCoins(): List<String>? = runCatchingCancellable {
        marketRepository.getMarketCurrencyPairsInfo(MarketInfo(key = "Binance", name = "Binance"))
            .pairs
            .filter { it.quote == STABLE && it.contractType == FuturesContractType.NONE }
            .map { it.base.uppercase() }
            .filter { isSymbol(it) }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private suspend fun coinbaseCoins(): List<String>? {
        val body = get("https://api.exchange.coinbase.com/products", COINBASE_HOST, bulkClient) ?: return null
        return runCatchingCancellable {
            val array = JSONArray(body)
            (0 until array.length()).mapNotNull { i ->
                val p = array.getJSONObject(i)
                p.optString("base_currency").takeIf {
                    p.optString("quote_currency") == "USD" && !p.optBoolean("trading_disabled", false)
                }
            }.map { it.uppercase() }.filter { isSymbol(it) }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** GET mit eigener Zeitgrenze; null bei Fehler. 451/403 sperrt die Quelle vorübergehend. */
    private suspend fun get(url: String, host: String, client: OkHttpClient = httpClient): String? = try {
        withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { client.callMarket(url, null) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!BlockedSources.noteFailure(host, e)) Timber.d(e, "Portfolio: Abfrage fehlgeschlagen: %s", url)
        null
    }

    private companion object {
        const val STABLE = "USDT"
        const val BINANCE_HOST = "data-api.binance.vision"
        /** Quellen der Kerzen-Kette ([CandleDataSource]) — Binance, Binance.US, Coinbase. */
        val CANDLE_HOSTS = listOf("data-api.binance.vision", "api.binance.com", "api.binance.us", "api.exchange.coinbase.com")
        const val COINBASE_HOST = "api.exchange.coinbase.com"
        const val MAX_SYMBOLS = 100
        const val PRICE_TTL_MILLIS = 60_000L
        const val PAIR_LIST_RETRY_MILLIS = 6 * 60 * 60_000L
        const val COINS_TTL_MILLIS = 24 * 60 * 60_000L
        const val REQUEST_TIMEOUT_MILLIS = 25_000L
        const val PREFS = "portfolio"
        const val KEY_COINS = "coin_list"
        const val KEY_COINS_AT = "coin_list_at"
        const val STORE_FILE = "portfolio_last_prices.txt"

        fun isSymbol(s: String) = s.length in 1..15 && s.all { it.isLetterOrDigit() }
    }
}
