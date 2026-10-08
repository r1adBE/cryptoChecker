package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.SparklineRepository
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeBasisMath
import com.cryptochecker.app.domain.watch.DayChange
import com.cryptochecker.marketdata.model.Ticker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
) {
    /** Läuft unabhängig vom Aufrufer weiter (App-weit, ein Singleton). */
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
    ): List<Job> =
        watches.flatMap { watch ->
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
                    runCatching {
                        if (dayStart != null) sparklineRepository.dayStartReference(base, quote, dayStart)
                        else sparklineRepository.dayReference(base, quote)
                    }
                }
            }

    /** Wartet höchstens [DAY_REFERENCE_WAIT_MILLIS]; was dann fehlt, kommt aus dem Zwischenspeicher. */
    internal suspend fun awaitDayReferenceLoads(jobs: List<Job>) {
        if (jobs.isEmpty()) return
        withTimeoutOrNull(DAY_REFERENCE_WAIT_MILLIS) { jobs.joinAll() }
    }

    /**
     * Veränderung zum neuen Kurs gemäss %-Basis ([ChangeBasisMath.choose]). Rollend: zuerst der
     * 24-h-Wert aus dem Ticker (gilt für das Paar selbst, also schon in seiner Quote — auch bei
     * Fiat-Quotes), sonst aus Kerzen. Tages-Basen ([dayStart]): nur aus Kerzen seit Tagesbeginn.
     * Kerzen mit [DayChange.select] (Kursabstand-Prüfung); null ohne Bezug — nie die
     * Veränderung seit der letzten Abfrage.
     */
    internal fun change24h(watch: WatchEntity, price: Double, ticker: Ticker, basis: ChangeBasis, dayStart: Long?): Double? =
        ChangeBasisMath.choose(basis, ticker.change24hPercent) { candleChange(watch, price, dayStart) }

    /** Veränderung aus gemerkten Kerzen: rollend ([dayStart] null) oder seit Tagesbeginn. */
    internal fun candleChange(watch: WatchEntity, price: Double, dayStart: Long?): Double? {
        val quote = DayChange.candleQuote(watch.quoteAsset)
        fun reference(q: String) = if (dayStart != null) sparklineRepository.cachedDayStartReference(watch.baseAsset, q, dayStart)
        else sparklineRepository.cachedDayReference(watch.baseAsset, q)
        return DayChange.select(
            price = price,
            pairReference = reference(quote),
            usdtReference = reference(DAY_QUOTE),
            quoteIsFiat = isFiat(watch.quoteAsset),
        )
    }

    private fun isFiat(quote: String): Boolean = quote.trim().uppercase() in FxRateSource.CURRENCIES

    private companion object {
        /** Kerzen-Quote der Mini-Charts (Ausweich-Reihe für Fiat-Quotes). */
        const val DAY_QUOTE = "USDT"

        /** So lange wartet ein Durchlauf nach den Kursen noch auf fehlende 24-h-Bezüge. */
        const val DAY_REFERENCE_WAIT_MILLIS = 5_000L
    }
}
