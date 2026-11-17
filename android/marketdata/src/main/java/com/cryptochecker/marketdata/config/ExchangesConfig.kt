package com.cryptochecker.marketdata.config

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
import com.cryptochecker.marketdata.model.exchange.Upbit
import com.cryptochecker.marketdata.model.exchange.UpbitId
import com.cryptochecker.marketdata.model.exchange.UpbitTh
import com.cryptochecker.marketdata.model.exchange.Valr
import com.cryptochecker.marketdata.model.exchange.WazirX
import com.cryptochecker.marketdata.model.exchange.WhiteBit
import com.cryptochecker.marketdata.model.exchange.Woo
import com.cryptochecker.marketdata.model.exchange.WooFutures
import com.cryptochecker.marketdata.model.exchange.ZebPay

/**
 * Registry of all built-in exchanges in display order; the first one is the default for new pairs. The exchange picker
 * groups them by [Exchange.category] and, once the volume ranking of the install date is stored, sorts each section
 * by volume (`ExchangeOrder`); without it this order applies. A new adapter
 * is added here and in iOS `ExchangesConfig.swift`, and its key in `ExchangeKeyTest` (DEVELOPMENT.md § 8).
 */
object ExchangesConfig {

    /** All adapters by [Exchange.key], in display order. */
    val EXCHANGES: Map<String, Exchange> = listOf(
        Coinbase(),
        Kraken(),
        GateIo(),
        Binance(),
        BinanceUs(),
        Kucoin(),
        Okex(),
        OkexFutures(),
        BinanceFutures(),
        Bybit(),
        BybitFutures(),
        Bitget(),
        BitgetFutures(),
        Mexc(),
        MexcFutures(),
        Htx(),
        HtxFutures(),
        Bitfinex(),
        Bitstamp(),
        Bitvavo(),
        CryptoCom(),
        Gemini(),
        Hyperliquid(),
        Aster(),
        BingX(),
        BingXFutures(),
        Lighter(),
        Woo(),
        WooFutures(),
        Deribit(),
        Phemex(),
        PhemexFutures(),
        BitunixFutures(),
        Poloniex(),
        OneTrading(),
        Upbit(),
        Bithumb(),
        BitFlyer(),
        Bitbank(),
        Coincheck(),
        Btcturk(),
        Paribu(),
        Icrypex(),
        Bitso(),
        MercadoBitcoin(),
        Indodax(),
        ZebPay(),
        CoinDcx(),
        HashKey(),
        IndependentReserve(),
        BtcMarkets(),
        Luno(),
        Bitpanda(),
        Bitkub(),
        BinanceTh(),
        CoinsPh(),
        Coinone(),
        WhiteBit(),
        CoinSpot(),
        MaxMaicoin(),
        BitoPro(),
        BinanceTr(),
        Foxbit(),
        Bit2Me(),
        Valr(),
        WazirX(),
        Quidax(),
        Buda(),
        Swyftx(),
        Tokocrypto(),
        UpbitTh(),
        UpbitId(),
        Ndax(),
        CoinJar(),
        GmoCoin(),
        BitOasis(),
        Latoken(),
        NonKyc(),
        DexScreener(),
        EcbReference(),
        NbuReference(),
        NbpReference(),
    ).associateBy(Exchange::key)
}
