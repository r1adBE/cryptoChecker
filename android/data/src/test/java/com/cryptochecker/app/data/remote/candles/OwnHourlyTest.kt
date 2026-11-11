package com.cryptochecker.app.data.remote.candles

import com.cryptochecker.app.domain.activity.HourCandle
import com.cryptochecker.app.domain.watch.DayReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stundenkerzen der eigenen Börse (Mini-Chart, 24-h-Bezug). Wie `OwnHourlyTests.swift`. */
class OwnHourlyTest {

    private val hour = 3_600_000L

    private fun candles(n: Int) = (0 until n).map { i ->
        HourCandle(openTime = i * hour, open = 100.0 + i, high = 101.0 + i, low = 99.0 + i, close = 100.5 + i, volume = 1.0)
    }

    private fun at(vararg hours: Int) = hours.map { h ->
        HourCandle(openTime = h * hour, open = 100.0 + h, high = 101.0 + h, low = 99.0 + h, close = 100.5 + h, volume = 1.0)
    }

    @Test
    fun `series keeps newest 24 closes, opens of all candles`() {
        val s = OwnHourly.seriesOf(candles(26), "Kraken", 26 * hour)!!
        assertEquals(24, s.closes.size)
        assertEquals(102.5, s.closes.first(), 1e-9)
        assertEquals(125.5, s.closes.last(), 1e-9)
        // Bezug: Schluss der Kerze vor dem Fenster (1 h–2 h)
        assertEquals(DayReference(101.5, 125.5), s.day)
        assertEquals(125.5, s.lastClose, 1e-9)
        assertEquals(26, s.opens.size)
        assertEquals(100.0, s.opens[0L]!!, 1e-9)
        assertEquals("Kraken", s.provider)
    }

    @Test
    fun `series needs two valid candles`() {
        assertNull(OwnHourly.seriesOf(null, "Kraken", 26 * hour))
        assertNull(OwnHourly.seriesOf(candles(1), "Kraken", 26 * hour))
        val bad = candles(2).mapIndexed { i, c -> if (i == 0) c.copy(close = Double.NaN) else c }
        assertNull(OwnHourly.seriesOf(bad, "Kraken", 26 * hour))
    }

    @Test
    fun `rolling reference spans 24 hours, not 24 candles`() {
        // Nur Stunden mit Handel (Upbit): alte Kerzen 0–2 h, dann erst wieder 40–41 h; jetzt 42 h
        val gap = OwnHourly.seriesOf(at(0, 1, 2, 40, 41), "Upbit", 42 * hour)!!
        assertEquals(DayReference(102.5, 141.5), gap.day)
        assertEquals(5, gap.closes.size)
        // Laufende Stunde: Fenster ab 18.5 h, Kerze ab 18 h liegt davor
        assertEquals(DayReference(118.5, 141.5), OwnHourly.seriesOf(at(17, 18, 19, 41), "Upbit", 42 * hour + hour / 2)!!.day)
        // Keine Kerze vor dem Fenster, erste startet höchstens 1 h nach Fensterbeginn: ihre Eröffnung
        assertEquals(DayReference(127.0, 135.5), OwnHourly.seriesOf(at(27, 30, 35), "Upbit", 50 * hour + hour / 2)!!.day)
        // Erste Kerze erst 4 h nach Fensterbeginn: kein Bezug, Chart und letzter Schluss bleiben
        val late = OwnHourly.seriesOf(at(30, 31, 35), "Upbit", 50 * hour)!!
        assertNull(late.day)
        assertEquals(3, late.closes.size)
        assertEquals(135.5, late.lastClose, 1e-9)
        // Kein Handel seit über 24 h: letzter Kurs gilt an beiden Enden (0 %)
        assertEquals(DayReference(105.5, 105.5), OwnHourly.seriesOf(at(0, 5), "Upbit", 100 * hour)!!.day)
        // Kerze genau am Fensterbeginn gehört ins Fenster
        assertEquals(DayReference(101.5, 124.5), OwnHourly.rollingReference(at(1, 2, 24), 26 * hour))
    }

    @Test
    fun `fresh - ttl, failure ttl and the hour of the day start`() {
        val s = OwnHourly.seriesOf(candles(26), "OKX", 26 * hour)!!
        val ttl = OwnHourly.TTL_MILLIS
        assertTrue(OwnHourly.isFresh(1_000L, s, ttl, 1_000L + ttl - 1))
        assertFalse(OwnHourly.isFresh(1_000L, s, ttl, 1_000L + ttl))
        assertFalse(OwnHourly.isFresh(1_000L, s, ttl, 999L))
        // Fehlschlag: kürzer gemerkt
        assertTrue(OwnHourly.isFresh(0L, null, ttl, OwnHourly.FAILURE_TTL_MILLIS - 1))
        assertFalse(OwnHourly.isFresh(0L, null, ttl, OwnHourly.FAILURE_TTL_MILLIS))
        // Kerze des Tagesbeginns fehlt, Abruf kurz nach Beginn der Stunde: neu laden
        val missing = 30 * hour
        assertFalse(OwnHourly.isFresh(missing + 60_000L, s, ttl, missing + 120_000L, needsHour = missing))
        // Viel später abgerufen und trotzdem ohne sie: die Quelle liefert sie nicht — gilt bis zum Ablauf
        val late = missing + OwnHourly.NEW_CANDLE_GRACE_MILLIS
        assertTrue(OwnHourly.isFresh(late, s, ttl, late + 1_000L, needsHour = missing))
        // Enthalten: frisch
        assertTrue(OwnHourly.isFresh(25 * hour, s, ttl, 25 * hour + 1_000L, needsHour = 3 * hour))
    }

    @Test
    fun `cache key separates exchanges and contracts`() {
        val spot = CandlePair("Kraken", "Kraken", "BTC", "USD", "XXBTZUSD")
        val other = CandlePair("Bitstamp", "Bitstamp", "BTC", "USD", "btcusd")
        assertTrue(spot.cacheKey != other.cacheKey)
        assertEquals("Coinbase|BTC/USD|NONE", CandlePair("Coinbase", "Coinbase", "btc", "usd").cacheKey)
    }

    @Test
    fun `host of a candle url`() {
        assertEquals("api.kraken.com", OwnCandleDataSource.hostOf("https://api.kraken.com/0/public/OHLC?pair=XXBTZUSD"))
        assertEquals("www.okx.com", OwnCandleDataSource.hostOf("https://www.okx.com/api/v5/market/candles?instId=BTC-USDT"))
    }
}
