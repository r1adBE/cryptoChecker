package com.cryptochecker.marketdata.util

import com.cryptochecker.marketdata.config.ExchangesConfig
import com.cryptochecker.marketdata.model.Exchange
import com.cryptochecker.marketdata.model.exchange.UnknownExchange

object ExchangesConfigUtils {

    // Ersatz für gespeicherte Schlüssel, die es nicht mehr gibt
    private val fallback: Exchange by lazy { UnknownExchange() }

    /** Börse zum gespeicherten Schlüssel ([Exchange.key]); unbekannt oder null → [UnknownExchange]. */
    fun getExchangeByKey(key: String?): Exchange =
        key?.let { ExchangesConfig.EXCHANGES[it] } ?: fallback
}
