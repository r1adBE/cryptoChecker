package com.cryptochecker.marketdata.util

import com.cryptochecker.marketdata.config.MarketsConfig
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.market.UnknownMarket

object MarketsConfigUtils {

    // Ersatz für gespeicherte Schlüssel, die es nicht mehr gibt
    private val fallback: Market by lazy { UnknownMarket() }

    /** Börse zum gespeicherten Schlüssel ([Market.key]); unbekannt oder null → [UnknownMarket]. */
    fun getMarketByKey(key: String?): Market =
        key?.let { MarketsConfig.MARKETS[it] } ?: fallback
}
