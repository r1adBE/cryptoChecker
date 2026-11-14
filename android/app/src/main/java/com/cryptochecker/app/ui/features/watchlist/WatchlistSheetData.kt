// Daten für Aktionsblatt und Zeilen: Quelle der Tagesänderung, Mini-Chart (Zwischenspeicher, eigene Stundenkerzen,
// Ausweich-Reihe), Sats-Kurs, «Was bewegt sich mit?», Futures- und Spot-Daten, Chart des Aktionsblatts.
// Eigene Klasse, weil es reine Lese-Abfragen ohne eigenen Zustand sind; `WatchlistViewModel` reicht sie an die
// Oberfläche weiter (die Datenquellen bleiben hier privat).
package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.data.CoinLogoRepository
import com.cryptochecker.app.chart.SheetChartRepository
import com.cryptochecker.app.data.TokenIdentityStore
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.data.portfolio.CurrencyConverter
import com.cryptochecker.app.data.remote.FuturesDataSource
import com.cryptochecker.app.data.remote.SpotDataSource
import com.cryptochecker.app.activity.ActivityMonitor
import com.cryptochecker.app.domain.activity.WhyReport
import com.cryptochecker.app.domain.watch.SheetChartRange
import com.cryptochecker.app.domain.watch.SheetChartResult
import com.cryptochecker.app.data.settings.SettingsRepository
import com.cryptochecker.app.domain.util.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

class WatchlistSheetData @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val futuresDataSource: FuturesDataSource,
    private val spotDataSource: SpotDataSource,
    private val activityMonitor: ActivityMonitor,
    private val currencyConverter: CurrencyConverter,
    /** Mini-Chart der Zeilen und woher er kommt ([WatchSparklines]). */
    private val sparklines: WatchSparklines,
    private val sheetChartRepository: SheetChartRepository,
    private val coinLogoRepository: CoinLogoRepository,
    /** Namen der Coins je Börse: welcher CoinGecko-Treffer zum Paar gehört ([fetchSpot]). */
    private val tokenIdentityStore: TokenIdentityStore,
    /** Paare mit Mark-Preis statt Mittelkurs: «Mark» in Zeile und Blatt. */
    private val markFallbackStore: com.cryptochecker.app.data.MarkFallbackStore,
    /** Paare mit Stundenkurs (WOO X Spot ohne letzten Abschluss): «Stundenkurs» in Zeile und Blatt. */
    private val hourlyCloseStore: com.cryptochecker.app.data.HourlyCloseStore,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** Watch-Ids, deren letzter Kurs der Mark-Preis war ([com.cryptochecker.app.data.MarkFallbackStore]). */
    val markFallbackIds: kotlinx.coroutines.flow.StateFlow<Set<Long>> get() = markFallbackStore.ids

    /** Watch-Ids, deren letzter Kurs ein Stundenkurs war ([com.cryptochecker.app.data.HourlyCloseStore]). */
    val hourlyCloseIds: kotlinx.coroutines.flow.StateFlow<Set<Long>> get() = hourlyCloseStore.ids

    /**
     * Was in der Zeile von [watch] nicht von seiner Börse kommt (%-Änderung aus fremden Kerzen, Mini-Chart einer
     * anderen Börse) — für den kurzen Hinweis in der Zeile ([WatchSparklines.rowSource]).
     */
    fun rowSource(watch: WatchEntity): com.cryptochecker.app.domain.watch.DayChange.RowSource? = sparklines.rowSource(watch)

    /** Börse des Mini-Charts von [watch], falls nicht die eigene ([WatchSparklines.foreignSparklineSource]). */
    fun foreignSparklineSource(watch: WatchEntity): String? = sparklines.foreignSparklineSource(watch)

    /** Börse der Kerzen hinter der Änderung von [watch], falls nicht die eigene ([WatchSparklines.foreignCandleSource]). */
    fun foreignCandleSource(watch: WatchEntity): String? = sparklines.foreignCandleSource(watch)

    /** Zwischengespeicherter 24-Stunden-Verlauf, ohne Netz ([WatchSparklines.cached]). */
    fun cachedSparkline(watch: WatchEntity): List<Double>? = sparklines.cached(watch)

    /** 24-Stunden-Verlauf eines Assets; null bei Fehler oder fremdem Token ([WatchSparklines.load]). */
    suspend fun sparkline(watch: WatchEntity): List<Double>? = sparklines.load(watch)

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
    suspend fun explain(watch: WatchEntity, refresh: Boolean = false): WhyReport? =
        runCatching { activityMonitor.explain(watch, refresh) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull()

    /**
     * Funding Rate und Open Interest: Perpetuals aus dem eigenen Kontrakt, alle anderen Krypto-Paare
     * (Spot, Termin-Futures) aus dem USDT-Perpetual des Coins (Binance, sonst Bybit, OKX); TradFi-Paare
     * ohne Perpetual und DEX-Pools nie. null ohne Daten.
     */
    suspend fun fetchFutures(watch: WatchEntity): com.cryptochecker.app.domain.remote.FuturesInfo? =
        runCatching {
            kotlinx.coroutines.withContext(ioDispatcher) {
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
        return kotlinx.coroutines.withContext(ioDispatcher) {
            // Welcher CoinGecko-Treffer gemeint ist, entscheidet der Name (wie beim Portfolio-Kurs), nicht die Grösse
            val identity = com.cryptochecker.app.domain.spot.SpotMarket.Identity(
                ownName = tokenIdentityStore.pairName(watch.exchangeKey, watch.baseAsset),
                binanceName = tokenIdentityStore.binanceName(watch.baseAsset) ?: tokenIdentityStore.binanceName(symbol),
                otherNames = tokenIdentityStore.otherNames(watch.baseAsset),
            )
            spotDataSource.fetch(symbol, identity)
        }
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
