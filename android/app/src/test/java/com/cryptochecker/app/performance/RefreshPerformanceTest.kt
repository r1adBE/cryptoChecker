package com.cryptochecker.app.performance

import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.alarm.AlarmEvaluator
import com.cryptochecker.app.domain.refresh.Fetched
import com.cryptochecker.app.domain.refresh.RefreshFixture
import com.cryptochecker.app.domain.refresh.RefreshResultProcessor
import com.cryptochecker.app.performance.PerformanceTiming.assertLimitOnly
import com.cryptochecker.app.performance.PerformanceTiming.assertScales
import com.cryptochecker.app.performance.PerformanceTiming.medianMillis
import com.cryptochecker.marketdata.config.MarketsConfig
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.util.BulkTickerRows
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leistung einer Aktualisierung mit bis zu 2000 Paaren an einer grossen Börse, ohne Netz und Datenbank: Sammelantwort
 * von Binance lesen, Kurse den Paaren zuordnen, speichern (Fake-Speicher) und Alarme prüfen (echte
 * [RefreshResultProcessor] und `RefreshEffects` über [RefreshFixture]), dazu die reine Alarm-Regel. Grenzen und
 * Skalierung: [PerformanceTiming]. iOS: RefreshPerformanceTests.swift.
 */
class RefreshPerformanceTest {

    private val binance = MarketsConfig.MARKETS.getValue(PerformanceData.BINANCE.first)

    private fun binanceWatches(n: Int): List<WatchEntity> = PerformanceData.watches(n, PerformanceData.BINANCE)

    private fun parse(body: String): Map<String, Ticker> = binance.parseBulkTickersDetailed(0, body).tickers

    /** Kurse den Paaren zuordnen wie `PriceFetcher.fetchGroup` (Kennung → Zeile; unlesbare Zeile = Fehler). */
    private fun assign(watches: List<WatchEntity>, tickers: Map<String, Ticker>): Map<Long, Fetched> =
        watches.associate { w ->
            val ticker = w.pairId?.let { tickers[it] }
            w.id to if (ticker != null) Fetched(ticker, null) else Fetched(null, BulkTickerRows.ROW_ERROR)
        }

    /** Ein Durchlauf von [RefreshResultProcessor.process] unter der echten Sperre. */
    private fun RefreshFixture.process(watches: List<WatchEntity>, fetched: Map<Long, Fetched>) = runBlocking {
        lock.withLock { proof -> processor.process(proof, watches, fetched, settings.settings, dayStart = null) }
    }

    @Test
    fun binanceBulkResponseParses() {
        // Rund 1,3 MB JSON mit 2000 Zeilen: erwartet einige 10 ms
        assertScales("Sammelantwort Binance lesen", limitMillis = 1_000.0) { n ->
            val body = PerformanceData.binanceBulkBody(PerformanceData.newPrices(binanceWatches(n)))
            medianMillis { binance.parseBulkTickersDetailed(0, body) }
        }
        val watches = binanceWatches(PerformanceTiming.LARGE)
        val prices = PerformanceData.newPrices(watches)
        val parsed = binance.parseBulkTickersDetailed(0, PerformanceData.binanceBulkBody(prices))
        assertEquals(PerformanceTiming.LARGE, parsed.tickers.size)
        assertTrue(parsed.failed.isEmpty())
        val btc = prices.getValue("BTCUSDT")
        // Acht Nachkommastellen wie bei Binance
        assertEquals(btc, parsed.tickers.getValue("BTCUSDT").last, 1e-8 + btc * 1e-12)
    }

    @Test
    fun assignSaveAndCheckAlarms() {
        // Je Paar: Zuordnen, Frische, Speichern (Fake), 1–3 Alarme prüfen, Protokoll — erwartet um 50 ms
        assertScales("Kurse zuordnen, speichern und Alarme prüfen", limitMillis = 1_500.0) { n ->
            val watches = binanceWatches(n)
            val alarms = PerformanceData.alarms(watches)
            val body = PerformanceData.binanceBulkBody(PerformanceData.newPrices(watches))
            medianMillis({ RefreshFixture(watches, alarms) to parse(body) }) { (fixture, tickers) ->
                fixture.process(watches, assign(watches, tickers))
            }
        }
        val watches = binanceWatches(PerformanceTiming.LARGE)
        val alarms = PerformanceData.alarms(watches)
        val fixture = RefreshFixture(watches, alarms)
        val tickers = parse(PerformanceData.binanceBulkBody(PerformanceData.newPrices(watches)))
        val processed = fixture.process(watches, assign(watches, tickers))
        assertEquals(PerformanceTiming.LARGE, processed.checked)
        assertEquals(0, processed.failed)
        // Etwa jedes hundertste Paar hat eine schon überschrittene Kursmarke; Prozent-Alarme ohne Bezug bekommen einen
        assertTrue("ausgelöst: ${processed.alarms}", processed.alarms in 10..100)
        assertTrue(fixture.store.references.isNotEmpty())
        assertEquals(PerformanceTiming.LARGE, fixture.store.refreshWrites.single().size)
    }

    @Test
    fun alarmRuleOverAllAlarms() {
        val evaluator = AlarmEvaluator()
        val now = PerformanceData.UPDATED_AT + 60_000L
        assertScales("Alarm-Regel über alle Alarme", limitMillis = 100.0) { n ->
            val watches = binanceWatches(n)
            val alarms = PerformanceData.alarms(watches)
            val prices = PerformanceData.newPrices(watches)
            medianMillis { due(evaluator, watches, alarms, prices, now) }
        }
    }

    @Test
    fun alarmsWithoutExchangeRate() {
        // Kursmarken in EUR ohne Devisenkurs: jeder dieser Alarme wird als «nicht geprüft» vermerkt
        // (`AlarmCheckStatus.markUnchecked`). Der Vermerk kopiert die ganze Karte je Alarm — wächst also mit der Zahl
        // der vermerkten Alarme quadratisch; darum hier nur die feste Grenze (Verhältnis im Protokoll).
        assertLimitOnly("Alarme ohne Devisenkurs (Vermerk «nicht geprüft»)", limitMillis = 5_000.0) { n ->
            val watches = binanceWatches(n)
            val alarms = PerformanceData.alarms(watches, currency = "EUR")
            val body = PerformanceData.binanceBulkBody(PerformanceData.newPrices(watches))
            medianMillis({ RefreshFixture(watches, alarms) to parse(body) }) { (fixture, tickers) ->
                fixture.process(watches, assign(watches, tickers))
            }
        }
        val watches = binanceWatches(PerformanceTiming.LARGE)
        val alarms = PerformanceData.alarms(watches, currency = "EUR")
        val fixture = RefreshFixture(watches, alarms)
        val body = PerformanceData.binanceBulkBody(PerformanceData.newPrices(watches))
        fixture.process(watches, assign(watches, parse(body)))
        // Alle Kursmarken ausser denen an EUR-Paaren (Faktor 1) bleiben ungeprüft
        val unchecked = alarms.count { it.currency != null && watches[(it.watchId - 1).toInt()].quoteAsset != "EUR" }
        assertEquals(unchecked, fixture.checkStatus.state.value.size)
    }

    /** Fällige Alarme und solche, die erst einen Bezug brauchen (wie die Schleife in `RefreshEffects.checkAlarms`). */
    private fun due(
        evaluator: AlarmEvaluator,
        watches: List<WatchEntity>,
        alarms: List<AlarmEntity>,
        prices: Map<String, Double>,
        now: Long,
    ): Int {
        val byWatch = alarms.groupBy { it.watchId }
        var count = 0
        for (w in watches) {
            val price = prices[w.pairId] ?: continue
            for (alarm in byWatch[w.id].orEmpty()) {
                if (evaluator.needsReference(alarm, now) ||
                    evaluator.shouldRearmLevel(alarm, price, now) ||
                    evaluator.shouldTrigger(alarm, price, w.lastPrice, now, cooldownMinutes = 15)
                ) count++
            }
        }
        return count
    }
}
