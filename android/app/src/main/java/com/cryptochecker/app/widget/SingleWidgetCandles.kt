// Kerzen des Einzel-Widgets (Teil von `SingleWidgetRenderer`): eigene Kerzen bzw. Ausweich-Kette mit
// Zeitlimit, Zwischenspeicher je Paar und Zeitraum, Mini-Chart.
// Eigene Datei, weil das Laden der Daten vom Zeichnen getrennt lesbar sein soll; Erweiterungsfunktionen,
// der Zwischenspeicher bleibt in der Klasse.
package com.cryptochecker.app.widget

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Kerzen für den Chart eines Paars: zuerst von seiner EIGENEN Börse
 * ([com.cryptochecker.app.data.remote.candles.OwnCandleDataSource]), sonst über die Ausweich-Kette
 * ([sparkline], Futures-Paare zuerst USDⓈ-M) — je nur bei gleichem Token
 * ([WidgetChartGeometry.pairCandles]). TradFi-Paare und DEX-Pools nie aus der Kette.
 * [Sourced.provider]: Börse der Kerzen (eigene Börse oder die der Ausweich-Kette; null = unbekannt).
 */
internal suspend fun SingleWidgetRenderer.chartCandles(
    watch: com.cryptochecker.app.data.local.model.WatchEntity,
    range: WidgetChartRange,
    fetch: Boolean = true,
): com.cryptochecker.app.domain.market.Sourced<List<WidgetCandle>>? {
    val pair = candlePairs.of(watch)
    if (ownCandles.supports(pair, range.candleInterval)) {
        val cacheKey = "own|${pair.cacheKey}|${range.name}"
        // Ohne Netz: auch ältere Kerzen aus dem Zwischenspeicher (nicht neu datiert)
        val entry = sparkCache[cacheKey]
        // Ohne Netz und fehlend bzw. veraltet: zum Nachladen vormerken ([SingleWidgetRenderer.takeStaleCharts])
        if (!fetch && !isFreshSpark(entry?.first)) staleCharts.set(true)
        val cached = if (!fetch) entry?.second
        else entry?.takeIf { isFreshSpark(it.first) }?.second
        val own = cached ?: if (!fetch) null else try {
            withTimeoutOrNull(SPARKLINE_TIMEOUT_MILLIS) {
                ownCandles.candles(pair, range.candleInterval, range.limit)?.value?.map {
                    WidgetCandle(openTime = it.openTime, open = it.open, high = it.high, low = it.low, close = it.close)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Eigene Kerzen für Widget nicht verfügbar: %s", pair.cacheKey)
            null
        }
        val accepted = WidgetChartGeometry.pairCandles(own, watch.lastPrice)
        if (accepted != null) {
            if (cached == null) sparkCache[cacheKey] = System.currentTimeMillis() to accepted
            return com.cryptochecker.app.domain.market.Sourced(accepted, watch.marketName)
        }
    }
    if (!com.cryptochecker.app.data.remote.candles.OwnCandlePolicy.allowsFallback(pair)) return null
    val fallbackKey = sparkCacheKey(watch.baseAsset, watch.quoteAsset, range, pair.isFutures)
    val accepted = WidgetChartGeometry.pairCandles(
        sparkline(watch.baseAsset, watch.quoteAsset, range, preferFutures = pair.isFutures, price = watch.lastPrice, fetch = fetch),
        watch.lastPrice,
    ) ?: return null
    // Börse der Ausweich-Reihe (für «Chart: Binance»); klar anders benannter Token (K-8): kein Chart
    val provider = sparkProvider[fallbackKey]
    if (!tokenIdentityStore.fallbackSeriesAllowed(watch.marketKey, watch.baseAsset, provider)) return null
    return com.cryptochecker.app.domain.market.Sourced(accepted, provider)
}

/** Schlüssel von [sparkCache] für die Ausweich-Kette. */
internal fun SingleWidgetRenderer.sparkCacheKey(base: String, quote: String, range: WidgetChartRange, preferFutures: Boolean): String {
    val q = quote.uppercase().let { if (it == "USD") "USDT" else it }
    return base.uppercase() + q + "|" + range.name + if (preferFutures) "|F" else ""
}

/**
 * Kerzen für den Chart — gleiches Paar, auch wenn das Widget eine andere
 * Börse zeigt. Quelle über die Ausweich-Kette (Binance, Binance.US, Coinbase),
 * siehe [CandleDataSource]. 24 h = 24 × 1 h, 7 Tage = 42 × 4 h, 30 Tage = 30 × 1 Tag.
 * Eröffnung, Hoch, Tief und Schluss bleiben erhalten (Kerzen- und Linien-Chart).
 * [price]: Kurs des Paars — passt die Reihe einer Quelle nicht (anderer Token), ist die nächste dran.
 */
internal suspend fun SingleWidgetRenderer.sparkline(
    base: String, quote: String, range: WidgetChartRange, preferFutures: Boolean = false, price: Double? = null,
    fetch: Boolean = true,
): List<WidgetCandle>? {
    val q = quote.uppercase().let { if (it == "USD") "USDT" else it }
    val symbol = base.uppercase() + q
    val cacheKey = sparkCacheKey(base, quote, range, preferFutures)
    sparkCache[cacheKey]?.let { (time, data) ->
        val fresh = isFreshSpark(time)
        if (!fetch && !fresh) staleCharts.set(true)
        if (!fetch || fresh) return data
    }
    // Ohne Netz (Widget-Empfänger): nichts im Zwischenspeicher → kein Chart (zum Nachladen vormerken)
    if (!fetch) {
        staleCharts.set(true)
        return null
    }
    fun toWidget(list: List<com.cryptochecker.app.domain.activity.HourCandle>) = list.map {
        WidgetCandle(openTime = it.openTime, open = it.open, high = it.high, low = it.low, close = it.close)
    }
    // Fremde Reihe gleichen Kürzels: nächste Quelle der Kette
    val accept: (suspend (List<com.cryptochecker.app.domain.activity.HourCandle>) -> Boolean)? =
        if (price == null) null else ({ list -> WidgetChartGeometry.pairCandles(toWidget(list), price) != null })
    // CandleDataSource arbeitet auf Dispatchers.IO, nie auf dem Main-Thread.
    // Gesamtgrenze: Die Ausweich-Kette kann sonst fast eine Minute dauern
    // (goAsync im Widget-Empfänger). Abbruch des Aufrufers wird weitergereicht.
    val candles = try {
        withTimeoutOrNull(SPARKLINE_TIMEOUT_MILLIS) {
            candleDataSource.candlesSourced(base, quote, range.candleInterval, range.limit, preferFutures, accept = accept)
                ?.also { sourced -> sourced.provider?.let { sparkProvider[cacheKey] = it } }
                ?.value?.let(::toWidget)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Kerzen für Widget nicht verfügbar: %s", symbol)
        null
    }
    if (candles != null && candles.size >= 2) {
        sparkCache[cacheKey] = System.currentTimeMillis() to candles
        return candles
    }
    return null
}

/** Kerzen in [SingleWidgetRenderer.sparkCache] gelten so lange als frisch (gespeichert um [savedAt]; null = keine). */
internal fun isFreshSpark(savedAt: Long?, now: Long = System.currentTimeMillis()): Boolean =
    savedAt != null && now - savedAt in 0 until SPARK_FRESH_MILLIS

/** Frische der zwischengespeicherten Widget-Kerzen. */
internal const val SPARK_FRESH_MILLIS = 10 * 60_000L

/** Höchstens so lange auf die Kerzen eines Widgets warten (ganze Ausweich-Kette). */
private const val SPARKLINE_TIMEOUT_MILLIS = 20_000L
