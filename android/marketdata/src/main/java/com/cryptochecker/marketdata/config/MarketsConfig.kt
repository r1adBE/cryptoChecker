package com.cryptochecker.marketdata.config

import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.market.*

/** Alle eingebauten Börsen in Anzeigereihenfolge; die erste ist die Vorgabe für neue Paare. */
object MarketsConfig {

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
