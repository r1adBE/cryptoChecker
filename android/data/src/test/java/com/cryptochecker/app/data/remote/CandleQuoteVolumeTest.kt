package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.activity.HourCandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Quote-Umsatz der Kerzen (Feld 7 der Binance-Kline) für die Volumen-Balken im Aktionsblatt. */
class CandleQuoteVolumeTest {

    @Test
    fun `binance - quote volume from field 7, missing field stays null`() {
        val json = """[
            [1000, "10", "12", "9", "11", "5.5", 1999, "60.5", 10, "1", "2", "0"],
            [2000, "11", "13", "10", "12", "2"]
        ]"""
        val candles = CandleDataSource.parseBinance(json)
        assertEquals(2, candles.size)
        assertEquals(60.5, candles[0].quoteVolume!!, 1e-9)
        assertEquals(5.5, candles[0].volume, 1e-9)
        assertNull(candles[1].quoteVolume)
    }

    @Test
    fun `aggregate - quote volume summed only when every part has it`() {
        val hour = 3_600_000L
        val full = (0 until 4).map { i ->
            HourCandle(i * hour, 1.0, 2.0, 0.5, 1.5, volume = 1.0, quoteVolume = 10.0)
        }
        val merged = CandleDataSource.aggregate(full, 4 * hour, 0L, 4)
        assertEquals(1, merged.size)
        assertEquals(40.0, merged[0].quoteVolume!!, 1e-9)
        assertEquals(4.0, merged[0].volume, 1e-9)

        val partial = full.mapIndexed { i, c -> if (i == 2) c.copy(quoteVolume = null) else c }
        assertNull(CandleDataSource.aggregate(partial, 4 * hour, 0L, 4)[0].quoteVolume)
    }
}
