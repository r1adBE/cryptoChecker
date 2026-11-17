package com.cryptochecker.app.contract

import com.cryptochecker.marketdata.config.ExchangesConfig
import com.cryptochecker.marketdata.model.Exchange
import com.cryptochecker.marketdata.model.exchange.Aster
import com.cryptochecker.marketdata.model.exchange.Binance
import com.cryptochecker.marketdata.model.exchange.BinanceFutures
import com.cryptochecker.marketdata.model.exchange.BinanceTh
import com.cryptochecker.marketdata.model.exchange.BinanceTr
import com.cryptochecker.marketdata.model.exchange.BinanceUs
import com.cryptochecker.marketdata.model.exchange.BingX
import com.cryptochecker.marketdata.model.exchange.BingXFutures
import com.cryptochecker.marketdata.model.exchange.Bit2Me
import com.cryptochecker.marketdata.model.exchange.BitFlyer
import com.cryptochecker.marketdata.model.exchange.BitOasis
import com.cryptochecker.marketdata.model.exchange.Bitbank
import com.cryptochecker.marketdata.model.exchange.Bitfinex
import com.cryptochecker.marketdata.model.exchange.Bitget
import com.cryptochecker.marketdata.model.exchange.BitgetFutures
import com.cryptochecker.marketdata.model.exchange.Bithumb
import com.cryptochecker.marketdata.model.exchange.Bitkub
import com.cryptochecker.marketdata.model.exchange.BitoPro
import com.cryptochecker.marketdata.model.exchange.Bitpanda
import com.cryptochecker.marketdata.model.exchange.Bitso
import com.cryptochecker.marketdata.model.exchange.Bitstamp
import com.cryptochecker.marketdata.model.exchange.BitunixFutures
import com.cryptochecker.marketdata.model.exchange.Bitvavo
import com.cryptochecker.marketdata.model.exchange.BtcMarkets
import com.cryptochecker.marketdata.model.exchange.Btcturk
import com.cryptochecker.marketdata.model.exchange.Buda
import com.cryptochecker.marketdata.model.exchange.Bybit
import com.cryptochecker.marketdata.model.exchange.BybitFutures
import com.cryptochecker.marketdata.model.exchange.CoinDcx
import com.cryptochecker.marketdata.model.exchange.CoinJar
import com.cryptochecker.marketdata.model.exchange.CoinSpot
import com.cryptochecker.marketdata.model.exchange.Coinbase
import com.cryptochecker.marketdata.model.exchange.Coincheck
import com.cryptochecker.marketdata.model.exchange.Coinone
import com.cryptochecker.marketdata.model.exchange.CoinsPh
import com.cryptochecker.marketdata.model.exchange.CryptoCom
import com.cryptochecker.marketdata.model.exchange.Deribit
import com.cryptochecker.marketdata.model.exchange.DexScreener
import com.cryptochecker.marketdata.model.exchange.EcbReference
import com.cryptochecker.marketdata.model.exchange.Foxbit
import com.cryptochecker.marketdata.model.exchange.GateIo
import com.cryptochecker.marketdata.model.exchange.Gemini
import com.cryptochecker.marketdata.model.exchange.GmoCoin
import com.cryptochecker.marketdata.model.exchange.HashKey
import com.cryptochecker.marketdata.model.exchange.Htx
import com.cryptochecker.marketdata.model.exchange.HtxFutures
import com.cryptochecker.marketdata.model.exchange.Hyperliquid
import com.cryptochecker.marketdata.model.exchange.Icrypex
import com.cryptochecker.marketdata.model.exchange.IndependentReserve
import com.cryptochecker.marketdata.model.exchange.Indodax
import com.cryptochecker.marketdata.model.exchange.Kraken
import com.cryptochecker.marketdata.model.exchange.Kucoin
import com.cryptochecker.marketdata.model.exchange.Latoken
import com.cryptochecker.marketdata.model.exchange.Lighter
import com.cryptochecker.marketdata.model.exchange.Luno
import com.cryptochecker.marketdata.model.exchange.MaxMaicoin
import com.cryptochecker.marketdata.model.exchange.MercadoBitcoin
import com.cryptochecker.marketdata.model.exchange.Mexc
import com.cryptochecker.marketdata.model.exchange.MexcFutures
import com.cryptochecker.marketdata.model.exchange.NbpReference
import com.cryptochecker.marketdata.model.exchange.NbuReference
import com.cryptochecker.marketdata.model.exchange.Ndax
import com.cryptochecker.marketdata.model.exchange.NonKyc
import com.cryptochecker.marketdata.model.exchange.Okex
import com.cryptochecker.marketdata.model.exchange.OkexFutures
import com.cryptochecker.marketdata.model.exchange.OneTrading
import com.cryptochecker.marketdata.model.exchange.Paribu
import com.cryptochecker.marketdata.model.exchange.Phemex
import com.cryptochecker.marketdata.model.exchange.PhemexFutures
import com.cryptochecker.marketdata.model.exchange.Poloniex
import com.cryptochecker.marketdata.model.exchange.Quidax
import com.cryptochecker.marketdata.model.exchange.Swyftx
import com.cryptochecker.marketdata.model.exchange.Tokocrypto
import com.cryptochecker.marketdata.model.exchange.UnknownExchange
import com.cryptochecker.marketdata.model.exchange.Upbit
import com.cryptochecker.marketdata.model.exchange.UpbitId
import com.cryptochecker.marketdata.model.exchange.UpbitTh
import com.cryptochecker.marketdata.model.exchange.Valr
import com.cryptochecker.marketdata.model.exchange.WazirX
import com.cryptochecker.marketdata.model.exchange.WhiteBit
import com.cryptochecker.marketdata.model.exchange.Woo
import com.cryptochecker.marketdata.model.exchange.WooFutures
import com.cryptochecker.marketdata.model.exchange.ZebPay
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
