package com.cryptochecker.app.contract

import com.cryptochecker.marketdata.config.MarketsConfig
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.market.*
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Market.key] steht in Datenbank, Einstellungen, Sicherungen und Widgets. Früher war er `javaClass.simpleName`;
 * heute setzt jede Börse ihn ausdrücklich. Die Liste unten hält die gespeicherten Werte fest — ausgeschrieben, nicht
 * aus dem Klassennamen abgeleitet, damit ein Umbenennen der Klasse hier auffällt statt still Daten zu verlieren.
 */
class MarketKeyTest {

    private val expected: List<Pair<Market, String>> = listOf(
        Coinbase() to "Coinbase",
        Kraken() to "Kraken",
        GateIo() to "GateIo",
        Binance() to "Binance",
        BinanceUs() to "BinanceUs",
        Kucoin() to "Kucoin",
        Okex() to "Okex",
        OkexFutures() to "OkexFutures",
        BinanceFutures() to "BinanceFutures",
        Bybit() to "Bybit",
        BybitFutures() to "BybitFutures",
        Bitget() to "Bitget",
        BitgetFutures() to "BitgetFutures",
        Mexc() to "Mexc",
        MexcFutures() to "MexcFutures",
        Htx() to "Htx",
        HtxFutures() to "HtxFutures",
        Bitfinex() to "Bitfinex",
        Bitstamp() to "Bitstamp",
        Bitvavo() to "Bitvavo",
        CryptoCom() to "CryptoCom",
        Gemini() to "Gemini",
        Hyperliquid() to "Hyperliquid",
        Woo() to "Woo",
        WooFutures() to "WooFutures",
        Deribit() to "Deribit",
        Phemex() to "Phemex",
        PhemexFutures() to "PhemexFutures",
        Poloniex() to "Poloniex",
        OneTrading() to "OneTrading",
        Upbit() to "Upbit",
        Bithumb() to "Bithumb",
        BitFlyer() to "BitFlyer",
        Btcturk() to "Btcturk",
        Bitso() to "Bitso",
        Indodax() to "Indodax",
        ZebPay() to "ZebPay",
        Bitbank() to "Bitbank",
        Coincheck() to "Coincheck",
        HashKey() to "HashKey",
        BtcMarkets() to "BtcMarkets",
        Aster() to "Aster",
        BingX() to "BingX",
        BingXFutures() to "BingXFutures",
        Paribu() to "Paribu",
        Bitexen() to "Bitexen",
        MercadoBitcoin() to "MercadoBitcoin",
        Luno() to "Luno",
        CoinDcx() to "CoinDcx",
        IndependentReserve() to "IndependentReserve",
        Latoken() to "Latoken",
        NonKyc() to "NonKyc",
        DexScreener() to "DexScreener",
        UnknownMarket() to "UnknownMarket",
    )

    @Test
    fun everyKeyEqualsTheLegacyClassName() {
        for ((market, key) in expected) {
            assertEquals(key, market.key)
            // Im Testlauf ist nichts verkleinert: der Klassenname ist der alte Schlüssel
            assertEquals(market.javaClass.simpleName, market.key)
        }
    }

    @Test
    fun configuredMarketsAreAllCovered() {
        val listed = expected.map { it.second }.toSet()
        assertEquals(MarketsConfig.MARKETS.keys - listed, emptySet<String>())
        for ((key, market) in MarketsConfig.MARKETS) assertEquals(key, market.key)
        assertEquals(expected.size, listed.size)
    }
}
