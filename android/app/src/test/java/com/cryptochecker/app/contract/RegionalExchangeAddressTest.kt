package com.cryptochecker.app.contract

import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.exchange.Bitbank
import com.cryptochecker.marketdata.model.exchange.BtcMarkets
import com.cryptochecker.marketdata.model.exchange.Coincheck
import com.cryptochecker.marketdata.model.exchange.HashKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Adressen von bitbank, Coincheck, HashKey Exchange und BTC Markets: Kennung aus der Paarliste oder aus Basis und
 * Gegenwährung gebildet, dazu die gefilterte Sammelabfrage von BTC Markets (je Markt ein «marketId»). Gegenstück:
 * `Tests/RegionalExchangeAddressTests.swift`.
 */
class RegionalExchangeAddressTest {

    private fun query(base: String, quote: String, id: String?) = PairQuery(base, quote, id, FuturesContractType.NONE)

    @Test
    fun tickerAddressesUseStoredOrDerivedIds() {
        assertEquals("https://public.bitbank.cc/btc_jpy/ticker", Bitbank().tickerAddress(0, query("BTC", "JPY", null)))
        assertEquals("https://public.bitbank.cc/eth_jpy/ticker", Bitbank().tickerAddress(0, query("X", "Y", "eth_jpy")))
        // Bitcoin Cash heisst bei bitbank «bcc»; angezeigt wird BCH
        assertEquals("https://public.bitbank.cc/bcc_jpy/ticker", Bitbank().tickerAddress(0, query("BCH", "JPY", null)))
        assertEquals(
            "https://coincheck.com/api/ticker?pair=btc_jpy",
            Coincheck().tickerAddress(0, query("BTC", "JPY", null)),
        )
        assertEquals(
            "https://api-pro.hashkey.com/quote/v1/ticker/24hr?symbol=BTCHKD",
            HashKey().tickerAddress(0, query("BTC", "HKD", null)),
        )
        assertEquals(
            "https://api.btcmarkets.net/v3/markets/BTC-AUD/ticker",
            BtcMarkets().tickerAddress(0, query("BTC", "AUD", null)),
        )
    }

    @Test
    fun btcMarketsBulkIsASortedSelection() {
        val exchange = BtcMarkets()
        val ids = listOf("XRP-AUD", "BTC-AUD", "BTC-AUD", "", "BAD ID")
        assertEquals(
            "https://api.btcmarkets.net/v3/markets/tickers?marketId=BTC-AUD&marketId=XRP-AUD",
            exchange.getBulkTickersUrl(0, ids),
        )
        // Nur eine Anfrage; ohne (brauchbare) Kennung und ungefiltert gibt es keine Sammelabfrage
        assertNull(exchange.getBulkTickersUrl(1, ids))
        assertNull(exchange.getBulkTickersUrl(0, listOf("BAD ID")))
        assertNull(exchange.getBulkTickersUrl(0, emptyList()))
        assertNull(exchange.getBulkTickersUrl(0))
        assertEquals("https://api.btcmarkets.net/v3/markets", exchange.getBulkKnownPairsUrl(0))
    }
}
