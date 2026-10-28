// Daten für Aktionsblatt und Zeilen: Quelle der Tagesänderung, Mini-Chart (Zwischenspeicher, eigene Stundenkerzen,
// Ausweich-Reihe), Sats-Kurs, «Was bewegt sich mit?», Futures- und Spot-Daten, Chart des Aktionsblatts.
// Eigene Klasse, weil es reine Lese-Abfragen ohne eigenen Zustand sind; `WatchlistViewModel` reicht sie an die
// Oberfläche weiter (die Datenquellen bleiben hier privat).
package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.data.CoinLogoRepository
import com.cryptochecker.app.data.SheetChartRepository
import com.cryptochecker.app.data.SparklineRepository
import com.cryptochecker.app.data.TokenIdentityStore
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.portfolio.CurrencyConverter
import com.cryptochecker.app.data.remote.FuturesDataSource
import com.cryptochecker.app.data.remote.SpotDataSource
import com.cryptochecker.app.data.remote.candles.CandlePairs
import com.cryptochecker.app.data.remote.candles.OwnHourlyCandles
import com.cryptochecker.app.domain.activity.ActivityMonitor
import com.cryptochecker.app.domain.activity.WhyReport
import com.cryptochecker.app.domain.refresh.DayReferences
import com.cryptochecker.app.domain.watch.SheetChart
import com.cryptochecker.app.domain.watch.SheetChartRange
import com.cryptochecker.app.domain.watch.SheetChartResult
import com.cryptochecker.app.settings.SettingsRepository
import javax.inject.Inject

class WatchlistSheetData @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val futuresDataSource: FuturesDataSource,
    private val spotDataSource: SpotDataSource,
    private val activityMonitor: ActivityMonitor,
    private val currencyConverter: CurrencyConverter,
    private val sparklineRepository: SparklineRepository,
    private val sheetChartRepository: SheetChartRepository,
    private val coinLogoRepository: CoinLogoRepository,
    private val dayReferences: DayReferences,
    private val ownHourly: OwnHourlyCandles,
    private val candlePairs: CandlePairs,
    /** Namen der Coins je Börse: klar anderer Name als die Ausweich-Reihe → kein Mini-Chart von dort. */
    private val tokenIdentityStore: TokenIdentityStore,
    /** Paare mit Mark-Preis statt Mittelkurs: «Mark» in Zeile und Blatt. */
    private val markFallbackStore: com.cryptochecker.app.data.MarkFallbackStore,
) {

    /** Watch-Ids, deren letzter Kurs der Mark-Preis war ([com.cryptochecker.app.data.MarkFallbackStore]). */
    val markFallbackIds: kotlinx.coroutines.flow.StateFlow<Set<Long>> get() = markFallbackStore.ids

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
    fun cachedSparkline(watch: WatchEntity): List<Double>? {
        val pair = candlePairs.of(watch)
        ownSparkline(pair, watch.lastPrice)?.let { return it }
        if (!com.cryptochecker.app.data.remote.candles.OwnCandlePolicy.allowsFallback(pair)) return null
        if (!fallbackSeriesAllowed(watch, sparklineRepository.sparklineProvider(watch.baseAsset))) return null
        return SheetChart.sparklineFor(
            sparklineRepository.cached(watch.baseAsset), watch.lastPrice, currencyConverter.cachedRate(watch.conversionQuote, USD),
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
    suspend fun sparkline(watch: WatchEntity): List<Double>? {
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
        return SheetChart.sparklineFor(closes, watch.lastPrice, quoteUsd)
    }

    /**
     * «1 CHF = 1’234 Sats» im Aktionsblatt eines Bitcoin-Paars: Umrechnungswährung (Hauptwährung)
     * und Faktor Quote → diese Währung — zuerst aus den Zwischenspeichern, sonst frisch; null ohne Kurs.
     */
    suspend fun satsRate(quote: String): Pair<String, Double>? {
        val target = settingsRepository.current().portfolioCurrency
        val rate = currencyConverter.cachedRate(quote, target) ?: currencyConverter.rate(quote, target)
        return rate?.let { target to it }
    }

    /** Daten für «Was bewegt sich mit?»; null, wenn gar nichts geladen werden konnte. */
    suspend fun explain(watch: WatchEntity): WhyReport? =
        runCatching { activityMonitor.explain(watch) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull()

    /**
     * Funding Rate und Open Interest: Perpetuals aus dem eigenen Kontrakt, alle anderen Krypto-Paare
     * (Spot, Termin-Futures) aus dem USDT-Perpetual des Coins (Binance, sonst Bybit, OKX); TradFi-Paare
     * ohne Perpetual und DEX-Pools nie. null ohne Daten.
     */
    suspend fun fetchFutures(watch: WatchEntity): com.cryptochecker.app.data.remote.FuturesInfo? =
        runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (watch.contractType.isPerpetual) {
                    futuresDataSource.fetch(watch)
                } else if (com.cryptochecker.app.domain.spot.SpotMarket.eligible(watch.exchangeKey, isTradFi(watch))) {
                    futuresDataSource.fetchForBase(watch.baseAsset)
                } else {
                    null
                }
            }
        }
            // Blatt geschlossen: Abbruch weiterreichen statt als «keine Daten» zu schlucken
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull()

    /**
     * Spot-Kennzahlen (CoinGecko) für den Spot-Tab der Karte «Marktdaten»; null = keine Spot-Daten
     * (TradFi, DEX-Pool, unbekannter Coin oder nicht erreichbar). Erst beim Öffnen des Tabs.
     */
    suspend fun fetchSpot(watch: WatchEntity): com.cryptochecker.app.domain.spot.SpotData? {
        if (!com.cryptochecker.app.domain.spot.SpotMarket.eligible(watch.exchangeKey, isTradFi(watch))) return null
        val symbol = com.cryptochecker.app.domain.spot.SpotMarket.lookupSymbol(watch.baseAsset) ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { spotDataSource.fetch(symbol) }
    }

    private fun isTradFi(watch: WatchEntity): Boolean =
        coinLogoRepository.isTradFi(watch.exchangeKey, watch.baseAsset, watch.quoteAsset, watch.contractType.name)

    /** Chart im Aktionsblatt aus dem Zwischenspeicher (ohne Netz); null = laden. */
    fun cachedSheetChart(watch: WatchEntity, range: SheetChartRange): SheetChartResult? =
        sheetChartRepository.cached(watch, range)

    /** Chart im Aktionsblatt: Kerzen wie das Einzel-Widget, abseits des Main-Threads. */
    suspend fun loadSheetChart(watch: WatchEntity, range: SheetChartRange): SheetChartResult =
        sheetChartRepository.load(watch, range)
}

/** Ziel der Umrechnung für die Prüfung des Mini-Charts (USD je Einheit der Quote). */
private const val USD = "USD"
