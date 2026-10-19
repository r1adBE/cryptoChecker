package com.cryptochecker.marketdata.config

import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.market.*

/**
 * Registry of all built-in exchanges in display order; the first one is the default for new pairs. A new adapter
 * is added here and in iOS `MarketsConfig.swift`, and its key in `MarketKeyTest` (DEVELOPMENT.md § 8).
 */
object MarketsConfig {

    /** All adapters by [Market.key], in display order. */
    val MARKETS: Map<String, Market> = listOf(
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
        Woo(),
        WooFutures(),
        Deribit(),
        Phemex(),
        PhemexFutures(),
        Poloniex(),
        OneTrading(),
        Upbit(),
        Bithumb(),
        BitFlyer(),
        Bitbank(),
        Coincheck(),
        Btcturk(),
        Paribu(),
        Bitexen(),
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
    ).associateBy(Market::key)
}
