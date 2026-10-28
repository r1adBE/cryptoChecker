package com.cryptochecker.app.data

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.data.portfolio.HistoricPriceSource
import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.data.remote.candles.CandlePair
import com.cryptochecker.app.data.remote.candles.CandlePairs
import com.cryptochecker.app.data.remote.candles.OwnCandleDataSource
import com.cryptochecker.app.data.remote.candles.OwnCandlePolicy
import com.cryptochecker.app.domain.activity.HourCandle
import com.cryptochecker.app.domain.portfolio.PortfolioHistoryFx
import com.cryptochecker.app.domain.watch.SheetChart
import com.cryptochecker.app.domain.watch.SheetCandleRequest
import com.cryptochecker.app.domain.watch.SheetChartCache
import com.cryptochecker.app.domain.watch.SheetChartFx
import com.cryptochecker.app.domain.watch.SheetChartRange
import com.cryptochecker.app.domain.watch.SheetChartResult
import com.cryptochecker.app.widget.WidgetCandle
import com.cryptochecker.app.widget.WidgetChartRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kerzen für den Chart im Aktionsblatt: gleiche Intervalle wie das Einzel-Widget ([WidgetChartRange]).
 * Zuerst die Kerzen der EIGENEN Börse des Paars ([OwnCandleDataSource], z. B. Kraken-Paar → Kraken);
 * sonst die Ausweich-Kette ([CandleDataSource], Futures-Paare zuerst USDⓈ-M): Paar zuerst, bei
 * Fiat-Quotes ersatzweise die USDT-Reihe umgerechnet (siehe [SheetChart.requests]) — je Kerze mit dem
 * Devisenkurs ihres Tags (dieselben EZB-Tageskurse wie der Wertverlauf des Portfolios,
 * [HistoricPriceSource.usdToSeries]); ohne Tageskurse mit einem Faktor (aktueller Kurs).
 * Jede Reihe gilt nur, wenn sie (umgerechnet) zum Kurs des Paars passt (gleicher Token,
 * [SheetChart.accept]); passt die Reihe einer Quelle nicht, ist die nächste der Kette dran.
 * TradFi-Paare und DEX-Pools nie aus der Kette ([OwnCandlePolicy.allowsFallback]).
 * Im Speicher 5, 30 bzw. 60 Minuten je Paar (mit Börse) und Zeitraum ([SheetChartCache]), verworfen
 * beim Aktualisieren von Hand oder bei einem Kurssprung; nie auf dem Main-Thread.
 */
@Singleton
class SheetChartRepository @Inject constructor(
    private val candleDataSource: CandleDataSource,
    private val ownCandles: OwnCandleDataSource,
    private val candlePairs: CandlePairs,
    private val historicPriceSource: HistoricPriceSource,
    private val fxRateSource: FxRateSource,
    private val tokenIdentityStore: TokenIdentityStore,
) {
    private val cache = SheetChartCache()

    /** Börse des Paars für den Zwischenspeicher (eigene Kerzen je Börse). */
    private fun exchange(watch: WatchEntity): String = CandlePair.of(watch, tradFi = false).cacheKey

    /** Ohne Netz: ausgeblendet (DEX), gültiger Zwischenspeicher oder null (laden). */
    fun cached(watch: WatchEntity, range: SheetChartRange): SheetChartResult? {
        if (!SheetChart.isSupported(watch.exchangeKey, watch.baseAsset, watch.quoteAsset)) return SheetChartResult.Unsupported
        return cache.get(watch.baseAsset, watch.quoteAsset, range, watch.lastPrice, exchange(watch))
    }

    /** Aktualisieren im Blatt: Kerzen dieses Paars (alle Zeiträume) neu laden. */
    fun invalidate(watch: WatchEntity) {
        cache.invalidate(watch.baseAsset, watch.quoteAsset, exchange(watch))
        ownCandles.invalidate(CandlePair.of(watch, tradFi = false))
    }

    /** Nach unten ziehen in der Merkliste: alle Kerzen neu laden. */
    fun invalidateAll() {
        cache.invalidateAll()
        ownCandles.invalidateAll()
    }

    suspend fun load(watch: WatchEntity, range: SheetChartRange): SheetChartResult {
        cached(watch, range)?.let { return it }
        val widgetRange = WidgetChartRange.valueOf(range.name)
        val interval = widgetRange.candleInterval
        val pair = candlePairs.of(watch)
        val exchange = pair.cacheKey
        val quote = watch.quoteAsset.trim().uppercase()
        val quoteIsFiat = quote in FxRateSource.CURRENCIES
        return withContext(Dispatchers.Default) {
            // 1. Eigene Börse des Paars (kein Hinweis auf eine fremde Quelle nötig)
            if (ownCandles.supports(pair, interval)) {
                val own = try {
                    withTimeoutOrNull(TIMEOUT_MILLIS) { ownCandles.candles(pair, interval, widgetRange.limit) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Eigene Kerzen für das Aktionsblatt nicht verfügbar: %s", exchange)
                    null
                }
                val request = SheetCandleRequest(watch.baseAsset.trim().uppercase(), quote, convert = false)
                val candles = SheetChart.accept(own?.value?.map(::widgetCandle), request, watch.lastPrice)
                if (own != null && candles != null) {
                    val result = SheetChartResult.Ready(
                        candles = candles,
                        converted = false,
                        provider = own.provider,
                        sourcePair = "${request.base}/${request.quote}",
                        fx = SheetChartFx.NONE,
                        fetchedAt = System.currentTimeMillis(),
                    )
                    cache.put(watch.baseAsset, watch.quoteAsset, range, result, watch.lastPrice, exchange)
                    return@withContext result
                }
            }
            // TradFi und DEX: nie Kerzen eines gleichnamigen Krypto-Tokens
            if (!OwnCandlePolicy.allowsFallback(pair)) return@withContext SheetChartResult.NoData

            // 2. Ausweich-Kette (Binance … Coinbase), Futures-Paare zuerst USDⓈ-M; Binance nur, wenn der
            // Name des Coins an der Börse des Paars nicht klar anders lautet (gleicher Token)
            val identityName = tokenIdentityStore.pairName(watch.exchangeKey, watch.baseAsset)
            for (request in SheetChart.requests(watch.baseAsset, watch.quoteAsset, quoteIsFiat)) {
                var accepted: List<WidgetCandle>? = null
                var rates: Map<Long, Double>? = null
                var ratesLoaded = false
                // Gesamtgrenze je Abfrage: Die Ausweich-Kette kann sonst lange dauern
                val sourced = try {
                    withTimeoutOrNull(TIMEOUT_MILLIS) {
                        candleDataSource.candlesSourced(
                            request.base, request.quote, interval, widgetRange.limit, preferFutures = pair.isFutures,
                            identityName = identityName,
                        ) { hourCandles ->
                            val raw = hourCandles.map(::widgetCandle)
                            // Tageskurse nur für die Ausweich-Reihe, einmal je Abfrage
                            if (request.convert && !ratesLoaded) {
                                rates = dailyRates(quote)
                                ratesLoaded = true
                            }
                            val dayRates = rates
                            // Ohne Tageskurse: aktueller Devisenkurs für die Prüfung auf gleichen Token
                            val current = if (request.convert && dayRates.isNullOrEmpty()) currentRate(quote) else null
                            val ok = SheetChart.accept(raw, request, watch.lastPrice, dayRates, current)
                            accepted = ok
                            // Fremde Reihe gleichen Kürzels: nächste Quelle der Kette
                            ok != null
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Kerzen für das Aktionsblatt nicht verfügbar: %s/%s", request.base, request.quote)
                    null
                }
                val candles = accepted
                if (sourced == null || candles == null) continue
                val result = SheetChartResult.Ready(
                    candles = candles,
                    converted = request.convert,
                    provider = sourced.provider,
                    sourcePair = "${request.base}/${request.quote}",
                    fx = SheetChart.fxMode(request, rates),
                    fetchedAt = System.currentTimeMillis(),
                )
                cache.put(watch.baseAsset, watch.quoteAsset, range, result, watch.lastPrice, exchange)
                return@withContext result
            }
            SheetChartResult.NoData
        }
    }

    /** Kerze für Blatt und Widget; Umsatz in der Quote für die Volumen-Balken. */
    private fun widgetCandle(it: HourCandle): WidgetCandle = WidgetCandle(
        openTime = it.openTime, open = it.open, high = it.high, low = it.low, close = it.close,
        volume = SheetChart.quoteVolume(it.volume, it.quoteVolume, it.close),
        volumeApproximated = SheetChart.isVolumeApproximated(it.quoteVolume),
    )

    /**
     * Tageskurse USD → [quote] für ein Jahr (alle Zeiträume des Blatts aus EINER Abfrage, 6 h im
     * Speicher von [HistoricPriceSource]); null/leer = nicht zu haben → Umrechnung mit dem
     * aktuellen Kurs ([com.cryptochecker.app.domain.watch.SheetChartFx.CURRENT]).
     */
    private suspend fun dailyRates(quote: String): Map<Long, Double>? {
        val today = LocalDate.ofEpochDay(SheetChart.utcEpochDay(System.currentTimeMillis()))
        val (from, to) = PortfolioHistoryFx.requestRange(FX_DAYS, today)
        return try {
            withTimeoutOrNull(FX_TIMEOUT_MILLIS) { historicPriceSource.usdToSeries(quote, from, to) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "Tageskurse für das Aktionsblatt nicht verfügbar: %s", quote)
            null
        }
    }

    /** Aktueller Kurs USD → [quote] (Zwischenspeicher von [FxRateSource]); null = unbekannt. */
    private suspend fun currentRate(quote: String): Double? = try {
        withTimeoutOrNull(FX_TIMEOUT_MILLIS) { fxRateSource.usdTo(quote) } ?: fxRateSource.cached(quote)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.d(e, "Devisenkurs für das Aktionsblatt nicht verfügbar: %s", quote)
        fxRateSource.cached(quote)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
        const val FX_TIMEOUT_MILLIS = 12_000L

        /** Längster Zeitraum des Blatts (1 Jahr). */
        const val FX_DAYS = 366
    }
}
