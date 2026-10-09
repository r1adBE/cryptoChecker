package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.domain.activity.ActivityAnalyzer
import com.cryptochecker.app.domain.activity.HourCandle
import com.cryptochecker.app.domain.activity.WhyInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NotTradedTest {

    private data class Item(val id: Long, val error: String?)

    @Test
    fun marker_onlyExactText() {
        assertTrue(NotTraded.isMarker(NotTraded.MARKER))
        assertFalse(NotTraded.isMarker(null))
        assertFalse(NotTraded.isMarker("NETWORK_OFFLINE"))
        assertFalse(NotTraded.isMarker("wird an der börse nicht mehr gehandelt"))
    }

    @Test
    fun shownChange_hiddenForNotTraded() {
        assertNull(NotTraded.shownChange24h(NotTraded.MARKER, 13.9))
        assertEquals(13.9, NotTraded.shownChange24h(null, 13.9)!!, 0.0)
        // Andere Fehler (z. B. offline): letzter Wert bleibt sichtbar wie bisher
        assertEquals(-2.0, NotTraded.shownChange24h("NETWORK_OFFLINE", -2.0)!!, 0.0)
        assertNull(NotTraded.shownChange24h(null, null))
    }

    @Test
    fun ids_andWithoutIds() {
        val items = listOf(Item(1, null), Item(2, NotTraded.MARKER), Item(3, "x"), Item(4, NotTraded.MARKER))
        val ids = NotTraded.ids(items, { it.id }, { it.error })
        assertEquals(setOf(2L, 4L), ids)

        val signals = mapOf(1L to "a", 2L to "b", 3L to "c")
        assertEquals(mapOf(1L to "a", 3L to "c"), NotTraded.withoutIds(signals, ids))
        // Nichts zu entfernen: dieselbe Map (kein Neuaufbau)
        val clean = mapOf(1L to "a")
        assertSame(clean, NotTraded.withoutIds(clean, ids))
        assertSame(signals, NotTraded.withoutIds(signals, emptySet()))
    }

    @Test
    fun explain_notLiveGivesNoVerdict() {
        val h = ActivityAnalyzer.HOUR_MILLIS
        val now = 1_000L * h
        // Frische Kerzen mit kräftiger Bewegung und Umsatzsprung — trotzdem kein Urteil
        val candles = (0 until 48).map { i ->
            val close = if (i == 47) 113.9 else 100.0 + (i % 2) * 0.1
            HourCandle(openTime = now - (47 - i) * h, open = 100.0, high = close, low = 99.0, close = close,
                volume = if (i == 47) 3360.0 else 1000.0)
        }
        val input = WhyInput(
            baseAsset = "BOND",
            candles = candles,
            referenceCandles = candles,
            fundingPercent = 0.0,
            openInterestChangePercent = null,
            fearGreed = 50,
            fearGreedYesterday = 50,
            now = now,
            tickerChange24h = 13.9,
            marketLive = false,
        )
        val report = ActivityAnalyzer.explain(input)
        assertFalse(report.hasMarketData)
        assertTrue(report.reasons.isEmpty())
        assertNull(report.price)
        assertNull(report.change1h)
        assertNull(report.change24h)
        assertEquals(now, report.dataTime)

        // Gegenprobe: gehandelt → Gründe vorhanden
        val live = ActivityAnalyzer.explain(input.copy(marketLive = true))
        assertTrue(live.hasMarketData)
        assertTrue(live.reasons.isNotEmpty())
    }
}
