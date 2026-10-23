package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.MEMORY_CACHE_ENTRIES
import com.cryptochecker.app.data.BoundedCache
import com.cryptochecker.app.data.MarketRepository
import com.cryptochecker.app.data.TokenIdentityStore
import com.cryptochecker.app.data.remote.BlockedSources
import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.data.remote.callMarket
import com.cryptochecker.app.data.remote.candles.CandlePair
import com.cryptochecker.app.data.remote.candles.OwnCandleDataSource
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.activity.HourCandle
import com.cryptochecker.app.domain.convert.CurrencyConversion
import com.cryptochecker.app.domain.market.DataFreshness
import com.cryptochecker.app.domain.portfolio.CutoffCloses
import com.cryptochecker.app.domain.portfolio.CutoffExport
import com.cryptochecker.app.domain.portfolio.HistoricCloseChain
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryFx
import com.cryptochecker.app.domain.portfolio.PortfolioStables
import com.cryptochecker.app.domain.portfolio.PricePlausibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Devisenkurs eines Stichtags mit dem tatsächlichen Kursdatum (Wochenende → Freitag). */
data class HistoricFx(val rate: Double, val date: String?)

/**
 * Historische Kurse für den Stichtag-Export:
 *  - Tagesschlusskurs (Tageskerze UTC) in USDT, gleiche Ausweich-Kette wie
 *    [CandleDataSource]: data-api.binance.vision → api.binance.com → fapi.binance.com
 *    → api.binance.us (USDT, sonst USD) → Coinbase (USD, sonst USDC).
 *    Es zählt nur die Kerze, die genau an diesem Tag beginnt (Binance liefert sonst
 *    die nächste, wenn der Coin erst später gelistet wurde).
 *    Identitätsprüfung ([PricePlausibility.acceptSourceClose]): jede Quelle (auch Binance-Spot)
 *    zählt nur, wenn sie den Coin heute als denselben Token führt wie das Portfolio (ihr jüngster
 *    Schluss gegen den aktuellen Kurs, 1/3…3; Ausweich-Quellen ±25 %; eine Abfrage mehr, nur bei
 *    einem Treffer dort); sonst ist die nächste Quelle dran. Ohne gültigen aktuellen Kurs keine
 *    Abfrage, der Coin ist «ungeprüft» ([CutoffCloses.unchecked]), ebenso wenn nur nicht passende
 *    Kurse gefunden wurden.
 *  - Devisenkurs USD → Zielwährung dieses Tags (EZB über Frankfurter) und die Tageskurse eines
 *    Zeitraums für den Wertverlauf ([usdToSeries], gleicher Anbieter, 6 h im Speicher).
 *  - Ausweich-Kette ([HistoricCloseChain], HistoricPriceSourceChain.kt): eigene Börse des Coins zuerst (Tageskerzen
 *    dieser Börse, Identitätsprüfung 1/3…3 wie Binance-Spot), nach Binance und Coinbase noch Kraken → OKX → Bybit
 *    (eigene Kerzen dieser Börsen, ±25 % und Name) — nur Hosts, die die App ohnehin anfragt. Der Kurs trägt seine
 *    Quelle ([CloseResult.source]).
 * Stablecoins nach [PortfolioStables]: USDT = 1 ohne Abfrage, andere Stablecoins werden wie
 * jeder Coin abgefragt (fehlt der Kurs, setzt [CutoffExport.rows] 1). Kurse ohne eigenen Zwischenspeicher (die Kerzen der
 * Ausweich-Börsen hält [OwnCandleDataSource] kurz, damit viele Tage desselben Coins eine Abfrage teilen).
 */
@Singleton
class HistoricPriceSource @Inject constructor(
    private val httpClient: OkHttpClient,
    internal val tokenIdentityStore: TokenIdentityStore,
    internal val ownCandles: OwnCandleDataSource,
    internal val portfolioDao: PortfolioDao,
    internal val marketRepository: MarketRepository,
) {
    /**
     * Coin → (eigene Börse oder null, Lesezeit): die Steuer-Vorlage fragt je (Coin, Tag) einzeln ([ownRoutes]).
     * Begrenzt (sonst blieb jeder je abgefragte Coin bis zum Prozessende).
     */
    internal val routeMemo = BoundedCache<String, Pair<CandlePair?, Long>>(MEMORY_CACHE_ENTRIES)

    /**
     * Schlusskurse der Coins am Tag [date]; Coins ohne bestätigten Kurs fehlen in den Preisen.
     * [current]: Bezugskurse in USDT für die Identitätsprüfung ([CutoffExport.referencePrices]);
     * fehlt einer (auch: Laden der aktuellen Kurse ganz gescheitert), ist der Coin «ungeprüft».
     */
    suspend fun dailyClosesUsdt(
        coins: Collection<String>,
        date: LocalDate,
        current: Map<String, Double>,
    ): CutoffCloses =
        withContext(Dispatchers.IO) {
            val symbols = coins.map { PortfolioCalculator.normalizeCoin(it) }.filter { isAsset(it) }.distinct()
            // Eingetragene Börsen einmal für alle Coins (ohne Netz)
            val own = ownRoutes(symbols)
            val limiter = Semaphore(PARALLEL)
            val results = coroutineScope {
                symbols.map { coin ->
                    async { coin to limiter.withPermit { closeUsdt(coin, date, current[coin], own[coin]) } }
                }.awaitAll()
            }
            CutoffCloses(
                // Kerzenkurse kommen als Double: exakt über die kürzeste Darstellung (= Text der Quelle)
                prices = results.mapNotNull { (coin, r) -> PortfolioDecimal.of(r.price)?.let { coin to it } }.toMap(),
                unchecked = results.filter { it.second.unchecked }.map { it.first }.toSet(),
            )
        }

    /**
     * Ergebnis je Coin: bestätigter Kurs, sonst [unchecked] = Kurs nicht als derselbe Token bestätigt. [source] =
     * Anbieter des Kurses (z. B. «Binance», «Kraken»; null ohne Kurs bzw. USDT = 1).
     */
    data class CloseResult(val price: Double?, val unchecked: Boolean = false, val source: String? = null)

    /**
     * Schlusskurs eines Coins am Tag [date] (UTC-Tageskerze). USDT = 1. [current] = aktueller
     * Portfolio-Kurs (Bezug der Identitätsprüfung); ohne gültigen Bezug keine Abfrage, «ungeprüft».
     */
    suspend fun dailyCloseUsdt(coin: String, date: LocalDate, current: Double?): CloseResult {
        val b = PortfolioCalculator.normalizeCoin(coin)
        if (!PortfolioStables.needsQuote(b) || !isAsset(b)) return closeUsdt(b, date, current, null)
        return closeUsdt(b, date, current, ownRoutes(listOf(b))[b])
    }

    /** Wie [dailyCloseUsdt], mit schon gelesener eigener Börse [own] (null = keine bzw. Binance). */
    private suspend fun closeUsdt(coin: String, date: LocalDate, current: Double?, own: CandlePair?): CloseResult {
        val b = PortfolioCalculator.normalizeCoin(coin)
        if (!PortfolioStables.needsQuote(b)) return CloseResult(1.0)
        if (!isAsset(b)) return CloseResult(null)
        // Streng: ohne Bezug ist der gleiche Token nicht bestätigt — auch nicht über Ausweich-Quellen
        val reference = current?.takeIf { it > 0.0 && it.isFinite() } ?: return CloseResult(null, unchecked = true)
        val start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val ownName = tokenIdentityStore.watchName(b)
        val resolved = HistoricCloseChain.resolve(HistoricCloseChain.order(own?.marketKey)) { source ->
            when {
                own != null && source == own.marketKey -> ownOutcome(own, b, date, start, reference, ownName)
                source == HistoricCloseChain.BINANCE -> binanceOutcome(b, start, reference, ownName)
                source == HistoricCloseChain.COINBASE -> coinbaseOutcome(b, date, start, reference)
                else -> chainOutcome(source, b, start, reference, ownName)
            }
        }
        return CloseResult(resolved.price, resolved.unchecked, resolved.provider)
    }

    /** Coinbase wie bisher (Fenster genau über den Tag, jedes Alter; USD, sonst USDC; ±25 %). */
    internal suspend fun coinbaseOutcome(b: String, date: LocalDate, start: Long, reference: Double): HistoricCloseChain.Outcome =
        coinbaseClose(b, date, start, reference).let { HistoricCloseChain.Outcome(it.price, it.unchecked, DataFreshness.COINBASE) }

    /**
     * Binance-Gruppe wie bisher: Spot (data-api, api; 1/3…3), Futures (±25 %), Binance.US (USDT, sonst USD; ±25 %).
     * Anbieter des Treffers: «Binance», «Binance Futures» bzw. «Binance.US».
     */
    private suspend fun binanceOutcome(b: String, start: Long, reference: Double, ownName: String?): HistoricCloseChain.Outcome {
        // Gab es einen Kurs, der die Prüfung nicht bestand?
        var rejected = false

        // Binance-Spot: Identitätsprüfung 1/3…3; Futures und Ausweich-Quellen zusätzlich ±25 %
        val binance = listOf(
            Triple("data-api.binance.vision", "https://data-api.binance.vision/api/v3/klines", true),
            Triple("api.binance.com", "https://api.binance.com/api/v3/klines", true),
            Triple("fapi.binance.com", "https://fapi.binance.com/fapi/v1/klines", false),
        )
        // Gleicher Token (K-8): Nennen die Paare der Merkliste den Coin klar anders als Binance, fällt Binance aus
        val binanceOk = tokenIdentityStore.binanceAllowed(b, ownName)
        if (!binanceOk) rejected = true
        for ((host, endpoint, primary) in binance) {
            if (!binanceOk) break
            val r = binanceClose(host, endpoint, "${b}USDT", start, primary, reference)
            r.price?.let { return HistoricCloseChain.Outcome(it, provider = DataFreshness.candleProvider(host)) }
            rejected = rejected || r.unchecked
        }
        for (symbol in listOf("${b}USDT", "${b}USD")) {
            val r = binanceClose("api.binance.us", "https://api.binance.us/api/v3/klines", symbol, start, false, reference)
            r.price?.let { return HistoricCloseChain.Outcome(it, provider = DataFreshness.BINANCE_US) }
            rejected = rejected || r.unchecked
        }
        return HistoricCloseChain.Outcome(null, rejected)
    }

    private suspend fun binanceClose(
        host: String,
        endpoint: String,
        symbol: String,
        start: Long,
        primary: Boolean,
        current: Double,
    ): CloseResult {
        if (BlockedSources.isBlocked(host)) return CloseResult(null)
        val body = get(host, "$endpoint?symbol=$symbol&interval=1d&startTime=$start&limit=1") ?: return CloseResult(null)
        val candles = runCatchingCancellable { CandleDataSource.parseBinance(body) }.getOrNull() ?: return CloseResult(null)
        val close = closeOf(candles, start) ?: return CloseResult(null)
        // Führt die Quelle den Coin heute als denselben Token? (jüngste Tageskerze)
        val latest = get(host, "$endpoint?symbol=$symbol&interval=1d&limit=1")
            ?.let { runCatchingCancellable { CandleDataSource.parseBinance(it) }.getOrNull() }
            ?.lastOrNull()?.close
        return if (PricePlausibility.acceptSourceClose(close, primary, sourceLatest = latest, current = current)) {
            CloseResult(close)
        } else {
            CloseResult(null, unchecked = true)
        }
    }

    private suspend fun coinbaseClose(b: String, date: LocalDate, start: Long, current: Double): CloseResult {
        val host = "api.exchange.coinbase.com"
        // Fenster genau über den einen Tag (ISO 8601, UTC)
        val from = "${date}T00:00:00Z"
        val to = "${date}T23:59:59Z"
        var rejected = false
        for (quote in listOf("USD", "USDC")) {
            if (BlockedSources.isBlocked(host)) break
            val url = "https://$host/products/$b-$quote/candles?granularity=86400&start=$from&end=$to"
            val body = get(host, url) ?: continue
            val candles = runCatchingCancellable { CandleDataSource.parseCoinbase(body) }.getOrNull() ?: continue
            val close = closeOf(candles, start) ?: continue
            // Jüngste Tageskerzen desselben Produkts (ohne Zeitfenster = die neuesten)
            val latest = get(host, "https://$host/products/$b-$quote/candles?granularity=86400")
                ?.let { runCatchingCancellable { CandleDataSource.parseCoinbase(it) }.getOrNull() }
                ?.maxByOrNull { it.openTime }?.close
            if (PricePlausibility.acceptSourceClose(close, primary = false, sourceLatest = latest, current = current)) {
                return CloseResult(close)
            }
            rejected = true
        }
        return CloseResult(null, unchecked = rejected)
    }

    private fun closeOf(candles: List<HourCandle>, start: Long): Double? =
        candles.firstOrNull { it.openTime == start }?.close?.takeIf { it > 0.0 && it.isFinite() }

    /**
     * Devisenkurs USD → [currency] am Tag [date]; USD = 1 (ohne Datum).
     * Frankfurter liefert an Wochenenden/Feiertagen den letzten Geschäftstag — dessen
     * Datum steht in [HistoricFx.date]. null, wenn kein Kurs zu haben ist.
     */
    suspend fun usdTo(currency: String, date: LocalDate): HistoricFx? {
        // Unverändert durch die Ausweich-Kette der Kurse: Wochenende/Feiertag → letzter Geschäftstag (Frankfurter)
        val target = currency.trim().uppercase()
        if (target == "USD") return HistoricFx(1.0, null)
        if (target.length != 3 || !target.all { it in 'A'..'Z' }) return null
        return withContext(Dispatchers.IO) {
            val urls = listOf(
                "https://api.frankfurter.app/$date?from=USD&to=$target",
                "https://api.frankfurter.dev/v1/$date?from=USD&to=$target",
            )
            for (url in urls) {
                val body = get(FX_HOST, url) ?: continue
                val fx = runCatchingCancellable {
                    val root = JSONObject(body)
                    val rate = root.getJSONObject("rates").getDouble(target)
                    HistoricFx(rate, root.optString("date").takeIf { it.isNotEmpty() })
                }.getOrNull()
                if (fx != null && fx.rate > 0.0 && fx.rate.isFinite()) return@withContext fx
            }
            // Lew nach der Euro-Einführung: EUR × 1.95583 (fester Kurs)
            if (CurrencyConversion.deriveBgnFromEur(target, date)) {
                val eur = usdTo("EUR", date)
                CurrencyConversion.bgnFromEur(eur?.rate)?.let { return@withContext HistoricFx(it, eur?.date) }
            }
            null
        }
    }

    /** Tageskurse je «Währung|von|bis» mit Abfragezeit (nur im Speicher). */
    private val fxSeriesCache = ConcurrentHashMap<String, Pair<Map<Long, Double>, Long>>()

    /**
     * Tageskurse USD → [currency] von [from] bis [to] (EZB über Frankfurter, in Stücken von höchstens
     * [PortfolioHistoryFx.FX_CHUNK_DAYS] Tagen — längere Zeiträume liefert Frankfurter nur wöchentlich): Tag (Tage seit
     * 1970-01-01) → Kurs, nur Geschäftstage — Wochenenden und Feiertage füllt [PortfolioHistoryFx.rateOn] mit dem
     * Vortag. USD: leere Karte (kein Bedarf). 6 h im Speicher. null, wenn kein Kurs zu haben ist oder ein Stück mit
     * Werktag bei beiden Anbietern scheitert (lieber keine Reihe als eine mit einer Lücke von einem Jahr); ein Stück ohne
     * Werktag ([PortfolioHistoryFx.chunkMayHaveRates]) zählt dann als leer.
     */
    suspend fun usdToSeries(currency: String, from: LocalDate, to: LocalDate): Map<Long, Double>? {
        val target = currency.trim().uppercase()
        if (target == "USD") return emptyMap()
        if (target.length != 3 || !target.all { it in 'A'..'Z' } || from.isAfter(to)) return null
        val key = "$target|$from|$to"
        val now = System.currentTimeMillis()
        fxSeriesCache[key]?.let { (rates, at) -> if (now - at in 0 until FX_SERIES_TTL_MILLIS) return rates }
        return withContext(Dispatchers.IO) {
            val symbols = PortfolioHistoryFx.requestCurrencies(target).joinToString(",")
            val merged = HashMap<Long, Double>()
            val today = LocalDate.now(ZoneOffset.UTC)
            for ((start, end) in PortfolioHistoryFx.fxChunks(from, to)) {
                // Stück ohne Werktag (nur Wochenende bzw. nur heute): eine Fehlantwort ist dort normal — leer statt alles verwerfen
                val part = fxSeriesChunk(target, symbols, start, end)
                    ?: if (PortfolioHistoryFx.chunkMayHaveRates(start, end, today)) return@withContext null else emptyMap()
                merged += part
            }
            if (merged.isEmpty()) return@withContext null
            fxSeriesCache[key] = merged to System.currentTimeMillis()
            merged
        }
    }

    /**
     * Ein Stück der Zeitreihe ([usdToSeries]) von beiden Frankfurter-Adressen: die erste Antwort mit Kursen, sonst eine
     * gültige leere (z. B. Zeitraum ohne Kurse dieser Währung); null, wenn keine Adresse eine gültige Antwort gibt.
     */
    private suspend fun fxSeriesChunk(target: String, symbols: String, from: LocalDate, to: LocalDate): Map<Long, Double>? {
        val urls = listOf(
            "https://api.frankfurter.app/$from..$to?from=USD&to=$symbols",
            "https://api.frankfurter.dev/v1/$from..$to?from=USD&to=$symbols",
        )
        var answered = false
        for (url in urls) {
            val body = get(FX_HOST, url) ?: continue
            val byDate = runCatchingCancellable {
                val rates = JSONObject(body).getJSONObject("rates")
                rates.keys().asSequence().associateWith { date ->
                    val day = rates.getJSONObject(date)
                    day.keys().asSequence().associateWith { day.getDouble(it) }
                }
            }.getOrNull() ?: continue
            answered = true
            val series = PortfolioHistoryFx.ratesByDay(target, byDate)
            if (series.isNotEmpty()) return series
        }
        return if (answered) emptyMap() else null
    }

    /** GET mit eigener Zeitgrenze; null bei Fehler. 451/403 sperrt die Quelle vorübergehend. */
    private suspend fun get(host: String, url: String): String? {
        val body = try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { httpClient.callMarket(url, null) }
        } catch (e: Exception) {
            // Echten Abbruch des Aufrufers nicht verschlucken
            currentCoroutineContext().ensureActive()
            if (host == FX_HOST || !BlockedSources.noteFailure(host, e)) {
                Timber.d(e, "Stichtag: Abfrage fehlgeschlagen: %s", url)
            }
            null
        }
        currentCoroutineContext().ensureActive()
        return body
    }

    internal companion object {
        const val PARALLEL = 4
        const val REQUEST_TIMEOUT_MILLIS = 10_000L
        const val FX_HOST = "frankfurter"
        const val FX_SERIES_TTL_MILLIS = 6 * 60 * 60_000L

        fun isAsset(s: String) = s.isNotEmpty() && s.length <= 15 && s.all { it.isLetterOrDigit() }
    }
}
