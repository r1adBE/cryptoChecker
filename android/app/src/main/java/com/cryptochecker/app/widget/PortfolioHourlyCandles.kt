package com.cryptochecker.app.widget

import com.cryptochecker.app.data.SparklineRepository
import com.cryptochecker.app.domain.portfolio.PortfolioStables
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetSeries
import com.cryptochecker.app.domain.portfolio.PriceSample
import com.cryptochecker.app.domain.portfolio.TimedPrice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stundenkurse für den 24-h-Wertverlauf des Portfolio-Widgets ([PortfolioSnapshotUpdater]): fehlende Stundenkerzen
 * laden ([loadMissingCandles]) und die schon vorhandenen ohne Netz einsammeln ([cachedHourlyPrices]).
 * Eigene Klasse neben dem Updater, weil sie nur Kurse kennt, keine Aufnahme.
 */
@Singleton
class PortfolioHourlyCandles @Inject constructor(
    private val store: PortfolioSnapshotStore,
    private val widgetUpdater: WidgetUpdater,
    private val sparklineRepository: SparklineRepository,
) {
    /** Letzter Kerzen-Abruf je Coin (Grossschreibung) — höchstens einer je Stunde. */
    private val candleAttempts = ConcurrentHashMap<String, Long>()

    /**
     * Stundenkerzen für offene Coins, denen Stundenkurse fehlen — z. B. Coins, die nur im
     * Portfolio und nicht in der Merkliste sind (dort liefern sonst die Mini-Charts die Kurse).
     * Die grössten Positionen zuerst, je Coin höchstens ein Abruf pro Stunde
     * ([PortfolioWidgetSeries.candleCoins]), gemeinsam höchstens [CANDLE_LOAD_TIMEOUT_MILLIS]
     * (das Widget wartet insgesamt nur 8 s). Die Kerzen landen im Zwischenspeicher von
     * [SingleWidgetRenderer]; [PortfolioSnapshotUpdater.record] führt sie mit den gemerkten Kursen zusammen. Fehler bleiben still.
     * @param open offene Coins nach der Standardmethode, grösste Position zuerst (Gesamtsicht der Aufnahme)
     */
    suspend fun loadMissingCandles(open: List<String>) {
        if (open.isEmpty()) return
        val now = System.currentTimeMillis()
        val known = PortfolioWidgetSeries.merge(
            stored = store.hourlyPrices(),
            fresh = cachedHourlyPrices(open.toSet(), emptyList(), emptyMap(), now),
            now = now,
        )
        val due = PortfolioWidgetSeries.candleCoins(open, known, candleAttempts, now, PortfolioStables.COINS)
        if (due.isEmpty()) return
        due.forEach { candleAttempts[it] = now }
        withTimeoutOrNull(CANDLE_LOAD_TIMEOUT_MILLIS) {
            coroutineScope {
                due.map { coin ->
                    async {
                        try {
                            widgetUpdater.loadHourlyPrices(coin)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.d(e, "Portfolio-Widget: Stundenkerzen für %s nicht geladen", coin)
                        }
                    }
                }.awaitAll()
            }
        }
    }

    /**
     * Bereits geladene Stundenkurse der [coins] — ohne Netz: Mini-Charts der Merkliste
     * ([SparklineRepository]), Kerzen der Einzel-Widgets, die Kursaufnahmen ([history]) und
     * der aktuelle Kurs. Zusammengeführt wird später ([PortfolioWidgetSeries.merge]).
     */
    fun cachedHourlyPrices(
        coins: Set<String>,
        history: List<PriceSample>,
        current: Map<String, Double>,
        now: Long,
    ): Map<String, List<TimedPrice>> {
        val out = HashMap<String, List<TimedPrice>>()
        for (coin in coins) {
            // Nur USDT gilt immer 1; USDC, DAI … brauchen Stundenkurse (Abkopplung sichtbar)
            if (!PortfolioStables.needsQuote(coin)) continue
            val fromSparkline = sparklineRepository.cachedWithTime(coin)
                ?.let { (time, closes) -> PortfolioWidgetSeries.fromHourlyCloses(closes, time) }
                .orEmpty()
            val fromWidget = widgetUpdater.cachedHourlyPrices(coin).orEmpty()
            val fromSamples = history.mapNotNull { sample ->
                sample.prices[coin]?.takeIf { now - sample.time in 0..PortfolioWidgetSeries.KEEP_MILLIS }
                    ?.let { TimedPrice(sample.time, it) }
            }
            val all = fromSamples + fromSparkline + fromWidget + listOfNotNull(current[coin]?.let { TimedPrice(now, it) })
            if (all.isNotEmpty()) out[coin] = all
        }
        return out
    }

    private companion object {
        /** Kerzen für alle fehlenden Coins zusammen höchstens so lange laden. */
        const val CANDLE_LOAD_TIMEOUT_MILLIS = 4_000L
    }
}
