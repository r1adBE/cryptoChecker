package com.cryptochecker.app.data

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.domain.watch.SheetChart
import com.cryptochecker.app.domain.watch.SheetChartCache
import com.cryptochecker.app.domain.watch.SheetChartRange
import com.cryptochecker.app.domain.watch.SheetChartResult
import com.cryptochecker.app.widget.WidgetCandle
import com.cryptochecker.app.widget.WidgetChartRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kerzen für den Chart im Aktionsblatt: gleiche Quelle und Intervalle wie das
 * Einzel-Widget ([CandleDataSource], [WidgetChartRange]); Paar zuerst, bei Fiat-Quotes
 * ersatzweise die USDT-Reihe umgerechnet (siehe [SheetChart.requests]). Im Speicher
 * 5 bzw. 30 Minuten je Paar und Zeitraum ([SheetChartCache]); nie auf dem Main-Thread.
 */
@Singleton
class SheetChartRepository @Inject constructor(
    private val candleDataSource: CandleDataSource,
) {
    private val cache = SheetChartCache()

    /** Ohne Netz: ausgeblendet (DEX), gültiger Zwischenspeicher oder null (laden). */
    fun cached(watch: WatchEntity, range: SheetChartRange): SheetChartResult? {
        if (!SheetChart.isSupported(watch.marketKey, watch.baseAsset, watch.quoteAsset)) return SheetChartResult.Unsupported
        return cache.get(watch.baseAsset, watch.quoteAsset, range)
    }

    suspend fun load(watch: WatchEntity, range: SheetChartRange): SheetChartResult {
        cached(watch, range)?.let { return it }
        val widgetRange = WidgetChartRange.valueOf(range.name)
        val quoteIsFiat = watch.quoteAsset.trim().uppercase() in FxRateSource.CURRENCIES
        return withContext(Dispatchers.Default) {
            for (request in SheetChart.requests(watch.baseAsset, watch.quoteAsset, quoteIsFiat)) {
                // Gesamtgrenze je Abfrage: Die Ausweich-Kette kann sonst lange dauern
                val raw = try {
                    withTimeoutOrNull(TIMEOUT_MILLIS) {
                        candleDataSource.candles(request.base, request.quote, widgetRange.candleInterval, widgetRange.limit)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Kerzen für das Aktionsblatt nicht verfügbar: %s/%s", request.base, request.quote)
                    null
                }
                val candles = SheetChart.accept(
                    raw?.map { WidgetCandle(openTime = it.openTime, open = it.open, high = it.high, low = it.low, close = it.close) },
                    request,
                    watch.lastPrice,
                ) ?: continue
                val result = SheetChartResult.Ready(candles, converted = request.convert)
                cache.put(watch.baseAsset, watch.quoteAsset, range, result)
                return@withContext result
            }
            SheetChartResult.NoData
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
    }
}
