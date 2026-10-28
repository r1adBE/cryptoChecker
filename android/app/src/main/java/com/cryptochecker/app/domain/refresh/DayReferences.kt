package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.SparklineRepository
import com.cryptochecker.app.data.TokenIdentityStore
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.data.remote.candles.CandlePair
import com.cryptochecker.app.data.remote.candles.CandlePairs
import com.cryptochecker.app.data.remote.candles.OwnCandlePolicy
import com.cryptochecker.app.data.remote.candles.OwnHourlyCandles
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.domain.watch.DayChange
import com.cryptochecker.app.util.appScope
import com.cryptochecker.marketdata.model.Ticker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 24-h- und Tages-Bezüge aus Kerzen für die %-Veränderung: welche Paare Kerzen brauchen,
 * Laden anstossen und kurz abwarten, Veränderung gemäss %-Basis. Teil von [PriceRefresher].
 */
@Singleton
class DayReferences @Inject constructor(
    /** 24-h-Bezug aus Kerzen — nur Ausweich-Weg, wenn der Ticker keinen 24-h-Wert liefert. */
    private val sparklineRepository: SparklineRepository,
    /** Devisenkurs für die Kursabstand-Prüfung der USDT-Reihe bei Fiat-Paaren ([DayChange.fromUsdtSeries]). */
    private val fxRateSource: FxRateSource,
    /** Stundenkerzen der EIGENEN Börse des Paars — vor der Ausweich-Reihe (kein Hinweis «Änderung: Binance»). */
    private val ownHourly: OwnHourlyCandles,
    private val candlePairs: CandlePairs,
    /** Ausweich-Reihe nur ohne klaren Namens-Widerspruch zum Coin an der Börse des Paars (K-8). */
    private val tokenIdentityStore: TokenIdentityStore,
) {
    /** Läuft unabhängig vom Aufrufer weiter (App-weit, ein Singleton). */
    private val loadScope = appScope(Dispatchers.IO, "DayReferences")

    /**
     * Paare, die beim letzten vollen Durchlauf Kerzen brauchten (Ticker ohne 24-h-Wert) —
     * deren Bezüge werden gleich zu Beginn parallel zu den Kursen angestossen.
     */
    @Volatile
    var candleWatchIds: Set<Long> = emptySet()
        private set

    /**
     * Paare mit Kurs, die Kerzen brauchen ([ChangeBasisMath.needsCandles]): rollend nur ohne
     * brauchbaren 24-h-Wert im Ticker, Tages-Basen alle.
     * [remember]: Menge für den nächsten Durchlauf merken (nur bei vollen Durchläufen,
     * damit [PriceRefresher.refreshOne] sie nicht verkleinert).
     */
    internal fun watchesNeedingCandles(
        watches: List<WatchEntity>,
        fetched: Map<Long, Fetched>,
        basis: ChangeBasis,
        remember: Boolean,
    ): List<WatchEntity> {
        val needing = watches.filter { watch ->
            val ticker = fetched[watch.id]?.ticker ?: return@filter false
            ChangeBasisMath.needsCandles(basis, ticker.change24hPercent)
        }
        if (remember) candleWatchIds = needing.mapTo(HashSet()) { it.id }
        return needing
    }

    /**
     * Stösst das Laden der 24-h-Bezüge an: je Basis-Asset die Reihe in der Quote des Paars
     * (USD-artige teilen sich die USDT-Reihe des Mini-Charts), bei Fiat-Quotes zusätzlich
     * die USDT-Reihe als Ausweich. Läuft im eigenen Scope, damit ein Abruf nach dem
     * Warten ([awaitDayReferenceLoads]) fertig wird und beim nächsten Durchlauf bereitliegt.
     * Schlüssel in [started] werden übersprungen und ergänzt (kein doppelter Start).
     * [dayStart]: Tages-Basis — Bezug seit diesem Tagesbeginn statt rollend.
     */
    internal fun startDayReferenceLoads(
        watches: List<WatchEntity>,
        started: MutableSet<Pair<String, String>>,
        dayStart: Long?,
    ): List<Job> = startFxLoads(watches, started) + startOwnLoads(watches, started, dayStart) +
        // TradFi und DEX: nie die Reihe eines gleichnamigen Krypto-Tokens
        watches.filter { OwnCandlePolicy.allowsFallback(candlePairs.of(it)) }.flatMap { watch ->
            val quote = DayChange.candleQuote(watch.quoteAsset)
            if (quote != DAY_QUOTE && isFiat(watch.quoteAsset)) {
                listOf(watch.baseAsset to quote, watch.baseAsset to DAY_QUOTE)
            } else {
                listOf(watch.baseAsset to quote)
            }
        }
            .map { (base, quote) -> base.trim().uppercase() to quote }
            .filter { started.add(it) }
            .map { (base, quote) ->
                loadScope.launch {
                    runCatchingCancellable {
                        if (dayStart != null) sparklineRepository.dayStartReference(base, quote, dayStart)
                        else sparklineRepository.dayReference(base, quote)
                    }
                }
            }

    /**
     * Stundenkerzen der eigenen Börse ([OwnHourlyCandles]) für Paare, deren Börse sie liefert.
     * Schlüssel «OWN» + [CandlePair.cacheKey] in [started].
     */
    private fun startOwnLoads(watches: List<WatchEntity>, started: MutableSet<Pair<String, String>>, dayStart: Long?): List<Job> =
        watches.map { candlePairs.of(it) }
            .filter { ownHourly.supports(it) && started.add(OWN_KEY to it.cacheKey) }
            .map { pair ->
                loadScope.launch {
                    runCatchingCancellable {
                        if (dayStart != null) ownHourly.dayStartReference(pair, dayStart) else ownHourly.dayReference(pair)
                    }
                }
            }

    /**
     * Devisenkurse der Fiat-Quotes (3 h Zwischenspeicher in [FxRateSource]) — damit die
     * Kursabstand-Prüfung der USDT-Reihe einen Kurs hat. Schlüssel «FX» + Währung in [started].
     */
    private fun startFxLoads(watches: List<WatchEntity>, started: MutableSet<Pair<String, String>>): List<Job> =
        watches.map { it.quoteAsset.trim().uppercase() }
            .filter { DayChange.candleQuote(it) != DAY_QUOTE && isFiat(it) }
            .filter { started.add(FX_KEY to it) }
            .map { currency -> loadScope.launch { runCatchingCancellable { fxRateSource.usdTo(currency) } } }

    /**
     * Wartet höchstens [DAY_REFERENCE_WAIT_MILLIS] (mit Frist höchstens [limitMillis]); was dann
     * fehlt, kommt aus dem Zwischenspeicher.
     */
    internal suspend fun awaitDayReferenceLoads(jobs: List<Job>, limitMillis: Long = Long.MAX_VALUE) {
        val wait = minOf(DAY_REFERENCE_WAIT_MILLIS, limitMillis)
        if (jobs.isEmpty() || wait <= 0) return
        withTimeoutOrNull(wait) { jobs.joinAll() }
    }

    /**
     * Veränderung zum neuen Kurs gemäss %-Basis ([ChangeBasisMath.choose]). Rollend: zuerst der
     * 24-h-Wert aus dem Ticker (gilt für das Paar selbst, also schon in seiner Quote — auch bei
     * Fiat-Quotes), sonst aus Kerzen. Tages-Basen ([dayStart]): nur aus Kerzen seit Tagesbeginn.
     * Kerzen mit [DayChange.select] (Kursabstand-Prüfung); null ohne Bezug — nie die
     * Veränderung seit der letzten Abfrage.
     */
    internal fun change24h(watch: WatchEntity, price: Double, ticker: Ticker, basis: ChangeBasis, dayStart: Long?): Double? {
        val value = ChangeBasisMath.choose(basis, ticker.change24hPercent) { candleChange(watch, price, dayStart) }
        notePillSource(watch.id, if (value != null && ChangeBasisMath.needsCandles(basis, ticker.change24hPercent)) candleProvider(watch, price, dayStart) else null)
        return value
    }

    /**
     * Anbieter der Kerzen hinter der Änderung je Paar (Id) — nur, wenn sie zuletzt aus Kerzen kam;
     * nur im Speicher (nach dem Start bis zur ersten Aktualisierung leer).
     */
    private val pillSources = ConcurrentHashMap<Long, String>()

    /** Kerzen-Anbieter der zuletzt berechneten Änderung von [watchId]; null = aus dem Ticker oder unbekannt. */
    fun pillCandleSource(watchId: Long): String? = pillSources[watchId]

    /** Merkt (oder vergisst mit null) den Kerzen-Anbieter der Änderung von [watchId]. */
    internal fun notePillSource(watchId: Long, provider: String?) {
        if (provider != null) pillSources[watchId] = provider else pillSources.remove(watchId)
    }

    /**
     * Name der Reihe, die [DayChange.select] nimmt ([DayChange.candleSourceName]): eigene Reihe →
     * ihr Anbieter, USDT-Reihe eines Fiat-Paars → «Anbieter USDT» (andere Quote, immer Hinweis).
     */
    private fun candleProvider(watch: WatchEntity, price: Double, dayStart: Long?): String? {
        val pair = candlePairs.of(watch)
        // Eigene Börse: ihr Name (gilt nicht als fremd)
        if (ownChange(pair, price, dayStart) != null) return OwnCandlePolicy.provider(pair)
        val quote = DayChange.candleQuote(watch.quoteAsset)
        val series = DayChange.series(reference(watch, quote, dayStart), reference(watch, DAY_QUOTE, dayStart), isFiat(watch.quoteAsset))
        val providerQuote = if (series == DayChange.CandleSeries.USDT) DAY_QUOTE else quote
        return DayChange.candleSourceName(sparklineRepository.cachedProvider(watch.baseAsset, providerQuote), series)
    }

    /**
     * Gemerkter Bezug in Quote [q]: rollend ([dayStart] null) oder seit Tagesbeginn. Reihe einer anderen Börse nur
     * bei belegt gleichem Namen ([TokenIdentityStore.fallbackChangeAllowed]; Kürzel + Kursband reichen für die
     * %-Veränderung nicht) → sonst null: lieber «—» als ein fremder Wert.
     */
    private fun reference(watch: WatchEntity, q: String, dayStart: Long?): com.cryptochecker.app.domain.watch.DayReference? {
        val provider = sparklineRepository.cachedProvider(watch.baseAsset, q)
        if (!tokenIdentityStore.fallbackChangeAllowed(watch.exchangeKey, watch.exchangeName, watch.baseAsset, provider)) return null
        return if (dayStart != null) sparklineRepository.cachedDayStartReference(watch.baseAsset, q, dayStart)
        else sparklineRepository.cachedDayReference(watch.baseAsset, q)
    }

    /** Veränderung aus gemerkten Kerzen der eigenen Börse ([DayChange.fromPrice], Kursabstand-Prüfung); null = keine. */
    private fun ownChange(pair: CandlePair, price: Double, dayStart: Long?): Double? {
        if (!ownHourly.supports(pair)) return null
        val reference = if (dayStart != null) ownHourly.cachedDayStartReference(pair, dayStart) else ownHourly.cachedDayReference(pair)
        return DayChange.fromPrice(price, reference)
    }

    /**
     * Veränderung aus gemerkten Kerzen: rollend ([dayStart] null) oder seit Tagesbeginn. Zuerst die
     * eigene Börse des Paars, sonst die Ausweich-Reihe (nicht für TradFi und DEX).
     */
    internal fun candleChange(watch: WatchEntity, price: Double, dayStart: Long?): Double? {
        val pair = candlePairs.of(watch)
        ownChange(pair, price, dayStart)?.let { return it }
        if (!OwnCandlePolicy.allowsFallback(pair)) return null
        val quote = DayChange.candleQuote(watch.quoteAsset)
        val fiat = isFiat(watch.quoteAsset)
        return DayChange.select(
            price = price,
            pairReference = reference(watch, quote, dayStart),
            usdtReference = reference(watch, DAY_QUOTE, dayStart),
            quoteIsFiat = fiat,
            // Fiat je USD (nur gemerkt, kein Netz) für die Kursabstand-Prüfung der USDT-Reihe
            usdToQuote = if (fiat) fxRateSource.cached(watch.quoteAsset) else null,
            // Kein Devisenkurs von vor 24 h verfügbar → bei Fiat-Paaren ohne eigene Reihe kein Wert
            // (nie der USDT-Prozentwert als Fiat-Wert)
            usdToQuoteAtOpen = null,
        )
    }

    private fun isFiat(quote: String): Boolean = quote.trim().uppercase() in FxRateSource.CURRENCIES

    private companion object {
        /** Kerzen-Quote der Mini-Charts (Ausweich-Reihe für Fiat-Quotes). */
        const val DAY_QUOTE = DayChange.USDT_QUOTE

        /** Schlüssel-Präfix der Devisenkurs-Abrufe in der Start-Menge. */
        const val FX_KEY = "FX"

        /** Schlüssel-Präfix der Abrufe eigener Kerzen in der Start-Menge. */
        const val OWN_KEY = "OWN"

        /** So lange wartet ein Durchlauf nach den Kursen noch auf fehlende 24-h-Bezüge. */
        const val DAY_REFERENCE_WAIT_MILLIS = 5_000L
    }
}
