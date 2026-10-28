package com.cryptochecker.app.domain.refresh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefreshReportLogicTest {

    private fun exchange(
        name: String,
        pairs: Int,
        updated: Int,
        notTraded: Int = 0,
        failed: Int = pairs - updated - notTraded,
        millis: Long = 500,
    ) = ExchangeRefresh(name, millis, pairs, updated, notTraded, failed)

    private fun report(vararg exchanges: ExchangeRefresh, aborted: String? = null) = RefreshReport(
        at = 0, totalMillis = 1_800, pairs = exchanges.sumOf { it.pairs }, networkMillis = 1_700,
        exchanges = exchanges.toList(), aborted = aborted,
    )

    @Test
    fun statusGreenOrangeRed() {
        assertEquals(RefreshStatus.OK, RefreshReportLogic.status(exchange("A", 5, 5)))
        assertEquals(RefreshStatus.PARTIAL, RefreshReportLogic.status(exchange("A", 525, 522)))
        assertEquals(RefreshStatus.FAILED, RefreshReportLogic.status(exchange("A", 5, 0)))
        // Nur nicht gehandelte Paare: orange, nicht rot
        assertEquals(RefreshStatus.PARTIAL, RefreshReportLogic.status(exchange("A", 3, 0, notTraded = 3)))
        // Nichts aktualisiert, teils Fehler, teils nicht gehandelt: rot
        assertEquals(RefreshStatus.FAILED, RefreshReportLogic.status(exchange("A", 4, 0, notTraded = 1)))
        // Leere Börse: grün
        assertEquals(RefreshStatus.OK, RefreshReportLogic.status(exchange("A", 0, 0)))
    }

    @Test
    fun sortedRedOrangeGreenThenSlowestFirst() {
        val sorted = RefreshReportLogic.sorted(
            listOf(
                exchange("Green fast", 5, 5, millis = 100),
                exchange("Orange", 10, 9, millis = 50),
                exchange("Green slow", 5, 5, millis = 900),
                exchange("Red", 2, 0, millis = 10),
                exchange("Orange slow", 10, 8, millis = 700),
            )
        )
        assertEquals(listOf("Red", "Orange slow", "Orange", "Green slow", "Green fast"), sorted.map { it.name })
    }

    @Test
    fun overallIsWorst() {
        assertEquals(RefreshStatus.OK, RefreshReportLogic.overall(report()))
        assertEquals(RefreshStatus.OK, RefreshReportLogic.overall(report(exchange("A", 5, 5))))
        assertEquals(RefreshStatus.PARTIAL, RefreshReportLogic.overall(report(exchange("A", 5, 5), exchange("B", 5, 4))))
        assertEquals(RefreshStatus.FAILED, RefreshReportLogic.overall(report(exchange("A", 5, 4), exchange("B", 5, 0))))
        assertEquals(RefreshStatus.FAILED, RefreshReportLogic.overall(report(exchange("A", 5, 5), aborted = "IOException")))
    }

    @Test
    fun headlineChoice() {
        assertEquals(RefreshHeadline.AllUpdated, RefreshReportLogic.headline(report(exchange("A", 5, 5))))
        assertEquals(
            RefreshHeadline.PairsNotUpdated(3),
            RefreshReportLogic.headline(report(exchange("A", 5, 5), exchange("B", 525, 522)))
        )
        // Nicht gehandelte zählen als «nicht aktualisiert»
        assertEquals(
            RefreshHeadline.PairsNotUpdated(4),
            RefreshReportLogic.headline(report(exchange("A", 5, 4), exchange("B", 3, 0, notTraded = 3)))
        )
        assertEquals(
            RefreshHeadline.ExchangeUnreachable("Binance"),
            RefreshReportLogic.headline(report(exchange("Binance", 5, 0), exchange("B", 525, 522)))
        )
        assertEquals(
            RefreshHeadline.ExchangesUnreachable(2),
            RefreshReportLogic.headline(report(exchange("A", 5, 0), exchange("B", 2, 0)))
        )
        assertEquals(
            RefreshHeadline.Aborted,
            RefreshReportLogic.headline(report(exchange("A", 5, 5), aborted = "x"))
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
        assertEquals(RefreshFailure.UNAVAILABLE, RefreshReportLogic.classify("Exchange unavailable"))
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
