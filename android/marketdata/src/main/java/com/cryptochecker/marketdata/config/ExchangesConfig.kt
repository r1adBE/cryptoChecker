package com.cryptochecker.marketdata.config

import com.cryptochecker.marketdata.model.Exchange
import com.cryptochecker.marketdata.model.exchange.*

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
        Latoken(),
        NonKyc(),
        DexScreener(),
        EcbReference(),
    ).associateBy(Exchange::key)
}
