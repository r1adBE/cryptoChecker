package com.cryptochecker.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** Uhr-App: Antworten der drei Börsen, Zusammenführen und Zahlen (Listen: `WatchSettingsParityTest`). */
class WatchQuotesTest {

    private val wanted = listOf("BTC", "ETH", "SOL")

    @Test
    fun binance() {
        val body = """[{"symbol":"BTCUSDT","lastPrice":"67420.10","priceChangePercent":"1.234"},
            {"symbol":"ETHUSDT","lastPrice":"0","priceChangePercent":"2"},{"symbol":"XRPUSDT","lastPrice":"0.5","priceChangePercent":"1"}]"""
        val q = WatchQuotes.binance(body, wanted)
        assertEquals(listOf("BTC"), q.map { it.symbol })
        assertEquals(67420.10, q[0].price, 1e-9)
        assertEquals(1.234, q[0].change24h!!, 1e-9)
        assertEquals(WatchQuotes.BINANCE, q[0].source)
    }

    @Test
    fun bybitAndOkx() {
        val bybit = """{"result":{"list":[{"symbol":"ETHUSDT","lastPrice":"3000","price24hPcnt":"-0.0125"}]}}"""
        val e = WatchQuotes.bybit(bybit, wanted).single()
        assertEquals(-1.25, e.change24h!!, 1e-9)
        val okx = """{"data":[{"instId":"SOL-USDT","last":"110","open24h":"100"},{"instId":"SOL-USDC","last":"1","open24h":"1"}]}"""
        val s = WatchQuotes.okx(okx, wanted).single()
        assertEquals("SOL", s.symbol)
        assertEquals(10.0, s.change24h!!, 1e-9)
        assertNull(WatchQuotes.okx("""{"data":[{"instId":"BTC-USDT","last":"5","open24h":"0"}]}""", wanted).single().change24h)
    }

    @Test
    fun mergeKeepsTheFirstSource() {
        val a = listOf(WatchQuote("BTC", 1.0, null, "A"))
        val b = listOf(WatchQuote("BTC", 2.0, null, "B"), WatchQuote("ETH", 3.0, null, "B"))
        val merged = WatchQuotes.merge(a, b)
        assertEquals("A", merged.getValue("BTC").source)
        assertEquals("B", merged.getValue("ETH").source)
    }

    @Test
    fun formats() {
        assertEquals("67,420.10", WatchFormat.price(67420.1, Locale.US))
        assertEquals("1.50", WatchFormat.price(1.5, Locale.US))
        assertEquals("+1.23%", WatchFormat.change(1.234, Locale.US))
        assertEquals("−0.50%", WatchFormat.change(-0.5, Locale.US))
        assertEquals("0.00%", WatchFormat.change(0.001, Locale.US))
        assertEquals("—", WatchFormat.change(null, Locale.US))
    }
}
