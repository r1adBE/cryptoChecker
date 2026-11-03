package com.cryptochecker.app.contract

import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.exchange.Bitbank
import com.cryptochecker.marketdata.model.exchange.Bitkub
import com.cryptochecker.marketdata.model.exchange.BitoPro
import com.cryptochecker.marketdata.model.exchange.Bitpanda
import com.cryptochecker.marketdata.model.exchange.BtcMarkets
import com.cryptochecker.marketdata.model.exchange.CoinSpot
import com.cryptochecker.marketdata.model.exchange.Coincheck
import com.cryptochecker.marketdata.model.exchange.Coinone
import com.cryptochecker.marketdata.model.exchange.HashKey
import com.cryptochecker.marketdata.model.exchange.MaxMaicoin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Adressen von bitbank, Coincheck, HashKey Exchange und BTC Markets: Kennung aus der Paarliste oder aus Basis und
 * Gegenwährung gebildet, dazu die gefilterte Sammelabfrage von BTC Markets (je Markt ein «marketId»). Ab Build 187
 * auch Bitkub, Coinone (Pfad «KRW/BTC»), BitoPro und MAX (Auswahl je «markets[]»). Gegenstück:
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

    @Test
    fun build187AddressesUseStoredOrDerivedIds() {
        assertEquals(
            "https://api.bitkub.com/api/v3/market/ticker?sym=BTC_THB",
            Bitkub().tickerAddress(0, query("btc", "thb", null)),
        )
        assertEquals(
            "https://api.coinone.co.kr/public/v2/ticker_new/KRW/BTC",
            Coinone().tickerAddress(0, query("BTC", "KRW", null)),
        )
        assertEquals(
            "https://api.coinone.co.kr/public/v2/ticker_new/KRW/ETH",
            Coinone().tickerAddress(0, query("X", "Y", "ETH-KRW")),
        )
        assertEquals("https://api.bitopro.com/v3/tickers/btc_twd", BitoPro().tickerAddress(0, query("BTC", "TWD", null)))
        assertEquals(
            "https://max-api.maicoin.com/api/v3/ticker?market=btctwd",
            MaxMaicoin().tickerAddress(0, query("BTC", "TWD", null)),
        )
        // Gesamtlisten: die Adresse bleibt, die Kennung wählt die Zeile
        assertEquals("https://api.bitpanda.com/v1/ticker", Bitpanda().tickerAddress(0, query("BTC", "EUR", null)))
        assertEquals("https://www.coinspot.com.au/pubapi/v2/latest", CoinSpot().tickerAddress(0, query("XLM", "AUD", null)))
    }

    @Test
    fun maxBulkIsASortedSelection() {
        val exchange = MaxMaicoin()
        val ids = listOf("ethtwd", "btctwd", "btctwd", "", "BAD ID")
        assertEquals(
            "https://max-api.maicoin.com/api/v3/tickers?markets%5B%5D=btctwd&markets%5B%5D=ethtwd",
            exchange.getBulkTickersUrl(0, ids),
        )
        assertNull(exchange.getBulkTickersUrl(1, ids))
        assertNull(exchange.getBulkTickersUrl(0, listOf("BAD ID")))
        assertNull(exchange.getBulkTickersUrl(0))
        assertEquals("https://max-api.maicoin.com/api/v3/markets", exchange.getBulkKnownPairsUrl(0))
    }
}
