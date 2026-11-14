package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.alarm.AlarmFreshness
import com.cryptochecker.app.domain.refresh.RefreshFixture.Companion.above
import com.cryptochecker.app.domain.refresh.RefreshFixture.Companion.watch
import com.cryptochecker.app.domain.watch.DexLiquidity
import com.cryptochecker.marketdata.model.SimpleTicker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Letzter Schritt der Aktualisierung ([RefreshResultProcessor.process]) mit Fakes: Kurs wird gespeichert, aber
 * Alarme ruhen bei DEX-Pools mit zu wenig Liquidität und bei einem laut Börse zu alten Kurs; Fehler und unmögliche
 * Kurse werden als Fehler gespeichert. Die Alarm-Prüfung selbst ist die echte ([RefreshEffects]).
 */
class RefreshResultProcessorTest {

    private val hour = 60 * 60_000L

    private fun ticker(price: Double, exchangeTime: Long? = null, liquidityUsd: Double? = null) = SimpleTicker().apply {
        last = price
        timestamp = System.currentTimeMillis()
        this.exchangeTime = exchangeTime
        this.liquidityUsd = liquidityUsd
    }

    /** Ein Durchlauf unter der echten Sperre (der Beweis kommt nur von [RefreshLock]). */
    private fun RefreshFixture.process(fetched: Map<Long, Fetched>, live: Boolean = false): RefreshResultProcessor.Processed =
        runBlocking {
            val watches: List<WatchEntity> = store.getWatches()
            lock.withLock { proof -> processor.process(proof, watches, fetched, settings.settings, dayStart = null, live = live) }
        }

    @Test
    fun crossingPriceIsWrittenAndFiresTheAlarm() {
        val f = RefreshFixture(listOf(watch(price = 99.0)), listOf(above(100.0)))

        val processed = f.process(mapOf(1L to Fetched(ticker(101.0), null)))

        assertEquals(1, processed.alarms)
        assertEquals(101.0, f.store.watches.getValue(1).lastPrice!!, 0.0)
        assertEquals(1, f.notifier.alarmPosts.size)
    }

    @Test
    fun dexPoolWithLowLiquidityWritesPriceButHoldsAlarms() {
        val pool = "solana/pool1"
        val f = RefreshFixture(
            listOf(watch(price = 99.0, exchangeKey = DexLiquidity.EXCHANGE_KEY, pairId = pool)),
            listOf(above(100.0)),
        )
        f.dex.blocked = setOf(pool)

        val processed = f.process(mapOf(1L to Fetched(ticker(101.0, liquidityUsd = 500.0), null)))

        assertEquals(0, processed.alarms)
        assertTrue(f.notifier.alarmPosts.isEmpty())
        // Kurs trotzdem gespeichert, Liquidität gemerkt, Alarm scharf und unverändert
        assertEquals(101.0, f.store.watches.getValue(1).lastPrice!!, 0.0)
        assertEquals(500.0, f.dex.measured[pool]!!, 0.0)
        assertEquals(0L, f.store.alarm(7).referenceAt)
        assertTrue(f.store.alarm(7).enabled)

        // Genug Liquidität gemessen: der noch scharfe Alarm meldet beim nächsten Kurs
        f.dex.blocked = emptySet()
        assertEquals(1, f.process(mapOf(1L to Fetched(ticker(102.0, liquidityUsd = 1_000_000.0), null))).alarms)
        assertEquals(1, f.notifier.alarmPosts.size)
    }

    @Test
    fun staleExchangeTimeHoldsAlarms() {
        val f = RefreshFixture(listOf(watch(price = 99.0)), listOf(above(100.0)))
        val now = System.currentTimeMillis()

        val stale = f.process(mapOf(1L to Fetched(ticker(101.0, exchangeTime = now - AlarmFreshness.EXCHANGE_STALE_MILLIS - hour), null)))

        assertEquals(0, stale.alarms)
        assertTrue(f.notifier.alarmPosts.isEmpty())
        assertEquals(101.0, f.store.watches.getValue(1).lastPrice!!, 0.0)
        assertEquals(0L, f.store.alarm(7).referenceAt)

        // Frischer Kurs laut Börse: der Alarm meldet
        val fresh = f.process(mapOf(1L to Fetched(ticker(101.5, exchangeTime = System.currentTimeMillis() - 60_000L), null)))
        assertEquals(1, fresh.alarms)
        assertEquals(1, f.notifier.alarmPosts.size)
    }

    @Test
    fun markPriceFallbackWritesPriceButHoldsAlarms() {
        val f = RefreshFixture(listOf(watch(price = 99.0, exchangeKey = "Hyperliquid")), listOf(above(100.0)))

        // Kein Mittelkurs (dünnes Orderbuch): Kurs = Mark-Preis, gespeichert und gemerkt, aber kein Alarm
        val mark = f.process(mapOf(1L to Fetched(ticker(101.0).apply { markFallback = true }, null)))

        assertEquals(0, mark.alarms)
        assertTrue(f.notifier.alarmPosts.isEmpty())
        assertEquals(101.0, f.store.watches.getValue(1).lastPrice!!, 0.0)
        assertEquals(true, f.markFallback[1L])
        assertTrue(f.store.alarm(7).enabled)

        // Wieder ein Mittelkurs: der noch scharfe Alarm meldet, der Merker fällt weg
        val mid = f.process(mapOf(1L to Fetched(ticker(101.5), null)))
        assertEquals(1, mid.alarms)
        assertEquals(false, f.markFallback[1L])
    }

    /** Stundenkurs (WOO X Spot ohne letzten Abschluss): je Paar gemerkt, die nächste Abfrage setzt ihn neu. */
    @Test
    fun hourlyPriceIsRememberedUntilTheNextFetch() {
        val f = RefreshFixture(listOf(watch(price = 99.0, exchangeKey = "Woo")), emptyList())
        f.process(mapOf(1L to Fetched(ticker(100.0).apply { hourlyClose = true }, null)))
        assertEquals(true, f.hourlyClose[1L])
        f.process(mapOf(1L to Fetched(ticker(100.5), null)))
        assertEquals(false, f.hourlyClose[1L])
    }

    @Test
    fun failedOrImpossiblePricesAreStoredAsErrorsWithoutAlarms() {
        val f = RefreshFixture(
            listOf(watch(id = 1, price = 99.0), watch(id = 2, price = 99.0), watch(id = 3, price = 99.0)),
            listOf(above(100.0, id = 7, watchId = 1), above(100.0, id = 8, watchId = 2), above(100.0, id = 9, watchId = 3)),
        )

        val processed = f.process(
            mapOf(
                1L to Fetched(null, "HttpCode: 500"),
                2L to Fetched(ticker(Double.POSITIVE_INFINITY), null),
                3L to Fetched(ticker(Double.NaN), null),
            ),
        )

        assertEquals(3, processed.failed)
        assertEquals(0, processed.alarms)
        assertEquals(listOf(1L, 2L, 3L), f.store.errorWrites.map { it.id })
        assertEquals("HttpCode: 500", f.store.watches.getValue(1).lastError)
        // Kein unmöglicher Kurs übernommen (∞ hätte «über» ausgelöst)
        assertEquals(99.0, f.store.watches.getValue(2).lastPrice!!, 0.0)
        assertTrue(f.notifier.alarmPosts.isEmpty())
    }

    /** Nicht gehandelt bzw. Börse stillgelegt: weder «geprüft» noch «fehlgeschlagen» (F2-01). */
    @Test
    fun notTradedPairsCountNeitherAsCheckedNorAsFailed() {
        val f = RefreshFixture(listOf(watch(id = 1, price = 99.0), watch(id = 2, price = 99.0), watch(id = 3, price = 99.0)))

        val processed = f.process(
            mapOf(
                1L to Fetched(ticker(100.0), null),
                2L to Fetched(null, "Exchange retired: Bitpanda", notTraded = true),
                3L to Fetched(null, "Wird an der Börse nicht mehr gehandelt", notTraded = true),
            ),
        )

        assertEquals(1, processed.checked)
        assertEquals(0, processed.failed)
        assertEquals(2, processed.notTraded)
        // Der Marker wird trotzdem gespeichert (Grund in der Anzeige)
        assertEquals("Exchange retired: Bitpanda", f.store.watches.getValue(2).lastError)
        assertTrue(BackgroundSchedule.isFullCheck(processed.checked, processed.failed, processed.notTraded))
    }

    /**
     * Kurs-Meldung (N3-01): ein gewöhnlicher Fehler (Zeit, 5xx) lässt sie stehen; entfernt wird sie nur, wenn das Paar
     * nicht mehr gehandelt wird bzw. die Börse stillgelegt ist. iOS `PriceRefresher.evaluate` gleich.
     */
    @Test
    fun ordinaryFailureKeepsThePriceNotification() {
        val f = RefreshFixture(listOf(watch(id = 1, price = 99.0), watch(id = 2, price = 99.0), watch(id = 3, price = 99.0)))

        f.process(
            mapOf(
                1L to Fetched(null, "timeout"),
                2L to Fetched(null, "HttpCode: 503"),
                3L to Fetched(null, "Wird an der Börse nicht mehr gehandelt", notTraded = true),
            ),
        )

        assertEquals(listOf(3L), f.notifier.cancelledPrices.toList())
    }

    /** Alle Paare stillgelegt (Portfolio-Alarm aktiv): der Lauf zählt als volle Prüfung und als Aktualisierung. */
    @Test
    fun allPairsRetiredStillCountsAsFullCheck() {
        val f = RefreshFixture(listOf(watch(id = 1, price = 99.0), watch(id = 2, price = 99.0)))

        val processed = f.process(
            mapOf(
                1L to Fetched(null, "Exchange retired: Bitpanda", notTraded = true),
                2L to Fetched(null, "Exchange retired: Bitpanda", notTraded = true),
            ),
        )

        assertEquals(0, processed.checked)
        assertEquals(0, processed.failed)
        assertEquals(CheckResult.OK, CheckRunLog.result(processed.checked, processed.failed))
        assertTrue(BackgroundSchedule.isFullCheck(processed.checked, processed.failed, processed.notTraded))
        assertTrue(BackgroundSchedule.refreshed(processed.checked, processed.failed, processed.notTraded))
    }

    @Test
    fun liveQuotesDoNotMeasureDexLiquidity() {
        val pool = "solana/pool1"
        val f = RefreshFixture(listOf(watch(price = 99.0, exchangeKey = DexLiquidity.EXCHANGE_KEY, pairId = pool)))

        f.process(mapOf(1L to Fetched(ticker(101.0, liquidityUsd = 500.0), null)), live = true)

        assertTrue("Live-Takt kennt keine Liquidität", f.dex.measured.isEmpty())
        assertEquals(101.0, f.store.watches.getValue(1).lastPrice!!, 0.0)
    }
}
