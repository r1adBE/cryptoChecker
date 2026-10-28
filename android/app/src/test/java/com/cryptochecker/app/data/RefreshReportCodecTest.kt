package com.cryptochecker.app.data

import com.cryptochecker.app.domain.refresh.ExchangeRefresh
import com.cryptochecker.app.domain.refresh.RefreshFailure
import com.cryptochecker.app.domain.refresh.RefreshReport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RefreshReportCodecTest {

    private val report = RefreshReport(
        at = 1_700_000_000_000L,
        totalMillis = 1_200,
        pairs = 3,
        networkMillis = 900,
        exchanges = listOf(
            ExchangeRefresh(
                name = "Binance", millis = 800, pairs = 3, updated = 2, failed = 1,
                bulkTried = true, bulkMillis = 500, bulkPrices = 2, singles = 1,
                reason = RefreshFailure.TIMEOUT, pausedUntil = 1_700_000_060_000L,
                pauseReason = RefreshFailure.RATE_LIMIT, detail = "Invalid symbol.",
            ),
        ),
        dbMillis = 40,
        effectsMillis = 20,
        alarms = 1,
        notifications = 2,
        widgetMillis = 10,
        waitMillis = 30_400,
        attempt = 2,
    )

    @Test
    fun roundTripKeepsAttempt() {
        assertEquals(report, RefreshReportCodec.decode(RefreshReportCodec.encode(report)))
    }

    @Test
    fun withoutAttemptNotWritten() {
        val plain = report.copy(attempt = null, waitMillis = null)
        val json = JSONObject(RefreshReportCodec.encode(plain))
        assertFalse(json.has("attempt"))
        assertFalse(json.has("wait"))
        assertEquals(plain, RefreshReportCodec.decode(json.toString()))
    }

    @Test
    fun olderReportWithoutAttemptStillReadable() {
        // Gespeichert von einer Fassung ohne Versuchszahl: Wartezeit bleibt, Versuch unbekannt (kein Zusatz)
        val old = """{"at":5,"total":100,"pairs":1,"network":80,"exchanges":[],""" +
            """"alarms":0,"notifications":0,"wait":30000}"""
        val decoded = RefreshReportCodec.decode(old)
        assertEquals(30_000L, decoded?.waitMillis)
        assertNull(decoded?.attempt)
    }

    @Test
    fun unreadableIsNoReport() {
        assertNull(RefreshReportCodec.decode(null))
        assertNull(RefreshReportCodec.decode(""))
        assertNull(RefreshReportCodec.decode("{kaputt"))
    }
}
