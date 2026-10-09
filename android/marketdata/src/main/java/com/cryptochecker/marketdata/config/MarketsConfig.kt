package com.cryptochecker.marketdata.config

import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.market.*

object MarketsConfig {
    private val registeredMarkets = arrayOf(
        Coinbase(), // Default market
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
    )

    val MARKETS: Map<String, Market> = registeredMarkets.associateBy { it.key }
}
