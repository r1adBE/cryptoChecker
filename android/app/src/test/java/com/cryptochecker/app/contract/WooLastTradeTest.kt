package com.cryptochecker.app.contract

import com.cryptochecker.app.data.MutableTicker
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.market.Woo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * WOO X Spot: zweite Anfrage «letzter Abschluss» (echte Antwort vom 20.10.2026) ersetzt den Schluss der
 * Stundenkerze, behält die Eröffnung der Veränderung und setzt die Zeit der Börse. Gegenstück: `Tests/WooLastTradeTests.swift`.
 */
class WooLastTradeTest {

    private val market = Woo()
    private val info = PairQuery("BTC", "USDT", "SPOT_BTC_USDT", FuturesContractType.NONE)

    @Test
    fun secondRequestAsksForTheLastTrade() {
        assertEquals(2, market.tickerCallCount(info))
        assertEquals("https://api.woox.io/v1/public/market_trades?symbol=SPOT_BTC_USDT&limit=1", market.tickerAddress(1, info))
        assertFalse(market.priceIsHourlyClose)
    }

    @Test
    fun lastTradeReplacesTheCandleCloseAndKeepsTheOpening() {
        // Nach den Kerzen: Kurs 80 000, +10 % → Eröffnung 72 727,27
        val ticker = MutableTicker().apply { last = 80_000.0; change24hPercent = 10.0; exchangeTime = 1_792_533_600_000 }
        market.readTicker(1, REAL_TRADES, ticker, info)
        assertEquals(83_081.96, ticker.last, 0.0)
        assertEquals(1_792_534_030_272L, ticker.exchangeTime)
        val opening = 80_000.0 / 1.1
        assertEquals((83_081.96 - opening) / opening * 100, ticker.change24hPercent!!, 1e-9)
    }

    /** «inf» oder riesige Zeit: kein Absturz, die Zeit der Kerzen bleibt (der Kurs gilt trotzdem). */
    @Test
    fun unreadableTradeTimeKeepsTheCandleTime() {
        for (raw in listOf("inf", "1e300")) {
            val ticker = MutableTicker().apply { last = 80_000.0; exchangeTime = 1_792_533_600_000 }
            market.readTicker(1, """{"success":true,"rows":[{"executed_price":83081.96,"executed_timestamp":"$raw"}]}""", ticker, info)
            assertEquals(raw, 83_081.96, ticker.last, 0.0)
            assertEquals(raw, 1_792_533_600_000L, ticker.exchangeTime)
        }
    }

    @Test
    fun emptyTradesLeaveTheCandlePrice() {
        val ticker = MutableTicker().apply { last = 80_000.0; change24hPercent = 10.0 }
        assertThrows(Exception::class.java) { market.readTicker(1, """{"success":true,"rows":[]}""", ticker, info) }
        assertEquals(80_000.0, ticker.last, 0.0)
        assertEquals(10.0, ticker.change24hPercent!!, 0.0)
    }

    private companion object {
        const val REAL_TRADES = """{"success":true,"rows":[{"symbol":"SPOT_BTC_USDT","side":"SELL","executed_price":83081.96,"executed_quantity":0.174010,"executed_timestamp":"1792534030.272","source":0,"rpi":false}]}"""
    }
}
