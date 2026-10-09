package com.cryptochecker.app.domain.refresh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefreshReportLogicTest {

    private fun market(
        name: String,
        pairs: Int,
        updated: Int,
        notTraded: Int = 0,
        failed: Int = pairs - updated - notTraded,
        millis: Long = 500,
    ) = MarketRefresh(name, millis, pairs, updated, notTraded, failed)

    private fun report(vararg markets: MarketRefresh, aborted: String? = null) = RefreshReport(
        at = 0, totalMillis = 1_800, pairs = markets.sumOf { it.pairs }, networkMillis = 1_700,
        markets = markets.toList(), aborted = aborted,
    )

    @Test
    fun statusGreenOrangeRed() {
        assertEquals(RefreshStatus.OK, RefreshReportLogic.status(market("A", 5, 5)))
        assertEquals(RefreshStatus.PARTIAL, RefreshReportLogic.status(market("A", 525, 522)))
        assertEquals(RefreshStatus.FAILED, RefreshReportLogic.status(market("A", 5, 0)))
        // Nur nicht gehandelte Paare: orange, nicht rot
        assertEquals(RefreshStatus.PARTIAL, RefreshReportLogic.status(market("A", 3, 0, notTraded = 3)))
        // Nichts aktualisiert, teils Fehler, teils nicht gehandelt: rot
        assertEquals(RefreshStatus.FAILED, RefreshReportLogic.status(market("A", 4, 0, notTraded = 1)))
        // Leere Börse: grün
        assertEquals(RefreshStatus.OK, RefreshReportLogic.status(market("A", 0, 0)))
    }

    @Test
    fun sortedRedOrangeGreenThenSlowestFirst() {
        val sorted = RefreshReportLogic.sorted(
            listOf(
                market("Green fast", 5, 5, millis = 100),
                market("Orange", 10, 9, millis = 50),
                market("Green slow", 5, 5, millis = 900),
                market("Red", 2, 0, millis = 10),
                market("Orange slow", 10, 8, millis = 700),
            )
        )
        assertEquals(listOf("Red", "Orange slow", "Orange", "Green slow", "Green fast"), sorted.map { it.name })
    }

    @Test
    fun overallIsWorst() {
        assertEquals(RefreshStatus.OK, RefreshReportLogic.overall(report()))
        assertEquals(RefreshStatus.OK, RefreshReportLogic.overall(report(market("A", 5, 5))))
        assertEquals(RefreshStatus.PARTIAL, RefreshReportLogic.overall(report(market("A", 5, 5), market("B", 5, 4))))
        assertEquals(RefreshStatus.FAILED, RefreshReportLogic.overall(report(market("A", 5, 4), market("B", 5, 0))))
        assertEquals(RefreshStatus.FAILED, RefreshReportLogic.overall(report(market("A", 5, 5), aborted = "IOException")))
    }

    @Test
    fun headlineChoice() {
        assertEquals(RefreshHeadline.AllUpdated, RefreshReportLogic.headline(report(market("A", 5, 5))))
        assertEquals(
            RefreshHeadline.PairsNotUpdated(3),
            RefreshReportLogic.headline(report(market("A", 5, 5), market("B", 525, 522)))
        )
        // Nicht gehandelte zählen als «nicht aktualisiert»
        assertEquals(
            RefreshHeadline.PairsNotUpdated(4),
            RefreshReportLogic.headline(report(market("A", 5, 4), market("B", 3, 0, notTraded = 3)))
        )
        assertEquals(
            RefreshHeadline.MarketUnreachable("Binance"),
            RefreshReportLogic.headline(report(market("Binance", 5, 0), market("B", 525, 522)))
        )
        assertEquals(
            RefreshHeadline.MarketsUnreachable(2),
            RefreshReportLogic.headline(report(market("A", 5, 0), market("B", 2, 0)))
        )
        assertEquals(
            RefreshHeadline.Aborted,
            RefreshReportLogic.headline(report(market("A", 5, 5), aborted = "x"))
        )
    }

    @Test
    fun classifyErrors() {
        assertEquals(RefreshFailure.TIMEOUT, RefreshReportLogic.classify("java.net.SocketTimeoutException: timeout"))
        assertEquals(RefreshFailure.TIMEOUT, RefreshReportLogic.classify("Request timed out"))
        assertEquals(RefreshFailure.OFFLINE, RefreshReportLogic.classify("java.net.UnknownHostException: Unable to resolve host"))
        assertEquals(RefreshFailure.OFFLINE, RefreshReportLogic.classify(RefreshReportLogic.OFFLINE_MARKER))
        assertEquals(RefreshFailure.RATE_LIMIT, RefreshReportLogic.classify("HttpCode: 429"))
        assertEquals(RefreshFailure.RATE_LIMIT, RefreshReportLogic.classify("HTTP 418: banned"))
        assertEquals(RefreshFailure.SERVER, RefreshReportLogic.classify("HTTP 503"))
        assertEquals(RefreshFailure.NO_DATA, RefreshReportLogic.classify("Parsed ticker has no data"))
        assertEquals(RefreshFailure.UNAVAILABLE, RefreshReportLogic.classify("Market unavailable"))
        assertEquals(RefreshFailure.OTHER, RefreshReportLogic.classify("Something odd"))
        assertEquals(RefreshFailure.OTHER, RefreshReportLogic.classify(null))
    }

    @Test
    fun reasonIsMostFrequent() {
        assertNull(RefreshReportLogic.reason(emptyList()))
        assertEquals(
            RefreshFailure.TIMEOUT,
            RefreshReportLogic.reason(listOf("timeout", "HTTP 500", "timed out"))
        )
        // Gleichstand: frühere Art (Zeitüberschreitung vor Serverfehler)
        assertEquals(RefreshFailure.TIMEOUT, RefreshReportLogic.reason(listOf("HTTP 500", "timeout")))
    }
}
