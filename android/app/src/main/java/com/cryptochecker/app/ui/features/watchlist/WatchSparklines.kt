// Mini-Chart der Zeilen (Zwischenspeicher, eigene Stundenkerzen, Ausweich-Reihe) und Quelle von Tagesänderung und
// Chart, wenn sie nicht von der Börse des Paars kommen. Reine Lese-Abfragen ohne eigenen Zustand.
package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.data.SparklineRepository
import com.cryptochecker.app.data.TokenIdentityStore
import com.cryptochecker.app.data.portfolio.CurrencyConverter
import com.cryptochecker.app.data.remote.candles.CandlePairs
import com.cryptochecker.app.data.remote.candles.OwnHourlyCandles
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.watch.SheetChart
import com.cryptochecker.app.refresh.DayReferences
import javax.inject.Inject

class WatchSparklines @Inject constructor(
    private val currencyConverter: CurrencyConverter,
    private val sparklineRepository: SparklineRepository,
    private val dayReferences: DayReferences,
    private val ownHourly: OwnHourlyCandles,
    private val candlePairs: CandlePairs,
    /** Namen der Coins je Börse: klar anderer Name als die Ausweich-Reihe → kein Mini-Chart von dort. */
    private val tokenIdentityStore: TokenIdentityStore,
) {

    /**
     * Was in der Zeile von [watch] nicht von seiner Börse kommt (%-Änderung aus fremden Kerzen, Mini-Chart einer
     * anderen Börse) — für den kurzen Hinweis in der Zeile ([com.cryptochecker.app.domain.watch.DayChange.rowSource]).
     */
    fun rowSource(watch: WatchEntity): com.cryptochecker.app.domain.watch.DayChange.RowSource? =
        com.cryptochecker.app.domain.watch.DayChange.rowSource(foreignCandleSource(watch), foreignSparklineSource(watch))

    /**
     * Börse des Mini-Charts von [watch], falls nicht die eigene (USDT-Reihe der Ausweich-Kette); null = eigene Börse
     * bzw. kein Chart. Für den Hinweis im Aktionsblatt und den Zeilensatz des Screenreaders.
     */
    fun foreignSparklineSource(watch: WatchEntity): String? {
        // Mini-Chart von der eigenen Börse: kein Hinweis; sonst Anbieter der USDT-Reihe
        val pair = candlePairs.of(watch)
        val chartProvider = if (ownSparkline(pair, watch.lastPrice) != null) {
            com.cryptochecker.app.data.remote.candles.OwnCandlePolicy.provider(pair)
        } else if (com.cryptochecker.app.data.remote.candles.OwnCandlePolicy.allowsFallback(pair)) {
            sparklineRepository.sparklineProvider(watch.baseAsset)?.takeIf { fallbackSeriesAllowed(watch, it) }
        } else {
            null
        }
        return com.cryptochecker.app.domain.watch.DayChange.foreignCandleSource(listOf(watch.exchangeKey, watch.exchangeName), chartProvider)
    }

    /**
     * Kam die Änderung von [watch] aus den Kerzen einer anderen Börse (z. B. Kraken-Paar, Binance-Kerzen)?
     * Dann deren Name für den Hinweis im Aktionsblatt und in «Was bewegt sich mit?»; sonst null.
     */
    fun foreignCandleSource(watch: WatchEntity): String? =
        com.cryptochecker.app.domain.watch.DayChange.foreignCandleSource(
            listOf(watch.exchangeKey, watch.exchangeName),
            dayReferences.pillCandleSource(watch.id),
        )

    /**
     * Zwischengespeicherter 24-Stunden-Verlauf, ohne Netz — für den ersten Frame einer Zeile.
     * Nur bei gleichem Token wie das Paar ([SheetChart.sparklineFor]).
     */
    fun cached(watch: WatchEntity): List<Double>? {
        val pair = candlePairs.of(watch)
        ownSparkline(pair, watch.lastPrice)?.let { return it }
        if (!com.cryptochecker.app.data.remote.candles.OwnCandlePolicy.allowsFallback(pair)) return null
        if (!fallbackSeriesAllowed(watch, sparklineRepository.sparklineProvider(watch.baseAsset))) return null
        return SheetChart.sparklineFor(
            sparklineRepository.cached(watch.baseAsset), watch.lastPrice, currencyConverter.cachedRate(watch.conversionQuote, USD),
            currencyConverter.liveUsdtUsd(),
        )
    }

    /** Ausweich-Reihe von [provider] beim Paar [watch] erlaubt (kein klarer Namens-Widerspruch)? */
    private fun fallbackSeriesAllowed(watch: WatchEntity, provider: String?): Boolean =
        tokenIdentityStore.fallbackSeriesAllowed(watch.exchangeKey, watch.baseAsset, provider)

    /**
     * Gemerkte Stundenkurse der EIGENEN Börse des Paars (in seiner Quote, Umrechnungsfaktor 1) — nur
     * bei gleichem Token ([SheetChart.sparklineFor]); null = keine (dann die USDT-Reihe).
     */
    private fun ownSparkline(pair: com.cryptochecker.app.data.remote.candles.CandlePair, price: Double?): List<Double>? =
        if (!ownHourly.supports(pair)) null else SheetChart.sparklineFor(ownHourly.cachedCloses(pair), price, 1.0)

    /**
     * 24-Stunden-Verlauf eines Assets (Schlusskurse, aufsteigend); null bei Fehler oder wenn die
     * USDT-Reihe nicht zum Kurs des Paars passt (anderer Token, [SheetChart.sparklineFor]).
     * Wird nur für sichtbare Zeilen angefragt, siehe WatchlistScreen.
     */
    suspend fun load(watch: WatchEntity): List<Double>? {
        // Zuerst die eigene Börse des Paars; TradFi und DEX nie mit der USDT-Reihe eines Krypto-Tokens
        val pair = candlePairs.of(watch)
        if (ownHourly.supports(pair)) {
            SheetChart.sparklineFor(ownHourly.closes(pair), watch.lastPrice, 1.0)?.let { return it }
        }
        if (!com.cryptochecker.app.data.remote.candles.OwnCandlePolicy.allowsFallback(pair)) return null
        val closes = sparklineRepository.closes(watch.baseAsset) ?: return null
        // Reihe eines anderen Tokens (Name klar verschieden): lieber kein Chart
        if (!fallbackSeriesAllowed(watch, sparklineRepository.sparklineProvider(watch.baseAsset))) return null
        val quoteUsd = currencyConverter.cachedRate(watch.conversionQuote, USD) ?: currencyConverter.rate(watch.conversionQuote, USD)
        return SheetChart.sparklineFor(closes, watch.lastPrice, quoteUsd, currencyConverter.liveUsdtUsd())
    }
}

/** Ziel der Umrechnung für die Prüfung des Mini-Charts (USD je Einheit der Quote). */
private const val USD = "USD"
