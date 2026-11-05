package com.cryptochecker.app.contract

import com.cryptochecker.marketdata.config.ExchangesConfig
import com.cryptochecker.marketdata.model.Exchange
import com.cryptochecker.marketdata.model.exchange.*
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Exchange.key] steht in Datenbank, Einstellungen, Sicherungen und Widgets. Jede Börse setzt ihn
 * ausdrücklich, nicht über `javaClass.simpleName`.
 * Die Liste unten hält die gespeicherten Werte fest — ausgeschrieben, nicht
 * aus dem Klassennamen abgeleitet, damit ein Umbenennen der Klasse hier auffällt statt still Daten zu verlieren.
 */
class ExchangeKeyTest {

    private val expected: List<Pair<Exchange, String>> = listOf(
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
        Lighter() to "Lighter",
        BingX() to "BingX",
        BingXFutures() to "BingXFutures",
        Paribu() to "Paribu",
        Icrypex() to "Icrypex",
        MercadoBitcoin() to "MercadoBitcoin",
        Luno() to "Luno",
        Bitpanda() to "Bitpanda",
        Bitkub() to "Bitkub",
        BinanceTh() to "BinanceTh",
        CoinsPh() to "CoinsPh",
        Coinone() to "Coinone",
        WhiteBit() to "WhiteBit",
        CoinSpot() to "CoinSpot",
        MaxMaicoin() to "MaxMaicoin",
        BitoPro() to "BitoPro",
        BinanceTr() to "BinanceTr",
        Foxbit() to "Foxbit",
        Bit2Me() to "Bit2Me",
        Valr() to "Valr",
        WazirX() to "WazirX",
        Quidax() to "Quidax",
        Buda() to "Buda",
        Swyftx() to "Swyftx",
        Tokocrypto() to "Tokocrypto",
        UpbitTh() to "UpbitTh",
        UpbitId() to "UpbitId",
        Ndax() to "Ndax",
        CoinJar() to "CoinJar",
        GmoCoin() to "GmoCoin",
        BitOasis() to "BitOasis",
        CoinDcx() to "CoinDcx",
        IndependentReserve() to "IndependentReserve",
        Latoken() to "Latoken",
        NonKyc() to "NonKyc",
        BitunixFutures() to "BitunixFutures",
        DexScreener() to "DexScreener",
        EcbReference() to "EcbReference",
        NbuReference() to "NbuReference",
        NbpReference() to "NbpReference",
        UnknownExchange() to "UnknownExchange",
    )

    @Test
    fun everyKeyEqualsTheClassName() {
        for ((exchange, key) in expected) {
            assertEquals(key, exchange.key)
            // Im Testlauf ist nichts verkleinert: der Schlüssel gleicht dem Klassennamen
            assertEquals(exchange.javaClass.simpleName, exchange.key)
        }
    }

    @Test
    fun configuredExchangesAreAllCovered() {
        val listed = expected.map { it.second }.toSet()
        assertEquals(ExchangesConfig.EXCHANGES.keys - listed, emptySet<String>())
        for ((key, exchange) in ExchangesConfig.EXCHANGES) assertEquals(key, exchange.key)
        assertEquals(expected.size, listed.size)
    }
}
