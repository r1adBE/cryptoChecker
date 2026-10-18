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
        Btcturk(),
        Bitso(),
        Indodax(),
        ZebPay(),
        IndependentReserve(),
        Latoken(),
        NonKyc(),
        DexScreener(),
    ).associateBy(Market::key)
}
