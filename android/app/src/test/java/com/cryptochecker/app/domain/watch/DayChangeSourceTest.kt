package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Hinweis «Veränderung aus …-Kerzen»: nur wenn die Kerzen von einer anderen Börse kommen. */
class DayChangeSourceTest {

    @Test
    fun foreignOnlyForOtherExchange() {
        assertEquals("Binance", DayChange.foreignCandleSource(listOf("Kraken", "Kraken"), "Binance"))
        assertEquals("Coinbase", DayChange.foreignCandleSource(listOf("Bitstamp"), "Coinbase"))
        assertEquals("Binance.US", DayChange.foreignCandleSource(listOf("Binance"), "Binance.US"))
        // eigener Markt: kein Hinweis
        assertNull(DayChange.foreignCandleSource(listOf("Binance"), "Binance"))
        assertNull(DayChange.foreignCandleSource(listOf("BinanceFutures", "Binance Futures"), "Binance Futures"))
        // Spot und Futures derselben Börse sind verschiedene Märkte
        assertEquals("Binance Spot", DayChange.foreignCandleSource(listOf("BinanceFutures", "Binance Futures"), "Binance"))
        assertEquals("Binance Futures", DayChange.foreignCandleSource(listOf("Binance"), "Binance Futures"))
        assertNull(DayChange.foreignCandleSource(listOf("BinanceUS", "Binance.US"), "Binance.US"))
        assertNull(DayChange.foreignCandleSource(listOf("coinbase"), "Coinbase"))
        // keine Kerzen (Ticker) oder unbekannter Anbieter
        assertNull(DayChange.foreignCandleSource(listOf("Kraken"), null))
        assertNull(DayChange.foreignCandleSource(listOf("Kraken"), " "))
    }

    @Test
    fun usdtSeriesOfFiatPairIsAlwaysMarked() {
        // Binance BTC/EUR ohne 24-h-Wert, Pille aus Binance-USDT-Kerzen: andere Quote → Hinweis
        val name = DayChange.candleSourceName("Binance", DayChange.CandleSeries.USDT)
        assertEquals("Binance USDT", name)
        assertEquals("Binance USDT", DayChange.foreignCandleSource(listOf("binance", "Binance"), name))
        assertEquals("Kraken USDT", DayChange.foreignCandleSource(listOf("Binance"), DayChange.candleSourceName(" Kraken ", DayChange.CandleSeries.USDT)))
        // eigene Reihe: wie bisher nur bei fremder Börse
        assertNull(DayChange.foreignCandleSource(listOf("Binance"), DayChange.candleSourceName("Binance", DayChange.CandleSeries.PAIR)))
        assertNull(DayChange.candleSourceName("Binance", DayChange.CandleSeries.NONE))
        assertNull(DayChange.candleSourceName(null, DayChange.CandleSeries.USDT))
        assertNull(DayChange.candleSourceName(" ", DayChange.CandleSeries.USDT))
    }

    @Test
    fun seriesFollowsSelectOrder() {
        val ref = DayReference(100.0, 110.0)
        assertEquals(DayChange.CandleSeries.PAIR, DayChange.series(ref, ref, quoteIsFiat = true))
        assertEquals(DayChange.CandleSeries.USDT, DayChange.series(null, ref, quoteIsFiat = true))
        assertEquals(DayChange.CandleSeries.NONE, DayChange.series(null, ref, quoteIsFiat = false))
        assertEquals(DayChange.CandleSeries.NONE, DayChange.series(null, null, quoteIsFiat = true))
    }

    @Test
    fun usdtSeriesPriceGap() {
        val usdt = DayReference(50.0, 60.0)
        // CHF je USD 0.9 → letzter Schluss 54 CHF
        assertEquals(20.0, DayChange.fromUsdtSeries(54.0, usdt, 0.9, 0.9)!!, 1e-9)
        assertNull(DayChange.fromUsdtSeries(70.2, usdt, 0.9, 0.9))
        assertNull(DayChange.fromUsdtSeries(37.8, usdt, 0.9, 0.9))
        // Wechselkursbewegung zählt mit: 60 × 0.9 gegen 50 × 1.0 = +8 %
        assertEquals(8.0, DayChange.fromUsdtSeries(54.0, usdt, 0.9, 1.0)!!, 1e-9)
        // ohne Kurs an einem Ende: unbekannt, nie der USDT-Prozentwert
        assertNull(DayChange.fromUsdtSeries(5000.0, usdt, null, 0.9))
        assertNull(DayChange.fromUsdtSeries(54.0, usdt, 0.9, null))
        assertNull(DayChange.fromUsdtSeries(5000.0, usdt, Double.NaN, 0.9))
        assertNull(DayChange.fromUsdtSeries(54.0, null, 0.9, 0.9))
        assertNull(DayChange.select(70.2, null, usdt, quoteIsFiat = true, usdToQuote = 0.9, usdToQuoteAtOpen = 0.9))
    }

    @Test
    fun rowSourceNamesChartEvenWhenChangeComesFromAnotherExchange() {
        val k = DayChange.RowSourceKind.CHANGE
        // Phemex-Paar: nur der Chart fällt auf Binance zurück
        assertEquals(DayChange.RowSource(DayChange.RowSourceKind.CHART, "Binance"), DayChange.rowSource(null, "Binance"))
        assertEquals(DayChange.RowSource(DayChange.RowSourceKind.CHANGE_AND_CHART, "Binance"), DayChange.rowSource("Binance", "Binance"))
        // Änderung und Chart von zwei verschiedenen fremden Börsen: der Chart wird nicht verschwiegen
        assertEquals(DayChange.RowSource(k, "Coinbase", "Binance"), DayChange.rowSource("Coinbase", "Binance"))
        assertEquals(DayChange.RowSource(k, "Coinbase"), DayChange.rowSource("Coinbase", null))
        assertNull(DayChange.rowSource(null, null))
    }

    @Test
    fun rowChangeMarkerOnlyForShownForeignCandleValue() {
        // Gemeinsame Fälle mit iOS (DayChangeSourceTests.testRowChangeMarker)
        val cases = ParityJson.load("day_change.json")["rowChangeMarker"].list()
        check(cases.isNotEmpty())
        cases.forEach { raw ->
            val c = raw.obj()
            val actual = DayChange.rowChangeMarker(c["foreignSource"] as String?, c["shownChange"].num(), c["sinceLast"] as Boolean)
            assertEquals("rowChangeMarker: ${c["name"]}", c["expected"] as String?, actual)
        }
    }
}
