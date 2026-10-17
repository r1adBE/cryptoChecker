package com.cryptochecker.app.domain.market

import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.util.Change24h
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Umrechnung der 24-h-Angaben der Börsen in Prozent. */
class Change24hTest {

    private val eps = 1e-9

    @Test
    fun percentIsTakenAsIs() {
        assertEquals(1.24, Change24h.percent(1.24)!!, eps)
        assertEquals(-0.947, Change24h.percent(-0.947)!!, eps)
        assertEquals(0.0, Change24h.percent(0.0)!!, eps)
    }

    @Test
    fun fractionIsScaledToPercent() {
        // Bybit price24hPcnt "0.0068" = +0,68 %
        assertEquals(0.68, Change24h.fraction(0.0068)!!, eps)
        // KuCoin changeRate "-0.0055" = −0,55 %
        assertEquals(-0.55, Change24h.fraction(-0.0055)!!, eps)
    }

    @Test
    fun openGivesChangeFromPrice24hAgo() {
        // Bitstamp: last 26216, open_24 25895 → +1,2396 % (percent_change_24 = 1.24)
        assertEquals(1.2396215485615, Change24h.fromOpen(26216.0, 25895.0)!!, 1e-9)
        assertEquals(-50.0, Change24h.fromOpen(50.0, 100.0)!!, eps)
        assertEquals(0.0, Change24h.fromOpen(100.0, 100.0)!!, eps)
    }

    @Test
    fun absoluteChangeUsesOpenEqualLastMinusChange() {
        // Bitso: last 36599.54, change_24 −105.64 → open 36705.18 → −0,2878 %
        assertEquals(-105.64 / 36705.18 * 100.0, Change24h.fromAbsolute(36599.54, -105.64)!!, 1e-9)
    }

    @Test
    fun missingOrBrokenValuesGiveNull() {
        // optDouble liefert NaN für ein fehlendes Feld
        assertNull(Change24h.percent(Double.NaN))
        assertNull(Change24h.percent(Double.POSITIVE_INFINITY))
        assertNull(Change24h.fraction(Double.NaN))
        assertNull(Change24h.fromOpen(100.0, Double.NaN))
        assertNull(Change24h.fromOpen(100.0, 0.0))
        assertNull(Change24h.fromOpen(100.0, -1.0))   // NO_DATA
        assertNull(Change24h.fromOpen(-1.0, 100.0))   // letzter Kurs fehlt
        assertNull(Change24h.fromOpen(Double.NaN, 100.0))
        assertNull(Change24h.fromAbsolute(100.0, Double.NaN))
        assertNull(Change24h.fromAbsolute(100.0, 100.0)) // Eröffnung wäre 0
    }

    @Test
    fun tickerHasNoChangeByDefault() {
        assertNull(SimpleTicker().change24hPercent)
    }

    @Test
    fun hourlyWindowNeedsContiguousHours() {
        // WOO X Spot: 24 Stundenkerzen ohne Lücke → Start der ältesten; fehlt eine Stunde → kein Fenster (wie iOS)
        val h = Change24h.HOUR_MILLIS
        val full = (0 until 24).map { 1_000 * h - it * h }
        assertEquals(1_000 * h - 23 * h, Change24h.hourlyWindowStart(full.shuffled(java.util.Random(7))))
        // Ältere 25. Kerze zählt nicht: nur die jüngsten 24
        assertEquals(1_000 * h - 23 * h, Change24h.hourlyWindowStart(full + (1_000 * h - 30 * h)))
        // Doppelte Startzeit zählt einmal → nur 23 verschiedene Stunden
        assertNull(Change24h.hourlyWindowStart(full.dropLast(1) + full.first()))
        // 24 Kerzen, aber die drittjüngste fehlt (dafür eine ältere): unvollständig
        assertNull(Change24h.hourlyWindowStart(full.filterIndexed { i, _ -> i != 2 } + (1_000 * h - 24 * h)))
        assertNull(Change24h.hourlyWindowStart(full.take(23)))
        assertNull(Change24h.hourlyWindowStart(emptyList()))
    }
}
