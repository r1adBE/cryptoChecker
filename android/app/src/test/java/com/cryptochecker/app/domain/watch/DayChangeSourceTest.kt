package com.cryptochecker.app.domain.watch

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
        // eigene Börse oder ihr Futures-Markt: kein Hinweis
        assertNull(DayChange.foreignCandleSource(listOf("Binance"), "Binance"))
        assertNull(DayChange.foreignCandleSource(listOf("BinanceFutures", "Binance Futures"), "Binance"))
        assertNull(DayChange.foreignCandleSource(listOf("BinanceUS", "Binance.US"), "Binance.US"))
        assertNull(DayChange.foreignCandleSource(listOf("coinbase"), "Coinbase"))
        // keine Kerzen (Ticker) oder unbekannter Anbieter
        assertNull(DayChange.foreignCandleSource(listOf("Kraken"), null))
        assertNull(DayChange.foreignCandleSource(listOf("Kraken"), " "))
    }
}
