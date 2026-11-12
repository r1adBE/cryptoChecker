package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.domain.ExchangeRepository
import com.cryptochecker.app.domain.alarm.AlarmCardStatus
import com.cryptochecker.app.domain.model.BulkTickers
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.domain.model.ExchangePairsInfo
import com.cryptochecker.app.domain.model.ExchangeTickerResult
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.portfolio.PortfolioExchangeRoute
import com.cryptochecker.app.domain.refresh.BulkDelistedStore
import com.cryptochecker.app.domain.refresh.PriceFetcher
import com.cryptochecker.app.domain.refresh.StoredError
import com.cryptochecker.app.domain.util.KeyValueStore
import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import com.cryptochecker.marketdata.config.ExchangeRetirement
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.exchange.DexPool
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stillgelegte Börsen ([ExchangeRetirement], Bitpanda ab 1. Februar 2027 00:00 UTC): gemeinsame Testfälle
 * `exchange_retirement.json` (iOS: `ExchangeRetirementTests.swift`) — Uhr vor/nach dem Zeitpunkt, Marker, Hinweis — und
 * was daraus folgt: der Abruf fragt nichts mehr (jedes Paar mit Marker, «nicht gehandelt»), der Marker bleibt beim
 * Speichern, Alarme ruhen mit Grund, das Portfolio fragt die Börse nicht mehr.
 */
class ExchangeRetirementParityTest {

    private val fixture = ParityJson.load("exchange_retirement.json")
    private val end = ExchangeRetirement.BITPANDA_END_MILLIS

    @After
    fun resetClock() = ExchangeRetirement.resetClock()

    @Test
    fun constantsAsFixture() {
        assertEquals(fixture["endMillis"].num()!!.toLong(), end)
        assertEquals(fixture["markerPrefix"] as String, ExchangeRetirement.MARKER_PREFIX)
        // 1. Februar 2027 00:00:00 UTC
        assertEquals(java.time.Instant.parse("2027-02-01T00:00:00Z").toEpochMilli(), end)
    }

    @Test
    fun retiredAsFixture() {
        val cases = fixture["retired"].list().map { it.obj() }
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val now = c["now"].num()!!.toLong()
            assertEquals(c["name"] as String, c["expected"] as Boolean, ExchangeRetirement.isRetired(c["exchange"] as String, now))
        }
    }

    @Test
    fun markersAsFixture() {
        for (c in fixture["markers"].list().map { it.obj() }) {
            val name = c["name"] as String
            val error = c["lastError"] as String?
            assertEquals(name, c["exchange"] as String?, ExchangeRetirement.exchangeOf(error))
            assertEquals("$name › notTraded", c["notTraded"] as Boolean, NotTraded.isMarker(error))
            assertEquals("$name › askSingle", c["askSingle"] as Boolean, NotTraded.askSingleWhenMissing(error))
        }
        assertEquals("Exchange retired: Bitpanda", ExchangeRetirement.marker(" Bitpanda "))
    }

    @Test
    fun noticeAsFixture() {
        for (c in fixture["notice"].list().map { it.obj() }) {
            val got = ExchangeRetirement.noticeExchanges(
                c["exchanges"].list().map { it as String },
                c["dismissed"].list().map { it as String }.toSet(),
                c["now"].num()!!.toLong(),
            )
            assertEquals(c["name"] as String, c["expected"].list(), got)
        }
    }

    /** Die eine Regel «stillgelegt»: Marker ODER Ende erreicht. */
    @Test
    fun retiredNowAsFixture() {
        val cases = fixture["retiredNow"].list().map { it.obj() }
        assertTrue(cases.size >= 8)
        for (c in cases) {
            val got = ExchangeRetirement.retiredNow(c["exchange"] as String?, c["lastError"] as String?, c["now"].num()!!.toLong())
            assertEquals(c["name"] as String, c["expected"] as String?, got)
        }
    }

    /** Ankündigung ab dem 1. Januar 2027 bis vor dem Ende, mit eigenem ✕. */
    @Test
    fun announcementAsFixture() {
        assertEquals(fixture["noticeMillis"].num()!!.toLong(), ExchangeRetirement.BITPANDA_NOTICE_MILLIS)
        assertEquals(java.time.Instant.parse("2027-01-01T00:00:00Z").toEpochMilli(), ExchangeRetirement.BITPANDA_NOTICE_MILLIS)
        for (c in fixture["announced"].list().map { it.obj() }) {
            assertEquals(
                c["name"] as String, c["expected"] as Boolean,
                ExchangeRetirement.isAnnounced(c["exchange"] as String, c["now"].num()!!.toLong()),
            )
        }
        for (c in fixture["advanceNotice"].list().map { it.obj() }) {
            val got = ExchangeRetirement.advanceNoticeExchanges(
                c["exchanges"].list().map { it as String },
                c["dismissed"].list().map { it as String }.toSet(),
                c["now"].num()!!.toLong(),
            )
            assertEquals(c["name"] as String, c["expected"].list(), got)
        }
        assertEquals("bitpanda#advance", ExchangeRetirement.advanceKey(" Bitpanda "))
    }

    /** Anzeige nach der einen Regel: ohne alten Kurs ab dem Ende — auch ohne Marker (offline, deaktiviert) — oder mit Marker. */
    @Test
    fun shownWithoutOldPrice() {
        val priced = watch(1).copy(lastPrice = 80_000.0, change24h = 1.5)
        assertEquals(80_000.0, RetiredWatch.shown(priced, end - 1).lastPrice!!, 0.0)
        assertNull(RetiredWatch.exchangeOf(priced, end - 1))
        val off = RetiredWatch.shown(priced.copy(disabled = true), end)
        assertNull(off.lastPrice)
        assertNull(off.change24h)
        assertEquals("Exchange retired: Bitpanda", off.lastError)
        assertTrue(off.isNotTraded)
        // Uhr zurückgestellt: der Marker gilt, bis ein Abruf wieder einen Kurs bringt
        val marked = priced.copy(lastError = ExchangeRetirement.marker("Bitpanda"))
        assertEquals("Bitpanda", RetiredWatch.exchangeOf(marked, end - 86_400_000))
        assertNull(RetiredWatch.shown(marked, end - 86_400_000).lastPrice)
    }

    /** Android lädt weder Alarm-Zusatzdaten noch Tages-Bezüge für stillgelegte bzw. nicht gehandelte Paare (F2-05). */
    @Test
    fun noPrefetchForRetiredPairs() {
        val source = sequenceOf("", "../", "android/")
            .map { java.io.File(it + "domain/src/main/java/com/cryptochecker/app/domain/refresh/PriceRefresher.kt") }
            .first { it.isFile }.readText()
        assertTrue(source.contains("val traded = watches.filterNot { it.isNotTraded || RetiredWatch.exchangeOf(it) != null }"))
        assertTrue(source.contains("val earlyWatches = if (dayStart != null) traded else traded.filter {"))
        assertTrue(source.contains("traded.filterNot { ExchangeBackoff.isPaused(prefetchBackoff[it.exchangeKey], startedAt) }"))
    }

    @Test
    fun clockIsReplaceable() {
        ExchangeRetirement.clock = { end - 1 }
        assertFalse(ExchangeRetirement.isRetired(ExchangeRetirement.BITPANDA))
        ExchangeRetirement.clock = { end }
        assertTrue(ExchangeRetirement.isRetired(ExchangeRetirement.BITPANDA))
    }

    /** Nach dem Ende: keine einzige Anfrage, jedes Paar «nicht gehandelt» mit dem Marker, kein Fehler im Bericht. */
    @Test
    fun refreshSkipsRetiredExchange() = runBlocking {
        ExchangeRetirement.clock = { end + 1 }
        val repo = CountingRepository()
        val fetcher = PriceFetcher(repo, BulkDelistedStore { MemoryStore() })
        val group = (1L..4L).map { watch(it, error = if (it == 4L) NotTraded.MARKER else null) }
        val (results, signals) = fetcher.fetchGroup(group, includeRollingFutures = false)
        assertEquals(0, repo.calls)
        assertEquals(group.map { it.id }.toSet(), results.keys)
        for (fetched in results.values) {
            assertNull(fetched.ticker)
            assertTrue(fetched.notTraded)
            assertEquals("Exchange retired: Bitpanda", fetched.error)
        }
        assertEquals(4, signals.report.notTraded)
        assertEquals(0, signals.report.failed)
        assertEquals(0, signals.report.updated)
        assertTrue(signals.failures.isEmpty())
        // Gespeichert bleibt der Marker samt Name (Anzeige, Widgets, Uhr lesen ihn)
        assertEquals("Exchange retired: Bitpanda", StoredError.sanitize("Exchange retired: Bitpanda"))
    }

    /** Vor dem Ende fragt der Abruf Bitpanda wie bisher (Sammelabfrage). */
    @Test
    fun refreshAsksBeforeTheEnd() = runBlocking {
        ExchangeRetirement.clock = { end - 1 }
        val repo = CountingRepository()
        val fetcher = PriceFetcher(repo, BulkDelistedStore { MemoryStore() })
        fetcher.fetchGroup((1L..4L).map { watch(it) }, includeRollingFutures = false)
        assertTrue(repo.calls > 0)
    }

    @Test
    fun alarmsRestWithTheReason() {
        val kind = AlarmCardStatus.of(
            alarmEnabled = true, notificationsAllowed = false, dexBlocksAlarms = true, exchangeTime = null,
            lastUpdate = 0L, now = end, staleAfterMillis = 1L, unchecked = true, exchangeRetired = true,
        )
        assertEquals(AlarmCardStatus.Kind.EXCHANGE_RETIRED, kind)
        // Ausgeschaltet: wie jeder Alarm ohne Zeile
        assertNull(
            AlarmCardStatus.of(
                alarmEnabled = false, notificationsAllowed = true, dexBlocksAlarms = false, exchangeTime = null,
                lastUpdate = 0L, now = end, staleAfterMillis = 1L, unchecked = false, exchangeRetired = true,
            ),
        )
    }

    @Test
    fun portfolioNoLongerAsksRetiredExchange() {
        val exchanges = mapOf("Bitpanda" to "Bitpanda", "Kraken" to "Kraken")
        ExchangeRetirement.clock = { end - 1 }
        assertEquals("Bitpanda" to "Bitpanda", PortfolioExchangeRoute.exchange("Bitpanda", exchanges))
        ExchangeRetirement.clock = { end }
        assertNull(PortfolioExchangeRoute.exchange("Bitpanda", exchanges))
        assertEquals("Kraken" to "Kraken", PortfolioExchangeRoute.exchange("kraken", exchanges))
        // Hinweis wie ein Abruffehler: «Bitpanda liefert keine Kurse mehr – mit Binance bewertet»
        assertEquals("Bitpanda", PortfolioExchangeRoute.retiredExchange(" bitpanda ", exchanges))
        assertNull(PortfolioExchangeRoute.retiredExchange("Kraken", exchanges))
        assertNull(PortfolioExchangeRoute.retiredExchange("Freitext", exchanges))
        ExchangeRetirement.clock = { end - 1 }
        assertNull(PortfolioExchangeRoute.retiredExchange("Bitpanda", exchanges))
    }

    private fun watch(id: Long, error: String? = null) = WatchEntity(
        id = id, exchangeKey = "Bitpanda", exchangeName = "Bitpanda", baseAsset = "C$id", quoteAsset = "EUR",
        pairId = "C${id}_EUR", lastError = error,
    )

    /** Zählt jede Anfrage an die Börse; Antworten leer. */
    private class CountingRepository : ExchangeRepository {
        var calls = 0
        override suspend fun getExchangeList(): List<ExchangeInfo> = emptyList()
        override suspend fun getExchangeCurrencyPairsInfo(exchange: ExchangeInfo, withAlpha: Boolean) =
            ExchangePairsInfo().also { calls++ }
        override fun isExchangeSupportsUpdatePairs(exchange: ExchangeInfo) = false
        override suspend fun getExchangeTicker(exchange: ExchangeInfo, pairInfo: ExchangePair): ExchangeTickerResult {
            calls++
            error("no ticker in test")
        }
        override fun isExchangeSupportsBulkTickers(exchange: ExchangeInfo) = true
        override suspend fun getBulkTickers(exchange: ExchangeInfo, pairIds: Collection<String>): BulkTickers {
            calls++
            return BulkTickers(complete = false, error = "HTTP 503")
        }
        override suspend fun updateExchangeCurrencyPairs(exchange: ExchangeInfo) { calls++ }
        override suspend fun searchDexPools(query: String): List<DexPool> = emptyList()
    }

    /** Leere Ablage (nichts gemerkt, Schreiben ohne Wirkung). */
    private class MemoryStore : KeyValueStore {
        override fun getInt(key: String, default: Int) = default
        override fun getLong(key: String, default: Long) = default
        override fun getBoolean(key: String, default: Boolean) = default
        override fun getString(key: String): String? = null
        override fun contains(key: String) = false
        override fun all(): Map<String, Any?> = emptyMap()
        override fun edit(block: KeyValueStore.Editor.() -> Unit) = Unit
    }
}
