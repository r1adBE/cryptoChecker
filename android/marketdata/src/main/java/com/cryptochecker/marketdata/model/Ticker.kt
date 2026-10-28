package com.cryptochecker.marketdata.model

/**
 * Price data of one pair, filled by the adapter's ticker parsers. Numbers not delivered stay at [MISSING].
 */
interface Ticker {
    /** Last price (the mid price for [Exchange.priceIsMid]); required for a usable ticker. */
    var last: Double

    /** Best bid. */
    var bid: Double

    /** Best ask. */
    var ask: Double

    /** 24 h high. */
    var high: Double

    /** 24 h low. */
    var low: Double

    /** 24 h volume in the base currency. */
    var vol: Double

    /** 24 h volume in the quote currency. */
    var volQuote: Double

    /**
     * Parsers may write the exchange time here (s, ms, µs or ns); after [Exchange.readTicker] it holds the fetch
     * time in ms and the exchange time is in [exchangeTime].
     */
    var timestamp: Long

    /**
     * Time reported by the exchange in ms; null if it sends none (Kraken, Gate.io, Hyperliquid …). If the price
     * stays the same for long, the watchlist shows «price unchanged since …» (`PriceFreshness`).
     */
    var exchangeTime: Long?

    /**
     * Rolling 24 h change in percent (1.5 = +1.5 %) as reported in the exchange's ticker. null without a rolling
     * value — the app then computes it from hourly candles. Changes since midnight (UTC, KST …) do not belong
     * here; details in DEVELOPMENT.md, «24 h change».
     */
    var change24hPercent: Double?

    /**
     * Pool liquidity in USD — DEX pools only ([com.cryptochecker.marketdata.model.exchange.DexScreener]); null =
     * unknown or not a DEX source. A thin pool can be moved with small amounts, so no alarm fires then.
     */
    var liquidityUsd: Double?
        get() = null
        set(@Suppress("UNUSED_PARAMETER") value) {}

    /**
     * [last] is the mark price because the exchange sent no mid price (thin or empty order book, Hyperliquid). The
     * app labels such a price «Mark» and does not fire price alarms on it (the mark price is an index-based value,
     * not a tradable quote). Same as iOS `Ticker.markFallback`.
     */
    var markFallback: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") value) {}

    companion object {
        /** Placeholder for «not delivered» in the number fields (all price values are positive otherwise). Same as iOS `Ticker.missing`. */
        const val MISSING = -1.0
    }
}
